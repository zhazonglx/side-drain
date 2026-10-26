package com.sidedrain.network;

import com.sidedrain.server.CarryManager;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client asks the server to lift a block, lift a placeable entity, or put the carried thing down.
 *
 * <p>The three operations share one packet because they are one gesture: the carry key. {@code pos}
 * is used for blocks and for the drop target, {@code entityId} for placeable entities.
 */
public class CarryRequestPacket {
    public static final int PICK_UP = 0;
    public static final int PUT_DOWN = 1;
    public static final int PICK_UP_ENTITY = 2;

    private final int action;
    private final BlockPos pos;
    private final int entityId;

    public CarryRequestPacket(int action, BlockPos pos) {
        this(action, pos, -1);
    }

    public CarryRequestPacket(int action, int entityId) {
        this(action, BlockPos.ZERO, entityId);
    }

    private CarryRequestPacket(int action, BlockPos pos, int entityId) {
        this.action = action;
        this.pos = pos;
        this.entityId = entityId;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(this.action);
        buffer.writeBlockPos(this.pos);
        buffer.writeVarInt(this.entityId);
    }

    public static CarryRequestPacket decode(FriendlyByteBuf buffer) {
        return new CarryRequestPacket(buffer.readVarInt(), buffer.readBlockPos(), buffer.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }
        switch (this.action) {
            case PICK_UP -> CarryManager.pickUp(player, this.pos);
            case PUT_DOWN -> CarryManager.putDown(player, this.pos);
            case PICK_UP_ENTITY -> CarryManager.pickUpEntity(player, this.entityId);
            default -> {
            }
        }
        context.setPacketHandled(true);
    }
}
