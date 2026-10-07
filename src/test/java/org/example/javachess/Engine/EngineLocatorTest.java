package org.example.javachess.Engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EngineLocatorTest {

    @Test
    void packageManagerPathsAreRecognised() {
        assertTrue(EngineLocator.packageManagerPath(Path.of("/opt/homebrew/bin/stockfish")));
        assertTrue(EngineLocator.packageManagerPath(Path.of("/usr/games/stockfish")));
        assertFalse(EngineLocator.packageManagerPath(Path.of("/home/pi/javachess/engines/stockfish/stockfish")));
    }

    @Test
    void missingEngineGivesAnActionableMessage() {
        EngineLocator.Lookup l = EngineLocator.find("no-such-engine-xyz", "no.such.engine.path");
        assertTrue(l.path().isEmpty());
        assertTrue(l.describeMissing().contains("install-engines.sh"));
    }
}
