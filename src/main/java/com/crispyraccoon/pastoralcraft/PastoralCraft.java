package com.crispyraccoon.pastoralcraft;

import org.slf4j.Logger;

import com.crispyraccoon.pastoralcraft.crop.CropGrowthConfig;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Main mod class for PastoralCraft.
 * Implements a deterministic, solar-day-based crop growth system that integrates
 * with Ecliptic Seasons for seasonal growth mechanics.
 */
@Mod(PastoralCraft.MODID)
public class PastoralCraft {
    /** The unique mod identifier. Must match the entry in neoforge.mods.toml and gradle.properties. */
    public static final String MODID = "pastoralcraft";

    /** Shared mod logger. */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Mod constructor. FML injects the mod event bus and mod container.
     * @param modEventBus the mod-specific event bus
     * @param modContainer the mod container for this mod
     */
    public PastoralCraft(IEventBus modEventBus, ModContainer modContainer) {
        // Register our crop growth config spec so FML creates and manages the config file
        modContainer.registerConfig(ModConfig.Type.COMMON, CropGrowthConfig.SPEC);

        LOGGER.info("PastoralCraft initialized - deterministic crop growth system active");
    }
}