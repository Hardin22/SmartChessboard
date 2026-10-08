package io.github.hardin22.javachess.e2e;

import io.github.hardin22.javachess.Hardware.Hardware;
import io.github.hardin22.javachess.Hardware.SimulatorAutoplay;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link TapWalkEndToEndTest} on the simulated board: while the screen is tapped at random, a player sitting at the
 * board ({@link SimulatorAutoplay}, both sides) sets the pieces up when the LEDs ask, reproduces the moves shown,
 * puts pieces back and plays moves on the sensors — in every screen that drives the board (games, puzzles, the
 * mistake trainer, resumed games, the analysis board). Replay with {@code -De2e.simtap.seed=N -De2e.simtap.steps=M}.
 */
class SimTapWalkEndToEndTest {

    private static Thread player;

    @BeforeAll
    static void startApp() throws Exception {
        TapWalkEndToEndTest.setUp("sim");
        player = new SimulatorAutoplay(Hardware.simulator(), Hardware.boardState(), null, Integer.MAX_VALUE).start();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (player != null) {
            player.interrupt();
        }
        TapWalkEndToEndTest.tearDown();
    }

    @Test
    void tappingAroundWithAPlayerAtTheBoardLogsNoErrorsAndNeverDeadEnds() throws Exception {
        TapWalkEndToEndTest.walk("sim tap walk", Long.getLong("e2e.simtap.seed", 8102026L),
                Integer.getInteger("e2e.simtap.steps", 500));
    }
}
