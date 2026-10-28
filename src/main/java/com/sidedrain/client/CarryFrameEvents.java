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
        // AFTER_SKY, not AFTER_ENTITIES.
        //
        // The counter has to be fresh *before* entities are drawn, because that is when the carried
        // block is rendered. Bumping it afterwards meant the value seen during entity rendering was
        // still the previous frame's, and if the stage ever failed to fire the counter simply froze:
        // every player id stayed in the "already drawn" set, so the block was drawn exactly once and
        // then never again - which is what "third person shows nothing" looked like. AFTER_SKY is
        // reached on every frame before ENTITIES, so the guard can never get stuck.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            CarryRenderState.beginFrame();
        }
    }

    private CarryFrameEvents() {
    }
}
