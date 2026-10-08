package io.github.hardin22.javachess.Browser;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Matches DevTools answers to the calls waiting for them, whichever comes first: the message id of a call is known
 * only after Chromium accepted it, and its answer can arrive before that (JCEF's own client loses such answers and
 * its caller waits forever, see {@code org.cef.browser.DevToolsAccess}). Thread-safe.
 */
final class DevToolsReplies {

    /** Early answers kept for ids nobody waits for yet (answers to other clients' calls are dropped in time). */
    static final int MAX_EARLY = 64;

    private record Answer(boolean success, String result) {
    }

    private final Map<Integer, CompletableFuture<String>> waiting = new HashMap<>();
    private final LinkedHashMap<Integer, Answer> early = new LinkedHashMap<>();
    private boolean closed;

    /** The answer of message {@code id}: already there, or when it comes. */
    CompletableFuture<String> await(int id, CompletableFuture<String> future) {
        Answer answer;
        synchronized (this) {
            if (closed) {
                future.completeExceptionally(new IllegalStateException("DevTools session closed"));
                return future;
            }
            answer = early.remove(id);
            if (answer == null) {
                if (!future.isDone()) { // a call that already timed out waits for nothing
                    waiting.put(id, future);
                }
                return future;
            }
        }
        complete(future, answer);
        return future;
    }

    /** An answer from Chromium. */
    void received(int id, boolean success, String result) {
        CompletableFuture<String> future;
        Answer answer = new Answer(success, result);
        synchronized (this) {
            future = waiting.remove(id);
            if (future == null) {
                if (closed) {
                    return;
                }
                early.put(id, answer);
                Iterator<Integer> oldest = early.keySet().iterator();
                while (early.size() > MAX_EARLY && oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
                return;
            }
        }
        complete(future, answer);
    }

    /** A call that will never be answered (timeout): forget it. */
    synchronized void forget(int id) {
        waiting.remove(id);
    }

    /** Fails every waiting call; later answers are ignored. */
    void close() {
        Map<Integer, CompletableFuture<String>> pending;
        synchronized (this) {
            closed = true;
            pending = new HashMap<>(waiting);
            waiting.clear();
            early.clear();
        }
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("DevTools session closed")));
    }

    synchronized int waitingCount() {
        return waiting.size();
    }

    synchronized int earlyCount() {
        return early.size();
    }

    private static void complete(CompletableFuture<String> future, Answer answer) {
        if (answer.success()) {
            future.complete(answer.result());
        } else {
            future.completeExceptionally(new IllegalStateException("DevTools error: " + answer.result()));
        }
    }
}
