package io.github.hardin22.javachess.Stats;

import com.github.bhlangonijr.chesslib.Board;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.ArchivedGame.GameMode;
import io.github.hardin22.javachess.Services.GameArchiveService;
import io.github.hardin22.javachess.Utils.ConfigManager;
import io.github.hardin22.javachess.Utils.PgnCodec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Imports the player's recent games from Lichess or Chess.com (public APIs, by username, nothing to log in), so they
 * can be reviewed with the local engine and replayed on the board. Games already in the archive are skipped; names
 * keep the rating ("hardin22 (1650)") so the review judges the moves at the right level.
 */
public final class OnlineImport {

    private static final Logger log = LoggerFactory.getLogger(OnlineImport.class);
    private static final String USER_AGENT = "javaChess (github.com/Hardin22/SmartChessboard)";
    private static final DateTimeFormatter PGN_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    /** Where the games come from. */
    public enum Source {
        LICHESS("Lichess", "import.lichess.username"), CHESS_COM("Chess.com", "import.chesscom.username");

        private final String label;
        private final String settingsKey;

        Source(String label, String settingsKey) {
            this.label = label;
            this.settingsKey = settingsKey;
        }

        public String label() {
            return label;
        }

        /** Setting with the last username imported from this source (also counted as "me" by the statistics). */
        public String settingsKey() {
            return settingsKey;
        }
    }

    /**
     * Outcome of an import.
     *
     * @param imported     games added to the archive
     * @param alreadyThere games skipped because the archive has them
     * @param message      Italian summary ("12 partite importate, 3 già presenti") or the error
     * @param ok           false when nothing could be downloaded (network, unknown user)
     */
    public record Result(List<ArchivedGame> imported, int alreadyThere, String message, boolean ok) {
        public Result {
            imported = List.copyOf(imported);
        }
    }

    /** Downloads a URL as text: HTTP status and body. */
    public interface Http {
        CompletableFuture<HttpResponse<String>> get(String url, String accept);
    }

    private final Http http;
    private final GameArchiveService archive;

    public OnlineImport() {
        this(defaultHttp(), GameArchiveService.getInstance());
    }

    public OnlineImport(Http http, GameArchiveService archive) {
        this.http = http;
        this.archive = archive;
    }

    /** Last username used for {@code source} (to fill the field), "" when none. */
    public static String lastUsername(Source source) {
        return ConfigManager.getProperty(source.settingsKey(), "").trim();
    }

    /**
     * Imports up to {@code max} most recent games of {@code username}. Never fails: problems end up in the result.
     * Network I/O runs in the background; the archive write runs on the completing thread.
     */
    public CompletableFuture<Result> importRecent(Source source, String username, int max) {
        String user = username == null ? "" : username.trim();
        if (!user.matches("[A-Za-z0-9_-]{2,30}")) {
            return CompletableFuture.completedFuture(fail("Nome utente non valido"));
        }
        int n = Math.max(1, Math.min(200, max));
        CompletableFuture<String> pgn = source == Source.LICHESS ? lichess(user, n) : chessCom(user, n);
        return pgn.thenApply(text -> {
            Result r = store(source, user, text, n);
            if (r.ok()) {
                ConfigManager.setProperty(source.settingsKey(), user);
            }
            return r;
        }).exceptionally(t -> {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            log.info("import from {} failed: {}", source.label(), c.toString());
            return fail(c instanceof UserNotFound ? "Utente «" + user + "» non trovato su " + source.label()
                    : "Impossibile scaricare le partite da " + source.label() + ": controlla la connessione");
        });
    }

    // ------------------------------------------------------------------ download

    private CompletableFuture<String> lichess(String user, int max) {
        String url = "https://lichess.org/api/games/user/" + enc(user) + "?max=" + max
                + "&opening=true&clocks=false&evals=false&literate=false";
        return http.get(url, "application/x-chess-pgn").thenApply(r -> body(r));
    }

    /** Chess.com: the monthly archives, newest first, until {@code max} games are collected. */
    private CompletableFuture<String> chessCom(String user, int max) {
        String url = "https://api.chess.com/pub/player/" + enc(user.toLowerCase(Locale.ROOT)) + "/games/archives";
        return http.get(url, "application/json").thenCompose(r -> {
            JSONArray months = new JSONObject(body(r)).optJSONArray("archives");
            List<String> urls = new ArrayList<>();
            if (months != null) {
                for (int i = months.length() - 1; i >= 0 && urls.size() < 3; i--) {
                    urls.add(months.getString(i) + "/pgn");
                }
            }
            return collect(urls, 0, new StringBuilder(), max);
        });
    }

    private CompletableFuture<String> collect(List<String> urls, int i, StringBuilder acc, int max) {
        if (i >= urls.size() || PgnCodec.parsePgn(acc.toString()).size() >= max) {
            return CompletableFuture.completedFuture(acc.toString());
        }
        return http.get(urls.get(i), "application/x-chess-pgn").thenCompose(r -> {
            // newest month first: older games go after
            acc.append('\n').append(body(r)).append('\n');
            return collect(urls, i + 1, acc, max);
        });
    }

    private static String body(HttpResponse<String> r) {
        if (r.statusCode() == 404) {
            throw new UserNotFound();
        }
        if (r.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    private static final class UserNotFound extends RuntimeException {
    }

    // ------------------------------------------------------------------ archive

    private Result store(Source source, String user, String pgnText, int max) {
        List<ArchivedGame> found = toArchived(source, pgnText);
        found.sort(Comparator.comparing(ArchivedGame::playedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed());
        if (found.size() > max) {
            found = new ArrayList<>(found.subList(0, max));
        }
        Set<String> existing = new HashSet<>();
        for (ArchivedGame g : archive.list()) {
            existing.add(fingerprint(g));
        }
        List<ArchivedGame> fresh = new ArrayList<>();
        int dup = 0;
        for (ArchivedGame g : found) {
            if (existing.add(fingerprint(g))) {
                fresh.add(g);
            } else {
                dup++;
            }
        }
        List<ArchivedGame> stored = archive.addAll(fresh);
        String msg = found.isEmpty() ? "Nessuna partita trovata per «" + user + "» su " + source.label()
                : stored.size() + (stored.size() == 1 ? " partita importata" : " partite importate")
                + (dup > 0 ? ", " + dup + " già presenti" : "");
        return new Result(stored, dup, msg, true);
    }

    /** Same players (without ratings), same start and moves: the same game. */
    static String fingerprint(ArchivedGame g) {
        return base(g.white()) + "|" + base(g.black()) + "|" + g.initialFen().split(" ")[0] + "|"
                + String.join(" ", g.movesUci());
    }

    private static String base(String name) {
        return name == null ? "" : name.replaceAll("\\s*\\(\\d{3,4}\\)\\s*$", "").trim().toLowerCase(Locale.ROOT);
    }

    /** The games of a PGN download as archive records (no id yet). */
    static List<ArchivedGame> toArchived(Source source, String pgnText) {
        List<ArchivedGame> out = new ArrayList<>();
        for (PgnCodec.PgnGame pg : PgnCodec.parsePgn(pgnText)) {
            if (pg.uciMoves().isEmpty()) {
                continue;
            }
            Map<String, String> t = pg.tags();
            String variant = t.getOrDefault("Variant", "Standard");
            if (!variant.equalsIgnoreCase("Standard") && !variant.equalsIgnoreCase("From Position")) {
                continue; // chess960, crazyhouse...: not for this board
            }
            Board end = PgnCodec.replay(pg.initialFen(), pg.uciMoves()).board();
            GameMode mode = source == Source.LICHESS ? GameMode.LICHESS : GameMode.BROWSER;
            out.add(new ArchivedGame(0, mode, source.label() + " · importata", rated(t, "White"), rated(t, "Black"),
                    pg.result(), termination(t.getOrDefault("Termination", ""), pg.result(), end),
                    opening(source, t), timeControl(t.getOrDefault("TimeControl", "")), date(source, t),
                    pg.initialFen(), "", pg.uciMoves()));
        }
        return out;
    }

    private static String rated(Map<String, String> t, String side) {
        String name = t.getOrDefault(side, "?").trim();
        String elo = t.getOrDefault(side + "Elo", "").trim();
        return elo.matches("\\d{3,4}") ? name + " (" + elo + ")" : name;
    }

    /** Termination in the archive's Italian words. */
    static String termination(String tag, String result, Board end) {
        String t = tag.toLowerCase(Locale.ROOT);
        if (end != null && end.isMated()) {
            return "Scaccomatto";
        }
        if (t.contains("time") || t.contains("tempo")) {
            return result.equals("1/2-1/2") ? "Patta: tempo scaduto" : "Tempo";
        }
        if (t.contains("resign")) {
            return "Abbandono";
        }
        if (t.contains("checkmate")) {
            return "Scaccomatto";
        }
        if (t.contains("abandon")) {
            return "Abbandonata";
        }
        if (t.contains("repetition")) {
            return "Triplice ripetizione";
        }
        if (t.contains("agreement")) {
            return "Patta d'accordo";
        }
        if (t.contains("stalemate")) {
            return "Stallo";
        }
        if (t.contains("insufficient")) {
            return "Materiale insufficiente";
        }
        if (t.contains("50")) {
            return "Regola delle 50 mosse";
        }
        if (end != null && end.isStaleMate()) {
            return "Stallo";
        }
        return switch (result) {
            case "1-0", "0-1" -> "Abbandono";
            case "1/2-1/2" -> "Patta";
            default -> "";
        };
    }

    /** "C53 Italian Game: Giuoco Piano" (Lichess tags), or from the Chess.com opening link. */
    static String opening(Source source, Map<String, String> t) {
        String eco = t.getOrDefault("ECO", "").trim();
        String name = t.getOrDefault("Opening", "").trim();
        if (name.isEmpty() && source == Source.CHESS_COM) {
            String url = t.getOrDefault("ECOUrl", "");
            int slash = url.lastIndexOf('/');
            if (slash >= 0) {
                String slug = url.substring(slash + 1);
                // "Italian-Game-Giuoco-Piano-4.c3" → "Italian Game Giuoco Piano"
                StringBuilder sb = new StringBuilder();
                for (String part : slug.split("-")) {
                    if (part.matches("\\d.*")) {
                        break;
                    }
                    sb.append(sb.length() == 0 ? "" : " ").append(part);
                }
                name = sb.toString();
            }
        }
        if (name.equals("?")) {
            name = "";
        }
        return (eco.matches("[A-E]\\d\\d") && !name.isEmpty() ? eco + " " : "") + name;
    }

    /** "600+5" (seconds) → "10+5"; daily games and unknown → "". */
    static String timeControl(String tag) {
        if (tag == null || !tag.matches("\\d+(\\+\\d+)?")) {
            return "";
        }
        String[] p = tag.split("\\+");
        int seconds = Integer.parseInt(p[0]);
        int inc = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        String minutes = seconds % 60 == 0 ? String.valueOf(seconds / 60) : seconds + "s";
        return minutes + "+" + inc;
    }

    private static LocalDateTime date(Source source, Map<String, String> t) {
        String d = source == Source.LICHESS ? t.getOrDefault("UTCDate", t.get("Date"))
                : t.getOrDefault("EndDate", t.get("Date"));
        String time = source == Source.LICHESS ? t.get("UTCTime") : t.getOrDefault("EndTime", t.get("StartTime"));
        try {
            LocalDate day = LocalDate.parse(d, PGN_DATE);
            LocalTime at = time != null && time.matches("\\d{1,2}:\\d{2}:\\d{2}") ? LocalTime.parse(
                    time.length() == 7 ? "0" + time : time) : LocalTime.NOON;
            return LocalDateTime.of(day, at);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Result fail(String message) {
        return new Result(List.of(), 0, message, false);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Http defaultHttp() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return (url, accept) -> client.sendAsync(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).header("Accept", accept).header("User-Agent", USER_AGENT).GET()
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
