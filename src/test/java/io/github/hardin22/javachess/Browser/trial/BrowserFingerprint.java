package io.github.hardin22.javachess.Browser.trial;

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
 * Developer tool: what the integrated Chromium tells pages about itself (user agent, brands, automation flag,
 * WebGL renderer...), to compare with a normal browser when a site's bot check (Cloudflare Turnstile) fails.
 * Read only: one script on a blank local page, nothing sent anywhere.
 *
 * <p>{@code java ... BrowserFingerprint [url] [secondsToWatch]}: with a URL (e.g. Cloudflare's public Turnstile
 * demo) the page is only opened and watched, never clicked, and with {@code -Dfp.noDevTools=true} no DevTools call
 * is made at all while it is open (the result is read at the end).</p>
 */
public final class BrowserFingerprint {

    private static final String SCRIPT = """
            (() => { let gl = null; try { const c = document.createElement('canvas');
              const g = c.getContext('webgl'); const d = g && g.getExtension('WEBGL_debug_renderer_info');
              gl = g ? { vendor: d ? g.getParameter(d.UNMASKED_VENDOR_WEBGL) : g.getParameter(g.VENDOR),
                         renderer: d ? g.getParameter(d.UNMASKED_RENDERER_WEBGL) : g.getParameter(g.RENDERER) } : null;
              } catch (e) { gl = String(e); }
              return JSON.stringify({ ua: navigator.userAgent, webdriver: navigator.webdriver,
                brands: navigator.userAgentData ? navigator.userAgentData.brands : null,
                platform: navigator.platform, languages: navigator.languages, plugins: navigator.plugins.length,
                cores: navigator.hardwareConcurrency, memory: navigator.deviceMemory, chrome: typeof window.chrome,
                chromeRuntime: !!(window.chrome && window.chrome.runtime), notification: typeof Notification === 'undefined'
                  ? null : Notification.permission, webgl: gl, screen: [screen.width, screen.height, devicePixelRatio],
                turnstile: (document.querySelector('[name="cf-turnstile-response"]') || {}).value || null,
                title: document.title, url: location.href }); })()""";

    private BrowserFingerprint() {
    }

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "about:blank";
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        CefApp app = JcefRuntime.start(new JcefRuntime.Progress() {
            @Override
            public void downloading(double fraction) {
            }

            @Override
            public void installing() {
            }
        }).get(15, TimeUnit.MINUTES);
        CefBrowser[] browser = new CefBrowser[1];
        SwingUtilities.invokeAndWait(() -> {
            CefClient client = app.createClient();
            browser[0] = client.createBrowser(url, BrowserWindow.useOffscreenRendering(), false);
            JFrame frame = new JFrame("javaChess fingerprint");
            frame.add(browser[0].getUIComponent());
            frame.setBounds(60, 60, 720, 900);
            frame.setVisible(true);
        });
        Thread.sleep(seconds * 1000L);
        CdpPageDriver page = new CdpPageDriver(browser[0]);
        System.out.println("[fingerprint] " + page.evaluate(SCRIPT).get(10, TimeUnit.SECONDS));
        System.exit(0);
    }
}
