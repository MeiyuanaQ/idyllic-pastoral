package com.crispyraccoon.pastoralcraft.crop;

import com.crispyraccoon.pastoralcraft.PastoralCraft;

/**
 * Lightweight timing and counting probes for locating slow growth paths.
 *
 * <p>All probes self-gate on {@link DebugGate.DebugModule#PERF}; when disabled,
 * {@link #startSection()} returns {@code 0} and callers skip {@link #endSection}
 * entirely, so the default cost is a single volatile read.</p>
 *
 * <p>Counters are written on the server main thread and read from the same
 * thread by the dump/status commands, so {@code volatile} suffices.</p>
 */
public final class DebugProfiler {
    private DebugProfiler() {
    }

    /** Cumulative count of server-side, non-internal {@code setBlock} calls. */
    private static volatile long setBlockCount;

    /** Increment the setBlock hot-path counter (perf-gated). */
    public static void incrementSetBlock() {
        if (DebugGate.enabled(DebugGate.DebugModule.PERF)) {
            setBlockCount++;
        }
    }

    public static long getSetBlockCount() {
        return setBlockCount;
    }

    /**
     * Begin a timed section. Returns {@code 0} when profiling is disabled — a
     * sentinel the caller must use to skip {@link #endSection}.
     */
    public static long startSection() {
        return DebugGate.enabled(DebugGate.DebugModule.PERF) ? System.nanoTime() : 0L;
    }

    /**
     * End a timed section; warn when it exceeded the configured threshold and
     * record the event into the ring buffer so a freeze that suppresses logging
     * is still recoverable from the on-stop dump.
     *
     * @param t0    the value returned by {@link #startSection()}; {@code 0} = disabled
     * @param label a short section label
     * @param ctx   optional context values (only stringified when over threshold)
     */
    public static void endSection(long t0, String label, Object... ctx) {
        if (t0 == 0L) return;
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        int warnMs = warnMs();
        if (elapsedMs >= warnMs) {
            String ctxStr = join(ctx);
            PastoralCraft.LOGGER.warn("[Perf] {} took {}ms{}", label, elapsedMs, ctxStr);
            DebugRingBuffer.record("PERF", "{} took {}ms{}", label, elapsedMs, ctxStr);
        }
    }

    /** A short summary line for status/dump output. */
    public static String summary() {
        return "setBlockCount=" + setBlockCount;
    }

    /** Reset all counters (perf reset command). */
    public static void reset() {
        setBlockCount = 0L;
    }

    private static int warnMs() {
        try {
            return CropGrowthConfig.DEBUG_PERF_WARN_MS.get();
        } catch (Exception ignored) {
            return 10;
        }
    }

    private static String join(Object... ctx) {
        if (ctx == null || ctx.length == 0) return "";
        StringBuilder sb = new StringBuilder(" (");
        for (int i = 0; i < ctx.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(ctx[i]);
        }
        return sb.append(')').toString();
    }
}
