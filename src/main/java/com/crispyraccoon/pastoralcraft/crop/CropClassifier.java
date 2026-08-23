package com.crispyraccoon.pastoralcraft.crop;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Unified crop classification: growable-crop detection, freeze (non-arable)
 * classification, segmented-rice structure detection, age/state accessors and
 * climb/double-crop shape helpers. All read-only (no setBlock); caches are
 * block-level and stable until a config reload.
 *
 * <p>Extracted from {@link CropGrowthTracker} (mechanical move, no logic change).
 */
public final class CropClassifier {

    private CropClassifier() {
        // Utility class — prevent instantiation.
    }

    // Block-level non-arable cache: blocks do not change identity, so the
    // freeze/water-crop classification is stable until a config reload. Keeps
    // the hot isNonArableBlock path off the built-in registry (no repeated
    // ResourceLocation lookups per growth attempt).
    private static final Map<Block, Boolean> FREEZE_CACHE = new ConcurrentHashMap<>();

    /** Invalidate {@link #FREEZE_CACHE} (called on config override reload). */
    public static void clearFreezeCache() {
        FREEZE_CACHE.clear();
    }

    /**
     * Classify whether a crop is a "non-arable" crop that freezes (rather than
     * degrading) during unsuitable seasons. Sugar cane, nether wart, cocoa and
     * any {@link LiquidBlockContainer} (water crops such as kelp, Farmers
     * Delight rice, Kaleidoscope rice) simply stop growing in unsuitable
     * seasons; all other supported crops (field crops, berry bushes, stems)
     * continue attempting growth with the three-way unsuitable-season outcome.
     *
     * @param block the crop block to classify
     * @return true if the crop freezes during unsuitable seasons
     */
    public static boolean isNonArableBlock(Block block) {
        Boolean cached = FREEZE_CACHE.get(block);
        if (cached != null) return cached;
        boolean nonArable = block instanceof SugarCaneBlock
                || block instanceof CactusBlock
                || block instanceof NetherWartBlock
                || block instanceof CocoaBlock
                || block instanceof LiquidBlockContainer
                // KaleidoscopeCookery rice_crop implements SimpleWaterloggedBlock
                // (a DIFFERENT interface from LiquidBlockContainer), so it would
                // otherwise be classified arable and mutate to short grass in water.
                // Detect it structurally and freeze it like the other water crops.
                || isSegmentedRice(block)
                // freeze=true structural declaration (flax / rice_panicles / tomato
                // family) — declared in the crop_structure data map.
                || CropStructureRegistry.resolve(block).freeze();
        FREEZE_CACHE.put(block, nonArable);
        return nonArable;
    }

    /**
     * Position-level non-arable check. In addition to {@link #isNonArableBlock},
     * a crop also freezes when the block directly below it is itself a water
     * crop (a {@link LiquidBlockContainer} that is a recognized crop). This
     * covers Farmers Delight {@code rice_panicles}, which is a plain
     * {@link CropBlock} whose supporting block is the waterlogged
     * {@code rice} block.
     *
     * <p>The below-block test is deliberately tightened to "water crop" (not
     * merely any {@link LiquidBlockContainer}) to avoid false freezes on top of
     * waterlogged slabs, buckets and similar structures.</p>
     *
     * @param level the level
     * @param pos   the crop position
     * @param block the crop block at {@code pos}
     * @return true if the crop freezes during unsuitable seasons
     */
    public static boolean isNonArableAt(Level level, BlockPos pos, Block block) {
        if (isNonArableBlock(block)) return true;
        Block below = level.getBlockState(pos.below()).getBlock();
        return below instanceof LiquidBlockContainer
                && CropKindResolver.kindOf(below) != CropKind.NONE;
    }

    // =======================================================================
    // Segmented Water Rice (KaleidoscopeCookery rice_crop)
    // =======================================================================

    /**
     * Sentinel for "no segmented-rice location property". {@link ConcurrentHashMap}
     * forbids null values in {@code computeIfAbsent}, so a non-null sentinel is
     * used to represent "not segmented rice".
     */
    static final IntegerProperty NO_SEGMENT_PROPERTY =
            IntegerProperty.create("pastoralcraft_no_segment", 0, 1);

    private static final Map<Block, IntegerProperty> SEGMENTED_RICE_LOCATION =
            new ConcurrentHashMap<>();

    static IntegerProperty segmentedLocationProperty(Block block) {
        return SEGMENTED_RICE_LOCATION.computeIfAbsent(block, CropClassifier::resolveSegmentProperty);
    }

    /**
     * Resolve the segmented-rice {@code location} property. An explicit structural
     * declaration ({@link CropStructureRegistry}) wins; blocks without one fall
     * back to the generic property scan, which is downgraded to the default
     * provider (P5 — see crop-structure-registry-blueprint.md).
     */
    private static IntegerProperty resolveSegmentProperty(Block block) {
        StructureDescriptor descriptor = CropStructureRegistry.resolve(block);
        if (descriptor == CropStructureRegistry.EMPTY) {
            // No explicit declaration: generic scan for the "location" property.
            return scanForSegmentProperty(block, "location");
        }
        String name = descriptor.segmentProperty();
        if (name == null) {
            // Explicitly declared as NOT segmented — skip the fragile scan.
            return NO_SEGMENT_PROPERTY;
        }
        return scanForSegmentProperty(block, name);
    }

    private static IntegerProperty scanForSegmentProperty(Block block, String name) {
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            if (!(property instanceof IntegerProperty intProperty)
                    || !name.equals(intProperty.getName())) {
                continue;
            }
            // Require exactly three values {0, 1, 2} (DOWN/MIDDLE/UP).
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            int count = 0;
            boolean hasZero = false;
            boolean hasTwo = false;
            for (int value : intProperty.getPossibleValues()) {
                count++;
                if (value < min) min = value;
                if (value > max) max = value;
                if (value == 0) hasZero = true;
                if (value == 2) hasTwo = true;
            }
            if (count == 3 && min == 0 && max == 2 && hasZero && hasTwo) {
                return intProperty;
            }
        }
        return NO_SEGMENT_PROPERTY;
    }

    /**
     * Detect segmented water rice (e.g. KaleidoscopeCookery's {@code rice_crop},
     * a {@link CropBlock} with an {@code AGE} property and a three-value
     * {@code LOCATION} property DOWN=0/MIDDLE=1/UP=2). Detection is structural
     * (per-block cached property scan) so no mod-id or classpath dependency is
     * required, and the hot path is O(1).
     *
     * @param block the block to classify
     * @return true if the block exposes a segmented-rice location property
     */
    public static boolean isSegmentedRice(Block block) {
        return segmentedLocationProperty(block) != NO_SEGMENT_PROPERTY;
    }

    /**
     * Get the segment index of a segmented rice state: DOWN=0, MIDDLE=1, UP=2.
     * Returns -1 if the block is not segmented rice.
     *
     * @param state the block state to query
     * @return the segment index, or -1 if not segmented rice
     */
    public static int getRiceSegment(BlockState state) {
        IntegerProperty location = segmentedLocationProperty(state.getBlock());
        if (location == NO_SEGMENT_PROPERTY || !state.hasProperty(location)) return -1;
        return state.getValue(location);
    }

    // =======================================================================
    // Crop Type Detection — Unified Growable Crop Support
    // =======================================================================

    /**
     * Check if a block is a recognized age-based growable crop.
     *
     * <p><b>AttachedStemBlock exclusion:</b> {@link AttachedStemBlock} has no
     * {@code AGE} property (only {@code FACING}), so it is intentionally excluded
     * here — it is treated as a non-crop, triggering entry cleanup in the caller.</p>
     *
     * @param block the block to check
     * @return true if the block is a recognized growable crop
     */
    public static boolean isGrowableCrop(Block block) {
        return block instanceof CropBlock
                || block instanceof StemBlock
                || block instanceof NetherWartBlock
                || block instanceof CocoaBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof SugarCaneBlock
                || block instanceof KelpBlock
                || block instanceof KelpPlantBlock
                || block instanceof CactusBlock
                || CropKindResolver.regrowOf(block) != null
                // Generic AGE recognition: any block exposing an "age" property via
                // CropKindResolver (BushBlock subclasses, Farmers Delight
                // BuddingTomatoBlock / RiceBlock, vanilla PitcherCropBlock, ...).
                || CropKindResolver.ageOf(block) != null;
    }

    /**
     * Check if a block is a REGROW crop (boolean product, e.g. the sunflower's
     * {@code has_seeds}).
     *
     * @param block the block to check
     * @return true if the block is a REGROW crop
     */
    public static boolean isRegrow(Block block) {
        return CropKindResolver.regrowOf(block) != null;
    }

    /**
     * Extract the current age from a recognized growable crop block state.
     * Returns -1 if the block is not a recognized growable crop.
     *
     * @param state the block state to read from
     * @return the current age (0-based), or -1 if not a recognized crop
     */
    public static int getCropAge(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) return crop.getAge(state);
        // AttachedStemBlock has no AGE property — defense in depth:
        // isGrowableCrop already excludes it, but a stray call could still reach here
        if (block instanceof AttachedStemBlock) return -1;
        if (block instanceof StemBlock) return state.getValue(StemBlock.AGE);
        if (block instanceof NetherWartBlock) return state.getValue(NetherWartBlock.AGE);
        if (block instanceof CocoaBlock) return state.getValue(CocoaBlock.AGE);
        if (block instanceof SweetBerryBushBlock) return state.getValue(SweetBerryBushBlock.AGE);
        if (block instanceof SugarCaneBlock) return -1; // Age is height-based, not property-based
        if (block instanceof KelpBlock || block instanceof KelpPlantBlock) return -1; // height-based
        if (block instanceof CactusBlock) return -1; // height-based (AGE_15 is vanilla random progress)
        // Generic AGE fallback: BuddingTomatoBlock / PitcherCropBlock / RiceBlock
        // (BushBlock-style) expose a plain "age" property.
        AgeCrop ageCrop = CropKindResolver.ageOf(block);
        if (ageCrop != null && state.hasProperty(ageCrop.ageProperty())) {
            return state.getValue(ageCrop.ageProperty());
        }
        return -1;
    }

    /**
     * Get the maximum age for a recognized growable crop block.
     * Returns -1 if the block is not a recognized growable crop.
     *
     * @param block the block to query
     * @return the maximum age, or -1 if not a recognized crop
     */
    public static int getCropMaxAge(Block block) {
        if (block instanceof CropBlock crop) return crop.getMaxAge();
        if (block instanceof StemBlock) return StemBlock.MAX_AGE;
        if (block instanceof NetherWartBlock) return NetherWartBlock.MAX_AGE;
        if (block instanceof CocoaBlock) return CocoaBlock.MAX_AGE;
        if (block instanceof SweetBerryBushBlock) return SweetBerryBushBlock.MAX_AGE;
        if (block instanceof SugarCaneBlock) return 2; // Max 3 blocks tall (0-indexed age = 2)
        if (block instanceof KelpBlock || block instanceof KelpPlantBlock) return KELP_MAX_HEIGHT - 1; // 25
        if (block instanceof CactusBlock) return 2; // Max 3 blocks tall (0-indexed age = 2)
        if (CropKindResolver.regrowOf(block) != null) return 1; // binary: product absent (false) = 0, present (true) = 1
        // Generic AGE fallback (see getCropAge).
        AgeCrop ageCrop = CropKindResolver.ageOf(block);
        if (ageCrop != null) return ageCrop.maxAge();
        return -1;
    }

    /**
     * Get the {@link BlockState} for a recognized growable crop at the given age.
     * The provided state is used as a base, preserving other properties (e.g.
     * {@code CocoaBlock}'s {@code FACING}) via {@code setValue}.
     * The incoming {@code age} is clamped to {@code [0, maxAge]} to prevent
     * {@link IllegalArgumentException} from out-of-bounds property values.
     *
     * <p><b>Defense:</b> {@link AttachedStemBlock} is not a {@link StemBlock}
     * and has no {@code AGE} property — it will fall through to {@code return null}.
     * Callers should guard with {@link #isGrowableCrop} before calling this method.</p>
     *
     * @param state the current block state to use as a base
     * @param age   the desired age (will be clamped to valid range)
     * @return the block state at the given age, or null if not a recognized crop
     */
    public static BlockState getCropStateForAge(BlockState state, int age) {
        Block block = state.getBlock();
        // Clamp age to valid range — prevents IllegalArgumentException from
        // setValue/set when catch-up logic produces an out-of-range age
        int maxAge = getCropMaxAge(block);
        if (maxAge < 0) return null; // not a recognized crop (includes AttachedStemBlock)
        int clampedAge = Math.clamp(age, 0, maxAge);
        if (block instanceof CropBlock crop) return crop.getStateForAge(clampedAge);
        if (block instanceof StemBlock) return state.setValue(StemBlock.AGE, clampedAge);
        if (block instanceof NetherWartBlock) return state.setValue(NetherWartBlock.AGE, clampedAge);
        if (block instanceof CocoaBlock) return state.setValue(CocoaBlock.AGE, clampedAge);
        if (block instanceof SweetBerryBushBlock) return state.setValue(SweetBerryBushBlock.AGE, clampedAge);
        // Sugar cane & kelp growth are handled via block placement, not state changes
        if (block instanceof SugarCaneBlock) return state;
        if (block instanceof KelpBlock || block instanceof KelpPlantBlock) return state;
        if (block instanceof CactusBlock) return state;
        // Generic AGE fallback (see getCropAge). clampedAge was derived from
        // getCropMaxAge, which already reflects the AGE max, so it is in range.
        AgeCrop ageCrop = CropKindResolver.ageOf(block);
        if (ageCrop != null) return ageCrop.stateForAge(state, clampedAge);
        return null;
    }

    /**
     * Get the block state for a crop at the given age, preserving all current
     * state properties for segmented water rice.
     *
     * <p>{@link CropBlock#getStateForAge} builds a fresh default state, which for
     * segmented rice (KaleidoscopeCookery {@code rice_crop}) resets
     * {@code WATERLOGGED} to false and {@code LOCATION} to DOWN — de-waterlogging
     * the DOWN segment so it fails {@code canSurvive} and breaks. For segmented
     * rice this method instead sets only the {@code AGE} property on the current
     * state, preserving {@code WATERLOGGED}/{@code LOCATION}.</p>
     *
     * @param state the current block state to use as a base
     * @param age   the desired age
     * @return the block state at the given age
     */
    public static BlockState getCropStateForAgePreserving(BlockState state, int age) {
        if (isSegmentedRice(state.getBlock())) {
            return state.setValue(CropBlock.AGE, age);
        }
        return getCropStateForAge(state, age);
    }

    /** Maximum height of a kelp stalk (vanilla GrowingPlantHeadBlock max age 25 → 26 blocks). */
    static final int KELP_MAX_HEIGHT = 26;

    /**
     * Check if the given block is a kelp block (head or stem/plant).
     *
     * @param block the block to check
     * @return true if the block is a kelp head ({@link KelpBlock}) or stem ({@link KelpPlantBlock})
     */
    static boolean isKelp(Block block) {
        return block instanceof KelpBlock || block instanceof KelpPlantBlock;
    }

    /**
     * Resolve the stem whose calendar/season config anchors a stem-family block.
     * {@link AttachedStemBlock}s carry no season tag of their own, so they must
     * reuse their base stem's ({@code melon_stem}/{@code pumpkin_stem}) config.
     */
    static Block stemAnchor(Block block) {
        if (block == Blocks.ATTACHED_MELON_STEM) return Blocks.MELON_STEM;
        if (block == Blocks.ATTACHED_PUMPKIN_STEM) return Blocks.PUMPKIN_STEM;
        return block;
    }

    /**
     * Whether the upper half of a two-block (DOUBLE) crop needs to be placed or
     * refreshed. Idempotency guard for {@code placeDoubleUpperHalf}.
     *
     * @param aboveState the current state above the lower half
     * @param upperState the target upper-half state
     * @return true when the upper half must be (re)placed
     */
    static boolean needsUpperHalfPlacement(BlockState aboveState, BlockState upperState) {
        return !aboveState.equals(upperState);
    }

    /**
     * Whether the state above a crop is that crop's UPPER half (used when a
     * mutated two-block crop must clear its detached upper half so it does not
     * float).
     *
     * @param aboveState the state directly above the crop
     * @param cropBlock  the crop block
     * @return true when aboveState is the UPPER half of cropBlock
     */
    static boolean isUpperHalfOf(BlockState aboveState, Block cropBlock) {
        return aboveState.getBlock() == cropBlock
                && aboveState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && aboveState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
    }

    /**
     * Whether a block state is the UPPER half of a two-block (DOUBLE) crop such
     * as flax or pitcher_crop, for which the tracker only follows the LOWER
     * half. An UPPER-half state that is independently tracked would advance its
     * own age, and {@link #getCropStateForAge} resets {@code HALF} to LOWER —
     * corrupting the two-block structure.
     *
     * @param state    the block state to test
     * @param override the crop override (null = not configured as a DOUBLE crop)
     * @return true when the state is an UPPER half of a DOUBLE crop
     */
    static boolean isDoubleCropUpperHalf(BlockState state) {
        return isDoubleCropUpperHalf(state, CropStructureRegistry.resolve(state.getBlock()));
    }

    static boolean isDoubleCropUpperHalf(BlockState state, StructureDescriptor descriptor) {
        return descriptor.doubleAge() >= 0
                && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
    }

    /**
     * Whether a crop block is a climb-family crop (its override carries a
     * {@code climbBlock} and a positive {@code maxClimbHeight}).
     *
     * @param block the crop block
     * @return {@code true} if the block participates in calendar-driven climbing
     */
    public static boolean isClimbCrop(Block block) {
        StructureDescriptor descriptor = CropStructureRegistry.resolve(block);
        return descriptor.climbBlock() != null && descriptor.maxClimbHeight() > 0;
    }
}
