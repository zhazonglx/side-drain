package com.sidedrain.network;

import com.sidedrain.client.CarryHandler;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server tells every nearby client whether a given player is carrying something, and what to draw.
 *
 * <p>Three things travel together because each covers a case the others cannot:
 * <ul>
 *   <li>{@code carrying} - the authoritative "hands are full" flag. It is separate from the stack on
 *       purpose: a Sophisticated Backpack is placed by an item with no matching BlockItem, so its
 *       display stack is empty. Deriving the state from the stack made the client believe its hands
 *       were free, so it never sent the put-down and the player was stuck forever.</li>
 *   <li>{@code stack} - what to draw when the thing has a usable item form.</li>
 *   <li>{@code state} - the block state, so a block with no item form can still be drawn as itself.</li>
 * </ul>
 */
public class CarrySyncPacket {
    private final UUID playerId;
    private final ItemStack stack;
    private final boolean carrying;
    private final CompoundTag state;

    public CarrySyncPacket(UUID playerId, ItemStack stack, boolean carrying) {
        this(playerId, stack, carrying, new CompoundTag());
    }

    public CarrySyncPacket(UUID playerId, ItemStack stack, boolean carrying, CompoundTag state) {
        this.playerId = playerId;
        this.stack = stack;
        this.carrying = carrying;
        this.state = state;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUUID(this.playerId);
        buffer.writeItem(this.stack);
        buffer.writeBoolean(this.carrying);
        buffer.writeNbt(this.state.isEmpty() ? null : this.state);
    }

    public static CarrySyncPacket decode(FriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        ItemStack stack = buffer.readItem();
        boolean carrying = buffer.readBoolean();
        CompoundTag state = buffer.readNbt();
        return new CarrySyncPacket(id, stack, carrying, state == null ? new CompoundTag() : state);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        UUID id = this.playerId;
        ItemStack stack = this.stack;
        boolean carrying = this.carrying;
        CompoundTag state = this.state;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CarryHandler.setCarried(id, stack, carrying, state));
    }
}
