package com.crispyraccoon.pastoralcraft.crop;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * Single query point for a crop block's {@link StructureDescriptor}.
 *
 * <p>Resolution merges two sources (per-field) and caches the result per block:</p>
 * <ol>
 *   <li>The user-facing config {@link CropGrowthConfig.CropOverride} — its
 *       structural keys ({@code doubleAge}/{@code topBlock}/{@code transformBlock}/
 *       {@code climbBlock}/{@code climbSupport}/{@code maxClimbHeight}) win over the
 *       data map, preserving the "user config beats built-in" contract
 *       (AGENTS.md §5.1-10).</li>
 *   <li>The {@link CropDataMaps#CROP_STRUCTURE} data map (built-in JSON + data packs),
 *       which is the P5 declaration channel.</li>
 * </ol>
 *
 * <p>Blocks with no structural declaration resolve to the shared {@link #EMPTY}
 * singleton. Query points that still need a <em>generic scan</em> (age property,
 * regrow property, or the segmented-rice {@code location} property) treat
 * {@link #EMPTY} as "no explicit declaration" and fall back to the scan — the
 * scan is downgraded to the default provider, never the primary detector.</p>
 */
public final class CropStructureRegistry {

    /** Shared sentinel for "no structural declaration" (all fields defaulted). */
    public static final StructureDescriptor EMPTY =
            new StructureDescriptor(-1, null, null, null, null, 0, null);

    private static final Map<Block, StructureDescriptor> CACHE = new ConcurrentHashMap<>();

    private CropStructureRegistry() {
        // Utility class — prevent instantiation.
    }

    /**
     * Resolve the merged structural descriptor for a block (cached, O(1) after
     * first resolve).
     *
     * @param block the crop block
     * @return the merged descriptor, or {@link #EMPTY} when none applies
     */
    public static StructureDescriptor resolve(Block block) {
        return CACHE.computeIfAbsent(block, CropStructureRegistry::compute);
    }

    /** Invalidate the cache (config reload / data-map reload). */
    public static void clearCache() {
        CACHE.clear();
    }

    @SuppressWarnings("deprecation")
    private static StructureDescriptor compute(Block block) {
        StructureDescriptor dataMap = block.builtInRegistryHolder().getData(CropDataMaps.CROP_STRUCTURE);
        CropGrowthConfig.CropOverride override =
                CropGrowthConfig.getOverride(BuiltInRegistries.BLOCK.getKey(block));

        int doubleAge = -1;
        ResourceLocation topBlock = null;
        ResourceLocation transformBlock = null;
        ResourceLocation climbBlock = null;
        ResourceLocation climbSupport = null;
        int maxClimbHeight = 0;
        String segmentProperty = null;

        // 1. Config override (user-facing) wins on a per-field basis.
        if (override != null) {
            if (override.doubleAge >= 0) doubleAge = override.doubleAge;
            if (override.topBlock != null) topBlock = override.topBlock;
            if (override.transformBlock != null) transformBlock = override.transformBlock;
            if (override.climbBlock != null) climbBlock = override.climbBlock;
            if (override.climbSupport != null) climbSupport = override.climbSupport;
            if (override.maxClimbHeight > 0) maxClimbHeight = override.maxClimbHeight;
        }
        // 2. Data map fills the remaining gaps (built-in + data pack declarations).
        if (dataMap != null) {
            if (doubleAge < 0) doubleAge = dataMap.doubleAge();
            if (topBlock == null) topBlock = dataMap.topBlock();
            if (transformBlock == null) transformBlock = dataMap.transformBlock();
            if (climbBlock == null) climbBlock = dataMap.climbBlock();
            if (climbSupport == null) climbSupport = dataMap.climbSupport();
            if (maxClimbHeight <= 0) maxClimbHeight = dataMap.maxClimbHeight();
            if (segmentProperty == null) segmentProperty = dataMap.segmentProperty();
        }

        if (doubleAge < 0 && topBlock == null && transformBlock == null && climbBlock == null
                && maxClimbHeight <= 0 && segmentProperty == null) {
            return EMPTY;
        }
        return new StructureDescriptor(doubleAge, topBlock, transformBlock,
                climbBlock, climbSupport, maxClimbHeight, segmentProperty);
    }
}
