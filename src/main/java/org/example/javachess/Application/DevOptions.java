package org.example.javachess.Application;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.example.javachess.Controllers.MainController;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Developer switches read from system properties, used to run and screenshot the app on a given display.
 *
 * <ul>
 *   <li>{@code -Djavachess.screen=N} show the window on screen N (0 = primary), e.g. the 720x1920 board monitor</li>
 *   <li>{@code -Djavachess.windowed=WxH} windowed mode with the given size instead of full screen</li>
 *   <li>{@code -Djavachess.view=NAME} navigate to a view after start-up (MainController view name, e.g. SETTINGS)</li>
 *   <li>{@code -Djavachess.snapshot=out.png} write a PNG of the scene after {@code javachess.snapshot.delayMs} (default 3000)</li>
 *   <li>{@code -Djavachess.snapshot.exit=true} quit after writing the snapshot</li>
 *   <li>{@code -Djavachess.theme=dark|light|system} start with that theme (not persisted)</li>
 *   <li>{@code -Djavachess.demo=game|review|puzzle} open a screen in a realistic state for screenshots:
 *       a two-player game with {@code javachess.demo.moves} (UCI, space separated) played; the review of an archived
 *       game ({@code javachess.demo.game=id}, {@code javachess.demo.ply=N}, {@code javachess.demo.analyze=true});
 *       a random puzzle around 1500</li>
 *   <li>{@code -Djavachess.demo.sheet=engine|analysis|end} also open that bottom sheet on the game screen</li>
 * </ul>
 */
public final class DevOptions {

    private DevOptions() {
    }

    /** Positions the stage; returns true when full screen should be applied by the caller. */
    public static boolean placeStage(Stage stage) {
        List<Screen> screens = Screen.getScreens();
        int index = Integer.getInteger("javachess.screen", 0);
        Screen screen = index >= 0 && index < screens.size() ? screens.get(index) : Screen.getPrimary();
        Rectangle2D bounds = screen.getBounds();
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());

        String windowed = System.getProperty("javachess.windowed");
        if (windowed != null && windowed.matches("\\d+x\\d+")) {
            String[] size = windowed.split("x");
            stage.setWidth(Double.parseDouble(size[0]));
            stage.setHeight(Double.parseDouble(size[1]));
            return false;
        }
        if (index != 0) {
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
        }
        return true;
    }

    /** Applies the optional start-up view and snapshot once the stage is showing. */
    public static void afterShow(Stage stage, MainController mainController) {
        String view = System.getProperty("javachess.view");
        String demo = System.getProperty("javachess.demo");
        if (demo != null && mainController != null) {
            Platform.runLater(() -> DevDemos.run(mainController, demo));
        } else if (view != null && mainController != null) {
            Platform.runLater(() -> navigate(mainController, view));
        }
        String snapshot = System.getProperty("javachess.snapshot");
        if (snapshot != null) {
            long delay = Long.getLong("javachess.snapshot.delayMs", 3000L);
            PauseTransition pause = new PauseTransition(Duration.millis(delay));
            pause.setOnFinished(e -> writeSnapshot(stage.getScene(), new File(snapshot), ok -> {
                if (Boolean.getBoolean("javachess.snapshot.exit")) {
                    Platform.exit();
                }
            }));
            pause.play();
        }
    }

    private static void navigate(MainController mainController, String view) {
        try {
            mainController.navigateTo(view);
        } catch (RuntimeException e) {
            System.err.println("[DevOptions] Cannot navigate to " + view + ": " + e.getMessage());
        }
    }

    private static void writeSnapshot(Scene scene, File out, Consumer<Boolean> done) {
        WritableImage image = scene.snapshot(null);
        Thread.ofVirtual().start(() -> {
            boolean ok;
            try {
                File parent = out.getAbsoluteFile().getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                ok = ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", out);
                System.out.println("[DevOptions] Snapshot written: " + out.getAbsolutePath());
            } catch (IOException e) {
                ok = false;
                System.err.println("[DevOptions] Snapshot failed: " + e.getMessage());
            }
            boolean result = ok;
            Platform.runLater(() -> done.accept(result));
        });
    }
}
