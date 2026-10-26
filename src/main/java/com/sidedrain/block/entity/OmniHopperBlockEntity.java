package com.sidedrain.block.entity;

import com.sidedrain.block.OmniHopperBlock;
import com.sidedrain.registry.ModBlockEntities;
import com.sidedrain.util.HopperOrientation;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Hopper logic that works with a spout pointing in any of the six directions.
 *
 * <p>Items are pushed into the container the tail points at, and pulled out of the container the
 * bucket opens towards (for a vanilla downward hopper that is the block above it). Loose item
 * entities in front of the bucket are still sucked in.
 */
public class OmniHopperBlockEntity extends RandomizableContainerBlockEntity implements Hopper {
    public static final int MOVE_ITEM_SPEED = 8;
    public static final int CONTAINER_SIZE = 5;


    private NonNullList<ItemStack> items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);
    private int cooldownTime = -1;
    private long tickedGameTime;

    public OmniHopperBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OMNI_HOPPER.get(), pos, state);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        if (!this.tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, this.items);
        }
        this.cooldownTime = tag.getInt("TransferCooldown");
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!this.trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, this.items);
        }
        tag.putInt("TransferCooldown", this.cooldownTime);
    }

    @Override
    public int getContainerSize() {
        return this.items.size();
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        this.unpackLootTable(null);
        return ContainerHelper.removeItem(this.getItems(), slot, amount);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.unpackLootTable(null);
        this.getItems().set(slot, stack);
        if (stack.getCount() > this.getMaxStackSize()) {
            stack.setCount(this.getMaxStackSize());
        }
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.sidedrain.omni_hopper");
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new HopperMenu(id, inventory, this);
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    /**
     * The volume items are sucked out of, in front of the bucket mouth.
     *
     * <p>Hopper.SUCK is the vanilla intake volume: the inside of the bucket plus one block above it.
     * Turning it so that "above" points out of the bucket gives the intake volume of any form, so a
     * hopper hanging from a ceiling still catches items dropped below it.
     */
    @Override
    public net.minecraft.world.phys.shapes.VoxelShape getSuckShape() {
        HopperOrientation orientation = this.getBlockState().getValue(OmniHopperBlock.FACING);
        return suckShapeFor(orientation);
    }

    /**
     * The intake volume of a form, cached per body direction.
     *
     * <p>Only six distinct answers exist - one per direction the bucket can open towards - but the
     * old code rotated {@code Hopper.SUCK} again for every hopper on every tick, and again for every
     * item entity test inside {@code getItemsAtAndAbove}. The array is filled on first use and then
     * every query is an array read.
     */
    private static final net.minecraft.world.phys.shapes.VoxelShape[] SUCK_SHAPES =
            new net.minecraft.world.phys.shapes.VoxelShape[6];

    public static net.minecraft.world.phys.shapes.VoxelShape suckShapeFor(HopperOrientation orientation) {
        net.minecraft.core.Direction body = orientation.body();
        int index = body.get3DDataValue();
        net.minecraft.world.phys.shapes.VoxelShape cached = SUCK_SHAPES[index];
        if (cached != null) {
            return cached;
        }
        // A race here would only compute the same immutable shape twice, so no locking is needed.
        net.minecraft.world.phys.shapes.VoxelShape built =
                com.sidedrain.util.ShapeRotator.toFacing(Hopper.SUCK, body);
        SUCK_SHAPES[index] = built;
        return built;
    }

    @Override
    public double getLevelX() {
        return (double) this.worldPosition.getX() + 0.5D;
    }

    @Override
    public double getLevelY() {
        return (double) this.worldPosition.getY() + 0.5D;
    }

    @Override
    public double getLevelZ() {
        return (double) this.worldPosition.getZ() + 0.5D;
    }

    public void setCooldown(int ticks) {
        this.cooldownTime = ticks;
    }

    private boolean isOnCooldown() {
        return this.cooldownTime > 0;
    }

    public boolean isOnCustomCooldown() {
        return this.cooldownTime > 8;
    }

    public long getLastUpdateTime() {
        return this.tickedGameTime;
    }

    // ------------------------------------------------------------------ ticking

    public static void pushItemsTick(Level level, BlockPos pos, BlockState state, OmniHopperBlockEntity hopper) {
        --hopper.cooldownTime;
        hopper.tickedGameTime = level.getGameTime();
        if (!hopper.isOnCooldown()) {
            hopper.setCooldown(0);
            tryMoveItems(level, pos, state, hopper, () -> suckInItems(level, hopper));
        }
    }

    private static boolean tryMoveItems(Level level, BlockPos pos, BlockState state,
                                        OmniHopperBlockEntity hopper, BooleanSupplier suck) {
        if (level.isClientSide) {
            return false;
        }
        if (hopper.isOnCooldown()) {
            return false;
        }
        boolean moved = false;
        if (!hopper.isEmpty()) {
            moved = ejectItems(level, pos, state, hopper);
        }
        if (!hopper.inventoryFull()) {
            moved |= suck.getAsBoolean();
        }
        if (moved) {
            hopper.setCooldown(MOVE_ITEM_SPEED);
            setChanged(level, pos, state);
            return true;
        }
        return false;
    }

    private boolean inventoryFull() {
        for (ItemStack stack : this.items) {
            if (stack.isEmpty() || stack.getCount() != stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    private static boolean ejectItems(Level level, BlockPos pos, BlockState state, OmniHopperBlockEntity hopper) {
        Container target = getAttachedContainer(level, pos, state);
        if (target == null) {
            return false;
        }
        Direction side = state.getValue(OmniHopperBlock.FACING).spout().getOpposite();
        if (isFullContainer(target, side)) {
            return false;
        }
        for (int i = 0; i < hopper.getContainerSize(); ++i) {
            if (!hopper.getItem(i).isEmpty()) {
                ItemStack snapshot = hopper.getItem(i).copy();
                ItemStack remainder = addItem(hopper, target, hopper.removeItem(i, 1), side);
                if (remainder.isEmpty()) {
                    target.setChanged();
                    return true;
                }
                hopper.setItem(i, snapshot);
            }
        }
        return false;
    }

    @Nullable
    private static Container getAttachedContainer(Level level, BlockPos pos, BlockState state) {
        Direction direction = state.getValue(OmniHopperBlock.FACING).spout();
        return com.sidedrain.util.Containers.getContainerAt(level, pos.relative(direction));
    }

    @Nullable
    private static Container getSourceContainer(Level level, BlockPos pos, BlockState state) {
        Direction direction = state.getValue(OmniHopperBlock.FACING).body();
        return com.sidedrain.util.Containers.getContainerAt(level, pos.relative(direction));
    }

    public static boolean suckInItems(Level level, OmniHopperBlockEntity hopper) {
        BlockState state = hopper.getBlockState();
        BlockPos pos = hopper.getBlockPos();
        Container source = getSourceContainer(level, pos, state);
        if (source != null) {
            // The face of the source container that looks back at this hopper.
            Direction side = state.getValue(OmniHopperBlock.FACING).sourceSide();
            if (!isEmptyContainer(source, side)) {
                if (getSlots(source, side).anyMatch(slot -> tryTakeInItemFromSlot(hopper, source, slot, side))) {
                    return true;
                }
            }
        }
        for (ItemEntity itemEntity : getItemsAtAndAbove(level, hopper)) {
            if (addItem(hopper, itemEntity)) {
                return true;
            }
        }
        return false;
    }

    private static boolean tryTakeInItemFromSlot(Hopper hopper, Container container, int slot, Direction side) {
        ItemStack stack = container.getItem(slot);
        if (!stack.isEmpty() && canTakeItemFromContainer(hopper, container, stack, slot, side)) {
            ItemStack snapshot = stack.copy();
            ItemStack remainder = addItem(container, hopper, container.removeItem(slot, 1), null);
            if (remainder.isEmpty()) {
                container.setChanged();
                return true;
            }
            container.setItem(slot, snapshot);
        }
        return false;
    }

    public static boolean addItem(Container target, ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem().copy();
        ItemStack remainder = addItem(null, target, stack, null);
        if (remainder.isEmpty()) {
            itemEntity.discard();
            return true;
        }
        itemEntity.setItem(remainder);
        return false;
    }

    public static ItemStack addItem(@Nullable Container source, Container target, ItemStack stack, @Nullable Direction side) {
        if (target instanceof WorldlyContainer worldly && side != null) {
            int[] slots = worldly.getSlotsForFace(side);
            for (int i = 0; i < slots.length && !stack.isEmpty(); ++i) {
                stack = tryMoveInItem(source, target, stack, slots[i], side);
            }
            return stack;
        }
        int size = target.getContainerSize();
        for (int i = 0; i < size && !stack.isEmpty(); ++i) {
            stack = tryMoveInItem(source, target, stack, i, side);
        }
        return stack;
    }

    private static boolean canPlaceItemInContainer(Container container, ItemStack stack, int slot, @Nullable Direction side) {
        if (!container.canPlaceItem(slot, stack)) {
            return false;
        }
        if (container instanceof WorldlyContainer worldly) {
            return worldly.canPlaceItemThroughFace(slot, stack, side);
        }
        return true;
    }

    private static boolean canTakeItemFromContainer(Container hopper, Container container, ItemStack stack,
                                                    int slot, Direction side) {
        if (!container.canTakeItem(hopper, slot, stack)) {
            return false;
        }
        if (container instanceof WorldlyContainer worldly) {
            return worldly.canTakeItemThroughFace(slot, stack, side);
        }
        return true;
    }

    private static ItemStack tryMoveInItem(@Nullable Container source, Container target, ItemStack stack,
                                           int slot, @Nullable Direction side) {
        ItemStack existing = target.getItem(slot);
        if (!canPlaceItemInContainer(target, stack, slot, side)) {
            return stack;
        }
        boolean changed = false;
        boolean wasEmpty = target.isEmpty();
        if (existing.isEmpty()) {
            target.setItem(slot, stack);
            stack = ItemStack.EMPTY;
            changed = true;
        } else if (canMergeItems(existing, stack)) {
            int limit = stack.getMaxStackSize() - existing.getCount();
            int amount = Math.min(stack.getCount(), limit);
            stack.shrink(amount);
            existing.grow(amount);
            changed = amount > 0;
        }
        if (changed) {
            if (wasEmpty && target instanceof OmniHopperBlockEntity other && !other.isOnCustomCooldown()) {
                int offset = 0;
                if (source instanceof OmniHopperBlockEntity sourceHopper
                        && other.tickedGameTime >= sourceHopper.tickedGameTime) {
                    offset = 1;
                }
                other.setCooldown(8 - offset);
            }
            target.setChanged();
        }
        return stack;
    }

    private static boolean canMergeItems(ItemStack a, ItemStack b) {
        return a.getCount() <= a.getMaxStackSize() && ItemStack.isSameItemSameTags(a, b);
    }

    private static IntStream getSlots(Container container, Direction side) {
        return container instanceof WorldlyContainer worldly
                ? IntStream.of(worldly.getSlotsForFace(side))
                : IntStream.range(0, container.getContainerSize());
    }

    private static boolean isFullContainer(Container container, Direction side) {
        return getSlots(container, side).allMatch(slot -> {
            ItemStack stack = container.getItem(slot);
            return stack.getCount() >= stack.getMaxStackSize();
        });
    }

    private static boolean isEmptyContainer(Container container, Direction side) {
        return getSlots(container, side).allMatch(slot -> container.getItem(slot).isEmpty());
    }

    public static List<ItemEntity> getItemsAtAndAbove(Level level, Hopper hopper) {
        return hopper.getSuckShape().toAabbs().stream()
                .flatMap(box -> level.getEntitiesOfClass(ItemEntity.class,
                        box.move(hopper.getLevelX() - 0.5D, hopper.getLevelY() - 0.5D, hopper.getLevelZ() - 0.5D),
                        EntitySelector.ENTITY_STILL_ALIVE).stream())
                .collect(Collectors.toList());
    }

    @Nullable
    public static Container getContainerAt(Level level, BlockPos pos) {
        return getContainerAt(level, (double) pos.getX() + 0.5D, (double) pos.getY() + 0.5D, (double) pos.getZ() + 0.5D);
    }

    @Nullable
    private static Container getContainerAt(Level level, double x, double y, double z) {
        Container container = null;
        BlockPos pos = BlockPos.containing(x, y, z);
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof WorldlyContainerHolder holder) {
            container = holder.getContainer(state, level, pos);
        } else if (state.hasBlockEntity()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Container asContainer) {
                container = asContainer;
                if (container instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                        && block instanceof ChestBlock chestBlock) {
                    container = ChestBlock.getContainer(chestBlock, state, level, pos, true);
                }
            }
        }
        if (container == null) {
            List<Entity> entities = level.getEntities((Entity) null,
                    new AABB(x - 0.5D, y - 0.5D, z - 0.5D, x + 0.5D, y + 0.5D, z + 0.5D),
                    EntitySelector.CONTAINER_ENTITY_SELECTOR);
            if (!entities.isEmpty()) {
                container = (Container) entities.get(level.random.nextInt(entities.size()));
            }
        }
        return container;
    }

    public static void entityInside(Level level, BlockPos pos, BlockState state, Entity entity,
                                    OmniHopperBlockEntity hopper) {
        if (entity instanceof ItemEntity itemEntity
                && Shapes.joinIsNotEmpty(
                        Shapes.create(itemEntity.getBoundingBox().move(-pos.getX(), -pos.getY(), -pos.getZ())),
                        hopper.getSuckShape(), BooleanOp.AND)) {
            tryMoveItems(level, pos, state, hopper, () -> addItem(hopper, itemEntity));
        }
    }
}
