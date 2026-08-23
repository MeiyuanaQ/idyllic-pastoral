package com.crispyraccoon.pastoralcraft.crop;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;

import com.crispyraccoon.pastoralcraft.PastoralCraft;

/**
 * Data-driven maturity side effects (TRANSFORM / COMPANION / DOUBLE / BONEMEAL)
 * plus the DOUBLE upper-half placement and mutation-to-short-grass helpers.
 *
 * <p>Extracted from {@link CropGrowthTracker} (mechanical move, no logic change).
 * All internal writes go through {@link BlockWriter}; the RE-GROWTH guard is
 * preserved exactly.</p>
 */
public final class MaturitySideEffects {

    private MaturitySideEffects() {
        // Utility class — prevent instantiation.
    }

    /**
     * Side-effect decision for a crop that just reached maturity.
     *
     * <p>The enum mirrors the priority order in
     * {@link #applyMaturitySideEffects}: TRANSFORM → COMPANION → DOUBLE →
     * BONEMEAL fallback → NONE. The actual placement logic (which additionally
     * checks the world, e.g. whether the position above is air/water, or whether
     * {@code newAge >= doubleAge}) stays in the caller; this method only decides
     * <em>which</em> strategy applies, as a pure function of the override and the
     * block's capability. Package-private so unit tests can exercise the decision
     * without a Minecraft level.</p>
     */
    enum MaturitySideEffect {
        /** TRANSFORM — replace the crop block itself with {@code transformBlock}. */
        TRANSFORM,
        /** COMPANION — place {@code topBlock} above (water required when {@code waterCompanion}). */
        COMPANION,
        /** DOUBLE — place the upper half (HALF=UPPER) once {@code newAge >= doubleAge}. */
        DOUBLE,
        /** BONEMEAL — experimental fallback: a single native {@code performBonemeal}
         *  attempt; for fully grown vanilla crops this usually no-ops because
         *  {@code isValidBonemealTarget} returns false. */
        BONEMEAL,
        /** NONE — no side effect configured or applicable. */
        NONE
    }

    /**
     * Pure decision logic for {@link #applyMaturitySideEffects}.
     *
     * @param override    the crop override, or {@code null}
     * @param newAge      the new (mature) age
     * @param canBonemeal whether a {@code performBonemeal} fallback is possible
     *                    (level is a {@link ServerLevel} and the block is a
     *                    {@link BonemealableBlock})
     * @return the applicable side-effect strategy
     */
    static MaturitySideEffect decideSideEffect(StructureDescriptor descriptor,
                                               int newAge,
                                               boolean canBonemeal) {
        if (descriptor.transformBlock() != null) {
            return MaturitySideEffect.TRANSFORM;
        }
        if (descriptor.topBlock() != null) {
            return MaturitySideEffect.COMPANION;
        }
        if (descriptor.doubleAge() >= 0) {
            return MaturitySideEffect.DOUBLE;
        }
        return canBonemeal ? MaturitySideEffect.BONEMEAL : MaturitySideEffect.NONE;
    }

    public static boolean applyMaturitySideEffects(Level level, BlockPos pos, BlockState matureState, Block block, int newAge) {
        // Stems use deterministic fruiting; never apply side effects to them
        if (block instanceof StemBlock) return false;

        ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
        StructureDescriptor descriptor = CropStructureRegistry.resolve(block);
        boolean keepEntry = false;

        // Climb crops (Farmers Delight tomatoes) stay tracked past maturity so the
        // catch-up loops can keep driving tryClimbVine; they must never fall into the
        // BONEMEAL fallback (TomatoBlock.isValidBonemealTarget returns true for a mature
        // vine with rope above, which would trigger the native performBonemeal).
        boolean climbCrop = descriptor.hasClimb();
        boolean canBonemeal = level instanceof ServerLevel && block instanceof BonemealableBlock && !climbCrop;
        switch (decideSideEffect(descriptor, newAge, canBonemeal)) {
            case TRANSFORM -> {
                Block transform = BuiltInRegistries.BLOCK.get(descriptor.transformBlock());
                if (transform != Blocks.AIR) {
                    CropGrowthTracker.placeAndTrack(level, pos, transform.defaultBlockState());
                    // A transform may produce a new growable crop at the same position
                    // (e.g. Farmers Delight budding_tomatoes → tomatoes). Keep the
                    // entry so the caller does not delete the freshly re-registered
                    // tracking for the transformed crop, and restart its plantedDay so
                    // the new phase (fruiting/climbing) follows a fresh calendar rhythm.
                    keepEntry = CropClassifier.isGrowableCrop(transform);
                    if (keepEntry) {
                        CropGrowthTracker.resetPlantedDay(pos, level);
                    }
                    CropGrowthTracker.logDebug(DebugGate.DebugModule.SIDE_EFFECT, "Maturity side-effect: {} at {} transformed into {}", cropId, pos, descriptor.transformBlock());
                }
            }
            case COMPANION -> {
                BlockPos above = pos.above();
                boolean waterCompanion = descriptor.water();
                boolean aboveOk = waterCompanion
                        ? level.getFluidState(above).is(Fluids.WATER)
                        : level.getBlockState(above).isAir();
                if (aboveOk) {
                    Block companion = BuiltInRegistries.BLOCK.get(descriptor.topBlock());
                    if (companion != Blocks.AIR) {
                        // Companion crops (rice_panicles) share the base crop's
                        // plantedDay so their calendar phases stay in sync —
                        // otherwise they restart from the current day on every
                        // placement and never catch up to the base crop's rhythm.
                        int basePlantedDay = getPlantedDay(level, pos);
                        CropGrowthTracker.placeAndTrack(level, above, companion.defaultBlockState(), basePlantedDay);
                        CropGrowthTracker.logDebug(DebugGate.DebugModule.SIDE_EFFECT, "Maturity side-effect: {} at {} placed companion {} above", cropId, pos, descriptor.topBlock());
                    }
                }
                // Re-harvestable companion (e.g. Farmers Delight rice → rice_panicles):
                // keep the mature base crop tracked so the calendar re-places the
                // companion on the next cycle after it is harvested.
                keepEntry = true;
            }
            case DOUBLE -> placeDoubleUpperHalf(level, pos, matureState, block, newAge);
            case BONEMEAL -> {
                // Experimental fallback for crops without a configured override:
                // forward the mature state to the block's native performBonemeal.
                // In practice this usually no-ops for fully grown vanilla crops
                // (isValidBonemealTarget returns false), so treat it only as a
                // last-resort attempt rather than a guaranteed companion spawn.
                if (level instanceof ServerLevel serverLevel && block instanceof BonemealableBlock bonemealable) {
                    boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
                    if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
                    try {
                        // Fully grown crops report isValidBonemealTarget == false, so
                        // skip the native bonemeal path for them. The check itself is
                        // wrapped too: a corrupted/mixed-in age property must not
                        // crash the server tick loop (see torchflower crash report).
                        if (bonemealable.isValidBonemealTarget(serverLevel, pos, matureState)) {
                            bonemealable.performBonemeal(serverLevel, level.getRandom(), pos, matureState);
                        }
                    } catch (Exception e) {
                        // Defensive: a single crop's native bonemeal path must never
                        // crash the server tick loop. Log and move on.
                        PastoralCraft.LOGGER.warn("Maturity side-effect: bonemeal fallback failed for {} at {}: {}",
                                cropId, pos, e.toString());
                    } finally {
                        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
                    }
                    CropGrowthTracker.logDebug(DebugGate.DebugModule.SIDE_EFFECT, "Maturity side-effect: {} at {} attempted native performBonemeal fallback (may no-op for fully grown vanilla crops)", cropId, pos);
                }
            }
            case NONE -> { /* no side effect */ }
        }
        return keepEntry || climbCrop;
    }

    /**
     * Read the {@code plantedDay} of an existing tracked entry, falling back to
     * the current solar day when the position is not yet tracked.
     */
    private static int getPlantedDay(Level level, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        CropProgressEntry entry = chunkData.pastoralcraft$getCropData().get(pos);
        return entry != null ? entry.plantedDay : CropGrowthTracker.getSolarDays(level);
    }

    /**
     * Place or refresh the upper half of a two-block (DOUBLE) crop as soon as
     * {@code newAge} crosses {@code doubleAge}, not only at full maturity.
     *
     * <p>Placing the UPPER half <b>before</b> the LOWER half is required by
     * {@code FlaxBlock.updateShape}: a LOWER half whose slot above is not its
     * UPPER half breaks itself. All internal {@code setBlock} calls are guarded
     * by {@link InternalGrowthFlag}.</p>
     */
    public static void placeDoubleUpperHalf(Level level, BlockPos pos, BlockState lowerState, Block block, int newAge) {
        long t0 = DebugProfiler.startSection();
        StructureDescriptor descriptor = CropStructureRegistry.resolve(block);
        if (!descriptor.isDouble()) return;
        if (newAge < descriptor.doubleAge()) return;

        BlockPos above = pos.above();
        BlockState aboveState = level.getBlockState(above);
        // Only place/refresh when the slot above is free or already this crop's UPPER half.
        if (!aboveState.isAir()
                && !(aboveState.getBlock() == block
                     && aboveState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                     && aboveState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)) {
            return;
        }

        BlockState upperState = CropClassifier.getCropStateForAge(lowerState, newAge);
        if (upperState == null || !upperState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return;
        upperState = upperState.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);

        // Idempotency: when the upper half is already in the target state, skip
        // both the setBlock and the log line. Without this, maxAge triggers a
        // direct placement AND the DOUBLE side effect on the same cycle, logging
        // two "placed upper half" lines and writing the block twice.
        if (!CropClassifier.needsUpperHalfPlacement(aboveState, upperState)) return;

        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            BlockWriter.internalSetBlock(level, above, upperState, BlockWriter.FLAG_UPDATE_CLIENTS);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
        CropGrowthTracker.logDebug(DebugGate.DebugModule.SIDE_EFFECT, "Maturity side-effect: {} at {} placed upper half (age={})", BuiltInRegistries.BLOCK.getKey(block), pos, newAge);
        if (FlaxDiagnostics.enabled() && FlaxDiagnostics.isFlax(block)) {
            FlaxDiagnostics.logDecision("placeDoubleUpperHalf pos={} upper={} age={}", pos, upperState, newAge);
        }
        if (t0 != 0L) DebugProfiler.endSection(t0, "placeDoubleUpperHalf", "age=" + newAge, "pos=" + pos);
    }

    /**
     * Replace a mutated crop with short grass, cleaning up its upper half for
     * two-block crops (flax, pitcher_crop) so no floating upper block remains.
     * All internal setBlock calls are guarded by {@link InternalGrowthFlag} so
     * {@code LevelMixin} does not re-track the mutation.
     *
     * @param level the level
     * @param pos   the crop position (lower half)
     * @param block the crop block
     * @param flags block update flags ({@link BlockWriter#FLAG_UPDATE_CLIENTS} or
     *              {@link BlockWriter#FLAG_UPDATE_NEIGHBORS})
     */
    public static void mutateToShortGrass(Level level, BlockPos pos, Block block, int flags) {
        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            BlockWriter.internalSetBlock(level, pos, Blocks.SHORT_GRASS.defaultBlockState(), flags);
            // Two-block crops: clear the detached upper half before it floats.
            StructureDescriptor descriptor = CropStructureRegistry.resolve(block);
            if (descriptor.isDouble()) {
                BlockPos above = pos.above();
                BlockState aboveState = level.getBlockState(above);
                if (CropClassifier.isUpperHalfOf(aboveState, block)) {
                    BlockWriter.internalSetBlock(level, above, Blocks.AIR.defaultBlockState(), flags);
                }
            }
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
    }
}
