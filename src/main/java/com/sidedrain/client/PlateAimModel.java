package com.sidedrain.client;

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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.IDynamicBakedModel;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.IGeometryLoader;
import net.minecraftforge.client.model.geometry.IUnbakedGeometry;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The plate's rendered form: one flat slab, turned by whatever aim the wrench set.
 *
 * <h2>Why a dynamic model at all</h2>
 *
 * <p>The aim is two angles - {@code tilt} (45 degree steps about x) and {@code spin} (45 degree steps
 * about y) - and a blockstate can only express multiples of 90 degrees on both axes. The 45 degree
 * parts therefore have to live in the baked geometry, and one element rotation cannot carry both at
 * once. Baking 6 x 8 x 8 static variants would work but would be 384 near-identical JSON files; this
 * model instead keeps the <b>flat</b> slab of each family and applies the composed rotation at mesh
 * time, exactly the way {@link RotatedBlockModel} builds the carrier.
 *
 * <h2>What it reads</h2>
 *
 * <p>{@link IDynamicBakedModel#getQuads} receives the real {@link BlockState} being meshed, so the
 * facing, tilt and spin come straight off it - no model data, no block entity, no per-position state.
 * Every plate at the same angles shares one cache entry.
 *
 * <h2>The two texture traps this class avoids</h2>
 *
 * <ol>
 *   <li><b>The rotated quad kept its old {@code Direction}.</b> The mesher culls per direction and
 *       lights per direction, so a quad filed under a direction it no longer faces is either hidden
 *       or lit from the wrong side. Rotated quads are restamped with the direction they now face, and
 *       diagonally-facing quads go to the unculled bucket where no culling decision is made.</li>
 *   <li><b>The wrong render type.</b> The slab is drawn through the render types of the flat source
 *       model, so it keeps its alpha test and its shader phase instead of collapsing onto solid.</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class PlateAimModel implements IDynamicBakedModel {
    /**
     * Where the flat source slabs live, per model family and per plate size.
     *
     * <p>The two plates are the same geometry with different textures, so the family key is
     * {@code plate / family} and the expanded entries are the same slabs with the expanded palette.
     * Without the size in the key an expanded plate would render in the plain plate's colours - which
     * is exactly what would happen if the blockstate pointed both plates at the same model.
     */
    private static final Map<String, ResourceLocation> SOURCES = Map.of(
            "plain/side", new ResourceLocation("sidedrain", "block/side_drain_plate_side_t0"),
            "plain/up", new ResourceLocation("sidedrain", "block/side_drain_plate_up_t0"),
            "plain/down", new ResourceLocation("sidedrain", "block/side_drain_plate_down_t0"),
            "expanded/side", new ResourceLocation("sidedrain", "block/expanded_side_drain_plate_side_t0"),
            "expanded/up", new ResourceLocation("sidedrain", "block/expanded_side_drain_plate_up_t0"),
            "expanded/down", new ResourceLocation("sidedrain", "block/expanded_side_drain_plate_down_t0"));

    /** Cache of turned geometry, keyed by (facing, tilt, spin, layer). */
    private static final Map<Key, List<BakedQuad>[]> CACHE = new ConcurrentHashMap<>();

    private PlateAimModel() {
    }

    /** Registers the geometry loader under {@code sidedrain:plate_aim}. */
    public static final class Loader implements IGeometryLoader<Geometry> {
        @Override
        public Geometry read(com.google.gson.JsonObject json, com.google.gson.JsonDeserializationContext context) {
            return new Geometry();
        }
    }

    /** The unbaked form; it carries no data because everything comes from the block state. */
    public static final class Geometry implements IUnbakedGeometry<Geometry> {
        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                               java.util.function.Function<net.minecraft.client.resources.model.Material,
                                       TextureAtlasSprite> spriteGetter,
                               ModelState modelState, ItemOverrides overrides,
                               ResourceLocation modelLocation) {
            return new PlateAimModel();
        }
    }

    /** The bucket the mesher draws without any culling test. */
    private static final int UNCULLED = Direction.values().length;

    private record Key(int facing, int tilt, int spin, @Nullable RenderType renderType,
                       boolean expanded) {
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                    RandomSource rand, ModelData data,
                                    @Nullable RenderType renderType) {
        IntegerProperty tiltProperty = com.sidedrain.block.SideDrainBlock.TILT;
        IntegerProperty spinProperty = com.sidedrain.block.SideDrainBlock.SPIN;
        if (state == null || !state.hasProperty(tiltProperty) || !state.hasProperty(spinProperty)) {
            // An item render or a state without the properties: draw the flat side slab as authored.
            // The item models are ordinary JSON, so this path is only ever hit by a state that lost
            // its properties; the plain family is the safe answer.
            BakedModel source = sourceModel("plain/side");
            return source == null ? List.of() : source.getQuads(null, side,
                    RandomSource.create(42L), ModelData.EMPTY, renderType);
        }
        int facing = state.getValue(com.sidedrain.block.SideDrainBlock.FACING).ordinal();
        int tilt = state.getValue(tiltProperty);
        int spin = state.getValue(spinProperty);
        final int keyFacing = facing;
        final int keyTilt = tilt;
        final int keySpin = spin;
        final RenderType keyLayer = renderType;
        final boolean expanded = state.getBlock() instanceof com.sidedrain.block.SideDrainBlock block
                && block.isExpanded();
        List<BakedQuad>[] byFace = CACHE.computeIfAbsent(
                new Key(keyFacing, keyTilt, keySpin, keyLayer, expanded),
                key -> build(keyFacing, keyTilt, keySpin, keyLayer, expanded));
        if (side == null) {
            return byFace[UNCULLED];
        }
        return byFace[side.get3DDataValue()];
    }

    @SuppressWarnings("unchecked")
    private static List<BakedQuad>[] build(int facingOrdinal, int tilt, int spin,
                                           @Nullable RenderType renderType, boolean expanded) {
        List<BakedQuad>[] byFace = new List[Direction.values().length + 1];
        for (int i = 0; i < byFace.length; i++) {
            byFace[i] = new ArrayList<>();
        }
        Direction facing = Direction.values()[facingOrdinal];
        BakedModel source = sourceModel((expanded ? "expanded/" : "plain/")
                + com.sidedrain.util.PlateAim.modelFamily(facing));
        if (source == null) {
            return byFace;
        }
        // The composed transform, about the block centre: tilt about x first, then the spin and face
        // turn about y. The same two angles the collision shape uses - see PlateAim.build - so what
        // you bump into is the plate you can see.
        Matrix4f transform = buildTransform(facing, tilt, spin);
        net.minecraftforge.client.model.IQuadTransformer transformer =
                net.minecraftforge.client.model.QuadTransformers
                        .applying(new com.mojang.math.Transformation(transform));
        boolean axisAligned = Math.floorMod(tilt * 45, 90) == 0 && Math.floorMod(spin * 45, 90) == 0;
        RandomSource random = RandomSource.create(42L);
        for (Direction side : Direction.values()) {
            for (BakedQuad quad : source.getQuads(null, side, random, ModelData.EMPTY, renderType)) {
                addRotated(byFace, quad, transformer, transform, axisAligned);
            }
        }
        for (BakedQuad quad : source.getQuads(null, null, random, ModelData.EMPTY, renderType)) {
            addRotated(byFace, quad, transformer, transform, axisAligned);
        }
        return byFace;
    }

    /** The flat slab of one model family, or {@code null} when it is not baked. */
    @Nullable
    private static BakedModel sourceModel(String family) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getModelManager() == null) {
            return null;
        }
        ResourceLocation location = SOURCES.get(family);
        return location == null ? null : minecraft.getModelManager().getModel(location);
    }

    /** Rotates one quad, files it under the direction it now faces, and stamps that direction on it. */
    private static void addRotated(List<BakedQuad>[] byFace, BakedQuad quad,
                                   net.minecraftforge.client.model.IQuadTransformer transformer,
                                   Matrix4f transform, boolean axisAligned) {
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
     * The model -> world rotation, taken straight from {@link PlateAim#renderMatrix}.
     *
     * <p>Not rebuilt here. The plate's geometry, its collision shape, its aim vector and the weapon
     * decal all have to describe one transform; keeping a private copy of the arithmetic is how they
     * drifted apart in the first place. The constants are shared too, so a change to the tilt step
     * reaches every consumer at once.
     */
    private static Matrix4f buildTransform(Direction facing, int tilt, int spin) {
        return com.sidedrain.util.PlateAim.renderMatrix(facing, tilt, spin);
    }

    /**
     * The axis direction a quad faces <b>after</b> the rotation.
     *
     * <p>For the 90 degree steps that is exact; the 45 degree steps go to the unculled bucket, so an
     * approximation is never used for a culling decision.
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

    /** Drops the turned-geometry cache; call when models are reloaded. */
    public static void clearCaches() {
        CACHE.clear();
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
        return getParticleIcon(ModelData.EMPTY);
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        BakedModel source = sourceModel("plain/side");
        if (source != null) {
            return source.getParticleIcon(data);
        }
        return Minecraft.getInstance().getModelManager().getMissingModel().getParticleIcon();
    }

    @Override
    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }
}
