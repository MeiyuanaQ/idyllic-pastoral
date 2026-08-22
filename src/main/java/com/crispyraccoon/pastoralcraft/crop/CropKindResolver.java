package com.crispyraccoon.pastoralcraft.crop;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Classifies crop blocks into {@link CropKind growth strategies}.
 *
 * <p>Each block is resolved once and cached, so the hot path is O(1). The AGE
 * classification first runs a fast {@code instanceof} check against the plant
 * family base classes ({@link CropBlock}, {@link StemBlock}, {@link BushBlock})
 * and only then lazily scans the state definition for an {@code age} integer
 * property. In the 1.21.1 Mojmap mapping there is no {@code PlantBlock}; its
 * former subclasses ({@code SaplingBlock}, {@code FlowerBlock},
 * {@code DoublePlantBlock}) all extend {@link BushBlock} directly, so
 * {@code BushBlock} covers the whole plant family. Restricting the scan to
 * these base classes prevents non-crop blocks that carry an {@code age}
 * property (e.g. {@code FireBlock}, {@code FrostedIceBlock}) from being
 * misclassified. The {@link GrowingPlantBlock} family (cave vines, twisting /
 * weeping vines, kelp) is intentionally <b>excluded</b>: its head blocks expose
 * an {@code age} property but are not stage-driven crops, and treating them as
 * such would cancel their vanilla random-tick growth and mutate them to short
 * grass in unsuitable seasons. Kelp is still handled via the separate HEIGHT
 * strategy.</p>
 */
public final class CropKindResolver {

    /** Maximum total height (including the root) for sugar cane. */
    public static final int SUGAR_CANE_MAX_HEIGHT = 3;

    /** Maximum total height (including the root) for kelp (head AGE caps at 25). */
    public static final int KELP_MAX_HEIGHT = 26;

    private static final Map<Block, CropKind> KIND_CACHE = new ConcurrentHashMap<>();
    private static final Map<Block, AgeCrop> AGE_CACHE = new ConcurrentHashMap<>();
    private static final Map<Block, HeightCrop> HEIGHT_CACHE = new ConcurrentHashMap<>();
    private static final Map<Block, RegrowCrop> REGROW_CACHE = new ConcurrentHashMap<>();

    private CropKindResolver() {
    }

    /**
     * Resolve the growth kind for a block (cached, O(1) after first resolve).
     *
     * @param block the crop block
     * @return the growth kind
     */
    public static CropKind kindOf(Block block) {
        return KIND_CACHE.computeIfAbsent(block, CropKindResolver::computeKind);
    }

    /**
     * Resolve the AGE descriptor for a block, or {@code null} if it is not an
     * AGE crop.
     *
     * @param block the crop block
     * @return the AGE descriptor, or {@code null}
     */
    @Nullable
    public static AgeCrop ageOf(Block block) {
        return AGE_CACHE.computeIfAbsent(block, CropKindResolver::computeAge);
    }

    /**
     * Resolve the HEIGHT descriptor for a block, or {@code null} if it is not a
     * height-based crop.
     *
     * @param block the crop block
     * @return the HEIGHT descriptor, or {@code null}
     */
    @Nullable
    public static HeightCrop heightOf(Block block) {
        return HEIGHT_CACHE.computeIfAbsent(block, CropKindResolver::computeHeight);
    }

    /**
     * Resolve the REGROW descriptor for a block, or {@code null} if it is not a
     * boolean-product regrow crop.
     *
     * @param block the crop block
     * @return the REGROW descriptor, or {@code null}
     */
    @Nullable
    public static RegrowCrop regrowOf(Block block) {
        return REGROW_CACHE.computeIfAbsent(block, CropKindResolver::computeRegrow);
    }

    /**
     * Pure priority logic shared by {@link #computeKind(Block)} and unit tests:
     * HEIGHT > REGROW > AGE > NONE. Package-private so tests can exercise
     * the decision without a Minecraft {@link Block} instance.
     *
     * @param height the height-based descriptor, or {@code null}
     * @param regrow the regrow descriptor, or {@code null}
     * @param age    the age-based descriptor, or {@code null}
     * @return the resolved crop kind
     */
    static CropKind kindFrom(@Nullable HeightCrop height, @Nullable RegrowCrop regrow, @Nullable AgeCrop age) {
        if (height != null) return CropKind.HEIGHT;
        if (regrow != null) return CropKind.REGROW;
        if (age != null) return CropKind.AGE;
        return CropKind.NONE;
    }

    private static CropKind computeKind(Block block) {
        return kindFrom(heightOf(block), regrowOf(block), ageOf(block));
    }

    @Nullable
    private static AgeCrop computeAge(Block block) {
        // Fast path: only the crop base classes may carry a crop age.
        // FireBlock / FrostedIceBlock / GrowingPlantBlock (vines) are
        // intentionally excluded here.
        if (!(block instanceof CropBlock)
                && !(block instanceof StemBlock)
                && !(block instanceof BushBlock)) {
            return null;
        }
        IntegerProperty ageProperty = null;
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            if (property instanceof IntegerProperty integerProperty && property.getName().equals("age")) {
                ageProperty = integerProperty;
                break;
            }
        }
        if (ageProperty == null) return null;
        return new AgeCrop(ageProperty, resolveMaxAge(block, ageProperty));
    }

    /**
     * Resolve the maximum age: prefer a reflective {@code getMaxAge()} if the
     * class exposes one, otherwise fall back to the property's maximum value.
     */
    private static int resolveMaxAge(Block block, IntegerProperty ageProperty) {
        try {
            Method method = block.getClass().getMethod("getMaxAge");
            if (method.getReturnType() == int.class) {
                Object result = method.invoke(block);
                if (result instanceof Integer value) return value;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to the property maximum.
        }
        int max = Integer.MIN_VALUE;
        for (int value : ageProperty.getPossibleValues()) {
            if (value > max) max = value;
        }
        return max;
    }

    @Nullable
    private static HeightCrop computeHeight(Block block) {
        if (block instanceof SugarCaneBlock) {
            return new HeightCrop(SUGAR_CANE_MAX_HEIGHT, Blocks.SUGAR_CANE, false);
        }
        if (block instanceof KelpBlock) {
            return new HeightCrop(KELP_MAX_HEIGHT, Blocks.KELP, true);
        }
        if (block instanceof KelpPlantBlock) {
            return new HeightCrop(KELP_MAX_HEIGHT, Blocks.KELP_PLANT, true);
        }
        return null;
    }

    @Nullable
    private static RegrowCrop computeRegrow(Block block) {
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            if (!(property instanceof BooleanProperty booleanProperty)) continue;
            String name = property.getName();
            if (name.equals("has_seeds") || name.equals("seeded")) {
                return new RegrowCrop(booleanProperty);
            }
        }
        return null;
    }
}
