package org.example.javachess.Services;

import org.example.javachess.Oggetti.Puzzle;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Read-only puzzle database built by {@link PuzzleIndexer}, memory-mapped: the heap holds only the header
 * (a few KB); the OS pages in the parts that are read.
 *
 * <pre>
 * long   header length
 * header magic "JCPZ", version, count, block size, theme names, min/max rating,
 *        ratingStart[r - min] = first record with rating >= r (records are sorted by rating),
 *        offsets of the theme bits, the block table and the data
 * themes count x ceil(themes / 8) bytes: bit t set = puzzle has theme t
 * blocks (count / blockSize + 1) x long: start of each compressed block inside the data
 * data   deflate-compressed blocks of blockSize records, one line per record:
 *        id,fen,moves,ratingDeviation,popularity,nbPlays,gameUrl-without-prefix,openingTags
 * </pre>
 *
 * A search inside a rating window looks at the theme bits only (one byte per eight themes per puzzle) and then
 * decompresses one block (about 6 KB) for the chosen puzzle.
 */
public final class PuzzleDatabase implements Closeable {

    static final int MAGIC = 0x4A43505A; // "JCPZ"
    static final int VERSION = 1;
    static final int BLOCK_SIZE = 64;
    static final String GAME_URL_PREFIX = "https://lichess.org/";
    private static final int RANDOM_PROBES = 256;

    private final FileChannel channel;
    private final MappedByteBuffer map;
    private final int count;
    private final int blockSize;
    private final List<String> themeNames;
    private final Map<String, Integer> themeBit = new LinkedHashMap<>();
    private final int themeBytes;
    private final int minRating;
    private final int[] ratingStart;
    private final long themesOffset;
    private final long blockTableOffset;
    private final long dataOffset;
    private final Random random = new Random();

    private int cachedBlock = -1;
    private String[] cachedRecords;

    private PuzzleDatabase(FileChannel channel) throws IOException {
        this.channel = channel;
        long size = channel.size();
        if (size > Integer.MAX_VALUE) {
            throw new IOException("Puzzle database larger than 2 GB is not supported");
        }
        this.map = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
        ByteBuffer header = map.duplicate();
        long headerLength = header.getLong();
        if (headerLength <= 0 || headerLength > size || header.getInt() != MAGIC) {
            throw new IOException("Not a puzzle database");
        }
        int version = header.getInt();
        if (version != VERSION) {
            throw new IOException("Unsupported puzzle database version " + version);
        }
        count = header.getInt();
        blockSize = header.getInt();
        int themes = header.getShort();
        List<String> names = new ArrayList<>(themes);
        for (int i = 0; i < themes; i++) {
            byte[] name = new byte[header.get() & 0xFF];
            header.get(name);
            names.add(new String(name, StandardCharsets.UTF_8));
            themeBit.put(names.get(i).toLowerCase(Locale.ROOT), i);
        }
        themeNames = List.copyOf(names);
        themeBytes = Math.max(1, (themes + 7) / 8);
        minRating = header.getShort();
        int maxRating = header.getShort();
        ratingStart = new int[maxRating - minRating + 2];
        for (int i = 0; i < ratingStart.length; i++) {
            ratingStart[i] = header.getInt();
        }
        themesOffset = header.getLong();
        blockTableOffset = header.getLong();
        dataOffset = header.getLong();
    }

    public static PuzzleDatabase open(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        try {
            return new PuzzleDatabase(channel);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e instanceof IOException io ? io : new IOException("Corrupted puzzle database", e);
        }
    }

    public int size() {
        return count;
    }

    public List<String> themes() {
        return themeNames;
    }

    /** First and last+1 record index with rating in [from, to]. */
    int[] ratingRange(int from, int to) {
        int maxRating = minRating + ratingStart.length - 2;
        int lo = Math.max(from, minRating);
        int hi = Math.min(to, maxRating);
        if (lo > hi) {
            return new int[]{0, 0};
        }
        return new int[]{ratingStart[lo - minRating], ratingStart[hi - minRating + 1]};
    }

    /**
     * A random puzzle with rating in target±range having at least one of {@code themes}
     * (null, empty or "Tutti" = any theme). Returns null when nothing matches.
     */
    public synchronized Puzzle random(int targetRating, int range, Collection<String> themes) {
        int[] window = ratingRange(targetRating - range, targetRating + range);
        int lo = window[0];
        int hi = window[1];
        if (lo >= hi) {
            return null;
        }
        byte[] mask = themeMask(themes);
        if (mask == null) {
            return read(lo + random.nextInt(hi - lo));
        }
        if (isEmpty(mask)) {
            return null; // requested themes do not exist in this database
        }
        for (int probe = 0; probe < RANDOM_PROBES; probe++) {
            int index = lo + random.nextInt(hi - lo);
            if (matches(index, mask)) {
                return read(index);
            }
        }
        // rare themes: scan the window from a random point
        int startAt = lo + random.nextInt(hi - lo);
        for (int i = 0; i < hi - lo; i++) {
            int index = lo + (startAt - lo + i) % (hi - lo);
            if (matches(index, mask)) {
                return read(index);
            }
        }
        return null;
    }

    /** Null means "any theme". */
    private byte[] themeMask(Collection<String> themes) {
        if (themes == null || themes.isEmpty() || themes.stream().anyMatch(t -> t.equalsIgnoreCase("Tutti"))) {
            return null;
        }
        byte[] mask = new byte[themeBytes];
        for (String theme : themes) {
            Integer bit = themeBit.get(theme.toLowerCase(Locale.ROOT));
            if (bit != null) {
                mask[bit / 8] |= (byte) (1 << (bit % 8));
            }
        }
        return mask;
    }

    private static boolean isEmpty(byte[] mask) {
        for (byte b : mask) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(int index, byte[] mask) {
        int base = (int) (themesOffset + (long) index * themeBytes);
        for (int i = 0; i < themeBytes; i++) {
            if ((map.get(base + i) & mask[i]) != 0) {
                return true;
            }
        }
        return false;
    }

    /** Decodes record {@code index} (0 = lowest rating). */
    public synchronized Puzzle read(int index) {
        int block = index / blockSize;
        if (block != cachedBlock) {
            cachedRecords = decompressBlock(block);
            cachedBlock = block;
        }
        String[] f = cachedRecords[index % blockSize].split(",", -1);
        List<String> themes = new ArrayList<>();
        int base = (int) (themesOffset + (long) index * themeBytes);
        for (int t = 0; t < themeNames.size(); t++) {
            if ((map.get(base + t / 8) & (1 << (t % 8))) != 0) {
                themes.add(themeNames.get(t));
            }
        }
        return new Puzzle(f[0], f[1], Arrays.asList(f[2].split(" ")), ratingOf(index), parse(f[3]), parse(f[4]),
                parse(f[5]), themes, f[6].isEmpty() ? "" : GAME_URL_PREFIX + f[6], f.length > 7 ? f[7] : "");
    }

    private int ratingOf(int index) {
        int lo = 0;
        int hi = ratingStart.length - 2;
        while (lo < hi) { // last r with ratingStart[r] <= index
            int mid = (lo + hi + 1) >>> 1;
            if (ratingStart[mid] <= index) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return minRating + lo;
    }

    private String[] decompressBlock(int block) {
        long start = map.getLong((int) (blockTableOffset + block * 8L));
        long end = map.getLong((int) (blockTableOffset + (block + 1) * 8L));
        byte[] compressed = new byte[(int) (end - start)];
        map.get((int) (dataOffset + start), compressed);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 3);
            byte[] buffer = new byte[16 * 1024];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8).split("\n");
        } catch (DataFormatException e) {
            throw new IllegalStateException("Corrupted puzzle block " + block, e);
        } finally {
            inflater.end();
        }
    }

    private static int parse(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
