package io.github.hardin22.javachess.Play;

import com.github.bhlangonijr.chesslib.Side;
import io.github.hardin22.javachess.Engine.Score;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotDrawPolicyTest {

    static final String MIDDLEGAME = "r1bq1rk1/pp2bppp/2n1pn2/3p4/2PP4/2N1PN2/PP3PPP/R2QKB1R w KQ - 0 9";

    static BotDrawPolicy withScore(Score s) {
        return new BotDrawPolicy(fen -> CompletableFuture.completedFuture(s));
    }

    @Test
    void acceptsWhenEqualOrWorse() {
        // White to move scores +0.20: the bot is Black, so it is 0.20 worse
        BotDrawPolicy.Decision d = withScore(Score.cp(20)).offer(MIDDLEGAME, 40, Side.BLACK, "Stockfish").join();
        assertTrue(d.accepted());
        assertEquals("Stockfish accetta la patta", d.message());
    }

    @Test
    void declinesWhenBetter() {
        BotDrawPolicy.Decision d = withScore(Score.cp(-120)).offer(MIDDLEGAME, 40, Side.BLACK, "Maia 1500").join();
        assertFalse(d.accepted());
        assertEquals("Maia 1500 rifiuta: pensa di stare meglio", d.message());
        // bot to move and winning by mate
        assertFalse(withScore(Score.mate(3)).offer(MIDDLEGAME, 40, Side.WHITE, "Stockfish").join().accepted());
    }

    @Test
    void tooEarlyAndRepeatedOffers() {
        BotDrawPolicy p = withScore(Score.cp(0));
        BotDrawPolicy.Decision early = p.offer(MIDDLEGAME, 12, Side.BLACK, "Stockfish").join();
        assertFalse(early.accepted());
        assertEquals("Stockfish rifiuta: è presto per una patta", early.message());
        assertFalse(p.canOffer(14));
        assertEquals(4, p.pliesUntilNextOffer(14));
        assertEquals("Hai appena proposto la patta: riprova tra qualche mossa",
                p.offer(MIDDLEGAME, 14, Side.BLACK, "Stockfish").join().message());
        assertTrue(p.canOffer(18));
    }

    @Test
    void deadDrawIsAlwaysAccepted() {
        BotDrawPolicy p = withScore(Score.cp(900));
        assertTrue(p.offer("8/8/4k3/8/8/3NK3/8/8 w - - 0 60", 4, Side.WHITE, "Stockfish").join().accepted());
    }

    @Test
    void engineProblemIsAPoliteRefusal() {
        BotDrawPolicy p = new BotDrawPolicy(fen -> CompletableFuture.failedFuture(new RuntimeException("x")));
        BotDrawPolicy.Decision d = p.offer(MIDDLEGAME, 40, Side.BLACK, "Stockfish").join();
        assertFalse(d.accepted());
        assertEquals("Stockfish rifiuta: vuole continuare a giocare", d.message());
    }
}
