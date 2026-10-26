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
    /** Normal of the panel's broad face. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty POWER = BlockStateProperties.POWER;

    /** Plate thickness, in pixels. */
    private static final double T = 2.0D;

    public StandingRedstoneBlock() {
        super(Block.Properties.of().instabreak().noCollission().noOcclusion().dynamicShape());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.UP)
                .setValue(POWER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWER);
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
            this.updatePower(level, pos, state);
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        this.updatePower(level, pos, state);
    }

    /** Same strength as the wire it replaces: the strongest neighbour, no decay. */
    private void updatePower(Level level, BlockPos pos, BlockState state) {
        int strongest = 0;
        for (Direction direction : Direction.values()) {
            int signal = level.getSignal(pos.relative(direction), direction.getOpposite());
            if (signal > strongest) {
                strongest = signal;
            }
        }
        if (strongest != state.getValue(POWER)) {
            level.setBlock(pos, state.setValue(POWER, strongest), Block.UPDATE_ALL);
        }
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
