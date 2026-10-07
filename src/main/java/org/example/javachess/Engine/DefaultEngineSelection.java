package org.example.javachess.Engine;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.example.javachess.Services.EngineService;
import org.example.javachess.Utils.ConfigManager;

import java.util.List;

/** Placeholder implementation backed by the existing {@link EngineService}; to be replaced by the engine layer. */
final class DefaultEngineSelection implements EngineSelection {

    private static final List<EngineProfile> PROFILES = List.of(
            new EngineProfile("stockfish", "Stockfish", "Full strength", true));

    // Declared after PROFILES: the constructor reads it.
    static final DefaultEngineSelection INSTANCE = new DefaultEngineSelection();

    private final ReadOnlyObjectWrapper<EngineProfile> active = new ReadOnlyObjectWrapper<>(PROFILES.get(0));

    private DefaultEngineSelection() {
    }

    @Override
    public List<EngineProfile> profiles() {
        return PROFILES;
    }

    @Override
    public ReadOnlyObjectProperty<EngineProfile> activeProfileProperty() {
        return active.getReadOnlyProperty();
    }

    @Override
    public void select(String profileId) {
        PROFILES.stream().filter(p -> p.id().equals(profileId)).findFirst().ifPresent(p -> {
            ConfigManager.setProperty("engine.profile", p.id());
            Thread.ofVirtual().start(() -> EngineService.getInstance().setEngineType(EngineService.EngineType.STOCKFISH));
            if (Platform.isFxApplicationThread()) active.set(p); else Platform.runLater(() -> active.set(p));
        });
    }
}
