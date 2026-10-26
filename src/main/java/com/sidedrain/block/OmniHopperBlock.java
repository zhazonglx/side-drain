package com.sidedrain.block;

import com.sidedrain.block.entity.OmniHopperBlockEntity;
import com.sidedrain.registry.ModBlockEntities;
import com.sidedrain.util.HopperOrientation;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The rotatable hopper: "Hopper Rotated Block".
 *
 * <p>Thirty forms: the bucket can open towards any of the six faces, and for each of those the tail
 * can point at any of the other five. That is the six body facings times the five spout facings the
 * user asked for, and it is what makes the hopper genuinely omnidirectional - a ceiling mounted
 * hopper, a wall hopper lying on its side, a hopper whose bucket faces east while its tail points
 * north.
 *
 * <p>Body and spout are independent: items are pulled out of the container behind the bucket and
 * pushed into the container the tail points at, in every one of the thirty forms.
 *
 * <p>Not craftable. An Orientation Wrench turns a vanilla hopper into this the first time it is
 * rotated, preserving its contents; breaking it gives a plain hopper back.
 */
public class OmniHopperBlock extends BaseEntityBlock {
    public static final EnumProperty<HopperOrientation> FACING =
            EnumProperty.create("facing", HopperOrientation.class);
    public static final BooleanProperty ENABLED = BlockStateProperties.ENABLED;

    /** The collision shape of a form, generated from the same boxes as its model. */
    public static VoxelShape shapeFor(HopperOrientation orientation) {
        return HopperShapes.of(orientation);
    }

    public OmniHopperBlock() {
        super(Block.Properties.of().strength(3.0F, 4.8F).requiresCorrectToolForDrops().noOcclusion().dynamicShape());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, HopperOrientation.UP_DOWN)
                .setValue(ENABLED, Boolean.TRUE));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeFor(state.getValue(FACING));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Clicked the top of a block: the tail points down, the bucket opens up, like vanilla.
        Direction spout = context.getClickedFace().getOpposite();
        Direction body = spout.getOpposite();
        return this.defaultBlockState()
                .setValue(FACING, HopperOrientation.of(body, spout))
                .setValue(ENABLED, Boolean.TRUE);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OmniHopperBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, ModBlockEntities.OMNI_HOPPER.get(), OmniHopperBlockEntity::pushItemsTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (stack.hasCustomHoverName()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof OmniHopperBlockEntity hopper) {
                hopper.setCustomName(stack.getHoverName());
            }
        }
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        if (!oldState.is(state.getBlock())) {
            this.checkPoweredState(level, pos, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof OmniHopperBlockEntity hopper) {
            player.openMenu(hopper);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        this.checkPoweredState(level, pos, state, Block.UPDATE_CLIENTS);
    }

    private void checkPoweredState(Level level, BlockPos pos, BlockState state, int flags) {
        boolean powered = !level.hasNeighborSignal(pos);
        if (powered != state.getValue(ENABLED)) {
            level.setBlock(pos, state.setValue(ENABLED, powered), flags);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof OmniHopperBlockEntity hopper) {
                Containers.dropContents(level, pos, hopper);
                level.updateNeighbourForOutputSignal(pos, this);
            }
            super.onRemove(state, level, pos, newState, isMoving);
        }
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** The block has no item of its own, so picking it up hands back a plain hopper. */
    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return new ItemStack(net.minecraft.world.level.block.Blocks.HOPPER);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));
    }

    @Override
    public BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        HopperOrientation current = state.getValue(FACING);
        return state.setValue(FACING, HopperOrientation.of(
                rotation.rotate(current.body()), rotation.rotate(current.spout())));
    }

    @Override
    public BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        HopperOrientation current = state.getValue(FACING);
        return state.setValue(FACING, HopperOrientation.of(
                mirror.mirror(current.body()), mirror.mirror(current.spout())));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ENABLED);
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof OmniHopperBlockEntity hopper) {
            OmniHopperBlockEntity.entityInside(level, pos, state, entity, hopper);
        }
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return false;
    }
}
