package io.github.hardin22.javachess.Browser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Cmd+C/V/X/A/Z become CEF's frame commands (macOS has no Edit menu in this app, see EditShortcuts). */
class EditShortcutsTest {

    @Test
    void cmdShortcutsOnKeyDown() {
        assertEquals(EditShortcuts.Command.PASTE, EditShortcuts.commandFor(true, true, false, EditShortcuts.VK_V));
        assertEquals(EditShortcuts.Command.COPY, EditShortcuts.commandFor(true, true, false, EditShortcuts.VK_C));
        assertEquals(EditShortcuts.Command.CUT, EditShortcuts.commandFor(true, true, false, EditShortcuts.VK_X));
        assertEquals(EditShortcuts.Command.SELECT_ALL, EditShortcuts.commandFor(true, true, false, EditShortcuts.VK_A));
        assertEquals(EditShortcuts.Command.UNDO, EditShortcuts.commandFor(true, true, false, EditShortcuts.VK_Z));
        assertEquals(EditShortcuts.Command.REDO, EditShortcuts.commandFor(true, true, true, EditShortcuts.VK_Z));
    }

    @Test
    void otherKeysAndKeyUpsAreLeftToThePage() {
        assertNull(EditShortcuts.commandFor(false, true, false, EditShortcuts.VK_V), "key up");
        assertNull(EditShortcuts.commandFor(true, false, false, EditShortcuts.VK_V), "plain v");
        assertNull(EditShortcuts.commandFor(true, true, false, 0x52), "Cmd+R");
    }
}
