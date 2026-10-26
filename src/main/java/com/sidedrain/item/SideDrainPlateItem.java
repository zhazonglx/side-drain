package com.sidedrain.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * The placeable Side Drain Plate item.
 *
 * <p>Placing it works exactly like any other block, sneaking or not: sneak + right click a container
 * simply clamps the plate onto that container instead of opening it.
 */
public class SideDrainPlateItem extends BlockItem {
    public SideDrainPlateItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.sidedrain.side_drain_plate.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.sidedrain.side_drain_plate.2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.sidedrain.side_drain_plate.3").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.sidedrain.side_drain_plate.4").withStyle(ChatFormatting.DARK_GRAY));
    }
}
