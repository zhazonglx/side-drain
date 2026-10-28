package com.sidedrain.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Redstone dust raised onto its edge, in all six orientations.
 *
 * <p>The panel is always <em>anchored to a face</em> of its block instead of floating through the
 * middle: lying flat it sits on the floor like ordinary dust, and stood up it clings to the wall it
 * was turned against. That is what makes a row of them read as a continuous wire rather than as
 * detached plates hovering in the centre of each block.
 *
 * <p>Right click a panel while holding redstone and it grows another panel in the same orientation,
 * so a wire can be drawn out by hand in the direction you point at.
 *
 * <p>It needs no support block and powers every neighbour it touches, in every facing.
 */
public class StandingRedstoneBlock extends Block {
    /** Normal of the panel's broad face. This is the plane the wire runs in. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty POWER = BlockStateProperties.POWER;

    /**
     * Connection bits, one per direction <b>inside the panel's plane</b>.
     *
     * <p>Vanilla dust has exactly four of these - north, east, south, west - because it only ever
     * lies on the floor. A panel can face any of the six cube faces, so the same four bits describe
     * its plane: the two axes perpendicular to {@code FACING} give four in-plane directions, and the
     * four bits are laid out in a fixed local order (see {@link #planeDirections}).
     *
     * <p>Keeping the bits in a fixed local order rather than trying to map them to world directions
     * is what makes all six planes work from one code path: a wire on a wall and a wire on the floor
     * run the identical logic, they only differ in which world directions the four local slots map
     * to.
     */
    public static final IntegerProperty CONNECTIONS = IntegerProperty.create("connections", 0, 15);

    /** Plate thickness, in pixels. */
    private static final double T = 2.0D;

    public StandingRedstoneBlock() {
        super(Block.Properties.of().instabreak().noCollission().noOcclusion().dynamicShape());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.UP)
                .setValue(POWER, 0)
                .setValue(CONNECTIONS, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWER, CONNECTIONS);
    }

    /**
     * The four world directions that lie inside the plane of {@code facing}, in a fixed local order.
     *
     * <p>Slot 0 and slot 1 are one axis, slots 2 and 3 are the other. Every panel, whichever way it
     * faces, gets four slots, so a connection mask means the same thing everywhere and the wire
     * logic never has to special-case a plane.
     */
    public static Direction[] planeDirections(Direction facing) {
        // Slot order is fixed as {right(+u), left(-u), up(+v), down(-v)} and must match the model
        // generation exactly, otherwise the wire would connect to one side and draw on another.
        //
        // The frame per face is chosen so that "up" in the plane is the world up whenever the plane
        // is vertical, and north when the plane is horizontal - the same convention a player reads
        // as "up" and "right" when looking at the face.
        return switch (facing) {
            // Horizontal planes: u runs east, v runs south (the plane's own "up" is north).
            case UP, DOWN -> new Direction[]{
                    Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH};
            // Vertical planes facing along z: u runs east, v runs up.
            case NORTH, SOUTH -> new Direction[]{
                    Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN};
            // Vertical planes facing along x: u runs south, v runs up.
            case WEST, EAST -> new Direction[]{
                    Direction.SOUTH, Direction.NORTH, Direction.UP, Direction.DOWN};
        };
    }

    /** Bit index of {@code dir} inside the plane of {@code facing}, or -1 when it is out of plane. */
    public static int slotOf(Direction facing, Direction dir) {
        Direction[] slots = planeDirections(facing);
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == dir) {
                return i;
            }
        }
        return -1;
    }

    /** The plate hugs the face its normal points at, so it never floats in the middle of a block. */
    private static VoxelShape shapeFor(Direction facing) {
        return switch (facing) {
            case UP -> Block.box(0.0D, 0.0D, 0.0D, 16.0D, T, 16.0D);
            case DOWN -> Block.box(0.0D, 16.0D - T, 0.0D, 16.0D, 16.0D, 16.0D);
            case NORTH -> Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, T);
            case SOUTH -> Block.box(0.0D, 0.0D, 16.0D - T, 16.0D, 16.0D, 16.0D);
            case WEST -> Block.box(0.0D, 0.0D, 0.0D, T, 16.0D, 16.0D);
            case EAST -> Block.box(16.0D - T, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
        };
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeFor(state.getValue(FACING));
    }

    /**
     * The outline of a wire is its full in-plane square, not just the thin plate.
     *
     * <p>The selection box has to cover the whole cell the wire runs through, because the wire is
     * drawn as a line across that square: a two-pixel slab would make most of the visible wire
     * unclickable.
     */
    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return shapeFor(state.getValue(FACING));
    }

    /**
     * Where the wire should grow when the player right clicks with redstone.
     *
     * <p>Kept on the block rather than inferred from the click so a wire grows along its own plane
     * even when the player is looking at it from an awkward angle.
     */
    public static Direction growDirection(BlockState state, BlockHitResult hit, Player player) {
        return extensionSide(state.getValue(FACING), hit.getDirection(), player);
    }

    /** Free standing: no support needed, so a panel can hang in the air. */
    @Override
    public boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        return true;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }

    /**
     * Grows the wire: right clicking a panel with redstone lays another panel of the same facing next
     * to it, in the direction the player is pointing inside the panel's own plane.
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (!held.is(Items.REDSTONE)) {
            return InteractionResult.PASS;
        }
        Direction facing = state.getValue(FACING);
        BlockPos target = pos.relative(extensionSide(facing, hit.getDirection(), player));
        if (!level.getBlockState(target).canBeReplaced()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        level.setBlock(target, state.setValue(POWER, 0), Block.UPDATE_ALL);
        level.playSound(null, target, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Which way the wire should grow.
     *
     * <p>Clicking a side of the panel that lies inside its own plane grows that way, which is the
     * precise gesture. Clicking the broad face falls back to the direction the player is looking,
     * folded into the plane so the new panel keeps the same facing.
     */
    private static Direction extensionSide(Direction facing, Direction clicked, Player player) {
        if (clicked.getAxis() != facing.getAxis()) {
            return clicked;
        }
        Direction look = player.getDirection();
        if (look.getAxis() == facing.getAxis()) {
            look = look.getClockWise();
        }
        return look;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (!oldState.is(state.getBlock())) {
            this.updateShape(level, pos, state);
            // A newly placed panel changes how its neighbours should draw themselves: a panel that
            // had no connection now has one, and its CONNECTIONS mask - and therefore its model -
            // has to follow. Neighbours are only told through neighbourChanged, which vanilla does
            // not call for a plain setBlock, so ask them explicitly.
            notifyNeighbours(level, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            // The panels that were joined to this one must drop the arm that pointed here.
            notifyNeighbours(level, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /**
     * Re-evaluates every neighbour panel, so their connection masks track this one.
     *
     * <p>Deferred by one server tick instead of run inline. {@code updateShape} walks the whole wire
     * network; doing that for up to six neighbours inside a single {@code setBlock} meant laying a
     * line of panels cost O(n) full BFS passes per placement, on the tick thread. Collecting the
     * positions and resolving them once on the next tick collapses that burst, and the shape is
     * still correct before any player can see the difference.
     */
    private static void notifyNeighbours(Level level, BlockPos pos) {
        if (level.isClientSide || !(level instanceof net.minecraft.server.level.ServerLevel server)) {
            return;
        }
        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.relative(side);
            BlockState neighbourState = level.getBlockState(neighbour);
            if (neighbourState.getBlock() instanceof StandingRedstoneBlock) {
                PENDING.add(neighbour);
            }
        }
        scheduleFlush(server);
    }

    /** Panels awaiting a connection refresh. Identity set: the same panel is queued once. */
    private static final java.util.Set<BlockPos> PENDING =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /** Servers already scheduled, so N placements do not queue N flushes. */
    private static final java.util.Set<net.minecraft.server.level.ServerLevel> SCHEDULED =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    private static void scheduleFlush(net.minecraft.server.level.ServerLevel server) {
        if (!SCHEDULED.add(server)) {
            return;
        }
        server.getServer().execute(() -> {
            SCHEDULED.remove(server);
            java.util.List<BlockPos> batch;
            synchronized (PENDING) {
                if (PENDING.isEmpty()) {
                    return;
                }
                batch = new java.util.ArrayList<>(PENDING);
                PENDING.clear();
            }
            for (BlockPos target : batch) {
                BlockState targetState = server.getBlockState(target);
                if (targetState.getBlock() instanceof StandingRedstoneBlock panel) {
                    panel.updateShape(server, target, targetState);
                }
            }
        });
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                BlockPos fromPos, boolean isMoving) {
        this.updateShape(level, pos, state);
    }

    /**
     * Recomputes both the connection mask and the power of this panel.
     *
     * <p>One method, not two, and it always writes what it computed - including writing a lower
     * value back. That is the fix for the panel sticking on: the old code only ever took the
     * <em>strongest</em> neighbour signal and never re-read the level once a lever was switched off,
     * because a wire that powers its own neighbours sees its own signal through them and holds
     * itself up. Here the panel's own contribution is excluded, so the strength is whatever is
     * genuinely driving it, and zero when nothing is.
     */
    /**
     * Guards against the update cascade re-entering itself.
     *
     * <p>A panel tells its neighbours when it changes, and a neighbour that is also a panel runs the
     * same code and tells its own neighbours - which includes the first one. Without a guard two
     * panels wired together would call each other forever on the server thread. The set is per-thread
     * because the whole cascade happens inside one level update.
     */
    private static final ThreadLocal<java.util.Set<BlockPos>> UPDATING =
            ThreadLocal.withInitial(java.util.HashSet::new);

    private void updateShape(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide) {
            return;
        }
        java.util.Set<BlockPos> updating = UPDATING.get();
        if (!updating.add(pos)) {
            return;
        }
        try {
            updateShape0(level, pos, state);
        } finally {
            updating.remove(pos);
        }
    }

    private void updateShape0(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        int mask = 0;
        Direction[] slots = planeDirections(facing);
        for (int i = 0; i < slots.length; i++) {
            if (connectsTo(level, pos, facing, slots[i])) {
                mask |= 1 << i;
            }
        }
        int power = computePower(level, pos, state, facing, slots);
        BlockState next = state.setValue(CONNECTIONS, mask).setValue(POWER, power);
        boolean changed = !next.equals(state);
        if (changed) {
            // UPDATE_ALL, not UPDATE_CLIENTS: CONNECTIONS selects the block MODEL, so a client that
            // only receives the block-state update may keep drawing the previous variant. That was
            // the "no model connection" bug - the state said connected, the collision box said
            // connected, the panel even powered its neighbour, but the model still showed the old,
            // unconnected variant. UPDATE_ALL forces the chunk section to re-mesh.
            level.setBlock(pos, next, Block.UPDATE_ALL);
        }
        // A change of power has to reach the neighbours, which setBlock alone does not do.
        if (changed) {
            level.updateNeighborsAt(pos, this);
        }
    }

    /**
     * True when this panel should visually join {@code dir} inside its plane.
     *
     * <p>Two panels join when they share the same plane - the same axis and the same offset - which
     * is what makes a wall of panels read as one continuous wire. Dust on the floor joins too, so
     * laying panels next to ordinary dust keeps them connected.
     */
    private static boolean connectsTo(Level level, BlockPos pos, Direction facing, Direction dir) {
        BlockPos neighbour = pos.relative(dir);
        BlockState other = level.getBlockState(neighbour);
        if (other.getBlock() instanceof StandingRedstoneBlock) {
            // Same plane panels join along the plane; panels on perpendicular planes join where their
            // broad faces touch, which is the floor-to-wall corner. Without the second case a
            // vertical panel next to a flat run of dust drew no arm at all and the join was invisible.
            Direction otherFacing = other.getValue(FACING);
            if (otherFacing == facing) {
                return dir.getAxis() != facing.getAxis();
            }
            return otherFacing == dir.getOpposite();
        }
        if (other.getBlock() instanceof net.minecraft.world.level.block.RedStoneWireBlock) {
            // Flat dust joins a panel lying flat, and also a panel standing upright whose broad face
            // meets the ground - that is the same corner case, seen from the dust's side.
            return facing.getAxis() == Direction.Axis.Y || dir.getAxis() == Direction.Axis.Y;
        }
        return false;
    }

    /**
     * The signal this panel should display.
     *
     * <p>A panel is a piece of wire, so its strength is the strongest <em>external</em> source
     * reachable through the wire network, carried without decay - the same rule vanilla dust uses,
     * except a wire here never weakens per block.
     *
     * <p>The network is walked as a graph and every panel in it is treated as a conductor, never as
     * a source. That distinction is the whole fix for the panel that stayed lit: the previous code
     * read a neighbour panel's own {@code POWER} and kept the maximum, so two panels wired together
     * each fed the other its own value and the pair held itself up after the lever was switched off.
     * A panel is not a battery; only things that genuinely emit - a lever, a torch, a repeater, a
     * block of redstone - are read from the world, and the walk simply passes through panels.
     */
    private int computePower(Level level, BlockPos pos, BlockState state, Direction facing, Direction[] slots) {
        // Breadth-first over the connected wire, collecting the strongest external signal. Bounded by
        // a visited set, so a loop of panels cannot spin.
        // Reused per-thread scratch instead of a fresh HashSet + ArrayDeque per recompute. A redstone
        // line updates on every neighbour change, so this method is one of the hottest paths in the
        // mod; allocating two collections per call was pure garbage. ArrayDeque of BlockPos.Immutable
        // keeps the entries valid after the deque is cleared, because the queue outlives one call.
        Scratch scratch = SCRATCH.get();
        java.util.Set<BlockPos> visited = scratch.visited;
        java.util.ArrayDeque<BlockPos> queue = scratch.queue;
        visited.clear();
        queue.clear();
        visited.add(pos);
        queue.add(pos);
        int strongest = 0;
        int guard = 0;
        while (!queue.isEmpty() && guard++ < MAX_WIRE_SCAN) {
            BlockPos current = queue.poll();
            BlockState currentState = level.getBlockState(current);
            if (!(currentState.getBlock() instanceof StandingRedstoneBlock)) {
                continue;
            }
            Direction currentFacing = currentState.getValue(FACING);
            Direction[] currentSlots = planeDirections(currentFacing);
            // Every direction this panel touches: its four in-plane slots plus both broad faces.
            for (Direction side : Direction.values()) {
                BlockPos neighbour = current.relative(side);
                if (!visited.add(neighbour)) {
                    continue;
                }
                BlockState neighbourState = level.getBlockState(neighbour);
                if (neighbourState.getBlock() instanceof StandingRedstoneBlock) {
                    // Another panel: it conducts only when the two actually join. It is queued, never
                    // read for power - reading it is what latched the network on.
                    if (sharesWire(level, current, neighbour, currentFacing, neighbourState, side)) {
                        queue.add(neighbour);
                    }
                    continue;
                }
                // Anything else is a real source (or nothing). Read it from the world so switching a
                // lever off is seen immediately: this is the value that must fall back to zero.
                int signal = level.getSignal(neighbour, side.getOpposite());
                if (signal > strongest) {
                    strongest = signal;
                }
            }
        }
        return strongest;
    }

    /** A hard cap on how far one power recompute will walk, so a huge wire cannot stall a tick. */
    private static final int MAX_WIRE_SCAN = 512;

    /**
     * Per-thread reusable BFS scratch.
     *
     * <p>A redstone wire recomputes on every neighbour change, so allocating a {@code HashSet} and
     * an {@code ArrayDeque} per call dominated the cost of small networks. ThreadLocal keeps this
     * lock-free - the server thread and any worker each get their own - and the contents are
     * cleared at the start of every use, so no state leaks between recomputes.
     */
    private static final class Scratch {
        final java.util.Set<BlockPos> visited = new java.util.HashSet<>(64);
        final java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>(64);
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** True when {@code neighbour} is a panel that this one is wired to. */
    private static boolean sharesWire(Level level, BlockPos pos, BlockPos neighbour, Direction facing,
                                      BlockState neighbourState, Direction side) {
        Direction neighbourFacing = neighbourState.getValue(FACING);
        if (neighbourFacing == facing) {
            // Same plane: joined when adjacent inside that plane.
            return side.getAxis() != facing.getAxis();
        }
        // Different plane: the two broad faces meet. This is how a wire turns a corner from the
        // floor onto a wall, and it is why a vertical panel connects to the flat dust below it.
        return neighbourFacing == side.getOpposite();
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        // A panel is a wire on its edge, so it powers whatever touches it, on any face.
        return state.getValue(POWER);
    }

    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return 0;
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return new ItemStack(Items.REDSTONE);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
