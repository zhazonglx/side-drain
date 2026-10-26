package com.sidedrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.sidedrain.SideDrain;
import javax.annotation.Nullable;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Third person view of a carried block, for every player holding one.
 *
 * <p>The block is drawn <b>once</b>, at full block size, hugged against the chest by both arms.
 *
 * <h2>Why the previous version drew nothing</h2>
 *
 * <p>The block used to be placed with {@code translate(0, 1.501, 0)} followed by
 * {@code rotateY(180)} and {@code translate(0, 0.62, -0.32)}. That assumed the pose stack at
 * {@link RenderPlayerEvent.Post} was still inside the model transform. It is not. The real call
 * chain is:
 *
 * <pre>
 * EntityRenderDispatcher.render : pushPose, translate(entity position)      &lt;- world aligned
 *   PlayerRenderer.render       : setModelProperties, fire RenderPlayerEvent.Pre
 *     LivingEntityRenderer      : pushPose
 *                                 rotateY(180 - bodyRot)                    &lt;- body facing
 *                                 scale(-1, -1, 1)
 *                                 scale(0.9375)                             &lt;- player is 15/16
 *                                 translate(0, -1.501, 0)                   &lt;- feet to model root
 *                                 setupAnim + renderToBuffer
 *                                 popPose                                   &lt;- all of it undone
 *     PlayerRenderer.render     : fire RenderPlayerEvent.Post
 * </pre>
 *
 * <p>So {@code Post} fires back at the <b>entity origin, world aligned and unscaled</b>. The old
 * numbers were a mixture of model-space units (the {@code 1.501}) and world blocks, applied in the
 * wrong order and without the 0.9375 player scale. The result sat about two blocks up in the air and
 * was rotated the wrong way, which is why the third person view looked like nothing was being
 * carried.
 *
 * <h2>What this does instead</h2>
 *
 * <p>Because {@code Post} hands over a clean, world-aligned space whose units really are blocks and
 * whose Y really is up, the block is simply placed there directly:
 *
 * <ol>
 *   <li>rotate by {@code -bodyRot} so the following offsets are in the player's own frame,</li>
 *   <li>move to chest height and one step forward,</li>
 *   <li>turn to face away from the player and tip forward slightly,</li>
 *   <li>draw the block centred on the origin at full size.</li>
 * </ol>
 *
 * <p>No scale compensation is needed because none of the model transform is active here, and no
 * sign flips are needed because this space was never flipped. The placement also inherits the walk
 * bob and the body turn, so the block moves with the player instead of hanging in the air.
 *
 * <p>The arms are posed separately in {@link RenderLivingEvent.Pre}, which is the only hook that
 * runs after {@code setModelProperties} and before {@code setupAnim}, so the hugging pose survives
 * and still gets the walk swing.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = SideDrain.MODID, value = Dist.CLIENT)
public final class CarryRenderer {
    /** Height of the block's centre above the player's feet, in blocks. Chest height. */
    private static final double HOLD_Y = 0.95D;
    /**
     * How far in front of the player's centre the block sits, in blocks.
     *
     * <p>The torso is about 0.25 blocks deep, so its front face is at {@code z = 0.125}. A full
     * block centred here reaches back to {@code z = 0.22}, which leaves a small gap instead of the
     * block being buried inside the player, and still puts its front face within arm's reach.
     */
    private static final double HOLD_Z = 0.72D;
    /**
     * Tip towards the chest, so it reads as being hugged rather than levitating.
     *
     * <p>Negative on purpose. The block sits in front of the player, so the player is on its
     * {@code -Z} side and the top has to lean <b>back</b> towards them; a positive angle would tip
     * the top away and the block would look like it was sliding off.
     */
    private static final float TILT_X = -12.0F;
    /** Vertical bob amplitude while walking, in blocks. */
    private static final float BOB_Y = 0.035F;
    /** Sideways sway amplitude while walking, in blocks. */
    private static final float SWAY_X = 0.02F;
    /** Extra lean towards the chest while the player crouches, in degrees. */
    private static final float CROUCH_TILT = -14.0F;

    /**
     * Forces the arms into the hugging pose.
     *
     * <p>{@link RenderLivingEvent.Pre} is used rather than {@link RenderPlayerEvent.Pre} because it
     * fires after {@code PlayerRenderer.setModelProperties()} has already overwritten the arm poses
     * from the held items, and before {@code setupAnim} turns them into rotations.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderLivingPre(
            RenderLivingEvent.Pre<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
            return;
        }
        if (!player.isAlive() || player.isInvisible() || CarryHandler.carriedBy(player).isEmpty()) {
            return;
        }
        PlayerModel<AbstractClientPlayer> model = event.getRenderer().getModel();
        model.rightArmPose = HumanoidModel.ArmPose.BLOCK;
        model.leftArmPose = HumanoidModel.ArmPose.BLOCK;
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Post event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
            return;
        }
        if (!player.isAlive() || player.isInvisible()) {
            return;
        }
        CarryHandler.Carried carried = CarryHandler.carriedBy(player);
        if (carried.isEmpty()) {
            return;
        }
        ItemStack carriedStack = carried.display();
        BlockState state = carried.state() != null ? carried.state() : blockStateFor(carriedStack);
        if (state == null && carriedStack.isEmpty()) {
            return;
        }
        // One draw per player per frame: the event can fire more than once for the same player.
        if (!CarryRenderState.firstPlayerPass(player.getId())) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource buffers = event.getMultiBufferSource();
        float partialTick = event.getPartialTick();

        // The body yaw, interpolated exactly the way LivingEntityRenderer does it, so the block
        // turns with the torso rather than snapping between the twenty ticks a second.
        float bodyRot = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        // Walk animation drives a small bob and sway so the block is not glued to a fixed point.
        // walkAnimation.position() is the raw limb-swing counter the vanilla model feeds straight
        // into cos(limbSwing * 0.6662), so the same factor is used here to stay in step with the legs.
        float walk = Mth.clamp(player.walkAnimation.speed(partialTick), 0.0F, 1.0F);
        float limbSwing = player.walkAnimation.position(partialTick) * 0.6662F;
        double bob = Mth.cos(limbSwing * 2.0F) * BOB_Y * walk;
        double sway = Mth.cos(limbSwing) * SWAY_X * walk;

        poseStack.pushPose();
        // World aligned -> player aligned. After this the local +Z axis points where the player
        // is looking and the local Y axis still points up.
        poseStack.mulPose(Axis.YP.rotationDegrees(-bodyRot));
        poseStack.translate(sway, HOLD_Y + bob, HOLD_Z);
        // No yaw here: a block model's north face is its -Z face, and the player is looking at the
        // block from -Z, so the block's front (a chest's latch, a furnace's opening) already faces
        // the player - which is what you see when you hug a crate.
        // A crouching player leans over the block, so it tips with them instead of floating level.
        float crouch = player.isCrouching() ? 1.0F : 0.0F;
        poseStack.mulPose(Axis.XP.rotationDegrees(TILT_X + CROUCH_TILT * crouch));
        CarryBlockRender.draw(state, carriedStack, poseStack, buffers, event.getPackedLight(),
                player.level(), true);
        poseStack.popPose();
    }

    /** The block to draw when the glove carries no block state of its own. */
    @Nullable
    private static BlockState blockStateFor(ItemStack stack) {
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
            return blockItem.getBlock().defaultBlockState();
        }
        return null;
    }

    private CarryRenderer() {
    }
}
