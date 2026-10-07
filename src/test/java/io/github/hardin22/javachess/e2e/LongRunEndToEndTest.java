package io.github.hardin22.javachess.e2e;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Controllers.ActiveGameController;
import io.github.hardin22.javachess.Services.BoardStateManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static io.github.hardin22.javachess.e2e.E2eHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Long run: {@code javachess.e2e.longrun.games} (default 20) games against the bot in a row on the simulated
 * board, the "human" playing random legal moves on the sensors and reproducing the bot's moves. Looks for what a
 * day of use on the Raspberry Pi would accumulate: threads, engine processes, heap after GC, board state.
 */
class LongRunEndToEndTest {

    private static final int MAX_PLIES = 120;
    private static E2eHarness app;

    @BeforeAll
    static void startApp() throws Exception {
        app = E2eHarness.start("sim");
        boardState().setTimings(15, 300, 150);
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) {
            app.stop();
        }
    }

    private record Usage(int threads, int nonDaemon, long processes, long heapMb, TreeMap<String, Long> byName) {
        static Usage now() throws InterruptedException {
            clearSoftReferences();
            for (int i = 0; i < 3; i++) {
                System.gc();
                Thread.sleep(200);
            }
            var threads = Thread.getAllStackTraces().keySet();
            TreeMap<String, Long> byName = threads.stream().collect(Collectors.groupingBy(
                    t -> t.getName().replaceAll("[-#]?\\d+$", ""), TreeMap::new, Collectors.counting()));
            long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024);
            return new Usage(threads.size(), (int) threads.stream().filter(t -> !t.isDaemon()).count(),
                    ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count(), heap, byName);
        }
    }

    /**
     * JavaFX keeps render images in softly referenced pools; with the software pipeline (Raspberry Pi, headless CI)
     * they are Java arrays and fill the heap until memory gets tight, which is not a leak. An allocation larger than
     * the heap makes the JVM clear every soft reference first (and then fail), so only what is really held remains.
     */
    private static boolean clearSoftReferences() {
        if (!canClearSoftReferences()) {
            return false;
        }
        try {
            long[] tooBig = new long[(int) (Runtime.getRuntime().maxMemory() / 8 + 1024)];
            tooBig[0] = 1;
        } catch (OutOfMemoryError expected) {
            // soft references were cleared before this error
        }
        return true;
    }

    private static boolean canClearSoftReferences() {
        if (Runtime.getRuntime().maxMemory() / 8 + 1024 >= Integer.MAX_VALUE - 16) {
            return false; // the request could succeed on a huge heap
        }
        // run_pi.sh flags (-XX:+ExitOnOutOfMemoryError): the JVM would quit. That run checks there is no OOM with the
        // Pi's 512 MB heap; the heap after GC then includes the soft caches and is only reported.
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .noneMatch(a -> a.contains("OnOutOfMemoryError"));
    }

    @Test
    void twentyGamesInARowLeaveNoThreadsProcessesOrMemoryBehind() throws Exception {
        int games = Integer.getInteger("javachess.e2e.longrun.games", 20);
        Random random = new Random(11);
        app.bot(); // empty script: the scripted engine plays the best one-ply material move
        int archivedBefore = archive().size();
        Usage baseline = null;
        int finished = 0;
        long start = System.currentTimeMillis();
        for (int g = 0; g < games; g++) {
            if (g == 2) {
                baseline = Usage.now(); // after the first games: views, engines and pools are warm
                histogram("baseline");
            }
            sim().setOccupancy(0xFFFF_0000_0000_FFFFL);
            ActiveGameController game = app.startPvc(g % 2 == 0);
            if (playUntilTheEnd(game, random)) {
                finished++;
            }
            fx(() -> {
                app.main.navigateTo("HOME"); // unfinished games are archived as interrupted
                return null;
            });
            waitForMode(BoardStateManager.Mode.IDLE);
        }
        long seconds = (System.currentTimeMillis() - start) / 1000;
        waitFor("every game archived", () -> archive().size() == archivedBefore + games);
        Usage after = Usage.now();
        histogram("after");
        String report = String.format("%d games (%d finished) in %d s; threads %d -> %d, non-daemon %d -> %d, "
                        + "child processes %d -> %d, heap after GC %d MB -> %d MB%nthreads before %s%nthreads after  %s",
                games, finished, seconds, baseline.threads(), after.threads(), baseline.nonDaemon(), after.nonDaemon(),
                baseline.processes(), after.processes(), baseline.heapMb(), after.heapMb(), baseline.byName(),
                after.byName());
        System.out.println("[long run] " + report);
        assertTrue(after.threads() - baseline.threads() <= 6, "threads grow: " + report);
        assertTrue(after.nonDaemon() <= baseline.nonDaemon(), "non-daemon threads grow: " + report);
        assertTrue(after.processes() <= baseline.processes(), "engine processes pile up: " + report);
        if (canClearSoftReferences()) {
            assertTrue(after.heapMb() - baseline.heapMb() <= 48, "heap grows: " + report);
        }
    }

    /** With {@code -De2e.longrun.histogram=true}: the 40 biggest classes on the heap (jcmd), to find a leak. */
    private static void histogram(String when) {
        if (!Boolean.getBoolean("e2e.longrun.histogram")) {
            return;
        }
        try {
            Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(),
                    String.valueOf(ProcessHandle.current().pid()), "GC.class_histogram").redirectErrorStream(true).start();
            List<String> lines = new String(p.getInputStream().readAllBytes()).lines().limit(44).toList();
            Object cache = field(io.github.hardin22.javachess.Utils.ImageCache.getInstance(), "cache");
            System.out.println("[long run] image cache entries " + when + ": " + ((java.util.Map<?, ?>) cache).size());
            System.out.println("[long run] heap histogram " + when + ":\n" + String.join("\n", lines));
        } catch (Exception e) {
            System.out.println("[long run] no histogram: " + e);
        }
    }

    /** Plays random moves on the board until mate/draw or {@link #MAX_PLIES}; true when the game ended. */
    private static boolean playUntilTheEnd(ActiveGameController game, Random random) throws Exception {
        while (plies(game) < MAX_PLIES) {
            waitFor("next step of the game", () -> over(game) || boardState().mode() == BoardStateManager.Mode.REPLICATE
                    || (boardState().mode() == BoardStateManager.Mode.PLAY && fxGet(() -> game(game).isAwaitingHumanMove())));
            if (boardState().mode() == BoardStateManager.Mode.REPLICATE) {
                reproduceLastMove(game); // also the bot's mating move
                continue;
            }
            if (over(game)) {
                return true;
            }
            Board logical = new Board();
            logical.loadFromFen(boardState().logicalFen());
            List<Move> legal = logical.legalMoves();
            List<Move> captures = legal.stream().filter(m -> logical.getPiece(m.getTo()) != Piece.NONE).toList();
            List<Move> pool = !captures.isEmpty() && random.nextBoolean() ? captures : legal;
            Move move = pool.get(random.nextInt(pool.size()));
            int before = plies(game);
            physicalMove(move.toString());
            waitFor("move " + move + " taken", () -> plies(game) > before);
        }
        return over(game);
    }

    private static boolean over(ActiveGameController game) {
        return fxGet(() -> {
            Board b = game(game).getBoard();
            return b.isMated() || b.isDraw();
        });
    }
}
