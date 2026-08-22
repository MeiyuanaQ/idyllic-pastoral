package com.crispyraccoon.pastoralcraft.crop;

/**
 * Holds the ThreadLocal re-entrancy guard flag for internal growth operations.
 *
 * <p>This flag must NOT live in a Mixin class because Mixin 0.8.7+ in NeoForge
 * rejects non-private static fields in mixin classes. By keeping it in a plain
 * utility class, it can be shared between {@link CropGrowthTracker},
 * {@link com.crispyraccoon.pastoralcraft.event.CropGrowthHandler}, and
 * {@link com.crispyraccoon.pastoralcraft.mixin.LevelMixin} without triggering
 * the Mixin validator.</p>
 *
 * <p>When this flag is {@code true}, the LevelMixin's onSetBlock injection
 * skips processing to avoid duplicate recording or infinite recursion during
 * internal growth operations (catch-up, fruiting, sugar cane growth, weed mutation).</p>
 */
public final class InternalGrowthFlag {

    /** ThreadLocal flag to prevent re-entrant tracking during internal growth operations. */
    public static final ThreadLocal<Boolean> INTERNAL_GROWTH = ThreadLocal.withInitial(() -> false);

    private InternalGrowthFlag() {
        // Utility class — prevent instantiation
    }
}