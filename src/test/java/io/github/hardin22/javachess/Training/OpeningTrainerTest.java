package io.github.hardin22.javachess.Training;

import io.github.hardin22.javachess.Analysis.AnalysisTree;
import io.github.hardin22.javachess.Engine.review.OpeningBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpeningTrainerTest {

    @TempDir
    Path dir;

    OpeningTrainer trainer(String id, int plies, long seed, OpeningProgress progress) {
        return new OpeningTrainer(OpeningCatalog.byId(id).orElseThrow(), OpeningExplorer.standard(),
                OpeningExplorer.Rating.CLUB, OpeningBook.standard(), plies, new Random(seed * 0x9E3779B97F4A7C15L), null, progress);
    }

    OpeningProgress progress() {
        return new OpeningProgress(dir.resolve("openings.json"), Runnable::run,
                Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void theOpeningsMovesAreRequiredAndTheAppPlaysTheOtherSide() {
        OpeningTrainer t = trainer("italiana", 10, 1, null);
        assertEquals(AnalysisTree.START_FEN, t.fenProperty().get());
        assertEquals("Gioca la prima mossa della Partita Italiana", t.messageProperty().get());

        assertFalse(t.play("d2d4"), "the Italian starts with 1. e4");
        assertEquals(OpeningTrainer.State.WRONG, t.stateProperty().get());
        assertEquals("1. d4 non è la mossa della Partita Italiana: riprova", t.messageProperty().get());
        assertEquals(AnalysisTree.START_FEN, t.fenProperty().get());
        assertEquals(1, t.mistakesProperty().get());

        assertFalse(t.play("c2c4"));
        assertEquals("e2e4", t.shownMoveProperty().get(), "second wrong try: the move is shown");
        assertTrue(t.messageProperty().get().endsWith("Si gioca 1. e4"), t.messageProperty().get());
        assertFalse(t.theoryProperty().get().isEmpty());
        assertEquals(1, t.mistakesProperty().get(), "one position, one mistake");

        assertTrue(t.play("e2e4"));
        assertEquals(List.of("e2e4", "e7e5"), t.playedMoves(), "the app answers with the opening's move");
        assertEquals(OpeningTrainer.State.YOUR_MOVE, t.stateProperty().get());
        assertNull(t.shownMoveProperty().get());
        assertTrue(t.play("g1f3"));
        assertTrue(t.play("f1c4"));
        assertEquals("1. e4 e5 2. Cf3 Cc6 3. Ac4", t.movesTextProperty().get().substring(0, 26));
        assertTrue(t.openingNameProperty().get().contains("Partita Italiana"), t.openingNameProperty().get());
        assertEquals(6, t.pliesProperty().get(), "the app has replied after 3. Ac4");
    }

    @Test
    void afterTheLineTheoryMovesAreAcceptedAndRareMovesAreNot() {
        OpeningTrainer t = trainer("italiana", 30, 7, null);
        t.play("e2e4");
        t.play("g1f3");
        t.play("f1c4");
        String fen = t.fenProperty().get();
        var theory = OpeningExplorer.standard().moves(fen, OpeningExplorer.Rating.CLUB);
        assertFalse(theory.isEmpty());
        assertFalse(t.play("h2h4"), "h4 is not theory here");
        assertTrue(t.messageProperty().get().contains("fuori teoria"), t.messageProperty().get());
        assertTrue(t.play(theory.get(0).uci()));
        assertTrue(t.messageProperty().get().startsWith("Bene: "), t.messageProperty().get());
    }

    @Test
    void aWholeRunEndsAndIsRecorded() {
        OpeningProgress progress = progress();
        OpeningTrainer t = trainer("siciliana", 12, 3, progress);
        assertEquals(List.of("e2e4"), t.playedMoves(), "with Black, the app opens");
        assertEquals("Il Bianco ha giocato: rispondi come nella Difesa Siciliana", t.messageProperty().get());
        int guard = 0;
        while (t.stateProperty().get() != OpeningTrainer.State.DONE && guard++ < 20) {
            t.hint();
            assertTrue(t.play(t.shownMoveProperty().get()));
        }
        assertEquals(OpeningTrainer.State.DONE, t.stateProperty().get());
        assertTrue(t.messageProperty().get().startsWith("Linea completata: 6 mosse")
                || t.messageProperty().get().startsWith("Fine della teoria"), t.messageProperty().get());
        assertTrue(t.mistakesProperty().get() > 0, "hints count as mistakes");
        assertFalse(t.play("a2a3"), "nothing more to play");
        OpeningProgress.Entry e = progress.entry("siciliana");
        assertEquals(1, e.runs());
        assertEquals(1, e.level());
        assertEquals("Da ripassare", e.label());
    }

    @Test
    void repliesVaryWithTheRandomChoiceButStayInTheory() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (long seed = 0; seed < 12; seed++) {
            OpeningTrainer t = trainer("gambetto-donna", 8, seed, null);
            t.play("d2d4");
            t.play("c2c4");
            seen.add(t.playedMoves().get(3)); // Black's answer to the gambit
        }
        assertTrue(seen.size() >= 2, "different replies: " + seen);
        assertTrue(seen.stream().allMatch(m -> List.of("e7e6", "c7c6", "d5c4", "g8f6", "e7e5", "b8c6", "c8f5")
                .contains(m)), seen.toString());
    }
}
