package com.crispyraccoon.pastoralcraft.crop;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Central debug gate for PastoralCraft's modular diagnostics system.
 *
 * <p>Every debug feature is independently configurable and default-off. The
 * gate caches the configured switches into a single {@code volatile int}
 * bitmask so the hot-path check is one volatile read plus two bit tests — no
 * {@link ModConfigSpec.ConfigValue#get()} call (which synchronizes and boxes)
 * ever runs on the hot path.</p>
 *
 * <p>The cache is refreshed by {@link #refreshCache()}, which is invoked from
 * {@link CropGrowthConfig#refreshOverrides()} — the same reload chain driven by
 * tag updates and config (re)load.</p>
 *
 * <p>When the cache has never been refreshed (e.g. unit tests that never load
 * the config), the bitmask stays {@code 0} and every module reads disabled, so
 * the pure-function probes stay inert without touching the config system.</p>
 */
public final class DebugGate {
    private DebugGate() {
    }

    /** Debug subsystems, each independently switchable. */
    public enum DebugModule {
        GROWTH,
        STEM,
        SIDE_EFFECT,
        CATCH_UP,
        DATA,
        PERF,
        RING,
        COMMANDS
    }

    /** Bit {@code 8} marks the global {@code debugLogging} master switch. */
    private static final int MASTER_BIT = 1 << 8;

    /**
     * Cached gate bitmask. Bits {@code 0..7} hold the module switches,
     * bit {@code 8} holds the master logging switch. Volatile so a config reload
     * on another thread becomes visible to the main-thread hot path.
     */
    private static volatile int gate;

    /**
     * Test whether a debug module is enabled: the global {@code debugLogging}
     * master switch must be on AND the module's own switch must be on.
     *
     * <p>This is the cheapest possible hot-path entry — one volatile read and
     * two bit tests.</p>
     */
    public static boolean enabled(DebugModule m) {
        int g = gate;
        return (g & MASTER_BIT) != 0 && (g & (1 << m.ordinal())) != 0;
    }

    /** Convenience accessors for call sites. */
    public static boolean growth() {
        return enabled(DebugModule.GROWTH);
    }

    public static boolean stem() {
        return enabled(DebugModule.STEM);
    }

    public static boolean sideEffect() {
        return enabled(DebugModule.SIDE_EFFECT);
    }

    public static boolean catchUp() {
        return enabled(DebugModule.CATCH_UP);
    }

    public static boolean data() {
        return enabled(DebugModule.DATA);
    }

    public static boolean perf() {
        return enabled(DebugModule.PERF);
    }

    public static boolean ring() {
        return enabled(DebugModule.RING);
    }

    public static boolean commands() {
        return enabled(DebugModule.COMMANDS);
    }

    /**
     * Re-read every debug switch from config and republish the cache bitmask.
     * Called from {@link CropGrowthConfig#refreshOverrides()}.
     */
    public static void refreshCache() {
        int g = 0;
        if (safeGet(CropGrowthConfig.DEBUG_LOGGING)) g |= MASTER_BIT;
        if (safeGet(CropGrowthConfig.DEBUG_GROWTH)) g |= bit(DebugModule.GROWTH);
        if (safeGet(CropGrowthConfig.DEBUG_STEM)) g |= bit(DebugModule.STEM);
        if (safeGet(CropGrowthConfig.DEBUG_SIDE_EFFECT)) g |= bit(DebugModule.SIDE_EFFECT);
        if (safeGet(CropGrowthConfig.DEBUG_CATCH_UP)) g |= bit(DebugModule.CATCH_UP);
        if (safeGet(CropGrowthConfig.DEBUG_DATA)) g |= bit(DebugModule.DATA);
        if (safeGet(CropGrowthConfig.DEBUG_PERF)) g |= bit(DebugModule.PERF);
        if (safeGet(CropGrowthConfig.DEBUG_RING)) g |= bit(DebugModule.RING);
        if (safeGet(CropGrowthConfig.DEBUG_COMMANDS)) g |= bit(DebugModule.COMMANDS);
        gate = g;
        // Ring buffer capacity may have changed — let it re-read lazily.
        DebugRingBuffer.refreshCapacity();
    }

    private static int bit(DebugModule m) {
        return 1 << m.ordinal();
    }

    /** Read a boolean config value defensively; {@code false} if not loaded. */
    private static boolean safeGet(ModConfigSpec.BooleanValue value) {
        try {
            return value.get();
        } catch (Exception ignored) {
            return false;
        }
    }
}
