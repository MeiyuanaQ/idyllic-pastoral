package com.crispyraccoon.pastoralcraft.crop;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Data map value describing the <em>structure</em> of a crop block, as opposed
 * to its calendar/behavior configuration (days-per-stage, seasons, freeze) which
 * stays in {@link CropGrowthConfig.CropOverride}.
 *
 * <p>This is the P5 structural registry from
 * {@code plans/crop-structure-registry-blueprint.md}: special crop structures
 * (two-block DOUBLE, COMPANION top block, TRANSFORM target, CLIMB family, and the
 * segmented water-rice {@code location} property) are declared as data instead of
 * being detected by fragile generic property scans or hard-coded block ids.</p>
 *
 * <p>The JSON field names mirror Unloaded Activity's {@code simulate_info}
 * descriptors, but the carrier is a NeoForge {@code DataMapType} (see
 * {@link CropDataMaps}).</p>
 *
 * @param doubleAge      grow the upper half once age reaches this threshold; {@code -1} = not a two-block crop
 * @param topBlock       COMPANION: place this block above when mature; {@code null} = none
 * @param transformBlock TRANSFORM: replace the block with this one when mature; {@code null} = none
 * @param climbBlock     CLIMB: the vine-family block counted toward the climb stack; {@code null} = no climbing
 * @param climbSupport   CLIMB: the support block the vine climbs up; {@code null} = none
 * @param maxClimbHeight CLIMB: maximum vine segments the vine can climb (0 = none)
 * @param segmentProperty the integer property name marking a segmented (three-value 0..2) crop such as
 *                        KaleidoscopeCookery rice ({@code location}); {@code null} = not segmented
 */
public record StructureDescriptor(
        int doubleAge,
        @Nullable ResourceLocation topBlock,
        @Nullable ResourceLocation transformBlock,
        @Nullable ResourceLocation climbBlock,
        @Nullable ResourceLocation climbSupport,
        int maxClimbHeight,
        @Nullable String segmentProperty) {

    public static final Codec<StructureDescriptor> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.INT.optionalFieldOf("doubleAge", -1).forGetter(StructureDescriptor::doubleAge),
            ResourceLocation.CODEC.optionalFieldOf("topBlock")
                    .forGetter(d -> Optional.ofNullable(d.topBlock)),
            ResourceLocation.CODEC.optionalFieldOf("transformBlock")
                    .forGetter(d -> Optional.ofNullable(d.transformBlock)),
            ResourceLocation.CODEC.optionalFieldOf("climbBlock")
                    .forGetter(d -> Optional.ofNullable(d.climbBlock)),
            ResourceLocation.CODEC.optionalFieldOf("climbSupport")
                    .forGetter(d -> Optional.ofNullable(d.climbSupport)),
            Codec.INT.optionalFieldOf("maxClimbHeight", 0).forGetter(StructureDescriptor::maxClimbHeight),
            Codec.STRING.optionalFieldOf("segmentProperty")
                    .forGetter(d -> Optional.ofNullable(d.segmentProperty))
    ).apply(inst, StructureDescriptor::fromCodec));

    private static StructureDescriptor fromCodec(int doubleAge,
                                                  Optional<ResourceLocation> topBlock,
                                                  Optional<ResourceLocation> transformBlock,
                                                  Optional<ResourceLocation> climbBlock,
                                                  Optional<ResourceLocation> climbSupport,
                                                  int maxClimbHeight,
                                                  Optional<String> segmentProperty) {
        return new StructureDescriptor(doubleAge,
                topBlock.orElse(null), transformBlock.orElse(null),
                climbBlock.orElse(null), climbSupport.orElse(null),
                maxClimbHeight, segmentProperty.orElse(null));
    }

    /** Whether this descriptor marks a two-block DOUBLE crop. */
    public boolean isDouble() {
        return doubleAge >= 0;
    }

    /** Whether this descriptor carries a COMPANION top block. */
    public boolean hasCompanion() {
        return topBlock != null;
    }

    /** Whether this descriptor carries a TRANSFORM target. */
    public boolean hasTransform() {
        return transformBlock != null;
    }

    /** Whether this descriptor carries a CLIMB configuration. */
    public boolean hasClimb() {
        return climbBlock != null && maxClimbHeight > 0;
    }
}
