package org.example.javachess.Engine;

import org.example.javachess.Utils.ConfigManager;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * How many engine processes we can afford and how big their hash is, from the RAM of the machine.
 *
 * <p>Memory model measured on linux/arm64 (Stockfish 19 official aarch64 binary, Debian 12 container limited to
 * 4 CPUs / 1 GB, {@code /proc/PID/status}): one process = 112 MB of NNUE network in shared memory
 * ({@code RssShmem}, mapped ONCE for all Stockfish processes) + 97 MB of binary pages ({@code RssFile}, shared and
 * evictable) + ~30 MB private + the Hash. Measured cgroup totals with Hash 16: 1 process 260 MB, 2 processes
 * 306 MB, 3 processes 351 MB, i.e. ~46 MB per extra process. Hash 64 adds 48 MB to its process. (macOS reports
 * ~360 MB per process because it counts the shared pages in every RSS.) lc0 + a Maia network is a separate
 * binary: ~100-200 MB.</p>
 *
 * <table>
 *   <caption>Plans (MemTotal is a bit below the nominal size)</caption>
 *   <tr><th>RAM</th><th>Stockfish processes</th><th>analysis Hash</th><th>bot Hash</th><th>threads</th><th>idle close</th></tr>
 *   <tr><td>1 GB (Pi 4/3)</td><td>1: bot, analysis, LED verdicts and review share it (priority queue)</td><td>16</td><td>-</td><td>2</td><td>2 min</td></tr>
 *   <tr><td>2 GB</td><td>2: analysis(+LEDs+review) and bot</td><td>32</td><td>16</td><td>2</td><td>5 min</td></tr>
 *   <tr><td>4 GB (Pi 4/5)</td><td>2</td><td>64</td><td>16</td><td>2</td><td>10 min</td></tr>
 *   <tr><td>8 GB+ (Pi 5, desktop)</td><td>2</td><td>128</td><td>32</td><td>cores/2 (max 4)</td><td>10 min</td></tr>
 * </table>
 * Engine footprint with these plans: ~260 MB (1 GB), ~350 MB (2 GB), ~400 MB (4 GB) for Stockfish, plus lc0 only
 * while a Maia profile plays. {@code stockfish.threads} / {@code stockfish.hash} in config.properties override the
 * analysis numbers; {@code -Djavachess.ram.mb=N} simulates another board.
 *
 * @param ramMb             detected RAM (MemTotal)
 * @param tier              human readable tier ("1 GB"...)
 * @param botSharesAnalysis true when the Stockfish bot runs on the analysis process instead of its own
 * @param analysisHashMb    Hash of the analysis process
 * @param botHashMb         Hash of the bot process
 * @param analysisThreads   Threads of the analysis process
 * @param idleCloseMs       idle engine processes are closed after this long (restarted lazily)
 */
public record ProcessPlan(long ramMb, String tier, boolean botSharesAnalysis, int analysisHashMb, int botHashMb,
                          int analysisThreads, long idleCloseMs) {

    private static volatile ProcessPlan detected;

    /** Plan for this machine (cached). */
    public static ProcessPlan detect() {
        ProcessPlan p = detected;
        if (p == null) {
            p = forRam(totalRamMb(), Runtime.getRuntime().availableProcessors());
            detected = p;
        }
        return p;
    }

    public static ProcessPlan forRam(long ramMb, int cores) {
        int threads = Math.max(1, Math.min(2, cores));
        ProcessPlan p;
        if (ramMb < 1_400) {
            p = new ProcessPlan(ramMb, "1 GB", true, 16, 16, threads, 2 * 60_000L);
        } else if (ramMb < 2_800) {
            p = new ProcessPlan(ramMb, "2 GB", false, 32, 16, threads, 5 * 60_000L);
        } else if (ramMb < 6_000) {
            p = new ProcessPlan(ramMb, "4 GB", false, 64, 16, threads, 10 * 60_000L);
        } else {
            p = new ProcessPlan(ramMb, "8 GB+", false, 128, 32, Math.max(1, Math.min(cores / 2, 4)), 10 * 60_000L);
        }
        int cfgThreads = ConfigManager.getIntProperty("stockfish.threads", p.analysisThreads);
        int cfgHash = ConfigManager.getIntProperty("stockfish.hash", p.analysisHashMb);
        return new ProcessPlan(p.ramMb, p.tier, p.botSharesAnalysis, Math.max(1, cfgHash), p.botHashMb,
                Math.max(1, cfgThreads), p.idleCloseMs);
    }

    /** MemTotal from /proc/meminfo on Linux, the OS bean elsewhere, or {@code -Djavachess.ram.mb}. */
    static long totalRamMb() {
        Long forced = Long.getLong("javachess.ram.mb");
        if (forced != null) {
            return forced;
        }
        try {
            Path meminfo = Path.of("/proc/meminfo");
            if (Files.isReadable(meminfo)) {
                for (String line : Files.readAllLines(meminfo)) {
                    if (line.startsWith("MemTotal:")) {
                        return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        try {
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getTotalMemorySize() / (1024 * 1024);
            }
        } catch (Throwable ignored) {
            // not available
        }
        return 4_096;
    }

    public String describe() {
        return String.format(Locale.ROOT, "%s RAM (%d MB): %s, analysis Hash %d MB x%d threads, bot Hash %d MB, idle close %d s",
                tier, ramMb, botSharesAnalysis ? "1 Stockfish process (bot shares analysis)" : "2 Stockfish processes",
                analysisHashMb, analysisThreads, botHashMb, idleCloseMs / 1000);
    }
}
