package com.crispyraccoon.pastoralcraft.crop;

import java.util.Set;

import com.teamtea.eclipticseasons.api.constant.solar.Season;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Calendar-driven vine climbing (Farmers Delight tomato crops). Grows the vine
 * stack up its support one segment per suitable day, capped at
 * {@code maxClimbHeight}.
 *
 * <p>Extracted from {@link CropGrowthTracker} (mechanical move, no logic change).
 */
public final class ClimbStrategy {

    private ClimbStrategy() {
        // Utility class — prevent instantiation.
    }

    /**
     * Calendar-driven vine climbing. Grows the vine stack up its support (rope)
     * one segment per suitable day, capped at {@code maxClimbHeight}, matching the
     * growth rhythm of {@link CropSimulation#simulateGrowth}.
     *
     * <p>Called from the catch-up loops on every cycle (after maturity handling),
     * regardless of whether the age advanced — the number of suitable days between
     * {@code plantedDay} and {@code currentDay} deterministically determines the
     * target stack height, so catch-up is idempotent.</p>
     */
    public static void tryClimbVine(Level level, BlockPos pos, int plantedDay, int currentDay,
                                    Set<Season> suitableSeasons, int termLength) {
        if (termLength <= 0) return;
        Block block = level.getBlockState(pos).getBlock();
        StructureDescriptor descriptor = CropStructureRegistry.resolve(block);
        if (!descriptor.hasClimb()) return;
        if (!isSameClimbFamily(block, descriptor)) return;

        Block climb = BuiltInRegistries.BLOCK.get(descriptor.climbBlock());
        Block support = descriptor.climbSupport() != null ? BuiltInRegistries.BLOCK.get(descriptor.climbSupport()) : Blocks.AIR;
        if (climb == Blocks.AIR || support == Blocks.AIR) return;

        // Find the base of the climb-family stack (lowest consecutive family block).
        BlockPos base = pos;
        while (isClimbFamilyBlock(level.getBlockState(base.below()).getBlock(), descriptor)) {
            base = base.below();
        }
        // Count existing family segments above the base.
        int segments = 0;
        BlockPos probe = base.above();
        while (isClimbFamilyBlock(level.getBlockState(probe).getBlock(), descriptor) && segments < descriptor.maxClimbHeight()) {
            segments++;
            probe = probe.above();
        }
        long t0 = DebugProfiler.startSection();
        int suitableDays = CropCalendar.countSuitableDays(plantedDay, currentDay, suitableSeasons, termLength);
        int desired = Math.min(suitableDays, descriptor.maxClimbHeight());
        int toAdd = desired - segments;
        if (toAdd <= 0) {
            if (t0 != 0L) DebugProfiler.endSection(t0, "tryClimbVine", "climbed=false", "pos=" + pos);
            return;
        }

        BlockPos target = probe;
        for (int i = 0; i < toAdd; i++) {
            // FD native climbRopeAbove replaces the rope directly above the vine with
            // tomatoes_on_rope (no air gap), so the target block itself must be the
            // support (rope) and gets replaced in place.
            if (level.getBlockState(target).getBlock() != support) break;
            CropGrowthTracker.placeAndTrack(level, target, climb.defaultBlockState());
            target = target.above();
        }
        if (t0 != 0L) DebugProfiler.endSection(t0, "tryClimbVine", "climbed=true", "toAdd=" + toAdd, "pos=" + pos);
    }

    /**
     * Whether {@code block} belongs to the same climb family as the descriptor
     * (i.e. shares the same {@code climbBlock}). The base vine and any hanging
     * segments all carry the same climbBlock, so this identifies the whole stack.
     */
    private static boolean isSameClimbFamily(Block block, StructureDescriptor descriptor) {
        if (descriptor.climbBlock() == null) return false;
        StructureDescriptor other = CropStructureRegistry.resolve(block);
        return other.climbBlock() != null && descriptor.climbBlock().equals(other.climbBlock());
    }

    /**
     * Whether {@code block} is a climb-family block for the given descriptor.
     */
    private static boolean isClimbFamilyBlock(Block block, StructureDescriptor descriptor) {
        return isSameClimbFamily(block, descriptor);
    }
}
