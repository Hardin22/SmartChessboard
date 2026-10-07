package org.example.javachess.Utils;

import org.example.javachess.Services.LichessClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static helpers kept for the existing controllers; the real work is done by {@link LichessClient}.
 */
public final class LichessAPIHelper {

    private static final Logger log = LoggerFactory.getLogger(LichessAPIHelper.class);
    private static volatile LichessClient.SeekHandle currentSeek;

    private LichessAPIHelper() {
    }

    /** Id of the game in progress, or null (also when the token is missing or Lichess is unreachable). */
    public static String getGameId() {
        if (!ConfigManager.hasLichessToken()) {
            return null;
        }
        try {
            return new LichessClient().findOngoingGameId().orElse(null);
        } catch (LichessClient.LichessException e) {
            log.warn("Cannot check Lichess games in progress: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Seeks an opponent and blocks until the game starts. Returns the game id, or a string starting with
     * {@code "ERROR:"} followed by a message for the user. Call it off the JavaFX thread; {@link #cancelSeek()}
     * stops it.
     */
    public static String createSeek(int timeMinutes, int incrementSeconds, boolean rated, String color) {
        LichessClient.SeekHandle handle = new LichessClient.SeekHandle();
        currentSeek = handle;
        try {
            return new LichessClient().seek(timeMinutes, incrementSeconds, rated, color, handle);
        } catch (LichessClient.LichessException e) {
            return "ERROR:" + e.getMessage();
        } finally {
            if (currentSeek == handle) {
                currentSeek = null;
            }
        }
    }

    /** Cancels a running {@link #createSeek} (e.g. when the user leaves the setup screen). */
    public static void cancelSeek() {
        LichessClient.SeekHandle handle = currentSeek;
        if (handle != null) {
            handle.close();
        }
    }
}
