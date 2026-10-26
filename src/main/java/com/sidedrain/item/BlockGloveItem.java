package com.sidedrain.item;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The Block Glove.
 *
 * <p>Only a player holding this on the main hand may lift a block into their bare hands with sneak
 * + middle click. Without it the key does what it always did.
 *
 * <p>Each glove carries its <b>own</b> block: the payload is stored on this very item stack, not on
 * the player, so a player can own several gloves, each holding something different, and switching
 * the main hand away never loses what a glove is holding. The glove says what it carries in its
 * name and in its tooltip, so a hotbar full of them is still readable at a glance.
 */
public class BlockGloveItem extends Item {
    /** Mirrors {@code CarryManager.ROOT}; duplicated so this class needs no server-side import. */
    private static final String ROOT = "sidedrain_carry";

    public BlockGloveItem(Properties properties) {
        super(properties);
    }

    @Nullable
    private static CompoundTag carriedTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT, 10)) {
            return null;
        }
        CompoundTag root = tag.getCompound(ROOT);
        return root.contains("Block", 10) ? root.getCompound("Block") : null;
    }

    /** True when this particular glove stack is holding something. */
    public static boolean isCarrying(ItemStack stack) {
        return carriedTag(stack) != null;
    }

    /**
     * The stack to draw for what this glove carries, or {@link ItemStack#EMPTY}.
     *
     * <p>Read straight off the glove's own NBT, so it is correct on the client without a round trip
     * and correct for another player whose equipment has been synced. That is what lets two gloves
     * carry two different blocks and each one draw its own.
     */
    public static ItemStack carriedDisplay(ItemStack glove) {
        CompoundTag blockTag = carriedTag(glove);
        if (blockTag == null || !blockTag.contains("Display", 10)) {
            return ItemStack.EMPTY;
        }
        return ItemStack.of(blockTag.getCompound("Display"));
    }

    /** The carried block state tag of this glove, or {@code null} when it carries no block. */
    @Nullable
    public static CompoundTag carriedStateTag(ItemStack glove) {
        CompoundTag blockTag = carriedTag(glove);
        if (blockTag == null || !blockTag.contains("State", 10)) {
            return null;
        }
        return blockTag.getCompound("State");
    }

    /** True when this glove carries a placeable entity rather than a block. */
    public static boolean carriesEntity(ItemStack glove) {
        CompoundTag blockTag = carriedTag(glove);
        return blockTag != null && blockTag.contains("EntityType");
    }

    /**
     * The name of what this glove carries, or {@code null} when it is empty.
     *
     * <p>The carry payload always stores a display stack - the block's own item, or a spawn egg /
     * name tag for a carried entity - so one lookup covers both cases and never has to parse a
     * block state.
     */
    @Nullable
    public static Component carriedName(ItemStack stack) {
        CompoundTag blockTag = carriedTag(stack);
        if (blockTag == null || !blockTag.contains("Display", 10)) {
            return null;
        }
        ItemStack display = ItemStack.of(blockTag.getCompound("Display"));
        return display.isEmpty() ? null : display.getHoverName();
    }

    /** A glove holding a block reads "Block Glove (Dirt)" so two gloves are never confused. */
    @Override
    public Component getName(ItemStack stack) {
        Component base = super.getName(stack);
        Component carried = carriedName(stack);
        if (carried == null) {
            return base;
        }
        return Component.translatable("item.sidedrain.block_glove.carrying", base, carried);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        Component carried = carriedName(stack);
        if (carried != null) {
            tooltip.add(Component.translatable("tooltip.sidedrain.block_glove.carrying", carried)
                    .withStyle(ChatFormatting.GOLD));
        } else {
            tooltip.add(Component.translatable("tooltip.sidedrain.block_glove.empty")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.translatable("tooltip.sidedrain.block_glove.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.sidedrain.block_glove.2").withStyle(ChatFormatting.DARK_GRAY));
    }
}
