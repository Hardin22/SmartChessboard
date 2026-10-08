package io.github.hardin22.javachess.Controllers;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Play.BotLevels;
import io.github.hardin22.javachess.Play.TimeControl;
import io.github.hardin22.javachess.Services.EngineService.EngineType;
import io.github.hardin22.javachess.Utils.ImageCache;

import java.util.List;
import java.util.Map;

/**
 * Game against the computer: the opponent on a ladder of levels in approximate Elo (Stockfish weakened, or Maia
 * playing like a person), the time control (none by default), the colour, then one big "Gioca". Remembered.
 */
public class PvcSetupController implements Screen {

    static final String LEVEL_KEY = "game.pvc.level";
    static final String TIME_KEY = "game.pvc.time";
    static final String COLOR_KEY = "game.pvc.color";
    /** Time controls offered as tiles (no clock first). */
    private static final List<TimeControl> TIMES = List.of(TimeControl.UNLIMITED, TimeControl.minutes(3, 2),
            TimeControl.minutes(5, 3), TimeControl.minutes(10, 0), TimeControl.minutes(10, 5),
            TimeControl.minutes(15, 10), TimeControl.minutes(30, 0), TimeControl.minutes(30, 20),
            TimeControl.minutes(90, 30));
    /** Quick picks under the level card. */
    private static final String[][] QUICK = { { "beginner", "pvc.quick.beginner" }, { "club", "pvc.quick.club" },
            { "expert", "pvc.quick.expert" }, { "max", "pvc.quick.max" } };

    private MainController mainController;
    private final StartPositionRow startPosition = new StartPositionRow(() -> mainController);
    private final BorderPane root = new BorderPane();
    private final ToggleGroup colorGroup = new ToggleGroup();
    private final ToggleGroup timeGroup = new ToggleGroup();
    private final Label levelName = Ui.label("", "t-h1");
    private final Label levelElo = Ui.label("", "t-body-m", "t-muted");
    private final Label levelDescription = Ui.wrap("", "t-body", "t-muted");
    private final StackPane levelIcon = new StackPane();
    private final Button minus;
    private final Button plus;
    private final HBox quick = new HBox(10);
    private List<BotLevels.Level> levels = BotLevels.ALL;
    private BotLevels.Level level = BotLevels.byId(BotLevels.DEFAULT_ID).orElse(BotLevels.ALL.get(0));

    public PvcSetupController() {
        minus = stepButton("fth-minus", -1);
        plus = stepButton("fth-plus", 1);
        build();
    }

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    private void build() {
        ScreenHeader header = new ScreenHeader(I18n.t("pvc.title"), () -> mainController.navigateTo("HOME"));
        header.setSubtitle(I18n.t("pvc.subtitle"));

        // level card: icon, name, "circa 1350", description, − / + at the sides
        levelIcon.getStyleClass().add("tile-icon");
        VBox names = new VBox(2, levelName, levelElo);
        HBox nameRow = new HBox(16, levelIcon, names);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        VBox texts = new VBox(12, nameRow, levelDescription);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        levelDescription.setPrefWidth(400);
        HBox card = new HBox(14, minus, texts, plus);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(24, 14, 24, 14));
        for (String[] q : QUICK) {
            ToggleButton chip = Ui.chip(I18n.t(q[1]));
            chip.setUserData(q[0]);
            chip.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(chip, Priority.ALWAYS);
            chip.setOnAction(e -> pickQuick(q[0]));
            quick.getChildren().add(chip);
        }

        // time control tiles
        GridPane times = new GridPane();
        times.setHgap(12);
        times.setVgap(12);
        for (int c = 0; c < 3; c++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(100.0 / 3);
            col.setHgrow(Priority.ALWAYS);
            times.getColumnConstraints().add(col);
        }
        for (int i = 0; i < TIMES.size(); i++) {
            TimeControl tc = TIMES.get(i);
            ToggleButton tile = new ToggleButton();
            VBox content = tc.isUnlimited()
                    ? new VBox(6, Icons.of("fth-coffee", 38), Ui.label(I18n.t("pvc.timecontrol.none"), "option-sub"))
                    : new VBox(2, Ui.label(tc.initialSeconds() / 60 + "+" + tc.incrementSeconds(), "option-big"),
                            Ui.label(tc.category().italian(), "option-sub"));
            content.setAlignment(Pos.CENTER);
            tile.setGraphic(content);
            tile.getStyleClass().setAll("option");
            tile.setMaxWidth(Double.MAX_VALUE);
            tile.setMinHeight(128);
            tile.setUserData(tc);
            tile.setToggleGroup(timeGroup);
            times.add(tile, i % 3, i / 3);
        }
        Ui.keepOneSelected(timeGroup);

        ToggleButton white = colorSegment("w", I18n.t("common.white"), "white");
        ToggleButton random = colorSegment(null, I18n.t("common.random"), "random");
        ToggleButton black = colorSegment("b", I18n.t("common.black"), "black");
        HBox colors = Ui.segmented(colorGroup, List.of(white, random, black));
        colors.getChildren().forEach(n -> n.setStyle("-fx-min-height: 140px; -fx-pref-height: 140px;"));

        VBox body = new VBox(16,
                Ui.sectionLabel(I18n.t("pvc.opponent")), card, quick,
                Ui.wrap(I18n.t("pvc.level.help"), "t-small", "t-muted"),
                Ui.gap(8), Ui.sectionLabel(I18n.t("pvc.timecontrol")), times,
                Ui.gap(8), Ui.sectionLabel(I18n.t("pvc.color")), colors,
                Ui.wrap(I18n.t("pvc.color.hint"), "t-small", "t-muted"),
                Ui.gap(8), startPosition.node());
        body.getStyleClass().add("screen-body");

        Button play = Ui.wide(I18n.t("common.play"), "fth-play", "btn-primary", "btn-lg");
        play.setOnAction(e -> start());
        root.setTop(header);
        root.setCenter(Ui.scroll(body));
        root.setBottom(Ui.footer(play));
        refreshDefaults();
    }

    private Button stepButton(String icon, int delta) {
        Button b = new Button();
        b.getStyleClass().setAll("stepper-btn");
        b.setGraphic(Icons.of(icon, 34));
        b.setFocusTraversable(false);
        b.setOnAction(e -> setLevel(BotLevels.step(level, delta, levels)));
        return b;
    }

    private void pickQuick(String id) {
        if ("max".equals(id)) {
            setLevel(levels.get(levels.size() - 1));
        } else {
            BotLevels.byId(id).filter(levels::contains).ifPresentOrElse(this::setLevel, () -> setLevel(level));
        }
    }

    private void setLevel(BotLevels.Level newLevel) {
        level = newLevel;
        levelName.setText(level.name());
        levelElo.setText(level.eloText());
        levelDescription.setText(level.description());
        levelIcon.getChildren().setAll(Icons.of(level.isMaia() ? "fth-user" : "fth-cpu", 34));
        int index = levels.indexOf(level);
        minus.setDisable(index <= 0);
        plus.setDisable(index >= levels.size() - 1);
        for (javafx.scene.Node n : quick.getChildren()) {
            String id = (String) n.getUserData();
            boolean on = "max".equals(id) ? index == levels.size() - 1 : id.equals(level.id());
            ((ToggleButton) n).setSelected(on);
        }
    }

    private ToggleButton colorSegment(String pieceColor, String text, String id) {
        ToggleButton segment = new ToggleButton(text);
        if (pieceColor != null) {
            segment.setGraphic(kingImage(pieceColor, 64));
        } else {
            HBox both = new HBox(-26, kingImage("w", 56), kingImage("b", 56));
            both.setAlignment(Pos.CENTER);
            segment.setGraphic(both);
        }
        segment.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
        segment.setUserData(id);
        return segment;
    }

    private static ImageView kingImage(String color, double size) {
        ImageView king = new ImageView(ImageCache.getInstance().getImage(
                "/images/Pieces/" + BoardThemes.currentPieces() + "/" + color + "k.png", size, size));
        king.setFitWidth(size);
        king.setFitHeight(size);
        return king;
    }

    @Override
    public void onNavigatedTo() {
        refreshDefaults();
    }

    private void refreshDefaults() {
        try {
            levels = BotLevels.available();
        } catch (RuntimeException e) {
            levels = BotLevels.ALL;
        }
        if (levels.isEmpty()) {
            levels = BotLevels.ALL;
        }
        setLevel(lastLevel(levels));
        TimeControl time = TimeControl.parseStorage(Prefs.string(TIME_KEY, "")).orElse(TimeControl.UNLIMITED);
        timeGroup.getToggles().stream().filter(t -> time.equals(t.getUserData())).findFirst()
                .ifPresentOrElse(t -> t.setSelected(true), () -> timeGroup.getToggles().get(0).setSelected(true));
        String color = Prefs.string(COLOR_KEY, "white");
        colorGroup.getToggles().stream().filter(t -> color.equals(t.getUserData())).findFirst()
                .ifPresentOrElse(t -> t.setSelected(true), () -> colorGroup.getToggles().get(0).setSelected(true));
    }

    /** The level chosen last time (old settings: converted from engine type and skill), within those available. */
    static BotLevels.Level lastLevel(List<BotLevels.Level> available) {
        BotLevels.Level chosen = BotLevels.byId(Prefs.string(LEVEL_KEY, "")).orElse(null);
        if (chosen == null) {
            String engine = Prefs.string("game.pvc.bot", "");
            if (!engine.isEmpty()) {
                try {
                    chosen = BotLevels.fromLegacy(EngineType.valueOf(engine), Prefs.integer("game.bot.level", 10));
                } catch (RuntimeException e) {
                    chosen = null;
                }
            }
        }
        if (chosen == null) {
            chosen = BotLevels.byId(BotLevels.DEFAULT_ID).orElse(available.get(0));
        }
        return available.contains(chosen) ? chosen : BotLevels.closestTo(chosen.elo(), available);
    }

    /** "Circolo · circa 1350 · Bianco" (Home tile). */
    static String describeLastChoice() {
        BotLevels.Level l;
        try {
            l = lastLevel(BotLevels.available());
        } catch (RuntimeException e) {
            l = BotLevels.byId(BotLevels.DEFAULT_ID).orElse(BotLevels.ALL.get(0));
        }
        String color = switch (Prefs.string(COLOR_KEY, "white")) {
            case "black" -> I18n.t("common.black");
            case "random" -> I18n.t("common.random");
            default -> I18n.t("common.white");
        };
        TimeControl time = TimeControl.parseStorage(Prefs.string(TIME_KEY, "")).orElse(TimeControl.UNLIMITED);
        return l.name() + " · " + l.eloText() + (time.isUnlimited() ? "" : " · " + time.label()) + " · " + color;
    }

    private void start() {
        String color = colorGroup.getSelectedToggle() == null ? "white"
                : (String) colorGroup.getSelectedToggle().getUserData();
        TimeControl time = timeGroup.getSelectedToggle() == null ? TimeControl.UNLIMITED
                : (TimeControl) timeGroup.getSelectedToggle().getUserData();
        Prefs.setAll(Map.of(LEVEL_KEY, level.id(), COLOR_KEY, color, TIME_KEY, time.storage()));
        boolean isWhite = switch (color) {
            case "black" -> false;
            case "random" -> Math.random() < 0.5;
            default -> true;
        };
        ActiveGameController game = (ActiveGameController) mainController.getController("GAME");
        mainController.navigateTo("GAME");
        game.setNextStartPosition(startPosition.fen());
        game.startPvC(level, isWhite, time);
    }
}
