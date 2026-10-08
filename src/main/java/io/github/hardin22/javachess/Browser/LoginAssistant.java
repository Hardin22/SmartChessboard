package io.github.hardin22.javachess.Browser;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Types the saved login into chess.com's or lichess's login page when the user asks for it ("Usa l'accesso
 * salvato"). Login pages are otherwise left alone: the app does not read or script them, so that the sites' bot
 * checks (Cloudflare Turnstile is on chess.com's login) see only the user. Logins are saved from the settings.
 *
 * <ul>
 *   <li>Only on the real sites over https (never on another site that looks like them).</li>
 *   <li>The fields are filled like a person does (a click, then typed text: the sites' scripts see real input) and
 *       the form is sent once; if the login page is still there a few seconds later the user is told.</li>
 * </ul>
 * Methods run on the owner's thread (the browser session); page and keyring work completes asynchronously.
 */
public final class LoginAssistant {

    private static final Logger log = LoggerFactory.getLogger(LoginAssistant.class);

    public enum State {
        /** Nothing to do. */
        IDLE,
        /** Typing the saved login into the form. */
        FILLING,
        /** The saved login was sent and the form came back: it no longer works. */
        FAILED,
        /** The user logged in by hand: save the login? */
        OFFER_SAVE
    }

    /** Locates the login form: the password field, the name field before it and the submit button. */
    static final String FORM_SCRIPT = "(() => {"
            + " const vis = (e) => e && e.getBoundingClientRect().width > 0 && getComputedStyle(e).visibility !== 'hidden';"
            + " const pw = [...document.querySelectorAll('input[type=password]')].find(vis);"
            + " if (!pw) return null;"
            + " const scope = pw.form || document;"
            + " const inputs = [...scope.querySelectorAll('input')].filter(vis);"
            + " const before = inputs.slice(0, inputs.indexOf(pw)).filter(i => /^(text|email|)$/i.test(i.type || ''));"
            + " const user = before[before.length - 1];"
            + " const submit = [...scope.querySelectorAll('button[type=submit], input[type=submit], button:not([type])')]"
            + "   .find(vis);"
            + " const c = (e) => { if (!e) return null; const r = e.getBoundingClientRect();"
            + "   return {x: r.x + r.width / 2, y: r.y + r.height / 2}; };"
            + " return {user: c(user), pass: c(pw), submit: c(submit), userValue: user ? user.value : '',"
            + "   passValue: pw.value || ''};"
            + "})()";

    /** Selects the text of the focused field, so that typing replaces it. */
    static final String SELECT_FOCUSED = "(() => { const e = document.activeElement; if (e && e.select) e.select();"
            + " return !!e; })()";

    static final long FORM_BACK_MS = 8000;

    private final PageDriver page;
    private final CredentialStore store;
    private final Executor owner;
    private final Executor io;
    private final Runnable changed;
    private final LongSupplier clock;

    private State state = State.IDLE;
    private ChessSite site = ChessSite.OTHER;
    private String triedUrl;
    private long sentAt;
    private CredentialStore.Login typed;
    private ChessSite typedSite;
    private boolean typedOnLoginPage;
    private boolean busy;
    private final boolean[] saved = new boolean[ChessSite.values().length];
    private final boolean[] known = new boolean[ChessSite.values().length];

    /**
     * @param owner   the session's executor (results come back on it)
     * @param io      executor for the keyring (system tools may take a moment)
     * @param changed called on the owner's thread when the state changes
     */
    public LoginAssistant(PageDriver page, CredentialStore store, Executor owner, Executor io, Runnable changed,
                          LongSupplier clock) {
        this.page = page;
        this.store = store;
        this.owner = owner;
        this.io = io;
        this.changed = changed;
        this.clock = clock;
    }

    public State state() {
        return state;
    }

    public ChessSite site() {
        return site;
    }

    /** True when {@code url} is a page of {@code site} (chess.com or lichess) over https. */
    static boolean trusted(String url, ChessSite site) {
        if (site == ChessSite.OTHER || url == null) {
            return false;
        }
        try {
            URI u = URI.create(url);
            return "https".equalsIgnoreCase(u.getScheme()) && ChessSite.of(url) == site;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True when the page is one where logins may be typed: chess.com or lichess, over https. */
    static boolean trusted(BoardSnapshot s) {
        if (s.site() == ChessSite.OTHER) {
            return false;
        }
        try {
            URI u = URI.create(s.url());
            return "https".equalsIgnoreCase(u.getScheme()) && ChessSite.of(s.url()) == s.site();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Every poll of a page that is not a login page (on login pages the app keeps its hands off and nothing is
     * read: see {@link BrowserSession.HandsOff}). A saved login that was typed is settled here: the site moved on.
     */
    public void onSnapshot(BoardSnapshot s) {
        if (s.loginForm() && trusted(s)) {
            return; // a login form: the session keeps its hands off it
        }
        if (trusted(s)) {
            triedUrl = null; // a later login form (session expired) may be filled again
        }
        if (state == State.FILLING || state == State.FAILED) {
            set(State.IDLE); // logged in (or moved on)
        }
    }

    /** The browser shows a login page of {@code s}: find out whether a login is saved for it (to offer it). */
    public void onLoginPage(ChessSite s) {
        site = s;
        checkSaved(s);
    }

    /**
     * Types the saved login of {@code where} into the login page at {@code pageUrl}, because the user asked for
     * it ("Usa l'accesso salvato"); {@code done} runs on the owner's thread when the typing is over.
     */
    public void fillSaved(ChessSite where, String pageUrl, Runnable done) {
        if (state == State.FILLING) {
            return;
        }
        if (!trusted(pageUrl, where)) {
            log.warn("Saved login not typed: the page is not {} over https", where.displayName());
            done.run();
            return;
        }
        site = where;
        triedUrl = pageUrl;
        set(State.FILLING);
        CompletableFuture.supplyAsync(() -> store.load(where), io).thenCompose(login -> {
            if (login.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("no saved login"));
            }
            return typeInto(login.get());
        }).orTimeout(20, TimeUnit.SECONDS).whenComplete((v, error) -> owner.execute(() -> {
            sentAt = clock.getAsLong();
            if (error != null) {
                log.warn("Could not type the saved {} login: {}", where.displayName(), error.getClass().getSimpleName());
                set(State.FAILED);
            } else {
                log.info("Saved {} login sent", where.displayName());
                set(State.IDLE);
            }
            done.run();
        }));
    }

    /** Periodic, with the page's address: a login page still there a while after the login was sent refused it. */
    public void tick(String currentUrl) {
        if (state == State.IDLE && triedUrl != null && triedUrl.equals(currentUrl)
                && clock.getAsLong() - sentAt > FORM_BACK_MS) {
            log.info("The saved {} login was not accepted", site.displayName());
            triedUrl = null;
            set(State.FAILED);
        }
    }

    /** The user agreed to save the login typed by hand. */
    public void save() {
        CredentialStore.Login login = typed;
        ChessSite where = typedSite;
        typed = null;
        if (login == null || where == null) {
            set(State.IDLE);
            return;
        }
        io.execute(() -> {
            try {
                store.save(where, login);
                owner.execute(() -> {
                    saved[where.ordinal()] = true;
                    known[where.ordinal()] = true;
                    set(State.IDLE);
                });
            } catch (Exception e) {
                log.warn("Login not saved: {}", e.getClass().getSimpleName());
                owner.execute(() -> set(State.IDLE));
            }
        });
    }

    /** The user does not want the login saved (or dismissed the failure). */
    public void dismiss() {
        typed = null;
        set(State.IDLE);
    }

    /** Forgets the saved login of the current site (it no longer works). */
    public void forget() {
        ChessSite where = site;
        saved[where.ordinal()] = false;
        io.execute(() -> store.remove(where));
        set(State.IDLE);
    }

    // ------------------------------------------------------------------ internals

    private void checkSaved(ChessSite s) {
        if (known[s.ordinal()] || busy) {
            return;
        }
        busy = true;
        io.execute(() -> {
            boolean has = store.has(s);
            owner.execute(() -> {
                known[s.ordinal()] = true;
                saved[s.ordinal()] = has;
                busy = false;
                changed.run(); // the offer to use it can be shown
            });
        });
    }

    /** Fills and sends the login form shown by the page (tests call it on a local page). */
    CompletableFuture<Void> typeInto(CredentialStore.Login login) {
        return page.evaluate(FORM_SCRIPT).thenCompose(json -> type(json, login));
    }

    private CompletableFuture<Void> type(String json, CredentialStore.Login login) {
        if (json == null || json.equals("null")) {
            return CompletableFuture.failedFuture(new IllegalStateException("no form"));
        }
        JSONObject f = new JSONObject(json);
        JSONObject user = f.optJSONObject("user");
        JSONObject pass = f.optJSONObject("pass");
        JSONObject submit = f.optJSONObject("submit");
        if (user == null || pass == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("form fields not found"));
        }
        CompletableFuture<Void> chain = page.click(user.getDouble("x"), user.getDouble("y"))
                .thenCompose(v -> page.evaluate(SELECT_FOCUSED))
                .thenCompose(v -> page.typeText(login.username()))
                .thenCompose(v -> page.click(pass.getDouble("x"), pass.getDouble("y")))
                .thenCompose(v -> page.evaluate(SELECT_FOCUSED))
                .thenCompose(v -> page.typeText(login.password()));
        if (submit != null) {
            chain = chain.thenCompose(v -> page.click(submit.getDouble("x"), submit.getDouble("y")));
        }
        return chain;
    }

    private void set(State next) {
        if (next != state) {
            state = next;
            changed.run();
        }
    }

    /** Whether a saved login is known to exist for the site (as far as checked). */
    public Optional<Boolean> hasSaved(ChessSite s) {
        return known[s.ordinal()] ? Optional.of(saved[s.ordinal()]) : Optional.empty();
    }
}
