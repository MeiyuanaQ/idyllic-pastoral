package com.crispyraccoon.pastoralcraft.crop;

import java.util.Map;
import java.util.Set;

import com.teamtea.eclipticseasons.api.constant.solar.Season;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Per-chunk crop entry CRUD: {@code getOrCreate}, {@code isTracked},
 * {@code removePosition}, {@code resetPlantedDay} and {@code placeAndTrack}.
 *
 * <p>Extracted from {@link CropGrowthTracker} (mechanical move, no logic change).
 * All internal writes go through {@link BlockWriter}.</p>
 */
public final class EntryStore {

    private EntryStore() {
        // Utility class — prevent instantiation.
    }

    public static CropProgressEntry getOrCreate(BlockPos pos, Level level, BlockState state) {
        return getOrCreate(pos, level, state, CropGrowthTracker.getSolarDays(level));
    }

    public static CropProgressEntry getOrCreate(BlockPos pos, Level level, BlockState state, int currentDay) {
        Block block = state.getBlock();
        if (!CropClassifier.isGrowableCrop(block)) return null;

        // Sugar cane: walk down to the bottom block and track only the root.
        // Depth-bounded to guard against pathological over-tall stalks.
        if (block instanceof SugarCaneBlock) {
            int depth = 0;
            while (level.getBlockState(pos.below()).getBlock() instanceof SugarCaneBlock && depth < 8) {
                pos = pos.below();
                depth++;
            }
            state = level.getBlockState(pos);
            block = state.getBlock();
        }

        // Kelp: walk down to the root block and track only the root.
        // Same bottom-only rule as sugar cane.
        if (CropClassifier.isKelp(block)) {
            int depth = 0;
            while (CropClassifier.isKelp(level.getBlockState(pos.below()).getBlock()) && depth < 8) {
                pos = pos.below();
                depth++;
            }
            state = level.getBlockState(pos);
            block = state.getBlock();
        }

        // REGROW two-block plants: only the product-bearing UPPER half is tracked.
        // The LOWER half carries the same state properties (including has_seeds)
        // but is not the product — skip it so only one entry exists per plant.
        // Single-block REGROW crops (no DOUBLE_BLOCK_HALF property) are tracked
        // normally, so only plants exposing the half property are filtered here.
        if (CropKindResolver.regrowOf(block) != null
                && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return null;
        }

        // Segmented water rice (KC rice_crop): only the DOWN (root) segment is
        // tracked. MIDDLE/UP segments mirror the DOWN segment's age and must not
        // get independent entries — skip them (mirrors the REGROW upper-half rule).
        if (CropClassifier.getRiceSegment(state) > 0) {
            return null;
        }

        // DOUBLE two-block crops (flax, pitcher_crop): only the LOWER half is
        // tracked. The UPPER half mirrors the LOWER age via placeDoubleUpperHalf
        // and must never get an independent entry — advancing it independently
        // corrupts the two-block structure (getCropStateForAge resets HALF to
        // LOWER, and a tracked UPPER reaching maxAge places a third UPPER block).
        if (CropClassifier.isDoubleCropUpperHalf(state)) {
            return null;
        }

        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();

        CropProgressEntry existing = cropData.get(pos);
        if (existing != null) return existing;

        int solarDay = currentDay;
        int currentAge = CropClassifier.getCropAge(state);
        // If the crop already has growth stages (e.g. world-gen farms),
        // back-calculate plantedDay so existing progress is preserved.
        if (currentAge > 0) {
            ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
            int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
            solarDay = PlantedDayMath.backCalculatedPlantedDay(currentDay, currentAge, daysPerStage);
        } else if (CropClassifier.isKelp(block)) {
            // HEIGHT crop: root-only tracking, getCropAge()==-1. If the stalk is
            // taller than 1 (world-gen / externally placed), back-calculate
            // plantedDay so the calendar target height aligns with actual height.
            int height = HeightStrategy.getKelpHeight(level, pos);
            if (height > 1) {
                int daysPerStage = CropGrowthConfig.getDaysPerStage(
                        BuiltInRegistries.BLOCK.getKey(Blocks.KELP));
                Set<Season> suitableSeasons = CropCalendar.resolveSuitableSeasons(CropGrowthTracker.getSeason(level), Blocks.KELP);
                solarDay = PlantedDayMath.heightCropPlantedDayAfterBonemeal(currentDay, height, CropClassifier.KELP_MAX_HEIGHT,
                        daysPerStage, suitableSeasons, CropGrowthTracker.getSeasonLength(level));
            }
        } else if (block instanceof SugarCaneBlock) {
            int height = HeightStrategy.getSugarCaneHeight(level, pos);
            if (height > 1) {
                int daysPerStage = CropGrowthConfig.getDaysPerStage(
                        BuiltInRegistries.BLOCK.getKey(Blocks.SUGAR_CANE));
                Set<Season> suitableSeasons = CropCalendar.resolveSuitableSeasons(CropGrowthTracker.getSeason(level), Blocks.SUGAR_CANE);
                solarDay = PlantedDayMath.heightCropPlantedDayAfterBonemeal(currentDay, height, 3,
                        daysPerStage, suitableSeasons, CropGrowthTracker.getSeasonLength(level));
            }
        }
        CropProgressEntry entry = new CropProgressEntry(solarDay);
        cropData.put(pos, entry);
        CropGrowthTracker.registerTrackedChunk(chunk);

        CropGrowthTracker.logDebug("Created new crop entry at {} dim={} plantedDay={}",
                pos, level.dimension().location(), solarDay);
        return entry;
    }

    /**
     * Whether the position currently has a tracked crop entry.
     * Used to avoid back-calculating plantedDay for an already-tracked crop:
     * bonemeal/rapid growth must not shift plantedDay backward.
     */
    public static boolean isTracked(Level level, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        return chunkData.pastoralcraft$getCropData().containsKey(pos);
    }

    /**
     * Remove a crop position from tracking (e.g. when broken or harvested).
     * If the chunk's crop data map becomes empty after removal, the chunk is
     * unregistered from the tracked set to prevent wasted iteration during
     * periodic catch-up checks.
     */
    public static void removePosition(BlockPos pos, Level level) {
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        cropData.remove(pos);
        CropGrowthTracker.logDebug("Removed crop tracking at {} dim={}", pos, level.dimension().location());

        // If this was the last crop in the chunk, unregister from tracked set
        // to avoid wasted iteration in periodic catch-up checks.
        if (cropData.isEmpty()) {
            CropGrowthTracker.unregisterTrackedChunk(chunk);
        }
    }

    /**
     * Reset the {@code plantedDay} of an existing tracked crop to the current
     * solar day. Used when a StemBlock reverts from AttachedStemBlock after
     * fruit harvest — the stem should start a fresh fruiting cycle from the
     * current day rather than continuing from the original plantedDay.
     *
     * <p>If no entry exists at the given position (e.g. a world-generated
     * sunflower harvested before it was ever tracked), a new entry is created
     * so the calendar can still drive its regrowth cycle.</p>
     */
    public static void resetPlantedDay(BlockPos pos, Level level) {
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        CropProgressEntry entry = cropData.get(pos);
        if (entry != null) {
            cropData.put(pos, new CropProgressEntry(CropGrowthTracker.getSolarDays(level)));
            CropGrowthTracker.logDebug(DebugGate.DebugModule.STEM, "Reset plantedDay for stem at {} dim={}", pos, level.dimension().location());
            return;
        }
        // No entry exists (e.g. a world-gen sunflower harvested before ever being
        // tracked): create one now so the calendar can drive the regrowth cycle.
        getOrCreate(pos, level, level.getBlockState(pos));
    }

    /**
     * Place a block state and (re)register it for calendar tracking if it is a
     * recognized growable crop. All internal {@code setBlock} calls are guarded
     * by {@link InternalGrowthFlag} so {@code LevelMixin} does not re-track the
     * placed block.
     */
    public static void placeAndTrack(Level level, BlockPos pos, BlockState state) {
        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            BlockWriter.internalSetBlock(level, pos, state, BlockWriter.FLAG_UPDATE_CLIENTS);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
        // Register the placed block for calendar tracking if it is a crop
        // (non-crops such as rice_panicles are ignored by getOrCreate).
        getOrCreate(pos, level, level.getBlockState(pos));
    }

    /**
     * Place a block state and register it for calendar tracking with an explicit
     * {@code plantedDay}. Companion crops (e.g. Farmers Delight rice_panicles)
     * share their base crop's plantedDay so their calendar phases stay in sync
     * instead of restarting from the current day on every placement.
     */
    public static void placeAndTrack(Level level, BlockPos pos, BlockState state, int plantedDay) {
        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            BlockWriter.internalSetBlock(level, pos, state, BlockWriter.FLAG_UPDATE_CLIENTS);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
        Block placed = level.getBlockState(pos).getBlock();
        if (!CropClassifier.isGrowableCrop(placed)) return;
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        int solarDay = PlantedDayMath.clampPlantedDay(plantedDay, CropGrowthTracker.getSolarDays(level));
        cropData.put(pos, new CropProgressEntry(solarDay));
        CropGrowthTracker.registerTrackedChunk(chunk);
        CropGrowthTracker.logDebug("Tracked placed crop at {} dim={} plantedDay={}",
                pos, level.dimension().location(), solarDay);
    }
}
