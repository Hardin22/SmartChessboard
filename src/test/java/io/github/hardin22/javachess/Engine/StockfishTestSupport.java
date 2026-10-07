package io.github.hardin22.javachess.Engine;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Locates a real Stockfish for integration tests; tests are skipped (not failed) when it is missing (CI). */
final class StockfishTestSupport {

    private StockfishTestSupport() {
    }

    static Optional<Path> stockfish() {
        String prop = System.getProperty("stockfish.path");
        if (prop != null && !prop.isBlank()) {
            return Optional.of(Path.of(prop));
        }
        return EngineLocator.stockfish().path();
    }

    static Path requireStockfish() {
        Optional<Path> p = stockfish();
        assumeTrue(p.isPresent(), "Stockfish not installed: integration test skipped");
        return p.get();
    }

    static UciClient client(String name, int threads, int hashMb) {
        return new UciClient(EngineSpec.of(name, requireStockfish(), EngineManager.stockfishOptions(threads, hashMb)));
    }

    static EngineManager.Budget budget(int min, int confirm) {
        return new EngineManager.Budget(1, 16, 30, min, confirm, 2_500, 200_000, 2_000, 1, 16, 1_000, 0, 0);
    }

    static Map<String, String> opts(int threads, int hash) {
        return EngineManager.stockfishOptions(threads, hash);
    }
}
