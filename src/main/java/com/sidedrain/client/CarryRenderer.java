package com.sidedrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.sidedrain.SideDrain;
import javax.annotation.Nullable;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
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

    /**
     * Draws the carried block in third person, for every player including the local one.
     *
     * <h2>Which event, and why the transform is spelled out in full</h2>
     *
     * <p>{@code PlayerRenderer.render} is:
     *
     * <pre>
     * setModelProperties(player);
     * if (post(RenderPlayerEvent.Pre))  return;      &lt;- no transform applied yet
     * super.render(...)                              &lt;- LivingEntityRenderer, pushes and pops
     * post(RenderPlayerEvent.Post);                  &lt;- transform already popped
     * </pre>
     *
     * <p>So {@code Pre} fires at the <b>entity origin</b>, before the model transform exists, and
     * {@code Post} fires at the same place after it has been undone. Neither one hands over model
     * space - which is why placing the block "in the arms' coordinate system" never worked: the arms'
     * system is never exposed. {@code Post} is used here because it is the only point where the
     * player's own geometry has finished drawing, so the block cannot be occluded by the body.
     *
     * <p>The full transform that {@code LivingEntityRenderer.render} applies around the model is
     * therefore reproduced by hand, in the same order:
     *
     * <pre>
     * translate(0, crouchOffset, 0)
     * rotateY(180 - bodyRot)
     * scale(-1, -1, 1)
     * scale(0.9375)
     * translate(0, -1.501, 0)     &lt;- feet to model root
     * </pre>
     *
     * <p>After reproducing it, the origin really is at the model root and the axes really do match
     * the arms, so a chest-height offset in blocks puts the block where both hands meet.
     */
    @SubscribeEvent
    public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
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
        float partialTick = event.getPartialTick();

        // Walk animation drives a small bob and sway so the block is not glued to a fixed point.
        // walkAnimation.position() is the raw limb-swing counter the vanilla model feeds straight
        // into cos(limbSwing * 0.6662), so the same factor keeps this in step with the legs.
        // The body yaw, interpolated exactly the way LivingEntityRenderer does it, so the block
        // turns with the torso rather than snapping between the twenty ticks a second.
        float bodyRot = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        float walk = Mth.clamp(player.walkAnimation.speed(partialTick), 0.0F, 1.0F);
        float limbSwing = player.walkAnimation.position(partialTick) * 0.6662F;
        double bob = Mth.cos(limbSwing * 2.0F) * BOB_Y * walk;
        double sway = Mth.cos(limbSwing) * SWAY_X * walk;
        // A crouching player leans over the block, so it tips with them instead of floating level.
        float crouch = player.isCrouching() ? 1.0F : 0.0F;

        poseStack.pushPose();
        // At Pre the pose stack is at the entity origin: world aligned, unscaled, origin at the feet
        // and Y up. Blocks are drawn in exactly that space, so all that is needed is to turn to face
        // the way the body faces and step up to the chest.
        //
        // Do NOT reproduce LivingEntityRenderer's model transform here. That transform belongs to the
        // model draw, which has not happened yet at Pre and has already been popped at Post; applying
        // it by hand either cancels out (Post) or is applied twice (Pre), and either way the block
        // ends up mirrored and metres away from the player.
        poseStack.mulPose(Axis.YP.rotationDegrees(-bodyRot));
        // In this space +Z is the direction the player looks, because rotateY(-bodyRot) maps the
        // body's forward onto +Z. Chest height and one step forward, in blocks.
        poseStack.translate(sway, HOLD_Y + bob, HOLD_Z);
        // Tip the top towards the chest so it reads as hugged rather than levitating.
        poseStack.mulPose(Axis.XP.rotationDegrees(TILT_X + CROUCH_TILT * crouch));
        // CarryBlockRender centres the block on the origin and draws it at one block, so no scaling
        // and no unit conversion is needed: this space is already in blocks.
        CarryBlockRender.draw(state, carriedStack, poseStack, event.getMultiBufferSource(),
                event.getPackedLight(), player.level(), true);
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
