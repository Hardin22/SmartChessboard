package io.github.hardin22.javachess.Browser.trial;

import io.github.hardin22.javachess.Browser.JcefRuntime;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;

/**
 * Developer tool: Chromium started while other threads free native memory, to reproduce the macOS crashes of
 * 8 October 2026 (a free() trapped inside the Chromium framework while it loads and makes PartitionAlloc the
 * default malloc zone). {@code java ... CefLoadRace during|before|none}: busy threads start before ("during") or
 * after ("before") Chromium's start, or Chromium is not started ("none"). Exits 0 when it survived.
 */
public final class CefLoadRace {

    private CefLoadRace() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "during";
        AtomicBoolean run = new AtomicBoolean(true);
        Runnable busy = () -> {
            while (run.get()) {
                Deflater d = new Deflater(); // malloc in the native zlib stream
                d.end(); // free
            }
        };
        if (mode.equals("before")) {
            start();
        }
        if (mode.equals("preload")) {
            System.out.println("[load-race] preloaded " + JcefRuntime.startup());
        }
        for (int i = 0; i < 4; i++) {
            Thread t = new Thread(busy, "free-" + i);
            t.setDaemon(true);
            t.start();
        }
        Thread.sleep(200);
        if (mode.equals("during") || mode.equals("preload")) {
            start(); // preload: the framework is already loaded, Chromium starts as at the first opening
        }
        Thread.sleep(1500);
        run.set(false);
        System.out.println("[load-race] survived " + mode);
        System.exit(0);
    }

    private static void start() throws Exception {
        JcefRuntime.start(new JcefRuntime.Progress() {
            @Override
            public void downloading(double fraction) {
            }

            @Override
            public void installing() {
            }
        }).get(2, TimeUnit.MINUTES);
    }
}
