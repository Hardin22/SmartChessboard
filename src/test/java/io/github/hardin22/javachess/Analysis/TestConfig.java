package io.github.hardin22.javachess.Analysis;

import io.github.hardin22.javachess.Utils.ConfigManager;

import java.util.HashMap;
import java.util.Map;

/** Settings of the feature tests: surefire points javachess.home at target/test-home; keys are reset per test. */
final class TestConfig {

    private TestConfig() {
    }

    static void isolate() {
        if (System.getProperty("javachess.home") == null) {
            System.setProperty("javachess.home", System.getProperty("java.io.tmpdir") + "/javachess-test-home");
            ConfigManager.reload();
        }
        Map<String, String> reset = new HashMap<>();
        reset.put(EngineLines.LINES_KEY, null);
        reset.put(EngineLines.DEPTH_KEY, null);
        ConfigManager.setProperties(reset);
    }
}
