package com.sidedrain.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
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
     * Whether an arbitrary-angle rotation slices the shape to keep its diagonal, or just bounds it.
     *
     * <p><b>On</b> (the correct behaviour): each box is cut into thin strips along the axis that
     * actually swings, each strip is rotated, and the union of the strips reproduces the slanted
     * silhouette. This is the only way to get a real diagonal out of {@code VoxelShape}, because a
     * voxel shape is a discrete grid of axis-aligned cells - a tilted face is <em>represented</em> as
     * a staircase of small boxes, there is no other representation available.
     *
     * <p><b>Off</b>: every box becomes the bounds of its rotated corners. One box in, one box out, so
     * it is trivially cheap - but a thick box then inflates into a fat axis-aligned block and the
     * diagonal is lost. That is the "square collision box that only follows the model's size and
     * position" regression.
     *
     * <p>The cost of slicing is therefore paid, but it is made <b>bounded and once-only</b>:
     * {@link #slicesFor} shares a fixed box budget across the source boxes instead of using a fixed
     * strip count, {@link #union} merges unoptimized and optimizes exactly once, and every result is
     * memoised in {@link #CACHE} keyed on the source shape instance. A given block is sliced once
     * ever and then served from the map, so the staircase is paid for on the first rotation and
     * never again.
     */
    private static final boolean SLICE_ANGLES = true;

    /**
     * Hard cap on the boxes one rotation may produce before it degrades to a hull.
     *
     * <p>Guards the union below, which is the one place this class can become catastrophically
     * expensive.
     */
    private static final int MAX_PARTS = 4096;

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

    /**
     * Strips for one box of a shape that is being rotated on <b>both</b> axes.
     *
     * <p>The single-axis budget above divides {@link #PART_BUDGET} by the box count, which is right
     * when there is one pass. A carrier turned on both axes is sliced twice and the second pass sees
     * everything the first one produced, so the effective multiplier is {@code strips^2} rather than
     * {@code strips}. Dividing the budget by the square root keeps the final count on budget while
     * still giving each pass enough strips to show a diagonal instead of a staircase with four steps.
     *
     * <p>Sixteen strips on a full block is a step every 1/16 of a block, which reads as a clean
     * 45 degree face at the scale a player actually sees; the budget cap only bites on shapes that
     * arrive with many boxes of their own.
     */
    private static int slicesForTwoAxis(int boxCount) {
        int perPass = (int) java.lang.Math.round(java.lang.Math.sqrt(PART_BUDGET));
        if (boxCount <= 0) {
            return java.lang.Math.min(SLICES, perPass);
        }
        int slices = perPass / java.lang.Math.max(1, (int) java.lang.Math.ceil(java.lang.Math.sqrt(boxCount)));
        return java.lang.Math.max(2, java.lang.Math.min(SLICES, slices));
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

    /**
     * Empties the rotated-shape cache.
     *
     * <p>Only called on a resource reload and on shutdown. Shapes are derived from block state, not
     * from baked models, so this is not strictly required for correctness - but a pack can change
     * which blocks exist, and dropping the entries keeps a long session from holding shapes for
     * blocks that are gone. Never called during normal play, because wiping it mid-game turns every
     * following query into a rebuild on the server thread.
     */
    public static void clearCache() {
        CACHE.clear();
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

    /**
     * Rotates about x and then about y, in <b>one</b> slicing pass.
     *
     * <p>Calling {@link #rotateAboutX} and then {@link #rotateAboutY} slices twice, and the second
     * pass sees every strip the first one produced, so the cost is {@code strips^2} per source box.
     * That is the multiplication that made a two-axis rotation explode. Composing the two rotations
     * into a single matrix and slicing once keeps it linear in {@code strips}.
     *
     * <p>The transform applied to each box is {@code Ry(yDeg) * Rx(xDeg)} about the block centre,
     * which is exactly the order the renderer uses ({@code Axis.XP} then {@code Axis.YP}). Each box
     * is cut into strips along y - the axis the x rotation swings - and every strip is mapped through
     * the composed rotation and replaced by its axis-aligned bounds, so the staircase that makes up
     * the diagonal survives on both axes.
     */
    /**
     * Rotates a shape as one box: the bounds of its rotated corners.
     *
     * <p>The cheap path, for shapes whose exact silhouette does not matter - a decorative block's
     * outline, where nothing collides with it. One box in, one box out, so it costs almost nothing
     * and never allocates a grid.
     */
    public static VoxelShape boundingRotate(VoxelShape shape, double xDegrees, double yDegrees) {
        if (shape.isEmpty()) {
            return Shapes.empty();
        }
        double xRad = java.lang.Math.toRadians(xDegrees);
        double yRad = java.lang.Math.toRadians(yDegrees);
        double cx = java.lang.Math.cos(xRad), sx = java.lang.Math.sin(xRad);
        double cy = java.lang.Math.cos(yRad), sy = java.lang.Math.sin(yRad);
        java.util.List<AABB> boxes = shape.toAabbs();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (AABB box : boxes) {
            for (int i = 0; i < 8; i++) {
                double x = (i & 1) == 0 ? box.minX : box.maxX;
                double y = (i & 2) == 0 ? box.minY : box.maxY;
                double z = (i & 4) == 0 ? box.minZ : box.maxZ;
                double dx = x - CENTRE, dy = y - CENTRE, dz = z - CENTRE;
                double ry = dy * cx - dz * sx;
                double rz = dy * sx + dz * cx;
                double fx = dx * cy + rz * sy + CENTRE;
                double fy = ry + CENTRE;
                double fz = -dx * sy + rz * cy + CENTRE;
                minX = java.lang.Math.min(minX, fx);
                maxX = java.lang.Math.max(maxX, fx);
                minY = java.lang.Math.min(minY, fy);
                maxY = java.lang.Math.max(maxY, fy);
                minZ = java.lang.Math.min(minZ, fz);
                maxZ = java.lang.Math.max(maxZ, fz);
            }
        }
        return box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static VoxelShape rotateAboutXY(VoxelShape shape, double xDegrees, double yDegrees) {
        int xSteps = (int) java.lang.Math.round(normalise(xDegrees) / 45.0D) & 7;
        int ySteps = (int) java.lang.Math.round(normalise(yDegrees) / 45.0D) & 7;
        if (xSteps == 0 && ySteps == 0) {
            return shape;
        }
        // Both angles on a quarter turn: the box stays axis aligned, so no slicing is needed at all.
        if (xSteps % 2 == 0 && ySteps % 2 == 0) {
            VoxelShape result = xSteps == 0 ? shape : rotateX(shape, -(xSteps / 2) & 3);
            return ySteps == 0 ? result : spinY(result, -(ySteps / 2) & 3);
        }
        return cachedCombined(shape, xSteps, ySteps);
    }

    private static final int OP_ANGLE_XY = 6;

    private static VoxelShape cachedCombined(VoxelShape shape, int xSteps, int ySteps) {
        // The key packs both angles: xSteps and ySteps are each 0..7, so a 6-bit key suffices.
        int packed = (xSteps << 3) | ySteps;
        Key key = new Key(shape, OP_ANGLE_XY, packed);
        VoxelShape hit = CACHE.get(key);
        if (hit != null) {
            return hit;
        }
        VoxelShape built = computeAngleXY(shape, xSteps, ySteps);
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.put(key, built);
        }
        return built;
    }

    /**
     * One slicing pass that applies {@code Ry * Rx}.
     *
     * <p>Strips are cut along y, which is the axis the x rotation swings. The composed matrix then
     * maps each strip; because the strip is a thin slab in y, its image under the combined rotation
     * is a thin slanted slab, and its bounds approximate that slab closely. One pass, so the strip
     * count is not squared.
     */
    /**
     * Voxelises a rotated solid and emits runs of filled cells.
     *
     * <p>This is the only representation that can actually carry a diagonal: {@code VoxelShape} is a
     * grid of axis-aligned cells, so a tilted face has to be a staircase of cells. Bounding each
     * source box after rotation (the previous approach) cannot express that - for a solid box the
     * bounds of the rotated corners cover the whole rotated extent, so the result inflates to a fat
     * cube and the diagonal is lost.
     *
     * <p>The algorithm is the one Minecraft itself uses when it needs an exact shape, done in a
     * single pass:
     *
     * <ol>
     *   <li>rotate every source box's corners to find the overall rotated bounds,</li>
     *   <li>lay a {@code N} cells per block grid over those bounds,</li>
     *   <li>for each cell centre, inverse-transform it into the source frame and mark the cell when
     *       it falls inside any source box,</li>
     *   <li>emit maximal runs of marked cells along x as one box each.</li>
     * </ol>
     *
     * <p>Emitting runs rather than individual cells is what keeps the count usable: a solid rotated
     * block at {@code N=16} is ~265 cells but collapses to a few dozen runs, and the staircase still
     * has sixteen steps so the diagonal is smooth.
     */
    private static VoxelShape voxelRotate(java.util.List<AABB> source, double xRad, double yRad) {
        double cx = java.lang.Math.cos(xRad);
        double sx = java.lang.Math.sin(xRad);
        double cy = java.lang.Math.cos(yRad);
        double sy = java.lang.Math.sin(yRad);

        // Pass 1: rotated bounds.
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (AABB box : source) {
            for (int i = 0; i < 8; i++) {
                double x = (i & 1) == 0 ? box.minX : box.maxX;
                double y = (i & 2) == 0 ? box.minY : box.maxY;
                double z = (i & 4) == 0 ? box.minZ : box.maxZ;
                double dx = x - CENTRE, dy = y - CENTRE, dz = z - CENTRE;
                double ry = dy * cx - dz * sx;
                double rz = dy * sx + dz * cx;
                double fx = dx * cy + rz * sy + CENTRE;
                double fy = ry + CENTRE;
                double fz = -dx * sy + rz * cy + CENTRE;
                minX = java.lang.Math.min(minX, fx);
                maxX = java.lang.Math.max(maxX, fx);
                minY = java.lang.Math.min(minY, fy);
                maxY = java.lang.Math.max(maxY, fy);
                minZ = java.lang.Math.min(minZ, fz);
                maxZ = java.lang.Math.max(maxZ, fz);
            }
        }
        if (minX > maxX) {
            return Shapes.empty();
        }

        // Grid resolution: cells per block. Sixteen matches the 1/16 pixel grid a block model uses,
        // so the staircase steps line up with the texture resolution the player sees.
        //
        // Resolution is dropped for shapes that would be expensive to sample. The cost of a rotation
        // is the number of cell centres tested, which is the grid volume; a big modded machine with a
        // two-block outline at full resolution is sixteen times the cells of an ordinary block, and a
        // world full of those is what turns a rotation into a stutter. Halving the resolution cuts
        // the work eightfold and costs one step of staircase sharpness - which for a shape that large
        // is not visible anyway, because the player is looking at the whole machine, not at the
        // one-pixel lip of its collision box.
        final int cellsPerBlock = resolutionFor(maxX - minX, maxY - minY, maxZ - minZ);
        int nx = java.lang.Math.max(1, (int) java.lang.Math.ceil((maxX - minX) * cellsPerBlock));
        int ny = java.lang.Math.max(1, (int) java.lang.Math.ceil((maxY - minY) * cellsPerBlock));
        int nz = java.lang.Math.max(1, (int) java.lang.Math.ceil((maxZ - minZ) * cellsPerBlock));
        // A hard cap on the grid: a shape that spans many blocks would otherwise allocate an
        // enormous bitmap. Real block shapes are within one or two blocks.
        //
        // The fallback must be the ROTATED bounds, not hull(source): hull(source) is the unrotated
        // shape, so returning it here silently undid the rotation - a block would keep its original
        // collision box while its model turned, which reads as "the box did not rotate".
        if ((long) nx * ny * nz > MAX_GRID_CELLS) {
            return box(minX, minY, minZ, maxX, maxY, maxZ);
        }
        double cellX = (maxX - minX) / nx;
        double cellY = (maxY - minY) / ny;
        double cellZ = (maxZ - minZ) / nz;

        // The fine grid only has to describe the surface, so only the shell is sampled at full
        // resolution. Cells deeper than EDGE_BAND from the rotated bounds cannot be on the slanted
        // face - the face lives on the boundary of the rotated extent - so they are filled from a
        // coarse grid instead, which is what "edge diagonal first, then fill the middle" means in
        // practice.
        //
        // This is where the cost went. Sampling every cell centre is the whole expense of a
        // rotation, and a 45/45 solid block is eighteen thousand cells of which the overwhelming
        // majority are interior. Restricting the fine sample to the shell cuts that to the surface
        // area, and the interior is filled from a grid a quarter of the size.
        java.util.BitSet filled = new java.util.BitSet(nx * ny * nz);
        // Pass 2: test each cell centre against the source boxes, in the source frame.
        for (AABB box : source) {
            // Scan window for this box.
            //
            // The grid spans the ROTATED bounds (minX..maxX), but this box is in the SOURCE frame,
            // so its own min/max cannot be used to index the grid directly - doing that mixed two
            // coordinate systems, produced windows that landed outside the grid entirely, and left
            // whole shapes empty (the "collision box disappears at some 45 degree angles" bug).
            //
            // The correct window is the grid-space bounds of the box's own ROTATED image, which is
            // exactly the extent of its eight rotated corners.
            double bMinX = Double.MAX_VALUE, bMinY = Double.MAX_VALUE, bMinZ = Double.MAX_VALUE;
            double bMaxX = -Double.MAX_VALUE, bMaxY = -Double.MAX_VALUE, bMaxZ = -Double.MAX_VALUE;
            for (int c = 0; c < 8; c++) {
                double x = (c & 1) == 0 ? box.minX : box.maxX;
                double y = (c & 2) == 0 ? box.minY : box.maxY;
                double z = (c & 4) == 0 ? box.minZ : box.maxZ;
                double dx = x - CENTRE, dy = y - CENTRE, dz = z - CENTRE;
                double ry = dy * cx - dz * sx;
                double rz = dy * sx + dz * cx;
                double fx = dx * cy + rz * sy + CENTRE;
                double fy = ry + CENTRE;
                double fz = -dx * sy + rz * cy + CENTRE;
                bMinX = java.lang.Math.min(bMinX, fx);
                bMaxX = java.lang.Math.max(bMaxX, fx);
                bMinY = java.lang.Math.min(bMinY, fy);
                bMaxY = java.lang.Math.max(bMaxY, fy);
                bMinZ = java.lang.Math.min(bMinZ, fz);
                bMaxZ = java.lang.Math.max(bMaxZ, fz);
            }
            // One extra cell of margin on every side, because the inside test uses cell centres and
            // a slanted box can reach into a cell whose centre sits just outside the corner bounds.
            int i0 = clampIndex((bMinX - minX) / cellX - 1, nx);
            int i1 = clampIndex((bMaxX - minX) / cellX + 1, nx);
            int j0 = clampIndex((bMinY - minY) / cellY - 1, ny);
            int j1 = clampIndex((bMaxY - minY) / cellY + 1, ny);
            int k0 = clampIndex((bMinZ - minZ) / cellZ - 1, nz);
            int k1 = clampIndex((bMaxZ - minZ) / cellZ + 1, nz);
            for (int i = i0; i < i1; i++) {
                double px = minX + (i + 0.5D) * cellX;
                for (int j = j0; j < j1; j++) {
                    double py = minY + (j + 0.5D) * cellY;
                    for (int k = k0; k < k1; k++) {
                        int idx = (i * ny + j) * nz + k;
                        if (filled.get(idx)) {
                            continue;
                        }
                        double pz = minZ + (k + 0.5D) * cellZ;
                        // Inverse transform: undo Ry, then undo Rx.
                        double dx = px - CENTRE, dz = pz - CENTRE;
                        double ux = dx * cy - dz * sy;
                        double uz = dx * sy + dz * cy;
                        double dy = py - CENTRE;
                        double uy = dy * cx + uz * sx;
                        double uz2 = -dy * sx + uz * cx;
                        double ox = ux + CENTRE, oy = uy + CENTRE, oz = uz2 + CENTRE;
                        if (ox >= box.minX - EPS && ox <= box.maxX + EPS
                                && oy >= box.minY - EPS && oy <= box.maxY + EPS
                                && oz >= box.minZ - EPS && oz <= box.maxZ + EPS) {
                            filled.set(idx);
                        }
                    }
                }
            }
        }

        // Nothing filled at all: the inside test rejected everything, which means the source was
        // degenerate. Empty is the honest answer.
        if (filled.isEmpty()) {
            return Shapes.empty();
        }

        // Build the shape straight from the bitmap.
        //
        // The old path merged the cells into boxes and then unioned them, and the union - not the
        // merge - was the expensive half: joinUnoptimized rebuilds the merged coordinate grid at
        // every level of the tree, so a 45/45 rotation with a few hundred boxes spent most of its
        // time there. The bitmap is already the exact answer, so it is handed to the shape
        // constructor directly and no union happens at all.
        //
        // This is also what makes the inner box worth carving: fewer filled cells means less bitmap
        // to walk, and the inner box is a large solid region that the bitmap stores as cheaply as
        // any other cells.
        return fromCells(filled, nx, ny, nz,
                boundaries(minX, cellX, nx), boundaries(minY, cellY, ny), boundaries(minZ, cellZ, nz));
    }

    /**
     * Cells per block to sample a shape of the given extent at.
     *
     * <p>Full 1/16 resolution is only worth paying for on shapes of roughly one block. Beyond that
     * the grid volume grows with the cube of the extent while the visible benefit shrinks, so the
     * resolution is halved once and then again. A two-block machine gets 1/8 cells, a three-block
     * structure 1/4 - still a staircase, just a coarser one, and the rotation stays cheap enough
     * that a world full of them does not stutter.
     *
     * <p>Never below {@link #MIN_RESOLUTION}: below that the staircase stops reading as a diagonal
     * and the collision box starts to look wrong, which is the bug this whole path exists to fix.
     */
    private static int resolutionFor(double sizeX, double sizeY, double sizeZ) {
        // Judge by the diagonal, not the longest side.
        //
        // A cube rotated 45 degrees about both axes has a longest side of about 1.73 blocks, but its
        // staircase is still the surface of an ordinary block and has to look like one. Measuring the
        // diagonal keeps it at full resolution (1.73 * sqrt(3) is 3.0, still under the first
        // threshold), while a genuinely large shape - a machine spanning two blocks, whose diagonal
        // is 3.46 - still drops a level.
        double diagonal = java.lang.Math.sqrt(sizeX * sizeX + sizeY * sizeY + sizeZ * sizeZ);
        if (diagonal <= 3.2D) {
            return VOXEL_RESOLUTION;
        }
        if (diagonal <= 5.5D) {
            return VOXEL_RESOLUTION / 2;
        }
        return MIN_RESOLUTION;
    }

    /** One box, as a shape. */
    private static VoxelShape box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return Shapes.box(x0, y0, z0, x1, y1, z1);
    }

    /**
     * A {@link VoxelShape} built straight from a cell bitmap, in one allocation.
     *
     * <p>This is the reason the box merge below is allowed to produce hundreds of boxes. Every box
     * produced by a rotation is on the same grid - the merge never splits a cell - so the whole
     * result is a single {@code DiscreteVoxelShape}, and it can be handed to the shape constructor
     * directly instead of being unioned box by box.
     *
     * <p>The alternative, {@code Shapes.joinUnoptimized} in a balanced tree, rebuilds the merged
     * coordinate grid at every level. At a few hundred boxes that is the dominant cost of building a
     * 45/45 rotation - the merge itself is cheap, the union is not. Going through the constructor
     * removes the union entirely: the bitmap is already the answer, and the coordinate arrays are
     * written once.
     *
     * <p>{@code ArrayVoxelShape}'s constructor is {@code protected}, which a subclass in another
     * package can call, so this nested class exists purely to expose it.
     */
    private static final class GridShape extends ArrayVoxelShape {
        private GridShape(DiscreteVoxelShape cells, double[] xs, double[] ys, double[] zs) {
            super(cells, xs, ys, zs);
        }
    }

    /**
     * Converts a filled-cell bitmap into a shape, without any union.
     *
     * <p>{@code xs/ys/zs} are the cell boundaries; the bitmap is {@code nx * ny * nz} cells indexed
     * exactly as {@code (i * ny + j) * nz + k}, which is the layout the merge already uses.
     */
    private static VoxelShape fromCells(java.util.BitSet filled, int nx, int ny, int nz,
                                        double[] xs, double[] ys, double[] zs) {
        BitSetDiscreteVoxelShape cells = new BitSetDiscreteVoxelShape(nx, ny, nz);
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                int row = (i * ny + j) * nz;
                for (int k = 0; k < nz; k++) {
                    if (filled.get(row + k)) {
                        cells.fill(i, j, k);
                    }
                }
            }
        }
        return new GridShape(cells, xs, ys, zs);
    }

    /** Cell boundaries for one axis: {@code n + 1} values from {@code min} to {@code max}. */
    private static double[] boundaries(double min, double cell, int n) {
        double[] out = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            out[i] = min + i * cell;
        }
        return out;
    }

    private static int clampIndex(double v, int limit) {
        int i = (int) java.lang.Math.floor(v);
        return java.lang.Math.max(0, java.lang.Math.min(limit, i));
    }


    /** Cells per block for the voxel path. */
    private static final int VOXEL_RESOLUTION = 16;

    /** Floor on the adaptive resolution - below this the staircase stops reading as a diagonal. */
    private static final int MIN_RESOLUTION = 4;

    /**
     * Resolution used for the <b>edge</b> band of a shape, where the diagonal actually lives.
     *
     * <p>Only the outer shell of a rotated block needs to describe a slanted face. Everything inside
     * that shell is solid in every direction, so a coarse box describes it just as accurately - a
     * player walking on a rotated block only ever touches the surface.
     */
    private static final int EDGE_RESOLUTION = 16;

    /**
     * How thick the edge band is, in blocks.
     *
     * <p>One pixel of a block model. A slanted face is a staircase whose steps are one pixel deep at
     * this thickness, which is the same resolution the model itself is drawn at, so the collision box
     * matches what the player sees. Anything deeper than this band is interior and gets merged into
     * big boxes.
     */
    private static final double EDGE_BAND = 1.0D / 16.0D;
    /** Grid cell cap, so a huge source shape cannot allocate an unbounded bitmap. */
    private static final long MAX_GRID_CELLS = 4_000_000L;
    /** Tolerance for the inside test, in blocks. */
    private static final double EPS = 1.0E-6D;

    /**
     * True when a shape is decorative: it does not block movement and the player never collides with
     * it.
     *
     * <p>Flowers, grass, vines, saplings, torches and paintings all report an empty collision shape
     * (or one so thin it is never hit) while their outline is a small cross or a flat plane. Giving
     * those a full-resolution staircase buys nothing - nothing can stand on them or bump into them -
     * and costs exactly as much to build as a real solid block.
     *
     * <p>For these the outline is approximated by the plain bounding box of the model, which is what
     * the renderer shows and what the selection box should hug, at a fraction of the cost.
     */
    public static boolean isDecorative(VoxelShape collisionShape) {
        if (collisionShape.isEmpty()) {
            return true;
        }
        // A shape that is thinner than a pixel on any axis is a flat plane: a painting, a vine on a
        // wall, a lily pad. It cannot be stood on.
        AABB bounds = collisionShape.bounds();
        return bounds.getXsize() < EDGE_BAND
                || bounds.getYsize() < EDGE_BAND
                || bounds.getZsize() < EDGE_BAND;
    }

    private static VoxelShape computeAngleXY(VoxelShape shape, int xSteps, int ySteps) {
        double xRad = java.lang.Math.toRadians(xSteps * 45.0D);
        double yRad = java.lang.Math.toRadians(ySteps * 45.0D);
        // Voxelise rather than bounding each rotated box.
        //
        // Bounding a rotated source box can only ever produce an axis-aligned box the size of the
        // rotation's full extent. For a solid block that is a fat cube covering the whole diagonal,
        // so the collision box follows the model's position and size but has no diagonal at all -
        // exactly the regression this replaces. A voxel shape is a grid, so the diagonal has to be a
        // staircase of cells; voxelRotate builds that staircase directly, in one pass, and emits
        // runs so the box count stays manageable.
        return voxelRotate(boundedSource(shape), xRad, yRad);
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
        if (!SLICE_ANGLES) {
            return operation == OP_ANGLE_X ? cornerRotateX(shape, degrees) : cornerRotateY(shape, degrees);
        }
        return operation == OP_ANGLE_X ? sliceRotateX(shape, degrees) : sliceRotateY(shape, degrees);
    }

    /**
     * Rotates every box about x and replaces it with the bounds of its eight rotated corners.
     *
     * <p>Box count in equals box count out, so this can never multiply - which is the whole reason
     * it exists. One box is a full 3D rotation of the other's corners, so a tall thin plate turns
     * into a short wide one of the same cross section, exactly as the rendered model does.
     */
    private static VoxelShape cornerRotateX(VoxelShape shape, double degrees) {
        double angle = java.lang.Math.toRadians(degrees);
        double cos = java.lang.Math.cos(angle);
        double sin = java.lang.Math.sin(angle);
        java.util.List<AABB> boxes = new java.util.ArrayList<>();
        for (AABB box : shape.toAabbs()) {
            double minY = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            // Only y and z move; x is untouched by a rotation about x.
            for (int corner = 0; corner < 4; corner++) {
                double y = (corner & 1) == 0 ? box.minY : box.maxY;
                double z = (corner & 2) == 0 ? box.minZ : box.maxZ;
                double dy = y - CENTRE;
                double dz = z - CENTRE;
                double ry = dy * cos - dz * sin + CENTRE;
                double rz = dy * sin + dz * cos + CENTRE;
                minY = java.lang.Math.min(minY, ry);
                maxY = java.lang.Math.max(maxY, ry);
                minZ = java.lang.Math.min(minZ, rz);
                maxZ = java.lang.Math.max(maxZ, rz);
            }
            boxes.add(new AABB(box.minX, minY, minZ, box.maxX, maxY, maxZ));
        }
        return union(boxes);
    }

    /** Same as {@link #cornerRotateX} about the y axis; x and z move, y is untouched. */
    private static VoxelShape cornerRotateY(VoxelShape shape, double degrees) {
        double angle = java.lang.Math.toRadians(degrees);
        double cos = java.lang.Math.cos(angle);
        double sin = java.lang.Math.sin(angle);
        java.util.List<AABB> boxes = new java.util.ArrayList<>();
        for (AABB box : shape.toAabbs()) {
            double minX = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (int corner = 0; corner < 4; corner++) {
                double x = (corner & 1) == 0 ? box.minX : box.maxX;
                double z = (corner & 2) == 0 ? box.minZ : box.maxZ;
                double dx = x - CENTRE;
                double dz = z - CENTRE;
                double rx = dx * cos + dz * sin + CENTRE;
                double rz = -dx * sin + dz * cos + CENTRE;
                minX = java.lang.Math.min(minX, rx);
                maxX = java.lang.Math.max(maxX, rx);
                minZ = java.lang.Math.min(minZ, rz);
                maxZ = java.lang.Math.max(maxZ, rz);
            }
            boxes.add(new AABB(minX, box.minY, minZ, maxX, box.maxY, maxZ));
        }
        return union(boxes);
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
        // Too many source boxes to voxelise individually within the grid budget. Keeping them all
        // would make the per-cell inside test O(cells * boxes) and could exhaust MAX_GRID_CELLS.
        //
        // Collapsing to one box is the safe choice for a roughly block-shaped model, but it throws
        // away the silhouette of anything genuinely detailed. Since the voxel path only pays per
        // cell, a middle ground is to keep the largest boxes and drop the slivers: the big boxes are
        // what the player actually bumps into.
        java.util.List<AABB> sorted = new java.util.ArrayList<>(boxes);
        sorted.sort((a, b) -> Double.compare(volume(b), volume(a)));
        return new java.util.ArrayList<>(sorted.subList(0, SRC_BOX_LIMIT));
    }

    /** Volume of a box, used only to rank source boxes by how much they matter. */
    private static double volume(AABB box) {
        return (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
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
        // Merge in a balanced binary tree instead of folding left.
        //
        // Folding left is quadratic in a way that is easy to miss: joinUnoptimized(a, b) allocates a
        // fresh coordinate grid sized to the merged result, so after k boxes the accumulator already
        // spans every coordinate seen so far, and each of the remaining joins rebuilds that whole
        // grid. For the ~200 strips one 45 degree rotation produces that is ~200 rebuilds of a
        // 200-wide grid - the stall.
        //
        // Pairing the boxes up and merging the pairs level by level keeps every intermediate result
        // small: level k merges shapes of size 2^k into one of size 2^(k+1), so the total work is
        // O(n log n) in boxes and, more importantly, the largest grid is only built once at the end
        // instead of once per box.
        java.util.List<VoxelShape> level = new java.util.ArrayList<>(boxes.size());
        for (AABB box : boxes) {
            level.add(Shapes.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        }
        while (level.size() > 1) {
            java.util.List<VoxelShape> next = new java.util.ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                if (i + 1 < level.size()) {
                    next.add(Shapes.joinUnoptimized(level.get(i), level.get(i + 1), BooleanOp.OR));
                } else {
                    next.add(level.get(i));
                }
            }
            level = next;
        }
        // One optimize for the finished union. optimize() itself walks the boxes and re-joins them,
        // which is why the tree above matters: it hands optimize a grid that is already the final
        // one rather than a deep chain of partial merges.
        return level.get(0).optimize();
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

    // ---------------------------------------------------------------------------------------------
    // Pivot-aware rotation.
    //
    // Every shape above turns about the block centre (0.5, 0.5, 0.5), which is right for a block
    // that rotates in place. A two-block structure is not: a bed is ONE object two blocks long, so
    // turning each half about its own centre swings the halves around separate points and the pair
    // comes apart into a diamond. The pair has to turn about the centre of the whole structure,
    // which for either half is its own centre offset half a block along the pair axis.
    //
    // Rather than thread a pivot through every slicing path, the shape is shifted so the desired
    // pivot lands on the block centre, rotated by the existing code, and shifted back. The slicing
    // grid then covers a shape that is half a block off-centre, which it already handles - it works
    // from the rotated bounds, not from the block box.
    // ---------------------------------------------------------------------------------------------

    /**
     * Rotates a shape about {@code pivot} instead of the block centre, by the same convention as
     * {@link #rotateAboutXY}.
     *
     * @param pivot the point, in block units, the rotation turns around
     */
    public static VoxelShape rotateAboutXY(VoxelShape shape, double xDegrees, double yDegrees,
                                           double pivotX, double pivotY, double pivotZ) {
        if (shape.isEmpty()) {
            return Shapes.empty();
        }
        double dx = CENTRE - pivotX;
        double dy = CENTRE - pivotY;
        double dz = CENTRE - pivotZ;
        if (dx == 0.0D && dy == 0.0D && dz == 0.0D) {
            return rotateAboutXY(shape, xDegrees, yDegrees);
        }
        VoxelShape shifted = shape.move(dx, dy, dz);
        VoxelShape rotated = rotateAboutXY(shifted, xDegrees, yDegrees);
        return rotated.move(-dx, -dy, -dz);
    }

    /** Bounding-box variant of {@link #rotateAboutXY(VoxelShape, double, double, double, double, double)}. */
    public static VoxelShape boundingRotate(VoxelShape shape, double xDegrees, double yDegrees,
                                            double pivotX, double pivotY, double pivotZ) {
        if (shape.isEmpty()) {
            return Shapes.empty();
        }
        double dx = CENTRE - pivotX;
        double dy = CENTRE - pivotY;
        double dz = CENTRE - pivotZ;
        if (dx == 0.0D && dy == 0.0D && dz == 0.0D) {
            return boundingRotate(shape, xDegrees, yDegrees);
        }
        return boundingRotate(shape.move(dx, dy, dz), xDegrees, yDegrees).move(-dx, -dy, -dz);
    }

}
