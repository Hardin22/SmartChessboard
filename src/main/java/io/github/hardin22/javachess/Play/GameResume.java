package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Services.GameArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

/**
 * Resuming a saved game: what the home screen offers, and the clean-up that keeps the archive right (a game closed
 * half-way is archived as interrupted; resuming it removes that copy, so the finished game is archived once).
 */
public final class GameResume {

    private static final Logger log = LoggerFactory.getLogger(GameResume.class);

    private GameResume() {
    }

    /**
     * The game that can be resumed, if any: consistent moves and a position that is not already over. Reads a small
     * file: call it off the JavaFX thread when possible.
     */
    public static Optional<GameSnapshot> available() {
        return GameSnapshotStore.get().load().filter(GameResume::isResumable);
    }

    /** True for a saved game whose position still has legal moves. */
    public static boolean isResumable(GameSnapshot s) {
        try {
            com.github.bhlangonijr.chesslib.Board b = new com.github.bhlangonijr.chesslib.Board();
            b.loadFromFen(s.currentFen());
            return s.isConsistent() && !b.legalMoves().isEmpty() && !b.isDraw();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The player does not want to resume it. */
    public static void discard() {
        GameSnapshotStore.get().clear();
    }

    /** True for the end-of-game messages that mean "interrupted" (left, app closed), not a result. */
    public static boolean isInterruption(String result) {
        String r = result == null ? "" : result.toLowerCase(Locale.ROOT);
        return r.contains("interrott") || r.contains("abort");
    }

    /**
     * Removes the archived "interrupted" copy of a game being resumed (same start, same moves, no result), so the
     * game is archived once when it ends. Runs on the storage thread.
     */
    public static void forgetArchivedInterruption(GameSnapshot s) {
        io.github.hardin22.javachess.Utils.AppExecutors.storage().execute(() -> forgetArchivedInterruption(s,
                GameArchiveService.getInstance()));
    }

    static void forgetArchivedInterruption(GameSnapshot s, GameArchiveService archive) {
        ArchivedGame.GameMode mode = s.mode() == GameSnapshot.Mode.PVC ? ArchivedGame.GameMode.PVC
                : ArchivedGame.GameMode.PVP;
        for (ArchivedGame g : archive.list()) {
            if (g.mode() == mode && "*".equals(g.result()) && g.movesUci().equals(s.moves())
                    && sameStart(g.initialFen(), s.initialFen())) {
                archive.delete(g.id());
                log.info("resumed game: removed its interrupted copy from the archive (id {})", g.id());
                return;
            }
        }
    }

    private static boolean sameStart(String archived, String snapshot) {
        String a = archived == null || archived.isBlank() ? io.github.hardin22.javachess.Analysis.AnalysisTree.START_FEN
                : archived;
        String[] x = a.trim().split("\\s+");
        String[] y = snapshot.trim().split("\\s+");
        return x[0].equals(y[0]) && (x.length < 2 || y.length < 2 || x[1].equals(y[1]));
    }
}
