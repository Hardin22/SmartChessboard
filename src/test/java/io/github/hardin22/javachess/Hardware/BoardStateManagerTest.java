package io.github.hardin22.javachess.Hardware;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Services.BoardStateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardStateManagerTest {

    private static final long SETTLE = 5;
    private static final long CASTLING_SETTLE = 250;
    private static final long ERROR_SETTLE = 60;

    private SimulatedBoard sim;
    private LedRenderer leds;
    private BoardStateManager manager;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> hints = new CopyOnWriteArrayList<>();
    private final List<String> fens = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        sim = new SimulatedBoard();
        leds = new LedRenderer(sim, LedMapping.DEFAULT);
        BoardStateManager.HintProvider hintProvider = new BoardStateManager.HintProvider() {
            @Override
            public void pieceLifted(Board position, Square from, double eval, String bestMove, boolean evaluate) {
                hints.add("lift " + from);
            }

            @Override
            public void hintsCleared() {
                hints.add("clear");
            }
        };
        manager = new BoardStateManager(leds, new MoveLeds(leds), Runnable::run, hintProvider,
                Executors.newSingleThreadScheduledExecutor());
        manager.setTimings(SETTLE, CASTLING_SETTLE, ERROR_SETTLE);
        manager.setListener(new BoardStateManager.BoardMoveListener() {
            @Override
            public void onPhysicalMoveDetected(String from, String to) {
                events.add("move " + from + to);
            }

            @Override
            public void onBoardSetupComplete() {
                events.add("setup complete");
            }

            @Override
            public void onSetupProgress(String message) {
                events.add("progress " + message);
            }

            @Override
            public void onBoardStateUpdated(String fen, String errorSquare) {
                fens.add(fen);
                if (errorSquare != null) {
                    events.add("error " + errorSquare);
                }
            }

            @Override
            public void onBotMoveReplicated() {
                events.add("replicated");
            }
        });
        sim.start(manager);
        manager.awaitIdle();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        leds.shutdown();
    }

    private void settle(long ms) throws InterruptedException {
        manager.awaitIdle();
        Thread.sleep(ms);
        manager.awaitIdle();
    }

    private void settle() throws InterruptedException {
        settle(SETTLE * 6);
    }

    private void play(String fen) throws InterruptedException {
        Board board = new Board();
        board.loadFromFen(fen);
        sim.setOccupancy(BoardStateManager.occupancy(board));
        manager.setLogicalBoard(board);
        manager.startGameMode();
        settle();
        events.clear();
        hints.clear();
    }

    private List<String> moves() {
        return events.stream().filter(e -> e.startsWith("move")).toList();
    }

    private int ledAt(String square) {
        return leds.composeNow()[Squares.parse(square)];
    }

    // --- setup ---------------------------------------------------------------------------------------------

    @Test
    void aPositionIsSetUpOneKindOfPieceAtATime() throws InterruptedException {
        sim.setOccupancy(0);
        manager.setSetupTargetFen("1K1k4/1P6/8/8/8/8/r7/2R5 w - - 0 1");
        manager.startSetupMode();
        settle();
        assertEquals(LedColors.MISSING, ledAt("b8"), "white king first");
        assertEquals(0, ledAt("d8"), "the black king waits for its turn");
        assertTrue(events.contains("progress Posiziona il Re bianco in b8 · passo 1 di 5"), events.toString());
        assertEquals(1, manager.setupStep().index());

        sim.place("b8");
        settle();
        assertEquals(LedColors.MISSING, ledAt("d8"));
        assertTrue(events.contains("progress Posiziona il Re nero in d8 · passo 2 di 5"));

        sim.place("e4"); // a stray piece is shown in red while the guide goes on
        settle();
        assertEquals(LedColors.WRONG, ledAt("e4"));
        assertTrue(events.contains("progress Posiziona i pezzi: togli quelli sulle case rosse (1), poi il Re nero in d8"
                + " · passo 2 di 5"), events.toString());
        sim.lift("e4");

        for (String square : new String[]{"d8", "c1", "a2", "b7"}) {
            sim.place(square);
        }
        settle();
        assertTrue(events.contains("setup complete"));
        assertEquals(null, manager.setupStep());
    }

    @Test
    void piecesKnownToBeWrongAreTakenAwayFirst() throws InterruptedException {
        // the board shows the starting position; the puzzle wants kings on g1/g8 (knights there now) and a rook on
        // d1 (the queen there now): same occupancy, other pieces
        manager.setSetupTargetFen("6k1/5ppp/r7/8/8/8/5PPP/3R2K1 w - - 0 1");
        manager.startSetupMode();
        settle();
        assertEquals(LedColors.WRONG, ledAt("g1"), "a knight stands where the white king goes");
        assertEquals(LedColors.WRONG, ledAt("d1"));
        assertEquals(LedColors.WRONG, ledAt("g8"));
        assertEquals(0, ledAt("f2"), "the right piece is already there");
        assertTrue(events.contains("progress Posiziona i pezzi: togli quelli sulle case rosse (26), poi il Re bianco "
                + "in g1 · passo 1 di 6"), events.toString());

        sim.lift("g1"); // the knight goes
        settle();
        assertEquals(LedColors.MISSING, ledAt("g1"), "now the king can go there");
        sim.place("g1");
        settle();
        assertTrue(events.stream().anyMatch(e -> e.endsWith("poi il Re nero in g8 · passo 2 di 6")),
                events.toString());
        assertFalse(events.contains("setup complete"));

        for (String sq : new String[]{"a1", "b1", "c1", "e1", "f1", "h1", "a2", "b2", "c2", "d2", "e2", "a7", "b7",
                "c7", "d7", "e7", "a8", "b8", "c8", "d8", "e8", "f8", "h8"}) {
            sim.lift(sq);
        }
        sim.lift("g8");
        sim.place("g8");
        sim.lift("d1");
        sim.place("d1");
        sim.place("a6");
        settle();
        assertTrue(events.contains("setup complete"), events.toString());
    }

    @Test
    void skippingTheGuideTrustsThePiecesAsTheyStand() throws InterruptedException {
        manager.setSetupTargetFen("6k1/5ppp/r7/8/8/8/5PPP/3R2K1 w - - 0 1");
        manager.startSetupMode();
        settle();
        assertEquals(Squares.bit(Squares.parse("g1")) | Squares.bit(Squares.parse("d1"))
                | Squares.bit(Squares.parse("g8")), manager.setupWrongSquares());
        manager.skipSetupGuide(); // "the pieces are right": the player knows better
        settle();
        assertEquals(0, manager.setupWrongSquares());
        assertEquals(0, ledAt("g1"));
        for (long bits = BoardStateManager.occupancy(new Board()) & ~BoardStateManager.occupancy(board(
                "6k1/5ppp/r7/8/8/8/5PPP/3R2K1 w - - 0 1")); bits != 0; bits &= bits - 1) {
            sim.lift(Long.numberOfTrailingZeros(bits));
        }
        sim.place("a6");
        settle();
        assertTrue(events.contains("setup complete"), events.toString());
    }

    private static Board board(String fen) {
        Board b = new Board();
        b.loadFromFen(fen);
        return b;
    }

    @Test
    void anUnknownBoardIsJudgedByOccupancyOnly() throws InterruptedException {
        sim.setOccupancy(BoardStateManager.occupancy(new Board()) & ~Squares.bit(Squares.parse("e2")));
        manager.setSetupTargetFen("6k1/5ppp/r7/8/8/8/5PPP/3R2K1 w - - 0 1");
        manager.startSetupMode();
        settle();
        assertEquals(LedColors.WRONG, ledAt("a1"));
        assertEquals(0, ledAt("g1"), "the sensors do not match the last position: g1 may hold anything");
    }

    @Test
    void theGuideCanBeSkippedForTheCurrentSetUp() throws InterruptedException {
        sim.setOccupancy(0);
        manager.setSetupTargetFen("1K1k4/1P6/8/8/8/8/r7/2R5 w - - 0 1");
        manager.startSetupMode();
        settle();
        assertEquals("Posiziona il Re bianco in b8", manager.setupStep().instruction());
        assertEquals(0, ledAt("d8"));
        manager.skipSetupGuide();
        settle();
        assertEquals(null, manager.setupStep());
        assertEquals(LedColors.MISSING, ledAt("d8"), "every square at once");
        assertTrue(events.contains("progress Posiziona i pezzi: mancano 5"), events.toString());
    }

    @Test
    void guidedSetupCanBeTurnedOff() throws InterruptedException {
        manager.setGuidedSetup(false);
        sim.setOccupancy(0);
        manager.setSetupTargetFen("1K1k4/1P6/8/8/8/8/r7/2R5 w - - 0 1");
        manager.startSetupMode();
        settle();
        assertEquals(LedColors.MISSING, ledAt("d8"));
        assertTrue(events.contains("progress Posiziona i pezzi: mancano 5"), events.toString());
    }


    @Test
    void setupCompletesWhenEveryPieceIsInPlace() throws InterruptedException {
        sim.setOccupancy(0);
        manager.setSetupTargetFen(new Board().getFen());
        manager.startSetupMode();
        settle();
        assertEquals(LedColors.MISSING, ledAt("e2"));
        assertTrue(events.stream().anyMatch(e -> e.startsWith("progress Posiziona i pezzi: mancano 32")));
        assertFalse(events.contains("setup complete"));

        sim.place("e4"); // wrong square
        settle();
        assertEquals(LedColors.WRONG, ledAt("e4"));
        sim.lift("e4");
        for (int square = 0; square < 16; square++) {
            sim.place(square);
            sim.place(63 - square);
        }
        settle();
        assertTrue(events.contains("setup complete"));
        assertEquals(LedColors.OFF, ledAt("e2"));
        assertEquals(BoardStateManager.Mode.IDLE, manager.mode());
    }

    @Test
    void withoutHardwareSetupAndReplicationCompleteImmediately() throws InterruptedException {
        manager.onConnectionChanged(false, "test");
        manager.setSetupTargetFen(new Board().getFen());
        manager.startSetupMode();
        settle();
        assertTrue(events.contains("setup complete"));
        Board board = new Board();
        board.doMove(new Move(Square.E2, Square.E4));
        manager.setLogicalBoard(board);
        manager.startGameMode();
        manager.startBotMoveReplication("E2", "E4");
        settle();
        assertTrue(events.contains("replicated"));
    }

    // --- moves ---------------------------------------------------------------------------------------------

    @Test
    void simpleMoveIsDetected() throws InterruptedException {
        play(new Board().getFen());
        sim.lift("e2");
        settle();
        assertEquals(List.of("lift E2"), hints);
        sim.place("e4");
        settle();
        assertEquals(List.of("move E2E4"), moves());
        assertTrue(manager.logicalFen().startsWith("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b"));
    }

    @Test
    void pieceLiftedAndPutBackIsNotAMove() throws InterruptedException {
        play(new Board().getFen());
        sim.lift("g1");
        sim.place("g1");
        // sensor noise on another square
        sim.lift("a2");
        sim.place("a2");
        settle(ERROR_SETTLE * 2);
        assertTrue(moves().isEmpty());
        assertEquals("clear", hints.get(hints.size() - 1));
        assertFalse(events.stream().anyMatch(e -> e.startsWith("error")));
    }

    @Test
    void holdingTheCapturingPieceIsNotACapture() throws InterruptedException {
        // white knight on e4 can take d6 (only capture available for it)
        play("4k3/8/3p4/8/4N3/8/8/4K3 w - - 0 1");
        sim.lift("e4");
        settle(ERROR_SETTLE * 2);
        assertTrue(moves().isEmpty(), "lifting the knight must not count as Nxd6");
        sim.lift("d6");
        sim.place("d6");
        settle();
        assertEquals(List.of("move E4D6"), moves());
    }

    @Test
    void captureWithCapturedPieceRemovedFirst() throws InterruptedException {
        play("4k3/8/3p4/8/4N3/8/8/4K3 w - - 0 1");
        sim.lift("d6");
        sim.lift("e4");
        sim.place("d6");
        settle();
        assertEquals(List.of("move E4D6"), moves());
    }

    @Test
    void castlingKingFirst() throws InterruptedException {
        play("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        sim.lift("e1");
        sim.place("g1");
        settle();
        assertTrue(moves().isEmpty());
        sim.lift("h1");
        sim.place("f1");
        settle();
        assertEquals(List.of("move E1G1"), moves());
    }

    @Test
    void castlingRookFirstIsNotTakenForARookMove() throws InterruptedException {
        play("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        sim.lift("a1");
        sim.place("d1");
        settle(CASTLING_SETTLE / 3);
        assertTrue(moves().isEmpty(), "Rd1 is also the start of O-O-O: wait");
        sim.lift("e1");
        sim.place("c1");
        settle();
        assertEquals(List.of("move E1C1"), moves());
        assertFalse(events.stream().anyMatch(e -> e.startsWith("error")), events.toString());
    }

    @Test
    void rookMoveThatCouldStartCastlingIsCommittedAfterTheLongerSettle() throws InterruptedException {
        play("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        sim.lift("h1");
        sim.place("f1");
        settle(CASTLING_SETTLE * 2);
        assertEquals(List.of("move H1F1"), moves());
        assertFalse(events.stream().anyMatch(e -> e.startsWith("error")), events.toString());
    }

    @Test
    void enPassant() throws InterruptedException {
        play("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1");
        sim.lift("e5");
        sim.place("d6");
        settle();
        assertTrue(moves().isEmpty(), "the captured pawn is still on d5");
        sim.lift("d5");
        settle();
        assertEquals(List.of("move E5D6"), moves());
    }

    @Test
    void promotion() throws InterruptedException {
        play("8/4P3/8/8/8/8/k7/4K3 w - - 0 1");
        sim.lift("e7");
        sim.place("e8");
        settle();
        assertEquals(List.of("move E7E8"), moves());
        assertTrue(manager.logicalFen().startsWith("4Q3/"), manager.logicalFen());
    }

    @Test
    void illegalMoveIsReportedAndCanBeTakenBack() throws InterruptedException {
        play(new Board().getFen());
        sim.lift("e2");
        sim.place("e5");
        settle(ERROR_SETTLE * 3);
        assertTrue(moves().isEmpty());
        assertTrue(events.contains("error E5"), events.toString());
        assertEquals(LedColors.WRONG, ledAt("e5"));
        sim.lift("e5");
        sim.place("e2");
        settle();
        assertEquals(LedColors.OFF, ledAt("e5"));
        sim.lift("e2");
        sim.place("e4");
        settle();
        assertEquals(List.of("move E2E4"), moves());
    }

    @Test
    void movesOfTheBotSideAreIgnored() throws InterruptedException {
        play(new Board().getFen());
        manager.setPhysicalMoveSide(Side.BLACK);
        sim.lift("e2");
        sim.place("e4");
        settle(ERROR_SETTLE * 2);
        assertTrue(moves().isEmpty());
        assertTrue(hints.isEmpty());
    }

    @Test
    void sequenceOfMovesFromBothSides() throws InterruptedException {
        Board reference = new Board();
        play(reference.getFen());
        String[] game = {"e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "e1g1", "f6e4", "d2d4", "e5d4"};
        for (String uci : game) {
            Move move = new Move(Square.valueOf(uci.substring(0, 2).toUpperCase()), Square.valueOf(uci.substring(2).toUpperCase()));
            sim.playMove(reference, move);
            reference.doMove(move);
            settle();
        }
        assertEquals(game.length, moves().size(), moves().toString());
        assertEquals(reference.getFen().split(" ")[0], manager.logicalFen().split(" ")[0]);
    }

    // --- opponent move replication -------------------------------------------------------------------------

    @Test
    void botMoveReplication() throws InterruptedException {
        play(new Board().getFen());
        sim.lift("e2");
        sim.place("e4");
        settle();
        Board afterBot = new Board();
        afterBot.doMove(new Move(Square.E2, Square.E4));
        afterBot.doMove(new Move(Square.E7, Square.E5));
        manager.setLogicalBoard(afterBot);
        manager.startBotMoveReplication("E7", "E5");
        settle();
        assertEquals(LedColors.REPLICATE, ledAt("e7"));
        assertEquals(LedColors.REPLICATE, ledAt("e5"));
        assertEquals(BoardStateManager.Mode.REPLICATE, manager.mode());
        sim.lift("e7");
        settle();
        assertEquals(LedColors.OFF, ledAt("e7"));
        sim.place("e5");
        settle();
        assertTrue(events.contains("replicated"));
        assertEquals(LedColors.OFF, ledAt("e5"));
        assertEquals(BoardStateManager.Mode.PLAY, manager.mode());
    }

    @Test
    void botCaptureNeedsTheCapturedPieceRemoved() throws InterruptedException {
        play("4k3/8/3p4/4N3/8/8/8/4K3 b - - 0 1");
        Board afterBot = new Board();
        afterBot.loadFromFen("4k3/8/8/4p3/8/8/8/4K3 w - - 0 1"); // d6xe5
        manager.setLogicalBoard(afterBot);
        manager.startBotMoveReplication("D6", "E5");
        settle();
        sim.lift("d6");
        settle();
        assertFalse(events.contains("replicated"), "the knight is still on e5");
        sim.lift("e5");
        sim.place("e5");
        settle();
        assertTrue(events.contains("replicated"));
    }

    @Test
    void occupancySnapshotResyncsMissedEvents() throws InterruptedException {
        play(new Board().getFen());
        Board after = new Board();
        after.doMove(new Move(Square.E2, Square.E4));
        sim.setOccupancy(BoardStateManager.occupancy(after)); // like a heartbeat after lost lines
        settle();
        assertEquals(List.of("move E2E4"), moves());
    }

    @Test
    void checkIsShownOnTheKingSquare() throws InterruptedException {
        play("4k3/8/8/8/8/8/8/4K2R w K - 0 1");
        sim.lift("h1");
        sim.place("h8");
        settle();
        assertEquals(List.of("move H1H8"), moves());
        assertEquals(LedColors.CHECK, ledAt("e8"));
        assertNotNull(manager.logicalFen());
    }

    // --- disconnection / reconnection during a game --------------------------------------------------------

    @Test
    void opponentMoveMadeWhileOfflineIsReproducedAfterReconnection() throws InterruptedException {
        play(new Board().getFen());
        sim.lift("e2");
        sim.place("e4");
        settle();
        sim.setConnected(false);
        settle();
        Board afterBot = new Board();
        afterBot.doMove(new Move(Square.E2, Square.E4));
        afterBot.doMove(new Move(Square.E7, Square.E5));
        manager.setLogicalBoard(afterBot);
        manager.startBotMoveReplication("E7", "E5");
        settle();
        assertTrue(events.contains("replicated"), "offline: nothing to reproduce");
        events.clear();

        sim.setConnected(true);
        settle();
        assertEquals(BoardStateManager.Mode.RESYNC, manager.mode());
        assertEquals(LedColors.MISSING, ledAt("e5"));
        assertEquals(LedColors.WRONG, ledAt("e7"));
        assertTrue(events.stream().anyMatch(e -> e.startsWith("progress Rimetti i pezzi come sullo schermo")),
                events.toString());
        assertTrue(moves().isEmpty());

        sim.lift("e7");
        sim.place("e5");
        settle();
        assertEquals(BoardStateManager.Mode.PLAY, manager.mode());
        assertTrue(events.contains("progress Scacchiera allineata"), events.toString());
        assertEquals(LedColors.OFF, ledAt("e5"));
        sim.lift("g1");
        sim.place("f3");
        settle();
        assertEquals(List.of("move G1F3"), moves());
    }

    @Test
    void moveMadeWhileOfflineIsTakenOnReconnection() throws InterruptedException {
        play(new Board().getFen());
        sim.setConnected(false);
        settle();
        sim.lift("e2");
        sim.place("e4");
        settle();
        assertTrue(moves().isEmpty(), "the app cannot see an unplugged board");
        sim.setConnected(true);
        settle();
        assertEquals(List.of("move E2E4"), moves());
        assertEquals(BoardStateManager.Mode.PLAY, manager.mode());
    }

    @Test
    void reconnectionWithTheBoardInSyncChangesNothing() throws InterruptedException {
        play(new Board().getFen());
        sim.setConnected(false);
        settle();
        sim.setConnected(true);
        settle(ERROR_SETTLE * 2);
        assertEquals(BoardStateManager.Mode.PLAY, manager.mode());
        assertTrue(moves().isEmpty());
        assertFalse(events.stream().anyMatch(e -> e.startsWith("error") || e.startsWith("progress")),
                events.toString());
    }

    @Test
    void takebackIsReproducedThroughTheResync() throws InterruptedException {
        play(new Board().getFen());
        sim.lift("e2");
        sim.place("e4");
        settle();
        manager.setLogicalBoard(new Board()); // e.g. the move was taken back on the screen
        manager.resyncToLogical();
        settle();
        assertEquals(BoardStateManager.Mode.RESYNC, manager.mode());
        assertEquals(LedColors.MISSING, ledAt("e2"));
        sim.lift("e4");
        sim.place("e2");
        settle();
        assertEquals(BoardStateManager.Mode.PLAY, manager.mode());
        assertEquals(List.of("move E2E4"), moves(), "the taken-back move is not detected again");
    }

    @Test
    void liftingTheKingAfterADoublePawnPushPublishesAReadableFen() throws InterruptedException {
        // 1.e4 c5 2.e5 d5: White may take en passant on d6; the white king is lifted (e.g. to castle later)
        play("rnbqkbnr/pp2pppp/8/2ppP3/8/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 3");
        fens.clear();
        sim.lift("e1");
        settle();
        assertFalse(fens.isEmpty());
        for (String fen : fens) {
            Board shown = new Board();
            shown.loadFromFen(fen); // threw ArrayIndexOutOfBoundsException (no king to check the en passant)
        }
        sim.place("e1");
        settle();
        assertTrue(fens.get(fens.size() - 1).contains(" d6 "), "the full position keeps its en passant square");
    }
}
