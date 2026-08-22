package com.crispyraccoon.pastoralcraft.mixin;

import com.crispyraccoon.pastoralcraft.crop.ChunkCropData;
import com.crispyraccoon.pastoralcraft.crop.CropProgressEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashMap;
import java.util.Map;

/**
 * Mixin on {@link ChunkAccess} to inject per-chunk crop progress data storage.
 *
 * <p>This replaces the global {@code Map<Dimension, Map<BlockPos, Entry>>} in
 * {@link com.crispyraccoon.pastoralcraft.crop.CropGrowthTracker} with data
 * stored directly on each chunk object. Benefits:
 * <ul>
 *   <li><b>Performance:</b> No more O(N) dimension scans on chunk unload/load</li>
 *   <li><b>Persistence:</b> Data saves/loads with the chunk via NBT</li>
 *   <li><b>Memory:</b> Data naturally cleans up when chunks are unloaded</li>
 * </ul>
 *
 * <p>Inspired by the Unloaded-Activity mod's {@code ChunkTimeData} pattern.</p>
 */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin implements ChunkCropData {

    /**
     * Per-chunk map of crop positions to their growth progress entries.
     * Uses HashMap (not ConcurrentHashMap) because chunk operations are
     * single-threaded per chunk in Minecraft's chunk system.
     */
    @Unique
    private Map<BlockPos, CropProgressEntry> pastoralcraft$cropData = new HashMap<>();

    @Override
    public Map<BlockPos, CropProgressEntry> pastoralcraft$getCropData() {
        return this.pastoralcraft$cropData;
    }

    @Override
    public void pastoralcraft$setCropData(Map<BlockPos, CropProgressEntry> data) {
        this.pastoralcraft$cropData = data;
    }
}