package com.sidedrain.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.IDynamicBakedModel;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.IGeometryLoader;
import net.minecraftforge.client.model.geometry.IUnbakedGeometry;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The model of the carrier block.
 *
 * <h2>What it does</h2>
 *
 * <p>A carrier has no geometry of its own - it shows whatever block it wraps, turned by its
 * {@code YAW}/{@code PITCH}. Both of those live in the block entity (the wrapped state) and in the
 * block state (the angles), so the geometry cannot be baked once at load time: it depends on what is
 * wrapped. This model therefore rebuilds the wrapped block's quads on demand and returns them already
 * rotated.
 *
 * <h2>Why this is the right layer</h2>
 *
 * <p>Returning rotated quads from a baked model means the <b>chunk mesher</b> consumes them, exactly
 * like any other block's. That is what makes the carrier look identical to a normal block:
 *
 * <ul>
 *   <li>the lightmap is computed by the mesher from the real neighbourhood, so nothing is darker;</li>
 *   <li>the ambient-occlusion pass runs over the turned geometry, so gradients are correct;</li>
 *   <li>the shader pack receives the block as ordinary terrain, so the vegetation wind, the shadow map
 *       and the terrain programs all apply without special handling;</li>
 *   <li>nothing is drawn from a render stage, so there is no extra buffer to flicker.</li>
 * </ul>
 *
 * <p>An earlier version drew the block from a block entity renderer with a rotated pose. Every
 * symptom this class fixes - too dark, wrong gradient, flicker - came from the lighting and the shader
 * phase having to be reproduced by hand there.
 */
@OnlyIn(Dist.CLIENT)
public final class RotatedBlockModel implements IDynamicBakedModel {
    /** The state the carrier wraps. Set by the block entity through model data. */
    public static final ModelProperty<BlockState> INNER = new ModelProperty<>();

    /** Cache of rotated geometry, keyed by (wrapped state, yaw, pitch). */
    private static final Map<Key, List<BakedQuad>[]> CACHE = new ConcurrentHashMap<>();

    private record Key(BlockState inner, int yaw, int pitch) {
    }

    private RotatedBlockModel() {
    }

    /** Registers the geometry loader under {@code sidedrain:rotated}. */
    public static final class Loader implements IGeometryLoader<Geometry> {
        @Override
        public Geometry read(JsonObject json, JsonDeserializationContext context)
                throws JsonParseException {
            return new Geometry();
        }
    }

    /** The unbaked form; it carries no data because everything comes from model data. */
    public static final class Geometry implements IUnbakedGeometry<Geometry> {
        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                               java.util.function.Function<net.minecraft.client.resources.model.Material,
                                       TextureAtlasSprite> spriteGetter,
                               ModelState modelState, ItemOverrides overrides,
                               ResourceLocation modelLocation) {
            return new RotatedBlockModel();
        }
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                    RandomSource rand, ModelData data,
                                    @Nullable RenderType renderType) {
        BlockState inner = data.get(INNER);
        if (inner == null || inner.isAir()) {
            return List.of();
        }
        // The angles come from the carrier's own state, which is always the one being meshed.
        int yaw = state == null ? 0 : state.getValue(com.sidedrain.block.RotatedBlock.YAW) * 45;
        int pitch = state == null ? 0 : state.getValue(com.sidedrain.block.RotatedBlock.PITCH) * 45;
        List<BakedQuad>[] byFace = CACHE.computeIfAbsent(new Key(inner, yaw, pitch),
                key -> build(inner, yaw, pitch));
        if (side == null) {
            // The mesher asks for side == null when it wants geometry it must not cull. Only the
            // quads that could not be assigned to an axis direction live here, so nothing is drawn
            // twice and nothing is dropped.
            return byFace[UNCULLED];
        }
        return byFace[side.get3DDataValue()];
    }

    /**
     * The bucket the mesher draws without any culling test.
     *
     * <p>{@code getQuads(state, null, ...)} is a separate call from the six directional ones, so a
     * quad stored under a direction is drawn once by the directional call and a quad stored here is
     * drawn once by the null call - never both. That is what makes it safe to put diagonally-facing
     * geometry here: it still appears exactly once, just without a culling decision.
     */
    private static final int UNCULLED = Direction.values().length;

    /** Builds the wrapped model's quads, rotated, and bucketed by the direction they now face. */
    @SuppressWarnings("unchecked")
    private static List<BakedQuad>[] build(BlockState inner, int yaw, int pitch) {
        // One bucket per direction, plus the unculled one.
        List<BakedQuad>[] byFace = new List[Direction.values().length + 1];
        for (int i = 0; i < byFace.length; i++) {
            byFace[i] = new ArrayList<>();
        }
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel source = minecraft.getBlockRenderer().getBlockModel(inner);
        if (source == null) {
            return byFace;
        }
        Matrix4f transform = buildTransform(inner, yaw, pitch);
        Matrix3f normalTransform = new Matrix3f(transform);

        // Whether the rotation lands the geometry back on the axis grid.
        //
        // This matters for the mesher's face culling: it calls getQuads once per direction and only
        // draws a face if the neighbour in THAT direction is not solid. Bucketing a quad under the
        // direction it now faces is therefore correct - but only when that direction exists. At 45
        // degrees a face points diagonally, so "the nearest axis" is a lie: culling it against that
        // neighbour would delete faces that are in plain sight.
        boolean axisAligned = Math.floorMod(yaw, 90) == 0 && Math.floorMod(pitch, 90) == 0;

        for (Direction side : Direction.values()) {
            for (BakedQuad quad : source.getQuads(inner, side, RandomSource.create(42L),
                    ModelData.EMPTY, null)) {
                BakedQuad rotated = rotate(quad, transform, normalTransform);
                byFace[axisAligned ? facingOf(rotated).get3DDataValue() : UNCULLED].add(rotated);
            }
        }
        for (BakedQuad quad : source.getQuads(inner, null, RandomSource.create(42L),
                ModelData.EMPTY, null)) {
            BakedQuad rotated = rotate(quad, transform, normalTransform);
            byFace[axisAligned ? facingOf(rotated).get3DDataValue() : UNCULLED].add(rotated);
        }
        return byFace;
    }

    /**
     * Builds the model -> world rotation, about the same pivot the collision shape uses.
     *
     * <p>For an ordinary block the pivot is the block's own centre. A bed is one object two blocks
     * long, so both halves must turn about the middle of the whole bed instead: each half expresses
     * that same world point in its own local frame, which is what keeps the pair parallel and one
     * block apart at every angle. Rotating each half about its own centre is what scissored the pair
     * into a diamond.
     */
    private static Matrix4f buildTransform(BlockState inner, int yaw, int pitch) {
        float pivotX = 0.5F, pivotY = 0.5F, pivotZ = 0.5F;
        if (inner.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            net.minecraft.core.Direction bedFacing =
                    inner.getValue(net.minecraft.world.level.block.BedBlock.FACING);
            // FACING runs foot -> head, so the pair centre sits half a block from this half's centre
            // towards the OTHER half: the head steps along -FACING, the foot along +FACING.
            boolean head = inner.getValue(net.minecraft.world.level.block.BedBlock.PART)
                    == net.minecraft.world.level.block.state.properties.BedPart.HEAD;
            float step = head ? -0.5F : 0.5F;
            pivotX += bedFacing.getStepX() * step;
            pivotY += bedFacing.getStepY() * step;
            pivotZ += bedFacing.getStepZ() * step;
        }
        Matrix4f matrix = new Matrix4f();
        matrix.translate(pivotX, pivotY, pivotZ);
        matrix.rotate(Axis.YP.rotationDegrees(-yaw));
        matrix.rotate(Axis.XP.rotationDegrees(pitch));
        matrix.translate(-pivotX, -pivotY, -pivotZ);
        return matrix;
    }

    /** Rotates one quad's positions and normals; UVs, tint and sprite are left untouched. */
    private static BakedQuad rotate(BakedQuad quad, Matrix4f transform, Matrix3f normalTransform) {
        int[] vertices = quad.getVertices().clone();
        for (int i = 0; i < 4; i++) {
            int offset = i * 8;
            Vector4f pos = new Vector4f(
                    Float.intBitsToFloat(vertices[offset]),
                    Float.intBitsToFloat(vertices[offset + 1]),
                    Float.intBitsToFloat(vertices[offset + 2]),
                    1.0F);
            transform.transform(pos);
            vertices[offset] = Float.floatToRawIntBits(pos.x());
            vertices[offset + 1] = Float.floatToRawIntBits(pos.y());
            vertices[offset + 2] = Float.floatToRawIntBits(pos.z());

            int normalIn = vertices[offset + 5];
            Vector3f normal = new Vector3f(
                    ((byte) (normalIn & 0xFF)) / 127.0F,
                    ((byte) ((normalIn >> 8) & 0xFF)) / 127.0F,
                    ((byte) ((normalIn >> 16) & 0xFF)) / 127.0F);
            normalTransform.transform(normal);
            vertices[offset + 5] = (((byte) (normal.x() * 127.0F)) & 0xFF)
                    | ((((byte) (normal.y() * 127.0F)) & 0xFF) << 8)
                    | ((((byte) (normal.z() * 127.0F)) & 0xFF) << 16)
                    | (normalIn & 0xFF000000);
        }
        return new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), quad.getSprite(),
                quad.isShade(), quad.hasAmbientOcclusion());
    }

    /** The axis direction the rotated quad now faces. */
    private static Direction facingOf(BakedQuad quad) {
        int[] vertices = quad.getVertices();
        int normalIn = vertices[5];
        Vector3f normal = new Vector3f(
                ((byte) (normalIn & 0xFF)) / 127.0F,
                ((byte) ((normalIn >> 8) & 0xFF)) / 127.0F,
                ((byte) ((normalIn >> 16) & 0xFF)) / 127.0F);
        Direction best = Direction.UP;
        float bestDot = -2.0F;
        for (Direction side : Direction.values()) {
            float dot = normal.x() * side.getStepX() + normal.y() * side.getStepY()
                    + normal.z() * side.getStepZ();
            if (dot > bestDot) {
                bestDot = dot;
                best = side;
            }
        }
        return best;
    }

    /** Drops the rotated-geometry cache; call when models are reloaded. */
    public static void clearCaches() {
        CACHE.clear();
    }

    /**
     * The render types of the <b>wrapped</b> block.
     *
     * <p>This has to be forwarded explicitly. The default implementation asks
     * {@code ItemBlockRenderTypes.getRenderLayers(carrierState)}, and the carrier's own state says
     * nothing about what is wrapped - so every rotated block would be drawn as {@code solid}.
     * A rotated grass or flower would then lose its alpha test and render as an opaque slab, and any
     * translucent block would occlude what is behind it.
     *
     * <p>Asking the wrapped model keeps the layer exactly as vanilla chose for that block, which is
     * also what the shader pack's material lookup expects.
     */
    @Override
    public net.minecraftforge.client.ChunkRenderTypeSet getRenderTypes(BlockState state,
            RandomSource rand, ModelData data) {
        BlockState inner = data.get(INNER);
        if (inner == null || inner.isAir()) {
            return net.minecraftforge.client.ChunkRenderTypeSet.none();
        }
        BakedModel source = Minecraft.getInstance().getBlockRenderer().getBlockModel(inner);
        if (source == null) {
            return net.minecraftforge.client.ChunkRenderTypeSet.none();
        }
        return source.getRenderTypes(inner, rand, ModelData.EMPTY);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return true;
    }

    @Override
    public boolean isGui3d() {
        return true;
    }

    @Override
    public boolean usesBlockLight() {
        return true;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return Minecraft.getInstance().getModelManager().getMissingModel().getParticleIcon();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }
}
