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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.BoardThemes;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.Stepper;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Engine.EngineSelection;
import io.github.hardin22.javachess.Services.EngineService.EngineType;
import io.github.hardin22.javachess.Utils.ImageCache;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Game against the computer: opponent, level and colour, then one big "Gioca". Choices are remembered. */
public class PvcSetupController implements Screen {

    static final String BOT_KEY = "game.pvc.bot";
    static final String COLOR_KEY = "game.pvc.color";
    private static final int[] QUICK_LEVELS = { 1, 5, 10, 15, 20 };

    private MainController mainController;
    private final BorderPane root = new BorderPane();
    private final ToggleGroup botGroup = new ToggleGroup();
    private final ToggleGroup colorGroup = new ToggleGroup();
    private final Map<EngineType, ToggleButton> botCards = new EnumMap<>(EngineType.class);
    private final Stepper level = new Stepper(1, 20, 1, 10);
    private final VBox levelSection = new VBox(16);
    private final HBox quickLevels = new HBox(10);

    public PvcSetupController() {
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

        VBox bots = new VBox(12);
        bots.getChildren().addAll(
                bot(EngineType.STOCKFISH, I18n.t("pvc.stockfish"), I18n.t("pvc.stockfish.description")),
                bot(EngineType.MAIA_1100, I18n.t("pvc.maia1100"), I18n.t("pvc.maia1100.description")),
                bot(EngineType.MAIA_1500, I18n.t("pvc.maia1500"), I18n.t("pvc.maia1500.description")),
                bot(EngineType.MAIA_1900, I18n.t("pvc.maia1900"), I18n.t("pvc.maia1900.description")));
        Ui.keepOneSelected(botGroup);
        botGroup.selectedToggleProperty().addListener((obs, o, n) -> updateLevelState());

        level.format(String::valueOf, I18n.t("pvc.level.of"));
        for (int q : QUICK_LEVELS) {
            ToggleButton chip = Ui.chip(String.valueOf(q));
            chip.setOnAction(e -> level.setValue(q));
            chip.setUserData(q);
            HBox.setHgrow(chip, javafx.scene.layout.Priority.ALWAYS);
            chip.setMaxWidth(Double.MAX_VALUE);
            quickLevels.getChildren().add(chip);
        }
        level.valueProperty().addListener((obs, o, n) -> quickLevels.getChildren().forEach(node ->
                ((ToggleButton) node).setSelected(node.getUserData().equals(n.intValue()))));
        Label levelHint = Ui.wrap(I18n.t("pvc.level.hint"), "t-small", "t-muted");
        levelSection.getChildren().addAll(Ui.sectionLabel(I18n.t("pvc.level")), level, quickLevels, levelHint);

        ToggleButton white = colorSegment("w", I18n.t("common.white"), "white");
        ToggleButton random = colorSegment(null, I18n.t("common.random"), "random");
        ToggleButton black = colorSegment("b", I18n.t("common.black"), "black");
        HBox colors = Ui.segmented(colorGroup, List.of(white, random, black));
        colors.getChildren().forEach(n -> n.setStyle("-fx-min-height: 140px; -fx-pref-height: 140px;"));

        VBox body = new VBox(16,
                Ui.sectionLabel(I18n.t("pvc.opponent")), bots,
                Ui.gap(8), levelSection,
                Ui.gap(8), Ui.sectionLabel(I18n.t("pvc.color")), colors,
                Ui.wrap(I18n.t("pvc.color.hint"), "t-small", "t-muted"));
        body.getStyleClass().add("screen-body");

        Button play = Ui.wide(I18n.t("common.play"), "fth-play", "btn-primary", "btn-lg");
        play.setOnAction(e -> start());
        VBox footer = Ui.footer(play);

        root.setTop(header);
        root.setCenter(Ui.scroll(body));
        root.setBottom(footer);
        refreshDefaults();
    }

    private ToggleButton bot(EngineType type, String title, String description) {
        Region dot = new Region();
        dot.getStyleClass().add("check-dot");
        HBox content = new HBox(20, Ui.texts(title, description, "option-title", "option-sub"), dot);
        content.setAlignment(Pos.CENTER_LEFT);
        ToggleButton card = new ToggleButton();
        card.getStyleClass().setAll("option");
        card.setGraphic(content);
        card.setMaxWidth(Double.MAX_VALUE);
        content.prefWidthProperty().bind(card.widthProperty().subtract(52));
        card.setToggleGroup(botGroup);
        card.setUserData(type);
        botCards.put(type, card);
        return card;
    }

    private ToggleButton colorSegment(String pieceColor, String text, String id) {
        ToggleButton segment = new ToggleButton(text);
        if (pieceColor != null) {
            ImageView king = new ImageView(ImageCache.getInstance().getImage(
                    "/images/Pieces/" + BoardThemes.currentPieces() + "/" + pieceColor + "k.png", 64, 64));
            king.setFitWidth(64);
            king.setFitHeight(64);
            segment.setGraphic(king);
        } else {
            HBox both = new HBox(-26, kingImage("w"), kingImage("b"));
            both.setAlignment(Pos.CENTER);
            segment.setGraphic(both);
        }
        segment.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
        segment.setUserData(id);
        return segment;
    }

    private static ImageView kingImage(String color) {
        ImageView king = new ImageView(ImageCache.getInstance().getImage(
                "/images/Pieces/" + BoardThemes.currentPieces() + "/" + color + "k.png", 56, 56));
        king.setFitWidth(56);
        king.setFitHeight(56);
        return king;
    }

    @Override
    public void onNavigatedTo() {
        refreshDefaults();
        markUnavailableBots();
    }

    private void refreshDefaults() {
        level.setValue(Prefs.integer("game.bot.level", 10));
        EngineType bot = lastBot();
        ToggleButton card = botCards.get(bot);
        if (card != null && !card.isDisabled()) {
            card.setSelected(true);
        } else {
            botCards.get(EngineType.STOCKFISH).setSelected(true);
        }
        String color = Prefs.string(COLOR_KEY, "white");
        colorGroup.getToggles().stream().filter(t -> color.equals(t.getUserData())).findFirst()
                .ifPresent(t -> t.setSelected(true));
        if (colorGroup.getSelectedToggle() == null) {
            colorGroup.getToggles().get(0).setSelected(true);
        }
        updateLevelState();
    }

    static EngineType lastBot() {
        try {
            return EngineType.valueOf(Prefs.string(BOT_KEY, "STOCKFISH"));
        } catch (IllegalArgumentException e) {
            return EngineType.STOCKFISH;
        }
    }

    /** "Stockfish · livello 10 · Bianco" (Home tile). */
    static String describeLastChoice() {
        EngineType bot = lastBot();
        String who = bot == EngineType.STOCKFISH
                ? I18n.t("pvc.bot.name", Prefs.integer("game.bot.level", 10))
                : "Maia " + bot.name().replace("MAIA_", "");
        String color = switch (Prefs.string(COLOR_KEY, "white")) {
            case "black" -> I18n.t("common.black");
            case "random" -> I18n.t("common.random");
            default -> I18n.t("common.white");
        };
        return who + " · " + color;
    }

    /** Maia needs lc0 and its weights: cards of engines missing on this device are disabled, with the reason. */
    private void markUnavailableBots() {
        for (Map.Entry<EngineType, ToggleButton> entry : botCards.entrySet()) {
            if (entry.getKey() == EngineType.STOCKFISH) {
                continue;
            }
            String id = entry.getKey().profileId();
            boolean missing = EngineSelection.get().profiles().stream()
                    .anyMatch(p -> p.id().equals(id) && !p.available());
            ToggleButton card = entry.getValue();
            card.setDisable(missing);
            if (missing && card.getGraphic() instanceof HBox content
                    && content.getChildren().get(0) instanceof VBox texts && texts.getChildren().size() > 1
                    && texts.getChildren().get(1) instanceof Label desc) {
                desc.setText(I18n.t("engine.unavailable.short"));
            }
            if (missing && card.isSelected()) {
                botCards.get(EngineType.STOCKFISH).setSelected(true);
            }
        }
    }

    private void updateLevelState() {
        boolean stockfish = selectedBot() == EngineType.STOCKFISH;
        levelSection.setDisable(!stockfish);
        levelSection.setOpacity(stockfish ? 1 : 0.4);
    }

    private EngineType selectedBot() {
        return botGroup.getSelectedToggle() == null ? EngineType.STOCKFISH
                : (EngineType) botGroup.getSelectedToggle().getUserData();
    }

    private void start() {
        EngineType bot = selectedBot();
        String color = colorGroup.getSelectedToggle() == null ? "white"
                : (String) colorGroup.getSelectedToggle().getUserData();
        Prefs.setAll(Map.of(BOT_KEY, bot.name(), COLOR_KEY, color,
                "game.bot.level", String.valueOf(level.getValue())));
        boolean isWhite = switch (color) {
            case "black" -> false;
            case "random" -> Math.random() < 0.5;
            default -> true;
        };
        ActiveGameController game = (ActiveGameController) mainController.getController("GAME");
        mainController.navigateTo("GAME");
        game.startPvC(level.getValue(), isWhite, bot);
    }
}
