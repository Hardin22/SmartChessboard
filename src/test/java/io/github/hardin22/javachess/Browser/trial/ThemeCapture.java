package io.github.hardin22.javachess.Browser.trial;

import io.github.hardin22.javachess.Browser.BoardPicture;
import io.github.hardin22.javachess.Browser.BoardProbe;
import io.github.hardin22.javachess.Browser.BoardSnapshot;
import io.github.hardin22.javachess.Browser.CdpPageDriver;
import io.github.hardin22.javachess.Browser.JcefRuntime;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Captures real lichess boards in many board themes and piece sets for the vision battery: in an anonymous
 * browser profile it sets the (anonymous, cookie-based) board preferences, opens analysis boards on several positions
 * with white and with black to move (lichess turns the board to the side to move) and saves the board pictured by
 * Chromium with the position read from the page. One group per theme and piece set; the start position is the
 * calibration picture.
 *
 * <pre>java ... ThemeCapture OUT_DIR</pre>
 */
public final class ThemeCapture {

    static final List<String[]> COMBOS = List.of(
            new String[]{"brown", "cburnett"}, new String[]{"blue", "merida"}, new String[]{"green", "alpha"},
            new String[]{"wood4", "california"}, new String[]{"marble", "staunty"}, new String[]{"purple", "maestro"},
            new String[]{"grey", "kosal"}, new String[]{"maple", "fresca"}, new String[]{"newspaper", "companion"},
            new String[]{"ic", "pirouetti"}, new String[]{"blue-marble", "chessnut"}, new String[]{"canvas", "leipzig"},
            new String[]{"metal", "riohacha"}, new String[]{"olive", "cardinal"}, new String[]{"wood", "spatial"},
            new String[]{"leather", "gioco"}, new String[]{"pink", "tatiana"}, new String[]{"green-plastic", "horsey"});

    static final List<String> FENS = List.of(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1",
            "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R w KQkq - 0 1",
            "r2q1rk1/pp1nbppp/2p1pn2/3p4/2PP4/2N1PN2/PP1BBPPP/R2Q1RK1 b - - 0 1",
            "8/5k2/3p4/1p1Pp2p/pP2Pp1P/P4P1K/8/8 w - - 0 1",
            "3qk3/8/8/3Q4/8/8/8/3QK3 b - - 0 1");

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        CefApp app = JcefRuntime.start(new JcefRuntime.Progress() {
            @Override
            public void downloading(double fraction) {
            }

            @Override
            public void installing() {
            }
        }).get(15, TimeUnit.MINUTES);
        CefBrowser[] browser = new CefBrowser[1];
        boolean osr = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
        SwingUtilities.invokeAndWait(() -> {
            CefClient client = app.createClient();
            browser[0] = client.createBrowser("https://lichess.org/analysis", osr, false);
            JFrame frame = new JFrame("capture");
            frame.add(browser[0].getUIComponent());
            frame.setBounds(0, 0, 720, 1280);
            frame.setVisible(true);
        });
        CdpPageDriver page = new CdpPageDriver(browser[0]);
        Thread.sleep(6000);
        JSONArray manifest = new JSONArray();
        int n = 0;
        for (String[] combo : COMBOS) {
            String theme = combo[0];
            String pieces = combo[1];
            // the board preferences of an anonymous visitor (stored in its cookie), as the settings menu sets them
            String result = page.evaluate("(async () => { const h = {'X-Requested-With': 'XMLHttpRequest',"
                    + " 'Content-Type': 'application/x-www-form-urlencoded'};"
                    + " const a = await fetch('/pref/theme', {method: 'POST', headers: h, body: 'theme=" + theme + "'});"
                    + " const b = await fetch('/pref/pieceSet', {method: 'POST', headers: h, body: 'set=" + pieces + "'});"
                    + " return a.status + ',' + b.status; })()").get(10, TimeUnit.SECONDS);
            Thread.sleep(500);
            for (int f = 0; f < FENS.size(); f++) {
                String fen = FENS.get(f);
                String url = "https://lichess.org/analysis/standard/" + fen.replace(' ', '_');
                page.evaluate("location.href = " + JSONObject.quote(url)).get(10, TimeUnit.SECONDS);
                BoardSnapshot s = null;
                for (int i = 0; i < 40; i++) {
                    Thread.sleep(400);
                    try {
                        BoardSnapshot x = BoardProbe.read(page).get(10, TimeUnit.SECONDS);
                        if (x.board() != null && fen.startsWith(String.valueOf(x.board().placement()))
                                && !x.board().animating() && x.url().contains("analysis")) {
                            s = x;
                            break;
                        }
                    } catch (Exception e) {
                        // loading
                    }
                }
                if (s == null) {
                    System.out.println("skip " + theme + " " + pieces + " " + fen);
                    continue;
                }
                Thread.sleep(800); // piece images loaded
                s = BoardProbe.read(page).get(10, TimeUnit.SECONDS);
                BufferedImage img = BoardPicture.take(page, s).get(10, TimeUnit.SECONDS);
                String applied = page.evaluate("(() => { const b = document.body; return (b.dataset.board || '') + '/'"
                        + " + (b.dataset.pieceSet || '') + ' ' + b.className.slice(0, 80); })()").get(5, TimeUnit.SECONDS);
                String name = String.format("lichess-%03d-%s-%s", n++, theme, pieces);
                ImageIO.write(img, "png", out.resolve(name + ".png").toFile());
                manifest.put(new JSONObject().put("file", name + ".png").put("site", "lichess").put("theme", theme)
                        .put("pieces", pieces).put("placement", s.board().placement()).put("flipped", s.board().flipped())
                        .put("category", "theme (page)").put("group", theme + "+" + pieces)
                        .put("calibration", f == 0).put("prefs", result).put("applied", applied));
                Files.writeString(out.resolve("manifest.json"), manifest.toString(1));
                System.out.println("captured " + name + " flipped " + s.board().flipped() + " applied " + applied);
            }
        }
        System.out.println("DONE " + manifest.length());
        System.exit(0);
    }
}
