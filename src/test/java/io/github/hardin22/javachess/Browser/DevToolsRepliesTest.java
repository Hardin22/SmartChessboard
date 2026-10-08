package io.github.hardin22.javachess.Browser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DevTools answers reach their call whether they arrive before or after the call's message id is known. */
class DevToolsRepliesTest {

    @Test
    void anAnswerAfterTheIdIsKnown() throws Exception {
        DevToolsReplies r = new DevToolsReplies();
        CompletableFuture<String> f = r.await(7, new CompletableFuture<>());
        assertFalse(f.isDone());
        r.received(7, true, "{\"ok\":1}");
        assertEquals("{\"ok\":1}", f.get(1, TimeUnit.SECONDS));
        assertEquals(0, r.waitingCount());
    }

    @Test
    void anAnswerBeforeTheIdIsKnownIsNotLost() throws Exception {
        // the case JCEF's CefDevToolsClient gets wrong: its caller then waits forever
        DevToolsReplies r = new DevToolsReplies();
        r.received(8, true, "early");
        CompletableFuture<String> f = r.await(8, new CompletableFuture<>());
        assertEquals("early", f.get(1, TimeUnit.SECONDS));
        assertEquals(0, r.earlyCount());
    }

    @Test
    void aFailedMethodFailsItsCall() {
        DevToolsReplies r = new DevToolsReplies();
        CompletableFuture<String> f = r.await(9, new CompletableFuture<>());
        r.received(9, false, "{\"code\":-32000,\"message\":\"No node\"}");
        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(1, TimeUnit.SECONDS));
        assertTrue(e.getCause().getMessage().contains("No node"));
    }

    @Test
    void answersNobodyWaitsForAreBounded() {
        DevToolsReplies r = new DevToolsReplies();
        for (int id = 1; id <= 500; id++) {
            r.received(id, true, "x");
        }
        assertEquals(DevToolsReplies.MAX_EARLY, r.earlyCount());
    }

    @Test
    void aTimedOutCallIsForgotten() {
        DevToolsReplies r = new DevToolsReplies();
        CompletableFuture<String> late = new CompletableFuture<>();
        late.completeExceptionally(new java.util.concurrent.TimeoutException());
        r.await(10, late); // the id came after the timeout
        assertEquals(0, r.waitingCount());
        r.await(11, new CompletableFuture<>());
        r.forget(11);
        assertEquals(0, r.waitingCount());
    }

    @Test
    void closingFailsWaitingCalls() {
        DevToolsReplies r = new DevToolsReplies();
        CompletableFuture<String> f = r.await(12, new CompletableFuture<>());
        r.close();
        assertTrue(f.isCompletedExceptionally());
        assertTrue(r.await(13, new CompletableFuture<>()).isCompletedExceptionally());
        r.received(13, true, "ignored");
        assertEquals(0, r.earlyCount());
    }

    @Test
    void racingAnswersAndIdsAlwaysMeet() throws Exception {
        // answers and ids from two threads in random order, like Chromium's UI thread and the caller
        DevToolsReplies r = new DevToolsReplies();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int id = 0;
            for (int round = 0; round < 300; round++) {
                int first = id + 1;
                int last = id + 16; // a burst of calls in flight, well under MAX_EARLY
                id = last;
                List<CompletableFuture<String>> futures = new ArrayList<>();
                for (int i = first; i <= last; i++) {
                    futures.add(new CompletableFuture<>());
                }
                CountDownLatch go = new CountDownLatch(1);
                var answers = pool.submit(() -> {
                    go.await();
                    for (int i = first; i <= last; i++) {
                        r.received(i, true, "answer " + i);
                    }
                    return null;
                });
                var waits = pool.submit(() -> {
                    go.await();
                    for (int i = first; i <= last; i++) {
                        r.await(i, futures.get(i - first));
                    }
                    return null;
                });
                go.countDown();
                answers.get(5, TimeUnit.SECONDS);
                waits.get(5, TimeUnit.SECONDS);
                for (int i = first; i <= last; i++) {
                    assertEquals("answer " + i, futures.get(i - first).get(1, TimeUnit.SECONDS));
                }
            }
            assertEquals(0, r.waitingCount());
            assertEquals(0, r.earlyCount());
        } finally {
            pool.shutdownNow();
        }
    }
}
