package com.crispyraccoon.pastoralcraft.crop;

import java.util.Map;
import java.util.Set;

import com.teamtea.eclipticseasons.api.constant.solar.Season;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;

/**
 * Height-based crop growth (sugar cane / kelp). Only the root block is tracked;
 * growth places new blocks on top up to the max height.
 *
 * <p>Extracted from {@link CropGrowthTracker} (mechanical move, no logic change).
 * All internal writes go through {@link BlockWriter}.</p>
 */
public final class HeightStrategy {

    private HeightStrategy() {
        // Utility class — prevent instantiation.
    }

    /**
     * Count the height of a sugar cane stalk at the given position.
     * Counts upward from the given position (which should be the bottom block)
     * and includes all consecutive sugar cane blocks.
     *
     * @param level the world level
     * @param pos   the bottom sugar cane block position
     * @return the stalk height (1-3), or 0 if the block is not sugar cane
     */
    static int getSugarCaneHeight(Level level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof SugarCaneBlock)) return 0;
        int height = 0;
        BlockPos checkPos = pos;
        while (level.getBlockState(checkPos).getBlock() instanceof SugarCaneBlock && height < 5) {
            height++;
            checkPos = checkPos.above();
        }
        return height;
    }

    /**
     * Grow a sugar cane stalk by placing a new block on top.
     * Only grows if the current height is less than the maximum (3).
     *
     * @param level the world level
     * @param pos   the bottom sugar cane block position
     * @param currentHeight the current stalk height
     * @return true if sugar cane was grown (a new block was placed)
     */
    static boolean growSugarCane(Level level, BlockPos pos, int currentHeight) {
        if (currentHeight >= 3) return false;
        BlockPos topPos = pos.above(currentHeight);
        if (level.getBlockState(topPos).isAir()) {
            BlockWriter.internalSetBlock(level, topPos, Blocks.SUGAR_CANE.defaultBlockState(), BlockWriter.FLAG_UPDATE_CLIENTS);
            CropGrowthTracker.logDebug("Sugar cane grew: bottom={} new height={}", pos, currentHeight + 1);
            return true;
        }
        return false;
    }

    /**
     * Handle sugar cane harvest: a non-bottom sugar cane block at
     * {@code harvestedPos} is being removed (e.g. the player broke the second
     * or third block of the stalk).
     *
     * <p>The blocks below {@code harvestedPos} survive and should continue
     * growing from their current height. The root block's
     * {@link CropProgressEntry#plantedDay} is therefore back-calculated to
     * preserve the progress already accumulated in the surviving blocks:</p>
     *
     * <pre>
     * plantedDay = currentDay - (remainingHeight - 1) * daysPerStage
     * </pre>
     *
     * <p>The entry is created if missing, which also heals legacy data where
     * the entry was removed when the stalk previously reached max height.</p>
     */
    public static void onSugarCaneHarvest(Level level, BlockPos harvestedPos) {
        // Walk down to the root (bottom) block, bounded to guard against
        // pathological over-tall stalks from other mods or data corruption.
        BlockPos root = harvestedPos;
        int depth = 0;
        while (level.getBlockState(root.below()).getBlock() instanceof SugarCaneBlock && depth < 8) {
            root = root.below();
            depth++;
        }

        int currentDay = CropGrowthTracker.getSolarDays(level);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(
                BuiltInRegistries.BLOCK.getKey(Blocks.SUGAR_CANE));

        // Everything below the harvested block survives the harvest.
        int remainingHeight = Math.max(1, harvestedPos.getY() - root.getY());

        // Back-calculate plantedDay so the surviving height maps to the
        // progress already accumulated (1 block = 0 stages, 2 blocks = 1 stage).
        int plantedDay = currentDay - (remainingHeight - 1) * daysPerStage;
        // Defensive clamp: an invalid (negative) daysPerStage must never push
        // plantedDay into the future, which would freeze growth forever.
        if (plantedDay > currentDay) {
            plantedDay = currentDay;
        }

        LevelChunk chunk = level.getChunkAt(root);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        cropData.put(root, new CropProgressEntry(plantedDay));
        CropGrowthTracker.registerTrackedChunk(chunk);

        CropGrowthTracker.logDebug("Sugar cane harvested: harvested={} root={} remainingHeight={} plantedDay={}",
                harvestedPos, root, remainingHeight, plantedDay);
    }

    /**
     * Account for a sugar cane stalk being accelerated by bonemeal (or an
     * external mod placing sugar cane above the stalk): one sugar cane block was
     * placed above the head while the root plantedDay stayed fixed. Back-shift the
     * root plantedDay by the accelerated stages so the calendar target height stays
     * aligned; only a backward (earlier) shift is ever applied.
     */
    public static void onSugarCaneBonemeal(Level level, BlockPos headPos) {
        // Walk down to the root (bottom) block, bounded like onSugarCaneHarvest.
        BlockPos root = headPos;
        int depth = 0;
        while (level.getBlockState(root.below()).getBlock() instanceof SugarCaneBlock && depth < 8) {
            root = root.below();
            depth++;
        }

        int currentDay = CropGrowthTracker.getSolarDays(level);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(
                BuiltInRegistries.BLOCK.getKey(Blocks.SUGAR_CANE));
        Set<Season> suitableSeasons = CropCalendar.resolveSuitableSeasons(CropGrowthTracker.getSeason(level), Blocks.SUGAR_CANE);
        int seasonLength = CropGrowthTracker.getSeasonLength(level);
        int newHeight = headPos.getY() - root.getY() + 1;

        int plantedDay = PlantedDayMath.heightCropPlantedDayAfterBonemeal(currentDay, newHeight,
                3, daysPerStage, suitableSeasons, seasonLength);

        LevelChunk chunk = level.getChunkAt(root);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        CropProgressEntry entry = cropData.get(root);
        // Conservative: only allow shifting backward (earlier), never forward.
        if (entry != null && plantedDay >= entry.plantedDay) return;
        // NOTE: entry==null is intentionally created here (unlike round5
        // onCropBonemeal's early-out): a world-gen / externally-placed stalk may
        // never have been tracked, and catch-up needs an entry to stay aligned.
        cropData.put(root, new CropProgressEntry(plantedDay));
        CropGrowthTracker.registerTrackedChunk(chunk);
        if (entry != null) {
            CropGrowthTracker.logDebug("Sugar cane bonemeal: root={} newHeight={} plantedDay={} (was {})",
                    root, newHeight, plantedDay, entry.plantedDay);
        } else {
            CropGrowthTracker.logDebug("Sugar cane bonemeal (new track): root={} newHeight={} plantedDay={}",
                    root, newHeight, plantedDay);
        }
    }

    // =======================================================================
    // Cactus — deterministic height-based growth (vanilla cactus)
    // =======================================================================

    /**
     * Count the height of a cactus stalk at the given position.
     * Counts upward from the given position (which should be the bottom block)
     * and includes all consecutive cactus blocks.
     *
     * @param level the world level
     * @param pos   the bottom cactus block position
     * @return the stalk height (1-3), or 0 if the block is not cactus
     */
    static int getCactusHeight(Level level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof CactusBlock)) return 0;
        int height = 0;
        BlockPos checkPos = pos;
        while (level.getBlockState(checkPos).getBlock() instanceof CactusBlock && height < 5) {
            height++;
            checkPos = checkPos.above();
        }
        return height;
    }

    /**
     * Grow a cactus stalk by placing a new block on top.
     * Only grows if the current height is less than the maximum (3).
     *
     * @param level the world level
     * @param pos   the bottom cactus block position
     * @param currentHeight the current stalk height
     * @return true if cactus was grown (a new block was placed)
     */
    static boolean growCactus(Level level, BlockPos pos, int currentHeight) {
        if (currentHeight >= CropKindResolver.CACTUS_MAX_HEIGHT) return false;
        BlockPos topPos = pos.above(currentHeight);
        if (level.getBlockState(topPos).isAir()) {
            BlockWriter.internalSetBlock(level, topPos, Blocks.CACTUS.defaultBlockState(), BlockWriter.FLAG_UPDATE_CLIENTS);
            CropGrowthTracker.logDebug("Cactus grew: bottom={} new height={}", pos, currentHeight + 1);
            return true;
        }
        return false;
    }

    /**
     * Handle cactus harvest: a non-bottom cactus block at {@code harvestedPos}
     * is being removed (e.g. the player broke the second or third block of the
     * stalk). Mirrors {@link #onSugarCaneHarvest}: the root's
     * {@link CropProgressEntry#plantedDay} is back-calculated so the surviving
     * blocks keep their accumulated height progress.
     */
    public static void onCactusHarvest(Level level, BlockPos harvestedPos) {
        BlockPos root = harvestedPos;
        int depth = 0;
        while (level.getBlockState(root.below()).getBlock() instanceof CactusBlock && depth < 8) {
            root = root.below();
            depth++;
        }

        int currentDay = CropGrowthTracker.getSolarDays(level);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(
                BuiltInRegistries.BLOCK.getKey(Blocks.CACTUS));

        int remainingHeight = Math.max(1, harvestedPos.getY() - root.getY());

        int plantedDay = currentDay - (remainingHeight - 1) * daysPerStage;
        if (plantedDay > currentDay) {
            plantedDay = currentDay;
        }

        LevelChunk chunk = level.getChunkAt(root);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        cropData.put(root, new CropProgressEntry(plantedDay));
        CropGrowthTracker.registerTrackedChunk(chunk);

        CropGrowthTracker.logDebug("Cactus harvested: harvested={} root={} remainingHeight={} plantedDay={}",
                harvestedPos, root, remainingHeight, plantedDay);
    }

    /**
     * Account for a cactus stalk being accelerated by an external mod placing a
     * cactus block above the head. Mirrors {@link #onSugarCaneBonemeal}: back-shift
     * the root plantedDay by the accelerated stages (only backward shifts apply).
     */
    public static void onCactusBonemeal(Level level, BlockPos headPos) {
        BlockPos root = headPos;
        int depth = 0;
        while (level.getBlockState(root.below()).getBlock() instanceof CactusBlock && depth < 8) {
            root = root.below();
            depth++;
        }

        int currentDay = CropGrowthTracker.getSolarDays(level);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(
                BuiltInRegistries.BLOCK.getKey(Blocks.CACTUS));
        Set<Season> suitableSeasons = CropCalendar.resolveSuitableSeasons(CropGrowthTracker.getSeason(level), Blocks.CACTUS);
        int seasonLength = CropGrowthTracker.getSeasonLength(level);
        int newHeight = headPos.getY() - root.getY() + 1;

        int plantedDay = PlantedDayMath.heightCropPlantedDayAfterBonemeal(currentDay, newHeight,
                CropKindResolver.CACTUS_MAX_HEIGHT, daysPerStage, suitableSeasons, seasonLength);

        LevelChunk chunk = level.getChunkAt(root);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        CropProgressEntry entry = cropData.get(root);
        if (entry != null && plantedDay >= entry.plantedDay) return;
        cropData.put(root, new CropProgressEntry(plantedDay));
        CropGrowthTracker.registerTrackedChunk(chunk);
        if (entry != null) {
            CropGrowthTracker.logDebug("Cactus bonemeal: root={} newHeight={} plantedDay={} (was {})",
                    root, newHeight, plantedDay, entry.plantedDay);
        } else {
            CropGrowthTracker.logDebug("Cactus bonemeal (new track): root={} newHeight={} plantedDay={}",
                    root, newHeight, plantedDay);
        }
    }

    /**
     * Count the height of a kelp stalk at the given position.
     * Counts upward from the given position (which should be the root block)
     * and includes all consecutive kelp blocks.
     *
     * @param level the world level
     * @param pos   the root kelp block position
     * @return the stalk height (1-26), or 0 if the block is not kelp
     */
    static int getKelpHeight(Level level, BlockPos pos) {
        if (!CropClassifier.isKelp(level.getBlockState(pos).getBlock())) return 0;
        int height = 0;
        BlockPos checkPos = pos;
        while (CropClassifier.isKelp(level.getBlockState(checkPos).getBlock()) && height < CropClassifier.KELP_MAX_HEIGHT + 1) {
            height++;
            checkPos = checkPos.above();
        }
        return height;
    }

    /**
     * Grow a kelp stalk by placing a new head block on top.
     * Only grows if the target position is water — kelp never grows above the
     * surface (matching vanilla {@code GrowingPlantHeadBlock} behavior).
     *
     * <p>The current top head is converted into a stem and a fresh head is
     * placed above it, matching vanilla {@code getBodyBlock}/{@code getHeadBlock}
     * semantics.</p>
     *
     * @param level         the world level
     * @param pos           the root kelp block position
     * @param currentHeight the current stalk height
     * @return true if kelp was grown (a new block was placed)
     */
    static boolean growKelp(Level level, BlockPos pos, int currentHeight) {
        if (currentHeight >= CropClassifier.KELP_MAX_HEIGHT) return false;
        BlockPos targetPos = pos.above(currentHeight);
        // Water precondition: kelp never grows above the water surface.
        if (!level.getFluidState(targetPos).is(Fluids.WATER)) return false;
        if (currentHeight > 0) {
            // Convert the current top head into a stem block.
            BlockPos oldHeadPos = pos.above(currentHeight - 1);
            BlockWriter.internalSetBlock(level, oldHeadPos, Blocks.KELP_PLANT.defaultBlockState(), BlockWriter.FLAG_UPDATE_CLIENTS);
        }
        BlockWriter.internalSetBlock(level, targetPos, Blocks.KELP.defaultBlockState(), BlockWriter.FLAG_UPDATE_CLIENTS);
        CropGrowthTracker.logDebug("Kelp grew: bottom={} new height={}", pos, currentHeight + 1);
        return true;
    }

    /**
     * Handle kelp harvest: a non-root kelp block at {@code harvestedPos} is being
     * removed (e.g. the player broke the second block of the stalk).
     *
     * <p>The blocks below {@code harvestedPos} survive and should continue
     * growing from their current height. The root block's
     * {@link CropProgressEntry#plantedDay} is therefore back-calculated to
     * preserve the progress already accumulated in the surviving blocks,
     * mirroring {@link #onSugarCaneHarvest}.</p>
     */
    public static void onKelpHarvest(Level level, BlockPos harvestedPos) {
        // Walk down to the root (bottom) block, bounded to guard against
        // pathological over-tall stalks from other mods or data corruption.
        BlockPos root = harvestedPos;
        int depth = 0;
        while (CropClassifier.isKelp(level.getBlockState(root.below()).getBlock()) && depth < 8) {
            root = root.below();
            depth++;
        }

        int currentDay = CropGrowthTracker.getSolarDays(level);
        // Anchor to the kelp config regardless of whether the root is currently
        // a head (KelpBlock) or stem (KelpPlantBlock).
        int daysPerStage = CropGrowthConfig.getDaysPerStage(
                BuiltInRegistries.BLOCK.getKey(Blocks.KELP));

        // Everything below the harvested block survives the harvest.
        int remainingHeight = Math.max(1, harvestedPos.getY() - root.getY());

        // Back-calculate plantedDay so the surviving height maps to the
        // progress already accumulated (1 block = 0 stages, 2 blocks = 1 stage).
        int plantedDay = currentDay - (remainingHeight - 1) * daysPerStage;
        // Defensive clamp: an invalid (negative) daysPerStage must never push
        // plantedDay into the future, which would freeze growth forever.
        if (plantedDay > currentDay) {
            plantedDay = currentDay;
        }

        LevelChunk chunk = level.getChunkAt(root);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        cropData.put(root, new CropProgressEntry(plantedDay));
        CropGrowthTracker.registerTrackedChunk(chunk);

        CropGrowthTracker.logDebug("Kelp harvested: harvested={} root={} remainingHeight={} plantedDay={}",
                harvestedPos, root, remainingHeight, plantedDay);
    }

    /**
     * Account for a kelp stalk being accelerated by bonemeal: one KelpBlock was
     * placed above the head while the root plantedDay stayed fixed. Back-shift
     * the root plantedDay by the accelerated stages so the calendar target height
     * stays aligned; only a backward (earlier) shift is ever applied.
     */
    public static void onKelpBonemeal(Level level, BlockPos headPos) {
        // Walk down to the root (bottom) block, bounded like onKelpHarvest.
        BlockPos root = headPos;
        int depth = 0;
        while (CropClassifier.isKelp(level.getBlockState(root.below()).getBlock()) && depth < 8) {
            root = root.below();
            depth++;
        }

        int currentDay = CropGrowthTracker.getSolarDays(level);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(
                BuiltInRegistries.BLOCK.getKey(Blocks.KELP));
        Set<Season> suitableSeasons = CropCalendar.resolveSuitableSeasons(CropGrowthTracker.getSeason(level), Blocks.KELP);
        int seasonLength = CropGrowthTracker.getSeasonLength(level);
        int newHeight = headPos.getY() - root.getY() + 1;

        int plantedDay = PlantedDayMath.heightCropPlantedDayAfterBonemeal(currentDay, newHeight,
                CropClassifier.KELP_MAX_HEIGHT, daysPerStage, suitableSeasons, seasonLength);

        LevelChunk chunk = level.getChunkAt(root);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        CropProgressEntry entry = cropData.get(root);
        // Conservative: only allow shifting backward (earlier), never forward.
        if (entry != null && plantedDay >= entry.plantedDay) return;
        // NOTE: entry==null is intentionally created here (unlike round5
        // onCropBonemeal's early-out): a world-gen / externally-placed stalk may
        // never have been tracked, and catch-up needs an entry to stay aligned.
        cropData.put(root, new CropProgressEntry(plantedDay));
        CropGrowthTracker.registerTrackedChunk(chunk);
        if (entry != null) {
            CropGrowthTracker.logDebug("Kelp bonemeal: root={} newHeight={} plantedDay={} (was {})",
                    root, newHeight, plantedDay, entry.plantedDay);
        } else {
            CropGrowthTracker.logDebug("Kelp bonemeal (new track): root={} newHeight={} plantedDay={}",
                    root, newHeight, plantedDay);
        }
    }
}
