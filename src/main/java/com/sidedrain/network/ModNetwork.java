package com.sidedrain.network;

import com.sidedrain.SideDrain;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Tiny network channel: carry-a-block requests and the resulting sync. */
public final class ModNetwork {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(SideDrain.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private static int nextId;

    public static void register() {
        CHANNEL.messageBuilder(CarryRequestPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CarryRequestPacket::encode)
                .decoder(CarryRequestPacket::decode)
                .consumerMainThread(CarryRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(WrenchLeftClickPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(WrenchLeftClickPacket::encode)
                .decoder(WrenchLeftClickPacket::decode)
                .consumerMainThread(WrenchLeftClickPacket::handle)
                .add();

        CHANNEL.messageBuilder(SetTargetSlotPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetTargetSlotPacket::encode)
                .decoder(SetTargetSlotPacket::decode)
                .consumerMainThread(SetTargetSlotPacket::handle)
                .add();

        CHANNEL.messageBuilder(CarrySyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CarrySyncPacket::encode)
                .decoder(CarrySyncPacket::decode)
                .consumerMainThread(CarrySyncPacket::handle)
                .add();
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }

    public static void sendToPlayer(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** Tells everyone who can see this player that they are carrying {@code stack}. */
    public static void sendToTracking(ServerPlayer player, Object message) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), message);
    }

    private ModNetwork() {
    }
}
