package com.sidedrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Draws a carried block at its real, full block size - in the hands and on another player.
 *
 * <p>There are two ways a block can be drawn and they must both come out at the same size:
 *
 * <ul>
 *   <li>A block with a {@link RenderShape#MODEL} shape is drawn by the
 *       {@link BlockRenderDispatcher}, which always produces exactly one block. This covers stone,
 *       planks, grass, a Sophisticated Backpack's block form and most machines.</li>
 *   <li>A block whose model lives in a block entity renderer - a shulker box, a chest, a bed, a
 *       sign, a banner - is not drawn by the dispatcher at all. The previous code fell back to
 *       {@code ItemRenderer.renderStatic(..., FIRST_PERSON_RIGHT_HAND, ...)}, and that transform is
 *       the small "item in your hand" pose: a shulker box came out roughly half a block wide while
 *       every other block was full size. The fix is to use {@link ItemDisplayContext#FIXED}, the
 *       item frame pose, which draws the model at block scale centred on the origin.</li>
 * </ul>
 *
 * <p>Because {@code FIXED} already centres the model on the origin, the caller must place the origin
 * at the centre of where the block should sit. The {@code MODEL} path draws into the unit cube
 * {@code [0,1]}, so it is the one that needs the extra {@code (-0.5, -0.5, -0.5)} shift.
 */
@OnlyIn(Dist.CLIENT)
public final class CarryBlockRender {
    /**
     * Draws {@code state} (or, when it has no drawable model, {@code stack}) at the current origin.
     *
     * @param centreOnOrigin true when the caller has put the pose origin at the block's centre, in
     *                       which case the {@code MODEL} path is shifted by half a block to match
     *                       the {@code FIXED} path
     * @return true when something was drawn
     */
    public static boolean draw(@Nullable BlockState state, ItemStack stack, PoseStack poseStack,
                               MultiBufferSource buffers, int packedLight, @Nullable Level level,
                               boolean centreOnOrigin) {
        Minecraft minecraft = Minecraft.getInstance();
        if (state != null && state.getRenderShape() == RenderShape.MODEL) {
            if (centreOnOrigin) {
                poseStack.translate(-0.5D, -0.5D, -0.5D);
            }
            // Draw through the model's own render types rather than renderSingleBlock, which routes
            // every model through the ENTITY render type. A held block drawn on the entity path
            // loses block lightmap and shader block programs, so it looked flat and unlit next to
            // the world around it.
            BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
            net.minecraft.client.resources.model.BakedModel model = dispatcher.getBlockModel(state);
            var random = net.minecraft.util.RandomSource.create(42L);
            // Resolve the block colour here, once per draw.
            //
            // The vanilla renderModel path never consults BlockColors: its tint multipliers are the
            // raw R/G/B floats the caller passes, and the AO tesselation path - the one the world
            // uses - is what calls blockColors.getColor per quad. Passing 1,1,1 through renderModel
            // therefore drew every tinted quad untinted: a carried grass block or a carried leaves
            // block came out white, the same symptom the rotated carrier had before its own colour
            // delegation existed. The colour is asked from the state itself at a real position of
            // that state, so biome tints and modded handlers both resolve correctly.
            int tint = level != null
                    ? minecraft.getBlockColors().getColor(state, level, net.minecraft.core.BlockPos.ZERO, -1)
                    : minecraft.getBlockColors().getColor(state, null, null, -1);
            float red = (tint >> 16 & 0xFF) / 255.0F;
            float green = (tint >> 8 & 0xFF) / 255.0F;
            float blue = (tint & 0xFF) / 255.0F;
            for (net.minecraft.client.renderer.RenderType type
                    : model.getRenderTypes(state, random, net.minecraftforge.client.model.data.ModelData.EMPTY)) {
                dispatcher.getModelRenderer().renderModel(poseStack.last(), buffers.getBuffer(type), state,
                        model, red, green, blue, packedLight, OverlayTexture.NO_OVERLAY,
                        net.minecraftforge.client.model.data.ModelData.EMPTY, type);
            }
            return true;
        }
        // A block entity model, or a carried thing that has no block state at all (a modded
        // backpack placed as an entity). FIXED is the item frame pose: full block scale, centred.
        if (!stack.isEmpty()) {
            ItemRenderer itemRenderer = minecraft.getItemRenderer();
            itemRenderer.renderStatic(stack, ItemDisplayContext.FIXED, packedLight,
                    OverlayTexture.NO_OVERLAY, poseStack, buffers, level, 0);
            return true;
        }
        // Last resort: no item form but a state that is not MODEL. The dispatcher may still have
        // something to draw for the handful of blocks that report ENTITYBLOCK_ANIMATED with no
        // block entity renderer.
        if (state != null) {
            if (centreOnOrigin) {
                poseStack.translate(-0.5D, -0.5D, -0.5D);
            }
            minecraft.getBlockRenderer().renderSingleBlock(state, poseStack, buffers,
                    packedLight, OverlayTexture.NO_OVERLAY);
            return true;
        }
        return false;
    }

    private CarryBlockRender() {
    }
}
