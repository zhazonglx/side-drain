package com.sidedrain.network;

import com.sidedrain.block.entity.SideDrainBlockEntity;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client tells the server "this slot is the one the plate should pull from".
 *
 * <p>Alt + click in the GUI sends this, which is how the Slot strategy is chosen without a
 * separate keybind.
 */
public class SetTargetSlotPacket {
    private final BlockPos pos;
    private final int slot;

    public SetTargetSlotPacket(BlockPos pos, int slot) {
        this.pos = pos;
        this.slot = slot;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(this.pos);
        buffer.writeVarInt(this.slot);
    }

    public static SetTargetSlotPacket decode(FriendlyByteBuf buffer) {
        return new SetTargetSlotPacket(buffer.readBlockPos(), buffer.readVarInt());
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
        if (player.distanceToSqr(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D) > 64.0D) {
            return;
        }
        BlockEntity entity = player.level().getBlockEntity(this.pos);
        if (entity instanceof SideDrainBlockEntity plate) {
            plate.setTargetSlot(this.slot);
            plate.setStrategy(SideDrainBlockEntity.STRATEGY_SLOT);
        }
    }
}
