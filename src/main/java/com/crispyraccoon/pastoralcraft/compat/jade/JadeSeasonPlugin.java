package com.crispyraccoon.pastoralcraft.compat.jade;

import net.minecraft.world.level.block.Block;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * PastoralCraft's Jade plugin: registers the crop-season block provider.
 *
 * <p>The class is only loaded by Jade's {@code @WailaPlugin} scanner, which runs
 * only when Jade is installed — Jade is a compile-only dependency, so this class
 * is never referenced (and never loaded) on servers or without Jade.</p>
 */
@WailaPlugin
public class JadeSeasonPlugin implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(JadeSeasonProvider.INSTANCE, Block.class);
    }
}
