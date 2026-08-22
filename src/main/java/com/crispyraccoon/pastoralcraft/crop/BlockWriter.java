package com.crispyraccoon.pastoralcraft.crop;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Single funnel for internal block writes.
 *
 * <p><b>Contract (red lines):</b></p>
 * <ul>
 *   <li>Every internal {@code setBlock} is guarded by {@link InternalGrowthFlag}
 *       (save/restore, never unconditional clear) so {@code LevelMixin} does not
 *       re-track the write. The ThreadLocal lives only in
 *       {@link InternalGrowthFlag}, never in a Mixin.</li>
 *   <li>Flag semantics are named here instead of scattered literals:
 *       {@link #FLAG_UPDATE_CLIENTS} (growth stage changes) and
 *       {@link #FLAG_UPDATE_NEIGHBORS} (fruit placement / mutation).</li>
 *   <li>Cross-chunk reads/writes are <b>forbidden</b> from the chunk-load path.
 *       The only cross-chunk writer ({@code tryPlaceStemFruit}) must only run when
 *       {@code !duringChunkLoad}; callers gate it explicitly.</li>
 * </ul>
 *
 * <p>{@code UPDATE_KNOWN_SHAPE} (16) is appended <em>only</em> by
 * {@code LevelMixin} for internal writes — this helper must not add it, to avoid
 * double-applying the flag.</p>
 */
public final class BlockWriter {

    private BlockWriter() {
        // Utility class — prevent instantiation.
    }

    /** UPDATE_CLIENTS only (2) — default for growth stage changes; no neighbor updates. */
    public static final int FLAG_UPDATE_CLIENTS = Block.UPDATE_CLIENTS;

    /** UPDATE_CLIENTS | UPDATE_NEIGHBORS (3) — fruit placement / mutation. */
    public static final int FLAG_UPDATE_NEIGHBORS = Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS;

    /**
     * Perform an internal {@code setBlock} guarded by {@link InternalGrowthFlag}.
     *
     * @param level       the level to write to
     * @param pos         the position to write at
     * @param state       the state to place
     * @param updateFlags block update flags ({@link #FLAG_UPDATE_CLIENTS} or
     *                    {@link #FLAG_UPDATE_NEIGHBORS})
     * @return the result of the underlying {@code level.setBlock}
     */
    public static boolean internalSetBlock(Level level, BlockPos pos, BlockState state, int updateFlags) {
        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            return level.setBlock(pos, state, updateFlags);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
    }
}
