package com.crispyraccoon.pastoralcraft.crop;

import java.util.Set;

import com.teamtea.eclipticseasons.api.constant.solar.Season;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * AGE (single-block age-driven) crops, including the segmented-rice sync, DOUBLE
 * upper-half refresh, maturity side effects and tomato vine climbing.
 *
 * <p>Extracted from {@link CropGrowthTracker} (mechanical move, no logic change).
 * All internal writes go through {@link BlockWriter}.</p>
 */
public final class AgeStrategy {

    private AgeStrategy() {
        // Utility class — prevent instantiation.
    }

    /**
     * Process one tracked AGE entry for one catch-up pass.
     *
     * @param ctx      the shared catch-up context
     * @param pos      the tracked position
     * @param state    the current block state
     * @param progress the tracked progress entry
     * @return the entry disposition (grew / remove)
     */
    static CatchUpContext.Outcome process(CatchUpContext ctx, BlockPos pos, BlockState state,
                                          CropProgressEntry progress) {
        Level level = ctx.level;
        Block block = state.getBlock();
        int currentAge = CropClassifier.getCropAge(state);
        int maxAge = CropClassifier.getCropMaxAge(block);

        // Segmented water rice: only the DOWN (root) segment is tracked. A
        // MIDDLE/UP entry (legacy/corrupt data) mirrors the DOWN age and
        // must be removed — it would otherwise advance independently.
        int riceSegment = CropClassifier.getRiceSegment(state);
        if (riceSegment > 0) {
            return CatchUpContext.Outcome.REMOVE;
        }
        IntegerProperty riceLocation = riceSegment == 0 ? CropClassifier.segmentedLocationProperty(block) : null;

        ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
        StructureDescriptor descriptor = CropStructureRegistry.resolve(block);

        // DOUBLE two-block crops (flax, pitcher_crop): an UPPER half entry is
        // legacy/corrupt data (pre-fix save) and must be removed — advancing
        // it independently corrupts the two-block structure. The LOWER half
        // entry refreshes the UPPER block via placeDoubleUpperHalf.
        if (CropClassifier.isDoubleCropUpperHalf(state)) {
            return CatchUpContext.Outcome.REMOVE;
        }

        if (currentAge >= maxAge) {
            // Segmented rice DOWN at max age still needs the upper segments
            // synced (e.g. after a bonemeal jump left them stale) before the
            // entry is dropped.
            if (riceLocation != null) {
                CropGrowthTracker.syncRiceSegments(level, pos, maxAge, riceLocation);
            }
            // Climb crops (Farmers Delight tomatoes) stay tracked past maturity
            // so tryClimbVine below keeps driving rope climbing; fall through
            // (simulateGrowth no-ops at maxAge for non-arable crops).
            if (!CropClassifier.isClimbCrop(block)) {
                // Override-bearing crops may need a maturity side effect even
                // when they arrive at maxAge without a tracker growth step
                // (bonemeal jump, chunk-load). Run it so TRANSFORM (budding →
                // tomatoes) and COMPANION (rice → panicles) fire instead of
                // silently dropping the entry.
                boolean hasSideEffect = descriptor.hasTransform() || descriptor.hasCompanion() || descriptor.isDouble();
                if (hasSideEffect && CropGrowthTracker.applyMaturitySideEffects(level, pos, state, block, maxAge)) {
                    // TRANSFORM/COMPANION re-registered a growable crop at this
                    // position; skip the stale-state growth path below and let
                    // the next cycle process the fresh entry.
                    return CatchUpContext.Outcome.KEEP;
                }
                return CatchUpContext.Outcome.REMOVE;
            }
        }

        int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
        Set<Season> suitableSeasons = CropCalendar.resolveSuitableSeasons(ctx.currentSeason, block);
        boolean nonArable = CropClassifier.isNonArableAt(level, pos, block);

        CropSimulation.GrowthSimulation sim = CropSimulation.simulateGrowth(pos, progress.plantedDay, ctx.currentDay,
                daysPerStage, maxAge, ctx.seasonLength, suitableSeasons, nonArable,
                CropGrowthConfig.getUnsuitableMutateChance(cropId),
                CropGrowthConfig.getUnsuitableGrowChance(cropId));

        if (sim.mutated()) {
            CropGrowthTracker.mutateToShortGrass(level, pos, block, BlockWriter.FLAG_UPDATE_CLIENTS);
            CropGrowthTracker.logDebug("Catch-up (load): {} at {} mutated to short grass (plantedDay={}, currentDay={})",
                    cropId, pos, progress.plantedDay, ctx.currentDay);
            return CatchUpContext.Outcome.REMOVE;
        }

        boolean grew = false;
        if (sim.stage() > currentAge) {
            int newAge = Math.min(sim.stage(), maxAge);
            // Segmented rice: preserve WATERLOGGED/LOCATION on the DOWN segment
            // (getStateForAge would reset both and de-waterlog the crop in water).
            BlockState newState = riceLocation != null
                    ? state.setValue(CropBlock.AGE, newAge)
                    : CropClassifier.getCropStateForAge(state, newAge);
            BlockWriter.internalSetBlock(level, pos, newState, BlockWriter.FLAG_UPDATE_CLIENTS);

            grew = true;
            CropGrowthTracker.logDebug("Catch-up (load): {} at {} advanced from age {} to {} (target={}, plantedDay={}, currentDay={})",
                    cropId, pos, currentAge, newAge, sim.stage(), progress.plantedDay, ctx.currentDay);

            // Segmented rice: sync MIDDLE/UP to the new age (the mod advances
            // the DOWN segment with UPDATE_CLIENTS, so updateShape never fires).
            if (riceLocation != null) {
                CropGrowthTracker.syncRiceSegments(level, pos, newAge, riceLocation);
            }

            // Double-crop (e.g. flax): place/refresh the upper half as soon as
            // the age crosses doubleAge, not only at full maturity.
            CropGrowthTracker.placeDoubleUpperHalf(level, pos, newState, block, newAge);

            if (FlaxDiagnostics.enabled() && FlaxDiagnostics.isFlax(block)) {
                FlaxDiagnostics.logDecision("chunk-load catch-up {}->{} pos={} target={} plantedDay={} currentDay={}",
                        currentAge, newAge, pos, sim.stage(), progress.plantedDay, ctx.currentDay);
                FlaxDiagnostics.logSnapshot(level, pos, "chunk-load catch-up post-growth");
            }

            if (newAge >= maxAge) {
                boolean keep = CropGrowthTracker.applyMaturitySideEffects(level, pos, newState, block, newAge);
                // TRANSFORM to a new growable crop (e.g. tomato_budding →
                // tomato_crop) keeps the entry: placeAndTrack already
                // re-registered it with a fresh plantedDay.
                if (!keep) {
                    return new CatchUpContext.Outcome(grew, true);
                }
            }
        }

        // Calendar-driven vine climbing (Farmers Delight tomato): grow one
        // rope segment per suitable day, every cycle, regardless of whether
        // the fruit age advanced this pass. Idempotent via countSuitableDays.
        //
        // Re-read the live entry: a TRANSFORM side effect (budding_tomatoes →
        // tomatoes) may have just replaced the tracked entry with a fresh
        // plantedDay (current day). Using the snapshot's stale plantedDay here
        // would make countSuitableDays count the whole budding phase as
        // suitable climb days and instantly place multiple rope segments.
        CropProgressEntry climbEntry = ctx.cropData.get(pos);
        int climbPlantedDay = climbEntry != null ? climbEntry.plantedDay : progress.plantedDay;
        CropGrowthTracker.tryClimbVine(level, pos, climbPlantedDay, ctx.currentDay, suitableSeasons,
                Math.max(1, ctx.seasonLength / CropGrowthConfig.SOLAR_TERMS_PER_SEASON));
        return new CatchUpContext.Outcome(grew, false);
    }
}
