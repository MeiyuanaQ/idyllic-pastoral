package com.crispyraccoon.pastoralcraft.mixin;

import com.crispyraccoon.pastoralcraft.crop.ChunkCropData;
import com.crispyraccoon.pastoralcraft.crop.CropProgressEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Mixin on {@link LevelChunk} to:
 * <ul>
 *   <li>Copy crop progress data from {@link ProtoChunk} to {@link LevelChunk}
 *       during chunk promotion (world generation)</li>
 * </ul>
 *
 * <p><b>NBT serialization</b> for persistence is handled separately via
 * {@link net.neoforged.neoforge.event.level.ChunkDataEvent} in
 * {@link com.crispyraccoon.pastoralcraft.event.CropGrowthHandler}.</p>
 *
 * <p><b>Crop placement/destruction tracking</b> is handled by
 * {@link com.crispyraccoon.pastoralcraft.mixin.LevelMixin#onSetBlock},
 * which injects into {@link net.minecraft.world.level.Level#setBlock} —
 * the single source of truth for all block changes.</p>
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    /**
     * Copy crop progress data from ProtoChunk when a LevelChunk is created from one.
     * This ensures that crop data set during world generation (on the ProtoChunk)
     * is transferred to the final LevelChunk.
     */
    @Inject(
            method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
            at = @At("RETURN")
    )
    private void onInitFromProto(ServerLevel level, ProtoChunk protoChunk,
                                  LevelChunk.PostLoadProcessor postLoadProcessor, CallbackInfo ci) {
        ChunkCropData protoData = (ChunkCropData) protoChunk;
        Map<BlockPos, CropProgressEntry> protoCropData = protoData.pastoralcraft$getCropData();
        if (!protoCropData.isEmpty()) {
            ChunkCropData self = (ChunkCropData) this;
            self.pastoralcraft$setCropData(new HashMap<>(protoCropData));
        }
    }
}