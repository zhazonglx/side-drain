package com.sidedrain.client;

import com.sidedrain.registry.ModBlockEntities;
import com.sidedrain.registry.ModBlocks;
import com.sidedrain.registry.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
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

            // The redstone panel re-uses vanilla's own dust textures, and every one of them is
            // alpha-tested: redstone_dust_line0 is a thin anti-aliased line on a transparent
            // sheet, and redstone_dust_overlay is fully transparent. Left on the default SOLID
            // layer the alpha test never runs, so the transparent texels are written to the
            // depth buffer as opaque black - the overlay sheet alone covers the whole face and
            // the block reads as a black/magenta slab instead of red dust. Vanilla registers
            // REDSTONE_WIRE as cutout for exactly this reason; the panel has to match it or it
            // stops looking like redstone at all.
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.STANDING_REDSTONE.get(), RenderType.cutout());
        });
    }

    /**
     * Drops caches that describe baked models.
     *
     * <p>A resource pack swap rebuilds every model, so the render-type lists cached per state and
     * the rotated shape cache all point at geometry that no longer exists. Clearing them here means
     * the first frame after the reload rebuilds from the new pack.
     */
    @SubscribeEvent
    public static void onReloadListeners(
            net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((barrier, manager, prepProfiler, reloadProfiler, background, game) ->
                barrier.wait(null).thenRunAsync(() -> {
                    RotatedBlockRenderer.clearCaches();
                    com.sidedrain.util.ShapeRotator.clearCache();
                }, game));
    }

    /** The standing panel is tinted with the exact same redstone colour curve as vanilla dust. */
    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) ->
                        RedStoneWireBlock.getColorForPower(state.getValue(com.sidedrain.block.StandingRedstoneBlock.POWER)),
                ModBlocks.STANDING_REDSTONE.get());
    }

    /**
     * Registers the carrier's geometry loader.
     *
     * <p>The carrier's model has no fixed geometry: it shows whatever block is wrapped, turned by the
     * carrier's own angles. Both live in model data, so the model is built at bake time as an empty
     * shell that produces its quads on demand - which is what lets the <b>chunk mesher</b> draw it,
     * and with it inherit correct lighting, ambient occlusion and shader phase.
     */
    @SubscribeEvent
    public static void onRegisterGeometryLoaders(
            net.minecraftforge.client.event.ModelEvent.RegisterGeometryLoaders event) {
        event.register("rotated", new com.sidedrain.client.RotatedBlockModel.Loader());
    }

    private ClientSetup() {
    }
}
