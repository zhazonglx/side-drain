package com.sidedrain.util;

import javax.annotation.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;

/**
 * The thirty forms of the rotatable hopper: six body facings times five spout facings.
 *
 * <p>A hopper is two independent halves and this enum keeps them that way:
 * <ul>
 *   <li>{@link #body()} is the face the wide bucket opens towards. Items are pulled out of
 *       the container on that side.</li>
 *   <li>{@link #spout()} is the direction the narrow tail points. Items are pushed into the
 *       container on that side.</li>
 * </ul>
 * The spout never points back into its own bucket, so each body has five partners and the
 * enum has thirty entries.
 */
public enum HopperOrientation implements StringRepresentable {
    UP_DOWN("up_down", Direction.UP, Direction.DOWN),
    UP_NORTH("up_north", Direction.UP, Direction.NORTH),
    UP_SOUTH("up_south", Direction.UP, Direction.SOUTH),
    UP_EAST("up_east", Direction.UP, Direction.EAST),
    UP_WEST("up_west", Direction.UP, Direction.WEST),
    DOWN_UP("down_up", Direction.DOWN, Direction.UP),
    DOWN_NORTH("down_north", Direction.DOWN, Direction.NORTH),
    DOWN_SOUTH("down_south", Direction.DOWN, Direction.SOUTH),
    DOWN_EAST("down_east", Direction.DOWN, Direction.EAST),
    DOWN_WEST("down_west", Direction.DOWN, Direction.WEST),
    NORTH_UP("north_up", Direction.NORTH, Direction.UP),
    NORTH_DOWN("north_down", Direction.NORTH, Direction.DOWN),
    NORTH_SOUTH("north_south", Direction.NORTH, Direction.SOUTH),
    NORTH_EAST("north_east", Direction.NORTH, Direction.EAST),
    NORTH_WEST("north_west", Direction.NORTH, Direction.WEST),
    SOUTH_UP("south_up", Direction.SOUTH, Direction.UP),
    SOUTH_DOWN("south_down", Direction.SOUTH, Direction.DOWN),
    SOUTH_NORTH("south_north", Direction.SOUTH, Direction.NORTH),
    SOUTH_EAST("south_east", Direction.SOUTH, Direction.EAST),
    SOUTH_WEST("south_west", Direction.SOUTH, Direction.WEST),
    EAST_UP("east_up", Direction.EAST, Direction.UP),
    EAST_DOWN("east_down", Direction.EAST, Direction.DOWN),
    EAST_NORTH("east_north", Direction.EAST, Direction.NORTH),
    EAST_SOUTH("east_south", Direction.EAST, Direction.SOUTH),
    EAST_WEST("east_west", Direction.EAST, Direction.WEST),
    WEST_UP("west_up", Direction.WEST, Direction.UP),
    WEST_DOWN("west_down", Direction.WEST, Direction.DOWN),
    WEST_NORTH("west_north", Direction.WEST, Direction.NORTH),
    WEST_SOUTH("west_south", Direction.WEST, Direction.SOUTH),
    WEST_EAST("west_east", Direction.WEST, Direction.EAST);

    private static final Direction[] BODY_ORDER = {
            Direction.UP, Direction.DOWN, Direction.NORTH,
            Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    private static final Direction[] SPOUT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.EAST,
            Direction.SOUTH, Direction.WEST, Direction.UP
    };

    private final String name;
    private final Direction body;
    private final Direction spout;

    HopperOrientation(String name, Direction body, Direction spout) {
        this.name = name;
        this.body = body;
        this.spout = spout;
    }

    /** Face the bucket opens towards; items are pulled from this side. */
    public Direction body() {
        return this.body;
    }

    /** Direction the tail points; items are pushed into this side. */
    public Direction spout() {
        return this.spout;
    }

    /** The face of the source container that looks back at this hopper. */
    public Direction sourceSide() {
        return this.body.getOpposite();
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /** Walks the body to the next facing that is not already used by the spout. */
    public HopperOrientation nextBody() {
        Direction next = this.body;
        for (int i = 0; i < BODY_ORDER.length; i++) {
            next = BODY_ORDER[(indexOf(BODY_ORDER, this.body) + 1 + i) % BODY_ORDER.length];
            if (next != this.spout) {
                break;
            }
        }
        return of(next, this.spout);
    }

    /** Walks the spout to the next facing that is not already used by the body. */
    public HopperOrientation nextSpout() {
        Direction next = this.spout;
        for (int i = 0; i < SPOUT_ORDER.length; i++) {
            next = SPOUT_ORDER[(indexOf(SPOUT_ORDER, this.spout) + 1 + i) % SPOUT_ORDER.length];
            if (next != this.body) {
                break;
            }
        }
        return of(this.body, next);
    }

    private static int indexOf(Direction[] order, Direction value) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == value) {
                return i;
            }
        }
        return 0;
    }

    public static HopperOrientation of(Direction body, Direction spout) {
        if (body == spout) {
            spout = spout.getOpposite();
        }
        for (HopperOrientation value : values()) {
            if (value.body == body && value.spout == spout) {
                return value;
            }
        }
        return UP_DOWN;
    }

    /** The form a freshly placed hopper should take for a given spout direction. */
    public static HopperOrientation fromSpout(Direction spout) {
        return of(Direction.UP, spout == Direction.UP ? Direction.DOWN : spout);
    }

    @Nullable
    public static HopperOrientation byName(String name) {
        for (HopperOrientation value : values()) {
            if (value.name.equals(name)) {
                return value;
            }
        }
        return null;
    }

    /** True when the tail leaves straight out of the back of the bucket. */
    public boolean spoutIsOpposite() {
        return this.spout == this.body.getOpposite();
    }
}
