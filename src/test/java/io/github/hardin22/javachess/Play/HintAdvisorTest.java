package io.github.hardin22.javachess.Play;

import io.github.hardin22.javachess.Hardware.LedColors;
import io.github.hardin22.javachess.Hardware.Squares;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HintAdvisorTest {

    static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    static final String AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";

    final Map<String, CompletableFuture<String>> answers = new HashMap<>();
    final List<Map<Integer, Integer>> ledFrames = new ArrayList<>();
    final HintAdvisor hints = new HintAdvisor(fen -> answers.computeIfAbsent(fen, f -> new CompletableFuture<>()),
            ledFrames::add, Runnable::run);

    @Test
    void pieceFirstThenTheMove() {
        hints.request(START);
        assertEquals(HintAdvisor.Level.THINKING, hints.levelProperty().get());
        assertEquals("Sto cercando la mossa…", hints.textProperty().get());
        answers.get(START).complete("g1f3");
        assertEquals(HintAdvisor.Level.PIECE, hints.levelProperty().get());
        assertEquals("Muovi il Cavallo in g1", hints.textProperty().get());
        assertEquals("g1", hints.fromSquareProperty().get());
        assertNull(hints.moveProperty().get());
        assertEquals(Map.of(Squares.parse("G1"), LedColors.BEST), ledFrames.get(0));
        assertTrue(hints.canAskMoreProperty().get());

        hints.request(START);
        assertEquals(HintAdvisor.Level.MOVE, hints.levelProperty().get());
        assertEquals("Il suggerimento: 1. Cf3", hints.textProperty().get());
        assertEquals("g1f3", hints.moveProperty().get());
        assertEquals(LedColors.BEST, ledFrames.get(1).get(Squares.parse("F3")));
        assertFalse(hints.canAskMoreProperty().get());
        assertEquals(1, hints.usedProperty().get(), "two steps in one position count as one hint");

        hints.request(START); // nothing more to give
        assertEquals(2, ledFrames.size());
    }

    @Test
    void aMoveClearsTheHintAndIgnoresLateAnswers() {
        hints.request(START);
        hints.clear();
        answers.get(START).complete("g1f3");
        assertEquals(HintAdvisor.Level.NONE, hints.levelProperty().get());
        assertTrue(ledFrames.isEmpty());

        hints.request(AFTER_E4);
        answers.get(AFTER_E4).complete("e7e5");
        assertEquals("Muovi il pedone in e7", hints.textProperty().get());
        hints.clear();
        assertEquals(Map.of(), ledFrames.get(ledFrames.size() - 1), "LEDs switched off");
        assertEquals(2, hints.usedProperty().get());
    }

    @Test
    void engineFailureIsReportedAndCanBeRetried() {
        hints.request(START);
        answers.get(START).completeExceptionally(new RuntimeException("no engine"));
        assertEquals(HintAdvisor.Level.FAILED, hints.levelProperty().get());
        answers.remove(START);
        hints.request(START);
        assertEquals(HintAdvisor.Level.THINKING, hints.levelProperty().get());
        answers.get(START).complete("e2e9"); // nonsense from the engine
        assertEquals(HintAdvisor.Level.FAILED, hints.levelProperty().get());
    }

    @Test
    void articles() {
        assertEquals("l'Alfiere", HintAdvisor.article(com.github.bhlangonijr.chesslib.Piece.BLACK_BISHOP));
        assertEquals("la Donna", HintAdvisor.article(com.github.bhlangonijr.chesslib.Piece.WHITE_QUEEN));
        assertEquals("il pezzo", HintAdvisor.article(null));
    }
}
