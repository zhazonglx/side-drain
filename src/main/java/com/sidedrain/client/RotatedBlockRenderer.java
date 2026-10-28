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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Draws the animated wrapped blocks of a carrier - chests, beds, signs, shulker boxes.
 *
 * <h2>Why a plain model is not drawn here</h2>
 *
 * <p>A {@link RenderShape#MODEL} block is now drawn by the <b>chunk mesher</b>, not by a block entity
 * renderer. {@code RotatedBlock} reports {@code MODEL}, and {@link RotatedBlockModel} returns the
 * wrapped block's quads already rotated, so the carrier goes through exactly the same mesh path as an
 * ordinary block. That is what fixes the lighting: the lightmap, the ambient-occlusion pass and the
 * shadow map are all produced while a section is meshed, and no amount of re-tessellating from a
 * render pass can reproduce them faithfully.
 *
 * <p>Drawing it here used to also route the geometry through the block entity shader phase, where a
 * shader pack sends block <i>models</i> to its block-entity program - a program with no terrain
 * lightmap - which is why rotated blocks once rendered solid black.
 *
 * <h2>What is still drawn here</h2>
 *
 * <p>Genuinely animated shapes have no static-quad equivalent, so they keep their own renderer and
 * keep using this pass, which is what it exists for: a chest whose lid moves, a sign's text, a bed's
 * two-part model. Those were never affected by the lighting problems - they are block entities, and
 * the block entity pass renders them correctly.
 *
 * <p>The pose maths is shared with the rotated collision shape, so what is drawn and what is walked
 * into stay together.
 */
@OnlyIn(Dist.CLIENT)
public class RotatedBlockRenderer implements BlockEntityRenderer<RotatedBlockEntity> {
    public RotatedBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(RotatedBlockEntity entity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        BlockState inner = entity.getInnerState();
        if (inner.isAir()) {
            return;
        }
        // A plain model is NOT drawn here any more.
        //
        // RotatedBlock now reports RenderShape.MODEL, so the chunk mesher draws the block itself,
        // using the quads RotatedBlockModel returns already rotated. That is what makes the carrier
        // light, occlude and shade exactly like an ordinary block - see RotatedBlockModel for why
        // drawing it from this pass could not reproduce the mesher's lighting and produced a darker
        // block, a wrong AO gradient and flicker.
        //
        // Only a genuinely animated shape (a chest, a bed, a sign, a shulker box) still needs a block
        // entity renderer, because its appearance is not expressible as static quads.
        if (inner.getRenderShape() == RenderShape.MODEL) {
            return;
        }
        BlockState wrapper = entity.getBlockState();
        int yaw = wrapper.getValue(RotatedBlock.YAW) * 45;
        int pitch = wrapper.getValue(RotatedBlock.PITCH) * 45;
        if (yaw == 0 && pitch == 0) {
            // Unrotated: draw it exactly where it would have been.
            this.drawAnimated(inner, entity, poseStack, buffers, packedLight, partialTick);
            return;
        }
        // A bed is one object two blocks long, so both halves turn about the middle of the whole
        // bed - the same world point, expressed in each half's own local frame. Rotating each half
        // about its own centre is what scissored the pair into a diamond.
        double pivotX = 0.5D, pivotY = 0.5D, pivotZ = 0.5D;
        if (inner.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            net.minecraft.core.Direction bedFacing =
                    inner.getValue(net.minecraft.world.level.block.BedBlock.FACING);
            // FACING runs foot -> head, so the pair centre sits half a block from this half's
            // centre towards the OTHER half: the head steps along -FACING, the foot along +FACING.
            boolean head = inner.getValue(net.minecraft.world.level.block.BedBlock.PART)
                    == net.minecraft.world.level.block.state.properties.BedPart.HEAD;
            double step = head ? -0.5D : 0.5D;
            pivotX += bedFacing.getStepX() * step;
            pivotY += bedFacing.getStepY() * step;
            pivotZ += bedFacing.getStepZ() * step;
        }
        poseStack.pushPose();
        // Translate the pivot onto the origin, rotate there, and put it back. Written this way the
        // two translates are the same numbers, so the pivot is unambiguous even though the pose
        // stack's y is inverted relative to block space (translate(0,1,0) is the top of the block).
        poseStack.translate(pivotX, pivotY, pivotZ);
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.translate(-pivotX, -pivotY, -pivotZ);
        this.drawAnimated(inner, entity, poseStack, buffers, packedLight, partialTick);
        poseStack.popPose();
    }

    /** Draws an animated wrapped state, falling back to nothing when it has no renderer. */
    private void drawAnimated(BlockState inner, RotatedBlockEntity carrier, PoseStack poseStack,
                              MultiBufferSource buffers, int packedLight, float partialTick) {
        if (inner.getRenderShape() == RenderShape.MODEL) {
            return;
        }
        this.drawViaRenderer(inner, carrier, poseStack, buffers, packedLight, partialTick);
    }

    /**
     * Draws the wrapped block through its own block entity renderer.
     *
     * <p>{@code carrier.innerEntity(level)} is a live detached copy of the wrapped block entity,
     * already loaded with the carrier's stored NBT, so a rotated chest or a rotated sign renders
     * with its own contents and text instead of a generic default.
     */
    private boolean drawViaRenderer(BlockState inner, RotatedBlockEntity carrier, PoseStack poseStack,
                                    MultiBufferSource buffers, int packedLight, float partialTick) {
        if (!(inner.getBlock() instanceof net.minecraft.world.level.block.EntityBlock)) {
            return false;
        }
        // A bed has to be drawn by a renderer that sees the BED state, not the carrier.
        //
        // BedRenderer reads BedBlock.PART and BedBlock.FACING off whatever getBlockState() returns
        // on the entity, and it resolves the pair through level.getBlockState(getBlockPos()). The
        // detached entity is positioned at the carrier and its state is the carrier's, so vanilla's
        // renderer would read a state with no PART property - the two halves then each drew a whole
        // bed and the pair came apart. A bed therefore goes through {@link #drawBed}, which is the
        // same model with the state and position it actually needs.
        if (inner.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            return drawBed(inner, carrier, poseStack, buffers, packedLight, partialTick);
        }
        // A block entity renderer that reads its level (a bed looks up its other half) needs a
        // non-null one, and the carrier can be rendered while detached - in a blueprint preview, a
        // structure block view, or the frame the chunk is still loading. Falling through keeps it
        // visible instead of throwing out of the render pass.
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

    /**
     * Draws one half of a bed using the bed state the carrier wraps.
     *
     * <p>This mirrors {@code BedRenderer#renderPiece} exactly - same layer, same pose, same texture
     * lookup - because the geometry is vanilla's and must not be approximated. What it does not do
     * is the pair lookup: each carrier already knows which half it is and which way the bed faces,
     * so the head and foot are drawn independently and stay aligned through the shared FACING.
     *
     * <p>Drawing the halves separately is what keeps a rotated bed together: both carriers hold the
     * same bed facing, so the two halves are laid out around the same anchor no matter how far the
     * pair has been tilted.
     */
    private boolean drawBed(BlockState bed, RotatedBlockEntity carrier, PoseStack poseStack,
                            MultiBufferSource buffers, int packedLight, float partialTick) {
        if (!(carrier.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel)) {
            return false;
        }
        net.minecraft.world.level.block.entity.BedBlockEntity bedEntity =
                new net.minecraft.world.level.block.entity.BedBlockEntity(
                        carrier.getBlockPos(), bed);
        bedEntity.setLevel(carrier.getLevel());
        BlockEntityRenderer<BlockEntity> renderer = rendererFor(bedEntity);
        if (renderer == null) {
            return false;
        }
        // The renderer is given an entity whose state and position are the BED's, so its internal
        // PART/FACING reads and its getBlockPos() are correct. The pair lookup it performs through
        // the level is harmless: it only affects the brightness of the two halves.
        renderer.render(bedEntity, partialTick, poseStack, buffers, packedLight, OverlayTexture.NO_OVERLAY);
        return true;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static BlockEntityRenderer<BlockEntity> rendererFor(BlockEntity wrapped) {
        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        return (BlockEntityRenderer<BlockEntity>) dispatcher.getRenderer(wrapped);
    }

    /** Drops the model caches; call when models are reloaded. */
    public static void clearCaches() {
        RotatedBlockModel.clearCaches();
    }
}
