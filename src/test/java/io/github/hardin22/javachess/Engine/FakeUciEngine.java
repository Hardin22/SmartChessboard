package io.github.hardin22.javachess.Engine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tiny scripted UCI engine used to test {@link UciClient} edge cases in a real child process.
 * Modes (first argument):
 * <ul>
 *   <li>{@code normal}: searches "forever" in 10 ms steps, one info line per depth, honours stop and limits</li>
 *   <li>{@code crash-on-go}: exits with code 3 as soon as it receives go</li>
 *   <li>{@code crash-once}: like crash-on-go but only if the marker file (2nd arg) does not exist yet</li>
 *   <li>{@code ignore-stop}: never answers stop</li>
 *   <li>{@code no-readyok}: never answers isready</li>
 *   <li>{@code garbage}: emits malformed info lines and noise before a valid result</li>
 * </ul>
 */
public final class FakeUciEngine {

    private static final PrintStream OUT = System.out;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "normal";
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        AtomicBoolean stop = new AtomicBoolean();
        Thread search = null;
        int multiPv = 1;
        int skill = 20;
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.equals("uci")) {
                say("id name FakeEngine " + mode);
                say("option name Hash type spin default 16 min 1 max 1024");
                say("option name MultiPV type spin default 1 min 1 max 50");
                say("option name Skill Level type spin default 20 min 0 max 20");
                say("uciok");
            } else if (line.equals("isready")) {
                if (!mode.equals("no-readyok")) {
                    say("readyok");
                }
            } else if (line.startsWith("setoption name Skill Level value ")) {
                skill = Integer.parseInt(line.substring("setoption name Skill Level value ".length()).trim());
            } else if (line.startsWith("setoption name MultiPV value ")) {
                multiPv = Integer.parseInt(line.substring("setoption name MultiPV value ".length()).trim());
            } else if (line.startsWith("go")) {
                if (mode.equals("crash-on-go")) {
                    System.exit(3);
                }
                if (mode.equals("crash-once") && args.length > 1) {
                    java.nio.file.Path marker = java.nio.file.Path.of(args[1]);
                    if (!java.nio.file.Files.exists(marker)) {
                        java.nio.file.Files.createFile(marker);
                        System.exit(4);
                    }
                }
                stop.set(false);
                final int k = multiPv;
                final String go = line;
                final String best = skill < 20 ? "g1f3" : "e2e4"; // lets tests see the Skill Level in effect
                search = new Thread(() -> runSearch(go, k, stop, mode, best));
                search.setDaemon(true);
                search.start();
            } else if (line.equals("stop")) {
                if (!mode.equals("ignore-stop")) {
                    stop.set(true);
                }
            } else if (line.equals("quit")) {
                return;
            }
        }
    }

    private static void runSearch(String go, int multiPv, AtomicBoolean stop, String mode, String best) {
        List<String> t = List.of(go.split("\\s+"));
        int maxDepth = t.contains("depth") ? Integer.parseInt(t.get(t.indexOf("depth") + 1)) : Integer.MAX_VALUE;
        long movetime = t.contains("movetime") ? Long.parseLong(t.get(t.indexOf("movetime") + 1)) : Long.MAX_VALUE;
        long start = System.currentTimeMillis();
        if (mode.equals("garbage")) {
            say("info depth x score cp 12 pv e2e4");
            say("info depth 3 score cp pv");
            say("info string hello world score cp 5 pv e2e4");
            say("totally unrelated noise");
            say("info depth 2 score banana 3 pv e2e4");
            say("info depth 4 currmove e2e4 currmovenumber 1");
        }
        String[] moves = { "e2e4", "d2d4", "g1f3", "c2c4", "b1c3" };
        int depth = 0;
        while (!stop.get() && depth < maxDepth && System.currentTimeMillis() - start < movetime) {
            depth++;
            for (int k = 1; k <= Math.min(multiPv, moves.length); k++) {
                say("info depth " + depth + " seldepth " + (depth + 2) + " multipv " + k + " score cp " + (40 - 10 * k)
                        + " nodes " + depth * 1000L + " nps 100000 time " + (System.currentTimeMillis() - start)
                        + " pv " + moves[k - 1] + " e7e5");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (mode.equals("ignore-stop") && stop.get()) {
            return;
        }
        say("bestmove " + best + " ponder e7e5");
    }

    private static synchronized void say(String s) {
        OUT.println(s);
        OUT.flush();
    }
}
