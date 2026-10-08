package io.github.hardin22.javachess.Browser;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.json.JSONObject;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Developer tool for real keyboard and mouse input (Linux box: xdotool, xclip): opens the local login page with the
 * app's standard handlers, focuses the name field, prints "[paste] ready", waits for {@code /tmp/paste.go}, then
 * prints the field's value and whether the page still answers (after a right click, for instance).
 */
public final class PasteCheck {

    private PasteCheck() {
    }

    public static void main(String[] args) throws Exception {
        CefApp app = JcefRuntime.start(new JcefRuntime.Progress() {
            @Override
            public void downloading(double fraction) {
            }

            @Override
            public void installing() {
            }
        }).get(5, TimeUnit.MINUTES);
        String url = PasteCheck.class.getResource("/browser/e2e/login.html").toExternalForm();
        CefBrowser[] browser = new CefBrowser[1];
        SwingUtilities.invokeAndWait(() -> {
            CefClient client = app.createClient();
            BrowserWindow.installStandardHandlers(client);
            browser[0] = client.createBrowser(url, BrowserWindow.useOffscreenRendering(), false);
            if (BrowserWindow.useOffscreenRendering()) {
                EditShortcuts.installForOffscreen(browser[0]); // as BrowserWindow does
            }
            JFrame frame = new JFrame("javaChess paste check");
            frame.add(browser[0].getUIComponent());
            frame.setBounds(0, 0, 720, 900);
            frame.setVisible(true);
        });
        CdpPageDriver page = new CdpPageDriver(browser[0]);
        String form = null;
        for (int i = 0; i < 100 && (form == null || form.equals("null")); i++) {
            Thread.sleep(200);
            try {
                form = page.evaluate(LoginAssistant.FORM_SCRIPT).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                form = null;
            }
        }
        JSONObject user = new JSONObject(form).getJSONObject("user");
        page.click(user.getDouble("x"), user.getDouble("y")).get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> browser[0].setFocus(true));
        System.out.println("[paste] ready " + Math.round(user.getDouble("x")) + " " + Math.round(user.getDouble("y")));
        Path go = Path.of("/tmp/paste.go");
        for (int i = 0; i < 300 && !Files.exists(go); i++) {
            Thread.sleep(100);
        }
        Thread.sleep(500);
        if (Boolean.getBoolean("paste.copyDirect")) { // CEF's copy command without any key
            SwingUtilities.invokeAndWait(() -> browser[0].getFocusedFrame().copy());
            Thread.sleep(800);
            System.out.println("[paste] copied directly");
        }
        if (Boolean.getBoolean("paste.direct")) { // CEF's paste command without any key
            SwingUtilities.invokeAndWait(() -> browser[0].getFocusedFrame().paste());
            Thread.sleep(800);
        }
        String value = page.evaluate("JSON.stringify({focused: document.activeElement && (document.activeElement.tagName"
                + " + ':' + (document.activeElement.type || '')), name: [...document.querySelectorAll('input')]"
                + ".map(i => i.type + '=' + i.value), codes: [...document.querySelectorAll('input')][0].value.split('')"
                + ".map(ch => ch.charCodeAt(0)), selection: String(getSelection())})").get(5, TimeUnit.SECONDS);
        System.out.println("[paste] value " + value);
        System.out.println("[paste] page answers " + page.evaluate("1 + 1").get(5, TimeUnit.SECONDS));
        System.exit(0);
    }
}
