package io.github.hardin22.javachess.Browser;

import me.friwi.jcefmaven.UnsupportedPlatformException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Start-up decisions that do not need Chromium: bundle location, preload check, failure classification. */
class JcefRuntimeTest {

    @Test
    void oneBundleFolderPerVersion() {
        assertEquals("146.0.10", JcefRuntime.cefVersionOf("jcef-d3de827+cef-146.0.10+g8219561+chromium-146.0.7680.179"));
        assertEquals("unknown", JcefRuntime.cefVersionOf(null));
        assertEquals(JcefRuntime.cefVersion(), JcefRuntime.cefVersionOf(
                "cef-" + JcefRuntime.cefVersion()), "the version of the jar on the class path is known");
        assertTrue(JcefRuntime.installDir().getFileName().toString().startsWith(".jcef-bundle-"));
        String old = System.getProperty("javachess.jcef.dir");
        try {
            System.setProperty("javachess.jcef.dir", "/tmp/custom-bundle");
            assertEquals(Path.of("/tmp/custom-bundle"), JcefRuntime.installDir());
        } finally {
            if (old == null) {
                System.clearProperty("javachess.jcef.dir");
            } else {
                System.setProperty("javachess.jcef.dir", old);
            }
        }
    }

    @Test
    void findsThePreloadedLibcef() {
        assertEquals("/home/pi/.jcef-bundle-146.0.10/libcef.so", JcefRuntime.libcefIn(List.of(
                "aaaa-bbbb r-xp 00000000 08:02 123 /usr/lib/aarch64-linux-gnu/libc.so.6",
                "cccc-dddd r-xp 00000000 08:02 456   /home/pi/.jcef-bundle-146.0.10/libcef.so")));
        assertNull(JcefRuntime.libcefIn(List.of("aaaa-bbbb r-xp 00000000 08:02 123 /usr/lib/libc.so.6")));
    }

    @Test
    void classifiesStartUpFailures() {
        assertEquals(BrowserSession.Failure.NO_NETWORK,
                JcefRuntime.classify(new IOException("wrap", new UnknownHostException("github.com"))));
        assertEquals(BrowserSession.Failure.NO_NETWORK, JcefRuntime.classify(new IOException("Download failed")));
        assertEquals(BrowserSession.Failure.UNSUPPORTED,
                JcefRuntime.classify(new UnsupportedPlatformException("linux", "riscv")));
        assertEquals(BrowserSession.Failure.NO_SPACE, JcefRuntime.classify(new IOException("No space left on device")));
        assertEquals(BrowserSession.Failure.MISSING_OPTIONS, JcefRuntime.classify(new IllegalAccessError(
                "class org.cef.browser.mac.CefBrowserWindowMac cannot access class sun.lwawt.LWComponentPeer")));
        assertEquals(BrowserSession.Failure.OTHER, JcefRuntime.classify(new RuntimeException("boom")));
    }

    @Test
    void restartIsRecognised() {
        assertTrue(JcefRuntime.isRestartRequired(new JcefRuntime.RestartRequiredException("preload")));
        assertTrue(JcefRuntime.isRestartRequired(new RuntimeException("x",
                new UnsatisfiedLinkError("libcef.so: cannot allocate memory in static TLS block"))));
        assertFalse(JcefRuntime.isRestartRequired(new RuntimeException("other")));
    }

    @Test
    void theGpuIsOffByDefaultEverywhere() {
        // on macOS Chromium's GPU in the app's process crashed JavaFX's OpenGL renderer (8 October 2026)
        assertFalse(JcefRuntime.useGpu(null, true));
        assertFalse(JcefRuntime.useGpu(null, false));
        assertFalse(JcefRuntime.useGpu("false", true));
        assertTrue(JcefRuntime.useGpu("true", false));
        assertFalse(JcefRuntime.chromiumArgs(true).contains("--disable-gpu"), "only when asked for");
        assertTrue(JcefRuntime.chromiumArgs(false).containsAll(List.of("--disable-gpu", "--disable-gpu-compositing")));
        assertTrue(JcefRuntime.chromiumArgs(true).contains("--no-sandbox"));
    }
}
