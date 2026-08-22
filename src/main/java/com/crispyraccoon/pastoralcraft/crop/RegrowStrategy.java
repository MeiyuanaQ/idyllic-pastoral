package com.crispyraccoon.pastoralcraft.crop;

import java.util.Set;

import com.teamtea.eclipticseasons.api.constant.solar.Season;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Boolean-product regrow (e.g. sunflower {@code has_seeds}). The product is
 * driven entirely by the calendar, never by random ticks.
 *
 * <p>Extracted from {@link CropGrowthTracker} (mechanical move, no logic change).
 */
public final class RegrowStrategy {

    private RegrowStrategy() {
        // Utility class — prevent instantiation.
    }

    /**
     * Process one tracked REGROW entry for one catch-up pass.
     *
     * @param ctx      the shared catch-up context
     * @param pos      the tracked position (product-bearing UPPER half)
     * @param state    the current block state
     * @param progress the tracked progress entry
     * @return the entry disposition (grew / remove)
     */
    static CatchUpContext.Outcome process(CatchUpContext ctx, BlockPos pos, BlockState state,
                                          CropProgressEntry progress) {
        Block block = state.getBlock();

        // Defensive: only the product-bearing UPPER half may be tracked.
        // A LOWER-half entry (legacy/corrupt data) has no product and
        // would never regrow — remove it.
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return CatchUpContext.Outcome.REMOVE;
        }

        BooleanProperty product = CropKindResolver.regrowOf(block).productProperty();
        // Product already present — fully regrown; keep the entry (re-harvestable).
        if (state.getValue(product)) {
            return CatchUpContext.Outcome.KEEP;
        }

        // Calendar-driven regrowth: only suitable-season days advance the
        // countdown. Non-arable (freeze, never mutate to short grass) so
        // unsuitable seasons halt regrowth.
        ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
        Set<Season> suitableSeasons = CropCalendar.resolveSuitableSeasons(ctx.currentSeason, block);

        CropSimulation.GrowthSimulation sim = CropSimulation.simulateGrowth(pos, progress.plantedDay, ctx.currentDay,
                daysPerStage, 1, ctx.seasonLength, suitableSeasons, true);

        if (sim.stage() >= 1) {
            BlockWriter.internalSetBlock(ctx.level, pos, state.setValue(product, true), BlockWriter.FLAG_UPDATE_CLIENTS);
            CropGrowthTracker.logDebug("Catch-up (load): {} at {} regrew seeds (plantedDay={}, currentDay={})",
                    cropId, pos, progress.plantedDay, ctx.currentDay);
            return CatchUpContext.Outcome.grown();
        }
        return CatchUpContext.Outcome.KEEP;
    }
}
