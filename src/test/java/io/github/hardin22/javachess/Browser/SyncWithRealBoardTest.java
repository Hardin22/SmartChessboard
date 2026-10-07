package io.github.hardin22.javachess.Browser;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import io.github.hardin22.javachess.Hardware.LedMapping;
import io.github.hardin22.javachess.Hardware.LedRenderer;
import io.github.hardin22.javachess.Hardware.MoveLeds;
import io.github.hardin22.javachess.Hardware.SimulatedBoard;
import io.github.hardin22.javachess.Oggetti.ArchivedGame;
import io.github.hardin22.javachess.Services.BoardStateManager;
import io.github.hardin22.javachess.Utils.PgnCodec;
import io.github.hardin22.javachess.Vision.BoardReading;
import io.github.hardin22.javachess.Vision.BotMover;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration: the real {@link BoardStateManager} with the simulated sensor board (pieces lifted and placed one by
 * one, LEDs), the real {@link BotMover} clicking on the fake site, and the synchronisation in the middle. Only the
 * web page is simulated.
 */
class SyncWithRealBoardTest {

    private SimulatedBoard sim;
    private LedRenderer leds;
    private BoardStateManager manager;
    private FakeSite site;
    private ExecutorService owner;
    private OnlineGameSync sync;
    private final List<OnlineGameSync.State> states = new CopyOnWriteArrayList<>();
    private final List<ArchivedGame> archived = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        sim = new SimulatedBoard(0); // empty board: the user will set it up
        leds = new LedRenderer(sim, LedMapping.DEFAULT);
        manager = new BoardStateManager(leds, new MoveLeds(leds), Runnable::run, new BoardStateManager.HintProvider() {
            @Override
            public void pieceLifted(Board p, Square from, double eval, String best, boolean evaluate) {
            }

            @Override
            public void hintsCleared() {
            }
        }, Executors.newSingleThreadScheduledExecutor());
        manager.setTimings(5, 250, 60);
        sim.start(manager);
        manager.awaitIdle();
        site = new FakeSite();
        owner = Executors.newSingleThreadExecutor();
        BotMover mover = new BotMover(site);
        sync = new OnlineGameSync(new BoardStateManagerBoard(manager, () -> true), mover::play, archived::add,
                states::add, System::currentTimeMillis, owner);
    }

    @AfterEach
    void tearDown() {
        owner.shutdownNow();
        manager.shutdown();
        leds.shutdown();
    }

    private void onOwner(Runnable r) throws Exception {
        owner.submit(r).get(5, TimeUnit.SECONDS);
    }

    private void seePage() throws Exception {
        BoardSnapshot s = BoardProbe.parse(site.json());
        onOwner(() -> sync.onPosition(new BoardWatcher.PositionUpdate(s, null, BoardReading.certain(site.placement()),
                BoardWatcher.ReadMode.PAGE)));
    }

    private void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            manager.awaitIdle();
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    private OnlineGameSync.Phase phase() throws Exception {
        return owner.submit(sync::phase).get(5, TimeUnit.SECONDS);
    }

    private void hand(String uci) {
        Board b = new Board();
        b.loadFromFen(manager.logicalFen());
        Move m = PgnCodec.fromUci(b, uci);
        sim.playMove(b, m);
    }

    @Test
    void setUpThePiecesThenPlayBothWays() throws Exception {
        onOwner(() -> sync.start(OnlineGameSync.Mode.PLAY, PageInfo.of(site.url).withHint("game")));
        seePage();
        assertEquals(OnlineGameSync.Phase.SETUP, phase());

        // the user places the 32 pieces
        for (int square = 0; square < 16; square++) {
            sim.place(square);
            sim.place(63 - square);
        }
        waitFor(() -> {
            try {
                return phase() == OnlineGameSync.Phase.MY_TURN;
            } catch (Exception e) {
                return false;
            }
        }, "setup complete");

        // e2-e4 by hand: BotMover clicks it on the page
        hand("e2e4");
        waitFor(() -> site.played.contains("e2e4"), "the move reached the page");
        assertEquals(List.of("e2", "e4"), site.clickedSquares());
        seePage();
        assertEquals(OnlineGameSync.Phase.OPPONENT_TURN, phase());

        // the opponent answers on the page; the user reproduces it on the board
        site.opponentPlays("e7e5");
        seePage();
        assertEquals(OnlineGameSync.Phase.REPLICATE, phase());
        sim.lift("e7");
        sim.place("e5");
        waitFor(() -> {
            try {
                return phase() == OnlineGameSync.Phase.MY_TURN;
            } catch (Exception e) {
                return false;
            }
        }, "replicated");
        assertEquals(List.of("e2e4", "e7e5"), owner.submit(sync::moves).get());
    }

    @Test
    void promotionIsClickedInTheSitesMenu() throws Exception {
        String fen = "8/4P1k1/8/8/8/8/6K1/8 w - - 0 1";
        site.position(fen);
        sim.setOccupancy(BoardStateManager.occupancy(PgnCodec.boardFromFen(fen)));
        manager.awaitIdle();
        onOwner(() -> sync.start(OnlineGameSync.Mode.ANALYSIS, PageInfo.of("https://lichess.org/analysis")));
        seePage();
        waitFor(() -> {
            try {
                return phase() == OnlineGameSync.Phase.MY_TURN;
            } catch (Exception e) {
                return false;
            }
        }, "setup complete (pieces already in place)");
        hand("e7e8q");
        waitFor(() -> site.played.contains("e7e8q"), "promoted to a queen on the page");
        assertEquals(List.of("e7", "e8", "e8"), site.clickedSquares());
    }
}
