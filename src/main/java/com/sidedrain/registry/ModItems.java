package com.sidedrain.registry;

import com.sidedrain.SideDrain;
import com.sidedrain.item.BlockGloveItem;
import com.sidedrain.item.OrientationWrenchItem;
import com.sidedrain.item.SideDrainPlateItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, SideDrain.MODID);

    public static final RegistryObject<Item> ORIENTATION_WRENCH =
            ITEMS.register("orientation_wrench", () -> new OrientationWrenchItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> SIDE_DRAIN_PLATE =
            ITEMS.register("side_drain_plate", () -> new SideDrainPlateItem(ModBlocks.SIDE_DRAIN_PLATE.get(), new Item.Properties()));

    /** Expanded plate: plate + any chest. Twice the storage, 6 x 6 filter. */
    public static final RegistryObject<Item> EXPANDED_SIDE_DRAIN_PLATE =
            ITEMS.register("expanded_side_drain_plate", () -> new SideDrainPlateItem(
                    ModBlocks.EXPANDED_SIDE_DRAIN_PLATE.get(), new Item.Properties()));

    public static final RegistryObject<Item> BLOCK_GLOVE =
            ITEMS.register("block_glove", () -> new BlockGloveItem(new Item.Properties().stacksTo(1)));
}
