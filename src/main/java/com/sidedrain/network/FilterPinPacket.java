package com.sidedrain.network;

import com.sidedrain.block.entity.SideDrainBlockEntity;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

/**
 * Every filter-grid gesture, in one packet.
 *
 * <h2>Why the filter grid is not a set of slots</h2>
 *
 * <p>A filter cell records an item; it does not hold one. Making the cells real {@code Slot}s made
 * vanilla's click handling treat the recorded stack as an item that could be picked up, shift-clicked
 * into storage or drag-split - every one of those is a free item the plate never had. The cells are
 * therefore drawn by the screen and reported here by coordinate, which keeps the whole grid outside
 * vanilla's inventory logic.
 *
 * <p>Three gestures:
 * <ul>
 *   <li>{@link #CLICK} - the player clicked a cell. A cell with a pin loses it; an empty cell takes
 *       a copy of whatever is on the cursor.</li>
 *   <li>{@link #FROM_VIEWER} - the recipe-viewer shortcut: pin the item the pointer was over.</li>
 *   <li>{@link #CLEAR} - empty the whole grid.</li>
 * </ul>
 */
public class FilterPinPacket {
    /** Click a cell: clear the pin, or record the cursor's item. */
    public static final int CLICK = 0;
    /** Pin the item the pointer was over in a recipe viewer. */
    public static final int FROM_VIEWER = 1;
    /** Empty the whole grid. */
    public static final int CLEAR = 2;

    private final BlockPos pos;
    private final int action;
    private final int cell;
    private final ItemStack stack;

    private FilterPinPacket(BlockPos pos, int action, int cell, ItemStack stack) {
        this.pos = pos;
        this.action = action;
        this.cell = cell;
        this.stack = stack;
    }

    /** A click on cell {@code cell}. The server reads the cursor itself. */
    public static FilterPinPacket click(BlockPos pos, int cell) {
        return new FilterPinPacket(pos, CLICK, cell, ItemStack.EMPTY);
    }

    /** Pin {@code stack}, which the pointer was over in a recipe viewer. */
    public static FilterPinPacket fromViewer(BlockPos pos, ItemStack stack) {
        return new FilterPinPacket(pos, FROM_VIEWER, -1, stack);
    }

    /** Empty the whole grid. */
    public static FilterPinPacket clear(BlockPos pos) {
        return new FilterPinPacket(pos, CLEAR, -1, ItemStack.EMPTY);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(this.pos);
        buffer.writeByte(this.action);
        buffer.writeVarInt(this.cell);
        buffer.writeItem(this.stack);
    }

    public static FilterPinPacket decode(FriendlyByteBuf buffer) {
        return new FilterPinPacket(buffer.readBlockPos(), buffer.readByte(), buffer.readVarInt(),
                buffer.readItem());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }
        if (!player.level().mayInteract(player, this.pos)) {
            return;
        }
        if (player.distanceToSqr(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D,
                this.pos.getZ() + 0.5D) > 64.0D) {
            return;
        }
        BlockEntity entity = player.level().getBlockEntity(this.pos);
        if (!(entity instanceof SideDrainBlockEntity plate)) {
            return;
        }
        switch (this.action) {
            case CLICK -> {
                // A cell with a pin loses it. An empty cell records the cursor's item - or, when the
                // cursor is empty, does nothing at all, so a stray click cannot wipe a filter.
                if (!plate.clearFilterSlot(this.cell)) {
                    // The server's own copy of the carried stack, not the one in the packet: the
                    // client's version is only a hint, and a hand-crafted packet must not be able to
                    // pin an item the player is not actually holding.
                    ItemStack carried = player.containerMenu == null
                            ? ItemStack.EMPTY : player.containerMenu.getCarried();
                    if (!carried.isEmpty()) {
                        plate.setFilterSlot(this.cell, carried);
                    }
                }
            }
            // The stack is re-created with a count of one: a filter cell records an item, and a
            // client that sent a stack of sixty-four must not leave sixty-four items in it.
            case FROM_VIEWER -> {
                boolean pinned = plate.addFilterItem(this.stack.copyWithCount(1));
                // Answered out loud, because this gesture has no visible target: the player pressed a
                // key while pointing at an item somewhere on screen. Without a line in the action bar
                // there is no way to tell "recorded" from "the key did nothing", which is exactly the
                // report that started this. The message goes to the action bar so it cannot scroll the
                // chat away.
                if (pinned) {
                    player.displayClientMessage(Component.translatable("message.sidedrain.filter.added",
                            this.stack.copyWithCount(1).getHoverName()), true);
                } else if (plate.matchesFilter(this.stack)) {
                    player.displayClientMessage(Component.translatable("message.sidedrain.filter.already",
                            this.stack.copyWithCount(1).getHoverName()), true);
                } else {
                    player.displayClientMessage(Component.translatable("message.sidedrain.filter.full"),
                            true);
                }
            }
            case CLEAR -> plate.clearFilter();
            default -> {
            }
        }
    }
}
