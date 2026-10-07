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
import java.awt.Rectangle;
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
    private GraphicsDevice fullScreenDevice;

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
    static boolean useOffscreenRendering() {
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
     * Shows the window over the app's window. With {@code fullScreen} the frame takes the whole screen of
     * {@code bounds} (macOS full-screen apps live in their own space, so a plain window would open elsewhere).
     */
    public void show(Rectangle bounds, boolean fullScreen) {
        GraphicsDevice device = deviceAt(bounds);
        boolean exclusive = fullScreen && isMac() && device != null && device.isFullScreenSupported();
        if (exclusive) {
            frame.setBounds(device.getDefaultConfiguration().getBounds());
            frame.setVisible(true);
            try {
                device.setFullScreenWindow(frame);
                fullScreenDevice = device;
            } catch (RuntimeException e) {
                log.warn("Full screen browser window failed, using a plain window: {}", e.toString());
                fullScreenDevice = null;
                frame.setBounds(bounds);
            }
        } else {
            frame.setBounds(bounds);
            frame.setAlwaysOnTop(!isMac()); // kiosk window manager: stay above the app's full-screen window
            frame.setVisible(true);
        }
        frame.toFront();
        frame.requestFocus();
        browser.setFocus(true);
        log.info("Browser window shown at {} on {}{}", frame.getBounds(),
                device == null ? "?" : device.getIDstring(), exclusive ? " (full screen)" : "");
    }

    public void hide() {
        if (fullScreenDevice != null) {
            try {
                if (fullScreenDevice.getFullScreenWindow() == frame) {
                    fullScreenDevice.setFullScreenWindow(null);
                }
            } catch (RuntimeException e) {
                log.debug("Leaving full screen failed: {}", e.toString());
            }
            fullScreenDevice = null;
        }
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
