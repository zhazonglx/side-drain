package com.sidedrain.block;

import com.sidedrain.block.entity.SideDrainBlockEntity;
import com.sidedrain.registry.ModBlockEntities;
import com.sidedrain.util.PlateAim;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Side Drain Plate.
 *
 * <p>A thin plate that clamps onto one face of a container block and moves items for it. Its own
 * 27 slots, filter row and weapon slot all survive being broken, exactly like a shulker box.
 */
public class SideDrainBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /**
     * The aim of the plate's nozzle, in 45 degree steps away from {@link #FACING}.
     *
     * <p>{@code FACING} still decides which face of the container the plate is clamped to - the
     * container side never changes, so every piece of container logic keeps using it unchanged.
     * This property only swivels where the plate <em>points</em>, which is what makes a plate on a
     * wall able to spit items diagonally up or down. The collision box follows the same angle, so
     * what you see is what you bump into.
     */
    public static final IntegerProperty TILT = IntegerProperty.create("tilt", 0, PlateAim.STEPS - 1);

    /** Convenience: the whole aim of a placed plate. */
    public static Vec3 ejectDirection(BlockState state) {
        return PlateAim.ejectDirection(state.getValue(FACING), state.getValue(TILT));
    }

    /** Convenience: the aim in whole 45 degree steps. */
    public static int tiltOf(BlockState state) {
        return state.getValue(TILT);
    }

    public SideDrainBlock() {
        super(Block.Properties.of().strength(2.5F, 6.0F).noOcclusion());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(TILT, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TILT);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PlateAim.shape(state.getValue(FACING), state.getValue(TILT));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PlateAim.shape(state.getValue(FACING), state.getValue(TILT));
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getClickedFace())
                .setValue(TILT, 0);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SideDrainBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // The plate must always have a ticker on the server. Returning null on the client keeps the
        // client from running item logic, but the server side is what actually moves items.
        if (level.isClientSide) {
            return null;
        }
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof SideDrainBlockEntity plate) {
                SideDrainBlockEntity.serverTick(tickLevel, pos, tickState, plate);
            }
        };
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof SideDrainBlockEntity plate) {
            // Sneak + right click flips the power switch without opening the screen.
            if (player.isShiftKeyDown()) {
                boolean on = plate.toggleEnabled();
                player.displayClientMessage(Component.translatable(
                        on ? "message.sidedrain.enabled" : "message.sidedrain.disabled"), true);
                return InteractionResult.CONSUME;
            }
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                net.minecraftforge.network.NetworkHooks.openScreen(serverPlayer, plate, pos);
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (stack.hasCustomHoverName()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof SideDrainBlockEntity plate) {
                plate.setCustomName(stack.getHoverName());
            }
        }
    }

    /** Contents travel with the block, like a shulker box. */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        BlockEntity blockEntity = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        ItemStack stack = new ItemStack(this);
        if (blockEntity != null) {
            CompoundTag tag = blockEntity.saveWithoutMetadata();
            tag.remove("x");
            tag.remove("y");
            tag.remove("z");
            tag.remove("id");
            if (!tag.isEmpty()) {
                stack.getOrCreateTag().put("BlockEntityTag", tag);
            }
        }
        return List.of(stack);
    }
}
