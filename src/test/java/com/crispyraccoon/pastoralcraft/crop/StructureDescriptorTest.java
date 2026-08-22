package com.crispyraccoon.pastoralcraft.crop;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the P5 structural descriptor codec (S3).
 *
 * <p>The codec is exercised offline via {@link JsonOps}: {@link ResourceLocation}
 * is a plain value type, so no Minecraft registry bootstrap is required. These
 * tests pin the JSON field names that data packs use to declare crop structures
 * (mirroring Unloaded Activity's {@code simulate_info} field semantics).</p>
 */
class StructureDescriptorTest {

    private static StructureDescriptor decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        return StructureDescriptor.CODEC.decode(JsonOps.INSTANCE, element).getOrThrow().getFirst();
    }

    private static JsonElement encode(StructureDescriptor descriptor) {
        return StructureDescriptor.CODEC.encodeStart(JsonOps.INSTANCE, descriptor).getOrThrow();
    }

    @Test
    void codec_decodesEmptyJsonToDefaults() {
        StructureDescriptor d = decode("{}");
        assertEquals(-1, d.doubleAge());
        assertNull(d.topBlock());
        assertNull(d.transformBlock());
        assertNull(d.climbBlock());
        assertNull(d.climbSupport());
        assertEquals(0, d.maxClimbHeight());
        assertNull(d.segmentProperty());
        assertEquals(CropStructureRegistry.EMPTY, d);
    }

    @Test
    void codec_decodesFlaxDoubleAge() {
        StructureDescriptor d = decode("{\"doubleAge\": 4}");
        assertEquals(4, d.doubleAge());
        assertTrue(d.isDouble());
    }

    @Test
    void codec_decodesRiceCompanion() {
        StructureDescriptor d = decode("{\"topBlock\": \"farmersdelight:rice_panicles\"}");
        assertEquals(ResourceLocation.parse("farmersdelight:rice_panicles"), d.topBlock());
        assertTrue(d.hasCompanion());
    }

    @Test
    void codec_decodesTomatoesClimb() {
        StructureDescriptor d = decode("{\"climbBlock\": \"farmersdelight:tomatoes_on_rope\","
                + " \"climbSupport\": \"farmersdelight:rope\", \"maxClimbHeight\": 2}");
        assertEquals(ResourceLocation.parse("farmersdelight:tomatoes_on_rope"), d.climbBlock());
        assertEquals(ResourceLocation.parse("farmersdelight:rope"), d.climbSupport());
        assertEquals(2, d.maxClimbHeight());
        assertTrue(d.hasClimb());
    }

    @Test
    void codec_decodesKcRiceSegmentProperty() {
        StructureDescriptor d = decode("{\"segmentProperty\": \"location\"}");
        assertEquals("location", d.segmentProperty());
    }

    @Test
    void codec_roundTrips() {
        StructureDescriptor original = new StructureDescriptor(4,
                ResourceLocation.parse("a:b"), ResourceLocation.parse("c:d"),
                ResourceLocation.parse("e:f"), ResourceLocation.parse("g:h"),
                2, "location");
        StructureDescriptor decoded = decode(encode(original).toString());
        assertEquals(original, decoded);
    }

    @Test
    void predicates_defaultToFalse() {
        assertFalse(CropStructureRegistry.EMPTY.isDouble());
        assertFalse(CropStructureRegistry.EMPTY.hasCompanion());
        assertFalse(CropStructureRegistry.EMPTY.hasTransform());
        assertFalse(CropStructureRegistry.EMPTY.hasClimb());
    }
}
