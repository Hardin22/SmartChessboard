package io.github.hardin22.javachess.Engine;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** The single daemon thread on which analysis updates and move feedback are delivered, in order. */
final class EngineEvents {

    static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(UciClient.daemonFactory("engine-events"));

    private EngineEvents() {
    }
}
