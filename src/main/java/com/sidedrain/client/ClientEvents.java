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

    /**
     * Handles the mod's own key bindings.
     *
     * <p>{@code ClientTickEvent} is the right hook: {@code KeyMapping.consumeClick()} has to be
     * drained every tick or the press queue grows, and the tick runs whether or not a screen is
     * open. The screen check is explicit because a bound key still fires while a container is open,
     * and rotating or lifting a block from inside a chest screen is not something the player can
     * mean.
     */
    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        boolean usable = minecraft.screen == null && !minecraft.player.isSpectator();
        // Drain both queues unconditionally: a click left over from a screen is discarded rather
        // than firing the moment the screen closes.
        boolean rotate = ModKeys.ROTATE.consumeClick();
        boolean carry = ModKeys.CARRY.consumeClick();
        if (!usable) {
            return;
        }
        if (rotate) {
            rotateUnderCursor(minecraft);
        }
        if (carry) {
            // The same action as the sneak + middle click gesture, minus the sneak requirement: a
            // bound key is already an explicit intent. Both end in CarryHandler's shared body, so
            // they can never behave differently.
            CarryHandler.onBoundKey();
        }
    }

    /**
     * Sends the wrench's spin request for whatever the player is looking at.
     *
     * <p>Separate from the sneak + left click path because that one has to cancel a vanilla attack;
     * this one has no vanilla action to suppress, so it only has to send the packet. The same
     * throttle applies, so holding the key turns a block at a readable rate instead of one packet
     * per frame.
     */
    private static void rotateUnderCursor(Minecraft minecraft) {
        if (!(minecraft.player.getMainHandItem().getItem()
                instanceof com.sidedrain.item.OrientationWrenchItem)) {
            return;
        }
        if (minecraft.hitResult == null || minecraft.hitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockHitResult hit = (BlockHitResult) minecraft.hitResult;
        if (!sendRotation(minecraft, hit, com.sidedrain.network.WrenchLeftClickPacket.SPIN)) {
            return;
        }
    }

    /**
     * Sneak + middle click carries a block, as it always has.
     *
     * <p>This is the original gesture and it stays: the {@code R} binding is an addition, not a
     * replacement. Both reach {@link CarryHandler#onPickBlock()}, so they cannot behave differently.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPickBlock(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isPickBlock()) {
            return;
        }
        if (CarryHandler.onPickBlock()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /**
     * Sneak + left click spins the block with the wrench, as it always has.
     *
     * <p>The {@code G} binding does the same thing without sneaking; this path keeps the original
     * gesture alive and cancels the vanilla attack so the block is not mined instead.
     */
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
        sendRotation(minecraft, hit, com.sidedrain.network.WrenchLeftClickPacket.SPIN);
        event.setCanceled(true);
        event.setSwingHand(true);
    }

    /** True when the wrench is in the main or the off hand, which is what "holding it" means. */
    private static boolean wrenchInHand(Minecraft minecraft) {
        if (minecraft.player == null) {
            return false;
        }
        return minecraft.player.getMainHandItem().getItem()
                instanceof com.sidedrain.item.OrientationWrenchItem
                || minecraft.player.getOffhandItem().getItem()
                instanceof com.sidedrain.item.OrientationWrenchItem;
    }

    /**
     * Sends the wrench request for the targeted block.
     *
     * @return true when a packet was sent
     */
    private static boolean sendRotation(Minecraft minecraft, BlockHitResult hit, byte axis) {
        long now = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        if (now - lastWrenchClick < 4L) {
            return false;
        }
        lastWrenchClick = now;
        com.sidedrain.network.ModNetwork.sendToServer(
                new com.sidedrain.network.WrenchLeftClickPacket(hit.getBlockPos(), axis));
        return true;
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
        // The plates clamped onto the carried block are drawn in the same block-centred space, so
        // they sit exactly where they were standing and follow the hand on the same frame.
        com.sidedrain.client.CarryPlateRender.drawCarriedPlates(
                com.sidedrain.client.CarryHandler.gloveInHand(minecraft.player), poseStack,
                event.getMultiBufferSource(), event.getPackedLight(), level);
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
