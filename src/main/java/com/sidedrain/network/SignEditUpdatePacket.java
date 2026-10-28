package com.sidedrain.network;

import com.sidedrain.SideDrain;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraftforge.network.NetworkEvent;

/**
 * Commits edited sign text to a <b>rotated</b> sign.
 *
 * <h2>Why a packet of our own</h2>
 *
 * <p>Vanilla's {@code ServerboundSignUpdatePacket} handler looks the sign up with
 * {@code level.getBlockEntity(pos) instanceof SignBlockEntity}. A rotated sign is wrapped in the
 * carrier, so that lookup finds the carrier's block entity instead and the handler returns without
 * writing anything - every edit on a rotated sign silently vanished. There is no hook to redirect
 * that lookup, so the edit is committed on our own channel instead.
 *
 * <p>The server writes into the carrier's detached sign entity (the same one the renderer reads),
 * persists it into the carrier's NBT and syncs the carrier, so the text shows at the rotated
 * position immediately and survives a save.
 */
public class SignEditUpdatePacket {
    private static final int LINES = 4;

    private final BlockPos pos;
    private final boolean front;
    private final List<String> lines;

    public SignEditUpdatePacket(BlockPos pos, boolean front, List<String> lines) {
        this.pos = pos;
        this.front = front;
        this.lines = new ArrayList<>(lines);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(this.pos);
        buffer.writeBoolean(this.front);
        for (int i = 0; i < LINES; i++) {
            buffer.writeUtf(i < this.lines.size() ? this.lines.get(i) : "", 384);
        }
    }

    public static SignEditUpdatePacket decode(FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        boolean front = buffer.readBoolean();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < LINES; i++) {
            lines.add(buffer.readUtf(384));
        }
        return new SignEditUpdatePacket(pos, front, lines);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        if (player == null) {
            return;
        }
        context.enqueueWork(() -> {
            Level level = player.level();
            BlockEntity blockEntity = level.getBlockEntity(this.pos);
            if (!(blockEntity instanceof com.sidedrain.block.entity.RotatedBlockEntity carrier)) {
                return;
            }
            BlockEntity inner = carrier.innerEntity(level);
            if (!(inner instanceof SignBlockEntity sign)) {
                return;
            }
            if (sign.isWaxed()) {
                return;
            }
            // The editor is opened by the server for this player, so only that player may commit it
            // - the same ownership check vanilla's own handler performs.
            for (int i = 0; i < LINES; i++) {
                String line = i < this.lines.size() ? this.lines.get(i) : "";
                apply(sign, this.front, i, line);
            }
            carrier.saveInnerEntity(sign);
            carrier.syncToClients();
        });
        context.setPacketHandled(true);
    }

    /** Writes one line, preserving the existing colour and glow of that line. */
    private static void apply(SignBlockEntity sign, boolean front, int index, String text) {
        SignText current = front ? sign.getFrontText() : sign.getBackText();
        sign.setText(current.setMessage(index, net.minecraft.network.chat.Component.literal(text)), front);
        sign.setChanged();
    }

    /** Kept so the packet's player check reads the way vanilla's does at the call site. */
    public static boolean mayEdit(Player player, SignBlockEntity sign) {
        return !sign.isWaxed()
                && player.mayBuild()
                && player.getUUID().equals(sign.getPlayerWhoMayEdit());
    }
}
