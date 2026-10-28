package com.sidedrain.util;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Shared container lookup, so the plate, the hopper logic and the wrench all agree.
 *
 * <p>A chest is deliberately returned as its own block entity rather than through
 * {@code ChestBlock.getContainer}: the combined double-chest view rebuilds itself from a block
 * entity cache that is stale for a block entity that was only just placed, which made a plate on a
 * fresh chest see nothing. The plain block entity is always correct and still exposes every slot.
 */
public final class Containers {
    @Nullable
    public static Container getContainerAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // Air is the overwhelmingly common answer - a plate clamped to a wall, a hopper pointing at
        // open space - and the entity sweep below is by far the most expensive part of this method.
        // Bailing out here turns a full AABB query into one chunk lookup.
        if (state.isAir()) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof Container container) {
            return container;
        }
        // A rotated block hides its container inside a carrier. The plate must be able to clamp onto
        // a rotated chest and keep working, so unwrap it here rather than special casing every
        // caller: the plate, the hopper logic and the wrench all go through this one method.
        if (blockEntity instanceof com.sidedrain.block.entity.RotatedBlockEntity rotated) {
            net.minecraft.world.level.block.entity.BlockEntity inner = rotated.innerEntity(level);
            if (inner instanceof Container container) {
                return container;
            }
        }
        if (state.getBlock() instanceof WorldlyContainerHolder holder) {
            Container held = holder.getContainer(state, level, pos);
            if (held != null) {
                return held;
            }
        }
        // Modded containers that expose their inventory through a capability rather than by
        // implementing Container on the block entity. Sophisticated Backpacks, Tom's Simple Storage
        // terminals and Refined Storage / Applied Energistics style grids all live here: their block
        // entity is not a Container, it *has* one behind an item handler capability. Asking the
        // capability is what makes the plate see them at all.
        Container viaCapability = fromItemHandler(level, pos, null);
        if (viaCapability != null) {
            return viaCapability;
        }
        List<Entity> entities = level.getEntities((Entity) null,
                new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1),
                EntitySelector.CONTAINER_ENTITY_SELECTOR);
        if (!entities.isEmpty()) {
            return (Container) entities.get(level.random.nextInt(entities.size()));
        }
        return null;
    }

    /**
     * Wraps a modded item handler as a {@link Container}.
     *
     * <p>Every modern storage mod exposes {@code IItemHandler} through Forge's capability system
     * instead of implementing vanilla {@code Container}. That is the whole reason a plate clamped to
     * a Sophisticated Backpack, a Tom's Simple Storage crafting terminal or a Refined Storage grid saw
     * nothing: the block entity answers {@code getCapability(ITEM_HANDLER)} but is not a Container, so
     * the vanilla-shaped check above walked straight past it.
     *
     * <p>The wrapper is read/write and forwards {@code setChanged} to the block entity, so draining
     * through it marks the chunk dirty exactly like draining a chest does.
     */
    @Nullable
    private static Container fromItemHandler(Level level, BlockPos pos, @Nullable Direction side) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }
        // A rotated carrier has no capability of its own; its wrapped entity does.
        BlockEntity target = blockEntity;
        if (blockEntity instanceof com.sidedrain.block.entity.RotatedBlockEntity rotated) {
            BlockEntity inner = rotated.innerEntity(level);
            if (inner != null) {
                target = inner;
            }
        }
        net.minecraftforge.common.capabilities.Capability<net.minecraftforge.items.IItemHandler> cap =
                net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER;
        // Prefer the null side (the "any side" view). Storage mods that gate by side return their
        // full inventory there, while a specific face may expose only an input or an output.
        net.minecraftforge.items.IItemHandler handler = target.getCapability(cap, side).orElse(null);
        if (handler == null || handler.getSlots() <= 0) {
            return null;
        }
        return new ItemHandlerContainer(handler, target);
    }

    /** Read/write {@link Container} view over a Forge {@code IItemHandler}. */
    private static final class ItemHandlerContainer implements Container {
        private final net.minecraftforge.items.IItemHandler handler;
        private final BlockEntity owner;

        ItemHandlerContainer(net.minecraftforge.items.IItemHandler handler, BlockEntity owner) {
            this.handler = handler;
            this.owner = owner;
        }

        @Override
        public int getContainerSize() {
            return this.handler.getSlots();
        }

        @Override
        public boolean isEmpty() {
            for (int i = 0; i < this.handler.getSlots(); i++) {
                if (!this.handler.getStackInSlot(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return this.handler.getStackInSlot(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            // extractItem respects each slot's own limit and returns what it actually gave, which is
            // exactly the contract Container.removeItem promises.
            return this.handler.extractItem(slot, amount, false);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack stack = this.handler.getStackInSlot(slot);
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            return this.handler.extractItem(slot, stack.getCount(), false);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            // insertItem will not replace an existing stack, so the slot is emptied first; that is
            // the only way to honour Container.setItem's "put this here" contract through a handler.
            this.handler.extractItem(slot, this.handler.getStackInSlot(slot).getCount(), false);
            if (!stack.isEmpty()) {
                this.handler.insertItem(slot, stack, false);
            }
        }

        @Override
        public void setChanged() {
            this.owner.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            for (int i = 0; i < this.handler.getSlots(); i++) {
                this.handler.extractItem(i, this.handler.getStackInSlot(i).getCount(), false);
            }
        }
    }

    private Containers() {
    }
}
