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
import com.github.bhlangonijr.chesslib.Side;
import org.example.javachess.Controllers.MainController;
import org.example.javachess.Hardware.Hardware;
import org.example.javachess.Hardware.SimulatedBoard;
import org.example.javachess.Hardware.SimulatorAutoplay;
import org.example.javachess.Hardware.SimulatorWindow;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
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
 *   <li>{@code -Djavachess.dev.pvc=...} scripted PvC game, see {@link DevScenario}</li>
 *   <li>{@code -Djavachess.devgame=pvc} start a game against the bot (player white, lowest level) right away</li>
 *   <li>{@code -Djavachess.board=sim} software chessboard; with {@code -Djavachess.simulator.window=true} its
 *       debug window, with {@code -Djavachess.sim.autoplay=N} N moves played on it automatically</li>
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
        if (view != null && mainController != null) {
            Platform.runLater(() -> navigate(mainController, view));
        }
        Platform.runLater(() -> DevScenario.startIfRequested(mainController));
        if ("pvc".equalsIgnoreCase(System.getProperty("javachess.devgame")) && mainController != null) {
            Platform.runLater(() -> startBotGame(mainController));
        }
        SimulatedBoard simulator = System.getProperty("javachess.board", "").startsWith("sim") ? Hardware.simulator() : null;
        if (simulator != null) {
            if (Boolean.getBoolean("javachess.simulator.window")) {
                Platform.runLater(() -> new SimulatorWindow(simulator, Hardware.leds().mapping()).show());
            }
            int autoplay = Integer.getInteger("javachess.sim.autoplay", 0);
            if (autoplay > 0) {
                new SimulatorAutoplay(simulator, Hardware.boardState(), Side.WHITE, autoplay).start();
            }
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

    /** Same as choosing "vs bot", white, lowest level, first engine type on the setup screen. */
    private static void startBotGame(MainController mainController) {
        try {
            mainController.navigateTo("GAME");
            Object controller = mainController.getController("GAME");
            for (Method method : controller.getClass().getMethods()) {
                if (method.getName().equals("startPvC") && method.getParameterCount() == 3
                        && method.getParameterTypes()[2].isEnum()) {
                    method.invoke(controller, 1, true, method.getParameterTypes()[2].getEnumConstants()[0]);
                    return;
                }
            }
            System.err.println("[DevOptions] startPvC(int, boolean, enum) not found");
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[DevOptions] Cannot start the bot game: " + e);
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
