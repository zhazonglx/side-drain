package com.sidedrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.sidedrain.block.SideDrainBlock;
import com.sidedrain.item.BlockGloveItem;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;

/**
 * Draws the Side Drain Plates that travel with a carried block or a carried plate.
 *
 * <h2>Where the plates come from</h2>
 *
 * <p>The plates are stored in the glove's own NBT - the same list the carry manager detaches on
 * pickup and reattaches on put-down - so the client can read them straight off the synced glove
 * stack with no round trip and no cache. That is what keeps the picture in step with the hand: swap
 * gloves and the plates swap on the same frame.
 *
 * <h2>How each plate is drawn</h2>
 *
 * <p>A plate is a thin slab clamped onto one face of the carried block, pointing along its own
 * aim. Both the position and the orientation come from the stored state, so a drawn plate sits
 * exactly where it was standing and points exactly where it was pointing:
 *
 * <ul>
 *   <li>position: the face centre of the host block, half a block out from the centre,</li>
 *   <li>orientation: turned onto that face, then flipped so the nozzle points away from the host,</li>
 *   <li>aim: the plate's own model already encodes the 45 degree nozzle tilt, so a plate tilted 45
 *       degrees visually points 45 degrees with no extra maths here.</li>
 * </ul>
 *
 * <p>The draw itself goes through the plate's model render types rather than
 * {@code renderSingleBlock}, so the plate keeps its alpha test and its block lightmap next to the
 * carried block instead of looking flat and unlit against it.
 */
@OnlyIn(Dist.CLIENT)
public final class CarryPlateRender {
    /**
     * Draws every plate carried on {@code glove} around the carried block.
     *
     * <p>The pose origin must be the <b>centre of the carried block</b> - the same point both carry
     * renderers already put at the origin, so plates and block always line up.
     *
     * @return true when at least one plate was drawn
     */
    public static boolean drawCarriedPlates(@Nullable ItemStack glove, PoseStack poseStack,
                                            MultiBufferSource buffers, int packedLight,
                                            @Nullable Level level) {
        if (glove == null || level == null) {
            return false;
        }
        CompoundTag blockTag = BlockGloveItem.carriedTag(glove);
        if (blockTag == null || !blockTag.contains("Plates", 9)) {
            return false;
        }
        ListTag plates = blockTag.getList("Plates", 9);
        if (plates.isEmpty()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        boolean drewAny = false;
        for (int i = 0; i < plates.size(); i++) {
            CompoundTag plateTag = plates.getCompound(i);
            BlockState plateState = NbtUtils.readBlockState(level.holderLookup(
                            net.minecraft.core.registries.Registries.BLOCK),
                    plateTag.getCompound("State"));
            if (!(plateState.getBlock() instanceof SideDrainBlock)) {
                continue;
            }
            // The side the plate was clamped onto, stored when the payload was packed.
            Direction side = Direction.byName(plateTag.getString("Side"));
            if (side == null) {
                continue;
            }
            poseStack.pushPose();
            try {
                drawOne(plateState, side, poseStack, buffers, packedLight, level, dispatcher);
                drewAny = true;
            } finally {
                poseStack.popPose();
            }
        }
        return drewAny;
    }

    /**
     * Places and draws one plate relative to the carried block's centre.
     *
     * <p>The plate slab is authored for a north-facing plate as a thin wall on the far side of its
     * own cell ({@code z 0.8125..1.0}), with the nozzle pointing north. To clamp it onto the host's
     * {@code side} face the transform is: step to the host face, turn the unit cube onto that face,
     * then flip so the nozzle points away from the host rather than into it.
     */
    private static void drawOne(BlockState plateState, Direction side, PoseStack poseStack,
                                MultiBufferSource buffers, int packedLight, Level level,
                                BlockRenderDispatcher dispatcher) {
        // Step from the carried block's centre out to the face the plate was clamped onto.
        poseStack.translate(side.getStepX() * 0.5D, side.getStepY() * 0.5D, side.getStepZ() * 0.5D);
        // Turn the following unit cube onto that face. A blockstate facing does exactly this, so the
        // plate's own model lands in the same orientation a standing plate has.
        switch (side) {
            case DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
            case UP, NORTH -> { }
            case SOUTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            case WEST -> poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
            case EAST -> poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
        }
        // The plate's nozzle is authored pointing north (the model's -Z). On the UP and DOWN faces
        // that already points away from the host after the cube turn above; on the four side faces
        // the turn above left the nozzle pointing back INTO the host, so flip the plate around.
        switch (side) {
            case DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
            case NORTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            default -> { }
        }
        // The plate is a real MODEL-shape block with real elements, so the dispatcher path keeps its
        // alpha test and its block lightmap; renderSingleBlock would route it through the ENTITY
        // render type and make it look flat next to the carried block.
        if (plateState.getRenderShape() == RenderShape.MODEL) {
            BakedModel model = dispatcher.getBlockModel(plateState);
            RandomSource random = RandomSource.create(42L);
            for (net.minecraft.client.renderer.RenderType type
                    : model.getRenderTypes(plateState, random, ModelData.EMPTY)) {
                dispatcher.getModelRenderer().tesselateWithoutAO(level, model, plateState,
                        net.minecraft.core.BlockPos.ZERO, poseStack, buffers.getBuffer(type), false,
                        random, 42L, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                        ModelData.EMPTY, type);
            }
        } else {
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(plateState, poseStack,
                    buffers, packedLight, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        }
    }

    private CarryPlateRender() {
    }
}
