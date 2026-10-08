package io.github.hardin22.javachess.Controllers;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.ScreenHeader;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Services.GameArchiveService;
import io.github.hardin22.javachess.Stats.PlayerStats;
import io.github.hardin22.javachess.Stats.ReviewStore;
import io.github.hardin22.javachess.Utils.AppExecutors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Personal statistics: results, form, accuracy, opponents, openings, kinds of game. Only the games where "I" can be
 * recognised count (against the computer, online with my username). Presentation only; {@link PlayerStats} computes.
 */
public class StatsController implements Screen, NavigationAware {

    private MainController mainController;
    private final BorderPane root = new BorderPane();
    private final ScreenHeader header;
    private final ToggleGroup period = new ToggleGroup();
    private final VBox body = new VBox(16);
    private int generation;

    public StatsController() {
        header = new ScreenHeader(I18n.t("stats.title"), () -> mainController.navigateTo("HOME"));
        header.setSubtitle(I18n.t("stats.subtitle"));
        ToggleButton all = new ToggleButton(I18n.t("stats.period.all"));
        ToggleButton month = new ToggleButton(I18n.t("stats.period.month"));
        ToggleButton week = new ToggleButton(I18n.t("stats.period.week"));
        all.setUserData(0);
        month.setUserData(30);
        week.setUserData(7);
        HBox periods = Ui.segmented(period, List.of(all, month, week));
        all.setSelected(true);
        Ui.keepOneSelected(period);
        period.selectedToggleProperty().addListener((obs, o, n) -> reload());
        VBox top = new VBox(16, header, padded(periods));
        root.setTop(top);
        body.getStyleClass().add("screen-body");
        root.setCenter(Ui.scroll(body));
    }

    private static Node padded(Node n) {
        VBox box = new VBox(n);
        box.setPadding(new Insets(0, 24, 8, 24));
        return box;
    }

    @Override
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    @Override
    public void onNavigatedTo() {
        reload();
    }

    @Override
    public boolean onBack() {
        mainController.navigateTo("HOME");
        return true;
    }

    private void reload() {
        int days = period.getSelectedToggle() == null ? 0 : (int) period.getSelectedToggle().getUserData();
        int gen = ++generation;
        AppExecutors.io().execute(() -> {
            PlayerStats.Stats stats;
            try {
                var games = GameArchiveService.getInstance().list();
                if (days > 0) {
                    games = PlayerStats.since(games, LocalDateTime.now().minusDays(days));
                }
                stats = PlayerStats.compute(games, ReviewStore.get().summaries(), PlayerStats.Identity.fromSettings());
            } catch (RuntimeException e) {
                stats = null;
            }
            PlayerStats.Stats s = stats;
            Platform.runLater(() -> {
                if (gen == generation) {
                    show(s);
                }
            });
        });
    }

    private void show(PlayerStats.Stats s) {
        body.getChildren().clear();
        if (s == null || s.total().games() == 0) {
            VBox empty = new VBox(18, Icons.of("fth-bar-chart-2", 64), Ui.label(I18n.t("stats.empty"), "empty-title"),
                    Ui.wrap(I18n.t("stats.empty.text"), "empty-sub"));
            empty.getStyleClass().add("empty-state");
            body.getChildren().add(empty);
            header.setSubtitle(I18n.t("stats.subtitle"));
            return;
        }
        header.setSubtitle(I18n.t("stats.counted", s.total().games())
                + (s.unfinished() > 0 ? " · " + I18n.t("stats.unfinished", s.unfinished()) : ""));

        // results
        PlayerStats.Score t = s.total();
        Label percent = Ui.label(t.percentText(), "stats-big");
        Label points = Ui.wrap(I18n.t("stats.points"), "t-small", "t-muted");
        VBox big = new VBox(0, percent, points);
        percent.setMinWidth(Region.USE_PREF_SIZE);
        big.setMinWidth(Region.USE_PREF_SIZE);
        VBox wdl = new VBox(10, scoreBar(t), Ui.label(I18n.t("stats.wdl", t.wins(), t.draws(), t.losses()), "t-body"));
        HBox.setHgrow(wdl, Priority.ALWAYS);
        wdl.setAlignment(Pos.CENTER_LEFT);
        HBox results = new HBox(28, big, wdl);
        results.setAlignment(Pos.CENTER_LEFT);
        VBox resultsCard = card(Ui.label(I18n.t("stats.results"), "row-title"), results,
                Ui.hairline(), sideRow(I18n.t("stats.white"), s.asWhite()), sideRow(I18n.t("stats.black"), s.asBlack()));

        // form
        HBox dots = new HBox(8);
        for (int outcome : s.form()) {
            Label dot = Ui.label(I18n.t(outcome > 0 ? "stats.form.win" : outcome < 0 ? "stats.form.loss" : "stats.form.draw"),
                    "form-dot", outcome > 0 ? "win" : outcome < 0 ? "loss" : "draw");
            dots.getChildren().add(dot);
        }
        String streak = s.currentStreak() > 1 ? I18n.t("stats.streak", s.currentStreak())
                : s.currentStreak() == 1 ? I18n.t("stats.streak.one") : I18n.t("stats.streak.none");
        VBox formCard = card(Ui.label(I18n.t("stats.form"), "row-title"),
                Ui.wrap(I18n.t("stats.form.hint"), "t-small", "t-muted"), dots,
                Ui.label(streak + (s.bestStreak() > 1 ? " · " + I18n.t("stats.streak.best", s.bestStreak()) : ""),
                        "t-body"));

        // accuracy
        VBox accuracyCard;
        if (s.reviewed() == 0) {
            accuracyCard = card(Ui.label(I18n.t("stats.accuracy"), "row-title"),
                    Ui.wrap(I18n.t("stats.accuracy.none"), "t-body", "t-muted"));
        } else {
            // same form as the review ("81,4%"): a number without the sign reads as a score
            Label value = Ui.label(s.accuracyText() + "%", "stats-big");
            value.setMinWidth(Region.USE_PREF_SIZE);
            VBox texts = new VBox(4, Ui.wrap(s.reviewed() == 1 ? I18n.t("stats.accuracy.of.one")
                    : I18n.t("stats.accuracy.of", s.reviewed()), "t-body"));
            if (!Double.isNaN(s.recentAccuracy())) {
                HBox recent = new HBox(10, Ui.label(I18n.t("stats.accuracy.recent",
                        String.format(java.util.Locale.ITALIAN, "%.1f%%", s.recentAccuracy())), "t-small", "t-muted"));
                String trend = s.trendText();
                if (!trend.isEmpty()) {
                    recent.getChildren().add(Ui.label(trend, "t-small", s.accuracyTrend() >= 0 ? "t-ok" : "t-danger"));
                }
                texts.getChildren().add(recent);
            }
            HBox.setHgrow(texts, Priority.ALWAYS);
            HBox row = new HBox(28, value, texts);
            row.setAlignment(Pos.CENTER_LEFT);
            accuracyCard = card(Ui.label(I18n.t("stats.accuracy"), "row-title"), row);
        }

        body.getChildren().addAll(resultsCard, formCard, accuracyCard);
        table(I18n.t("stats.opponents"), s.byOpponent());
        table(I18n.t("stats.openings"), s.openings());
        table(I18n.t("stats.modes"), s.byMode());
    }

    /** Wins, draws and losses as one bar of three colours. */
    private static Node scoreBar(PlayerStats.Score score) {
        HBox bar = new HBox(3);
        bar.getStyleClass().add("stats-bar");
        bar.setMinHeight(18);
        int games = Math.max(1, score.games());
        int[] counts = { score.wins(), score.draws(), score.losses() };
        String[] classes = { "win", "draw", "loss" };
        for (int i = 0; i < 3; i++) {
            if (counts[i] == 0) {
                continue;
            }
            Region part = new Region();
            part.getStyleClass().addAll("stats-bar-part", classes[i]);
            part.setMinWidth(8);
            part.setPrefWidth(1000.0 * counts[i] / games);
            part.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(part, Priority.ALWAYS);
            bar.getChildren().add(part);
        }
        return bar;
    }

    private static Node sideRow(String side, PlayerStats.Score score) {
        Label name = Ui.label(side, "t-body");
        HBox.setHgrow(name, Priority.ALWAYS);
        name.setMaxWidth(Double.MAX_VALUE);
        Label detail = Ui.label(score.games() == 0 ? "—"
                : I18n.t("stats.wdl.short", score.wins(), score.draws(), score.losses()), "t-small", "t-muted");
        Label pct = Ui.label(score.games() == 0 ? "" : score.percentText(), "t-body-m");
        pct.setMinWidth(72);
        pct.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(16, name, detail, pct);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinHeight(52);
        return row;
    }

    private void table(String title, List<PlayerStats.Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        VBox list = new VBox();
        for (int i = 0; i < rows.size(); i++) {
            PlayerStats.Row r = rows.get(i);
            if (i > 0) {
                list.getChildren().add(Ui.hairline());
            }
            Label name = Ui.label(r.name(), "t-body-m");
            name.setMinWidth(0);
            String games = I18n.t(r.score().games() == 1 ? "stats.games.one" : "stats.games", r.score().games());
            String detail = games + " · " + I18n.t("stats.wdl.short", r.score().wins(), r.score().draws(),
                    r.score().losses()) + (r.reviewed() > 0 ? " · " + I18n.t("stats.row.accuracy", r.accuracyText()) : "");
            VBox texts = new VBox(2, name, Ui.label(detail, "t-small", "t-muted"));
            texts.setMinWidth(0);
            HBox.setHgrow(texts, Priority.ALWAYS);
            Label pct = Ui.label(r.score().percentText(), "stats-pct");
            pct.setMinWidth(Region.USE_PREF_SIZE);
            HBox row = new HBox(16, texts, pct);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinHeight(84);
            list.getChildren().add(row);
        }
        body.getChildren().addAll(Ui.gap(4), Ui.sectionLabel(title), card(list));
    }

    private static VBox card(Node... children) {
        VBox card = new VBox(14, children);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(22, 24, 22, 24));
        return card;
    }
}
