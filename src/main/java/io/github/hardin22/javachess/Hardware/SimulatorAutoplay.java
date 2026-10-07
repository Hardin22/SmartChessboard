package io.github.hardin22.javachess.Hardware;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Services.BoardStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;

/**
 * Development helper that plays on the software board like a person would: it makes {@code moves} moves for
 * {@code side} and reproduces the opponent's moves when the app asks for it. Used to test a whole game against
 * the bot without hardware ({@code -Djavachess.sim.autoplay=N}).
 */
public final class SimulatorAutoplay {

    private static final Logger log = LoggerFactory.getLogger(SimulatorAutoplay.class);

    private final SimulatedBoard sim;
    private final BoardStateManager manager;
    private final Side side;
    private final int moves;
    private final Random random = new Random(7);

    public SimulatorAutoplay(SimulatedBoard sim, BoardStateManager manager, Side side, int moves) {
        this.sim = sim;
        this.manager = manager;
        this.side = side;
        this.moves = moves;
    }

    public Thread start() {
        return Thread.ofVirtual().name("simulator-autoplay").start(this::run);
    }

    private void run() {
        int played = 0;
        Board lastSeen = null;
        try {
            while (played < moves) {
                Thread.sleep(400);
                BoardStateManager.Mode mode = manager.mode();
                Board logical = new Board();
                logical.loadFromFen(manager.logicalFen());
                if (mode == BoardStateManager.Mode.REPLICATE || mode == BoardStateManager.Mode.RESYNC) {
                    replicate(lastSeen, logical);
                } else if (mode == BoardStateManager.Mode.PLAY && logical.getSideToMove() == side
                        && BoardStateManager.occupancy(logical) == sim.occupancy()) {
                    List<Move> legal = logical.legalMoves();
                    if (legal.isEmpty()) {
                        return;
                    }
                    Move move = pick(logical, legal);
                    log.info("Autoplay: {}", move);
                    sim.playMove(logical, move);
                    played++;
                    Board after = logical.clone();
                    after.doMove(move);
                    logical = after;
                }
                lastSeen = logical;
            }
            log.info("Autoplay finished after {} moves", played);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Autoplay stopped: {}", e.toString());
        }
    }

    /** Prefers captures, otherwise a random move. */
    private Move pick(Board board, List<Move> legal) {
        List<Move> captures = legal.stream().filter(m -> board.getPiece(m.getTo()) != Piece.NONE).toList();
        List<Move> pool = captures.isEmpty() ? legal : captures;
        return pool.get(random.nextInt(pool.size()));
    }

    /** Moves the physical pieces so the board matches the logical position (the opponent's move). */
    private void replicate(Board before, Board logical) {
        long target = BoardStateManager.occupancy(logical);
        long physical = sim.occupancy();
        if (before != null) {
            for (int square = 0; square < 64; square++) {
                Square sq = Square.squareAt(square);
                Piece was = before.getPiece(sq);
                Piece now = logical.getPiece(sq);
                if (was != Piece.NONE && now != Piece.NONE && was != now) {
                    sim.lift(square); // captured piece taken off, capturing piece put there below
                }
            }
            physical = sim.occupancy();
        }
        for (long bits = physical & ~target; bits != 0; bits &= bits - 1) {
            sim.lift(Long.numberOfTrailingZeros(bits));
        }
        physical = sim.occupancy();
        for (long bits = target & ~physical; bits != 0; bits &= bits - 1) {
            sim.place(Long.numberOfTrailingZeros(bits));
        }
        log.info("Autoplay: replicated the opponent move");
    }
}
