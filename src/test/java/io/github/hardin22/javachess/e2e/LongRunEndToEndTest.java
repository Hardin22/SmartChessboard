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
    /** After each game, a visit of the other screens (review with a full analysis started and left, statistics...). */
    private static final boolean TOUR = !"false".equals(System.getProperty("javachess.e2e.longrun.tour"));
    /** Every registered screen but the browser and the game itself (new screens included), then the home. */
    private static List<String> tourScreens() throws Exception {
        java.lang.reflect.Field registry = io.github.hardin22.javachess.Controllers.MainController.class
                .getDeclaredField("VIEWS");
        registry.setAccessible(true);
        List<String> screens = new java.util.ArrayList<>(((java.util.Map<?, ?>) registry.get(null)).keySet().stream()
                .map(String::valueOf).filter(v -> !v.equals("BROWSER") && !v.equals("GAME") && !v.equals("HOME"))
                .toList());
        screens.add("HOME");
        return screens;
    }
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

    /**
     * @param threads app threads, without the pool workers that come and go with the load ({@code poolWorkers})
     */
    private record Usage(int threads, int poolWorkers, int nonDaemon, long processes, long heapMb,
                         TreeMap<String, Long> byName) {
        static Usage now() throws InterruptedException {
            long heap = Long.MAX_VALUE;
            for (int sample = 0; sample < 3; sample++) { // lowest of three: a GC under load can leave garbage behind
                clearSoftReferences();
                for (int i = 0; i < 3; i++) {
                    System.gc();
                    Thread.sleep(200);
                }
                heap = Math.min(heap, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1024 * 1024));
            }
            var threads = Thread.getAllStackTraces().keySet();
            TreeMap<String, Long> byName = threads.stream().collect(Collectors.groupingBy(
                    t -> t.getName().replaceAll("[-#]?\\d+$", ""), TreeMap::new, Collectors.counting()));
            int pool = (int) threads.stream().filter(t -> t.getName().matches("ForkJoinPool.*-worker-\\d+")).count();
            return new Usage(threads.size() - pool, pool, (int) threads.stream().filter(t -> !t.isDaemon()).count(),
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
        java.util.Map<String, Long> baselineHistogram = java.util.Map.of();
        FxLatencyProbe probe = new FxLatencyProbe();
        int finished = 0;
        long start = System.currentTimeMillis();
        for (int g = 0; g < games; g++) {
            if (g == 2) {
                baseline = Usage.now(); // after the first games: views, engines and pools are warm
                histogram("baseline");
                baselineHistogram = classHistogram();
                probe.start();
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
            if (TOUR) {
                tourOfTheOtherScreens(archivedBefore + g + 1);
            }
        }
        long seconds = (System.currentTimeMillis() - start) / 1000;
        probe.stop();
        waitFor("every game archived", () -> archive().size() == archivedBefore + games);
        Usage after = Usage.now();
        histogram("after");
        String report = String.format("%d games (%d finished, tour " + TOUR + ") in %d s; threads %d -> %d (+ pool workers "
                        + baseline.poolWorkers() + " -> " + after.poolWorkers() + "), non-daemon %d -> %d, "
                        + "child processes %d -> %d, heap after GC %d MB -> %d MB; FX thread latency %s%n"
                        + "threads before %s%nthreads after  %s",
                games, finished, seconds, baseline.threads(), after.threads(), baseline.nonDaemon(), after.nonDaemon(),
                baseline.processes(), after.processes(), baseline.heapMb(), after.heapMb(), probe.summary(),
                baseline.byName(), after.byName());
        System.out.println("[long run] " + report);
        assertTrue(after.threads() - baseline.threads() <= 6, "threads grow: " + report);
        // pool workers come and go with the work (and are bounded by the pools' parallelism)
        assertTrue(after.poolWorkers() <= Runtime.getRuntime().availableProcessors() * 2 + 2, "pool workers: " + report);
        assertTrue(after.nonDaemon() <= baseline.nonDaemon(), "non-daemon threads grow: " + report);
        assertTrue(after.processes() <= baseline.processes(), "engine processes pile up: " + report);
        assertTrue(probe.maxMillis() < scaled(2000), "the FX thread froze: " + report);
        if (canClearSoftReferences() && after.heapMb() - baseline.heapMb() > 48) {
            Thread.sleep(5000); // a loaded (swapping) machine: measure once more before calling it a leak
            Usage again = Usage.now();
            assertTrue(again.heapMb() - baseline.heapMb() <= 48, "heap grows: " + report + "\nmeasured again: "
                    + again.heapMb() + " MB\nbiggest growth by class:\n" + histogramGrowth(baselineHistogram)
                    + "\n" + diagnostics());
        }
    }

    /**
     * How long a task posted to the FX thread waits before it runs, sampled every 50 ms while games are played: a
     * long wait is a frozen screen (I/O, an engine call or heavy work on the FX thread).
     */
    private static final class FxLatencyProbe {
        private final List<Long> samples = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile boolean running;
        private Thread thread;

        void start() {
            running = true;
            thread = Thread.ofPlatform().daemon().name("fx-latency-probe").start(() -> {
                while (running) {
                    long posted = System.nanoTime();
                    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                    javafx.application.Platform.runLater(() -> {
                        samples.add((System.nanoTime() - posted) / 1_000_000);
                        done.countDown();
                    });
                    try {
                        done.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        }

        void stop() throws InterruptedException {
            running = false;
            if (thread != null) {
                thread.join(11_000);
            }
        }

        long maxMillis() {
            return samples.stream().mapToLong(Long::longValue).max().orElse(0);
        }

        String summary() {
            List<Long> sorted = samples.stream().sorted().toList();
            if (sorted.isEmpty()) {
                return "n/a";
            }
            return "max " + sorted.get(sorted.size() - 1) + " ms, p99 " + sorted.get((int) (sorted.size() * 0.99))
                    + " ms, median " + sorted.get(sorted.size() / 2) + " ms (" + sorted.size() + " samples)";
        }
    }

    /** Bytes per class on the heap now (jcmd GC.class_histogram, live objects only). */
    private static java.util.Map<String, Long> classHistogram() {
        java.util.Map<String, Long> bytes = new java.util.HashMap<>();
        try {
            Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(),
                    String.valueOf(ProcessHandle.current().pid()), "GC.class_histogram").redirectErrorStream(true).start();
            for (String line : new String(p.getInputStream().readAllBytes()).lines().toList()) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s*\\d+:\\s+\\d+\\s+(\\d+)\\s+(\\S+).*")
                        .matcher(line);
                if (m.matches()) {
                    bytes.put(m.group(2), Long.parseLong(m.group(1)));
                }
            }
        } catch (Exception e) {
            bytes.put("(no histogram: " + e + ")", 0L);
        }
        return bytes;
    }

    /** The 12 classes whose live bytes grew most since {@code before}. */
    private static String histogramGrowth(java.util.Map<String, Long> before) {
        java.util.Map<String, Long> now = classHistogram();
        return now.entrySet().stream()
                .map(e -> java.util.Map.entry(e.getKey(), e.getValue() - before.getOrDefault(e.getKey(), 0L)))
                .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed()).limit(12)
                .map(e -> String.format("  %+,d B  %s", e.getValue(), e.getKey()))
                .collect(Collectors.joining("\n"));
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

    /**
     * What a player does between games: reviews the game just played (the full analysis starts and is left half way,
     * which must close its engines), then looks at the statistics, the archive, the mistake trainer, the puzzles,
     * the settings and the themes.
     */
    private static void tourOfTheOtherScreens(int archived) throws Exception {
        waitFor("game archived", () -> archive().size() >= archived);
        io.github.hardin22.javachess.Oggetti.ArchivedGame last = archive().list().stream()
                .max(java.util.Comparator.comparingInt(io.github.hardin22.javachess.Oggetti.ArchivedGame::id))
                .orElseThrow();
        fx(() -> {
            io.github.hardin22.javachess.Controllers.ReviewController.open(app.main, last);
            return null;
        });
        io.github.hardin22.javachess.Controllers.ReviewController review =
                (io.github.hardin22.javachess.Controllers.ReviewController) fxGet(() -> app.main.getController("REVIEW"));
        fx(() -> {
            review.goTo(Math.min(8, last.movesUci().size()));
            review.analyze();
            return null;
        });
        Thread.sleep(400);
        for (String screen : tourScreens()) {
            fx(() -> {
                app.main.navigateTo(screen);
                return null;
            });
            Thread.sleep(80);
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
