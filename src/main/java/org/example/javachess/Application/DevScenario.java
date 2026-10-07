package org.example.javachess.Application;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import org.example.javachess.Controllers.ActiveGameController;
import org.example.javachess.Controllers.MainController;
import org.example.javachess.Engine.EngineSelection;
import org.example.javachess.Oggetti.AbstractGame;
import org.example.javachess.Services.EngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Scripted game for manual/automatic testing without the physical board (developer option).
 *
 * <ul>
 *   <li>{@code -Djavachess.dev.pvc=e2e4,g1f3,...} start a PvC game as White and play these moves for the human
 *       (an illegal/unknown move is replaced by a random legal one, which also exercises the blunder verdicts);
 *       {@code -Djavachess.dev.pvc=random:N} plays N random moves</li>
 *   <li>{@code -Djavachess.dev.moveDelayMs=3000} pause between the bot reply and the next human move</li>
 *   <li>{@code -Djavachess.dev.switch=3:maia-1500,6:stockfish-lite} switch engine profile after the given
 *       number of human moves (hot switch during the game)</li>
 * </ul>
 */
final class DevScenario {

    private static final Logger log = LoggerFactory.getLogger(DevScenario.class);

    private DevScenario() {
    }

    static void startIfRequested(MainController main) {
        String pvc = System.getProperty("javachess.dev.pvc");
        if (pvc == null || main == null) {
            return;
        }
        List<String> script = new ArrayList<>();
        int randomMoves = 0;
        if (pvc.startsWith("random:")) {
            randomMoves = Integer.parseInt(pvc.substring(7));
        } else {
            script.addAll(Arrays.asList(pvc.split(",")));
        }
        int total = Math.max(script.size(), randomMoves);
        main.loadView("GAME", "/UI/GameView.fxml");
        ActiveGameController game = (ActiveGameController) main.getController("GAME");
        game.startPvC(Integer.getInteger("javachess.dev.skill", 3), true, EngineService.EngineType.STOCKFISH);
        main.navigateTo("GAME");

        AbstractGame current = currentGame(game);
        long delay = Long.getLong("javachess.dev.moveDelayMs", 3000L);
        String switches = System.getProperty("javachess.dev.switch", "");
        Random rnd = new Random(7);
        int[] played = { 0 };
        Timeline tl = new Timeline(new KeyFrame(Duration.millis(delay), e -> {
            Board b = current.getBoard();
            if (played[0] >= total || b.isMated() || b.isDraw() || b.getSideToMove() != Side.WHITE) {
                return;
            }
            String move = played[0] < script.size() ? script.get(played[0]).trim() : "";
            List<Move> legal = b.legalMoves();
            boolean ok = legal.stream().anyMatch(m -> m.toString().equals(move));
            String chosen = ok ? move : legal.get(rnd.nextInt(legal.size())).toString();
            played[0]++;
            log.info("[dev] human move {}: {}{}", played[0], chosen, ok ? "" : " (random)");
            current.handleMoveInput(chosen);
            for (String sw : switches.split(",")) {
                String[] kv = sw.split(":");
                if (kv.length == 2 && Integer.parseInt(kv[0].trim()) == played[0]) {
                    log.info("[dev] switching engine profile to {}", kv[1].trim());
                    EngineSelection.get().select(kv[1].trim());
                }
            }
        }));
        tl.setCycleCount(Timeline.INDEFINITE);
        tl.play();
    }

    private static AbstractGame currentGame(ActiveGameController c) {
        try {
            java.lang.reflect.Field f = ActiveGameController.class.getDeclaredField("currentGame");
            f.setAccessible(true);
            return (AbstractGame) f.get(c);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
