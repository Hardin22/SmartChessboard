package io.github.hardin22.javachess.Vision;

import com.github.bhlangonijr.chesslib.Board;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;

/**
 * Downloads board pictures drawn by the sites' own renderers, in many board themes and piece sets, as the extended
 * vision battery ({@link VisionBatteryTest}): lichess {@code export/fen.gif} and chess.com {@code dynboard}. They
 * use the same artwork as the pages. Saved under {@code target/vision-battery/} (not committed: the artwork
 * belongs to the sites). Run: {@code java -cp target/test-classes:target/classes:CP ...BatteryDownload [dir]}.
 */
public final class BatteryDownload {

    /** Positions chosen for what breaks readers: crowded boards, few pieces, extra queens, same colours. */
    static final List<String> POSITIONS = List.of(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R w KQkq - 0 1",
            "r2q1rk1/pp1nbppp/2p1pn2/3p4/2PP4/2N1PN2/PP1BBPPP/R2Q1RK1 w - - 0 1",
            "8/5k2/3p4/1p1Pp2p/pP2Pp1P/P4P1K/8/8 w - - 0 1",
            "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1",
            "3qk3/8/8/3Q4/8/8/8/3QK3 w - - 0 1",
            "rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
            "2kr3r/ppp2ppp/2nb1n2/3qp3/3P2b1/2NBPN2/PPPB1PPP/R2QK2R w KQ - 0 1",
            "8/8/8/4k3/8/8/2K5/8 w - - 0 1",
            "q3k2q/8/8/8/8/8/8/Q3K2Q w - - 0 1");

    static final List<String> LICHESS_THEMES = List.of("brown", "blue", "blue2", "blue3", "blue-marble", "canvas",
            "wood", "wood2", "wood3", "wood4", "maple", "maple2", "leather", "green", "marble", "green-plastic",
            "grey", "metal", "olive", "newspaper", "purple", "purple-diag", "pink", "ic");
    static final List<String> LICHESS_PIECES = List.of("cburnett", "merida", "alpha", "pirouetti", "chessnut",
            "chess7", "reillycraig", "companion", "riohacha", "kosal", "leipzig", "fantasy", "spatial", "celtic",
            "california", "caliente", "pixel", "maestro", "fresca", "cardinal", "gioco", "tatiana", "staunty",
            "cooke", "monarchy", "governor", "dubrovny", "icpieces", "mpchess", "kiwen-suwi", "horsey", "anarcandy");
    static final List<String> CHESSCOM_BOARDS = List.of("green", "brown", "blue", "bubblegum", "dash", "glass",
            "graffiti", "icy_sea", "light", "lolz", "marble", "metal", "neon", "newspaper", "orange", "overlay",
            "parchment", "purple", "red", "sand", "sky", "stone", "tan", "tournament", "translucent", "walnut");
    static final List<String> CHESSCOM_PIECES = List.of("neo", "classic", "wood", "glass", "gothic", "alpha",
            "bases", "book", "bubblegum", "cases", "club", "condal", "dash", "game_room", "graffiti", "icy_sea",
            "light", "lolz", "marble", "maya", "metal", "nature", "neon", "newspaper", "ocean", "sky", "space",
            "tigers", "tournament", "vintage");

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/vision-battery/renders");
        Files.createDirectories(dir);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        if (args.length > 1 && args[1].equals("calibration")) {
            calibrationSet(http, dir.resolveSibling("calibration"));
            return;
        }
        JSONArray manifest = new JSONArray();
        Random rnd = new Random(7);
        int n = 0;
        for (int t = 0; t < LICHESS_THEMES.size(); t++) {
            for (int k = 0; k < 2; k++) {
                String theme = LICHESS_THEMES.get(t);
                String pieces = LICHESS_PIECES.get((t * 2 + k) % LICHESS_PIECES.size());
                String fen = POSITIONS.get((t + 3 * k) % POSITIONS.size());
                boolean black = rnd.nextBoolean();
                String url = "https://lichess1.org/export/fen.gif?fen=" + enc(fen.split(" ")[0]) + "&theme=" + theme
                        + "&piece=" + pieces + "&color=" + (black ? "black" : "white");
                String name = String.format("lichess-%02d-%s-%s", n++, theme, pieces);
                if (fetch(http, url, dir.resolve(name + ".gif"))) {
                    manifest.put(entry(name + ".gif", "lichess", theme, pieces, fen, black, "theme"));
                }
            }
        }
        for (int b = 0; b < CHESSCOM_BOARDS.size(); b++) {
            for (int k = 0; k < 2; k++) {
                String board = CHESSCOM_BOARDS.get(b);
                String pieces = CHESSCOM_PIECES.get((b * 2 + k) % CHESSCOM_PIECES.size());
                String fen = POSITIONS.get((b + 5 * k) % POSITIONS.size());
                boolean black = rnd.nextBoolean();
                String url = "https://www.chess.com/dynboard?fen=" + enc(fen) + "&board=" + board + "&piece=" + pieces
                        + "&size=3" + (black ? "&flip=true" : "");
                String name = String.format("chesscom-%02d-%s-%s", n++, board, pieces);
                if (fetchRender(http, url, dir.resolve(name + ".png"))) {
                    manifest.put(entry(name + ".png", "chesscom", board, pieces, fen, black, "theme"));
                }
            }
        }
        Files.writeString(dir.resolve("manifest.json"), manifest.toString(1));
        System.out.println("Downloaded " + manifest.length() + " boards to " + dir);
        calibrationSet(http, dir.resolveSibling("calibration"));
    }

    /**
     * For the calibrated reader: per board theme and piece set, the start position (to learn from) and positions
     * to read, in both orientations. Entries share a "group"; the start position is marked "calibration".
     */
    static void calibrationSet(HttpClient http, Path dir) throws Exception {
        Files.createDirectories(dir);
        JSONArray manifest = new JSONArray();
        int n = 0;
        for (int i = 0; i < CHESSCOM_BOARDS.size(); i++) {
            String board = CHESSCOM_BOARDS.get(i);
            String pieces = CHESSCOM_PIECES.get((i * 7 + 3) % CHESSCOM_PIECES.size());
            String group = board + "+" + pieces;
            for (int k = 0; k < 5; k++) {
                String fen = k == 0 ? POSITIONS.get(0) : POSITIONS.get((i + 2 * k) % (POSITIONS.size() - 1) + 1);
                boolean black = k == 0 ? i % 2 == 1 : (i + k) % 2 == 0;
                String url = "https://www.chess.com/dynboard?fen=" + enc(fen) + "&board=" + board + "&piece=" + pieces
                        + "&size=3" + (black ? "&flip=true" : "");
                String name = String.format("cal-%03d-%s-%s-%d", n++, board, pieces, k);
                if (fetchRender(http, url, dir.resolve(name + ".png"))) {
                    JSONObject e = entry(name + ".png", "chesscom", board, pieces, fen, black, "calibrated");
                    e.put("group", group).put("calibration", k == 0);
                    manifest.put(e);
                }
            }
        }
        Files.writeString(dir.resolve("manifest.json"), manifest.toString(1));
        System.out.println("Calibration set: " + manifest.length() + " boards in " + dir);
    }

    static JSONObject entry(String file, String site, String theme, String pieces, String fen, boolean black,
                            String category) {
        Board b = new Board();
        b.loadFromFen(fen);
        return new JSONObject().put("file", file).put("site", site).put("theme", theme).put("pieces", pieces)
                .put("placement", fen.split(" ")[0]).put("flipped", black).put("category", category)
                .put("source", site.equals("lichess") ? "lichess export renderer" : "chess.com dynboard renderer");
    }

    /**
     * A chess.com render: an unknown board or piece name silently gives the default board (480 px, not flipped), whose
     * labels would be wrong; those are dropped. {@code size=3} renders 720 px.
     */
    private static boolean fetchRender(HttpClient http, String url, Path out) throws java.io.IOException {
        if (!fetch(http, url, out)) {
            return false;
        }
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(out.toFile());
        if (img != null && img.getWidth() == 720) {
            return true;
        }
        System.out.println("Dropped (default board instead of the one asked): " + url);
        Files.deleteIfExists(out);
        return false;
    }

    private static boolean fetch(HttpClient http, String url, Path out) {
        if (Files.isRegularFile(out)) {
            return true;
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            if (fetchOnce(http, url, out, attempt == 3)) {
                return true;
            }
            try {
                Thread.sleep(3000L * (attempt + 1)); // 429: the sites ask to slow down
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean fetchOnce(HttpClient http, String url, Path out, boolean last) {
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "javaChess vision tests (github.com/Hardin22/SmartChessboard)")
                    .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200 || r.body().length < 1000) {
                if (last) {
                    System.out.println("skip " + r.statusCode() + " " + url);
                }
                return false;
            }
            Files.write(out, r.body());
            Thread.sleep(250); // be gentle with the sites
            return true;
        } catch (Exception e) {
            System.out.println("failed " + url + ": " + e);
            return false;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
