package io.github.hardin22.javachess.Components;

import io.github.hardin22.javachess.Utils.AppExecutors;
import io.github.hardin22.javachess.Utils.ConfigManager;

import java.util.Map;

/**
 * UI preferences. Reads go straight to {@link ConfigManager} (cached); writes happen on the storage thread so a
 * tap never waits for the disk. Screenshot and demo runs never write the user's settings.
 */
public final class Prefs {

    private Prefs() {
    }

    public static boolean readOnly() {
        return System.getProperty("javachess.snapshot") != null || System.getProperty("javachess.demo") != null;
    }

    public static void set(String key, Object value) {
        if (readOnly()) {
            return;
        }
        String text = String.valueOf(value);
        AppExecutors.storage().execute(() -> ConfigManager.setProperty(key, text));
    }

    public static void setAll(Map<String, String> values) {
        if (readOnly()) {
            return;
        }
        Map<String, String> copy = Map.copyOf(values);
        AppExecutors.storage().execute(() -> ConfigManager.setProperties(copy));
    }

    public static boolean bool(String key, boolean fallback) {
        return ConfigManager.getBooleanProperty(key, fallback);
    }

    public static int integer(String key, int fallback) {
        return ConfigManager.getIntProperty(key, fallback);
    }

    public static String string(String key, String fallback) {
        return ConfigManager.getProperty(key, fallback);
    }
}
