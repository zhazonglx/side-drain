package com.sidedrain.client;

import com.sidedrain.registry.ModBlockEntities;
import com.sidedrain.registry.ModBlocks;
import com.sidedrain.registry.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = com.sidedrain.SideDrain.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.SIDE_DRAIN.get(), SideDrainScreen::new);
            BlockEntityRenderers.register(ModBlockEntities.ROTATED_BLOCK.get(), RotatedBlockRenderer::new);
        });
    }

    /** The standing panel is tinted with the exact same redstone colour curve as vanilla dust. */
    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) ->
                        RedStoneWireBlock.getColorForPower(state.getValue(com.sidedrain.block.StandingRedstoneBlock.POWER)),
                ModBlocks.STANDING_REDSTONE.get());
    }

    private ClientSetup() {
    }
}
