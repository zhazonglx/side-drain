package com.sidedrain.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Thin strips per box used to approximate a rotated slab. */
    private static final int SLICES = 16;

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
        Vec3 authored = switch (facing) {
            case UP -> new Vec3(0.0D, 1.0D, 0.0D);
            case DOWN -> new Vec3(0.0D, -1.0D, 0.0D);
            default -> new Vec3(0.0D, 0.0D, -1.0D);
        };
        int steps = Math.floorMod(tilt, STEPS);
        Vec3 tilted = steps == 0 ? authored : rotateX(authored, steps * (double) STEP_DEGREES);
        return rotateY(tilted, yaw(facing)).normalize();
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
        VoxelShape tilted = steps == 0 ? base : rotateShapeX(base, steps * (double) STEP_DEGREES);
        return rotateShapeY(tilted, yaw(facing)).optimize();
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
        VoxelShape result = Shapes.empty();
        for (AABB box : shape.toAabbs()) {
            // One box rotated as a whole would bound the entire diagonal plate, which collides like a
            // fat cube. Slicing along the axis that actually swings (y) and bounding each thin strip
            // keeps the shape hugging the plate instead of filling the space around it.
            for (int slice = 0; slice < SLICES; slice++) {
                double y0 = box.minY + (box.maxY - box.minY) * slice / SLICES;
                double y1 = box.minY + (box.maxY - box.minY) * (slice + 1) / SLICES;
                double minY = Double.MAX_VALUE;
                double minZ = Double.MAX_VALUE;
                double maxY = -Double.MAX_VALUE;
                double maxZ = -Double.MAX_VALUE;
                for (int corner = 0; corner < 4; corner++) {
                    double y = (corner & 1) == 0 ? y0 : y1;
                    double z = (corner & 2) == 0 ? box.minZ : box.maxZ;
                    Vec3 rotated = rotateX(new Vec3(0.0D, y, z).subtract(PIVOT), degrees).add(PIVOT);
                    minY = Math.min(minY, rotated.y);
                    minZ = Math.min(minZ, rotated.z);
                    maxY = Math.max(maxY, rotated.y);
                    maxZ = Math.max(maxZ, rotated.z);
                }
                result = Shapes.joinUnoptimized(result,
                        Shapes.box(box.minX, minY, minZ, box.maxX, maxY, maxZ), BooleanOp.OR);
            }
        }
        return result;
    }

    /** Rotates a shape about y by a multiple of 90 degrees, exactly as a blockstate y does. */
    private static VoxelShape rotateShapeY(VoxelShape shape, int degrees) {
        if (degrees == 0) {
            return shape;
        }
        VoxelShape result = Shapes.empty();
        for (AABB box : shape.toAabbs()) {
            for (int slice = 0; slice < SLICES; slice++) {
                double x0 = box.minX + (box.maxX - box.minX) * slice / SLICES;
                double x1 = box.minX + (box.maxX - box.minX) * (slice + 1) / SLICES;
                double minX = Double.MAX_VALUE;
                double minZ = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE;
                double maxZ = -Double.MAX_VALUE;
                for (int corner = 0; corner < 4; corner++) {
                    double x = (corner & 1) == 0 ? x0 : x1;
                    double z = (corner & 2) == 0 ? box.minZ : box.maxZ;
                    Vec3 rotated = rotateY(new Vec3(x, 0.0D, z).subtract(PIVOT), degrees).add(PIVOT);
                    minX = Math.min(minX, rotated.x);
                    minZ = Math.min(minZ, rotated.z);
                    maxX = Math.max(maxX, rotated.x);
                    maxZ = Math.max(maxZ, rotated.z);
                }
                result = Shapes.joinUnoptimized(result,
                        Shapes.box(minX, box.minY, minZ, maxX, box.maxY, maxZ), BooleanOp.OR);
            }
        }
        return result;
    }

    private PlateAim() {
    }
}
