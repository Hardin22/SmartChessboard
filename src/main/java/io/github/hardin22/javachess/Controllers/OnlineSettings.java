package io.github.hardin22.javachess.Controllers;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import io.github.hardin22.javachess.Browser.BoardWatcher;
import io.github.hardin22.javachess.Browser.ChessSite;
import io.github.hardin22.javachess.Browser.JcefRuntime;
import io.github.hardin22.javachess.Components.I18n;
import io.github.hardin22.javachess.Components.Icons;
import io.github.hardin22.javachess.Components.LoginVault;
import io.github.hardin22.javachess.Components.Prefs;
import io.github.hardin22.javachess.Components.TouchKeyboard;
import io.github.hardin22.javachess.Components.Ui;
import io.github.hardin22.javachess.Utils.AppExecutors;
import io.github.hardin22.javachess.Utils.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Settings → "Gioco online": the logins the integrated browser may type for you (saved only after the user reads
 * where and how they are kept, removable at any time) and where the browser reads the position from
 * ({@code browser.reader}). The keyring is a separate process: every read and write runs off the JavaFX thread.
 */
final class OnlineSettings {

    private static final Logger log = LoggerFactory.getLogger(OnlineSettings.class);
    static final String READER_KEY = "browser.reader";
    private static final List<ChessSite> SITES = List.of(ChessSite.CHESS_COM, ChessSite.LICHESS);

    private final Supplier<MainController> main;
    private final LoginVault vault;
    private final Map<ChessSite, Label> statusLabels = new EnumMap<>(ChessSite.class);
    private final Map<ChessSite, Button> buttons = new EnumMap<>(ChessSite.class);
    private final Map<ChessSite, Optional<String>> saved = new EnumMap<>(ChessSite.class);
    private final Label note = Ui.wrap("", "row-sub");
    private final Label readerDescription = Ui.wrap("", "row-sub");
    private final Label readerRestart = Ui.wrap(I18n.t("online.reader.restart"), "t-small", "t-warn");
    private final ToggleGroup readerGroup = new ToggleGroup();
    private String place = "";
    private boolean updating;

    OnlineSettings(Supplier<MainController> main, LoginVault vault) {
        this.main = main;
        this.vault = vault;
    }

    /** The section's card: one row per site, the explanation, the reading mode. */
    VBox build() {
        VBox box = new VBox();
        box.getStyleClass().add("group");
        for (ChessSite site : SITES) {
            box.getChildren().addAll(siteRow(site), Ui.hairline());
        }
        VBox noteBox = new VBox(note);
        noteBox.setPadding(new Insets(20, 28, 24, 28));
        box.getChildren().addAll(noteBox, Ui.hairline(), readerBlock());
        return box;
    }

    private HBox siteRow(ChessSite site) {
        Label status = Ui.wrap(I18n.t("online.login.checking"), "row-sub");
        statusLabels.put(site, status);
        VBox texts = new VBox(4, Ui.label(site.displayName(), "row-title"), status);
        texts.setMinWidth(0);
        HBox.setHgrow(texts, Priority.ALWAYS);
        Button button = Ui.button(I18n.t("online.login.save"), "fth-key", "btn-outline", "btn-md");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setDisable(true);
        button.setId("online-login-" + site.name().toLowerCase(Locale.ROOT));
        buttons.put(site, button);
        HBox row = new HBox(16, Icons.of("fth-globe", 30), texts, button);
        row.getStyleClass().add("row");
        return row;
    }

    private VBox readerBlock() {
        ToggleButton page = segment(I18n.t("online.reader.page"), BoardWatcher.ReadMode.PAGE);
        ToggleButton vision = segment(I18n.t("online.reader.vision"), BoardWatcher.ReadMode.VISION);
        ToggleButton visionOnly = segment(I18n.t("online.reader.visiononly"), BoardWatcher.ReadMode.VISION_ONLY);
        HBox segments = Ui.segmented(readerGroup, List.of(page, vision, visionOnly));
        readerGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null) {
                return;
            }
            BoardWatcher.ReadMode mode = (BoardWatcher.ReadMode) n.getUserData();
            readerDescription.setText(describe(mode));
            if (!updating) {
                Prefs.set(READER_KEY, mode.name().toLowerCase(Locale.ROOT).replace('_', '-'));
                // the browser reads the setting each time it opens (BrowserController.showWindow)
                readerRestart.setVisible(JcefRuntime.app() != null);
                readerRestart.setManaged(readerRestart.isVisible());
            }
        });
        readerRestart.setVisible(false);
        readerRestart.setManaged(false);
        VBox block = new VBox(14, Ui.label(I18n.t("online.reader"), "row-title"), segments, readerDescription,
                readerRestart);
        block.setPadding(new Insets(24, 28, 24, 28));
        return block;
    }

    private static ToggleButton segment(String text, BoardWatcher.ReadMode mode) {
        ToggleButton b = new ToggleButton(text);
        b.setUserData(mode);
        b.setId("online-reader-" + mode.name().toLowerCase(Locale.ROOT));
        return b;
    }

    static String describe(BoardWatcher.ReadMode mode) {
        return switch (mode) {
            case PAGE -> I18n.t("online.reader.page.description");
            case VISION -> I18n.t("online.reader.vision.description");
            case VISION_ONLY -> I18n.t("online.reader.visiononly.description");
        };
    }

    /** The reading mode the browser will use (same parsing as the browser: unknown text means vision). */
    static BoardWatcher.ReadMode currentMode() {
        return BoardWatcher.ReadMode.parse(ConfigManager.getProperty(READER_KEY, BrowserController.DEFAULT_READER));
    }

    /** Re-reads the setting and the saved logins (in the background). */
    void refresh() {
        updating = true;
        BoardWatcher.ReadMode mode = currentMode();
        readerGroup.getToggles().stream().filter(t -> t.getUserData() == mode).findFirst()
                .ifPresent(t -> t.setSelected(true));
        readerDescription.setText(describe(mode));
        updating = false;
        for (ChessSite site : SITES) {
            if (!saved.containsKey(site)) {
                statusLabels.get(site).setText(I18n.t("online.login.checking"));
                buttons.get(site).setDisable(true);
            }
        }
        AppExecutors.io().execute(() -> {
            String where = vault.place();
            Map<ChessSite, Optional<String>> found = new EnumMap<>(ChessSite.class);
            for (ChessSite site : SITES) {
                found.put(site, vault.username(site));
            }
            Platform.runLater(() -> {
                place = where;
                note.setText(I18n.t("online.login.note", place));
                saved.putAll(found);
                SITES.forEach(this::showSite);
            });
        });
    }

    private void showSite(ChessSite site) {
        Optional<String> user = saved.getOrDefault(site, Optional.empty());
        Label status = statusLabels.get(site);
        Button button = buttons.get(site);
        button.setDisable(false);
        button.getStyleClass().removeAll("btn-outline", "btn-danger");
        if (user.isPresent()) {
            status.setText(I18n.t("online.login.saved", user.get()));
            button.setText(I18n.t("online.login.remove"));
            button.setGraphic(Icons.of("fth-trash-2", 24));
            button.getStyleClass().add("btn-danger");
            button.setOnAction(e -> confirmRemove(site));
        } else {
            status.setText(I18n.t("online.login.none"));
            button.setText(I18n.t("online.login.save"));
            button.setGraphic(Icons.of("fth-key", 24));
            button.getStyleClass().add("btn-outline");
            button.setOnAction(e -> openSave(site));
        }
    }

    // ------------------------------------------------------------------ save

    /** The form to save a login: what happens to it first, then name and password on the on-screen keyboard. */
    void openSave(ChessSite site) {
        new LoginForm(site).show();
    }

    private final class LoginForm {
        private final ChessSite site;
        private final TouchKeyboard keyboard = new TouchKeyboard("");
        private final Field userField = new Field(I18n.t("online.login.username"), false);
        private final Field passwordField = new Field(I18n.t("online.login.password"), true);
        private final Label error = Ui.wrap("", "t-small", "t-danger");
        private Field active;
        private boolean busy;

        LoginForm(ChessSite site) {
            this.site = site;
        }

        void show() {
            Label consent = Ui.wrap(I18n.t("online.login.consent", site.displayName(), place), "t-body", "t-muted");
            error.setVisible(false);
            error.setManaged(false);
            keyboard.setShowDisplay(false);
            keyboard.textProperty().addListener((obs, o, n) -> {
                if (active != null) {
                    active.setValue(n);
                }
                hideError();
            });
            keyboard.setOnDone(this::next);
            userField.box.setOnMouseClicked(e -> focus(userField));
            passwordField.box.setOnMouseClicked(e -> focus(passwordField));
            focus(userField);
            VBox fields = new VBox(12, userField.box, passwordField.box, error);
            Node content;
            if (main.get().isWide()) {
                // landscape: 720 px of height do not fit text, fields and keyboard in one column
                VBox left = new VBox(20, consent, fields);
                left.setPrefWidth(560);
                left.setMinWidth(560);
                HBox.setHgrow(keyboard, Priority.ALWAYS);
                content = new HBox(28, left, keyboard);
            } else {
                content = new VBox(20, consent, fields, keyboard);
            }
            content.setId("online-login-form");
            main.get().showWideSheet(I18n.t("online.login.title", site.displayName()), content, 1500);
        }

        private void focus(Field field) {
            active = field;
            userField.box.pseudoClassStateChanged(ACTIVE, field == userField);
            passwordField.box.pseudoClassStateChanged(ACTIVE, field == passwordField);
            keyboard.setMasked(field.secret && !field.revealed);
            keyboard.textProperty().set(field.value);
            keyboard.setDoneText(I18n.t(field == userField ? "online.login.next" : "online.login.store"));
            userField.render();
            passwordField.render();
        }

        private void next() {
            if (active == userField) {
                focus(passwordField);
                return;
            }
            String user = userField.value.trim();
            String password = passwordField.value;
            if (user.isEmpty()) {
                showError(I18n.t("online.login.missing.username"));
                focus(userField);
                return;
            }
            if (password.isEmpty()) {
                showError(I18n.t("online.login.missing.password"));
                return;
            }
            if (busy) {
                return;
            }
            busy = true;
            keyboard.setDisable(true);
            AppExecutors.io().execute(() -> {
                String failure = null;
                try {
                    vault.save(site, user, password);
                } catch (Exception ex) {
                    log.warn("Cannot save the login for {}: {}", site.displayName(), ex.getClass().getSimpleName());
                    failure = I18n.t("online.login.failed", place);
                }
                String f = failure;
                Platform.runLater(() -> {
                    busy = false;
                    keyboard.setDisable(false);
                    if (f != null) {
                        showError(f);
                        return;
                    }
                    saved.put(site, Optional.of(user));
                    showSite(site);
                    main.get().closeSheet();
                    main.get().showToast(I18n.t("online.login.done", site.displayName()));
                });
            });
        }

        private void showError(String text) {
            error.setText(text);
            error.setVisible(true);
            error.setManaged(true);
        }

        private void hideError() {
            if (error.isVisible()) {
                error.setVisible(false);
                error.setManaged(false);
            }
        }

        /** A large tappable field: label above, value (dots for the password) below; the eye shows the password. */
        private final class Field {
            final HBox box = new HBox(12);
            final boolean secret;
            final Label valueLabel = Ui.label("", "field-value");
            String value = "";
            boolean revealed;

            Field(String title, boolean secret) {
                this.secret = secret;
                Label titleLabel = Ui.label(title, "field-title");
                VBox texts = new VBox(2, titleLabel, valueLabel);
                texts.setMinWidth(0);
                HBox.setHgrow(texts, Priority.ALWAYS);
                box.getChildren().add(texts);
                box.getStyleClass().add("form-field");
                box.setAlignment(Pos.CENTER_LEFT);
                if (secret) {
                    Button eye = Ui.iconButton("fth-eye", I18n.t("online.login.show"), () -> {
                        revealed = !revealed;
                        if (active == this) {
                            keyboard.setMasked(!revealed);
                        }
                        render();
                    });
                    eye.getStyleClass().add("field-eye");
                    box.getChildren().add(eye);
                }
                render();
            }

            void setValue(String text) {
                value = text == null ? "" : text;
                render();
            }

            void render() {
                valueLabel.setText(TouchKeyboard.shown(value, secret && !revealed) + (active == this ? "▏" : ""));
            }
        }
    }

    private static final javafx.css.PseudoClass ACTIVE = javafx.css.PseudoClass.getPseudoClass("active");

    // ------------------------------------------------------------------ remove

    private void confirmRemove(ChessSite site) {
        Label text = Ui.wrap(I18n.t("online.login.remove.confirm", site.displayName()), "t-body", "t-muted");
        Button cancel = Ui.wide(I18n.t("common.cancel"), null, "btn-outline", "btn-lg");
        cancel.setOnAction(e -> main.get().closeSheet());
        Button confirm = Ui.wide(I18n.t("online.login.remove"), "fth-trash-2", "btn-danger-solid", "btn-lg");
        confirm.setOnAction(e -> {
            main.get().closeSheet();
            buttons.get(site).setDisable(true);
            AppExecutors.io().execute(() -> {
                vault.remove(site);
                Optional<String> left = vault.username(site);
                Platform.runLater(() -> {
                    saved.put(site, left);
                    showSite(site);
                    main.get().showToast(I18n.t(left.isPresent() ? "online.login.remove.failed"
                            : "online.login.removed", site.displayName()));
                });
            });
        });
        Node buttonsRow = Ui.equalRow(14, cancel, confirm);
        main.get().showModalSheet(I18n.t("online.login.remove.title", site.displayName()),
                new VBox(24, text, buttonsRow));
    }
}
