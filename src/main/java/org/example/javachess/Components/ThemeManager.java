package org.example.javachess.Components;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import org.example.javachess.Utils.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Single owner of the look of the app: loads the Geist fonts, installs the stylesheet and switches the
 * {@code theme-dark} / {@code theme-light} class on the scene root at runtime (no restart needed).
 *
 * <p>All colours live in {@code /Styles/Style.css} as looked-up colours. Canvas-based components cannot use CSS,
 * so they read {@link #palette()} and listen to {@link #darkProperty()} to repaint.</p>
 */
public final class ThemeManager {

    private static final Logger LOG = LoggerFactory.getLogger(ThemeManager.class);
    private static final ThemeManager INSTANCE = new ThemeManager();

    public static final String FONT_SANS = "Geist";
    public static final String FONT_MONO = "Geist Mono";
    private static final String STYLESHEET = "/Styles/Style.css";
    private static final String CONFIG_KEY = "ui.theme";

    /** User-facing theme choice. */
    public enum Mode {
        SYSTEM, DARK, LIGHT;

        static Mode parse(String value) {
            if (value == null) {
                return DARK;
            }
            try {
                return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DARK;
            }
        }
    }

    private final ReadOnlyObjectWrapper<Mode> mode = new ReadOnlyObjectWrapper<>(Mode.DARK);
    private final ReadOnlyBooleanWrapper dark = new ReadOnlyBooleanWrapper(true);
    private Scene scene;
    private volatile Boolean systemDark;
    private static boolean fontsLoaded;

    private ThemeManager() {
        String override = System.getProperty("javachess.theme");
        mode.set(Mode.parse(override != null ? override : ConfigManager.getProperty(CONFIG_KEY, "dark")));
        dark.set(resolveDark(mode.get()));
    }

    public static ThemeManager get() {
        return INSTANCE;
    }

    /** Loads fonts, adds the stylesheet and the window icons. Call once, right after the scene is created. */
    public static void install(Scene scene, Stage stage) {
        loadFonts();
        INSTANCE.scene = scene;
        String css = ThemeManager.class.getResource(STYLESHEET).toExternalForm();
        if (!scene.getStylesheets().contains(css)) {
            scene.getStylesheets().add(css);
        }
        scene.rootProperty().addListener((obs, oldRoot, newRoot) -> INSTANCE.applyTo(newRoot));
        INSTANCE.applyTo(scene.getRoot());
        scene.setFill(INSTANCE.palette().bg());
        if (stage != null) {
            stage.setTitle("javaChess");
            for (int size : new int[] { 16, 32, 64, 128, 256 }) {
                InputStream in = ThemeManager.class.getResourceAsStream("/images/logo/icon-" + size + ".png");
                if (in != null) {
                    stage.getIcons().add(new Image(in));
                }
            }
        }
        if (INSTANCE.mode.get() == Mode.SYSTEM) {
            INSTANCE.refreshSystemPreference();
        }
    }

    /** Registers the Geist families with JavaFX. Safe to call more than once. */
    public static synchronized void loadFonts() {
        if (fontsLoaded) {
            return;
        }
        String[] files = { "Geist-Regular", "Geist-Medium", "Geist-SemiBold", "Geist-Bold",
                "GeistMono-Regular", "GeistMono-Medium", "GeistMono-SemiBold" };
        for (String file : files) {
            try (InputStream in = ThemeManager.class.getResourceAsStream("/Font/Geist/" + file + ".ttf")) {
                if (in == null || Font.loadFont(in, 14) == null) {
                    LOG.warn("Font not loaded: {}", file);
                }
            } catch (Exception e) {
                LOG.warn("Font not loaded: {}", file, e);
            }
        }
        fontsLoaded = true;
    }

    public ReadOnlyObjectProperty<Mode> modeProperty() {
        return mode.getReadOnlyProperty();
    }

    public Mode getMode() {
        return mode.get();
    }

    /** True when the dark palette is active (resolved from the mode and, for SYSTEM, from the OS). */
    public ReadOnlyBooleanProperty darkProperty() {
        return dark.getReadOnlyProperty();
    }

    public boolean isDark() {
        return dark.get();
    }

    /** Changes the theme immediately and persists the choice. Must be called on the FX thread. */
    public void setMode(Mode newMode) {
        mode.set(newMode);
        if (System.getProperty("javachess.snapshot") == null) { // screenshot runs never touch the user config
            ConfigManager.setProperty(CONFIG_KEY, newMode.name().toLowerCase(Locale.ROOT));
        }
        if (newMode == Mode.SYSTEM) {
            refreshSystemPreference();
        }
        update();
    }

    private void update() {
        dark.set(resolveDark(mode.get()));
        if (scene != null) {
            applyTo(scene.getRoot());
            scene.setFill(palette().bg());
        }
    }

    private void applyTo(Parent root) {
        if (root == null) {
            return;
        }
        root.getStyleClass().removeAll("theme-dark", "theme-light");
        root.getStyleClass().add(dark.get() ? "theme-dark" : "theme-light");
    }

    private boolean resolveDark(Mode m) {
        return switch (m) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> systemDark == null || systemDark;
        };
    }

    /** Reads the OS colour scheme off the FX thread, then re-applies the theme. */
    private void refreshSystemPreference() {
        Boolean fromFx = readFxPreference();
        if (fromFx != null) {
            systemDark = fromFx;
            return;
        }
        Thread.ofVirtual().name("theme-probe").start(() -> {
            Boolean detected = probeOsDarkMode();
            if (detected != null) {
                systemDark = detected;
                Platform.runLater(this::update);
            }
        });
    }

    /** JavaFX 22+ exposes Platform.getPreferences().getColorScheme(); use it when available (reflection keeps 21 compatible). */
    private static Boolean readFxPreference() {
        try {
            Object prefs = Platform.class.getMethod("getPreferences").invoke(null);
            Object scheme = prefs.getClass().getMethod("getColorScheme").invoke(prefs);
            return "DARK".equals(String.valueOf(scheme));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Boolean probeOsDarkMode() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                // Prints "Dark" in dark mode, fails (exit 1) in light mode.
                String out = run("defaults", "read", "-g", "AppleInterfaceStyle");
                return out != null && out.toLowerCase(Locale.ROOT).contains("dark");
            }
            if (os.contains("linux")) {
                String out = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
                if (out != null && !out.isBlank()) {
                    return out.contains("dark");
                }
                out = run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme");
                return out == null ? null : out.toLowerCase(Locale.ROOT).contains("dark");
            }
        } catch (Exception e) {
            LOG.debug("Cannot detect OS colour scheme", e);
        }
        return null;
    }

    private static String run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return null;
        }
        return p.exitValue() == 0 ? sb.toString() : "";
    }

    /** Colours for canvas drawing; keep in sync with the tokens in Style.css. */
    public Palette palette() {
        return dark.get() ? Palette.DARK : Palette.LIGHT;
    }

    public record Palette(Color bg, Color surface, Color surface2, Color border, Color borderStrong, Color fg,
                          Color muted, Color accent, Color success, Color warning, Color danger) {

        static final Palette DARK = new Palette(
                Color.web("#0A0A0A"), Color.web("#111111"), Color.web("#1A1A1A"), Color.web("#262626"),
                Color.web("#3D3D3D"), Color.web("#EDEDED"), Color.web("#A1A1A1"), Color.web("#52A8FF"),
                Color.web("#3FB950"), Color.web("#F5A524"), Color.web("#FF6166"));

        static final Palette LIGHT = new Palette(
                Color.web("#FAFAFA"), Color.web("#FFFFFF"), Color.web("#F2F2F2"), Color.web("#E5E5E5"),
                Color.web("#C9C9C9"), Color.web("#171717"), Color.web("#5E5E5E"), Color.web("#0068D6"),
                Color.web("#1A7F37"), Color.web("#A35200"), Color.web("#CB2A2F"));
    }

    /** Human label of a mode, used by the settings screen. */
    public static String label(Mode m) {
        return Map.of(Mode.SYSTEM, I18n.t("theme.system"), Mode.DARK, I18n.t("theme.dark"),
                Mode.LIGHT, I18n.t("theme.light")).get(m);
    }
}
