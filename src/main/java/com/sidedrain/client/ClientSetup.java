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
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = com.sidedrain.SideDrain.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    /**
     * Puts the mod's keys into Options -> Controls.
     *
     * <p>These are <b>additional</b> keys, not replacements: the sneak + click gestures keep
     * working exactly as before, and these bindings let the same actions be performed without
     * holding sneak.
     *
     * <p>This is a mod-bus event, so it must be handled on this class (which is registered on
     * {@code Bus.MOD}); subscribing from a plain game-bus class would silently never fire and the
     * bindings would be missing from the controls screen with no error.
     */
    @SubscribeEvent
    public static void onRegisterKeyMappings(
            net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(ModKeys.ROTATE);
        event.register(ModKeys.CARRY);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.SIDE_DRAIN.get(), SideDrainScreen::new);
            // The persistent crafting menu reuses vanilla's screen layout wholesale: CraftingScreen
            // accepts any CraftingMenu subclass, and this menu is one.
            MenuScreens.register(ModMenus.PERSISTENT_CRAFTING.get(),
                    net.minecraft.client.gui.screens.inventory.CraftingScreen::new);
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
            ModKeys.describe();
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
                    com.sidedrain.client.PlateAimModel.clearCaches();
                    com.sidedrain.util.ShapeRotator.clearCache();
                }, game));
    }

    /** The standing panel is tinted with the exact same redstone colour curve as vanilla dust. */
    @SubscribeEvent
    public static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) ->
                        RedStoneWireBlock.getColorForPower(state.getValue(com.sidedrain.block.StandingRedstoneBlock.POWER)),
                ModBlocks.STANDING_REDSTONE.get());
        // The rotated block has to resolve its tint from the block it wraps.
        //
        // A rotated block's quads keep the tintIndex of the wrapped block - grass overlay, leaves,
        // redstone wire - but the tint is looked up from the state the mesher is drawing, which is
        // the carrier. The carrier had no colour handler, so every tinted quad resolved to white:
        // a rotated grass block turned grey-white, rotated leaves turned grey. Delegating to the
        // wrapped block's own handler, at the carrier's own position, keeps biome tints and modded
        // tints correct - the wrapped block's handler is asked, so a mod's own colours come through.
        event.register((state, level, pos, tintIndex) ->
                        com.sidedrain.client.RotatedBlockTint.color(state, level, pos, tintIndex),
                ModBlocks.ROTATED_BLOCK.get());
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
        // The plate's dynamic aim model: it turns the flat slab by the wrench's tilt and spin, which
        // the blockstate's 90-degree-only rotations cannot express.
        event.register("plate_aim", new com.sidedrain.client.PlateAimModel.Loader());
    }

    /**
     * Ensures the plate's flat source slabs are baked even though the blockstate no longer points at
     * them.
     *
     * <p>{@code PlateAimModel} turns the flat slab's quads at mesh time, so it needs the three flat
     * slabs baked as standalone models. Vanilla only bakes a model when something references it, and
     * the blockstate used to be that reference - pointing every variant at the dynamic model instead
     * would leave the slabs unbaked and the plate invisible. This registration is the reference.
     */
    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        event.register(new net.minecraft.resources.ResourceLocation(
                "sidedrain", "block/side_drain_plate_side_t0"));
        event.register(new net.minecraft.resources.ResourceLocation(
                "sidedrain", "block/side_drain_plate_up_t0"));
        event.register(new net.minecraft.resources.ResourceLocation(
                "sidedrain", "block/side_drain_plate_down_t0"));
    }

    private ClientSetup() {
    }
}
