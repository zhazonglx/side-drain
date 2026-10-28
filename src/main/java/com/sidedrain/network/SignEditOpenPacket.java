package com.sidedrain.network;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Asks the client to open the sign editor for a <b>rotated</b> sign.
 *
 * <p>Vanilla's {@code ClientboundOpenSignEditorPacket} makes the client build its editor from the
 * block state at that position, which for a carrier is the carrier's own state - the wrong wood
 * type, the wrong layout, and on submit it sends the vanilla update packet the server has to ignore.
 * Opening the editor from our own packet lets the client build it from the wrapped sign state and
 * commit through {@link SignEditUpdatePacket} instead.
 */
public class SignEditOpenPacket {
    private final BlockPos pos;
    private final boolean front;

    public SignEditOpenPacket(BlockPos pos, boolean front) {
        this.pos = pos;
        this.front = front;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(this.pos);
        buffer.writeBoolean(this.front);
    }

    public static SignEditOpenPacket decode(FriendlyByteBuf buffer) {
        return new SignEditOpenPacket(buffer.readBlockPos(), buffer.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> com.sidedrain.client.RotatedSignEdit.open(this.pos, this.front));
        context.setPacketHandled(true);
    }
}
