package com.crispyraccoon.pastoralcraft.crop;

import com.crispyraccoon.pastoralcraft.PastoralCraft;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * Holds the {@link DataMapType data maps} provided by PastoralCraft.
 *
 * <p>{@link #CROP_STRUCTURE} attaches a {@link StructureDescriptor} to a
 * {@link Block}, loaded from
 * {@code data/<ns>/data_maps/block/crop_structure.json} and overridable by any
 * data pack. The built-in declarations live in
 * {@code data/pastoralcraft/data_maps/block/crop_structure.json}; third-party
 * mods or pack authors can add their own entries (or tag-level entries) to
 * declare a crop's structure without touching Java.</p>
 *
 * <p>Registration is a mod-bus event ({@link RegisterDataMapTypesEvent}), so the
 * class is subscribed on {@link EventBusSubscriber.Bus#MOD}.</p>
 */
@EventBusSubscriber(modid = PastoralCraft.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CropDataMaps {

    /**
     * The {@linkplain Block} data map that declares a crop block's structure.
     * The location is {@code pastoralcraft/data_maps/block/crop_structure.json}.
     */
    public static final DataMapType<Block, StructureDescriptor> CROP_STRUCTURE = DataMapType.builder(
            ResourceLocation.fromNamespaceAndPath(PastoralCraft.MODID, "crop_structure"),
            Registries.BLOCK,
            StructureDescriptor.CODEC).build();

    private CropDataMaps() {
        // Utility class — prevent instantiation.
    }

    @SubscribeEvent
    private static void register(final RegisterDataMapTypesEvent event) {
        event.register(CROP_STRUCTURE);
    }
}
