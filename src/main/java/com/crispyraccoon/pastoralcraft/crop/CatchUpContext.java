package com.crispyraccoon.pastoralcraft.crop;

import java.util.Map;

import com.teamtea.eclipticseasons.api.constant.solar.Season;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Shared per-entry context passed to the per-kind catch-up strategies. Bundles
 * the level, the chunk's live crop-data map, and the pre-computed day/season so
 * a strategy can simulate and settle a single tracked entry without re-resolving
 * the calendar.
 */
final class CatchUpContext {

    /** Outcome of processing one entry: whether it grew and whether its tracking entry must be removed. */
    record Outcome(boolean grew, boolean remove) {
        static final Outcome KEEP = new Outcome(false, false);
        static final Outcome REMOVE = new Outcome(false, true);
        static Outcome grown() {
            return new Outcome(true, false);
        }
    }

    final Level level;
    final Map<BlockPos, CropProgressEntry> cropData;
    final int currentDay;
    final Season currentSeason;
    final int seasonLength;
    final boolean duringChunkLoad;

    CatchUpContext(Level level, Map<BlockPos, CropProgressEntry> cropData, int currentDay,
                   Season currentSeason, int seasonLength, boolean duringChunkLoad) {
        this.level = level;
        this.cropData = cropData;
        this.currentDay = currentDay;
        this.currentSeason = currentSeason;
        this.seasonLength = seasonLength;
        this.duringChunkLoad = duringChunkLoad;
    }
}
