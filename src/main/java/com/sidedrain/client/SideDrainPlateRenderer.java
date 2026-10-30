package com.sidedrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.sidedrain.block.SideDrainBlock;
import com.sidedrain.block.entity.SideDrainBlockEntity;
import com.sidedrain.util.PlateAim;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * Paints the loaded weapon's <b>icon</b> onto the middle of the plate's decorated face.
 *
 * <h2>A decal, not an object</h2>
 *
 * <p>The plate's face already carries a picture of a plate. What a player wants to know is
 * <em>which</em> weapon is loaded, so the weapon is drawn the way that face is drawn: a flat icon
 * lying on the surface. Drawing the item as a 3D model - which an earlier version did - puts a
 * floating object in the air in front of the plate, which reads as a dropped item rather than as a
 * label.
 *
 * <h2>Where exactly</h2>
 *
 * <p>The plate is not a plain cube. The wrench tilts it in 45 degree steps about two axes, and the
 * slab's decorated surface is not the block boundary but 3/16 of a block in from it. So the icon is
 * positioned in the plate's own <b>model space</b> - the same 0..16 frame the model JSON is written
 * in - and carried to the world by the very matrix the slab's quads go through,
 * {@link PlateAim#renderMatrix}.
 *
 * <p>This is the part that took several attempts, so it is worth recording why the obvious versions
 * fail:
 *
 * <ul>
 *   <li>Reading the direction from {@code FACING} ignores the tilt and spin rings entirely, so the
 *       icon sits on the block boundary rather than on the slab, and slides off the plate the moment
 *       the wrench is used.</li>
 *   <li>Rebuilding a rotation from the aim vector alone - yaw to its bearing, then pitch to its
 *       elevation - sends the icon's normal the right way but leaves an arbitrary <b>roll</b> about
 *       that normal. Both rotations point the picture the same direction and differ by a spin, so
 *       the picture comes out rotated on its own face. Reusing the model matrix leaves no roll to
 *       get wrong.</li>
 *   <li>Assuming the decorated face is half a block from the centre buries the icon inside the
 *       slab.</li>
 *   <li>Lifting the decal the wrong way along the face normal puts it behind the slab, where it is
 *       invisible from exactly the side the player stands on.</li>
 * </ul>
 *
 * <h2>Which way it faces</h2>
 *
 * <p>The decorated surface is the {@code #outer} texture: the side the player looks at, which is the
 * far side from where the plate shoots. It is authored on the model's {@code north} face for a side
 * plate, {@code up} for an up plate and {@code down} for a down plate. The icon is laid on that same
 * authored face and then carried by the matrix with everything else, so the aim transform never has
 * to be reasoned about here at all.
 */
@OnlyIn(Dist.CLIENT)
public class SideDrainPlateRenderer implements BlockEntityRenderer<SideDrainBlockEntity> {
    /** The decorated surface of each authored slab family, in model units. */
    private static final double SIDE_FACE_Z = 13.0D;
    private static final double UP_FACE_Y = 3.0D;
    private static final double DOWN_FACE_Y = 13.0D;

    /**
     * How far the icon floats clear of the face, in model units.
     *
     * <p>A decal has to sit in front of the surface it is painted on or the two z-fight, and z-fight
     * is the worst possible failure here: a flickering patch reads as a broken texture rather than
     * as a picture. A tenth of a model unit is 0.6% of a block - far too small to see as a gap, and
     * comfortably past the depth resolution of the slab it sits on.
     */
    private static final double DECAL_LIFT = 0.1D;

    /**
     * Half-width of the icon, in model units.
     *
     * <p>The face is a 16x16 square. Ten units across leaves three units of the plate's own border
     * showing all round, so the picture reads as a badge mounted on the plate rather than as the
     * plate having been retextured.
     */
    private static final double ICON_HALF = 5.0D;

    /**
     * Render types, one per icon texture.
     *
     * <p>{@link RenderType#entityCutoutNoCull(ResourceLocation)} builds a new object on every call
     * and {@code MultiBufferSource} keys its buffers by that object's identity. Calling it per frame
     * would therefore hand the batcher a fresh, empty buffer every frame for every plate, turning
     * one draw into one draw per plate. Caching by texture collapses them back into one.
     */
    private static final java.util.Map<ResourceLocation, RenderType> TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    public SideDrainPlateRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SideDrainBlockEntity plate, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        ItemStack weapon = plate.getWeapon();
        if (weapon.isEmpty()) {
            return;
        }
        BlockState state = plate.getBlockState();
        if (!(state.getBlock() instanceof SideDrainBlock)) {
            return;
        }
        WeaponIcon.Icon icon = WeaponIcon.resolve(weapon);
        if (icon == null) {
            // No honest picture - an item whose model has no texture at all, or a gun whose pack is
            // not installed. A bare plate is the correct thing to show; a missing-texture checker is
            // not.
            return;
        }
        Direction facing = state.getValue(SideDrainBlock.FACING);
        int tilt = state.getValue(SideDrainBlock.TILT);
        int spin = state.getValue(SideDrainBlock.SPIN);
        // The slab's own transform, then a switch to model units so every number below can be read
        // straight off the model JSON.
        poseStack.pushPose();
        poseStack.mulPoseMatrix(PlateAim.renderMatrix(facing, tilt, spin));
        poseStack.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
        drawDecal(poseStack, buffers, packedLight, icon, PlateAim.modelFamily(facing));
        poseStack.popPose();
    }

    /**
     * Lays the icon on the authored face and emits it as one quad.
     *
     * <p>Each family is written out rather than derived from a normal, because the three faces do
     * not share a UV orientation: the side plate's face is seen looking along +Z and the up plate's
     * along -Y, so the same corner of the picture belongs at a different corner of the face. The
     * orders below are {@code FaceBakery}'s own for those faces, which is what keeps the picture
     * upright rather than upside down or mirrored.
     */
    private static void drawDecal(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                  WeaponIcon.Icon icon, String family) {
        double a0 = 8.0D - ICON_HALF;
        double a1 = 8.0D + ICON_HALF;
        Matrix4f pose = poseStack.last().pose();
        // entityCutoutNoCull, not an atlas or opaque type. Three requirements, all of them load
        // bearing:
        //
        //   - A STANDALONE TEXTURE. A gun pack's slot art is not stitched into the block atlas, so
        //     an atlas-bound type samples the wrong sheet and the plate shows whatever happened to
        //     be at those atlas coordinates.
        //   - AN ALPHA TEST. An item icon is a picture on a transparent sheet. Drawn opaque, the
        //     transparent texels are written to the depth buffer as black and the plate wears a
        //     black square with a weapon-shaped hole in it.
        //   - NO CULLING. The decal is a single quad in front of a surface that is itself drawn, and
        //     with culling any winding or pose that ends up mirrored for one aim combination makes
        //     the icon invisible from exactly the side the player looks at - which reads as "there
        //     is no picture at all", the most confusing way for this feature to fail.
        VertexConsumer consumer = buffers.getBuffer(TYPES.computeIfAbsent(icon.texture(),
                RenderType::entityCutoutNoCull));
        switch (family) {
            case "up" -> {
                // Authored face: up, normal +Y, at y = 3. FaceBakery's UP order runs
                // (minX,minZ) -> (minX,maxZ) -> (maxX,maxZ) -> (maxX,minZ).
                double y = UP_FACE_Y + DECAL_LIFT;
                vertex(consumer, pose, a0, y, a0, icon.u0(), icon.v0(), packedLight, 0.0F, 1.0F, 0.0F);
                vertex(consumer, pose, a0, y, a1, icon.u0(), icon.v1(), packedLight, 0.0F, 1.0F, 0.0F);
                vertex(consumer, pose, a1, y, a1, icon.u1(), icon.v1(), packedLight, 0.0F, 1.0F, 0.0F);
                vertex(consumer, pose, a1, y, a0, icon.u1(), icon.v0(), packedLight, 0.0F, 1.0F, 0.0F);
            }
            case "down" -> {
                // Authored face: down, normal -Y, at y = 13. Lifted downwards, away from the slab.
                // FaceBakery's DOWN order runs (minX,maxZ) -> (minX,minZ) -> (maxX,minZ) ->
                // (maxX,maxZ), which is the up order seen from the other side.
                double y = DOWN_FACE_Y - DECAL_LIFT;
                vertex(consumer, pose, a0, y, a1, icon.u0(), icon.v0(), packedLight, 0.0F, -1.0F, 0.0F);
                vertex(consumer, pose, a0, y, a0, icon.u0(), icon.v1(), packedLight, 0.0F, -1.0F, 0.0F);
                vertex(consumer, pose, a1, y, a0, icon.u1(), icon.v1(), packedLight, 0.0F, -1.0F, 0.0F);
                vertex(consumer, pose, a1, y, a1, icon.u1(), icon.v0(), packedLight, 0.0F, -1.0F, 0.0F);
            }
            default -> {
                // Authored face: north, normal -Z. The slab runs z = 13..16, so its decorated surface
                // is at 13 and the decal sits just north of it. FaceBakery's NORTH order runs
                // (maxX,maxY) -> (maxX,minY) -> (minX,minY) -> (minX,maxY): the picture's top-left
                // corner belongs at maxX,maxY, because looking at the north face from outside puts
                // +X on the left and +Y up.
                double z = SIDE_FACE_Z - DECAL_LIFT;
                vertex(consumer, pose, a1, a1, z, icon.u0(), icon.v0(), packedLight, 0.0F, 0.0F, -1.0F);
                vertex(consumer, pose, a1, a0, z, icon.u0(), icon.v1(), packedLight, 0.0F, 0.0F, -1.0F);
                vertex(consumer, pose, a0, a0, z, icon.u1(), icon.v1(), packedLight, 0.0F, 0.0F, -1.0F);
                vertex(consumer, pose, a0, a1, z, icon.u1(), icon.v0(), packedLight, 0.0F, 0.0F, -1.0F);
            }
        }
    }

    /** Emits one vertex of the decal. */
    private static void vertex(VertexConsumer consumer, Matrix4f pose, double x, double y, double z,
                               float u, float v, int packedLight, float nx, float ny, float nz) {
        consumer.vertex(pose, (float) x, (float) y, (float) z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(nx, ny, nz)
                .endVertex();
    }

}
