package com.sidedrain.network;

import com.sidedrain.item.OrientationWrenchItem;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

/**
 * Shift + left click with the wrench rotates horizontally. The client cancels the attack before
 * the vanilla packet is built, so the request has to travel on its own channel.
 */
public class WrenchLeftClickPacket {
    private final BlockPos pos;

    public WrenchLeftClickPacket(BlockPos pos) {
        this.pos = pos;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(this.pos);
    }

    public static WrenchLeftClickPacket decode(FriendlyByteBuf buffer) {
        return new WrenchLeftClickPacket(buffer.readBlockPos());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }
        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(held.getItem() instanceof OrientationWrenchItem) || !player.isShiftKeyDown()) {
            return;
        }
        if (!player.level().mayInteract(player, this.pos)) {
            return;
        }
        double distance = player.distanceToSqr(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D);
        if (distance > 64.0D) {
            return;
        }
        OrientationWrenchItem.applyRotation(player.level(), this.pos, false);
    }
}
