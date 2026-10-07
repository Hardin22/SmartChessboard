package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import io.github.hardin22.javachess.Services.BoardStateManager;

import java.util.function.BooleanSupplier;

/** {@link PhysicalBoard} backed by the app's {@link BoardStateManager} (real board, simulator or no board). */
public final class BoardStateManagerBoard implements PhysicalBoard {

    private final BoardStateManager manager;
    private final BooleanSupplier suggestionsSetting;

    /**
     * @param suggestionsSetting the user's "move suggestions" setting, restored when the synchronisation ends
     */
    public BoardStateManagerBoard(BoardStateManager manager, BooleanSupplier suggestionsSetting) {
        this.manager = manager;
        this.suggestionsSetting = suggestionsSetting;
    }

    @Override
    public boolean isConnected() {
        return manager.isHardwareConnected();
    }

    @Override
    public void attach(Listener listener) {
        manager.reset();
        manager.setEvaluationEnabled(false); // no engine hints for an online game
        manager.setListener(new BoardStateManager.BoardMoveListener() {
            @Override
            public void onPhysicalMoveDetected(String from, String to) {
                listener.onPhysicalMove(from, to);
            }

            @Override
            public void onBoardSetupComplete() {
                listener.onSetupComplete();
            }

            @Override
            public void onSetupProgress(String message) {
                listener.onSetupProgress(message);
            }

            @Override
            public void onBoardStateUpdated(String fen, String errorSquare) {
            }

            @Override
            public void onBotMoveReplicated() {
                listener.onReplicated();
            }
        });
    }

    @Override
    public void detach() {
        manager.stopGameMode(); // also detaches the listener and turns the LEDs off
        manager.setEvaluationEnabled(suggestionsSetting.getAsBoolean());
    }

    @Override
    public void setup(String fen) {
        manager.setSetupTargetFen(fen);
        manager.startSetupMode();
    }

    @Override
    public void play(Board position, Side movingSide) {
        manager.setLogicalBoard(position);
        manager.startGameMode();
        manager.setPhysicalMoveSide(movingSide);
    }

    @Override
    public void setPosition(Board position) {
        manager.setLogicalBoard(position);
    }

    @Override
    public void replicate(Board after, String from, String to) {
        manager.setLogicalBoard(after);
        manager.startBotMoveReplication(from, to);
    }
}
