package io.github.hardin22.javachess.e2e;

import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Controllers.ActiveGameController;
import io.github.hardin22.javachess.Controllers.PuzzleController;
import io.github.hardin22.javachess.Hardware.Hardware;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Oggetti.ChessClock;
import io.github.hardin22.javachess.Oggetti.ChessTimer;
import io.github.hardin22.javachess.Oggetti.Puzzle;
import io.github.hardin22.javachess.Oggetti.PuzzleGame;
import io.github.hardin22.javachess.Services.BoardStateManager;
import io.github.hardin22.javachess.Services.PuzzleProgressService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.github.hardin22.javachess.e2e.E2eHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every local mode played to the end on the simulated board ({@code board.mode=sim}): the moves are made by lifting
 * and placing pieces on the sensors, the bot's moves are reproduced following the board manager, as a person
 * sitting at the board would do. Covers set-up, move detection, replication, end of game, archive, the cable
 * unplugged and plugged back during a game, and a puzzle solved on the board.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimBoardEndToEndTest {

    private static E2eHarness app;

    @BeforeAll
    static void startApp() throws Exception {
        app = E2eHarness.start("sim");
        assertNotNull(sim(), "simulated board");
        boardState().setTimings(40, 400, 200); // faster than a person, same order of events
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) {
            app.stop();
        }
    }

    @BeforeEach
    void backHomeWithTheInitialPosition() throws Exception {
        fx(() -> {
            app.main.navigateTo("HOME");
            return null;
        });
        sim().setConnected(true);
        sim().setOccupancy(0xFFFF_0000_0000_FFFFL); // pieces back in the starting position
        awaitStorage(); // the game the previous test left is archived before this test counts the games
    }

    @Test
    @Order(1)
    void pvcBotMatesAndItsLastMoveIsReproducedOnTheBoard() throws Exception {
        app.bot("e7e5", "d8h4"); // 1.f3 e5 2.g4 Qh4#
        int before = archive().size();
        ActiveGameController game = app.startPvc(true);
        playOnBoard(game, "f2f3", 2);
        reproduceLastMove(game);
        playOnBoard(game, "g2g4", 4);
        waitFor("mate", () -> fxGet(() -> game(game).getBoard().isMated()));
        // QA-003: the mating move is shown on the LEDs like any other and the result stays on screen
        reproduceLastMove(game);
        Thread.sleep(400); // the stray-piece check would fire here
        String status = fxGet(() -> game(game).lastStatus());
        assertTrue(status.startsWith("Scaccomatto"), status);
        ArchivedGame saved = waitForArchived(before + 1);
        assertEquals("0-1", saved.result());
        assertEquals("Scaccomatto", saved.termination());
        assertEquals(List.of("f2f3", "e7e5", "g2g4", "d8h4"), saved.movesUci());
    }

    @Test
    @Order(2)
    void pvcHumanWithBlackMatesTheBot() throws Exception {
        app.bot("f2f3", "g2g4");
        int before = archive().size();
        ActiveGameController game = app.startPvc(false);
        waitFor("bot's first move", () -> plies(game) >= 1);
        reproduceLastMove(game);
        playOnBoard(game, "e7e5", 3);
        reproduceLastMove(game);
        playOnBoard(game, "d8h4", 4);
        ArchivedGame saved = waitForArchived(before + 1);
        assertEquals("0-1", saved.result());
        assertEquals("Scaccomatto", saved.termination());
        assertEquals("Giocatore", saved.black());
        assertEquals(ArchivedGame.GameMode.PVC, saved.mode());
    }

    @Test
    @Order(3)
    void pvpScholarsMateOnTheBoardStopsTheClocks() throws Exception {
        int before = archive().size();
        ActiveGameController game = app.startPvp(300, 2);
        String[] moves = { "e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7" };
        for (int i = 0; i < moves.length; i++) {
            playOnBoard(game, moves[i], i + 1);
        }
        ArchivedGame saved = waitForArchived(before + 1);
        assertEquals("1-0", saved.result());
        assertEquals("Scaccomatto", saved.termination());
        assertEquals("Bianco", saved.white());
        assertEquals(5 * 60 + "+2", toSeconds(saved.timeControl()));
        ChessClock clock = fxGet(() -> ((ChessTimer) field(game(game), "chessTimer")).clock());
        assertNull(clock.running(), "no clock keeps running after the mate");
    }

    @Test
    @Order(4)
    void pvpThreefoldRepetitionIsADraw() throws Exception {
        int before = archive().size();
        ActiveGameController game = app.startPvp(300, 0);
        String[] moves = { "g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1", "f6g8" };
        for (int i = 0; i < moves.length; i++) {
            playOnBoard(game, moves[i], i + 1);
        }
        ArchivedGame saved = waitForArchived(before + 1);
        assertEquals("1/2-1/2", saved.result());
        assertEquals("Triplice ripetizione", saved.termination());
    }

    @Test
    @Order(5)
    void cableUnpluggedDuringTheBotMoveThenBoardPutBackInSync() throws Exception {
        app.bot("e7e5", "b8c6");
        ActiveGameController game = app.startPvc(true);
        playOnBoard(game, "e2e4", 1);
        sim().setConnected(false); // unplugged while the bot thinks
        waitFor("bot move while offline", () -> plies(game) >= 2);
        waitForMode(BoardStateManager.Mode.PLAY); // nothing to reproduce on an unplugged board
        sim().setConnected(true);
        waitForMode(BoardStateManager.Mode.RESYNC); // QA-004
        waitFor("resync message", () -> fxGet(() -> game(game).lastStatus()).startsWith("Rimetti i pezzi"));
        arrangeAsLogical(); // the player follows the LEDs
        waitForMode(BoardStateManager.Mode.PLAY);
        playOnBoard(game, "g1f3", 3);
        waitFor("bot answer", () -> plies(game) >= 4);
        reproduceLastMove(game);
        fx(() -> {
            app.main.navigateTo("HOME");
            return null;
        });
    }

    @Test
    @Order(6)
    void moveMadeWithTheCableUnpluggedIsTakenWhenItIsBack() throws Exception {
        app.bot("e7e5");
        ActiveGameController game = app.startPvc(true);
        waitFor("set-up done", () -> boardState().mode() == BoardStateManager.Mode.PLAY);
        sim().setConnected(false);
        physicalMove("d2d4");
        Thread.sleep(300);
        assertEquals(0, plies(game), "an unplugged board is not read");
        sim().setConnected(true);
        waitFor("move taken after the reconnection", () -> plies(game) >= 1);
        assertEquals("d2d4", fxGet(() -> game(game).getBoard().getBackup().getFirst().getMove().toString()));
        fx(() -> {
            app.main.navigateTo("HOME");
            return null;
        });
    }

    @Test
    @Order(7)
    void leavingAGameAsksForConfirmationAndArchivesItAsInterrupted() throws Exception {
        app.bot("e7e5");
        ActiveGameController game = app.startPvc(true);
        playOnBoard(game, "e2e4", 2);
        reproduceLastMove(game);
        playOnBoard(game, "g1f3", 3);
        awaitStorage();
        int before = archive().size();
        fx(() -> invoke(game, "requestLeave")); // back arrow / "Esci dalla partita"
        Thread.sleep(200);
        assertEquals("GAME", fxGet(app.main::getCurrentViewName), "nothing happens before the confirmation");
        assertEquals(before, archive().size());
        app.fireButton(I18n.t("game.leave"));
        waitFor("home", () -> "HOME".equals(fxGet(app.main::getCurrentViewName)));
        ArchivedGame saved = waitForArchived(before + 1);
        assertEquals("*", saved.result());
        assertEquals("Interrotta", saved.termination());
        assertTrue(saved.movesUci().size() >= 3);
        waitForMode(BoardStateManager.Mode.IDLE);
    }

    @Test
    @Order(8)
    void resigningAgainstTheBotIsALossForTheHuman() throws Exception {
        app.bot("e7e5");
        ActiveGameController game = app.startPvc(true);
        playOnBoard(game, "e2e4", 2);
        reproduceLastMove(game);
        awaitStorage();
        int before = archive().size();
        fx(() -> invoke(game, "requestResign"));
        Thread.sleep(200);
        assertEquals(before, archive().size(), "nothing happens before the confirmation");
        app.fireButton(I18n.t("game.resign.confirm.ok"));
        ArchivedGame saved = waitForArchived(before + 1);
        assertEquals("0-1", saved.result());
        assertEquals("Abbandono", saved.termination());
        assertFalse(fxGet(() -> game(game).isRunning()));
    }

    @Test
    @Order(9)
    void pvpDrawByAgreement() throws Exception {
        int before = archive().size();
        ActiveGameController game = app.startPvp(300, 0);
        playOnBoard(game, "e2e4", 1);
        playOnBoard(game, "e7e5", 2);
        fx(() -> {
            game.offerDraw(com.github.bhlangonijr.chesslib.Side.WHITE);
            game.answerDraw(true);
            return null;
        });
        ArchivedGame saved = waitForArchived(before + 1);
        assertEquals("1/2-1/2", saved.result());
        assertEquals("Patta d'accordo", saved.termination());
    }

    @Test
    @Order(10)
    void takeBackWhileTheBotMoveIsStillToBeReproducedIsFixedOnTheBoard() throws Exception {
        app.bot("e7e5"); // the scripted bot answers e7-e5 whenever it is legal
        ActiveGameController game = app.startPvc(true);
        playOnBoard(game, "e2e4", 2);
        waitForMode(BoardStateManager.Mode.REPLICATE); // e7-e5 shown on the LEDs, not reproduced yet
        io.github.hardin22.javachess.Oggetti.PvcGame pvc = (io.github.hardin22.javachess.Oggetti.PvcGame) game(game);
        assertTrue(fxGet(pvc::takeBack));
        assertEquals(0, plies(game));
        waitForMode(BoardStateManager.Mode.RESYNC); // e4 back to e2 (e7-e5 was never played on the board)
        arrangeAsLogical();
        waitForMode(BoardStateManager.Mode.PLAY);
        assertTrue(fxGet(pvc::isAwaitingHumanMove));
        playOnBoard(game, "d2d4", 2);
        reproduceLastMove(game);
        assertEquals(List.of("d2d4", "e7e5"), fxGet(() -> game(game).getBoard().getBackup().stream()
                .map(b -> b.getMove().toString()).toList()));
        fx(() -> {
            app.main.navigateTo("HOME");
            return null;
        });
    }

    @Test
    @Order(11)
    void puzzleSetUpAndSolvedOnTheBoard() throws Exception {
        PuzzleController puzzles = fx(() -> {
            PuzzleController c = (PuzzleController) app.main.getController("PUZZLE_GAME");
            app.main.navigateTo("PUZZLE_GAME");
            return c;
        });
        // Black plays Ra7-a6, White mates with Rd1-d8.
        Puzzle puzzle = new Puzzle("simA", "6k1/r4ppp/8/8/8/8/5PPP/3R2K1 b - - 0 1", List.of("a7a6", "d1d8"), 1500,
                80, 90, 100, List.of("mateIn1"), "", "");
        int attempts = PuzzleProgressService.getInstance().getStats().attempts();
        fx(() -> {
            puzzles.setPuzzle(puzzle, 1500, List.of("Tutti"));
            return null;
        });
        PuzzleGame game = (PuzzleGame) field(puzzles, "puzzleGame");
        waitForMode(BoardStateManager.Mode.SETUP);
        waitFor("opponent's first move shown", () -> fxGet(() -> game.getBoard().getBackup().size()) == 1);
        arrangeAs(fxGet(() -> game.getBoard().getFen())); // the player builds the position after Ra6
        waitFor("solving", () -> fxGet(game::isAwaitingHumanMove));

        // a wrong move: the LEDs show how to take it back (QA-007) and the puzzle goes on
        physicalMove("d1d2");
        waitForMode(BoardStateManager.Mode.RESYNC);
        arrangeAsLogical();
        waitForMode(BoardStateManager.Mode.PLAY);
        assertTrue(fxGet(game::isAwaitingHumanMove));

        physicalMove("d1d8");
        waitFor("solved", () -> PuzzleProgressService.getInstance().getStats().attempts() == attempts + 1);
        assertFalse(PuzzleProgressService.getInstance().isSolved("simA"), "a puzzle with a mistake is not clean");
        fx(() -> {
            app.main.navigateTo("HOME");
            return null;
        });
        waitForMode(BoardStateManager.Mode.IDLE);
        assertTrue(Hardware.isInitialized());
    }

    private static String toSeconds(String timeControl) {
        String[] parts = timeControl.split("\\+");
        return Integer.parseInt(parts[0].trim()) * 60 + "+" + parts[1].trim();
    }
}
