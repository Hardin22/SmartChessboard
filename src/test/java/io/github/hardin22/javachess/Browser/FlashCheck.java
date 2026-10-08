package io.github.hardin22.javachess.Browser;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.concurrent.TimeUnit;

/**
 * Developer tool for the off-screen page going black (Linux box, recorded with ffmpeg): a static board page under a
 * bar, as in the app's window, and one thing done every 500 ms per phase of 15 s. The bar's grey level tells the
 * phase in the recording (phase i: grey 20 * (i + 1)).
 */
public final class FlashCheck {

    private FlashCheck() {
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
        String url = FlashCheck.class.getResource("/browser/e2e/board.html").toExternalForm()
                + "?site=chesscom&top=300";
        CefBrowser[] browser = new CefBrowser[1];
        JPanel[] bar = new JPanel[1];
        JLabel label = new JLabel("start");
        JPanel[] content = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            CefClient client = app.createClient();
            BrowserWindow.installStandardHandlers(client);
            browser[0] = client.createBrowser(url, BrowserWindow.useOffscreenRendering(), false);
            JFrame frame = new JFrame("javaChess flash check");
            frame.setUndecorated(true);
            bar[0] = new JPanel(new BorderLayout());
            bar[0].setPreferredSize(new Dimension(720, 300));
            label.setForeground(Color.WHITE);
            bar[0].add(label);
            content[0] = new JPanel(new BorderLayout());
            content[0].add(bar[0], BorderLayout.NORTH);
            content[0].add(browser[0].getUIComponent(), BorderLayout.CENTER);
            frame.setContentPane(content[0]);
            frame.setBounds(0, 0, 720, 1920);
            frame.setVisible(true);
        });
        CdpPageDriver page = new CdpPageDriver(browser[0]);
        Thread.sleep(8000);
        String[] phases = {"idle", "relayout", "setBounds", "evaluate", "capture", "scroll", "label", "devtools",
                "animate", "animate+relayout", "animate+bar"};
        BoardSnapshot.Rect board = BoardProbe.read(page).get(10, TimeUnit.SECONDS).board().rect();
        BrowserBar appBar = new BrowserBar(nav -> { }, action -> { });
        for (int p = 0; p < phases.length; p++) {
            if (phases[p].startsWith("animate") && p == 8) { // the page draws all the time from here on
                page.evaluate("(() => { const d = document.createElement('div'); d.style.cssText = 'position:fixed;"
                        + "left:0;top:0;width:200px;height:60px;background:#c33;z-index:9';"
                        + " document.body.appendChild(d); let x = 0; setInterval(() => { x = (x + 7) % 500;"
                        + " d.style.left = x + 'px'; }, 16); return true; })()").get(5, TimeUnit.SECONDS);
            }
            if (phases[p].equals("animate+bar")) { // the app's own bar in place of the plain one
                SwingUtilities.invokeAndWait(() -> {
                    content[0].remove(bar[0]);
                    appBar.setBackground(new Color(220, 220, 220));
                    content[0].add(appBar, BorderLayout.NORTH);
                    content[0].revalidate();
                });
            }
            int phase = p;
            SwingUtilities.invokeAndWait(() -> {
                int g = 20 * (phase + 1);
                bar[0].setBackground(new Color(g, g, g));
                label.setText(phases[phase]);
            });
            System.out.println("[flash] phase " + phase + " " + phases[phase] + " at " + System.currentTimeMillis());
            for (int i = 0; i < 30; i++) {
                int n = i;
                switch (phases[phase]) {
                    case "relayout" -> SwingUtilities.invokeAndWait(() -> {
                        label.setText("relayout " + n); // what BrowserBar.show did: a revalidation of the window
                        label.revalidate();
                        content[0].revalidate();
                    });
                    case "setBounds" -> SwingUtilities.invokeAndWait(() -> {
                        Component c = browser[0].getUIComponent();
                        c.setBounds(c.getBounds()); // the same bounds, as a layout does
                    });
                    case "evaluate" -> page.evaluate("1 + 1").get(5, TimeUnit.SECONDS);
                    case "capture" -> { // the app's picture of the board
                        java.awt.image.BufferedImage img = page.screenshot(board).get(5, TimeUnit.SECONDS);
                        if (n == 0) {
                            javax.imageio.ImageIO.write(img, "png", new java.io.File(
                                    System.getProperty("flash.out", "/tmp") + "/board.png"));
                            System.out.println("[flash] board picture " + img.getWidth() + "x" + img.getHeight());
                        }
                    }
                    case "devtools" -> page.call("Page.captureScreenshot", new org.json.JSONObject()
                            .put("format", "png")).get(5, TimeUnit.SECONDS);
                    case "scroll" -> page.evaluate("window.scrollBy(0, " + (n % 2 == 0 ? 40 : -40) + ")")
                            .get(5, TimeUnit.SECONDS);
                    case "animate+relayout" -> SwingUtilities.invokeAndWait(() -> {
                        label.setText("relayout " + n);
                        content[0].revalidate();
                    });
                    case "animate+bar" -> SwingUtilities.invokeAndWait(() -> appBar.show(BrowserStatus.of(
                            n % 2 == 0 ? BrowserStatus.State.YOUR_TURN : BrowserStatus.State.OPPONENT_TURN,
                            BrowserStatus.NO_PROGRESS, java.util.List.of(BrowserStatus.Action.SYNC_STOP))));
                    case "label" -> SwingUtilities.invokeAndWait(() -> {
                        label.setText("label " + n); // the bar repainted, the page not laid out
                        label.repaint();
                    });
                    default -> {
                    }
                }
                Thread.sleep(500);
            }
        }
        System.out.println("[flash] done");
        System.exit(0);
    }
}
