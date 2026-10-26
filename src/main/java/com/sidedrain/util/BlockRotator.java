package com.sidedrain.util;

import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Turns blocks that already have an orientation of their own, in place.
 *
 * <p>This only handles states the block genuinely supports. Anything that cannot be flipped in its
 * own block state - a chest has no upside-down form, a slab's {@code type} is not a rotation - is
 * left to the wrench, which puts it in a {@link com.sidedrain.block.RotatedBlock} carrier instead.
 *
 * <p>{@code pitch == true} flips over: stairs, trapdoors, levers, buttons, lanterns, pillars and
 * six-way blocks all reach their upside-down form.
 * <p>{@code pitch == false} spins around the vertical axis.
 */
public final class BlockRotator {
    private static final Direction[] VERTICAL_RING = {
            Direction.DOWN, Direction.NORTH, Direction.UP, Direction.SOUTH
    };
    private static final Direction[] HORIZONTAL_RING = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final RailShape[] RAIL_RING = {
            RailShape.NORTH_SOUTH, RailShape.EAST_WEST,
            RailShape.ASCENDING_EAST, RailShape.ASCENDING_WEST,
            RailShape.ASCENDING_NORTH, RailShape.ASCENDING_SOUTH
    };

    private BlockRotator() {
    }

    /** Returns the rotated state, or {@code null} when this block cannot be turned in place. */
    @Nullable
    public static BlockState rotate(BlockState state, boolean pitch) {
        return pitch ? rotateVertical(state) : rotateHorizontal(state);
    }

    /** True when the block has a real upside-down form, so it should not be wrapped. */
    public static boolean hasVerticalForm(BlockState state) {
        return rotateVertical(state) != null;
    }

    // ------------------------------------------------------------------ vertical (top <-> bottom)

    @Nullable
    private static BlockState rotateVertical(BlockState state) {
        // A door's HALF is which half of the two-block door it is, not an orientation, so flipping
        // it would break the door in two. Doors are turned around instead.
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
            return rotateHorizontal(state);
        }
        // A bed has no vertical form at all: its two halves lie on the floor and PART says which is
        // which. A pitch click turns it right around instead (180 degrees), so the head ends up where
        // the foot was; a yaw click turns it a quarter turn. Two distinct, useful answers.
        if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            BlockState quarter = rotateHorizontal(state);
            return quarter == null ? null : rotateHorizontal(quarter);
        }
        if (has(state, BlockStateProperties.HALF)) {
            Half half = state.getValue(BlockStateProperties.HALF);
            return state.setValue(BlockStateProperties.HALF, half == Half.TOP ? Half.BOTTOM : Half.TOP);
        }
        if (has(state, BlockStateProperties.ATTACH_FACE)) {
            AttachFace face = state.getValue(BlockStateProperties.ATTACH_FACE);
            AttachFace next = switch (face) {
                case FLOOR -> AttachFace.WALL;
                case WALL -> AttachFace.CEILING;
                case CEILING -> AttachFace.FLOOR;
            };
            return state.setValue(BlockStateProperties.ATTACH_FACE, next);
        }
        if (has(state, BlockStateProperties.FACING)) {
            Direction next = step(state.getValue(BlockStateProperties.FACING), VERTICAL_RING,
                    BlockStateProperties.FACING);
            if (next != null) {
                return state.setValue(BlockStateProperties.FACING, next);
            }
        }
        if (has(state, BlockStateProperties.FACING_HOPPER)) {
            Direction next = step(state.getValue(BlockStateProperties.FACING_HOPPER), VERTICAL_RING,
                    BlockStateProperties.FACING_HOPPER);
            if (next != null) {
                return state.setValue(BlockStateProperties.FACING_HOPPER, next);
            }
        }
        if (has(state, BlockStateProperties.HANGING)) {
            return state.setValue(BlockStateProperties.HANGING, !state.getValue(BlockStateProperties.HANGING));
        }
        if (has(state, BlockStateProperties.AXIS)) {
            return state.setValue(BlockStateProperties.AXIS, nextAxis(state.getValue(BlockStateProperties.AXIS)));
        }
        if (has(state, BlockStateProperties.ORIENTATION)) {
            FrontAndTop current = state.getValue(BlockStateProperties.ORIENTATION);
            FrontAndTop next = FrontAndTop.fromFrontAndTop(current.front(), flipVertically(current.top()));
            if (next != null && BlockStateProperties.ORIENTATION.getPossibleValues().contains(next)) {
                return state.setValue(BlockStateProperties.ORIENTATION, next);
            }
        }
        // No upside-down form in the block state itself: the wrench wraps it in a carrier instead.
        return null;
    }

    // ------------------------------------------------------------------ horizontal (left <-> right)

    @Nullable
    private static BlockState rotateHorizontal(BlockState state) {
        if (has(state, BlockStateProperties.FACING)) {
            Direction next = step(state.getValue(BlockStateProperties.FACING), HORIZONTAL_RING,
                    BlockStateProperties.FACING);
            if (next != null) {
                return state.setValue(BlockStateProperties.FACING, next);
            }
        }
        if (has(state, BlockStateProperties.HORIZONTAL_FACING)) {
            Direction next = step(state.getValue(BlockStateProperties.HORIZONTAL_FACING), HORIZONTAL_RING,
                    BlockStateProperties.HORIZONTAL_FACING);
            if (next != null) {
                return state.setValue(BlockStateProperties.HORIZONTAL_FACING, next);
            }
        }
        if (has(state, BlockStateProperties.FACING_HOPPER)) {
            Direction next = step(state.getValue(BlockStateProperties.FACING_HOPPER), HORIZONTAL_RING,
                    BlockStateProperties.FACING_HOPPER);
            if (next != null) {
                return state.setValue(BlockStateProperties.FACING_HOPPER, next);
            }
        }
        if (has(state, BlockStateProperties.ORIENTATION)) {
            FrontAndTop current = state.getValue(BlockStateProperties.ORIENTATION);
            FrontAndTop next = FrontAndTop.fromFrontAndTop(rotateYaw(current.front()), current.top());
            if (next != null && BlockStateProperties.ORIENTATION.getPossibleValues().contains(next)) {
                return state.setValue(BlockStateProperties.ORIENTATION, next);
            }
        }
        if (has(state, BlockStateProperties.RAIL_SHAPE) || has(state, BlockStateProperties.RAIL_SHAPE_STRAIGHT)) {
            EnumProperty<RailShape> rail = has(state, BlockStateProperties.RAIL_SHAPE)
                    ? BlockStateProperties.RAIL_SHAPE : BlockStateProperties.RAIL_SHAPE_STRAIGHT;
            RailShape current = state.getValue(rail);
            int start = 0;
            for (int i = 0; i < RAIL_RING.length; i++) {
                if (RAIL_RING[i] == current) {
                    start = (i + 1) % RAIL_RING.length;
                    break;
                }
            }
            for (int i = 0; i < RAIL_RING.length; i++) {
                RailShape candidate = RAIL_RING[(start + i) % RAIL_RING.length];
                if (rail.getPossibleValues().contains(candidate)) {
                    return state.setValue(rail, candidate);
                }
            }
        }
        if (has(state, BlockStateProperties.ROTATION_16)) {
            int current = state.getValue(BlockStateProperties.ROTATION_16);
            return state.setValue(BlockStateProperties.ROTATION_16, Math.floorMod(current + 4, 16));
        }
        if (has(state, BlockStateProperties.AXIS)) {
            Direction.Axis axis = state.getValue(BlockStateProperties.AXIS);
            Direction.Axis next = switch (axis) {
                case X -> Direction.Axis.Z;
                case Z -> Direction.Axis.X;
                case Y -> Direction.Axis.Y;
            };
            if (next != axis) {
                return state.setValue(BlockStateProperties.AXIS, next);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean has(BlockState state, Property<?> property) {
        for (Property<?> candidate : state.getProperties()) {
            if (candidate == property) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Direction step(Direction current, Direction[] ring,
                                  net.minecraft.world.level.block.state.properties.DirectionProperty property) {
        int start = 0;
        for (int i = 0; i < ring.length; i++) {
            if (ring[i] == current) {
                start = (i + 1) % ring.length;
                break;
            }
        }
        for (int i = 0; i < ring.length; i++) {
            Direction candidate = ring[(start + i) % ring.length];
            if (property.getPossibleValues().contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Direction.Axis nextAxis(Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.Axis.Y;
            case Y -> Direction.Axis.Z;
            case Z -> Direction.Axis.X;
        };
    }

    private static Direction flipVertically(Direction direction) {
        return switch (direction) {
            case UP -> Direction.DOWN;
            case DOWN -> Direction.UP;
            default -> direction;
        };
    }

    private static Direction rotateYaw(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> direction;
        };
    }
}
