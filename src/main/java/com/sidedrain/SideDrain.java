package com.sidedrain;

import com.sidedrain.event.CommonEvents;
import com.sidedrain.network.ModNetwork;
import com.sidedrain.registry.ModBlockEntities;
import com.sidedrain.registry.ModBlocks;
import com.sidedrain.registry.ModCreativeTabs;
import com.sidedrain.registry.ModItems;
import com.sidedrain.registry.ModMenus;
import com.sidedrain.util.ShapeWarmup;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(SideDrain.MODID)
public class SideDrain {
    public static final String MODID = "sidedrain";

    /** Shared logger; the mod is deliberately quiet and only logs recoverable failures. */
    public static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    public SideDrain() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus(); // NOSONAR legacy entry point
        ModBlocks.BLOCKS.register(bus);
        ModItems.ITEMS.register(bus);
        ModBlockEntities.BLOCK_ENTITIES.register(bus);
        ModMenus.MENUS.register(bus);
        ModCreativeTabs.TABS.register(bus);

        ModNetwork.register();
        MinecraftForge.EVENT_BUS.register(CommonEvents.class);
        MinecraftForge.EVENT_BUS.register(Lifecycle.class);
        // Crafting-table container persistence: open/close restore + spill on break. This is what
        // turns a vanilla (or modded subclass) crafting table into a container the plates can clamp
        // onto, and what keeps items left on the table from dropping when the screen closes.
        MinecraftForge.EVENT_BUS.register(com.sidedrain.event.CraftingTableEvents.class);
    }

    /**
     * Stops the shape pre-computation worker when the server shuts down.
     *
     * <p>The pool is made of daemon threads, so leaving it running would not hang the game, but a
     * worker still holding references to the previous world's shapes would be wasted memory in a
     * single-player session that goes back to the main menu and loads a different world.
     */
    public static final class Lifecycle {
        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            ShapeWarmup.shutdown();
        }

        private Lifecycle() {
        }
    }
}
