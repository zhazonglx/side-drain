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
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
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
 * <h2>The three texture bugs this class has to avoid</h2>
 *
 * <p>Rotated blocks used to show missing textures, the purple/black checkerboard, and the previous
 * block's texture overlapping the new one. There were exactly three causes, and each one is now
 * handled at the point where it was introduced:
 *
 * <ol>
 *   <li><b>The rotated quad kept its old {@code Direction}.</b> {@code BakedQuad.direction} is what
 *       the mesher's ambient-occlusion pass and its per-face lightmap sample are indexed by. A quad
 *       that had been turned to face north but still claimed to face up was lit from the wrong side,
 *       and a quad filed under a direction it does not report made the two disagree. Every rebuilt
 *       quad is now constructed with the direction it <em>now</em> faces.</li>
 *   <li><b>Model data was never refreshed on the client.</b> The wrapped state reaches a client
 *       through the block entity update packet, which goes to {@code load} and not to
 *       {@code onLoad} - so {@code ModelDataManager} kept serving the data it captured when the
 *       carrier was still empty, and the model was asked to draw an air state. It answered with no
 *       quads, which is a block with no texture at all, or with the geometry of whatever it held
 *       before, which is the previous texture drawn on top of the new one.
 *       {@code RotatedBlockEntity.load} now requests the refresh and invalidates the section.</li>
 *   <li><b>The particle sprite was the missing-model sprite.</b> Break particles and the cracking
 *       overlay both ask the model for a particle icon, and the carrier returned the purple/black
 *       missing-texture sprite, so every rotated block spat checkerboard dust when mined. The icon is
 *       now the wrapped block's own.</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class RotatedBlockModel implements IDynamicBakedModel {
    /** The state the carrier wraps. Set by the block entity through model data. */
    public static final ModelProperty<BlockState> INNER = new ModelProperty<>();

    /**
     * Cache of rotated geometry, keyed by (wrapped state, yaw, pitch, layer).
     *
     * <p>{@link BlockState} implements value equality and there are only 64 angle combinations per
     * wrapped state, so this map is small and every entry in it is reused by every position that
     * wraps the same block at the same angle - which is the common case, since a player who rotates
     * one stone rotates twenty.
     */
    private static final Map<Key, List<BakedQuad>[]> CACHE = new ConcurrentHashMap<>();

    /**
     * Upper bound on cached bucket sets.
     *
     * <p>Each entry holds the rotated quads of one (block, angle, layer), which for a detailed
     * model is a few hundred {@code BakedQuad}s. The cache had no bound at all: a world that rotated
     * many different blocks - a showcase build, a modpack's decorative set - grew it until the
     * client ran out of memory, and the growth is invisible until it is fatal.
     *
     * <p>2048 entries is far more than any single view needs (a section is 4096 blocks and only a
     * fraction of them are carriers) while still small enough to be a bounded, predictable cost.
     */
    private static final int CACHE_LIMIT = 2048;

    /**
     * Cache key for one built bucket set.
     *
     * <p>The render type is part of the key because a model can expose different quads per layer: a
     * grass block's overlay lives on {@code cutout_mipped} while the dirt underneath is on
     * {@code solid}, and asking the source model for the wrong layer returns the wrong geometry or
     * none at all. That was one of the ways a texture went missing.
     *
     * <p>{@link ModelData} is deliberately <b>not</b> part of the key. It does not implement
     * {@code equals}, so including it would compare by identity and miss the cache whenever the model
     * data manager rebuilt an equal instance - a rebuild of every quad of every rotated block in
     * view. The only thing this model reads out of it is {@link #INNER}, and that is already the
     * first component of the key.
     */
    private record Key(BlockState inner, int yaw, int pitch, @Nullable RenderType renderType) {
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
        // Exactly one owner per wrapped block, or the block is drawn twice.
        //
        // A wrapped chest, bed, sign or shulker box is animated and cannot be expressed as static
        // quads, so RotatedBlockRenderer draws it through its own block entity renderer - which is
        // also the only way it gets its lid angle, its text or its two-block shape. The mesher must
        // therefore return nothing for those, because the carrier's block state says MODEL and the
        // mesher would otherwise happily tessellate the wrapped block's static model underneath the
        // animated one. Two copies of the same surface, one rotated by the pose and one not, is
        // precisely the "original texture overlapping the rotated one, misaligned" picture.
        if (inner.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
            return List.of();
        }
        // The angles come from the carrier's own state, which is always the one being meshed. A
        // state that is not a carrier (an item render with no state) means no rotation.
        int yaw = 0;
        int pitch = 0;
        if (state != null && state.hasProperty(com.sidedrain.block.RotatedBlock.YAW)) {
            yaw = state.getValue(com.sidedrain.block.RotatedBlock.YAW) * 45;
            pitch = state.getValue(com.sidedrain.block.RotatedBlock.PITCH) * 45;
        }
        // Locals, because a lambda may not capture a non-effectively-final variable. The four
        // captured values are already final in spirit: they are read once here and never reassigned.
        final int keyYaw = yaw;
        final int keyPitch = pitch;
        final RenderType keyLayer = renderType;
        final ModelData keyData = data;
        Key cacheKey = new Key(inner, keyYaw, keyPitch, keyLayer);
        List<BakedQuad>[] byFace = CACHE.get(cacheKey);
        if (byFace == null) {
            // Built outside computeIfAbsent on purpose: build() asks the model manager for the
            // wrapped block's model, and doing that inside a ConcurrentHashMap mapping function
            // holds a bin lock across a fairly heavy call.
            byFace = build(inner, keyYaw, keyPitch, keyLayer, keyData);
            if (CACHE.size() >= CACHE_LIMIT) {
                java.util.Iterator<Key> it = CACHE.keySet().iterator();
                if (it.hasNext()) {
                    CACHE.remove(it.next());
                }
            }
            CACHE.put(cacheKey, byFace);
        }
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
    private static List<BakedQuad>[] build(BlockState inner, int yaw, int pitch,
                                           @Nullable RenderType renderType, ModelData data) {
        // One bucket per direction, plus the unculled one.
        List<BakedQuad>[] byFace = new List[Direction.values().length + 1];
        for (int i = 0; i < byFace.length; i++) {
            byFace[i] = new ArrayList<>();
        }
        BakedModel source = sourceModel(inner);
        if (source == null) {
            return byFace;
        }
        Matrix4f transform = buildTransform(inner, yaw, pitch);
        net.minecraftforge.client.model.IQuadTransformer transformer =
                net.minecraftforge.client.model.QuadTransformers
                        .applying(new com.mojang.math.Transformation(transform));

        // Whether the rotation lands the geometry back on the axis grid.
        //
        // This matters for the mesher's face culling: it calls getQuads once per direction and only
        // draws a face if the neighbour in THAT direction is not solid. Bucketing a quad under the
        // direction it now faces is therefore correct - but only when that direction exists. At 45
        // degrees a face points diagonally, so "the nearest axis" is a lie: culling it against that
        // neighbour would delete faces that are in plain sight.
        boolean axisAligned = Math.floorMod(yaw, 90) == 0 && Math.floorMod(pitch, 90) == 0;

        // Ask the wrapped model with the SAME render type and model data the mesher is using for
        // this pass. Passing null/EMPTY here (as an earlier version did) makes a model that varies
        // its quads per layer return geometry for the wrong layer - or none at all - which reads in
        // game as a missing texture or the purple/black checkerboard.
        for (Direction side : Direction.values()) {
            for (BakedQuad quad : source.getQuads(inner, side, RandomSource.create(42L),
                    data, renderType)) {
                addRotated(byFace, quad, transformer, transform, axisAligned);
            }
        }
        for (BakedQuad quad : source.getQuads(inner, null, RandomSource.create(42L),
                data, renderType)) {
            addRotated(byFace, quad, transformer, transform, axisAligned);
        }
        return byFace;
    }

    /**
     * Rotates one quad, files it under the direction it now faces, and stamps that direction on it.
     *
     * <p>The stamped direction is the important half. {@code BakedQuad.direction} is not decoration:
     * {@code ModelBlockRenderer} hands it to the ambient-occlusion pass, to the per-face lightmap
     * sample and to the flat-lighting path, so a quad whose field disagrees with the bucket it was
     * filed under is lit as if it still faced the old way. That disagreement is what made turned
     * blocks show black faces, and on a shader pack it also changed which sample the shadow map was
     * taken from.
     */
    private static void addRotated(List<BakedQuad>[] byFace, BakedQuad quad,
                                   net.minecraftforge.client.model.IQuadTransformer transformer,
                                   Matrix4f transform, boolean axisAligned) {
        // process() copies the quad first, so the cached source model's vertices are never mutated -
        // which matters because the same BakedQuad instance is shared by every position using it.
        BakedQuad rotated = transformer.process(quad);
        Direction restamped = axisAligned ? facingOf(quad.getDirection(), transform) : null;
        if (restamped != null && restamped != rotated.getDirection()) {
            rotated = new BakedQuad(rotated.getVertices(), rotated.getTintIndex(), restamped,
                    rotated.getSprite(), rotated.isShade(), rotated.hasAmbientOcclusion());
        }
        int bucket = axisAligned && restamped != null ? restamped.get3DDataValue() : UNCULLED;
        byFace[bucket].add(rotated);
    }

    /**
     * The baked model of the wrapped block, or {@code null} when there is nothing safe to draw.
     *
     * <p>Two guards matter here. A carrier must never wrap another carrier: asking the model shaper
     * for the model of a carrier state returns <b>this</b> class, which would recurse until the stack
     * ran out inside the chunk mesher. And the result must not be the missing model, because drawing
     * the missing model's quads is precisely the purple/black checkerboard this class is trying to
     * avoid - an empty carrier is better than a checkerboard.
     */
    @Nullable
    private static BakedModel sourceModel(BlockState inner) {
        if (inner.getBlock() instanceof com.sidedrain.block.RotatedBlock) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getBlockRenderer() == null) {
            return null;
        }
        BakedModel source = minecraft.getBlockRenderer().getBlockModel(inner);
        if (source == null || source == minecraft.getModelManager().getMissingModel()) {
            return null;
        }
        return source;
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

    /**
     * The axis direction a quad faces <b>after</b> the rotation.
     *
     * <p>Rotating a quad changes which way it points, so its original {@code direction} field is no
     * longer the answer - a face that was {@code UP} becomes {@code NORTH} after a 90 degree pitch.
     * The mesher culls per direction, so bucketing by the old value would hide faces that are in
     * plain sight and draw ones that are buried.
     *
     * <p>The rotated normal is derived from the transform itself. For the 90 degree steps that is
     * exact; the 45 degree steps go to the unculled bucket instead, so an approximation is never
     * used for a culling decision.
     */
    private static Direction facingOf(@Nullable Direction original, Matrix4f transform) {
        if (original == null) {
            return Direction.UP;
        }
        Vector3f normal = new Vector3f(original.getStepX(), original.getStepY(), original.getStepZ());
        new Matrix3f(transform).transform(normal);
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
        // An animated wrapped block owns no mesh - see getQuads. Reporting a layer for it would make
        // the mesher open a buffer for a section that has nothing to put in it, and (worse) tell the
        // renderer the cell has terrain geometry when the only thing there is the block entity pass.
        if (inner.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
            return net.minecraftforge.client.ChunkRenderTypeSet.none();
        }
        BakedModel source = sourceModel(inner);
        if (source == null) {
            return net.minecraftforge.client.ChunkRenderTypeSet.none();
        }
        // Forward the model data too: a model that picks its layer per position would otherwise be
        // asked with EMPTY and could answer with the wrong layer - the same class of bug as asking
        // for quads with the wrong render type.
        return source.getRenderTypes(inner, rand, data);
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

    /**
     * The particle sprite of the wrapped block.
     *
     * <p>Break particles and the cracking overlay both ask for this, and the answer used to be the
     * missing model's sprite: every rotated block spat the purple/black checkerboard when mined, and
     * the crack overlay drawn over it was checkerboard as well. That is one of the three texture bugs
     * in this class's header, and it is fixed simply by asking the wrapped block.
     *
     * <p>The no-argument overload is the one vanilla's particle path calls, and it carries no
     * position, so the carrier resolves which block it is from the crosshair target - the same trick
     * {@link CarrierNameLookup} uses for the HUD name, and correct for exactly the case that matters:
     * the player is looking at the block they are mining.
     */
    @Override
    public TextureAtlasSprite getParticleIcon() {
        return getParticleIcon(ModelData.EMPTY);
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockState inner = data.get(INNER);
        if (inner == null || inner.isAir()) {
            inner = CarrierNameLookup.aimedInner();
        }
        BakedModel source = inner == null ? null : sourceModel(inner);
        if (source != null) {
            return source.getParticleIcon(data);
        }
        return minecraft.getModelManager().getMissingModel().getParticleIcon();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }
}
