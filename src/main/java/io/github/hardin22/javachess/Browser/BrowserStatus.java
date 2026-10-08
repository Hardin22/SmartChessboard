package io.github.hardin22.javachess.Browser;

import io.github.hardin22.javachess.Components.I18n;

import java.util.List;

/**
 * What the user must know about the integrated browser right now: a short title, one sentence saying what to do,
 * a tone (colour), an optional progress and the recovery actions to offer. Built by {@link BrowserSession}; shown
 * by the bar above the page and by the JavaFX browser view. Texts come from {@code i18n/messages.properties}
 * ({@code browser.status.<state>.title/detail}) and are short enough to fit a 720 px wide screen in two lines.
 *
 * @param progress 0..1, or -1 for an indeterminate activity, or {@link #NO_PROGRESS}
 */
public record BrowserStatus(State state, String title, String detail, Tone tone, double progress,
                            List<Action> actions) {

    public static final double NO_PROGRESS = -2;

    public enum Tone {
        INFO, PROGRESS, SUCCESS, WARNING, ERROR
    }

    /** Recovery and control actions offered next to the message (back and reload are always on the bar). */
    public enum Action {
        /** Try to start the browser again. */
        RETRY,
        /** Reload the page. */
        RELOAD,
        /** Go back to the previous page. */
        PAGE_BACK,
        /** Quit and restart the app (first browser download on the Raspberry Pi). */
        RESTART_APP,
        /** Start following the board on the page with the physical board. */
        SYNC_START,
        /** Stop following the page. */
        SYNC_STOP,
        /** Set the physical board up again from the page. */
        RESYNC,
        /** Scroll the page so that the whole board is visible. */
        SHOW_BOARD,
        /** Save the login the user typed, to log in by itself next time. */
        SAVE_LOGIN,
        USE_SAVED_LOGIN,
        /** Do not save the login / close the message. */
        DISMISS,
        /** Remove the saved login (it no longer works). */
        FORGET_LOGIN,
        /** Back to the app's home screen. */
        BACK_HOME;

        public String label() {
            return I18n.t("browser.action." + name().toLowerCase());
        }
    }

    public enum State {
        STARTING(Tone.PROGRESS),
        DOWNLOADING(Tone.PROGRESS),
        INSTALLING(Tone.PROGRESS),
        RESTART_REQUIRED(Tone.WARNING),
        UNAVAILABLE(Tone.ERROR),
        LOADING(Tone.PROGRESS),
        OFFLINE(Tone.ERROR),
        SITE_UNREACHABLE(Tone.ERROR),
        PAGE_CRASHED(Tone.ERROR),
        VERIFY(Tone.WARNING),
        LOGIN(Tone.INFO),
        LOGIN_FAILED(Tone.WARNING),
        SAVE_LOGIN(Tone.INFO),
        NO_GAME(Tone.INFO),
        EDITOR(Tone.INFO),
        BOARD_FOUND(Tone.INFO),
        PAUSED(Tone.INFO),
        READING(Tone.PROGRESS),
        BOARD_HIDDEN(Tone.WARNING),
        SETUP(Tone.WARNING),
        YOUR_TURN(Tone.SUCCESS),
        SENDING(Tone.PROGRESS),
        OPPONENT_TURN(Tone.INFO),
        REPLICATE(Tone.WARNING),
        NOT_ACCEPTED(Tone.ERROR),
        UNCERTAIN(Tone.WARNING),
        GAME_OVER(Tone.SUCCESS);

        private final Tone tone;

        State(Tone tone) {
            this.tone = tone;
        }

        public Tone tone() {
            return tone;
        }
    }

    /** Status with the texts of {@code state} ({@code {0}}, {@code {1}}... replaced by {@code args}). */
    public static BrowserStatus of(State state, double progress, List<Action> actions, Object... args) {
        String key = "browser.status." + state.name().toLowerCase();
        return new BrowserStatus(state, I18n.t(key + ".title", args), I18n.t(key + ".detail", args), state.tone(),
                progress, List.copyOf(actions));
    }

    /** Same status with another detail sentence. */
    public BrowserStatus withDetail(String newDetail) {
        return new BrowserStatus(state, title, newDetail, tone, progress, actions);
    }

    public boolean hasProgress() {
        return progress != NO_PROGRESS;
    }

    /** The very first status, before anything happened. */
    public static BrowserStatus initial() {
        return of(State.STARTING, -1, List.of());
    }
}
