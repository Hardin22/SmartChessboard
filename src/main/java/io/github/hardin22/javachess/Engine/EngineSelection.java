package io.github.hardin22.javachess.Engine;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.List;

/**
 * Contract between the UI and the engine layer for choosing which engine plays and analyses.
 * The UI only talks to this interface; the engine layer owns the implementation ({@link EngineManager}).
 */
public interface EngineSelection {

    /** All known engine profiles, including unavailable ones (shown disabled). */
    List<EngineProfile> profiles();

    /** The active profile. Updated on the JavaFX thread. */
    ReadOnlyObjectProperty<EngineProfile> activeProfileProperty();

    /** Switches engine and persists the choice. Never blocks the JavaFX thread. Works during a game. */
    void select(String profileId);

    /**
     * Engine state (loading while a profile switch starts the new engine, ready, or error with a message
     * such as "lc0 not found..."). Updated on the JavaFX thread.
     */
    default ReadOnlyObjectProperty<EngineStatus> statusProperty() {
        return new SimpleObjectProperty<>(new EngineStatus(EngineStatus.State.READY, "", ""));
    }

    static EngineSelection get() {
        return EngineManager.get();
    }
}
