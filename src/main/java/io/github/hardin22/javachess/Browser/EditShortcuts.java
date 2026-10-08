package io.github.hardin22.javachess.Browser;

import org.cef.browser.CefFrame;

import java.util.Locale;

/**
 * Cmd+C/V/X/A/Z on macOS. There the shortcuts of text fields work through the application's Edit menu (CEF expects
 * the app's main menu to carry them; without it "you probably need to implement performKeyEquivalent", CEF forum,
 * "[Mac] Keyboard shortcuts dont work on textfields cef browser"), and the app's menu is JavaFX's, without an Edit
 * menu: Cmd+V did nothing in chess.com's login form. So the browser's keyboard handler turns them into CEF's frame
 * commands ({@code CefFrame.paste()} etc.), the documented way for an embedder without that menu. On Linux Ctrl+C/V
 * reach Chromium directly and are left alone.
 */
public final class EditShortcuts {

    /** The frame command a shortcut stands for. */
    public enum Command {
        COPY, PASTE, CUT, SELECT_ALL, UNDO, REDO
    }

    /** Key codes (Windows virtual keys, as CEF reports them). */
    static final int VK_A = 0x41;
    static final int VK_C = 0x43;
    static final int VK_V = 0x56;
    static final int VK_X = 0x58;
    static final int VK_Z = 0x5A;

    private EditShortcuts() {
    }

    /**
     * The command for a key-down with these modifiers (CEF's EventFlags), or null. {@code command} is Cmd on macOS;
     * shift turns undo into redo.
     */
    public static Command commandFor(boolean keyDown, boolean command, boolean shift, int windowsKeyCode) {
        if (!keyDown || !command) {
            return null;
        }
        return switch (windowsKeyCode) {
            case VK_C -> Command.COPY;
            case VK_V -> Command.PASTE;
            case VK_X -> Command.CUT;
            case VK_A -> Command.SELECT_ALL;
            case VK_Z -> shift ? Command.REDO : Command.UNDO;
            default -> null;
        };
    }

    /** Runs the command on the frame. */
    public static void run(Command c, CefFrame frame) {
        switch (c) {
            case COPY -> frame.copy();
            case PASTE -> frame.paste();
            case CUT -> frame.cut();
            case SELECT_ALL -> frame.selectAll();
            case UNDO -> frame.undo();
            case REDO -> frame.redo();
        }
    }

    /**
     * macOS (windowed browser): the keys reach CEF natively and its keyboard handler sees them
     * ({@code CefKeyboardHandler.onPreKeyEvent}, "called before a keyboard event is sent to the renderer").
     */
    public static boolean nativeKeys() {
        return mac();
    }

    /** The modifier of the shortcuts in CEF's EventFlags: Cmd on macOS, Ctrl elsewhere. */
    public static int commandFlag() {
        return mac() ? org.cef.misc.EventFlags.EVENTFLAG_COMMAND_DOWN : org.cef.misc.EventFlags.EVENTFLAG_CONTROL_DOWN;
    }

    /**
     * Off-screen rendering (Linux, the Raspberry Pi): the keys reach Chromium through the AWT component, and JCEF
     * passes Ctrl+C/V/X/A/Z on without their meaning (checked in the Pi box: letters were typed, Ctrl+V pasted
     * nothing, while {@code CefFrame.paste()} pasted the clipboard), and without calling CEF's keyboard handler. A
     * {@link java.awt.KeyEventDispatcher} (standard AWT) turns them into the frame commands for this browser only.
     */
    public static void installForOffscreen(org.cef.browser.CefBrowser browser) {
        java.awt.Component ui = browser.getUIComponent();
        // Tab moves between the page's fields, not between AWT components (standard AWT: a component that wants
        // Tab turns the focus traversal keys off)
        ui.setFocusTraversalKeysEnabled(false);
        boolean[] handledPress = {false};
        int[] handledKey = {0}; // its release is swallowed too (Ctrl is often released before the letter)
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            java.awt.Component c = e.getComponent();
            if (c == null || (c != ui && !javax.swing.SwingUtilities.isDescendingFrom(c, ui))) {
                return false;
            }
            if (e.getID() == java.awt.event.KeyEvent.KEY_TYPED) {
                // the character of a handled shortcut (a control character, not always flagged with Ctrl) would
                // replace the selection in the field
                boolean swallow = handledPress[0] && e.getKeyChar() < 0x20;
                handledPress[0] = false;
                return swallow;
            }
            if (isModifierKey(e.getKeyCode())
                    && (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED || e.getID() == java.awt.event.KeyEvent.KEY_RELEASED)) {
                // JCEF (off-screen, Linux) passes a lone modifier key on as an empty text input: pressing Shift or
                // Ctrl deleted the selected text of a field (so Ctrl+A then Ctrl+C emptied it; seen in the Pi box).
                // Their state still reaches Chromium with the next key's modifiers.
                return true;
            }
            if (e.getID() == java.awt.event.KeyEvent.KEY_RELEASED) {
                boolean swallow = handledKey[0] != 0 && e.getKeyCode() == handledKey[0];
                if (swallow) {
                    handledKey[0] = 0;
                }
                return swallow;
            }
            if (e.getID() != java.awt.event.KeyEvent.KEY_PRESSED) {
                return false;
            }
            Command cmd = commandFor(true, mac() ? e.isMetaDown() : e.isControlDown(), e.isShiftDown(), e.getKeyCode());
            handledPress[0] = false;
            if (cmd == null) {
                return false;
            }
            try {
                org.cef.browser.CefFrame frame = browser.getFocusedFrame();
                if (frame == null) {
                    return false;
                }
                run(cmd, frame);
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(EditShortcuts.class).warn("Shortcut {} failed: {}", cmd, ex.toString());
                return false;
            }
            handledPress[0] = true;
            handledKey[0] = e.getKeyCode();
            e.consume();
            return true;
        });
    }

    static boolean isModifierKey(int keyCode) {
        return keyCode == java.awt.event.KeyEvent.VK_SHIFT || keyCode == java.awt.event.KeyEvent.VK_CONTROL
                || keyCode == java.awt.event.KeyEvent.VK_ALT || keyCode == java.awt.event.KeyEvent.VK_META
                || keyCode == java.awt.event.KeyEvent.VK_ALT_GRAPH;
    }

    private static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
