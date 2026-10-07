package io.github.hardin22.javachess.Play;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import io.github.hardin22.javachess.Engine.PositionAnalyzer;
import io.github.hardin22.javachess.Engine.Score;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * When the computer accepts a draw offer, like the bots of chess.com: not in the opening, never when it thinks it
 * is better, always in a dead draw. Between two offers the player must make a few moves.
 */
public final class BotDrawPolicy {

    /** No draw before this many half-moves (move 15). */
    public static final int MIN_PLIES = 30;
    /** Half-moves between two offers. */
    public static final int PLIES_BETWEEN_OFFERS = 6;
    /** The bot declines when it is at least this much better (centipawns, its point of view). */
    public static final int BETTER_CP = 50;
    /** Nodes of the evaluation search (≈ 0.2 s on a Raspberry Pi 5). */
    static final long NODES = 300_000;

    /**
     * The bot's answer.
     *
     * @param accepted true for a draw
     * @param message  Italian sentence for the player ("Stockfish accetta la patta", ...)
     */
    public record Decision(boolean accepted, String message) {
    }

    private final Function<String, CompletableFuture<Score>> evaluator;
    private int lastOfferPly = Integer.MIN_VALUE / 2;

    /** Evaluates with the shared analysis engine. */
    public BotDrawPolicy() {
        this(fen -> PositionAnalyzer.get().searchBest(fen, NODES, 3_000).thenApply(r -> r.score()));
    }

    /** @param evaluator score of a position from the side to move's point of view */
    public BotDrawPolicy(Function<String, CompletableFuture<Score>> evaluator) {
        this.evaluator = evaluator;
    }

    /** True when the player may offer a draw at half-move {@code ply} (not right after the previous offer). */
    public boolean canOffer(int ply) {
        return ply - lastOfferPly >= PLIES_BETWEEN_OFFERS;
    }

    /** Half-moves left before a new offer is possible (0 = now). */
    public int pliesUntilNextOffer(int ply) {
        return Math.max(0, PLIES_BETWEEN_OFFERS - (ply - lastOfferPly));
    }

    /**
     * The bot's answer to a draw offered in {@code fen} after {@code ply} half-moves. Never fails: an engine
     * problem is a polite refusal.
     */
    public CompletableFuture<Decision> offer(String fen, int ply, Side botSide, String botName) {
        if (!canOffer(ply)) {
            return CompletableFuture.completedFuture(new Decision(false,
                    "Hai appena proposto la patta: riprova tra qualche mossa"));
        }
        lastOfferPly = ply;
        Board board = new Board();
        board.loadFromFen(fen);
        if (board.isInsufficientMaterial() || (!GameClock.canMate(board, Side.WHITE)
                && !GameClock.canMate(board, Side.BLACK))) {
            return CompletableFuture.completedFuture(new Decision(true, botName + " accetta la patta"));
        }
        if (ply < MIN_PLIES) {
            return CompletableFuture.completedFuture(new Decision(false,
                    botName + " rifiuta: è presto per una patta"));
        }
        boolean botToMove = board.getSideToMove() == botSide;
        return evaluator.apply(fen).handle((score, err) -> {
            if (err != null || score == null) {
                return new Decision(false, botName + " rifiuta: vuole continuare a giocare");
            }
            int botCp = botToMove ? score.centipawns() : -score.centipawns();
            return decide(botCp, botName);
        });
    }

    /** Decision for an evaluation of {@code botCp} centipawns from the bot's side. */
    static Decision decide(int botCp, String botName) {
        if (botCp >= BETTER_CP) {
            return new Decision(false, botName + " rifiuta: pensa di stare meglio");
        }
        return new Decision(true, botName + " accetta la patta");
    }
}
