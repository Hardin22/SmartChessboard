package io.github.hardin22.javachess.Hardware;

import io.github.hardin22.javachess.Utils.AppExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Composes everything shown on the board LEDs and sends it to the hardware.
 *
 * <p>Content lives in {@link Layer layers}; a higher layer covers a lower one on the squares it lights. Callers
 * change layers from any thread and return immediately; a single "led-render" thread composes a frame at most
 * every {@link #MIN_FRAME_INTERVAL_MS} ms (many changes in a burst become one frame) and sends it only if it
 * differs from the previous one, so there is no flicker and the serial link carries only real changes.
 * Blinking and timed effects are evaluated by the same thread, so nobody sleeps.</p>
 */
public final class LedRenderer {

    private static final Logger log = LoggerFactory.getLogger(LedRenderer.class);

    /** Minimum time between two frames (about 60 frames per second). */
    public static final long MIN_FRAME_INTERVAL_MS = 16;
    public static final int TRANSPARENT = -1;

    /** Layers from lowest to highest priority. */
    public enum Layer {
        /** Setup guidance and opponent move to replicate. */
        BASE,
        /** Legal moves / move quality for the lifted piece. */
        HINT,
        /** Quality of the move just played (expires by itself). */
        VERDICT,
        /** Direct per-square commands from older code ({@code ArduinoController.sendLedCommand}). */
        LEGACY,
        /** Errors, check, short flashes. */
        ALERT,
        /** Full-board animations (victory). */
        ANIMATION
    }

    /** Time-dependent content of a layer. */
    private interface Effect {
        /** Color of the square at {@code now}, or TRANSPARENT. */
        int colorAt(int square, long now);

        /** When the effect ends (ms, same clock as now); Long.MAX_VALUE if it never ends by itself. */
        long endsAt();

        /** Next time the output of this effect changes, or Long.MAX_VALUE. */
        long nextChange(long now);
    }

    private final Object lock = new Object();
    private final Map<Layer, int[]> pixels = new EnumMap<>(Layer.class);
    private final Map<Layer, List<Effect>> effects = new EnumMap<>(Layer.class);
    private final ScheduledExecutorService renderThread;
    private final boolean ownsRenderThread;
    private final LongSupplier clockMs;
    private final LedMapping mapping;
    private volatile BoardHardware hardware;

    private ScheduledFuture<?> scheduledRender;
    private long scheduledAt = Long.MAX_VALUE;
    private long lastRenderAt = Long.MIN_VALUE / 2;
    private int[] lastSent;
    private int brightnessPercent = 100;
    private long framesSent;

    public LedRenderer(BoardHardware hardware, LedMapping mapping) {
        this(hardware, mapping, Executors.newSingleThreadScheduledExecutor(AppExecutors.daemonFactory("led-render")),
                true, System::currentTimeMillis);
    }

    LedRenderer(BoardHardware hardware, LedMapping mapping, ScheduledExecutorService renderThread,
                boolean ownsRenderThread, LongSupplier clockMs) {
        this.hardware = hardware;
        this.mapping = mapping;
        this.renderThread = renderThread;
        this.ownsRenderThread = ownsRenderThread;
        this.clockMs = clockMs;
        for (Layer layer : Layer.values()) {
            int[] layerPixels = new int[64];
            Arrays.fill(layerPixels, TRANSPARENT);
            pixels.put(layer, layerPixels);
            effects.put(layer, new ArrayList<>());
        }
    }

    public LedMapping mapping() {
        return mapping;
    }

    /** Switches to another board (e.g. simulator) and resends the current content. */
    public void setHardware(BoardHardware newHardware) {
        synchronized (lock) {
            hardware = newHardware;
            lastSent = null;
        }
        requestRender();
    }

    /** Brightness applied by the app, 0-100 %, on top of the firmware power cap. */
    public void setBrightnessPercent(int percent) {
        synchronized (lock) {
            brightnessPercent = Math.max(0, Math.min(100, percent));
            lastSent = null;
        }
        requestRender();
    }

    // --- static content ------------------------------------------------------------------------------------

    public void set(Layer layer, int square, int rgb) {
        synchronized (lock) {
            pixels.get(layer)[square] = rgb;
        }
        requestRender();
    }

    /** Replaces the whole static content of a layer (squares not in the map become transparent). */
    public void replace(Layer layer, Map<Integer, Integer> squareColors) {
        synchronized (lock) {
            int[] layerPixels = pixels.get(layer);
            Arrays.fill(layerPixels, TRANSPARENT);
            squareColors.forEach((square, rgb) -> layerPixels[square] = rgb);
        }
        requestRender();
    }

    /** Clears the static content and the effects of a layer. */
    public void clear(Layer layer) {
        synchronized (lock) {
            Arrays.fill(pixels.get(layer), TRANSPARENT);
            effects.get(layer).clear();
        }
        requestRender();
    }

    /** Turns everything off (all layers). */
    public void clearAll() {
        synchronized (lock) {
            for (Layer layer : Layer.values()) {
                Arrays.fill(pixels.get(layer), TRANSPARENT);
                effects.get(layer).clear();
            }
        }
        requestRender();
    }

    // --- effects -------------------------------------------------------------------------------------------

    /** Blinks {@code squares} {@code times} times (on for half a period, off for the other half). */
    public void flash(Layer layer, int[] squares, int rgb, int times, long periodMs) {
        long start = clockMs.getAsLong();
        long end = start + times * periodMs;
        long[] mask = {maskOf(squares)};
        addEffect(layer, new Effect() {
            @Override
            public int colorAt(int square, long now) {
                if ((mask[0] & Squares.bit(square)) == 0 || now >= end) {
                    return TRANSPARENT;
                }
                return ((now - start) % periodMs) < periodMs / 2 ? rgb : 0;
            }

            @Override
            public long endsAt() {
                return end;
            }

            @Override
            public long nextChange(long now) {
                long half = periodMs / 2;
                return Math.min(end, start + ((now - start) / half + 1) * half);
            }
        });
    }

    /** Shows {@code squareColors} on {@code layer} for {@code durationMs}, then removes them. */
    public void showFor(Layer layer, Map<Integer, Integer> squareColors, long durationMs) {
        long end = clockMs.getAsLong() + durationMs;
        int[] colors = new int[64];
        Arrays.fill(colors, TRANSPARENT);
        squareColors.forEach((square, rgb) -> colors[square] = rgb);
        addEffect(layer, new Effect() {
            @Override
            public int colorAt(int square, long now) {
                return now < end ? colors[square] : TRANSPARENT;
            }

            @Override
            public long endsAt() {
                return end;
            }

            @Override
            public long nextChange(long now) {
                return end;
            }
        });
    }

    /** Diagonal colored waves sweeping from a1 to h8 (end of game / puzzle solved). */
    public void playVictoryWave() {
        int[] palette = {0xFFFFFF, 0x00BFFF, 0xFF00FF};
        long stepMs = 70;
        int steps = 17;
        long start = clockMs.getAsLong();
        long end = start + palette.length * steps * stepMs;
        addEffect(Layer.ANIMATION, new Effect() {
            @Override
            public int colorAt(int square, long now) {
                if (now >= end) {
                    return TRANSPARENT;
                }
                long step = (now - start) / stepMs;
                int wave = (int) (step / steps);
                int front = (int) (step % steps);
                int diagonal = square % 8 + square / 8;
                return diagonal <= front && diagonal > front - 3 ? palette[wave % palette.length] : 0;
            }

            @Override
            public long endsAt() {
                return end;
            }

            @Override
            public long nextChange(long now) {
                return Math.min(end, start + ((now - start) / stepMs + 1) * stepMs);
            }
        });
    }

    private void addEffect(Layer layer, Effect effect) {
        synchronized (lock) {
            effects.get(layer).add(effect);
        }
        requestRender();
    }

    private static long maskOf(int[] squares) {
        long mask = 0;
        for (int square : squares) {
            mask |= Squares.bit(square);
        }
        return mask;
    }

    // --- rendering -----------------------------------------------------------------------------------------

    /** The composed frame in square order (0 = a1), as it would be sent now; used by tests and the simulator. */
    public int[] composeNow() {
        synchronized (lock) {
            return compose(clockMs.getAsLong());
        }
    }

    public long framesSent() {
        synchronized (lock) {
            return framesSent;
        }
    }

    private void requestRender() {
        synchronized (lock) {
            long now = clockMs.getAsLong();
            scheduleAt(Math.max(now, lastRenderAt + MIN_FRAME_INTERVAL_MS), now);
        }
    }

    /** Called with the lock held. */
    private void scheduleAt(long when, long now) {
        if (scheduledRender != null && !scheduledRender.isDone() && scheduledAt <= when) {
            return;
        }
        if (scheduledRender != null) {
            scheduledRender.cancel(false);
        }
        scheduledAt = when;
        try {
            scheduledRender = renderThread.schedule(this::render, Math.max(0, when - now), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            scheduledRender = null; // shutting down
        }
    }

    private void render() {
        int[] wire;
        BoardHardware target;
        synchronized (lock) {
            long now = clockMs.getAsLong();
            lastRenderAt = now;
            scheduledAt = Long.MAX_VALUE;
            scheduledRender = null;
            int[] frame = compose(now);
            long next = pruneAndNextChange(now);
            if (next != Long.MAX_VALUE) {
                scheduleAt(Math.max(next, now + MIN_FRAME_INTERVAL_MS), now);
            }
            if (lastSent != null && Arrays.equals(frame, lastSent)) {
                return;
            }
            lastSent = frame;
            framesSent++;
            wire = mapping.toWireOrder(applyBrightness(frame));
            target = hardware;
        }
        try {
            if (target != null) {
                target.sendFrame(wire);
            }
        } catch (RuntimeException e) {
            log.warn("LED frame not delivered: {}", e.toString());
        }
    }

    /** Called with the lock held. */
    private int[] compose(long now) {
        int[] frame = new int[64];
        for (Layer layer : Layer.values()) {
            int[] layerPixels = pixels.get(layer);
            List<Effect> layerEffects = effects.get(layer);
            for (int square = 0; square < 64; square++) {
                if (layerPixels[square] != TRANSPARENT) {
                    frame[square] = layerPixels[square];
                }
                for (Effect effect : layerEffects) {
                    int rgb = effect.colorAt(square, now);
                    if (rgb != TRANSPARENT) {
                        frame[square] = rgb;
                    }
                }
            }
        }
        return frame;
    }

    /** Removes finished effects; returns when the next frame is due because of an effect. Lock held. */
    private long pruneAndNextChange(long now) {
        long next = Long.MAX_VALUE;
        for (List<Effect> layerEffects : effects.values()) {
            Iterator<Effect> it = layerEffects.iterator();
            while (it.hasNext()) {
                Effect effect = it.next();
                if (effect.endsAt() <= now) {
                    it.remove();
                } else {
                    next = Math.min(next, effect.nextChange(now));
                }
            }
        }
        return next;
    }

    /** Lock held. */
    private int[] applyBrightness(int[] frame) {
        if (brightnessPercent >= 100) {
            return frame;
        }
        int[] scaled = new int[64];
        for (int i = 0; i < 64; i++) {
            int rgb = frame[i];
            int r = ((rgb >> 16) & 0xFF) * brightnessPercent / 100;
            int g = ((rgb >> 8) & 0xFF) * brightnessPercent / 100;
            int b = (rgb & 0xFF) * brightnessPercent / 100;
            scaled[i] = (r << 16) | (g << 8) | b;
        }
        return scaled;
    }

    /** Stops the render thread (application exit). Turns the LEDs off first. */
    public void shutdown() {
        clearAll();
        BoardHardware target = hardware;
        if (target != null) {
            target.sendFrame(new int[64]);
        }
        if (ownsRenderThread) {
            renderThread.shutdownNow();
        }
    }
}
