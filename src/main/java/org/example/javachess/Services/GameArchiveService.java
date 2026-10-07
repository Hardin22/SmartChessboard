package org.example.javachess.Services;

import com.github.bhlangonijr.chesslib.Board;
import org.example.javachess.Oggetti.ArchivedGame;
import org.example.javachess.Oggetti.ArchivedGame.GameMode;
import org.example.javachess.Utils.AppPaths;
import org.example.javachess.Utils.AtomicFiles;
import org.example.javachess.Utils.PgnCodec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game archive ({@code ~/.javachess/archive.json}).
 *
 * <h2>File format (schema version 2)</h2>
 * <pre>{ "schemaVersion": 2, "nextId": 12, "games": [ { "id": 11, "mode": "PVC", "white": "...", "black": "...",
 *   "result": "1-0", "termination": "Scaccomatto", "moves": ["e2e4", ...], "pgn": "[Event ...", ... } ] }</pre>
 * {@code pgn} is a convenience copy for external tools and is regenerated on every write.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li>Every write is atomic (temporary file + rename), so a crash never truncates the archive.</li>
 *   <li>The first write of each session and every migration first copy the previous file to
 *       {@code ~/.javachess/backups/} (the last 10 copies are kept).</li>
 *   <li>A corrupt file is moved aside (never overwritten) and the archive starts empty.</li>
 *   <li>Records that cannot be understood are kept verbatim and written back unchanged.</li>
 *   <li>Version 1 files (a bare JSON array with UCI moves mixed with Italian result messages, kept in the working
 *       directory by old versions) are migrated without losing games; the old file is left untouched.</li>
 * </ul>
 * All public methods are thread-safe.
 */
public class GameArchiveService {

    private static final Logger log = LoggerFactory.getLogger(GameArchiveService.class);

    public static final int SCHEMA_VERSION = 2;
    private static final int BACKUPS_KEPT = 10;
    private static final DateTimeFormatter LEGACY_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
    private static final DateTimeFormatter PGN_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private static volatile GameArchiveService instance;

    private final Path file;
    private final Path legacyFile;
    private final Path backupDir;
    private final List<ArchivedGame> games = new ArrayList<>();
    private final List<JSONObject> preserved = new ArrayList<>();
    /** Original schema-1 record of games whose migration could not keep every move (written back as "legacy"). */
    private final Map<Integer, JSONObject> legacyOriginals = new java.util.HashMap<>();
    private int nextId = 1;
    private boolean readOnly;
    private boolean backedUpThisSession;
    private String loadProblem;

    /** Shared archive in the user data folder (migrates the working-directory archive of old versions). */
    public static GameArchiveService getInstance() {
        GameArchiveService local = instance;
        if (local == null) {
            synchronized (GameArchiveService.class) {
                local = instance;
                if (local == null) {
                    local = new GameArchiveService(AppPaths.archiveFile(),
                            AppPaths.legacyDir().resolve("archive.json"), AppPaths.backupsDir());
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Forgets the shared instance (tests change {@code javachess.home} between runs). */
    public static synchronized void resetInstance() {
        instance = null;
    }

    public GameArchiveService(Path file, Path legacyFile, Path backupDir) {
        this.file = file;
        this.legacyFile = legacyFile;
        this.backupDir = backupDir;
        load();
    }

    // =================================================================== read

    /** All games, newest first. */
    public synchronized List<ArchivedGame> list() {
        List<ArchivedGame> copy = new ArrayList<>(games);
        copy.sort(Comparator.comparingInt(ArchivedGame::id).reversed());
        return copy;
    }

    public synchronized Optional<ArchivedGame> get(int id) {
        return games.stream().filter(g -> g.id() == id).findFirst();
    }

    public synchronized int size() {
        return games.size();
    }

    /** Non-null when the last load found a problem the user should know about (corrupt file, newer schema). */
    public synchronized String getLoadProblem() {
        return loadProblem;
    }

    public Path getFile() {
        return file;
    }

    // =================================================================== create / update / delete

    /**
     * Adds a game (the id of {@code draft} is ignored). Moves are checked against the rules: an illegal move and
     * everything after it are dropped. The final position is recomputed and the result is taken from the board when
     * the game ended by mate or a rule draw.
     *
     * @return the stored game, with its new id
     */
    public synchronized ArchivedGame add(ArchivedGame draft) {
        ArchivedGame stored = sanitize(draft).withId(nextId++);
        games.add(stored);
        persist();
        log.info("Archived game #{} ({}, {} moves, {})", stored.id(), stored.mode(), stored.movesUci().size(),
                stored.result());
        return stored;
    }

    /** Replaces the game with the same id. Returns false if it does not exist. */
    public synchronized boolean update(ArchivedGame game) {
        for (int i = 0; i < games.size(); i++) {
            if (games.get(i).id() == game.id()) {
                games.set(i, sanitize(game));
                persist();
                return true;
            }
        }
        return false;
    }

    /** Deletes a game. Returns false if it does not exist. */
    public synchronized boolean delete(int id) {
        boolean removed = games.removeIf(g -> g.id() == id);
        if (removed) {
            legacyOriginals.remove(id);
            persist();
            log.info("Deleted archived game #{}", id);
        }
        return removed;
    }

    // =================================================================== PGN

    /** Standard PGN of one game (SAN moves, seven tag roster, SetUp/FEN for custom starts). */
    public static String toPgn(ArchivedGame g) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("Event", g.label().isEmpty() ? eventFor(g.mode()) : g.label());
        tags.put("Site", g.mode() == GameMode.LICHESS ? "https://lichess.org"
                : g.mode() == GameMode.BROWSER ? "Online" : "javaChess");
        if (g.playedAt() != null) {
            tags.put("Date", g.playedAt().format(PGN_DATE));
            tags.put("Time", g.playedAt().toLocalTime().withNano(0).toString());
        }
        tags.put("White", g.white());
        tags.put("Black", g.black());
        if (!g.timeControl().isEmpty()) {
            tags.put("TimeControl", toPgnTimeControl(g.timeControl()));
        }
        if (!g.opening().isEmpty()) {
            tags.put("Opening", g.opening());
        }
        if (!g.termination().isEmpty()) {
            tags.put("Termination", g.termination());
        }
        return PgnCodec.toPgn(tags, g.initialFen(), g.movesUci(), g.result());
    }

    /** PGN of the given games (in the given order), separated by blank lines. Unknown ids are skipped. */
    public synchronized String exportPgn(Collection<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (Integer id : ids) {
            get(id).ifPresent(g -> sb.append(toPgn(g)).append('\n'));
        }
        return sb.toString();
    }

    /** PGN of the whole archive, oldest first. */
    public synchronized String exportAllPgn() {
        List<ArchivedGame> ordered = new ArrayList<>(games);
        ordered.sort(Comparator.comparingInt(ArchivedGame::id));
        StringBuilder sb = new StringBuilder();
        ordered.forEach(g -> sb.append(toPgn(g)).append('\n'));
        return sb.toString();
    }

    /** Writes the whole archive as PGN to {@code target} (atomically). */
    public void exportAllPgn(Path target) throws IOException {
        AtomicFiles.writeString(target, exportAllPgn(), false);
    }

    /** Outcome of a PGN import. */
    public record ImportReport(List<ArchivedGame> imported, int skipped, List<String> warnings) {
    }

    /**
     * Imports every game of a PGN text. Games without any legal move are skipped; a game with an illegal move is
     * imported up to that move and reported in the warnings.
     */
    public synchronized ImportReport importPgn(String pgnText) {
        List<ArchivedGame> imported = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int skipped = 0;
        int index = 0;
        for (PgnCodec.PgnGame pg : PgnCodec.parsePgn(pgnText)) {
            index++;
            Map<String, String> t = pg.tags();
            if (pg.uciMoves().isEmpty()) {
                skipped++;
                warnings.add("Partita " + index + ": nessuna mossa valida"
                        + (pg.rejectedToken() != null ? " (\"" + pg.rejectedToken() + "\")" : "") + ", ignorata");
                continue;
            }
            if (pg.rejectedToken() != null) {
                warnings.add("Partita " + index + ": importata fino alla mossa non valida \"" + pg.rejectedToken()
                        + "\"");
            }
            LocalDateTime when = parsePgnDate(t.get("Date"), t.get("Time"));
            ArchivedGame g = new ArchivedGame(0, GameMode.IMPORTED, t.getOrDefault("Event", ""),
                    t.get("White"), t.get("Black"), pg.result(), t.getOrDefault("Termination", ""),
                    t.getOrDefault("Opening", ""), t.getOrDefault("TimeControl", ""), when, pg.initialFen(), "",
                    pg.uciMoves());
            ArchivedGame stored = sanitize(g).withId(nextId++);
            games.add(stored);
            imported.add(stored);
        }
        if (!imported.isEmpty()) {
            persist();
        }
        log.info("PGN import: {} games imported, {} skipped", imported.size(), skipped);
        return new ImportReport(imported, skipped, warnings);
    }

    /** Imports a PGN file. */
    public ImportReport importPgn(Path pgnFile) throws IOException {
        return importPgn(Files.readString(pgnFile, StandardCharsets.UTF_8));
    }

    // =================================================================== compatibility

    /**
     * Old entry point kept for the game classes: {@code pgn} may contain UCI moves, move numbers and a free-text
     * result; {@code result} may be an Italian end-of-game message. Everything is normalised before storing.
     *
     * @deprecated build an {@link ArchivedGame} and call {@link #add(ArchivedGame)} (gives player names too)
     */
    @Deprecated
    public static void saveGame(String type, String opening, String pgn, String initialFen, String finalFen,
                                String result, String timeControl) {
        try {
            JSONObject legacy = new JSONObject();
            legacy.put("type", type == null ? "" : type);
            legacy.put("opening", opening == null ? "" : opening);
            legacy.put("pgn", pgn == null ? "" : pgn);
            legacy.put("initialFen", initialFen == null ? "" : initialFen);
            legacy.put("fen", finalFen == null ? "" : finalFen);
            legacy.put("result", result == null ? "" : result);
            legacy.put("time", timeControl == null ? "" : timeControl);
            ArchivedGame g = fromLegacy(legacy, 0);
            g = new ArchivedGame(0, g.mode(), g.label(), g.white(), g.black(), g.result(), g.termination(),
                    g.opening(), g.timeControl(), LocalDateTime.now(), g.initialFen(), g.finalFen(), g.movesUci());
            if (g.movesUci().isEmpty()) {
                log.info("Game without moves not archived ({})", type);
                return;
            }
            getInstance().add(g);
        } catch (RuntimeException e) {
            log.error("Could not archive game", e);
        }
    }

    // =================================================================== load / save

    private void load() {
        games.clear();
        preserved.clear();
        legacyOriginals.clear();
        nextId = 1;
        readOnly = false;
        loadProblem = null;
        try {
            if (Files.exists(file)) {
                loadFile(file, false);
            } else if (legacyFile != null && Files.isRegularFile(legacyFile) && !sameFile(legacyFile, file)) {
                loadFile(legacyFile, true);
            }
        } catch (IOException e) {
            loadProblem = "Impossibile leggere l'archivio: " + e.getMessage();
            log.error("Cannot read archive {}: {}", file, e.getMessage());
            readOnly = true; // do not overwrite a file we could not read
        }
    }

    private void loadFile(Path source, boolean fromLegacyLocation) throws IOException {
        String content = Files.readString(source, StandardCharsets.UTF_8).strip();
        if (content.isEmpty()) {
            return;
        }
        Object root;
        try {
            root = new JSONTokener(content).nextValue();
        } catch (JSONException e) {
            quarantine(source, fromLegacyLocation, "file non valido (" + e.getMessage() + ")");
            return;
        }
        if (root instanceof JSONArray array) {
            migrateV1(array, source, fromLegacyLocation);
        } else if (root instanceof JSONObject obj) {
            int version = obj.optInt("schemaVersion", -1);
            if (version > SCHEMA_VERSION) {
                readOnly = true;
                loadProblem = "L'archivio è stato scritto da una versione più recente dell'app: sola lettura.";
                log.warn("Archive {} has schema {} (> {}): opened read-only", source, version, SCHEMA_VERSION);
            } else if (version < 1) {
                quarantine(source, fromLegacyLocation, "versione dello schema mancante");
                return;
            }
            readV2(obj);
            if (fromLegacyLocation) {
                persist();
            }
        } else {
            quarantine(source, fromLegacyLocation, "contenuto inatteso");
        }
    }

    private void readV2(JSONObject obj) {
        JSONArray arr = obj.optJSONArray("games");
        int maxId = 0;
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject g = arr.optJSONObject(i);
                if (g == null) {
                    continue;
                }
                try {
                    ArchivedGame game = fromV2(g);
                    games.add(game);
                    JSONObject original = g.optJSONObject("legacy");
                    if (original != null) {
                        legacyOriginals.put(game.id(), original);
                    }
                    maxId = Math.max(maxId, game.id());
                } catch (RuntimeException e) {
                    log.warn("Archive record {} not understood, kept as is: {}", i, e.getMessage());
                    preserved.add(g);
                    maxId = Math.max(maxId, g.optInt("id", 0));
                }
            }
        }
        nextId = Math.max(obj.optInt("nextId", 1), maxId + 1);
    }

    private void migrateV1(JSONArray array, Path source, boolean fromLegacyLocation) throws IOException {
        if (!fromLegacyLocation) {
            AtomicFiles.backup(source, backupDir, BACKUPS_KEPT);
        }
        java.util.Set<Integer> usedIds = new java.util.HashSet<>();
        List<ArchivedGame> migrated = new ArrayList<>();
        Map<ArchivedGame, JSONObject> lossy = new java.util.IdentityHashMap<>();
        int skipped = 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject legacy = array.optJSONObject(i);
            if (legacy == null) {
                skipped++;
                continue;
            }
            try {
                ArchivedGame g = fromLegacy(legacy, legacy.optInt("id", 0));
                migrated.add(g);
                long uciTokens = Arrays.stream(legacy.optString("pgn", "").split("\\s+"))
                        .filter(PgnCodec::looksLikeUci).count();
                if (uciTokens != g.movesUci().size()) {
                    lossy.put(g, legacy);
                }
            } catch (RuntimeException e) {
                log.warn("Old archive record {} not understood, kept as is: {}", i, e.getMessage());
                preserved.add(legacy);
            }
        }
        // Keep old ids when they are valid and unique, renumber the rest after them.
        int maxId = migrated.stream().mapToInt(ArchivedGame::id).filter(id -> id > 0).max().orElse(0);
        for (ArchivedGame g : migrated) {
            int id = g.id();
            if (id <= 0 || !usedIds.add(id)) {
                id = ++maxId;
                usedIds.add(id);
            }
            games.add(g.withId(id));
            if (lossy.containsKey(g)) {
                legacyOriginals.put(id, lossy.get(g));
            }
        }
        nextId = maxId + 1;
        if (!lossy.isEmpty()) {
            log.warn("{} old games contain illegal moves: kept up to the first illegal move, original record saved "
                    + "in the \"legacy\" field", lossy.size());
        }
        log.info("Migrated {} games from {} (schema 1 -> {}), {} unreadable", games.size(), source,
                SCHEMA_VERSION, skipped + preserved.size());
        backedUpThisSession = true; // the original is either backed up or untouched in its old location
        persist();
    }

    private void quarantine(Path source, boolean fromLegacyLocation, String reason) throws IOException {
        loadProblem = "L'archivio delle partite era danneggiato (" + reason + "). "
                + "È stato messo da parte in " + backupDir + " e ne è stato creato uno nuovo.";
        if (fromLegacyLocation) {
            log.error("Old archive {} is unreadable ({}), not migrated", source, reason);
            loadProblem = null;
            return;
        }
        Files.createDirectories(backupDir);
        Path target = backupDir.resolve(source.getFileName() + ".corrupt-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        log.error("Archive {} is corrupt ({}); moved to {}", source, reason, target);
    }

    private void persist() {
        if (readOnly) {
            log.warn("Archive is read-only, changes are not saved");
            return;
        }
        try {
            if (!backedUpThisSession && Files.exists(file)) {
                AtomicFiles.backup(file, backupDir, BACKUPS_KEPT);
            }
            backedUpThisSession = true;
            JSONObject root = new JSONObject();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("nextId", nextId);
            JSONArray arr = new JSONArray();
            List<ArchivedGame> ordered = new ArrayList<>(games);
            ordered.sort(Comparator.comparingInt(ArchivedGame::id));
            for (ArchivedGame g : ordered) {
                JSONObject o = toJson(g);
                JSONObject original = legacyOriginals.get(g.id());
                if (original != null) {
                    o.put("legacy", original);
                }
                arr.put(o);
            }
            preserved.forEach(arr::put);
            root.put("games", arr);
            AtomicFiles.writeString(file, root.toString(2), true);
        } catch (IOException e) {
            log.error("Cannot save the archive to {}: {}", file, e.getMessage());
            org.example.javachess.Utils.ErrorReporter.showError("Archivio",
                    "Impossibile salvare la partita nell'archivio: " + e.getMessage());
        }
    }

    // =================================================================== JSON mapping

    static JSONObject toJson(ArchivedGame g) {
        JSONObject o = new JSONObject();
        o.put("id", g.id());
        o.put("mode", g.mode().name());
        o.put("label", g.label());
        o.put("white", g.white());
        o.put("black", g.black());
        o.put("result", g.result());
        o.put("termination", g.termination());
        o.put("opening", g.opening());
        o.put("timeControl", g.timeControl());
        o.put("playedAt", g.playedAt() == null ? JSONObject.NULL : g.playedAt().withNano(0).toString());
        o.put("initialFen", g.initialFen());
        o.put("finalFen", g.finalFen());
        o.put("moves", new JSONArray(g.movesUci()));
        o.put("pgn", toPgn(g));
        return o;
    }

    static ArchivedGame fromV2(JSONObject o) {
        int id = o.getInt("id");
        if (id <= 0) {
            throw new JSONException("invalid id " + id);
        }
        GameMode mode;
        try {
            mode = GameMode.valueOf(o.optString("mode", "UNKNOWN"));
        } catch (IllegalArgumentException e) {
            mode = GameMode.UNKNOWN;
        }
        List<String> moves = new ArrayList<>();
        JSONArray arr = o.optJSONArray("moves");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                moves.add(arr.getString(i));
            }
        }
        LocalDateTime when = null;
        String playedAt = o.optString("playedAt", "");
        if (!playedAt.isEmpty() && !"null".equals(playedAt)) {
            try {
                when = LocalDateTime.parse(playedAt);
            } catch (DateTimeParseException ignored) {
                // keep null
            }
        }
        return new ArchivedGame(id, mode, o.optString("label"), o.optString("white"), o.optString("black"),
                o.optString("result", "*"), o.optString("termination"), o.optString("opening"),
                o.optString("timeControl"), when, o.optString("initialFen", PgnCodec.START_FEN),
                o.optString("finalFen"), moves);
    }

    /** Converts a schema-1 record (also what the old {@link #saveGame} API receives). */
    static ArchivedGame fromLegacy(JSONObject o, int id) {
        String type = o.optString("type", "").replace("Plaver", "Player").trim();
        String initialFen = o.optString("initialFen", "");
        if (!PgnCodec.isValidFen(initialFen)) {
            initialFen = PgnCodec.START_FEN;
        }
        List<String> tokens = Arrays.asList(o.optString("pgn", "").trim().split("\\s+"));
        PgnCodec.Replay replay = PgnCodec.replay(initialFen, tokens);
        if (!replay.complete()) {
            log.debug("Old record {}: replay stopped at {}", id, replay.rejectedToken());
        }
        String[] res = mapLegacyResult(o.optString("result", ""), replay.board());
        GameMode mode = modeOf(type);
        String white = "?";
        String black = "?";
        if (mode == GameMode.PVP) {
            white = "Bianco";
            black = "Nero";
        }
        String opening = o.optString("opening", "").trim();
        if (opening.equalsIgnoreCase("Unknown") || opening.equalsIgnoreCase("Unknown Opening")
                || opening.equalsIgnoreCase("Error in API Call") || opening.equalsIgnoreCase("Lichess Online")) {
            opening = "";
        }
        String time = o.optString("time", "").trim();
        if (time.equals("∞") || time.equalsIgnoreCase("N/A")) {
            time = "";
        }
        return new ArchivedGame(id, mode, type, white, black, res[0], res[1], opening, time,
                parseLegacyDate(o.optString("datetime", "")), initialFen, replay.finalFen(), replay.uciMoves());
    }

    private static GameMode modeOf(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        if (t.contains("stockfish") || t.contains("maia") || t.contains("bot") || t.contains("computer")) {
            return GameMode.PVC;
        }
        if (t.contains("player vs player") || t.contains("pvp")) {
            return GameMode.PVP;
        }
        if (t.contains("lichess")) {
            return GameMode.LICHESS;
        }
        if (t.contains("browser") || t.contains("chess.com")) {
            return GameMode.BROWSER;
        }
        if (t.contains("puzzle")) {
            return GameMode.PUZZLE;
        }
        return GameMode.UNKNOWN;
    }

    private static final Pattern WINNER = Pattern.compile("(?i)(vince il|vince|wins?)\\s*(bianco|nero|white|black)");

    /**
     * Maps an old free-text result ("Scaccomatto! Vince il Bianco.", "Il Nero vince per tempo",
     * "Partita patta per Stallo.", "Partita interrotta", "1-0"...) to {PGN result, termination}.
     * The final position wins over the text when it is a mate or a rule draw.
     */
    static String[] mapLegacyResult(String text, Board finalBoard) {
        String t = text == null ? "" : text.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        String boardResult = finalBoard == null ? null : PgnCodec.resultOf(finalBoard);
        if (finalBoard != null && finalBoard.isMated()) {
            return new String[]{boardResult, "Scaccomatto"};
        }
        if (t.matches("1-0|0-1|1/2-1/2|\\*")) {
            return new String[]{t, ""};
        }
        if (lower.contains("tempo") || lower.contains("time")) {
            String winner = winnerSide(t);
            if (winner != null) {
                return new String[]{winner, "Tempo"};
            }
        }
        if (lower.contains("abband") || lower.contains("resign")) {
            String winner = winnerSide(t);
            return new String[]{winner != null ? winner : "*", "Abbandono"};
        }
        if (lower.contains("scaccomatto") || lower.contains("checkmate") || lower.contains("mate")) {
            String winner = winnerSide(t);
            if (winner != null) {
                return new String[]{winner, "Scaccomatto"};
            }
        }
        if (lower.contains("patta") || lower.contains("draw") || lower.contains("stallo")
                || lower.contains("ripetizione") || lower.contains("insufficiente") || lower.contains("50 mosse")) {
            String reason = t.replaceFirst("(?i)^partita patta per\\s*", "").replaceAll("\\.$", "").trim();
            return new String[]{"1/2-1/2", reason.isEmpty() || reason.equalsIgnoreCase("altro") ? "Patta" : reason};
        }
        if (boardResult != null) {
            return new String[]{boardResult, "Patta"};
        }
        if (lower.contains("interrott") || lower.contains("abort")) {
            return new String[]{"*", "Interrotta"};
        }
        String winner = winnerSide(t);
        if (winner != null) {
            return new String[]{winner, ""};
        }
        return new String[]{"*", lower.isEmpty() || lower.equals("unknown") ? "" : t.replaceAll("\\.$", "")};
    }

    private static String winnerSide(String text) {
        Matcher m = WINNER.matcher(text);
        String side = null;
        if (m.find()) {
            side = m.group(2).toLowerCase(Locale.ROOT);
        } else {
            String l = text.toLowerCase(Locale.ROOT);
            if (l.matches(".*\\b(il bianco|white)\\b.*\\bvince\\b.*|.*\\bwhite wins\\b.*")) {
                side = "bianco";
            } else if (l.matches(".*\\b(il nero|black)\\b.*\\bvince\\b.*|.*\\bblack wins\\b.*")) {
                side = "nero";
            }
        }
        if (side == null) {
            return null;
        }
        return side.equals("bianco") || side.equals("white") ? "1-0" : "0-1";
    }

    private static LocalDateTime parseLegacyDate(String text) {
        String t = text.replaceFirst("(?i)^partita del\\s*", "").trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(t, LEGACY_DATE);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(t);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static LocalDateTime parsePgnDate(String date, String time) {
        if (date == null || !date.matches("\\d{4}\\.\\d{2}\\.\\d{2}")) {
            return null;
        }
        try {
            String iso = date.replace('.', '-') + "T" + (time != null && time.matches("\\d{2}:\\d{2}(:\\d{2})?")
                    ? (time.length() == 5 ? time + ":00" : time) : "00:00:00");
            return LocalDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String eventFor(GameMode mode) {
        return switch (mode) {
            case PVC -> "Partita contro il computer";
            case PVP -> "Partita a due giocatori";
            case LICHESS -> "Partita su Lichess";
            case BROWSER -> "Partita online";
            case PUZZLE -> "Puzzle";
            default -> "Partita";
        };
    }

    /** "10+0" -> "600+0" (PGN TimeControl is in seconds); other formats are kept. */
    private static String toPgnTimeControl(String tc) {
        Matcher m = Pattern.compile("^(\\d+)\\s*\\+\\s*(\\d+)$").matcher(tc.trim());
        if (m.matches()) {
            return (Integer.parseInt(m.group(1)) * 60) + "+" + m.group(2);
        }
        return tc;
    }

    // =================================================================== validation

    /** Keeps the legal prefix of the moves, recomputes the final position and fills the result when decided. */
    private static ArchivedGame sanitize(ArchivedGame g) {
        String initial = PgnCodec.isValidFen(g.initialFen()) ? PgnCodec.boardOrStart(g.initialFen()).getFen()
                : PgnCodec.START_FEN;
        PgnCodec.Replay replay = PgnCodec.replay(initial, g.movesUci());
        if (!replay.complete()) {
            log.warn("Game {}: illegal move {} and following moves dropped", g.id(), replay.rejectedToken());
        }
        String result = g.result();
        String termination = g.termination();
        String decided = PgnCodec.resultOf(replay.board());
        if ("*".equals(result) && decided != null) {
            result = decided;
            if (termination.isEmpty() || termination.equalsIgnoreCase("Interrotta")) {
                termination = replay.board().isMated() ? "Scaccomatto" : "Patta";
            }
        }
        if (!result.matches("1-0|0-1|1/2-1/2|\\*")) {
            String[] mapped = mapLegacyResult(result, replay.board());
            result = mapped[0];
            if (termination.isEmpty()) {
                termination = mapped[1];
            }
        }
        return new ArchivedGame(g.id(), g.mode(), g.label(), g.white(), g.black(), result, termination,
                g.opening(), g.timeControl(), g.playedAt(), initial, replay.finalFen(), replay.uciMoves());
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }
}
