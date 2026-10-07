package io.github.hardin22.javachess.Services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * Builds the compact puzzle database ({@link PuzzleDatabase}) from the Lichess puzzle CSV
 * (https://database.lichess.org/#puzzles, columns
 * {@code PuzzleId,FEN,Moves,Rating,RatingDeviation,Popularity,NbPlays,Themes,GameUrl,OpeningTags}).
 *
 * <p>Run it with {@code scripts/build-puzzle-db.sh} or
 * {@code java -cp ... io.github.hardin22.javachess.Services.PuzzleIndexer lichess_db_puzzle.csv data/puzzles.db}.
 * It needs about 300 MB of heap for the full 5.7 million puzzles and writes the file atomically.</p>
 */
public final class PuzzleIndexer {

    private static final Logger log = LoggerFactory.getLogger(PuzzleIndexer.class);

    /** Ignores puzzles below this popularity (-100..100) when given with --min-popularity. */
    private final int minPopularity;

    public PuzzleIndexer(int minPopularity) {
        this.minPopularity = minPopularity;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: PuzzleIndexer <puzzles.csv> <puzzles.db> [--min-popularity N]");
            System.exit(2);
        }
        int minPopularity = -101;
        for (int i = 2; i + 1 < args.length; i++) {
            if (args[i].equals("--min-popularity")) {
                minPopularity = Integer.parseInt(args[i + 1]);
            }
        }
        Stats stats = new PuzzleIndexer(minPopularity).build(Path.of(args[0]), Path.of(args[1]));
        System.out.println(stats);
    }

    /** Result of a build. */
    public record Stats(int puzzles, int themes, long bytes, long millis) {
        @Override
        public String toString() {
            return String.format("%d puzzles, %d themes, %.1f MB, %.1f s", puzzles, themes, bytes / 1048576.0, millis / 1000.0);
        }
    }

    public Stats build(Path csv, Path output) throws IOException {
        long start = System.currentTimeMillis();
        Scan scan = scan(csv);
        log.info("Scanned {} puzzles, {} themes in {} ms", scan.count, scan.themes.size(), System.currentTimeMillis() - start);

        // counting sort by rating: records end up ordered by rating, so a rating range is a contiguous slice
        int minRating = Integer.MAX_VALUE;
        int maxRating = Integer.MIN_VALUE;
        for (int i = 0; i < scan.count; i++) {
            minRating = Math.min(minRating, scan.ratings[i]);
            maxRating = Math.max(maxRating, scan.ratings[i]);
        }
        if (scan.count == 0) {
            minRating = maxRating = 0;
        }
        int[] ratingStart = new int[maxRating - minRating + 2];
        for (int i = 0; i < scan.count; i++) {
            ratingStart[scan.ratings[i] - minRating + 1]++;
        }
        for (int r = 1; r < ratingStart.length; r++) {
            ratingStart[r] += ratingStart[r - 1];
        }
        int[] order = new int[scan.count];
        int[] next = Arrays.copyOf(ratingStart, ratingStart.length);
        for (int i = 0; i < scan.count; i++) {
            order[next[scan.ratings[i] - minRating]++] = i;
        }

        int themeBytes = Math.max(1, (scan.themes.size() + 7) / 8);
        List<String> themeNames = new ArrayList<>(scan.themes.keySet());
        int blockCount = (scan.count + PuzzleDatabase.BLOCK_SIZE - 1) / PuzzleDatabase.BLOCK_SIZE;

        Path tmp = output.resolveSibling(output.getFileName() + ".tmp");
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (RandomAccessFile csvFile = new RandomAccessFile(csv.toFile(), "r");
             FileChannel csvChannel = csvFile.getChannel();
             FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            // header
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            DataOutputStream header = new DataOutputStream(headerBytes);
            header.writeInt(PuzzleDatabase.MAGIC);
            header.writeInt(PuzzleDatabase.VERSION);
            header.writeInt(scan.count);
            header.writeInt(PuzzleDatabase.BLOCK_SIZE);
            header.writeShort(themeNames.size());
            for (String theme : themeNames) {
                byte[] name = theme.getBytes(StandardCharsets.UTF_8);
                header.writeByte(name.length);
                header.write(name);
            }
            header.writeShort(minRating);
            header.writeShort(maxRating);
            for (int value : ratingStart) {
                header.writeInt(value);
            }
            header.flush();
            long themesOffset = 8 + headerBytes.size() + 3 * 8L;
            long blockTableOffset = themesOffset + (long) scan.count * themeBytes;
            long dataOffset = blockTableOffset + (blockCount + 1L) * 8;
            header.writeLong(themesOffset);
            header.writeLong(blockTableOffset);
            header.writeLong(dataOffset);
            header.flush();
            ByteBuffer headerBuffer = ByteBuffer.allocate(8 + headerBytes.size());
            headerBuffer.putLong(headerBytes.size());
            headerBuffer.put(headerBytes.toByteArray());
            headerBuffer.flip();
            writeFully(out, headerBuffer, 0);

            // theme bits in rating order
            ByteBuffer themesBuffer = ByteBuffer.allocate(1 << 16);
            long themesPosition = themesOffset;
            for (int i = 0; i < scan.count; i++) {
                if (themesBuffer.remaining() < themeBytes) {
                    themesBuffer.flip();
                    themesPosition += writeFully(out, themesBuffer, themesPosition);
                    themesBuffer.clear();
                }
                themesBuffer.put(scan.themeBits, order[i] * scan.themeBytes, themeBytes);
            }
            themesBuffer.flip();
            writeFully(out, themesBuffer, themesPosition);

            // records, compressed in blocks
            long[] blockOffsets = new long[blockCount + 1];
            out.position(dataOffset);
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            ByteArrayOutputStream raw = new ByteArrayOutputStream(16 * 1024);
            byte[] compressBuffer = new byte[64 * 1024];
            ByteBuffer lineBuffer = ByteBuffer.allocate(8192);
            long written = 0;
            for (int block = 0; block < blockCount; block++) {
                raw.reset();
                int first = block * PuzzleDatabase.BLOCK_SIZE;
                int last = Math.min(scan.count, first + PuzzleDatabase.BLOCK_SIZE);
                for (int i = first; i < last; i++) {
                    byte[] record = compactRecord(readLine(csvChannel, scan.offsets[order[i]], lineBuffer));
                    raw.write(record);
                    raw.write('\n');
                }
                deflater.reset();
                deflater.setInput(raw.toByteArray());
                deflater.finish();
                blockOffsets[block] = written;
                while (!deflater.finished()) {
                    int n = deflater.deflate(compressBuffer);
                    out.write(ByteBuffer.wrap(compressBuffer, 0, n));
                    written += n;
                }
            }
            blockOffsets[blockCount] = written;
            deflater.end();
            ByteBuffer table = ByteBuffer.allocate(blockOffsets.length * 8);
            for (long offset : blockOffsets) {
                table.putLong(offset);
            }
            table.flip();
            writeFully(out, table, blockTableOffset);
            out.force(false);
        }
        Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        long millis = System.currentTimeMillis() - start;
        Stats stats = new Stats(scan.count, themeNames.size(), Files.size(output), millis);
        log.info("Puzzle database written to {}: {}", output.toAbsolutePath(), stats);
        return stats;
    }

    /** First pass: rating, themes and file offset of every puzzle. */
    private static final class Scan {
        final Map<String, Integer> themes = new LinkedHashMap<>();
        int count;
        short[] ratings = new short[1 << 20];
        long[] offsets = new long[1 << 20];
        /** Fixed 16 bytes per puzzle during the scan (up to 128 themes). */
        final int themeBytes = 16;
        byte[] themeBits = new byte[(1 << 20) * 16];

        void add(int rating, long offset, String themeList) {
            if (count == ratings.length) {
                int size = count * 2;
                ratings = Arrays.copyOf(ratings, size);
                offsets = Arrays.copyOf(offsets, size);
                themeBits = Arrays.copyOf(themeBits, size * themeBytes);
            }
            ratings[count] = (short) rating;
            offsets[count] = offset;
            int start = 0;
            for (int i = 0; i <= themeList.length(); i++) {
                if (i == themeList.length() || themeList.charAt(i) == ' ') {
                    if (i > start) {
                        String theme = themeList.substring(start, i);
                        Integer bit = themes.get(theme);
                        if (bit == null && themes.size() < themeBytes * 8) {
                            bit = themes.size();
                            themes.put(theme, bit);
                        }
                        if (bit != null) {
                            themeBits[count * themeBytes + bit / 8] |= (byte) (1 << (bit % 8));
                        }
                    }
                    start = i + 1;
                }
            }
            count++;
        }
    }

    private Scan scan(Path csv) throws IOException {
        Scan scan = new Scan();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(csv), 1 << 20)) {
            byte[] line = new byte[4096];
            int length = 0;
            long offset = 0;
            long lineStart = 0;
            boolean header = true;
            int b;
            while ((b = in.read()) != -1) {
                offset++;
                if (b == '\n') {
                    if (header) {
                        header = false;
                    } else if (length > 0) {
                        parseForScan(scan, line, length, lineStart);
                    }
                    length = 0;
                    lineStart = offset;
                } else if (length < line.length) {
                    line[length++] = (byte) b;
                }
            }
            if (length > 0 && !header) {
                parseForScan(scan, line, length, lineStart);
            }
        }
        return scan;
    }

    private void parseForScan(Scan scan, byte[] line, int length, long offset) {
        // fields: 0 id, 1 fen, 2 moves, 3 rating, 4 rd, 5 popularity, 6 plays, 7 themes, 8 url, 9 opening
        int[] commas = new int[9];
        int found = 0;
        for (int i = 0; i < length && found < 9; i++) {
            if (line[i] == ',') {
                commas[found++] = i;
            }
        }
        if (found < 8) {
            return;
        }
        try {
            int rating = parseInt(line, commas[2] + 1, commas[3]);
            int popularity = parseInt(line, commas[4] + 1, commas[5]);
            if (popularity < minPopularity || rating < 0 || rating > Short.MAX_VALUE) {
                return;
            }
            String themes = new String(line, commas[6] + 1, commas[7] - commas[6] - 1, StandardCharsets.US_ASCII);
            scan.add(rating, offset, themes);
        } catch (NumberFormatException e) {
            // malformed line: skipped
        }
    }

    private static int parseInt(byte[] text, int from, int to) {
        if (from >= to) {
            throw new NumberFormatException("empty");
        }
        int value = 0;
        boolean negative = text[from] == '-';
        for (int i = negative ? from + 1 : from; i < to; i++) {
            int digit = text[i] - '0';
            if (digit < 0 || digit > 9) {
                throw new NumberFormatException();
            }
            value = value * 10 + digit;
        }
        return negative ? -value : value;
    }

    private static int writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            total += channel.write(buffer, position + total);
        }
        return total;
    }

    private static String readLine(FileChannel channel, long offset, ByteBuffer buffer) throws IOException {
        buffer.clear();
        channel.read(buffer, offset);
        buffer.flip();
        int end = 0;
        while (end < buffer.limit() && buffer.get(end) != '\n' && buffer.get(end) != '\r') {
            end++;
        }
        byte[] bytes = new byte[end];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The CSV line without rating and themes (kept in the index) and without the URL prefix. */
    static byte[] compactRecord(String csvLine) {
        String[] f = csvLine.split(",", -1);
        String url = f.length > 8 ? f[8] : "";
        if (url.startsWith(PuzzleDatabase.GAME_URL_PREFIX)) {
            url = url.substring(PuzzleDatabase.GAME_URL_PREFIX.length());
        }
        String record = String.join(",", f[0], f[1], f[2], f[4], f[5], f[6], url, f.length > 9 ? f[9] : "");
        return record.getBytes(StandardCharsets.UTF_8);
    }
}
