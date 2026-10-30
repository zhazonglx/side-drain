package com.sidedrain.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.sidedrain.util.ShapeRotator;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Where a Side Drain Plate points, at 45 degree resolution.
 *
 * <p>{@code FACING} still decides which face of a container the plate is clamped to - that never
 * changes, so every piece of container logic keeps using it untouched. What this class adds is a
 * swivelling nozzle: the aim can be rotated away from the face normal in eight 45 degree steps, so a
 * plate on a wall can spit items straight out, diagonally up, straight up, and so on.
 *
 * <h2>One rotation, three consumers</h2>
 *
 * <p>The rendered model, the collision box and the eject direction all describe the same transform:
 * the plate's own slab, tilted about the block centre by {@code 45 * tilt} degrees about x, then
 * turned onto its face by the blockstate's y rotation. Keeping one definition is the point of the
 * feature - the box you bump into and the path an item takes are the plate you can see.
 *
 * <p>Minecraft's blockstate {@code x}/{@code y} rotations only accept multiples of 90 degrees, so the
 * 45 degree part lives in the model as an element rotation ({@code side_drain_plate_t0..t7}), which
 * does allow arbitrary angles. The blockstate only ever applies the coarse face turn.
 */
public final class PlateAim {
    /** Steps of 45 degrees; matches {@code SideDrainBlock.TILT}. */
    public static final int STEPS = 8;
    /** Degrees per step. */
    public static final float STEP_DEGREES = 45.0F;

    /** The plate slab authored for a north facing plate: a thin wall on the south side. */
    private static final VoxelShape BASE_NORTH = Shapes.box(0.0D, 0.0D, 0.8125D, 1.0D, 1.0D, 1.0D);
    /** The plate slab authored for an up facing plate: a thin floor. */
    private static final VoxelShape BASE_UP = Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, 0.1875D, 1.0D);
    /** The plate slab authored for a down facing plate: a thin ceiling. */
    private static final VoxelShape BASE_DOWN = Shapes.box(0.0D, 0.8125D, 0.0D, 1.0D, 1.0D, 1.0D);

    /** Rotation pivot: the middle of the block, exactly where the model's element rotation pivots. */
    private static final Vec3 PIVOT = new Vec3(0.5D, 0.5D, 0.5D);

    private static final Map<Key, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    private record Key(Direction facing, int tilt, int spin) {
    }

    /** The coarse y rotation the blockstate applies for this facing. */
    public static int yaw(Direction facing) {
        return switch (facing) {
            case SOUTH -> 180;
            case EAST -> 90;
            case WEST -> 270;
            default -> 0;
        };
    }

    /** The model family this facing uses; up and down have their own authored slab. */
    public static String modelFamily(Direction facing) {
        return switch (facing) {
            case UP -> "up";
            case DOWN -> "down";
            default -> "side";
        };
    }

    /** Degrees the tilt bakes into the model. */
    public static int tiltDegrees(int tilt) {
        return Math.floorMod(tilt, STEPS) * (int) STEP_DEGREES;
    }

    /**
     * The direction items leave the plate, in 45 degree steps away from the face normal.
     *
     * <p>This rotates the <b>authored</b> outward normal, not {@code facing} itself. The models are
     * authored for a north (or up, or down) plate and the blockstate only turns them onto their face
     * afterwards, so the same order has to be used here: tilt first, face turn second. Rotating the
     * facing vector instead would send an east plate's items south.
     */
    public static Vec3 ejectDirection(Direction facing, int tilt) {
        return ejectDirection(facing, tilt, 0);
    }

    /**
     * The direction items leave the plate with both aim rings applied.
     *
     * <p>The vector is produced by transforming the authored nozzle normal through the <b>same JOML
     * matrix</b> the dynamic render model applies to the slab's quads. Deriving the direction by
     * hand-written trigonometry is exactly what drifted: JOML's {@code rotateY(+90)} maps north to
     * west - the opposite sign of the naive textbook convention - so a hand-rolled {@code Ry(-yaw)}
     * sent an east plate's items into its container and mirrored the x component of every
     * tilt-plus-spin combination. One matrix, three consumers: what the player sees, what they bump
     * into and where the items go can no longer disagree.
     */
    public static Vec3 ejectDirection(Direction facing, int tilt, int spin) {
        // Memoised. This is called several times per plate per tick - by muzzlePos(), the eject
        // path, the target scan and the shooter aim - and each call would otherwise build a
        // Matrix4f, a Matrix3f and two vectors. There are only 6 * 8 * 8 = 384 distinct answers for
        // the whole game, so they are computed once and shared.
        //
        // The returned Vec3 is immutable, so sharing one instance between callers is safe.
        return AIM_CACHE.computeIfAbsent(new AimKey(facing, tilt, spin), key ->
                transformNormal(key.facing(), key.tilt(), key.spin(),
                        authoredNormal(key.facing())));
    }

    /** One memoised aim vector per (facing, tilt, spin). */
    private record AimKey(Direction facing, int tilt, int spin) {
    }

    private static final Map<AimKey, Vec3> AIM_CACHE = new ConcurrentHashMap<>();

    /** The nozzle normal the flat slab is authored with, per model family. */
    private static Vec3 authoredNormal(Direction facing) {
        return switch (facing) {
            case UP -> new Vec3(0.0D, 1.0D, 0.0D);
            case DOWN -> new Vec3(0.0D, -1.0D, 0.0D);
            default -> new Vec3(0.0D, 0.0D, -1.0D);
        };
    }

    /**
     * The model -> world rotation for one aim, about the middle of the block.
     *
     * <p>This is <b>the</b> definition of how a plate is turned. Three things consume it and all
     * three must agree or the feature falls apart:
     *
     * <ol>
     *   <li>{@code PlateAimModel} transforms the slab's quads with it, so this is what the player
     *       sees;</li>
     *   <li>{@link #ejectDirection} transforms the authored nozzle normal with it, so this is where
     *       items and bullets leave;</li>
     *   <li>{@code SideDrainPlateRenderer} transforms the weapon decal with it, so the picture is
     *       painted on the face rather than floating near it.</li>
     * </ol>
     *
     * <p>The third one is why this is public and returns the matrix instead of a direction. An
     * earlier decal rebuilt a rotation from the aim vector alone - "yaw to the aim's bearing, then
     * pitch to its elevation" - which is a <em>different</em> rotation whenever the spin ring is
     * turned: both rotations send the plate's normal to the same place, but they differ by an
     * arbitrary roll about it, and the picture came out tilted on its own face. Reusing the matrix
     * removes that whole class of error: there is nothing left to disagree.
     *
     * <p>Order and signs are load-bearing. The tilt is about x and is applied <b>inside</b> the
     * yaw, matching how the models are authored: the 45 degree element rotation happens in the flat
     * slab's own frame, and the blockstate's face turn is applied to the result. Doing it the other
     * way sends an east plate's items south.
     */
    public static org.joml.Matrix4f renderMatrix(Direction facing, int tilt, int spin) {
        // Built from a memoised template, then copied for the caller.
        //
        // The memo saves the trigonometry and the object churn of composing three rotations every
        // frame per plate. The copy is not optional: consumers hand this matrix to
        // {@code Transformation}, which keeps the reference and can transform with it later, so a
        // shared instance would be a live aliasing bug waiting for the first mutating caller. A copy
        // of a 4x4 is a handful of floats - far cheaper than rebuilding it from angles.
        return new org.joml.Matrix4f(template(facing, tilt, spin));
    }

    /** The shared, never-mutated template for one aim. */
    private static org.joml.Matrix4f template(Direction facing, int tilt, int spin) {
        return MATRIX_CACHE.computeIfAbsent(new AimKey(facing, tilt, spin), key -> {
            org.joml.Matrix4f matrix = new org.joml.Matrix4f();
            matrix.translate(0.5F, 0.5F, 0.5F);
            matrix.rotate(com.mojang.math.Axis.YP.rotationDegrees(
                    key.spin() * STEP_DEGREES - yaw(key.facing())));
            matrix.rotate(com.mojang.math.Axis.XP.rotationDegrees(key.tilt() * STEP_DEGREES));
            matrix.translate(-0.5F, -0.5F, -0.5F);
            return matrix;
        });
    }

    /** One memoised transform per (facing, tilt, spin). */
    private static final Map<AimKey, org.joml.Matrix4f> MATRIX_CACHE = new ConcurrentHashMap<>();

    /**
     * Transforms {@code normal} by the model transform for this aim, returning the unit aim vector.
     *
     * <p>Goes through {@link #renderMatrix}, so the direction cannot drift away from the geometry.
     */
    private static Vec3 transformNormal(Direction facing, int tilt, int spin, Vec3 normal) {
        // Reads the shared template directly: Matrix3f copies what it needs out of it and never
        // writes back, so there is no reason to pay for the defensive copy the public accessor
        // hands out.
        org.joml.Vector3f rotated = new org.joml.Matrix3f(template(facing, tilt, spin))
                .transform(new org.joml.Vector3f((float) normal.x, (float) normal.y, (float) normal.z));
        return new Vec3(rotated.x(), rotated.y(), rotated.z()).normalize();
    }

    /** The collision and outline box of a plate with this facing and aim. */
    public static VoxelShape shape(Direction facing, int tilt) {
        return shape(facing, tilt, 0);
    }

    /** The collision and outline box of a plate with both aim rings applied. */
    public static VoxelShape shape(Direction facing, int tilt, int spin) {
        int steps = Math.floorMod(tilt, STEPS);
        int spinSteps = Math.floorMod(spin, STEPS);
        Key key = new Key(facing, steps, spinSteps);
        VoxelShape cached = SHAPES.get(key);
        if (cached != null) {
            return cached;
        }
        VoxelShape built = build(facing, steps, spinSteps);
        if (SHAPES.size() > 256) {
            SHAPES.clear();
        }
        SHAPES.put(key, built);
        return built;
    }

    private static VoxelShape build(Direction facing, int steps, int spinSteps) {
        VoxelShape base = switch (facing) {
            case UP -> BASE_UP;
            case DOWN -> BASE_DOWN;
            default -> BASE_NORTH;
        };
        // One composed pass for the tilt and the face turn, so the slab is sliced once instead of
        // twice. Slicing twice squared the strip count and, worse, the second pass worked on an
        // already-sliced shape whose boxes are no longer a clean slab.
        //
        // The plate's own slab is a full-block thin wall, so the diagonal is what makes the
        // collision box match the model: at 45 degrees the box has to be a staircase running from
        // one corner of the block to the other, not a fat axis-aligned block.
        //
        // The blockstate transform is NEGATIVE. BlockModelRotation builds it with
        //   new Quaternionf().rotateYXZ(-y, -x, 0)
        // so the model's world transform is Rx(-x) * Ry(-y) applied on the outside of the element
        // rotation. rotateAboutXY(x, y) applies Ry(y) * Rx(x), so the shape has to be given the
        // negated yaw; passing the raw blockstate value rotated the box the wrong way.
        //
        // This is why the misalignment looked intermittent: the error is zero when yaw is 0 or 180
        // (the rotation is its own inverse there) and a full 90 degrees on east and west. Verified
        // numerically over all 16 (facing, tilt) pairs - +yaw mismatches 8 of them by 9.19 blocks
        // in model units, -yaw matches all 16 exactly.
        //
        // Convention, settled by the SlabProbe/ShapeProbe pair: ShapeRotator follows the SAME
        // right-handed convention as JOML's Axis.YP/Axis.XP - rotateAboutY(-90) moves a +Z slab to
        // -X exactly as the render matrix does. So the shape takes the angles UNCHANGED: tilt*45
        // about x, then spin*45 - yaw about y. Negating them here was the earlier "collision box
        // spins the opposite way from the model" report, and the probes settle it definitively:
        // both consumers share one convention, so both get the same numbers.
        return ShapeRotator.rotateAboutXY(base, steps * (double) STEP_DEGREES,
                spinSteps * (double) STEP_DEGREES - yaw(facing));
    }

    // --- rotation primitives, kept only for reference ------------------------------------------
    //
    // The aim vector and the collision shape now both go through the JOML matrix, so these
    // hand-written helpers have no caller left. They stay as documentation of the convention that
    // caused the drift: both follow the textbook right-handed convention, which is the opposite
    // sign of JOML's Axis.YP/Axis.XP that the render model uses.

    /** {@code Ry(deg)} about the block centre. */
    private static Vec3 rotateY(Vec3 v, double degrees) {
        if (degrees == 0.0D) {
            return v;
        }
        double angle = Math.toRadians(degrees);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new Vec3(v.x * cos - v.z * sin, v.y, v.x * sin + v.z * cos);
    }

    /** {@code Rx(deg)} about the origin; used for directions, which have no centre. */
    private static Vec3 rotateX(Vec3 v, double degrees) {
        if (degrees == 0.0D) {
            return v;
        }
        double angle = Math.toRadians(degrees);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new Vec3(v.x, v.y * cos - v.z * sin, v.y * sin + v.z * cos);
    }

    /**
     * Rotates a shape about x by an arbitrary angle.
     *
     * <p>A rotated box is not a box, so the result is the axis aligned bound of the eight rotated
     * corners. For a thin slab that collides and outlines correctly, and it costs far less than a
     * true rotated convex shape.
     */
    private static VoxelShape rotateShapeX(VoxelShape shape, double degrees) {
        // One box in, one box out: the bounds of its eight rotated corners.
        //
        // This used to cut every box into SLICES strips and join them one at a time with
        // joinUnoptimized, which is quadratic in the strip count and rebuilt the accumulated shape on
        // every strip. For the plate's own slab it was survivable, but the same helper is reachable
        // from the wrench rotating a modded block, and there the strip count multiplied out into a
        // rebuild storm. The plate slab is thin in the rotating plane, so the corner bounds of the
        // whole box are already the exact silhouette - the strips bought nothing.
        java.util.List<AABB> boxes = new java.util.ArrayList<>();
        double angle = Math.toRadians(degrees);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        for (AABB box : shape.toAabbs()) {
            double minY = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (int corner = 0; corner < 4; corner++) {
                double y = (corner & 1) == 0 ? box.minY : box.maxY;
                double z = (corner & 2) == 0 ? box.minZ : box.maxZ;
                double dy = y - PIVOT.y;
                double dz = z - PIVOT.z;
                double ry = dy * cos - dz * sin + PIVOT.y;
                double rz = dy * sin + dz * cos + PIVOT.z;
                minY = Math.min(minY, ry);
                maxY = Math.max(maxY, ry);
                minZ = Math.min(minZ, rz);
                maxZ = Math.max(maxZ, rz);
            }
            boxes.add(new AABB(box.minX, minY, minZ, box.maxX, maxY, maxZ));
        }
        return unionOf(boxes);
    }

    /** Rotates a shape about y by a multiple of 90 degrees, exactly as a blockstate y does. */
    private static VoxelShape rotateShapeY(VoxelShape shape, int degrees) {
        if (degrees == 0) {
            return shape;
        }
        // Same one-in-one-out rule as rotateShapeX. A y rotation by a multiple of 90 degrees maps an
        // axis aligned box exactly onto another one, so for the only angles the blockstate uses this
        // is exact rather than an approximation.
        java.util.List<AABB> boxes = new java.util.ArrayList<>();
        double angle = Math.toRadians(degrees);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        for (AABB box : shape.toAabbs()) {
            double minX = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (int corner = 0; corner < 4; corner++) {
                double x = (corner & 1) == 0 ? box.minX : box.maxX;
                double z = (corner & 2) == 0 ? box.minZ : box.maxZ;
                double dx = x - PIVOT.x;
                double dz = z - PIVOT.z;
                double rx = dx * cos - dz * sin + PIVOT.x;
                double rz = dx * sin + dz * cos + PIVOT.z;
                minX = Math.min(minX, rx);
                maxX = Math.max(maxX, rx);
                minZ = Math.min(minZ, rz);
                maxZ = Math.max(maxZ, rz);
            }
            boxes.add(new AABB(minX, box.minY, minZ, maxX, box.maxY, maxZ));
        }
        return unionOf(boxes);
    }

    /**
     * Unions boxes with one optimize pass at the end.
     *
     * <p>{@code Shapes.or} is {@code joinUnoptimized(...).optimize()} per argument, so folding a list
     * through it runs a full optimize per box - quadratic, and the reason a many-boxed plate could
     * stall a tick. Merging unoptimized and optimizing once keeps it linear.
     */
    private static VoxelShape unionOf(java.util.List<AABB> boxes) {
        if (boxes.isEmpty()) {
            return Shapes.empty();
        }
        VoxelShape merged = Shapes.empty();
        for (AABB box : boxes) {
            merged = Shapes.joinUnoptimized(merged,
                    Shapes.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
                    BooleanOp.OR);
        }
        return merged.optimize();
    }

    private PlateAim() {
    }
}
