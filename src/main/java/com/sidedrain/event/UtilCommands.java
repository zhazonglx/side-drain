package com.sidedrain.event;

import com.sidedrain.server.CarryManager;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Player-facing utility commands. */
public final class UtilCommands {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("sidedrain")
                .then(Commands.literal("release")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            CarryManager.forceRelease(player);
                            context.getSource().sendSuccess(
                                    () -> Component.translatable("message.sidedrain.released"), false);
                            return 1;
                        })));
    }

    private UtilCommands() {
    }
}
