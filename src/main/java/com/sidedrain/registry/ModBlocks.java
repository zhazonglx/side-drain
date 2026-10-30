package com.sidedrain.registry;

import com.sidedrain.SideDrain;
import com.sidedrain.block.OmniHopperBlock;
import com.sidedrain.block.RotatedBlock;
import com.sidedrain.block.SideDrainBlock;
import com.sidedrain.block.StandingRedstoneBlock;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, SideDrain.MODID);

    public static final RegistryObject<Block> SIDE_DRAIN_PLATE =
            BLOCKS.register("side_drain_plate", SideDrainBlock::new);

    /** The same plate with twice the storage (54 slots) and a 6 x 6 filter grid. */
    public static final RegistryObject<Block> EXPANDED_SIDE_DRAIN_PLATE =
            BLOCKS.register("expanded_side_drain_plate", () -> new SideDrainBlock(true));

    /** Not craftable and not in the creative tab: it only exists as a wrench target. */
    public static final RegistryObject<Block> OMNI_HOPPER =
            BLOCKS.register("omni_hopper", OmniHopperBlock::new);

    /** Redstone dust stood on its edge. Reached by wrenching dust, not by crafting. */
    public static final RegistryObject<Block> STANDING_REDSTONE =
            BLOCKS.register("standing_redstone", StandingRedstoneBlock::new);

    /** Carrier that lets the wrench tilt blocks that have no orientation of their own. */
    public static final RegistryObject<Block> ROTATED_BLOCK =
            BLOCKS.register("rotated_block", RotatedBlock::new);
}
