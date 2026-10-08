package io.github.hardin22.javachess.Controllers;

import io.github.hardin22.javachess.Browser.BrowserStatus;
import io.github.hardin22.javachess.Browser.BrowserStatus.State;
import javafx.scene.Node;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * How the browser's start-up view presents a {@link BrowserStatus}: an icon that says the kind of situation at a
 * glance (downloading, no network, site down, restart needed...) inside a badge coloured by the status tone.
 * Presentation only; the states and texts belong to the browser logic.
 */
final class BrowserStatusLook {

    private static final String[] TONES = { "tone-info", "tone-progress", "tone-success", "tone-warning",
            "tone-error" };

    private BrowserStatusLook() {
    }

    static String icon(State state) {
        return switch (state) {
            case DOWNLOADING, INSTALLING -> "fth-download-cloud";
            case RESTART_REQUIRED -> "fth-refresh-cw";
            case UNAVAILABLE -> "fth-alert-octagon";
            case OFFLINE -> "fth-wifi-off";
            case SITE_UNREACHABLE -> "fth-cloud-off";
            case VERIFY -> "fth-shield";
            case LOGIN, LOGIN_FAILED, SAVE_LOGIN -> "fth-log-in";
            case NOT_ACCEPTED, UNCERTAIN, BOARD_HIDDEN -> "fth-alert-triangle";
            case GAME_OVER -> "fth-flag";
            case YOUR_TURN, SETUP, REPLICATE -> "fth-grid";
            default -> "fth-globe";
        };
    }

    /** Icon of an action button (null: text only). */
    static String actionIcon(BrowserStatus.Action action) {
        return switch (action) {
            case RETRY, RELOAD -> "fth-rotate-cw";
            case PAGE_BACK -> "fth-arrow-left";
            case RESTART_APP -> "fth-power";
            case SYNC_START -> "fth-link";
            case SYNC_STOP -> "fth-x-circle";
            case RESYNC -> "fth-refresh-cw";
            case SHOW_BOARD -> "fth-maximize";
            case SAVE_LOGIN -> "fth-key";
            case FORGET_LOGIN -> "fth-trash-2";
            case BACK_HOME -> "fth-home";
            case DISMISS -> null;
        };
    }

    /** Sets the badge's tone class and the icon for the status. */
    static void apply(BrowserStatus status, Node badge, FontIcon icon) {
        badge.getStyleClass().removeAll(TONES);
        badge.getStyleClass().add(TONES[status.tone().ordinal()]);
        icon.setIconLiteral(icon(status.state()));
    }
}
