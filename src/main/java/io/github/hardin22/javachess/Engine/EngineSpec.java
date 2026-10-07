package io.github.hardin22.javachess.Engine;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How to launch and configure one engine process.
 *
 * @param name        label for logs and thread names (e.g. "analysis", "bot-maia-1500")
 * @param command     executable followed by its arguments
 * @param options     UCI options sent once after the handshake, in order
 * @param workingDir  working directory or null
 * @param readyTimeoutMs max time for uci/isready handshakes (lc0 loads its network at isready)
 */
public record EngineSpec(String name, List<String> command, Map<String, String> options, Path workingDir,
                         long readyTimeoutMs) {

    public EngineSpec {
        command = List.copyOf(command);
        options = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    public static EngineSpec of(String name, Path executable, Map<String, String> options) {
        return new EngineSpec(name, List.of(executable.toString()), options, null, 15_000);
    }

    public EngineSpec withCommand(List<String> cmd) {
        return new EngineSpec(name, cmd, options, workingDir, readyTimeoutMs);
    }

    public EngineSpec withReadyTimeout(long ms) {
        return new EngineSpec(name, command, options, workingDir, ms);
    }
}
