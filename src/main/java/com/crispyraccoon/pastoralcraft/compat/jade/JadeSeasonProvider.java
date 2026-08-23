package com.crispyraccoon.pastoralcraft.compat.jade;

import com.crispyraccoon.pastoralcraft.PastoralCraft;
import com.crispyraccoon.pastoralcraft.client.CropSeasonDisplay;

import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade block tooltip provider that shows PastoralCraft's crop season lines
 * (ES-style, data sourced from ES but with PastoralCraft config overrides).
 */
public class JadeSeasonProvider implements IBlockComponentProvider {

    static final JadeSeasonProvider INSTANCE = new JadeSeasonProvider();

    private JadeSeasonProvider() {
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        tooltip.addAll(CropSeasonDisplay.seasonTooltip(accessor.getBlock()));
    }

    @Override
    public ResourceLocation getUid() {
        return ResourceLocation.fromNamespaceAndPath(PastoralCraft.MODID, "crop_season");
    }

    @Override
    public int getDefaultPriority() {
        return 1000;
    }
}
