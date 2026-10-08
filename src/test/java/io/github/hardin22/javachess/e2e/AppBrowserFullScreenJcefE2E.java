package io.github.hardin22.javachess.e2e;

import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The browser opened, left and opened again over the app in full screen, as on the board's monitor: the real app is
 * started like {@code scripts/dev-run.sh} (full screen on {@code -De2e.screen=N}, default the last screen, e.g. the
 * 720x1920 monitor) with the {@code browser-cycle} demo, which opens the browser on a local page imitating
 * chess.com, goes Home, opens the lichess one, and so on ({@code -De2e.rounds}, default 5), then quits.
 *
 * <p>On 8 October 2026 this killed the app on macOS (at the first or second opening): AWT's exclusive full screen
 * over the app's JavaFX full-screen window made AppKit raise exceptions on the main thread. The app must end by
 * itself with status 0, every round must show the window, and macOS must not write a crash report.</p>
 *
 * <p>A child process because the app's own start-up order matters (JavaFX owns the macOS application, AWT and
 * Chromium join later). Opt-in ({@code -DskipJcefE2E=false -Dtest=AppBrowserFullScreenJcefE2E}): needs a display
 * and the JCEF bundle; it takes over the chosen screen for about a minute.</p>
 */
class AppBrowserFullScreenJcefE2E {

    @Test
    void openHomeAndOpenAgainOverTheFullScreenApp() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        int rounds = Integer.getInteger("e2e.rounds", 5);
        int screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length;
        int screen = Integer.getInteger("e2e.screen", screens - 1);
        Path home = Files.createTempDirectory("javachess-fullscreen");
        String page = AppBrowserFullScreenJcefE2E.class.getResource("/browser/e2e/board.html").toExternalForm();
        Set<String> crashesBefore = crashReports();

        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(List.of("--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-exports=java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"));
        cmd.add("-Djavachess.home=" + home);
        cmd.add("-Djavachess.legacyDir=" + Files.createDirectories(home.resolve("legacy")));
        cmd.add("-Djavachess.log.level=INFO"); // the class path has the tests' logging set-up (WARN by default)
        cmd.add("-Djavachess.screen=" + screen);
        cmd.add("-Djavachess.board=sim");
        cmd.add("-Djavachess.demo=browser-cycle");
        cmd.add("-Djavachess.demo.urls=" + page + "?site=chesscom," + page + "?site=lichess");
        cmd.add("-Djavachess.demo.rounds=" + rounds);
        cmd.add("-Djavachess.demo.openSeconds=6");
        cmd.add("-Djavachess.demo.homeSeconds=3");
        cmd.add("-Djavachess.snapshot.exit=true");
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"),
                "io.github.hardin22.javachess.Application.Main"));
        Path log = home.resolve("app.log");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean ended = p.waitFor(60L + rounds * 20L, TimeUnit.SECONDS);
        if (!ended) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        String output = Files.readString(log);
        Thread.sleep(3000); // the crash reporter writes its report a moment later
        Set<String> crashes = crashReports();
        crashes.removeAll(crashesBefore);
        assertEquals(Set.of(), crashes, "the app crashed\n" + tail(output));
        assertTrue(ended, "the app did not end\n" + tail(output));
        assertEquals(0, p.exitValue(), "exit status\n" + tail(output));
        assertTrue(output.contains("Browser cycle done: " + rounds + " rounds"), tail(output));
        for (int i = 1; i <= rounds; i++) {
            assertTrue(output.contains("Browser cycle round " + i + ": window showing true"),
                    "round " + i + " showed the browser\n" + tail(output));
        }
    }

    private static String tail(String output) {
        String[] lines = output.split("\n");
        return String.join("\n", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - 40), lines.length));
    }

    private static Set<String> crashReports() throws Exception {
        Path dir = Path.of(System.getProperty("user.home"), "Library", "Logs", "DiagnosticReports");
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") || !Files.isDirectory(dir)) {
            return new HashSet<>();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("java-"))
                    .collect(Collectors.toCollection(HashSet::new));
        }
    }
}
