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
 * A wrench click, sent on its own channel.
 *
 * <p>The client cancels the vanilla interaction before it is built - a left click would start
 * breaking the block and a right click would open the container - so the request has to travel
 * separately.
 *
 * <h2>Why the packet carries the axis</h2>
 *
 * <p>The two rotations used to be told apart by which vanilla input produced them: left click meant
 * spin, right click meant flip. That is no longer enough, because the player may bind either action
 * to either button - or to both. The axis therefore travels with the request, so the server does
 * exactly what the binding said rather than guessing from the button.
 */
public class WrenchLeftClickPacket {
    /** Flip the block over: rotate about the horizontal axis. */
    public static final byte PITCH = 0;
    /** Spin the block around: rotate about the vertical axis. */
    public static final byte SPIN = 1;

    private final BlockPos pos;
    private final byte axis;

    public WrenchLeftClickPacket(BlockPos pos, byte axis) {
        this.pos = pos;
        this.axis = axis;
    }

    /** Kept for the older call sites: a plain constructor request means a spin. */
    public WrenchLeftClickPacket(BlockPos pos) {
        this(pos, SPIN);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(this.pos);
        buffer.writeByte(this.axis);
    }

    public static WrenchLeftClickPacket decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        return new WrenchLeftClickPacket(pos, buffer.readByte());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }
        // The wrench may be in either hand: the binding is a gesture, not a main-hand rule.
        if (!holdsWrench(player)) {
            return;
        }
        // The sneak gesture keeps working, and the bound key is an addition rather than a
        // replacement, so a request is honoured whether or not the player is sneaking: the client
        // already decided that this click was a wrench action.
        if (!player.level().mayInteract(player, this.pos)) {
            return;
        }
        double distance = player.distanceToSqr(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D);
        if (distance > 64.0D) {
            return;
        }
        OrientationWrenchItem.applyRotation(player.level(), this.pos, this.axis == PITCH);
    }

    private static boolean holdsWrench(ServerPlayer player) {
        ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (main.getItem() instanceof OrientationWrenchItem) {
            return true;
        }
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        return off.getItem() instanceof OrientationWrenchItem;
    }
}
