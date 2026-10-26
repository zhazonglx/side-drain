package com.sidedrain.client;

import com.sidedrain.SideDrain;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Bumps the carry render frame counter once per rendered frame.
 *
 * <p>Hand and player rendering both need a "once per frame" guard, and the game time is the wrong
 * clock for it: it only ticks twenty times a second. This hook runs on every frame, so the guards in
 * {@link CarryRenderState} line up with what the player actually sees.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = SideDrain.MODID, value = Dist.CLIENT)
public final class CarryFrameEvents {
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            CarryRenderState.beginFrame();
        }
    }

    private CarryFrameEvents() {
    }
}
