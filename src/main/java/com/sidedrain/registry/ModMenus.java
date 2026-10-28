package com.sidedrain.registry;

import com.sidedrain.SideDrain;
import com.sidedrain.menu.PersistentCraftingMenu;
import com.sidedrain.menu.SideDrainMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, SideDrain.MODID);

    public static final RegistryObject<MenuType<SideDrainMenu>> SIDE_DRAIN =
            MENUS.register("side_drain_plate", () -> IForgeMenuType.create(
                    (windowId, inv, data) -> new SideDrainMenu(windowId, inv, data)));

    /**
     * The crafting menu bound to a persistent table grid.
     *
     * <p>The client factory reads the table position off the network buffer, which is what lets the
     * menu persist its grid into the right table on close - the vanilla {@code ContainerLevelAccess}
     * field it inherits is private and unmapped at runtime.
     */
    public static final RegistryObject<MenuType<PersistentCraftingMenu>> PERSISTENT_CRAFTING =
            MENUS.register("persistent_crafting", () -> IForgeMenuType.create(
                    (windowId, inv, data) -> new PersistentCraftingMenu(windowId, inv, data)));
}
