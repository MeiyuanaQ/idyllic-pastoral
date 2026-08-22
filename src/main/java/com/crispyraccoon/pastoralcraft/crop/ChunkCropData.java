package com.crispyraccoon.pastoralcraft.crop;

import net.minecraft.core.BlockPos;

import java.util.Map;

/**
 * Interface for accessing PastoralCraft crop progress data stored on chunks.
 * Implemented via Mixin on {@link net.minecraft.world.level.chunk.ChunkAccess}.
 *
 * <p>This replaces the global {@link java.util.concurrent.ConcurrentHashMap} in
 * {@link CropGrowthTracker} with per-chunk storage, which:
 * <ul>
 *   <li>Eliminates O(N) dimension scans in chunk unload/load</li>
 *   <li>Persists crop data with chunk saves (survives server restarts)</li>
 *   <li>Naturally cleans up when chunks are unloaded from memory</li>
 * </ul>
 *
 * <p>Inspired by Unloaded-Activity's {@code ChunkTimeData} pattern.</p>
 */
public interface ChunkCropData {

    /**
     * Get the crop progress data map for this chunk.
     * Maps BlockPos (within the chunk) to CropProgressEntry.
     */
    Map<BlockPos, CropProgressEntry> pastoralcraft$getCropData();

    /**
     * Set the crop progress data map for this chunk.
     * Used during ProtoChunk → LevelChunk data copy and NBT deserialization.
     */
    void pastoralcraft$setCropData(Map<BlockPos, CropProgressEntry> data);
}