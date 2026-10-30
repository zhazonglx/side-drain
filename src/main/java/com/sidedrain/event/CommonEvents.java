package com.sidedrain.event;

import com.sidedrain.server.CarryManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Server side gameplay hooks that cannot live on an item or block class. */
public final class CommonEvents {
    @SubscribeEvent
    public static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        UtilCommands.onRegisterCommands(event);
    }

    // The crafting-table container events (grid persistence on open/close, spill on break) live in
    // CraftingTableEvents and are registered directly in the mod constructor on this same bus.

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.player instanceof ServerPlayer serverPlayer) {
            CarryManager.tickCarried(serverPlayer);
        }
    }


    /**
     * Carries a crafted plate's contents into the expanded plate.
     *
     * <p>Crafting the upgrade from a loaded plate would otherwise quietly destroy everything inside
     * it: the recipe consumes the plate, and a plain recipe result carries no block entity data. The
     * contents are copied across here, which is also why the recipe is shapeless and takes any chest
     * - the player is upgrading the machine they already built, not building a new one.
     */
    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        ItemStack crafted = event.getCrafting();
        if (!crafted.is(com.sidedrain.registry.ModItems.EXPANDED_SIDE_DRAIN_PLATE.get())) {
            return;
        }
        Container grid = event.getInventory();
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack ingredient = grid.getItem(i);
            if (!ingredient.is(com.sidedrain.registry.ModItems.SIDE_DRAIN_PLATE.get())) {
                continue;
            }
            CompoundTag data = ingredient.getTagElement("BlockEntityTag");
            if (data == null) {
                // A brand-new plate has nothing to carry; the crafted one starts empty, which is
                // what the player expects from an empty plate.
                return;
            }
            // Copied verbatim. The block entity re-sizes its inventory from the block on load, so
            // the fifty-four-slot layout is built fresh and the first twenty-seven slots keep
            // exactly what the plain plate held - the tail is simply empty.
            crafted.getOrCreateTag().put("BlockEntityTag", data.copy());
            return;
        }
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            CarryManager.syncTo(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            CarryManager.dropOnLogout(serverPlayer);
        }
    }

    private CommonEvents() {
    }
}
