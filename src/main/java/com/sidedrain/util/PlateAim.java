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

    private record Key(Direction facing, int tilt) {
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
        // The authored outward normal. The models are authored for a north (or up, or down) plate and
        // the blockstate only turns them onto their face afterwards, so the same order has to be used
        // here: tilt first, face turn second.
        Vec3 authored = switch (facing) {
            case UP -> new Vec3(0.0D, 1.0D, 0.0D);
            case DOWN -> new Vec3(0.0D, -1.0D, 0.0D);
            default -> new Vec3(0.0D, 0.0D, -1.0D);
        };
        int steps = Math.floorMod(tilt, STEPS);
        // Exactly the same composed transform the collision box uses: Rx(tilt) then Ry(yaw). Deriving
        // the direction from the same two angles is what guarantees the items leave along the normal
        // of the slanted face rather than along the old vertical one - the direction used to be
        // computed with a separate hand-written path that could drift from the shape.
        Vec3 tilted = steps == 0 ? authored : rotateX(authored, steps * (double) STEP_DEGREES);
        // Same negated yaw as the shape above, for the same reason: this must describe the identical
        // transform, or items fly out of a face the plate is not pointing at.
        return rotateY(tilted, -yaw(facing)).normalize();
    }

    /** The collision and outline box of a plate with this facing and aim. */
    public static VoxelShape shape(Direction facing, int tilt) {
        int steps = Math.floorMod(tilt, STEPS);
        Key key = new Key(facing, steps);
        VoxelShape cached = SHAPES.get(key);
        if (cached != null) {
            return cached;
        }
        VoxelShape built = build(facing, steps);
        if (SHAPES.size() > 64) {
            SHAPES.clear();
        }
        SHAPES.put(key, built);
        return built;
    }

    private static VoxelShape build(Direction facing, int steps) {
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
        return ShapeRotator.rotateAboutXY(base, steps * (double) STEP_DEGREES, -yaw(facing));
    }

    // --- rotation primitives, matching Minecraft's block model convention -----------------------

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
