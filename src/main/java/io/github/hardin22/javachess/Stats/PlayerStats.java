package io.github.hardin22.javachess.Stats;

import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.ArchivedGame.GameMode;
import io.github.hardin22.javachess.Utils.ConfigManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Personal statistics from the archive (and the saved reviews): results overall and by colour, by kind of game and
 * by opponent, the openings played with their results, the accuracy and how it is going, recent form and streaks.
 * Only games where the local player is known count: against the computer, and online games under the player's
 * Lichess / Chess.com name (two-player games at the board have no "me").
 */
public final class PlayerStats {

    /** Wins, draws and losses. */
    public record Score(int wins, int draws, int losses) {
        public static final Score NONE = new Score(0, 0, 0);

        public int games() {
            return wins + draws + losses;
        }

        /** Points in percent (win = 1, draw = ½), 0 when no game. */
        public double percent() {
            return games() == 0 ? 0 : (wins + draws * 0.5) * 100.0 / games();
        }

        /** "62%" for the interface. */
        public String percentText() {
            return Math.round(percent()) + "%";
        }

        Score plus(int outcome) {
            return outcome > 0 ? new Score(wins + 1, draws, losses)
                    : outcome < 0 ? new Score(wins, draws, losses + 1) : new Score(wins, draws + 1, losses);
        }
    }

    /** A row of a table (opponent, opening, kind of game). */
    public record Row(String name, Score score, double averageAccuracy, int reviewed) {
        /** "84,2" or "—". */
        public String accuracyText() {
            return reviewed == 0 ? "—" : String.format(Locale.ITALIAN, "%.1f", averageAccuracy);
        }
    }

    /** One counted game. */
    public record Entry(ArchivedGame game, boolean white, int outcome, Double accuracy) {
    }

    /**
     * The statistics.
     *
     * @param total          all counted games with a result
     * @param asWhite        with White
     * @param asBlack        with Black
     * @param byMode         by kind of game ("Contro il computer", "Lichess", ...), most played first
     * @param byOpponent     by opponent, most played first (at most 10)
     * @param openings       by opening family, most played first (at most 10)
     * @param accuracy       average accuracy over the reviewed games (NaN when none)
     * @param reviewed       reviewed games among the counted ones
     * @param recentAccuracy average of the last 10 reviewed games (NaN when none)
     * @param accuracyTrend  last 10 minus the 10 before, in points (NaN without 2 × 5 reviewed games)
     * @param form           outcomes of the last 10 games, newest first (1 / 0 / -1)
     * @param currentStreak  wins in a row now
     * @param bestStreak     longest run of wins
     * @param unfinished     counted games without a result (interrupted)
     * @param entries        every counted game, newest first
     */
    public record Stats(Score total, Score asWhite, Score asBlack, List<Row> byMode, List<Row> byOpponent,
                        List<Row> openings, double accuracy, int reviewed, double recentAccuracy,
                        double accuracyTrend, List<Integer> form, int currentStreak, int bestStreak, int unfinished,
                        List<Entry> entries) {

        public Stats {
            byMode = List.copyOf(byMode);
            byOpponent = List.copyOf(byOpponent);
            openings = List.copyOf(openings);
            form = List.copyOf(form);
            entries = List.copyOf(entries);
        }

        /** "84,2" or "—". */
        public String accuracyText() {
            return Double.isNaN(accuracy) ? "—" : String.format(Locale.ITALIAN, "%.1f", accuracy);
        }

        /** "+3,1" / "−2,0" / "" when unknown. */
        public String trendText() {
            if (Double.isNaN(accuracyTrend)) {
                return "";
            }
            String v = String.format(Locale.ITALIAN, "%.1f", Math.abs(accuracyTrend));
            return (accuracyTrend >= 0 ? "+" : "−") + v;
        }
    }

    /** Who "I" am: names the archive uses for the local player. */
    public record Identity(Set<String> names) {
        /**
         * "Giocatore"/"Tu" (games against the computer) plus the online usernames in the settings and the ones used
         * to import games.
         */
        public static Identity fromSettings() {
            java.util.Set<String> n = new java.util.HashSet<>(Set.of("giocatore", "tu"));
            String lichess = ConfigManager.getProperty("lichess.username", "").trim();
            if (!lichess.isEmpty()) {
                n.add(lichess.toLowerCase(Locale.ROOT));
            }
            String chessCom = ConfigManager.getProperty("chess.com.username", "").trim();
            if (!chessCom.isEmpty() && !chessCom.contains("@")) {
                n.add(chessCom.toLowerCase(Locale.ROOT));
            }
            for (OnlineImport.Source s : OnlineImport.Source.values()) {
                String imported = OnlineImport.lastUsername(s);
                if (!imported.isEmpty()) {
                    n.add(imported.toLowerCase(Locale.ROOT));
                }
            }
            return new Identity(Set.copyOf(n));
        }

        boolean is(String archivedName) {
            if (archivedName == null) {
                return false;
            }
            String plain = archivedName.replaceAll("\\s*\\(\\d{3,4}\\)\\s*$", "").trim().toLowerCase(Locale.ROOT);
            return names.contains(plain);
        }
    }

    private PlayerStats() {
    }

    /** Statistics of the shared archive and review store (blocking: call it off the JavaFX thread). */
    public static Stats compute() {
        return compute(io.github.hardin22.javachess.Services.GameArchiveService.getInstance().list(),
                ReviewStore.get().summaries(), Identity.fromSettings());
    }

    /**
     * @param games   archived games, newest first
     * @param reviews saved review summaries by {@link ReviewStore#key(ArchivedGame)}
     */
    public static Stats compute(List<ArchivedGame> games, Map<String, ReviewStore.Summary> reviews, Identity me) {
        List<Entry> entries = new ArrayList<>();
        int unfinished = 0;
        List<ArchivedGame> sorted = new ArrayList<>(games);
        sorted.sort(Comparator.comparing(ArchivedGame::playedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(ArchivedGame::id).reversed());
        for (ArchivedGame g : sorted) {
            Boolean white = mySide(g, me);
            if (white == null) {
                continue;
            }
            Integer outcome = outcome(g.result(), white);
            if (outcome == null) {
                unfinished++;
                continue;
            }
            ReviewStore.Summary s = reviews.get(ReviewStore.key(g));
            Double acc = s == null || Double.isNaN(s.accuracy(white)) ? null : s.accuracy(white);
            entries.add(new Entry(g, white, outcome, acc));
        }
        Score total = Score.NONE;
        Score w = Score.NONE;
        Score b = Score.NONE;
        for (Entry e : entries) {
            total = total.plus(e.outcome());
            if (e.white()) {
                w = w.plus(e.outcome());
            } else {
                b = b.plus(e.outcome());
            }
        }
        List<Double> accs = entries.stream().map(Entry::accuracy).filter(a -> a != null).toList();
        double avg = mean(accs, 0, accs.size());
        double recent = mean(accs, 0, Math.min(10, accs.size()));
        double trend = Double.NaN;
        if (accs.size() >= 10) {
            int n = Math.min(10, accs.size() / 2);
            trend = mean(accs, 0, n) - mean(accs, n, Math.min(2 * n, accs.size()));
        }
        List<Integer> form = entries.stream().limit(10).map(Entry::outcome).toList();
        int current = 0;
        for (Entry e : entries) {
            if (e.outcome() > 0) {
                current++;
            } else {
                break;
            }
        }
        int best = 0;
        int run = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            run = entries.get(i).outcome() > 0 ? run + 1 : 0;
            best = Math.max(best, run);
        }
        return new Stats(total, w, b, group(entries, e -> modeName(e.game().mode()), 10),
                group(entries, e -> opponent(e), 10), group(entries, e -> openingFamily(e.game().opening()), 10),
                avg, accs.size(), recent, trend, form, current, best, unfinished, entries);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The local player's side in an archived game (TRUE = White), empty when it cannot be told (two players at the
     * board, someone else's game). For "Rigioca i tuoi errori" and per-game results.
     */
    public static java.util.Optional<Boolean> localSide(ArchivedGame g) {
        return java.util.Optional.ofNullable(mySide(g, Identity.fromSettings()));
    }

    /** True/false when the local player had White/Black, null when the game is not "mine". */
    static Boolean mySide(ArchivedGame g, Identity me) {
        if (g.mode() == GameMode.PVP || g.mode() == GameMode.PUZZLE) {
            return null;
        }
        boolean w = me.is(g.white());
        boolean b = me.is(g.black());
        if (w == b) {
            return null; // neither, or both (cannot tell)
        }
        return w;
    }

    /** 1 / 0 / -1 from the player's side, null without a result. */
    static Integer outcome(String result, boolean white) {
        return switch (result) {
            case "1-0" -> white ? 1 : -1;
            case "0-1" -> white ? -1 : 1;
            case "1/2-1/2" -> 0;
            default -> null;
        };
    }

    static String modeName(GameMode m) {
        return switch (m) {
            case PVC -> "Contro il computer";
            case LICHESS -> "Lichess";
            case BROWSER -> "Online (browser)";
            case IMPORTED -> "Importate";
            default -> "Altro";
        };
    }

    private static String opponent(Entry e) {
        String name = e.white() ? e.game().black() : e.game().white();
        return name == null || name.isBlank() || name.equals("?") ? "Sconosciuto" : name.trim();
    }

    /**
     * Opening family in Italian: without the ECO code and the variation ("C50 Italian Game: Giuoco Piano" →
     * "Partita Italiana", the same for a name already in Italian); "Sconosciuta" when the archive has no name.
     */
    static String openingFamily(String opening) {
        if (opening == null || opening.isBlank() || opening.equalsIgnoreCase("Unknown")
                || opening.equals("Opening Name")) {
            return "Sconosciuta";
        }
        String s = opening.trim().replaceFirst("^[A-E]\\d\\d\\s+", "");
        int colon = s.indexOf(':');
        if (colon > 0) {
            s = s.substring(0, colon);
        }
        int comma = s.indexOf(',');
        if (comma > 0) {
            s = s.substring(0, comma);
        }
        return io.github.hardin22.javachess.Analysis.OpeningNames.italian(s.trim()); // one name per family
    }

    private static List<Row> group(List<Entry> entries, java.util.function.Function<Entry, String> key, int max) {
        Map<String, Score> scores = new LinkedHashMap<>();
        Map<String, double[]> acc = new LinkedHashMap<>();
        for (Entry e : entries) {
            String k = key.apply(e);
            scores.merge(k, Score.NONE.plus(e.outcome()), (a, x) -> a.plus(e.outcome()));
            double[] a = acc.computeIfAbsent(k, x -> new double[2]);
            if (e.accuracy() != null) {
                a[0] += e.accuracy();
                a[1]++;
            }
        }
        List<Row> rows = new ArrayList<>();
        scores.forEach((k, s) -> {
            double[] a = acc.get(k);
            rows.add(new Row(k, s, a[1] == 0 ? Double.NaN : a[0] / a[1], (int) a[1]));
        });
        rows.sort(Comparator.comparingInt((Row r) -> r.score().games()).reversed());
        return rows.size() > max ? rows.subList(0, max) : rows;
    }

    private static double mean(List<Double> values, int from, int to) {
        if (to <= from) {
            return Double.NaN;
        }
        double s = 0;
        for (int i = from; i < to; i++) {
            s += values.get(i);
        }
        return Math.round(s / (to - from) * 10) / 10.0;
    }

    /** Games after {@code since} only (period filter: last 30 days...). */
    public static List<ArchivedGame> since(List<ArchivedGame> games, LocalDateTime since) {
        return games.stream().filter(g -> g.playedAt() != null && !g.playedAt().isBefore(since)).toList();
    }
}
