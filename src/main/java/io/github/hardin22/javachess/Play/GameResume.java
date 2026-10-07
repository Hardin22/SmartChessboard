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

    /** The player does not want to resume it: it goes to the archive as interrupted (if not there yet). */
    public static void discard() {
        Optional<GameSnapshot> s = GameSnapshotStore.get().load();
        GameSnapshotStore.get().clear();
        s.ifPresent(snapshot -> io.github.hardin22.javachess.Utils.AppExecutors.storage().execute(
                () -> keepInArchive(snapshot, GameArchiveService.getInstance())));
    }

    /** True for the end-of-game messages that mean "interrupted" (left, app closed), not a result. */
    public static boolean isInterruption(String result) {
        String r = result == null ? "" : result.toLowerCase(Locale.ROOT);
        return r.contains("interrott") || r.contains("abort");
    }

    /**
     * Id of the archived "interrupted" copy of a game being resumed (same mode, start and moves, no result), or 0.
     * The resumed game deletes it only when it is archived again ({@code AbstractGame.saveGameToJson}), so a power
     * cut after resuming never leaves the game neither in the archive nor on disk.
     */
    public static int archivedInterruption(GameSnapshot s, GameArchiveService archive) {
        ArchivedGame.GameMode mode = s.mode() == GameSnapshot.Mode.PVC ? ArchivedGame.GameMode.PVC
                : ArchivedGame.GameMode.PVP;
        for (ArchivedGame g : archive.list()) {
            if (g.mode() == mode && "*".equals(g.result()) && g.movesUci().equals(s.moves())
                    && sameStart(g.initialFen(), s.initialFen())) {
                return g.id();
            }
        }
        return 0;
    }

    /**
     * Keeps a saved game that is about to be dropped (discarded, or replaced by a new game) in the archive as an
     * interrupted game, unless the archive already has it. Nothing played is ever lost.
     */
    public static void keepInArchive(GameSnapshot s, GameArchiveService archive) {
        if (s == null || s.moves().isEmpty()) {
            return;
        }
        for (ArchivedGame g : archive.list()) {
            if (g.movesUci().equals(s.moves()) && sameStart(g.initialFen(), s.initialFen())) {
                return; // already archived (interrupted or finished)
            }
        }
        String bot = s.botLevelId() == null ? null
                : BotLevels.byId(s.botLevelId()).map(BotLevels.Level::playerName).orElse(null);
        if (bot == null && s.mode() == GameSnapshot.Mode.PVC) {
            bot = s.botEngine() != null && s.botEngine().startsWith("MAIA_") ? "Maia " + s.botEngine().substring(5)
                    : "Stockfish livello " + s.skillLevel();
        }
        String white = s.mode() == GameSnapshot.Mode.PVP ? "Bianco" : s.humanWhite() ? "Giocatore" : bot;
        String black = s.mode() == GameSnapshot.Mode.PVP ? "Nero" : s.humanWhite() ? bot : "Giocatore";
        ArchivedGame.GameMode mode = s.mode() == GameSnapshot.Mode.PVC ? ArchivedGame.GameMode.PVC
                : ArchivedGame.GameMode.PVP;
        archive.add(new ArchivedGame(0, mode, s.mode() == GameSnapshot.Mode.PVC ? "Player vs " + bot
                : "Player vs Player", white, black, "*", "Interrotta", "", s.timeControl().archiveForm(),
                s.savedAt() != null ? s.savedAt() : java.time.LocalDateTime.now(), s.initialFen(), "", s.moves()));
        log.info("saved game kept in the archive as interrupted ({} moves)", s.moves().size());
    }

    private static boolean sameStart(String archived, String snapshot) {
        String a = archived == null || archived.isBlank() ? io.github.hardin22.javachess.Analysis.AnalysisTree.START_FEN
                : archived;
        String[] x = a.trim().split("\\s+");
        String[] y = snapshot.trim().split("\\s+");
        return x[0].equals(y[0]) && (x.length < 2 || y.length < 2 || x[1].equals(y[1]));
    }
}
