package com.sidedrain.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Small helper for the "is a modifier key held" questions the screens ask. */
@OnlyIn(Dist.CLIENT)
public final class ClientInput {
    public static boolean isAltDown() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getWindow() != null
                && net.minecraft.client.gui.screens.Screen.hasAltDown();
    }

    private ClientInput() {
    }
}
