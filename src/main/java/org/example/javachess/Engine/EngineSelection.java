package org.example.javachess.Engine;

import javafx.beans.property.ReadOnlyObjectProperty;

import java.util.List;

/**
 * Contract between the UI and the engine layer for choosing which engine plays and analyses.
 * The UI only talks to this interface; the engine layer owns the implementation.
 */
public interface EngineSelection {

    /** All known engine profiles, including unavailable ones (shown disabled). */
    List<EngineProfile> profiles();

    /** The active profile. Updated on the JavaFX thread. */
    ReadOnlyObjectProperty<EngineProfile> activeProfileProperty();

    /** Switches engine and persists the choice. Never blocks the JavaFX thread. */
    void select(String profileId);

    static EngineSelection get() {
        return DefaultEngineSelection.INSTANCE;
    }
}
