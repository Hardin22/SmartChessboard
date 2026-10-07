package io.github.hardin22.javachess.Oggetti;

import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Two-sided chess clock measured with a monotonic clock (no accumulated sleep error). Thread-safe, no UI.
 * Time is shown as mm:ss, and with tenths (mm:ss.t) below ten seconds.
 */
public final class ChessClock {

    public enum Side {
        WHITE, BLACK;

        public Side other() {
            return this == WHITE ? BLACK : WHITE;
        }
    }

    private static final long TENTH_NS = 100_000_000L;
    private static final long SECOND_NS = 1_000_000_000L;
    private static final long TENTHS_BELOW_NS = 10 * SECOND_NS;

    private final LongSupplier nanoTime;
    private final long incrementNs;
    private final long[] remainingNs = new long[2];
    private Side running;
    private long runningSince;

    public ChessClock(long initialSeconds, long incrementSeconds) {
        this(initialSeconds, incrementSeconds, System::nanoTime);
    }

    public ChessClock(long initialSeconds, long incrementSeconds, LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.incrementNs = incrementSeconds * SECOND_NS;
        remainingNs[0] = initialSeconds * SECOND_NS;
        remainingNs[1] = initialSeconds * SECOND_NS;
    }

    /** Starts {@code side}'s clock, stopping the other one. */
    public synchronized void start(Side side) {
        settle();
        running = side;
        runningSince = nanoTime.getAsLong();
    }

    /** Stops the running clock (if it is {@code side}'s, or any when side is null). */
    public synchronized void stop(Side side) {
        if (running != null && (side == null || running == side)) {
            settle();
            running = null;
        }
    }

    public synchronized void addIncrement(Side side) {
        settle();
        remainingNs[side.ordinal()] += incrementNs;
    }

    public synchronized Side running() {
        return running;
    }

    public synchronized long remainingNanos(Side side) {
        long left = remainingNs[side.ordinal()];
        if (running == side) {
            left -= nanoTime.getAsLong() - runningSince;
        }
        return Math.max(0, left);
    }

    public long remainingMillis(Side side) {
        return remainingNanos(side) / 1_000_000L;
    }

    public synchronized boolean isFlagged(Side side) {
        return remainingNanos(side) == 0;
    }

    /** Text for {@code side}: "05:00", "00:09.4". */
    public String display(Side side) {
        return format(remainingNanos(side));
    }

    /**
     * Nanoseconds until the text of the running clock may change (just past the next whole second, or the next
     * tenth in the last eleven seconds), or -1 when no clock is running. Callers repaint only if the text differs.
     */
    public synchronized long nanosUntilDisplayChange() {
        if (running == null) {
            return -1;
        }
        long left = remainingNanos(running);
        if (left == 0) {
            return 0;
        }
        long unit = left <= TENTHS_BELOW_NS + SECOND_NS ? TENTH_NS : SECOND_NS;
        long untilBoundary = left % unit;
        return (untilBoundary == 0 ? unit : untilBoundary) + 1_000_000L;
    }

    /** Formats remaining nanoseconds (floored): mm:ss, or mm:ss.t below ten seconds. */
    public static String format(long nanos) {
        long tenths = Math.max(0, nanos) / TENTH_NS;
        long seconds = tenths / 10;
        if (nanos < TENTHS_BELOW_NS) {
            return String.format(Locale.ROOT, "%02d:%02d.%d", seconds / 60, seconds % 60, tenths % 10);
        }
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /** Sets a side's remaining time (a resumed game). The running clock keeps running from the new value. */
    public synchronized void setRemainingMillis(Side side, long millis) {
        settle();
        remainingNs[side.ordinal()] = Math.max(0, millis) * 1_000_000L;
    }

    /** Moves the running time into the remaining time. Lock held. */
    private void settle() {
        if (running != null) {
            long now = nanoTime.getAsLong();
            remainingNs[running.ordinal()] = Math.max(0, remainingNs[running.ordinal()] - (now - runningSince));
            runningSince = now;
        }
    }
}
