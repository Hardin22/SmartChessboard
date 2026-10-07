package io.github.hardin22.javachess.Engine.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Named opening positions for the Book label: a move is "Book" while the position it reaches is a named opening
 * position (the game leaves the book at its first move that does not). Offline, from {@code /review/openings.tsv}
 * (lichess-org/chess-openings, CC0, ~3.6k positions), keyed by piece placement and side to move.
 */
public final class OpeningBook {

    private static final Logger log = LoggerFactory.getLogger(OpeningBook.class);

    /** A book with no positions (no Book labels). */
    public static final OpeningBook NONE = new OpeningBook(Map.of());

    private static volatile OpeningBook standard;

    private final Map<String, String> names;
    private final Set<String> theory;

    public OpeningBook(Map<String, String> namesByKey) {
        this(namesByKey, Set.of());
    }

    /**
     * @param namesByKey     named opening positions ({@link #key})
     * @param theoryKeys     unnamed positions on the way to a named one (also book moves)
     */
    public OpeningBook(Map<String, String> namesByKey, Set<String> theoryKeys) {
        this.names = Map.copyOf(namesByKey);
        this.theory = Set.copyOf(theoryKeys);
    }

    /** The bundled book (loaded once). */
    public static OpeningBook standard() {
        OpeningBook b = standard;
        if (b == null) {
            synchronized (OpeningBook.class) {
                b = standard;
                if (b == null) {
                    b = load();
                    standard = b;
                }
            }
        }
        return b;
    }

    private static OpeningBook load() {
        Map<String, String> m = new HashMap<>(8_192);
        try (InputStream in = OpeningBook.class.getResourceAsStream("/review/openings.tsv")) {
            if (in == null) {
                log.warn("opening book resource missing: no Book labels");
                return NONE;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t");
                if (f.length >= 3) {
                    m.putIfAbsent(f[0], f[1] + " " + f[2]);
                }
            }
        } catch (Exception e) {
            log.warn("opening book not loaded: {}", e.toString());
            return NONE;
        }
        Set<String> t = new HashSet<>(8_192);
        try (InputStream in = OpeningBook.class.getResourceAsStream("/review/theory.tsv")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        t.add(line.trim());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("opening theory not loaded: {}", e.toString());
        }
        return new OpeningBook(m, t);
    }

    /** "ECO Name" ("C50 Italian Game") of the position, if it is a named opening position. */
    public Optional<String> nameAfter(String fen) {
        return Optional.ofNullable(names.get(key(fen)));
    }

    /** True when {@code fen} is a named opening position. */
    public boolean contains(String fen) {
        return names.containsKey(key(fen));
    }

    /** True when {@code fen} is a named opening position or on the way to one (a book move leads there). */
    public boolean isTheory(String fen) {
        String k = key(fen);
        return names.containsKey(k) || theory.contains(k);
    }

    public int size() {
        return names.size();
    }

    /** Piece placement and side to move of a FEN. */
    static String key(String fen) {
        String[] p = fen.trim().split("\\s+");
        return p.length >= 2 ? p[0] + " " + p[1] : p[0] + " w";
    }
}
