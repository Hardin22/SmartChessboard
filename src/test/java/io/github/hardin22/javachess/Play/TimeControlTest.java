package io.github.hardin22.javachess.Play;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeControlTest {

    @Test
    void categoriesFollowTheLichessEstimate() {
        assertEquals(TimeControl.Category.BULLET, TimeControl.minutes(1, 0).category());
        assertEquals(TimeControl.Category.BULLET, TimeControl.minutes(2, 1).category());
        assertEquals(TimeControl.Category.BLITZ, TimeControl.minutes(3, 2).category());
        assertEquals(TimeControl.Category.BLITZ, TimeControl.minutes(5, 0).category());
        assertEquals(TimeControl.Category.RAPID, TimeControl.minutes(10, 0).category());
        assertEquals(TimeControl.Category.RAPID, TimeControl.minutes(15, 10).category());
        assertEquals(TimeControl.Category.CLASSICAL, TimeControl.minutes(30, 0).category());
        assertEquals(TimeControl.Category.UNLIMITED, TimeControl.UNLIMITED.category());
    }

    @Test
    void labels() {
        assertEquals("10 + 5", TimeControl.minutes(10, 5).label());
        assertEquals("½ + 0", new TimeControl(30, 0).label());
        assertEquals("Blitz · 3 + 2", TimeControl.minutes(3, 2).description());
        assertEquals("Senza tempo", TimeControl.UNLIMITED.description());
        assertEquals("10+5", TimeControl.minutes(10, 5).archiveForm());
        assertEquals("", TimeControl.UNLIMITED.archiveForm());
    }

    @Test
    void storageRoundTrip() {
        TimeControl tc = TimeControl.minutes(15, 10);
        assertEquals(tc, TimeControl.parseStorage(tc.storage()).orElseThrow());
        assertEquals(TimeControl.UNLIMITED, TimeControl.parseStorage("").orElseThrow());
        assertTrue(TimeControl.parseStorage("x+y").isEmpty());
    }

    @Test
    void invalidControls() {
        assertThrows(IllegalArgumentException.class, () -> new TimeControl(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TimeControl(0, 5));
    }

    @Test
    void presetsAreOrderedFastestFirst() {
        for (int i = 1; i < TimeControl.PRESETS.size(); i++) {
            assertTrue(TimeControl.PRESETS.get(i).estimatedSeconds() >= TimeControl.PRESETS.get(i - 1).estimatedSeconds());
        }
    }
}
