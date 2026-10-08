package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;

/**
 * The physical chessboard (sensors and LEDs) as the online synchronisation uses it. In the app it is backed by
 * {@code BoardStateManager} ({@link BoardStateManagerBoard}); tests may use the simulated board or a fake.
 */
public interface PhysicalBoard {

    /** Events from the board; they may arrive on any thread. */
    interface Listener {
        /** A legal move made by hand on the board. */
        void onPhysicalMove(String from, String to);

        /** The pieces now match the target given to {@link #setup}. */
        void onSetupComplete();

        /** Setup progress for the user ("mancano 12 pezzi..."). */
        void onSetupProgress(String message);

        /** The move given to {@link #replicate} has been reproduced on the board. */
        void onReplicated();
    }

    /** True when the sensors are available. Without them setups and replications complete at once. */
    boolean isConnected();

    /** Starts sending events to the listener; forgets any half-done move. */
    void attach(Listener listener);

    /** Stops sending events, turns the LEDs off. */
    void detach();

    /** Asks the user to place the pieces as in this FEN (LEDs show missing and extra pieces). */
    void setup(String fen);

    /**
     * Follows a game from this position. Only moves of {@code movingSide} are detected (null: both sides, as on an
     * analysis board).
     */
    void play(Board position, Side movingSide);

    /** Corrects the position being followed (e.g. a move the site did not accept is taken back). */
    void setPosition(Board position);

    /** Asks the user to reproduce a move made on the screen; {@code after} already contains it. */
    void replicate(Board after, String from, String to);
}
