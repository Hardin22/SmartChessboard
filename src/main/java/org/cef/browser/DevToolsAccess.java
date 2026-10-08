package org.cef.browser;

import java.util.concurrent.CompletableFuture;

/**
 * javaChess: access to JCEF's DevTools primitives, which are package-private (hence this class in JCEF's package;
 * the JCEF jar is not sealed and runs on the class path).
 *
 * <p>Why not {@link CefDevToolsClient}: it loses answers that arrive before the message id is known. Its result
 * observer creates the pending entry, completes it and removes it; the caller then asks for the entry of that id,
 * gets a new one and waits forever (JCEF 146: seen as DevTools calls hanging until their timeout, a few times per
 * game in the field trials). {@code io.github.hardin22.javachess.Browser.DevToolsReplies} keeps early answers.</p>
 */
public final class DevToolsAccess {

    /** Receives the answer of every DevTools method sent to the browser. */
    public interface Results {
        void onResult(int messageId, boolean success, String result);
    }

    private DevToolsAccess() {
    }

    /** True when the browser offers DevTools (a native JCEF browser). */
    public static boolean supported(CefBrowser browser) {
        return browser instanceof CefBrowser_N;
    }

    /** Sends a method; the future gives its message id (the answer comes to the {@link Results} observer). */
    public static CompletableFuture<Integer> send(CefBrowser browser, String method, String params) {
        return ((CefBrowser_N) browser).executeDevToolsMethod(method, params);
    }

    /** Observes the answers; close the returned handle to stop. */
    public static AutoCloseable observe(CefBrowser browser, Results results) {
        CefRegistration registration = ((CefBrowser_N) browser).addDevToolsMessageObserver(
                new CefDevToolsMessageObserver() {
                    @Override
                    public void onDevToolsMethodResult(CefBrowser b, int messageId, boolean success, String result) {
                        results.onResult(messageId, success, result);
                    }

                    @Override
                    public void onDevToolsEvent(CefBrowser b, String method, String parameters) {
                    }
                });
        return registration::dispose;
    }
}
