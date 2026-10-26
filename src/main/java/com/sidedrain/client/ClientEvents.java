package com.sidedrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.sidedrain.SideDrain;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client side glue: sneak + middle click to carry a block, and the first person drawing of it. */
@Mod.EventBusSubscriber(modid = SideDrain.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    /** Throttles the shift + left click rotation so holding the button does not spam packets. */
    private static long lastWrenchClick = Long.MIN_VALUE / 2;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isPickBlock()) {
            return;
        }
        if (CarryHandler.onPickBlock()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLeftClick(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.isShiftKeyDown()) {
            return;
        }
        if (!(minecraft.player.getMainHandItem().getItem() instanceof com.sidedrain.item.OrientationWrenchItem)) {
            return;
        }
        if (minecraft.hitResult == null || minecraft.hitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockHitResult hit = (BlockHitResult) minecraft.hitResult;
        long now = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        if (now - lastWrenchClick < 4L) {
            event.setCanceled(true);
            event.setSwingHand(false);
            return;
        }
        lastWrenchClick = now;
        com.sidedrain.network.ModNetwork.sendToServer(
                new com.sidedrain.network.WrenchLeftClickPacket(hit.getBlockPos()));
        event.setCanceled(true);
        event.setSwingHand(true);
    }

    /**
     * Draws the carried block once, at full size, hugged against the bottom of the screen by both
     * hands. Both hand passes are cancelled so the vanilla item-in-hand rendering never appears on
     * top of it.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderHand(RenderHandEvent event) {
        // Resolved from the glove actually in hand, so swapping the main hand swaps what is drawn on
        // the very same frame instead of waiting for the next sync.
        CarryHandler.Carried carried = CarryHandler.carriedByLocalPlayer();
        if (carried.isEmpty()) {
            return;
        }
        // Whatever the hand, the vanilla hand item must not be drawn.
        event.setCanceled(true);
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return;
        }
        // Draw once per frame, not once per hand.
        if (!CarryRenderState.firstHandPass()) {
            return;
        }
        ItemStack carriedStack = carried.display();
        BlockState state = carried.state() != null ? carried.state() : blockStateFor(carriedStack);
        if (state == null && carriedStack.isEmpty()) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        // Held low and centred, the way two hands would cradle a crate in front of the player.
        poseStack.translate(0.0D, -0.42D, -0.72D);
        poseStack.mulPose(Axis.XP.rotationDegrees(10.0F));
        // CarryBlockRender shifts the MODEL path by half a block itself, so the origin here is the
        // centre of the carried block. The old code shifted unconditionally and then used the
        // FIRST_PERSON item pose for the fallback, which is why a shulker box came out half size.
        CarryBlockRender.draw(state, carriedStack, poseStack, event.getMultiBufferSource(),
                event.getPackedLight(), level, true);
        poseStack.popPose();
    }

    /** The block to draw when the glove carries no block state of its own. */
    private static BlockState blockStateFor(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
            return blockItem.getBlock().defaultBlockState();
        }
        return null;
    }

    private ClientEvents() {
    }
}
