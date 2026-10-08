package io.github.hardin22.javachess.Browser;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The window that shows the page: one undecorated Swing frame with the {@link BrowserBar} on top and the Chromium
 * view below, laid exactly over the app's window (same screen, same bounds). It is created once and then hidden
 * and shown again: the native browser view is never re-parented, so a second opening is as reliable as the first.
 *
 * <p>Page events (loads, errors, address changes) go to the {@link BrowserSession}. Pop-ups (e.g. "log in with
 * Google") open in the same view: a kiosk cannot manage extra windows. Must be used on the Swing thread.</p>
 */
public final class BrowserWindow {

    private static final Logger log = LoggerFactory.getLogger(BrowserWindow.class);

    private final CefClient client;
    private final CefBrowser browser;
    private final JFrame frame;
    private final BrowserBar bar;
    private final CdpPageDriver driver;
    private final boolean offscreen;

    /**
     * Creates the window (hidden) and the browser on {@code url}. Must run on the Swing thread; throws when
     * Chromium cannot create the view.
     */
    public BrowserWindow(CefApp app, String url, BrowserSession session, Consumer<BrowserBar.Nav> onNav) {
        offscreen = useOffscreenRendering();
        client = app.createClient();
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser b, CefFrame f, CefRequest.TransitionType type) {
                if (f != null && f.isMain()) {
                    session.pageLoading(f.getURL());
                }
            }

            @Override
            public void onLoadEnd(CefBrowser b, CefFrame f, int httpStatus) {
                if (f != null && f.isMain()) {
                    session.pageLoaded(f.getURL(), httpStatus);
                    JcefRuntime.flushCookies(); // a login just made survives a quick exit
                }
            }

            @Override
            public void onLoadError(CefBrowser b, CefFrame f, CefLoadHandler.ErrorCode code, String text, String failed) {
                if (f != null && f.isMain()) {
                    session.pageLoadFailed(failed, code == null ? "ERR_FAILED" : code.name());
                }
            }

            @Override
            public void onLoadingStateChange(CefBrowser b, boolean loading, boolean canGoBack, boolean canGoForward) {
                SwingUtilities.invokeLater(() -> barCanGoBack(canGoBack));
            }
        });
        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(CefBrowser b, CefFrame f, String address) {
                if (f == null || f.isMain()) {
                    session.addressChanged(address); // pages that change URL without a load (chess.com games)
                }
            }
        });
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser b, CefFrame f, String target, String frameName) {
                log.info("Pop-up opened in the main view: {}", target);
                if (target != null && !target.isBlank()) {
                    b.loadURL(target);
                }
                return true; // no extra windows
            }
        });
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public void onRenderProcessTerminated(CefBrowser b, TerminationStatus status, int errorCode,
                                                  String errorString) {
                log.warn("Page process terminated: {} {} {}", status, errorCode, errorString);
                session.pageLoadFailed(b.getURL(), "RENDERER_" + status);
            }
        });
        browser = client.createBrowser(url, offscreen, false);
        driver = new CdpPageDriver(browser);

        bar = new BrowserBar(onNav, session::perform);
        frame = new JFrame("javaChess - browser");
        frame.setUndecorated(true);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout());
        content.add(bar, BorderLayout.NORTH);
        content.add(browser.getUIComponent(), BorderLayout.CENTER);
        frame.setContentPane(content);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                onNav.accept(BrowserBar.Nav.HOME);
            }
        });
        log.info("Browser window created ({} rendering)", offscreen ? "off-screen" : "windowed");
    }

    /** Linux (Raspberry Pi) renders off-screen: windowed Chromium steals the X11 focus there. */
    public static boolean useOffscreenRendering() {
        String forced = System.getProperty("javachess.browser.osr");
        if (forced != null) {
            return Boolean.parseBoolean(forced);
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    public PageDriver page() {
        return driver;
    }

    public BrowserBar bar() {
        return bar;
    }

    public CefBrowser browser() {
        return browser;
    }

    public boolean isShowing() {
        return frame.isVisible();
    }

    public void load(String url) {
        browser.loadURL(url);
    }

    public void reload() {
        browser.reload();
    }

    public void goBack() {
        if (browser.canGoBack()) {
            browser.goBack();
        }
    }

    private void barCanGoBack(boolean canGoBack) {
        bar.setCanGoBack(canGoBack);
    }

    /**
     * Shows the window over the app's window. With {@code coverScreen} it covers the whole screen of {@code bounds}
     * (the app's window was full screen), minus what the system keeps for itself there (the macOS menu bar).
     *
     * <p>Never AWT's exclusive full screen ({@code GraphicsDevice.setFullScreenWindow}): on macOS, over the app's
     * JavaFX window in full screen, AppKit raises exceptions in it ({@code -[NSWindow setStyleMask:]}, unknown
     * selectors) on the main thread, which end the process (the app crashed when the browser was opened again,
     * 8 October 2026). On macOS the caller takes the app's window out of full screen first.</p>
     */
    public void show(Rectangle bounds, boolean coverScreen) {
        GraphicsDevice device = deviceAt(bounds);
        Rectangle screen = device.getDefaultConfiguration().getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(device.getDefaultConfiguration());
        frame.setBounds(windowBounds(bounds, coverScreen, screen, insets));
        frame.setAlwaysOnTop(!isMac()); // kiosk window manager: stay above the app's full-screen window
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
        browser.setFocus(true);
        log.info("Browser window shown at {} on {}{}", frame.getBounds(), device.getIDstring(),
                coverScreen ? " (whole screen)" : "");
    }

    /** Where the window goes: the app's bounds, or its whole screen without the system's insets. */
    static Rectangle windowBounds(Rectangle appBounds, boolean coverScreen, Rectangle screen, Insets insets) {
        if (!coverScreen) {
            return new Rectangle(appBounds);
        }
        Insets i = insets == null ? new Insets(0, 0, 0, 0) : insets;
        return new Rectangle(screen.x + i.left, screen.y + i.top, Math.max(1, screen.width - i.left - i.right),
                Math.max(1, screen.height - i.top - i.bottom));
    }

    public void hide() {
        frame.setAlwaysOnTop(false);
        frame.setVisible(false);
    }

    public void dispose() {
        hide();
        try {
            driver.close();
            browser.close(true);
            client.dispose();
        } catch (RuntimeException e) {
            log.debug("Browser close failed: {}", e.toString());
        }
        frame.dispose();
    }

    private static GraphicsDevice deviceAt(Rectangle bounds) {
        double cx = bounds.getCenterX();
        double cy = bounds.getCenterY();
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (d.getDefaultConfiguration().getBounds().contains(cx, cy)) {
                return d;
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
