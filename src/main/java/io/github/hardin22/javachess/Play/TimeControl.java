package io.github.hardin22.javachess.Play;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A time control: minutes (in seconds) per player and an increment per move, or no clock at all.
 *
 * @param initialSeconds   time for each player at the start (0 with {@code incrementSeconds} 0 = no clock)
 * @param incrementSeconds seconds added after each move (Fischer)
 */
public record TimeControl(int initialSeconds, int incrementSeconds) {

    /** No clock. */
    public static final TimeControl UNLIMITED = new TimeControl(0, 0);

    /** Speed categories, as lichess estimates them (initial + 40 × increment). */
    public enum Category {
        BULLET("Bullet"), BLITZ("Blitz"), RAPID("Rapid"), CLASSICAL("Classica"), UNLIMITED("Senza tempo");

        private final String italian;

        Category(String italian) {
            this.italian = italian;
        }

        public String italian() {
            return italian;
        }
    }

    /** The usual time controls, fastest first (the tiles of the set-up screens). */
    public static final List<TimeControl> PRESETS = List.of(
            minutes(1, 0), minutes(2, 1), minutes(3, 0), minutes(3, 2), minutes(5, 0), minutes(5, 3),
            minutes(10, 0), minutes(10, 5), minutes(15, 10), minutes(30, 0), minutes(30, 20), minutes(90, 30));

    public TimeControl {
        if (initialSeconds < 0 || incrementSeconds < 0) {
            throw new IllegalArgumentException("negative time control");
        }
        if (initialSeconds == 0 && incrementSeconds > 0) {
            throw new IllegalArgumentException("a clock needs some initial time");
        }
    }

    public static TimeControl minutes(int minutes, int incrementSeconds) {
        return new TimeControl(minutes * 60, incrementSeconds);
    }

    public boolean isUnlimited() {
        return initialSeconds == 0;
    }

    /** Lichess estimate of the game duration per player: initial + 40 × increment (seconds). */
    public int estimatedSeconds() {
        return initialSeconds + 40 * incrementSeconds;
    }

    public Category category() {
        if (isUnlimited()) {
            return Category.UNLIMITED;
        }
        int e = estimatedSeconds();
        if (e < 180) {
            return Category.BULLET;
        }
        if (e < 480) {
            return Category.BLITZ;
        }
        if (e < 1500) {
            return Category.RAPID;
        }
        return Category.CLASSICAL;
    }

    /** "10 + 5", "3 + 0", "½ + 0" (30 s), "Senza tempo". */
    public String label() {
        if (isUnlimited()) {
            return Category.UNLIMITED.italian();
        }
        String min = initialSeconds % 60 == 0 ? String.valueOf(initialSeconds / 60)
                : initialSeconds == 30 ? "½" : initialSeconds == 15 ? "¼"
                : String.format(Locale.ROOT, "%.1f", initialSeconds / 60.0).replace('.', ',');
        return min + " + " + incrementSeconds;
    }

    /** "Blitz · 3 + 2". */
    public String description() {
        return isUnlimited() ? label() : category().italian() + " · " + label();
    }

    /** Storage form "600+5" (seconds + seconds); "" for no clock. */
    public String storage() {
        return isUnlimited() ? "" : initialSeconds + "+" + incrementSeconds;
    }

    /** Archive form "10+5" (minutes + seconds, as the archive and PGN headers of the app use); "" for no clock. */
    public String archiveForm() {
        if (isUnlimited()) {
            return "";
        }
        return (initialSeconds % 60 == 0 ? String.valueOf(initialSeconds / 60) : initialSeconds + "s") + "+"
                + incrementSeconds;
    }

    /** Reads {@link #storage()}; empty for blank or malformed text. */
    public static Optional<TimeControl> parseStorage(String text) {
        if (text == null || text.isBlank()) {
            return Optional.of(UNLIMITED);
        }
        String[] p = text.trim().split("\\+");
        try {
            return Optional.of(new TimeControl(Integer.parseInt(p[0].trim()),
                    p.length > 1 ? Integer.parseInt(p[1].trim()) : 0));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
