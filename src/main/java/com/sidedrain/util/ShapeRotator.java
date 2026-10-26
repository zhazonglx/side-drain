package com.sidedrain.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Rotates a voxel shape by multiples of 45 degrees.
 *
 * <p>All maths is done in block pixel units (0..16), which is what {@link net.minecraft.world.level.block.Block#box}
 * produces. Shapes may extend outside the block, as the hopper's intake volume does.
 *
 * <h2>Caching</h2>
 *
 * <p>A rotation walks every box of the source shape, rebuilds a fresh {@code VoxelShape} through
 * {@code Shapes.joinUnoptimized} and then runs {@code optimize()} over the result. For a large modded
 * shape - a machine with thirty collision boxes - that is thousands of allocations, and it used to
 * happen on <b>every</b> call: every block update, every entity sweep, every frame the outline is
 * drawn, and once per tick for every hopper asking for its intake volume.
 *
 * <p>Rotation is a pure function of the source shape and the number of quarter turns, so the result
 * is memoised here. The key is the source {@link VoxelShape} <b>instance</b>: a block returns the
 * same instance for every query, and {@code VoxelShape} does not override {@code equals}, so map
 * equality is identity equality. A block that really does vary its shape per query hands back a
 * different instance and simply gets its own entry, which keeps the result correct rather than
 * pinning one entity's shape for all of them.
 *
 * <p>The map is a {@link ConcurrentHashMap} so the pre-computation worker in
 * {@code RotatedBlock} can fill it from another thread while the render thread reads it.
 */
public final class ShapeRotator {
    private static final double S = 16.0D;

    /** The block centre every rotation turns about, in block units. */
    private static final double CENTRE = 0.5D;

    /** Bounded so a world full of distinct modded shapes cannot grow the cache without limit. */
    private static final int CACHE_LIMIT = 2048;

    private static final Map<Key, VoxelShape> CACHE = new ConcurrentHashMap<>();

    /** {@code VoxelShape} has identity equality, which is exactly what is wanted here. */
    private record Key(VoxelShape source, int operation, int turns) {
    }

    private static final int OP_ROTATE_X = 0;
    private static final int OP_ROTATE_Y = 1;
    private static final int OP_ROTATE_Z = 2;
    private static final int OP_FLIP_Y = 3;
    /** Arbitrary-angle rotation about x; the key's {@code turns} field holds the angle in 45ths. */
    private static final int OP_ANGLE_X = 4;
    /** Arbitrary-angle rotation about y; the key's {@code turns} field holds the angle in 45ths. */
    private static final int OP_ANGLE_Y = 5;

    /** Thin slabs a box is cut into before an arbitrary-angle rotation. */
    private static final int SLICES = 16;

    /**
     * Hard cap on the boxes one rotation may produce before it degrades to a hull.
     *
     * <p>Guards the union below, which is the one place this class can become catastrophically
     * expensive.
     */
    private static final int MAX_PARTS = 1024;

    /**
     * How many boxes one rotation pass may produce.
     *
     * <p>Slicing runs twice for a carrier rotated on both axes, so this is a per-pass budget: it
     * keeps the second pass from re-slicing an already-sliced shape into a square of the count.
     */
    private static final int PART_BUDGET = 192;

    /**
     * How many boxes a source shape may have before slicing collapses it to one box first.
     *
     * <p>A rotated Sophisticated Backpack is a union of a body, two pouches or tanks and a battery -
     * a dozen boxes before slicing, each of which then becomes a strip. Slicing every one of them
     * makes the output proportional to the <em>mod's</em> shape complexity, which this class cannot
     * control. Collapsing to the overall bounding box above this limit keeps the work bounded no
     * matter what a mod hands us, and for a block that is roughly a cube the collapsed result is
     * closer to the real silhouette than slicing each sub-box separately.
     */
    private static final int SRC_BOX_LIMIT = 24;

    /**
     * How finely a shape is cut, chosen from how many boxes it already has.
     *
     * <p>Slicing is multiplicative. A carrier rotated on both axes is sliced twice, so with a fixed
     * sixteen strips a shape with {@code n} boxes became {@code 256 * n} - a vanilla bed went to
     * over two thousand boxes and the {@code Shapes.or} union over them ran for minutes on the
     * server thread, which is what froze the game. The count is therefore a budget shared out over
     * the boxes the shape already has, so the product stays bounded however many boxes it starts
     * with.
     */
    private static int slicesFor(int boxCount) {
        if (boxCount <= 0) {
            return SLICES;
        }
        // A budget, not a fixed count: the slices of one pass become the boxes of the next, so a
        // fixed 16 would still multiply out to 256 boxes for a shape that started with one.
        int slices = PART_BUDGET / boxCount;
        return java.lang.Math.max(1, java.lang.Math.min(SLICES, slices));
    }

    /** Rotates {@code shape} so that its local UP axis ends up pointing at {@code facing}. */
    public static VoxelShape toFacing(VoxelShape shape, Direction facing) {
        return switch (facing) {
            case UP -> shape;
            case DOWN -> flipVertical(shape);
            case NORTH -> rotateX(shape, 1);
            case SOUTH -> rotateX(shape, 3);
            case EAST -> rotateZ(shape, 1);
            case WEST -> rotateZ(shape, 3);
        };
    }

    public static VoxelShape spinY(VoxelShape shape, int quarterTurns) {
        int turns = quarterTurns & 3;
        if (turns == 0) {
            return shape;
        }
        return cached(shape, OP_ROTATE_Y, turns);
    }

    public static VoxelShape rotateY(VoxelShape shape) {
        return cached(shape, OP_ROTATE_Y, 1);
    }

    public static VoxelShape rotateX(VoxelShape shape, int quarterTurns) {
        int turns = quarterTurns & 3;
        if (turns == 0) {
            return shape;
        }
        return cached(shape, OP_ROTATE_X, turns);
    }

    public static VoxelShape rotateZ(VoxelShape shape, int quarterTurns) {
        int turns = quarterTurns & 3;
        if (turns == 0) {
            return shape;
        }
        return cached(shape, OP_ROTATE_Z, turns);
    }

    public static VoxelShape flipVertical(VoxelShape shape) {
        return cached(shape, OP_FLIP_Y, 1);
    }

    /**
     * Computes (or reuses) one rotation. Public so the pre-computation worker can warm the cache for
     * a shape it already has, off the server thread.
     */
    public static VoxelShape rotate(VoxelShape shape, int operation, int turns) {
        return cached(shape, operation, mask(operation, turns));
    }

    /** Quarter-turn operations wrap modulo four; the angle operations keep their 0..7 step count. */
    private static int mask(int operation, int turns) {
        return operation <= OP_FLIP_Y ? turns & 3 : turns & 7;
    }

    private static VoxelShape cached(VoxelShape shape, int operation, int turns) {
        Key key = new Key(shape, operation, turns);
        VoxelShape hit = CACHE.get(key);
        if (hit != null) {
            return hit;
        }
        VoxelShape built = compute(shape, operation, turns);
        // Never clear. Wiping the map turned every following query into a rebuild, and a rebuild
        // storm on the server thread is exactly what froze the game. A full map simply stops
        // accepting new entries; the ones already there keep being served, and a rare miss is
        // recomputed on demand.
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.put(key, built);
        }
        return built;
    }

    private static VoxelShape compute(VoxelShape shape, int operation, int turns) {
        return switch (operation) {
            case OP_ROTATE_Y -> {
                VoxelShape result = shape;
                for (int i = 0; i < turns; i++) {
                    result = rebuild(result, (x0, y0, z0, x1, y1, z1) ->
                            new double[]{S - z1, y0, x0, S - z0, y1, x1});
                }
                yield result;
            }
            case OP_ROTATE_X -> {
                VoxelShape result = shape;
                for (int i = 0; i < turns; i++) {
                    result = rebuild(result, (x0, y0, z0, x1, y1, z1) ->
                            new double[]{x0, z0, S - y1, x1, z1, S - y0});
                }
                yield result;
            }
            case OP_ROTATE_Z -> {
                VoxelShape result = shape;
                for (int i = 0; i < turns; i++) {
                    result = rebuild(result, (x0, y0, z0, x1, y1, z1) ->
                            new double[]{y0, S - x1, z0, y1, S - x0, z1});
                }
                yield result;
            }
            case OP_ANGLE_X, OP_ANGLE_Y -> computeAngle(shape, operation, turns);
            default -> rebuild(shape, (x0, y0, z0, x1, y1, z1) ->
                    new double[]{x0, S - y1, z0, x1, S - y0, z1});
        };
    }

    /**
     * Rotates a shape about the block centre by an arbitrary multiple of 45 degrees about the x axis.
     *
     * <p>The sign is the renderer's: {@code Axis.XP.rotationDegrees(degrees)}, i.e. the standard
     * right-handed rotation {@code y' = y cos - z sin}, {@code z' = y sin + z cos}. Quarter turns are
     * remapped exactly; anything else is approximated by cutting the shape into thin slabs, because a
     * rotated box is no longer axis aligned and a collision shape has to be.
     */
    public static VoxelShape rotateAboutX(VoxelShape shape, double degrees) {
        return angle(shape, OP_ANGLE_X, degrees);
    }

    /**
     * Rotates a shape about the block centre about the y axis.
     *
     * <p>Same convention as {@link #rotateAboutX}: the sign matches
     * {@code Axis.YP.rotationDegrees(degrees)}.
     */
    public static VoxelShape rotateAboutY(VoxelShape shape, double degrees) {
        return angle(shape, OP_ANGLE_Y, degrees);
    }

    private static VoxelShape angle(VoxelShape shape, int operation, double degrees) {
        int steps = (int) java.lang.Math.round(normalise(degrees) / 45.0D) & 7;
        if (steps == 0) {
            return shape;
        }
        return cached(shape, operation, mask(operation, steps));
    }

    /** Folds any angle into [0, 360). */
    private static double normalise(double degrees) {
        double wrapped = degrees % 360.0D;
        return wrapped < 0.0D ? wrapped + 360.0D : wrapped;
    }

    /**
     * One arbitrary-angle step.
     *
     * <p>A quarter turn maps an axis-aligned box onto another axis-aligned box, so it is done exactly
     * by transforming the eight corners. Any other angle leaves the box tilted; each slice is still
     * rotated as a box and its bounding box is taken, which is the same approximation the side drain
     * plate uses for its own collision shape.
     *
     * <p>The slice count is not fixed. Slicing twice multiplies, so a shape that is already detailed
     * is cut coarsely - see {@link #slicesFor}. Without that, a bed rotated on both axes produced
     * thousands of boxes and the union stalled the server thread.
     */
    private static VoxelShape computeAngle(VoxelShape shape, int operation, int steps) {
        double degrees = steps * 45.0D;
        if (degrees % 90.0D == 0.0D) {
            int quarterTurns = (int) (degrees / 90.0D) & 3;
            return operation == OP_ANGLE_X
                    ? rotateX(shape, -quarterTurns)
                    : spinY(shape, -quarterTurns);
        }
        return operation == OP_ANGLE_X ? sliceRotateX(shape, degrees) : sliceRotateY(shape, degrees);
    }

    /**
     * Slices along y, rotates each slab about x, and unions the bounding boxes.
     *
     * <p>Every slice's box is collected first and unioned in one pass. Adding them one at a time
     * with {@code joinUnoptimized} re-merged the whole accumulated shape on every iteration, which
     * is quadratic in the slice count and made a many-boxed shape take milliseconds to build.
     */
    private static VoxelShape sliceRotateX(VoxelShape shape, double degrees) {
        double angle = java.lang.Math.toRadians(degrees);
        double cos = java.lang.Math.cos(angle);
        double sin = java.lang.Math.sin(angle);
        java.util.List<AABB> slices = new java.util.ArrayList<>();
        java.util.List<AABB> source = boundedSource(shape);
        int strips = slicesFor(source.size());
        for (AABB box : source) {
            // Slice across the ROTATED extent, not the original one. A slab rotated 45 degrees
            // reaches well outside its old y range, and slicing only the old range left the
            // protruding part to be covered by an over-wide strip.
            java.util.List<double[]> poly = rotatedRectY(box, cos, sin);
            double yMin = Double.MAX_VALUE;
            double yMax = -Double.MAX_VALUE;
            for (double[] p : poly) {
                yMin = java.lang.Math.min(yMin, p[0]);
                yMax = java.lang.Math.max(yMax, p[0]);
            }
            for (int slice = 0; slice < strips; slice++) {
                double ya = yMin + (yMax - yMin) * slice / strips;
                double yb = yMin + (yMax - yMin) * (slice + 1) / strips;
                // The rotated box is a rotated rectangle in the y/z plane. Bounding the whole
                // strip by its four corners was the bug: a strip is full depth in z, so a 45
                // degree turn blew every strip up to about 0.7 x 0.7 and the union of those
                // was a fat cube that looked not rotated at all. Clipping the rotated rectangle
                // to the strip and taking the exact z extent hugs the real shape instead.
                java.util.List<double[]> band = clipBand(poly, ya, yb, 0);
                if (band.isEmpty()) {
                    continue;
                }
                double loY = Double.MAX_VALUE;
                double hiY = -Double.MAX_VALUE;
                double loZ = Double.MAX_VALUE;
                double hiZ = -Double.MAX_VALUE;
                for (double[] p : band) {
                    loY = java.lang.Math.min(loY, p[0]);
                    hiY = java.lang.Math.max(hiY, p[0]);
                    loZ = java.lang.Math.min(loZ, p[1]);
                    hiZ = java.lang.Math.max(hiZ, p[1]);
                }
                slices.add(new AABB(box.minX, loY, loZ, box.maxX, hiY, hiZ));
            }
        }
        // Hard clamp: two slicing passes multiply, and the second pass sees boxes the first one
            // produced. Above the per-pass budget the exact outline is worth less than a guaranteed
            // bound on the union cost, so collapse to the overall bounds instead.
            if (slices.size() > PART_BUDGET) {
                return hull(slices);
            }
            return union(slices);
    }

    /** The four corners of {@code box} rotated about x, as {@code {y, z}} pairs. */
    private static java.util.List<double[]> rotatedRectY(AABB box, double cos, double sin) {
        double[] ys = {box.minY, box.maxY};
        double[] zs = {box.minZ, box.maxZ};
        java.util.List<double[]> corners = new java.util.ArrayList<>(4);
        // Counter-clockwise so the polygon clipper below sees a consistent winding.
        int[][] order = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int[] o : order) {
            // Shapes arrive in block units (0..1), so the centre is 0.5. The pixel constant S is
            // only used inside rebuild(), which converts on the way in and back on the way out.
            double dy = ys[o[0]] - CENTRE;
            double dz = zs[o[1]] - CENTRE;
            corners.add(new double[]{dy * cos - dz * sin + CENTRE, dy * sin + dz * cos + CENTRE});
        }
        return corners;
    }

    /** The four corners of {@code box} rotated about y, as {@code {x, z}} pairs. */
    private static java.util.List<double[]> rotatedRectX(AABB box, double cos, double sin) {
        double[] xs = {box.minX, box.maxX};
        double[] zs = {box.minZ, box.maxZ};
        java.util.List<double[]> corners = new java.util.ArrayList<>(4);
        int[][] order = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int[] o : order) {
            double dx = xs[o[0]] - CENTRE;
            double dz = zs[o[1]] - CENTRE;
            corners.add(new double[]{dx * cos + dz * sin + CENTRE, -dx * sin + dz * cos + CENTRE});
        }
        return corners;
    }

    /**
     * Clips a convex polygon to the band {@code lo <= p[axis] <= hi}.
     *
     * <p>Sutherland-Hodgman against the two half planes. This is what makes a rotated thick shape
     * come out as the real rotated solid instead of its bounding box.
     */
    private static java.util.List<double[]> clipBand(java.util.List<double[]> poly,
                                                     double lo, double hi, int axis) {
        java.util.List<double[]> clipped = clipHalf(poly, lo, axis, true);
        return clipHalf(clipped, hi, axis, false);
    }

    private static java.util.List<double[]> clipHalf(java.util.List<double[]> poly,
                                                     double bound, int axis, boolean keepAbove) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            double[] cur = poly.get(i);
            double[] next = poly.get((i + 1) % n);
            boolean curIn = keepAbove ? cur[axis] >= bound : cur[axis] <= bound;
            boolean nextIn = keepAbove ? next[axis] >= bound : next[axis] <= bound;
            if (curIn) {
                out.add(cur);
            }
            if (curIn != nextIn) {
                double t = (bound - cur[axis]) / (next[axis] - cur[axis]);
                double[] hit = new double[2];
                hit[axis] = bound;
                hit[1 - axis] = cur[1 - axis] + t * (next[1 - axis] - cur[1 - axis]);
                out.add(hit);
            }
        }
        return out;
    }

    /** Slices along x, rotates each slab about y, and unions the bounding boxes. */
    private static VoxelShape sliceRotateY(VoxelShape shape, double degrees) {
        double angle = java.lang.Math.toRadians(degrees);
        double cos = java.lang.Math.cos(angle);
        double sin = java.lang.Math.sin(angle);
        java.util.List<AABB> slices = new java.util.ArrayList<>();
        java.util.List<AABB> source = boundedSource(shape);
        int strips = slicesFor(source.size());
        for (AABB box : source) {
            // Same as the x axis: slice the rotated extent, not the original one.
            java.util.List<double[]> poly = rotatedRectX(box, cos, sin);
            double xMin = Double.MAX_VALUE;
            double xMax = -Double.MAX_VALUE;
            for (double[] p : poly) {
                xMin = java.lang.Math.min(xMin, p[0]);
                xMax = java.lang.Math.max(xMax, p[0]);
            }
            for (int slice = 0; slice < strips; slice++) {
                double xa = xMin + (xMax - xMin) * slice / strips;
                double xb = xMin + (xMax - xMin) * (slice + 1) / strips;
                // Same fix as the x axis: clip the rotated rectangle to the strip instead of
                // bounding the strip by its corners, which inflated a 45 degree turn to a cube.
                java.util.List<double[]> band = clipBand(poly, xa, xb, 0);
                if (band.isEmpty()) {
                    continue;
                }
                double loX = Double.MAX_VALUE;
                double hiX = -Double.MAX_VALUE;
                double loZ = Double.MAX_VALUE;
                double hiZ = -Double.MAX_VALUE;
                for (double[] p : band) {
                    loX = java.lang.Math.min(loX, p[0]);
                    hiX = java.lang.Math.max(hiX, p[0]);
                    loZ = java.lang.Math.min(loZ, p[1]);
                    hiZ = java.lang.Math.max(hiZ, p[1]);
                }
                slices.add(new AABB(loX, box.minY, loZ, hiX, box.maxY, hiZ));
            }
        }
        // Hard clamp: two slicing passes multiply, and the second pass sees boxes the first one
            // produced. Above the per-pass budget the exact outline is worth less than a guaranteed
            // bound on the union cost, so collapse to the overall bounds instead.
            if (slices.size() > PART_BUDGET) {
                return hull(slices);
            }
            return union(slices);
    }

    /**
     * The source boxes to slice, collapsed to their overall bounds when there are too many.
     *
     * <p>Slicing is linear in the source box count, and the source box count belongs to whichever
     * mod built the shape. A machine made of thirty boxes would therefore cost thirty times a simple
     * block. Above {@link #SRC_BOX_LIMIT} the boxes are replaced by the single box that contains them
     * all, which caps the work and, for the roughly block-shaped models this affects, loses very
     * little: the slices of a union's bounding box track the union's silhouette closely.
     */
    private static java.util.List<AABB> boundedSource(VoxelShape shape) {
        java.util.List<AABB> boxes = shape.toAabbs();
        if (boxes.size() <= SRC_BOX_LIMIT) {
            return boxes;
        }
        return java.util.List.of(hullBox(boxes));
    }

    /**
     * Unions a list of boxes and drops the redundant interior faces.
     *
     * <p>This used to hand the whole array to {@code Shapes.or(first, rest...)}, which looks like one
     * call but is not: {@code or} is {@code Arrays.stream(rest).reduce(first, Shapes::or)}, and
     * {@code Shapes.or} is {@code join(..., OR)} which is {@code joinUnoptimized(...).optimize()}.
     * So an n-box union ran <b>n-1 full optimize() passes</b>, each one rebuilding the merged voxel
     * grid from scratch. For the 256 boxes a rotated Sophisticated Backpack produces that is 255
     * optimize passes over a shape that grows every time - quadratic work, tens of seconds, on the
     * server thread. That is exactly the "game logic stops after flipping" freeze: the server thread
     * was still inside this method, so the watchdog fired and every other tick waited behind it.
     *
     * <p>The fix is to merge without optimizing, then optimize exactly once at the end. Merging is
     * cheap because the result only has to be correct, not tidy; the single optimize then collapses
     * it. The box budget still guards the merge itself.
     */
    private static VoxelShape union(java.util.List<AABB> boxes) {
        if (boxes.isEmpty()) {
            return Shapes.empty();
        }
        if (boxes.size() > MAX_PARTS) {
            // Refuse to hand the merge an unbounded pile; the hull is a coarse but honest answer
            // and is what the shape degrades to rather than locking the server thread.
            return hull(boxes);
        }
        VoxelShape merged = Shapes.empty();
        for (AABB box : boxes) {
            VoxelShape part = Shapes.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            // joinUnoptimized, NOT join: this is the whole point. No optimize() until the end.
            merged = Shapes.joinUnoptimized(merged, part, BooleanOp.OR);
        }
        // One optimize for the finished union instead of one per box.
        return merged.optimize();
    }

    /** One box covering every part; the fallback when a rotation would produce too many. */
    private static VoxelShape hull(java.util.List<AABB> boxes) {
        AABB all = hullBox(boxes);
        return Shapes.box(all.minX, all.minY, all.minZ, all.maxX, all.maxY, all.maxZ);
    }

    /** The bounding box of every box in {@code boxes}. */
    private static AABB hullBox(java.util.List<AABB> boxes) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (AABB box : boxes) {
            minX = java.lang.Math.min(minX, box.minX);
            minY = java.lang.Math.min(minY, box.minY);
            minZ = java.lang.Math.min(minZ, box.minZ);
            maxX = java.lang.Math.max(maxX, box.maxX);
            maxY = java.lang.Math.max(maxY, box.maxY);
            maxZ = java.lang.Math.max(maxZ, box.maxZ);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private interface BoxMapper {
        double[] map(double x0, double y0, double z0, double x1, double y1, double z1);
    }

    private static VoxelShape rebuild(VoxelShape shape, BoxMapper mapper) {
        java.util.List<AABB> boxes = new java.util.ArrayList<>();
        for (AABB box : shape.toAabbs()) {
            double[] b = mapper.map(box.minX * S, box.minY * S, box.minZ * S,
                    box.maxX * S, box.maxY * S, box.maxZ * S);
            boxes.add(new AABB(
                    Math.min(b[0], b[3]) / S, Math.min(b[1], b[4]) / S, Math.min(b[2], b[5]) / S,
                    Math.max(b[0], b[3]) / S, Math.max(b[1], b[4]) / S, Math.max(b[2], b[5]) / S));
        }
        // Same single-pass union as the angle path. Joining one box at a time re-merged the whole
        // accumulated shape on every iteration, which is quadratic in the box count and was the
        // other way a big modded shape could stall the server thread.
        return union(boxes);
    }

    private ShapeRotator() {
    }
}
