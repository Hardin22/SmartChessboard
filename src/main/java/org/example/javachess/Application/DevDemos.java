package org.example.javachess.Application;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import org.example.javachess.Controllers.ActiveGameController;
import org.example.javachess.Controllers.MainController;
import org.example.javachess.Controllers.PuzzleController;
import org.example.javachess.Controllers.ReviewController;
import org.example.javachess.Oggetti.Puzzle;
import org.example.javachess.Services.PuzzleService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                    String text = switch (sheet) {
                        case "engine" -> "Motore";
                        case "analysis" -> "Analisi";
                        default -> "Termina";
                    };
                    Node root = main.getMainContainer();
                    root.lookupAll(".button").stream()
                            .filter(n -> n instanceof Button b && text.equals(b.getText()))
                            .findFirst().ifPresent(n -> ((Button) n).fire());
                });
            });
        }
    }

    private static void review(MainController main) {
        JSONObject chosen = null;
        try {
            JSONArray games = new JSONArray(Files.readString(Path.of("archive.json"), StandardCharsets.UTF_8));
            int wanted = Integer.getInteger("javachess.demo.game", -1);
            for (int i = 0; i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                if (wanted >= 0 ? g.optInt("id") == wanted
                        : g.optString("result").startsWith("Scaccomatto") && g.optString("pgn").length() > 400) {
                    chosen = g;
                    if (wanted >= 0) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("No archive for the review demo", e);
        }
        ReviewController review = (ReviewController) main.getController("REVIEW");
        main.navigateTo("REVIEW");
        if (chosen != null) {
            review.loadGame(chosen.optString("pgn"), chosen.optString("initialFen",
                    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"));
            review.goTo(Integer.getInteger("javachess.demo.ply", 20));
            if (Boolean.getBoolean("javachess.demo.analyze")) {
                review.analyze(Integer.getInteger("javachess.demo.depth", 10));
            }
        }
    }

    private static void puzzle(MainController main) {
        Thread.ofVirtual().start(() -> {
            PuzzleService service = PuzzleService.getInstance();
            Puzzle puzzle = service.getRandomPuzzle(service.getPuzzlesByThemeAndRating(List.of("Tutti"), 1500, 200));
            Platform.runLater(() -> {
                PuzzleController controller = (PuzzleController) main.getController("PUZZLE_GAME");
                main.navigateTo("PUZZLE_GAME");
                if (puzzle != null) {
                    controller.setPuzzle(puzzle, 1500, List.of("Tutti"));
                }
            });
        });
    }
}
