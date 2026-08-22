package com.crispyraccoon.pastoralcraft.crop;

import com.crispyraccoon.pastoralcraft.PastoralCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Diagnostic tracing for {@code supplementaries:flax} state changes.
 *
 * <p>Plain static utility class (no ThreadLocal, no global maps) that follows
 * every block-state write touching a flax block (either half), the external
 * {@code growCropBy} call site, and a post-growth integrity snapshot, output to
 * debug.log via {@link PastoralCraft#LOGGER} at DEBUG level.</p>
 *
 * <p>All logging methods self-gate on
 * {@code debugLogging && debugFlaxAll && flax registered}, so they are safe to
 * call unconditionally; callers use {@link #isFlax(Block)} / {@link #enabled()}
 * as a cheap pre-filter to avoid the call entirely when disabled.</p>
 */
public final class FlaxDiagnostics {
    private FlaxDiagnostics() {
    }

    private static final ResourceLocation FLAX_ID = ResourceLocation.parse("supplementaries:flax");

    /** Lazily-resolved flax block; {@code null} when Supplementaries is absent. */
    private static volatile Block flax;
    private static volatile boolean resolved;

    /**
     * Resolve and cache the flax block once. Registry lookup happens exactly once
     * on first use; afterwards this is a volatile read + reference compare, which
     * is the only cost on the {@code LevelMixin} hot path when diagnostics are off.
     *
     * @return the flax block, or {@code null} when not registered
     */
    private static Block flaxBlock() {
        if (!resolved) {
            Block found = null;
            try {
                Block candidate = BuiltInRegistries.BLOCK.get(FLAX_ID);
                // Registry.get returns Blocks.AIR (the default value) on a miss.
                if (candidate != Blocks.AIR) {
                    found = candidate;
                }
            } catch (Exception ignored) {
                // Registry not ready or resolution failed — treat as absent.
            }
            flax = found;
            resolved = true;
        }
        return flax;
    }

    /** Cheap reference-equality probe for the hot path. */
    public static boolean isFlax(Block block) {
        return block != null && block == flaxBlock();
    }

    /**
     * Full gating chain: debug logging on, flax tracing on, flax registered.
     * When this is false, no flax diagnostic log is ever emitted and the
     * {@code LevelMixin} hook short-circuits before touching the block state.
     */
    public static boolean enabled() {
        return CropGrowthConfig.DEBUG_LOGGING.get()
                && CropGrowthConfig.DEBUG_FLAX_ALL.get()
                && flaxBlock() != null;
    }

    /**
     * Log a single flax {@code setBlock} state transition with its update flags
     * and writer classification (internal tracker vs external grower/player).
     */
    public static void logSetBlock(Level level, BlockPos pos, BlockState oldState, BlockState newState,
                                   int flags, String source) {
        if (!enabled()) return;
        PastoralCraft.LOGGER.debug("[FlaxDiag] setBlock src={} pos={} flags={} {} -> {}",
                source, pos, flags, oldState, newState);
    }

    /**
     * Log the current lower/upper half states plus an integrity self-check result.
     * The self-check covers: both halves present, lower/upper age sync, and HALF
     * property correctness — the exact failure modes behind flax shattering.
     */
    public static void logSnapshot(Level level, BlockPos lowerPos, String event) {
        if (!enabled()) return;
        BlockState lower = level.getBlockState(lowerPos);
        BlockState upper = level.getBlockState(lowerPos.above());
        PastoralCraft.LOGGER.debug("[FlaxDiag] snapshot event={} lower={} upper={} integrity={}",
                event, lower, upper, integrityOf(lower, upper));
    }

    /**
     * Log a semantic decision-context line (growth path, ages, plantedDay, etc.).
     * Emitted by the tracker/handler and the {@code FlaxBlockMixin} growCropBy
     * trace to add human-readable context around the raw setBlock lines.
     */
    public static void logDecision(String message, Object... args) {
        if (!enabled()) return;
        PastoralCraft.LOGGER.debug("[FlaxDiag] decision " + message, args);
    }

    /**
     * Read the AGE property of a flax state; -1 when flax is absent or the state
     * carries no AGE property (defensive).
     */
    public static int getAge(BlockState state) {
        Block flax = flaxBlock();
        if (flax instanceof CropBlock && state.hasProperty(CropBlock.AGE)) {
            return state.getValue(CropBlock.AGE);
        }
        return -1;
    }

    /** Maximum flax age; 0 when flax is absent (defensive). */
    public static int getMaxAge() {
        Block flax = flaxBlock();
        if (flax instanceof CropBlock crop) {
            return crop.getMaxAge();
        }
        return 0;
    }

    private static String integrityOf(BlockState lower, BlockState upper) {
        Block flax = flaxBlock();
        boolean lowerFlax = lower.getBlock() == flax;
        boolean upperFlax = upper.getBlock() == flax;
        if (!lowerFlax && !upperFlax) return "BROKEN";
        if (!lowerFlax) return "LOWER_MISSING";
        if (!upperFlax) return "UPPER_MISSING";

        int lowerAge = getAge(lower);
        int upperAge = getAge(upper);
        if (lowerAge >= 0 && upperAge >= 0 && lowerAge != upperAge) return "AGE_MISMATCH";

        boolean halvesOk = "LOWER".equals(halfOf(lower)) && "UPPER".equals(halfOf(upper));
        if (!halvesOk) return "HALF_WRONG";
        return "OK";
    }

    private static String halfOf(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            return half == DoubleBlockHalf.UPPER ? "UPPER" : "LOWER";
        }
        return "NONE";
    }
}
