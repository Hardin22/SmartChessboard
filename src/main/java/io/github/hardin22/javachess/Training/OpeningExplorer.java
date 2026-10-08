package io.github.hardin22.javachess.Training;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Analysis.MoveText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline opening explorer: which moves are played from a position, and how often, in real games. Data:
 * {@code /review/popular.tsv}, positions of the first 20 plies of 3 million rated lichess games (CC0), with the
 * number of games of all players, of games with an average rating of 1600+ and of 2000+. Works without network,
 * as the Raspberry Pi often has none.
 */
public final class OpeningExplorer {

    private static final Logger log = LoggerFactory.getLogger(OpeningExplorer.class);

    /** Which games to count. */
    public enum Rating {
        ALL(0, "Tutti"), CLUB(1, "1600+"), EXPERT(2, "2000+");

        final int column;
        public final String label;

        Rating(int column, String label) {
            this.column = column;
            this.label = label;
        }
    }

    /**
     * A move played from the position.
     *
     * @param uci   the move
     * @param san   "Cf3" (Italian letters)
     * @param games games in the sample that played it (reached the position after it)
     * @param share fraction of the games from this position, 0..1
     */
    public record Candidate(String uci, String san, int games, double share) {
        /** "34%" (at least "<1%"). */
        public String percent() {
            long p = Math.round(share * 100);
            return p < 1 ? "<1%" : p + "%";
        }
    }

    public static final OpeningExplorer EMPTY = new OpeningExplorer(Map.of());
    private static volatile OpeningExplorer standard;

    /** Games by position key (placement + side to move): all, 1600+, 2000+. */
    private final Map<String, int[]> games;

    public OpeningExplorer(Map<String, int[]> gamesByKey) {
        this.games = Map.copyOf(gamesByKey);
    }

    /** The bundled data (loaded once, about 3 300 positions). */
    public static OpeningExplorer standard() {
        OpeningExplorer e = standard;
        if (e == null) {
            synchronized (OpeningExplorer.class) {
                e = standard;
                if (e == null) {
                    e = load();
                    standard = e;
                }
            }
        }
        return e;
    }

    private static OpeningExplorer load() {
        Map<String, int[]> m = new HashMap<>(8_192);
        try (InputStream in = OpeningExplorer.class.getResourceAsStream("/review/popular.tsv")) {
            if (in == null) {
                log.warn("opening statistics missing: explorer empty");
                return EMPTY;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t");
                if (f.length >= 4) {
                    m.put(f[0], new int[]{Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim()),
                            Integer.parseInt(f[3].trim())});
                }
            }
        } catch (Exception ex) {
            log.warn("opening statistics not loaded: {}", ex.toString());
            return EMPTY;
        }
        return new OpeningExplorer(m);
    }

    /** Games in the sample that reached {@code fen} (0 when rare or past the 20th ply). */
    public int games(String fen, Rating rating) {
        int[] g = games.get(key(fen));
        return g == null ? 0 : g[rating.column];
    }

    /** The moves played from {@code fen}, most played first; empty when the position is not in the sample. */
    public List<Candidate> moves(String fen, Rating rating) {
        Board board = new Board();
        try {
            board.loadFromFen(fen);
        } catch (RuntimeException e) {
            return List.of();
        }
        List<String> ucis = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        long total = 0;
        for (Move move : board.legalMoves()) {
            Board next = board.clone();
            next.doMove(move);
            int[] g = games.get(key(next.getFen()));
            int n = g == null ? 0 : g[rating.column];
            if (n > 0) {
                ucis.add(move.toString());
                counts.add(n);
                total += n;
            }
        }
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < ucis.size(); i++) {
            out.add(new Candidate(ucis.get(i), MoveText.italian(MoveText.sanOf(fen, ucis.get(i))), counts.get(i),
                    counts.get(i) / (double) total));
        }
        out.sort(Comparator.comparingInt(Candidate::games).reversed().thenComparing(Candidate::uci));
        return out;
    }

    public int size() {
        return games.size();
    }

    /** Piece placement and side to move. */
    static String key(String fen) {
        String[] p = fen.trim().split("\\s+");
        return p.length >= 2 ? p[0] + " " + p[1] : p[0] + " w";
    }
}
