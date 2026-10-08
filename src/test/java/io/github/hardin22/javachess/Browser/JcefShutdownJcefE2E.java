package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Browser.trial.JcefExitProbe;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Chromium must never take the process down when it ends. Each case starts Chromium in a child JVM on the local
 * test page ({@link JcefExitProbe}) and leaves it the way the app does: quitting with the browser open, after
 * hiding the window, after closing the page. On macOS a crash leaves a report in
 * {@code ~/Library/Logs/DiagnosticReports} (and a dialog), which fails the test too.
 *
 * <p>History: jcefmaven's start-up registers a JVM shutdown hook that disposes Chromium; on macOS 27 that dispose
 * runs on the Swing thread and the system aborts the process ("Must only be used from the main thread"), now and
 * then (4 crashes in a night of test runs). {@link JcefRuntime} starts Chromium without that hook.</p>
 *
 * <p>Opt-in like {@link BrowserJcefE2E} ({@code -DskipJcefE2E=false}).</p>
 */
class JcefShutdownJcefE2E {

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    @Test
    void quittingWithTheBrowserOpen() throws Exception {
        runProbe("runtime");
    }

    @Test
    void quittingWithoutAnyShutdownCall() throws Exception {
        runProbe("exit"); // a test JVM or a library calling System.exit
    }

    @Test
    void quittingAfterHidingTheWindow() throws Exception {
        runProbe("hide"); // Home, then quit
    }

    @Test
    void quittingAfterClosingThePage() throws Exception {
        runProbe("close");
    }

    private static void runProbe(String mode) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
        Set<String> before = crashReports();
        Path home = Files.createTempDirectory("javachess-exit-probe");
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(List.of("--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-exports=java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"));
        cmd.add("-Djavachess.home=" + home);
        cmd.add("-Dapple.awt.UIElement=true"); // no Dock icon, no "reopen windows?" alert after an old crash
        String jcefDir = System.getProperty("javachess.jcef.dir");
        if (jcefDir != null) {
            cmd.add("-Djavachess.jcef.dir=" + jcefDir);
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), JcefExitProbe.class.getName(), mode));
        File log = home.resolve("probe.log").toFile();
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
        boolean ended = p.waitFor(120, TimeUnit.SECONDS);
        if (!ended) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        String output = Files.readString(log.toPath());
        assertTrue(ended, mode + ": the process did not end\n" + output);
        Thread.sleep(MAC ? 3000 : 0); // the crash reporter writes its report a moment later
        Set<String> crashes = crashReports();
        crashes.removeAll(before);
        assertEquals(Set.of(), crashes, mode + ": Chromium crashed the process\n" + output);
        assertEquals(0, p.exitValue(), mode + ": exit status (3 = the page was not read)\n" + output);
    }

    /** The JVM crash reports of macOS (none elsewhere). */
    private static Set<String> crashReports() throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), "Library", "Logs", "DiagnosticReports");
        if (!MAC || !Files.isDirectory(dir)) {
            return new java.util.HashSet<>();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("java-"))
                    .collect(Collectors.toCollection(java.util.HashSet::new));
        }
    }
}
