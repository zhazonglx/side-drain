package com.sidedrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.sidedrain.block.RotatedBlock;
import com.sidedrain.block.entity.RotatedBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Draws the wrapped block tilted by the wrapper's yaw and pitch.
 *
 * <p>The block is rendered around the centre of its cell so a flower or a grass block visibly leans
 * or lies on its side without sliding out of the block space.
 *
 * <h2>Two render shapes, not one</h2>
 *
 * <p>Not every block is a static model. A bed, a chest, a shulker box, a sign and every
 * {@code ENTITYBLOCK_ANIMATED} block draw through their <b>block entity renderer</b>, and
 * {@code BedBlock.getRenderShape()} returns {@code ENTITYBLOCK_ANIMATED} rather than
 * {@code MODEL}. The renderer used to bail out on anything that was not {@code MODEL}, which is
 * exactly why a rotated bed kept its collision box but lost its model: there was no block model to
 * draw in the first place, and the block entity renderer was never invoked.
 *
 * <p>So the wrapped block is drawn the way the game itself would draw it:
 * <ul>
 *   <li>{@code MODEL} - baked into the chunk mesh normally, here through
 *       {@link BlockRenderDispatcher#renderSingleBlock}.</li>
 *   <li>{@code ENTITYBLOCK_ANIMATED} - through the wrapped block's own
 *       {@link BlockEntityRenderer}, built from the carrier's stored block entity data.</li>
 * </ul>
 * Both paths share the same pose, so the drawn block and the rotated collision shape stay together.
 */
@OnlyIn(Dist.CLIENT)
public class RotatedBlockRenderer implements BlockEntityRenderer<RotatedBlockEntity> {
    private final BlockRenderDispatcher dispatcher;

    public RotatedBlockRenderer(BlockEntityRendererProvider.Context context) {
        this.dispatcher = Minecraft.getInstance().getBlockRenderer();
    }

    @Override
    public void render(RotatedBlockEntity entity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        BlockState inner = entity.getInnerState();
        if (inner.isAir()) {
            return;
        }
        BlockState wrapper = entity.getBlockState();
        int yaw = wrapper.getValue(RotatedBlock.YAW) * 45;
        int pitch = wrapper.getValue(RotatedBlock.PITCH) * 45;
        if (yaw == 0 && pitch == 0) {
            // Unrotated: draw it exactly where it would have been.
            this.draw(inner, entity, poseStack, buffers, packedLight, partialTick);
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.5D, 0.5D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.translate(-0.5D, -0.5D, -0.5D);
        this.draw(inner, entity, poseStack, buffers, packedLight, partialTick);
        poseStack.popPose();
    }

    /**
     * Draws one wrapped state with whichever renderer the game would use for it.
     *
     * <p>The animated path falls back to the block model when the wrapped block has no block entity
     * renderer of its own, so a block that reports {@code ENTITYBLOCK_ANIMATED} but draws a plain
     * model is still visible instead of silently disappearing.
     */
    private void draw(BlockState inner, RotatedBlockEntity carrier, PoseStack poseStack,
                      MultiBufferSource buffers, int packedLight, float partialTick) {
        if (inner.getRenderShape() == RenderShape.MODEL) {
            this.dispatcher.renderSingleBlock(inner, poseStack, buffers, packedLight, OverlayTexture.NO_OVERLAY);
            return;
        }
        if (this.drawAnimated(inner, carrier, poseStack, buffers, packedLight, partialTick)) {
            return;
        }
        // A non-MODEL shape with no renderer: draw the model anyway rather than nothing at all.
        if (this.dispatcher.getBlockModel(inner) != null) {
            this.dispatcher.renderSingleBlock(inner, poseStack, buffers, packedLight, OverlayTexture.NO_OVERLAY);
        }
    }

    /**
     * Draws the wrapped block through its own block entity renderer.
     *
     * <p>{@code entity.innerEntity(level)} is a live detached copy of the wrapped block entity,
     * already loaded with the carrier's stored NBT, so a rotated chest or a rotated sign renders
     * with its own contents and text instead of a generic default.
     */
    private boolean drawAnimated(BlockState inner, RotatedBlockEntity carrier, PoseStack poseStack,
                                 MultiBufferSource buffers, int packedLight, float partialTick) {
        if (!(inner.getBlock() instanceof EntityBlock)) {
            return false;
        }
        // A block entity renderer that reads its level (a bed looks up its other half) needs a
        // non-null one, and the carrier can be rendered while detached - in a blueprint preview, a
        // structure block view, or the frame the chunk is still loading. Falling through to the
        // block model keeps it visible instead of throwing out of the render pass.
        if (carrier.getLevel() == null) {
            return false;
        }
        BlockEntity wrapped = carrier.innerEntity(carrier.getLevel());
        if (wrapped == null) {
            return false;
        }
        // The renderer looks the state up on the entity, so it has to see the wrapped state rather
        // than the carrier's own RotatedBlock state.
        wrapped.setLevel(carrier.getLevel());
        BlockEntityRenderer<BlockEntity> renderer = rendererFor(wrapped);
        if (renderer == null) {
            return false;
        }
        renderer.render(wrapped, partialTick, poseStack, buffers, packedLight, OverlayTexture.NO_OVERLAY);
        return true;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static BlockEntityRenderer<BlockEntity> rendererFor(BlockEntity wrapped) {
        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        return (BlockEntityRenderer<BlockEntity>) dispatcher.getRenderer(wrapped);
    }
}
