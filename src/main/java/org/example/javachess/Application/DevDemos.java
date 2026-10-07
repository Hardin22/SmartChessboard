package org.example.javachess.Application;

import javafx.application.Platform;
import javafx.scene.control.Button;
import org.example.javachess.Controllers.ActiveGameController;
import org.example.javachess.Controllers.MainController;
import org.example.javachess.Controllers.PuzzleController;
import org.example.javachess.Controllers.ReviewController;
import org.example.javachess.Services.PuzzleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/** Developer demos used by {@link DevOptions} to reach screens that need state (game in progress, review...). */
final class DevDemos {

    private static final Logger LOG = LoggerFactory.getLogger(DevDemos.class);
    private static final String DEFAULT_MOVES = "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1 f8e7 f1e1 b7b5 a4b3 d7d6";

    private DevDemos() {
    }

    static void run(MainController main, String demo) {
        try {
            switch (demo) {
                case "game" -> game(main);
                case "review" -> review(main);
                case "puzzle" -> puzzle(main);
                default -> LOG.warn("Unknown demo {}", demo);
            }
        } catch (RuntimeException e) {
            LOG.error("Demo {} failed", demo, e);
        }
    }

    private static void game(MainController main) {
        ActiveGameController game = (ActiveGameController) main.getController("GAME");
        main.navigateTo("GAME");
        game.startPvP(10, 5);
        List<String> moves = Arrays.asList(System.getProperty("javachess.demo.moves", DEFAULT_MOVES).trim().split("\\s+"));
        game.devPlayMoves(moves, 120);
        if (Boolean.getBoolean("javachess.demo.overlay")) {
            javafx.animation.PauseTransition later = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            later.setOnFinished(e -> {
                if (main.getMainContainer().lookup(".chess-board") instanceof org.example.javachess.Oggetti.ChessBoardUI b) {
                    b.showVictoryAnimation("SCACCO MATTO", "IL BIANCO VINCE");
                }
            });
            later.play();
        }
        String sheet = System.getProperty("javachess.demo.sheet");
        if (sheet != null) {
            // Open the sheet once the moves are in, through the same buttons a user would tap.
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(120L * moves.size() + 600);
                } catch (InterruptedException e) {
                    return;
                }
                Platform.runLater(() -> {
                    String id = switch (sheet) {
                        case "engine" -> "#engineButton";
                        case "analysis" -> "#settingsButton";
                        default -> "#endButton";
                    };
                    if (main.getMainContainer().lookup(id) instanceof Button button) {
                        button.fire();
                    }
                });
            });
        }
    }

    private static void review(MainController main) {
        var archive = org.example.javachess.Services.GameArchiveService.getInstance();
        int wanted = Integer.getInteger("javachess.demo.game", -1);
        var chosen = wanted >= 0 ? archive.get(wanted)
                : archive.list().stream()
                        .filter(g -> !"*".equals(g.result()) && g.movesUci().size() >= 40)
                        .findFirst();
        ReviewController review = (ReviewController) main.getController("REVIEW");
        main.navigateTo("REVIEW");
        if (chosen.isPresent()) {
            review.loadGame(chosen.get().movesAsUciString(), chosen.get().initialFen());
            review.goTo(Integer.getInteger("javachess.demo.ply", 20));
            if (Boolean.getBoolean("javachess.demo.analyze")) {
                review.analyze(Integer.getInteger("javachess.demo.depth", 10));
            }
        } else {
            LOG.warn("No archived game for the review demo");
        }
    }

    private static void puzzle(MainController main) {
        PuzzleService.getInstance().findPuzzleAsync(1500, 200, List.of("Tutti")).thenAccept(puzzle ->
                Platform.runLater(() -> {
                    PuzzleController controller = (PuzzleController) main.getController("PUZZLE_GAME");
                    main.navigateTo("PUZZLE_GAME");
                    if (puzzle != null) {
                        controller.setPuzzle(puzzle, 1500, List.of("Tutti"));
                    }
                }));
    }
}
