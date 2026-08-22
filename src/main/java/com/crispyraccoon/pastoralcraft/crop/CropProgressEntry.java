package com.crispyraccoon.pastoralcraft.crop;

/**
 * Tracks the growth progress of a single crop at a specific position.
 * Each crop position in the world gets one entry, managed by {@link CropGrowthTracker}.
 *
 * In the plantedDay scheme, the only persistent state is the solar day
 * when the crop was planted. All other state (growth stage, season transitions)
 * is derived from pure functions of plantedDay + currentDay + config.
 * This eliminates state consistency issues and automatically adapts to config changes.
 */
public class CropProgressEntry {
    /** The solar day when this crop was first detected (planted). */
    public final int plantedDay;

    public CropProgressEntry(int plantedDay) {
        this.plantedDay = plantedDay;
    }
}