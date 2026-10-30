package com.sidedrain.registry;

import com.sidedrain.SideDrain;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SideDrain.MODID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.sidedrain"))
            .icon(() -> new ItemStack(ModItems.ORIENTATION_WRENCH.get()))
            .displayItems((params, output) -> {
                output.accept(ModItems.ORIENTATION_WRENCH.get());
                output.accept(ModItems.SIDE_DRAIN_PLATE.get());
                output.accept(ModItems.EXPANDED_SIDE_DRAIN_PLATE.get());
                output.accept(ModItems.BLOCK_GLOVE.get());
                // The omni hopper and the standing redstone panel are wrench results, not items.
            })
            .build());
}
