package io.github.hardin22.javachess.Browser.trial;

import io.github.hardin22.javachess.Browser.BoardProbe;
import io.github.hardin22.javachess.Browser.BrowserWindow;
import io.github.hardin22.javachess.Browser.CdpPageDriver;
import io.github.hardin22.javachess.Browser.JcefRuntime;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.util.concurrent.TimeUnit;

/**
 * Developer tool: starts Chromium on the local test page and leaves the process in one of several ways, to find
 * which shutdown paths make Chromium abort on macOS (see docs/browser.md, "Shutting Chromium down").
 *
 * <p>Used by {@code JcefShutdownJcefE2E}; by hand: {@code java ... JcefExitProbe <mode>}: {@code exit} (System.exit, nothing closed), {@code hide} (frame hidden
 * first), {@code close} (browser closed and client disposed, CefApp kept), {@code dispose} (CefApp.dispose, the
 * path that aborted on macOS 27), {@code runtime} (JcefRuntime.shutdown, the app's path). Exit status 0 when the
 * page was read, 3 when it was not.</p>
 */
public final class JcefExitProbe {

    private JcefExitProbe() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "runtime";
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.out.println("[exit-probe] uncaught in " + t.getName() + ": " + e.getClass().getName());
            for (Throwable c = e; c != null; c = c.getCause()) {
                System.out.println("  " + c);
                for (StackTraceElement el : c.getStackTrace()) {
                    System.out.println("    at " + el);
                }
            }
        });
        String base = JcefExitProbe.class.getResource("/browser/e2e/board.html").toExternalForm();
        CefApp app = JcefRuntime.start(new JcefRuntime.Progress() {
            @Override
            public void downloading(double fraction) {
            }

            @Override
            public void installing() {
            }
        }).get(15, TimeUnit.MINUTES);
        CefClient[] client = new CefClient[1];
        CefBrowser[] browser = new CefBrowser[1];
        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            client[0] = app.createClient();
            browser[0] = client[0].createBrowser(base + "?site=lichess", BrowserWindow.useOffscreenRendering(), false);
            frame[0] = new JFrame("javaChess exit probe");
            frame[0].add(browser[0].getUIComponent());
            frame[0].setBounds(40, 40, 600, 700);
            frame[0].setVisible(true);
        });
        CdpPageDriver page = new CdpPageDriver(browser[0]);
        long deadline = System.currentTimeMillis() + 20_000;
        boolean ready = false;
        while (!ready && System.currentTimeMillis() < deadline) {
            try {
                ready = BoardProbe.read(page).get(5, TimeUnit.SECONDS).board() != null;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        System.out.println("[exit-probe] page ready=" + ready + ", mode " + mode);
        switch (mode) {
            case "hide" -> SwingUtilities.invokeAndWait(() -> frame[0].setVisible(false));
            case "close" -> {
                // JCEF's lifecycle: the browser's window goes only after onBeforeClose (disposing it right after
                // close() let JCEF touch a destroyed view: +[CefHandler setVisibility:], a crash)
                java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
                client[0].addLifeSpanHandler(new org.cef.handler.CefLifeSpanHandlerAdapter() {
                    @Override
                    public void onBeforeClose(CefBrowser b) {
                        closed.countDown();
                    }
                });
                SwingUtilities.invokeAndWait(() -> {
                    page.close();
                    browser[0].close(true);
                });
                System.out.println("[exit-probe] closed " + closed.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> {
                    client[0].dispose();
                    frame[0].dispose();
                });
            }
            case "dispose" -> app.dispose();
            case "runtime" -> JcefRuntime.shutdown();
            default -> {
            }
        }
        Thread.sleep(1500);
        System.out.println("[exit-probe] exiting");
        System.exit(ready ? 0 : 3);
    }
}
