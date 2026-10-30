package com.sidedrain.registry;

import com.sidedrain.SideDrain;
import com.sidedrain.block.entity.OmniHopperBlockEntity;
import com.sidedrain.block.entity.RotatedBlockEntity;
import com.sidedrain.block.entity.SideDrainBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, SideDrain.MODID);

    /**
     * Both plate blocks share one block entity type.
     *
     * <p>They are the same machine; the only difference is the two inventory sizes, which the block
     * entity reads off its own block. Registering a second type would have meant a second menu, a
     * second screen and a second copy of every transfer rule.
     */
    public static final RegistryObject<BlockEntityType<SideDrainBlockEntity>> SIDE_DRAIN =
            BLOCK_ENTITIES.register("side_drain_plate", () -> BlockEntityType.Builder
                    .of(SideDrainBlockEntity::new,
                            ModBlocks.SIDE_DRAIN_PLATE.get(),
                            ModBlocks.EXPANDED_SIDE_DRAIN_PLATE.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<OmniHopperBlockEntity>> OMNI_HOPPER =
            BLOCK_ENTITIES.register("omni_hopper", () -> BlockEntityType.Builder
                    .of(OmniHopperBlockEntity::new, ModBlocks.OMNI_HOPPER.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<RotatedBlockEntity>> ROTATED_BLOCK =
            BLOCK_ENTITIES.register("rotated_block", () -> BlockEntityType.Builder
                    .of(RotatedBlockEntity::new, ModBlocks.ROTATED_BLOCK.get())
                    .build(null));
}
