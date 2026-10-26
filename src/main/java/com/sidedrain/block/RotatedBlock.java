package com.sidedrain.block;

import com.sidedrain.block.entity.RotatedBlockEntity;
import com.sidedrain.util.ShapeRotator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The carrier behind every "X Rotated Block".
 *
 * <p>Blocks like grass, cobblestone, flowers, a slab stood on its edge or a modded machine have no
 * orientation property the wrench could flip. Instead the original block is lifted into this carrier,
 * which draws it at an angle and, for quarter turns, also rotates its collision box.
 *
 * <p><b>Behaviour is delegated, not copied.</b> Every question the game asks this block - right click
 * it, bounce off it, step on it, break it, tick it - is forwarded to the wrapped block with the
 * wrapped block's own state. That is what keeps a rotated slime block bouncy, a rotated bed
 * sleepable, and a rotated modded machine still a machine. Anything the carrier cannot forward
 * (a block whose logic needs its own block class in the world) is still restored exactly when the
 * carrier is turned back to zero.
 */
public class RotatedBlock extends BaseEntityBlock {
    /** Yaw in 45 degree steps; 0 is the original heading. */
    public static final IntegerProperty YAW = IntegerProperty.create("yaw", 0, 7);
    /** Pitch in 45 degree steps; 0 is upright, 2 is a quarter turn, 4 is upside down. */
    public static final IntegerProperty PITCH = IntegerProperty.create("pitch", 0, 7);

    /**
     * The wrapped bed's facing, exposed so vanilla bed code can read it.
     *
     * <p>{@code ServerPlayer.startSleepInBed} and {@code LivingEntity.stopSleeping} both do
     * {@code level.getBlockState(pos).getValue(FACING)} on the block they were given. A carrier had no
     * such property, so sleeping in a rotated bed threw. This property is a plain copy of the wrapped
     * bed's own facing and is <b>not</b> affected by {@link #YAW}/{@link #PITCH}: the two halves of
     * the bed still occupy the same world positions, only the model and collision box are turned, so
     * the facing that finds the other half has to stay the original one.
     *
     * <p>It is only meaningful when a bed is wrapped; every other block leaves it at the default.
     */
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
            net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;

    public RotatedBlock() {
        super(Block.Properties.of().strength(1.0F).noOcclusion().dynamicShape());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(YAW, 0).setValue(PITCH, 0).setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(YAW, PITCH, FACING);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Nullable
    public static BlockState innerState(BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof RotatedBlockEntity rotated) {
            BlockState inner = rotated.getInnerState();
            return inner.isAir() ? null : inner;
        }
        return null;
    }

    /** The wrapped block entity, when the wrapped block has one. */
    @Nullable
    private static BlockEntity innerEntity(BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof RotatedBlockEntity rotated && level instanceof Level real) {
            return rotated.innerEntity(real);
        }
        return null;
    }

    /**
     * Rotated shapes are cached per (inner state, yaw, pitch).
     *
     * <p>{@code getShape} is called for every block update, every entity sweep and every frame of the
     * block outline, and {@link ShapeRotator} rebuilds a fresh {@code VoxelShape} from scratch each
     * time. A rotated Sophisticated Backpack has a large, many-box collision shape, so that rebuild
     * was the single hottest path in the mod. The shape only depends on the inner state and the two
     * rotation properties, so it is computed once and reused.
     */
    private static final int SHAPE_CACHE_LIMIT = 4096;
    private static final java.util.Map<ShapeKey, VoxelShape> SHAPE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Keyed on the <b>unrotated shape object itself</b>, not on the block state.
     *
     * <p>A block state returns the very same {@code VoxelShape} instance for every query, so identity
     * is a perfect cache key for the common case. When a block really does vary its shape by context
     * (the entity-aware collision shapes) it hands back a different instance and the cache simply
     * misses, which keeps the result correct instead of pinning one entity's shape for all of them.
     */
    private record ShapeKey(VoxelShape raw, int yaw, int pitch) {
    }

    private static VoxelShape rotateCached(VoxelShape raw, BlockState carrier) {
        int yaw = carrier.getValue(YAW);
        int pitch = carrier.getValue(PITCH);
        ShapeKey key = new ShapeKey(raw, yaw, pitch);
        VoxelShape cached = SHAPE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        VoxelShape built;
        try {
            built = rotateShape(raw, carrier);
        } catch (RuntimeException | StackOverflowError failure) {
            // A modded shape that cannot be rotated must not take the server thread with it. This
            // runs inside block updates, so an exception here aborts the tick and, for a block the
            // player just flipped, leaves the world half-updated. Fall back to the unrotated shape:
            // the collision box is then wrong for that one block, which is a cosmetic problem, and
            // the game keeps running, which is not.
            com.sidedrain.SideDrain.LOGGER.warn("Could not rotate collision shape {}; using it unrotated",
                    raw, failure);
            return raw;
        }
        // Bounded so a world full of distinct modded blocks cannot grow the cache without limit.
        // It stops accepting new entries when full instead of clearing: a clear threw away every
        // shape that had already been paid for, so the next tick rebuilt all of them, and with the
        // warm-up worker refilling the map that became a permanent rebuild storm that froze the
        // server thread. A stale-but-full map is strictly better - it still answers the states that
        // are actually in use.
        if (SHAPE_CACHE.size() < SHAPE_CACHE_LIMIT) {
            SHAPE_CACHE.put(key, built);
        }
        // This state was not pre-computed - the block was rotated before the warm-up finished, or
        // the warm-up was skipped. Kick off the rest of the set so the next turn is a cache read.
        com.sidedrain.util.ShapeWarmup.warmRotatedShapes(raw);
        return built;
    }

    /**
     * Rotates a shape to match exactly what the renderer draws, at the full 45 degree resolution.
     *
     * <p>This used to quantise to quarter turns - {@code (pitch / 2) & 3} and {@code (yaw / 2) & 3} -
     * so a 45 degree rotation moved the model but left the collision box where it was. The shape now
     * follows every one of the eight steps on both axes.
     *
     * <p>Order and sign mirror {@link com.sidedrain.client.RotatedBlockRenderer}: the wrapped block
     * is pitched about x first, then yawed about y, and the pose is
     * {@code Ry(-yaw) * Rx(pitch)}. {@link ShapeRotator#rotateX} takes positive degrees clockwise
     * about +x, which is the same direction as the renderer's {@code Axis.XP.rotationDegrees(pitch)},
     * and {@link ShapeRotator#spinY} takes quarter turns of {@code -90} per step, matching
     * {@code Axis.YP.rotationDegrees(-yaw)}.
     */
    private static VoxelShape rotateShape(VoxelShape shape, BlockState state) {
        int yaw = state.getValue(YAW);
        int pitch = state.getValue(PITCH);
        VoxelShape result = ShapeRotator.rotateAboutX(shape, pitch * 45.0D);
        return ShapeRotator.rotateAboutY(result, -yaw * 45.0D);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockState inner = innerState(level, pos);
        return inner == null ? Shapes.empty() : rotateCached(inner.getShape(level, pos, context), state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        BlockState inner = innerState(level, pos);
        return inner == null ? Shapes.empty() : rotateCached(inner.getCollisionShape(level, pos, context), state);
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        BlockState inner = innerState(level, pos);
        return inner == null ? Shapes.empty() : rotateCached(inner.getInteractionShape(level, pos), state);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        BlockState inner = innerState(level, pos);
        return inner == null ? 0 : inner.getLightEmission(level, pos);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RotatedBlockEntity(pos, state);
    }

    /** Runs the wrapped block's own ticker, so a rotated furnace still smelts. */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // Server only. Ticking a detached copy of every wrapped block entity on the client was pure
        // waste: the client renderer never reads that copy, it draws from the block state alone.
        if (level.isClientSide) {
            return null;
        }
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (!(blockEntity instanceof RotatedBlockEntity rotated)) {
                return;
            }
            BlockState inner = rotated.getInnerState();
            if (inner.isAir()) {
                return;
            }
            // The carrier reports isRandomlyTicking() == false because its own state cannot know what
            // it wraps, so the wrapped block's random ticks are dispatched from here instead.
            if (inner.isRandomlyTicking() && tickLevel instanceof ServerLevel serverLevel) {
                int speed = serverLevel.getGameRules()
                        .getInt(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING);
                if (speed > 0 && serverLevel.random.nextInt(4096) < speed) {
                    inner.randomTick(serverLevel, pos, serverLevel.random);
                }
            }
            BlockEntity wrapped = rotated.innerEntity(tickLevel);
            if (wrapped == null) {
                return;
            }
            BlockEntityTicker<BlockEntity> ticker = null;
            if (inner.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
                ticker = (BlockEntityTicker<BlockEntity>) entityBlock.getTicker(tickLevel, inner, wrapped.getType());
            }
            if (ticker != null) {
                ticker.tick(tickLevel, pos, inner, wrapped);
            }
            // Do NOT serialise the wrapped block entity here. This runs twenty times a second for
            // every rotated block, and saveWithoutMetadata() walks the whole inventory - a rotated
            // Sophisticated Backpack with hundreds of stacks made the server hitch every tick. The
            // live entity is authoritative; RotatedBlockEntity.saveAdditional() writes it back when
            // the chunk is actually saved.
            rotated.markInnerDirty();
        };
    }

    // ------------------------------------------------------------------ behaviour delegation

    /**
     * Right clicking forwards to the wrapped block, so a rotated bed can be slept in, a rotated
     * crafting table opens, a rotated modded machine shows its own screen.
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        BlockState inner = innerState(level, pos);
        if (inner == null) {
            return InteractionResult.PASS;
        }
        // A wrapped bed needs the click routed to its head half. BedBlock.use does that itself, but
        // it finds the head with getBlockState(pos.relative(FACING)).is(this) - on a carrier that is
        // another carrier, so it bailed out and nothing happened. The head is looked up through the
        // wrapped state instead and then vanilla sleeping runs unchanged, now that the carrier
        // exposes FACING.
        if (inner.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            BlockPos head = com.sidedrain.util.RotatedBedSupport.headPos(level, pos);
            BlockState headBed = com.sidedrain.util.RotatedBedSupport.bedAt(level, head);
            if (headBed == null) {
                return InteractionResult.PASS;
            }
            // BedBlock.use takes its position from the hit result, so hand it the head position
            // explicitly. Clicking the foot half then still sleeps in the head, as vanilla does.
            BlockHitResult atHead = new BlockHitResult(
                    net.minecraft.world.phys.Vec3.atCenterOf(head), hit.getDirection(), head, hit.isInside());
            return headBed.use(level, player, hand, atHead);
        }
        // Any other block keeps its own interaction. A modded machine looks its block entity up by
        // position, so it will find the carrier's entity rather than its own; the mod either copes
        // with that or throws. Throwing here would abort the server tick that handled the click, so
        // it is caught and reported instead - a machine that refuses to open is a bug report, a
        // machine that takes the tick down with it is a crash.
        try {
            return inner.use(level, player, hand, hit);
        } catch (RuntimeException failure) {
            com.sidedrain.SideDrain.LOGGER.warn("Wrapped block {} failed to handle a click; ignoring it",
                    inner.getBlock(), failure);
            return InteractionResult.PASS;
        }
    }

    /**
     * Reports a wrapped bed as a bed.
     *
     * <p>{@code LivingEntity.tick} wakes a sleeping player on the next tick unless the block at the
     * sleeping position answers {@code isBed}. The carrier is not a {@link BedBlock}, so without this
     * a player would start to sleep and be thrown out again immediately.
     */
    @Override
    public boolean isBed(BlockState state, BlockGetter level, BlockPos pos,
                         @Nullable net.minecraft.world.entity.Entity sleeper) {
        return innerState(level, pos) != null
                && innerState(level, pos).getBlock() instanceof net.minecraft.world.level.block.BedBlock;
    }

    /**
     * Stops the wrapped bed's {@code OCCUPIED} property from being written onto the carrier.
     *
     * <p>Vanilla calls this with the carrier's own state; the property does not exist there and
     * {@code setValue} would throw. The wrapped state is updated by
     * {@link com.sidedrain.util.RotatedBedSupport} instead.
     */
    @Override
    public void setBedOccupied(BlockState state, Level level, BlockPos pos,
                               net.minecraft.world.entity.LivingEntity sleeper, boolean occupied) {
        com.sidedrain.util.RotatedBedSupport.setOccupied(level, pos, occupied);
    }

    /** The facing of the wrapped bed, so bed lookups never ask the carrier for a missing property. */
    @Override
    public Direction getBedDirection(BlockState state, LevelReader level, BlockPos pos) {
        BlockState inner = innerState(level, pos);
        if (inner != null && inner.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            return inner.getValue(net.minecraft.world.level.block.BedBlock.FACING);
        }
        return Direction.UP;
    }

    @Override
    public void attack(BlockState state, Level level, BlockPos pos, Player player) {
        BlockState inner = innerState(level, pos);
        if (inner != null) {
            inner.attack(level, pos, player);
        }
    }

    /** Bouncing is a property of the block, so a rotated slime block keeps its bounce. */
    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        BlockState inner = innerState(level, pos);
        if (inner != null) {
            inner.entityInside(level, pos, entity);
        }
    }

    /** Fall damage, trampling and the like are all computed from the block itself. */
    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        BlockState inner = innerState(level, pos);
        if (inner != null) {
            inner.getBlock().stepOn(level, pos, inner, entity);
        }
    }

    @Override
    public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float fallDistance) {
        BlockState inner = innerState(level, pos);
        if (inner != null) {
            inner.getBlock().fallOn(level, inner, pos, entity, fallDistance);
        } else {
            super.fallOn(level, state, pos, entity, fallDistance);
        }
    }

    @Override
    public void updateEntityAfterFallOn(BlockGetter level, Entity entity) {
        // The wrapped block may turn a landing into a bounce (slime block, bed); let it decide.
        BlockPos pos = entity.getOnPos();
        BlockState inner = innerState(level, pos);
        if (inner != null) {
            inner.getBlock().updateEntityAfterFallOn(level, entity);
        } else {
            super.updateEntityAfterFallOn(level, entity);
        }
    }

    @Override
    public void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        BlockState inner = innerState(level, hit.getBlockPos());
        if (inner != null) {
            inner.getBlock().onProjectileHit(level, inner, hit, projectile);
        }
    }

    /**
     * Everything below forwards a question the game asks a block to the block this carrier wraps.
     *
     * <p>Wrapping a block must not change what it does, only which way it faces. Every method here
     * that vanilla computes from the block's own state is answered from the wrapped state instead, so
     * a rotated ladder is still climbable, a rotated soul-sand block still slows you down, a rotated
     * bookshelf still opens its screen, and a rotated stone still takes the same time to mine.
     */

    /** Movement modifiers: soul sand slows, slime is slippery, honey sticks. */
    @Override
    public float getFriction(BlockState state, LevelReader level, BlockPos pos,
                             net.minecraft.world.entity.Entity entity) {
        BlockState inner = innerState(level, pos);
        return inner == null ? super.getFriction(state, level, pos, entity)
                : inner.getFriction(level, pos, entity);
    }

    /** Mining time and blast resistance both come from the wrapped block. */
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        BlockState inner = innerState(level, pos);
        return inner == null ? super.getDestroyProgress(state, player, level, pos)
                : inner.getDestroyProgress(player, level, pos);
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos,
                                        Explosion explosion) {
        BlockState inner = innerState(level, pos);
        return inner == null ? super.getExplosionResistance(state, level, pos, explosion)
                : inner.getExplosionResistance(level, pos, explosion);
    }

    /** Light and visibility: a rotated torch still lights the room, a rotated glass still sees through. */
    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        BlockState inner = innerState(level, pos);
        return inner == null ? super.getLightBlock(state, level, pos) : inner.getLightBlock(level, pos);
    }

    /**
     * A rotated redstone block, lever or button still powers its neighbours.
     *
     * <p>Redstone dust only asks a block for its signal when {@code isSignalSource} is true, and the
     * carrier's own default is false - so without this a rotated redstone block went silent even
     * though {@link #getSignal} was already forwarding to it.
     *
     * <p>The method is given no position, so it cannot know whether <em>this</em> carrier wraps a
     * signal source. Answering true unconditionally is the safe side of that trade: a carrier around
     * a block that emits nothing simply returns 0 from {@link #getSignal}, which is exactly what dust
     * sees from any ordinary block. Answering false would silently kill every rotated redstone
     * component in the world.
     */
    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    /** Opening a rotated chest, furnace or modded machine goes through its own menu provider. */
    @Nullable
    @Override
    public net.minecraft.world.MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        BlockState inner = innerState(level, pos);
        return inner == null ? null : inner.getMenuProvider(level, pos);
    }

    /** Pathfinding: a rotated ladder must still be climbable and a rotated fence still avoided. */
    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos,
                                  net.minecraft.world.level.pathfinder.PathComputationType type) {
        BlockState inner = innerState(level, pos);
        return inner != null && inner.isPathfindable(level, pos, type);
    }

    /** A rotated block still spawns its own break particles and drops its own experience. */
    @Override
    public void spawnAfterBreak(BlockState state, ServerLevel level, BlockPos pos,
                                ItemStack tool, boolean dropExperience) {
        BlockState inner = innerState(level, pos);
        if (inner != null) {
            inner.getBlock().spawnAfterBreak(inner, level, pos, tool, dropExperience);
        }
    }

    /**
     * Hands the wrapped block its own removal notification.
     *
     * <p>A rotated chest has to spill its contents and a rotated modded machine has to shut down
     * cleanly, so the wrapped block sees the removal it would have seen in the world.
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockState inner = innerState(level, pos);
            if (inner != null) {
                inner.getBlock().onRemove(inner, level, pos, inner, isMoving);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /** Comparator output and block events still reach the wrapped block. */
    @Override
    public boolean triggerEvent(BlockState state, Level level, BlockPos pos, int eventId, int eventParam) {
        BlockState inner = innerState(level, pos);
        return inner != null && inner.triggerEvent(level, pos, eventId, eventParam);
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        BlockState inner = innerState(level, pos);
        return inner == null ? 0 : inner.getSignal(level, pos, side);
    }

    @Override
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        BlockState inner = innerState(level, pos);
        return inner == null ? 0 : inner.getDirectSignal(level, pos, side);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        BlockEntity wrapped = innerEntity(level, pos);
        return wrapped == null ? 0
                : net.minecraft.world.inventory.AbstractContainerMenu.getRedstoneSignalFromBlockEntity(wrapped);
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        // The carrier itself has nothing random to do. The wrapped block is checked through its own
        // state, which is the only thing that can actually want random ticks (crops, leaves, ice).
        // Returning true unconditionally put every rotated block in the world into the random-tick
        // bucket for no reason.
        return false;
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState inner = innerState(level, pos);
        if (inner != null && inner.isRandomlyTicking()) {
            inner.randomTick(level, pos, random);
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState inner = innerState(level, pos);
        if (inner != null) {
            inner.tick(level, pos, random);
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        BlockState inner = innerState(level, pos);
        if (inner != null) {
            inner.getBlock().neighborChanged(inner, level, pos, block, fromPos, isMoving);
        }
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        BlockState inner = innerState(level, pos);
        if (inner != null) {
            inner.getBlock().onPlace(inner, level, pos, oldState, isMoving);
        }
    }

    /** A rotated explosion-sensitive block still reacts to blasts. */
    @Override
    public void wasExploded(Level level, BlockPos pos, Explosion explosion) {
        BlockState inner = innerState(level, pos);
        if (inner != null) {
            inner.getBlock().wasExploded(level, pos, explosion);
        }
    }

    /**
     * Drops whatever the wrapped block would have dropped, computed from its own loot table with its
     * own block entity data. That is what makes a rotated chest spill its contents and a rotated
     * machine return its parts.
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        BlockEntity entity = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (!(entity instanceof RotatedBlockEntity rotated)) {
            return List.of();
        }
        BlockState inner = rotated.getInnerState();
        if (inner.isAir()) {
            return List.of();
        }
        // A wrapped bed is two carriers holding the two halves of ONE bed. Vanilla drops a bed once,
        // from the head, so the foot half must drop nothing - otherwise breaking a rotated bed
        // hands out two beds for one.
        if (inner.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                && inner.getValue(net.minecraft.world.level.block.BedBlock.PART)
                != net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
            return List.of();
        }
        BlockEntity wrapped = rotated.createInnerEntity(builder.getLevel());
        List<ItemStack> drops = Block.getDrops(inner, builder.getLevel(), rotated.getBlockPos(), wrapped);
        if (!drops.isEmpty()) {
            // A broken rotated block keeps its identity: the drop is renamed the same way the clone
            // item is, so "泥土 旋转方块" survives the round trip.
            for (ItemStack drop : drops) {
                if (drop.getCount() == 1 && !drop.hasCustomHoverName()) {
                    drop.setHoverName(nameFor(inner));
                }
            }
            return drops;
        }
        // Some blocks have no loot table (modded ones usually do not). Fall back to the item form so
        // the block is never lost.
        ItemStack fallback = namedItemFor(inner);
        return fallback.isEmpty() ? List.of() : List.of(fallback);
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        BlockState inner = innerState(level, pos);
        if (inner == null || inner.isAir()) {
            return ItemStack.EMPTY;
        }
        return namedItemFor(inner);
    }

    /**
     * The item form of a rotated block: the wrapped block's own item, renamed.
     *
     * <p>Rotated dirt has to read "泥土 旋转方块" and not plain "泥土", otherwise a player cannot
     * tell the carrier from the block it wraps. The name lives on the stack, so it survives being
     * put in a chest, dropped, or picked back up.
     */
    public static ItemStack namedItemFor(BlockState inner) {
        ItemStack stack = new ItemStack(inner.getBlock());
        if (stack.isEmpty()) {
            return stack;
        }
        stack.setHoverName(Component.translatable("block.sidedrain.rotated_block.named",
                inner.getBlock().getName()));
        return stack;
    }

    /**
     * The name the game shows when this block is looked at.
     *
     * <p>{@link #nameFor} already drives the item, the drop and the tooltip, but the in-world name
     * asks the <em>block</em> instead of the block entity - and {@code Block.getName()} carries no
     * position, so it cannot look the wrapped state up on its own. The carrier resolves it from the
     * block the local player is currently aiming at, which is exactly the block the HUD is naming,
     * and falls back to the plain "旋转方块" whenever no carrier is targeted (or on a dedicated
     * server, where there is no client crosshair).
     */
    @Override
    public net.minecraft.network.chat.MutableComponent getName() {
        BlockState aimed = com.sidedrain.client.CarrierNameLookup.aimedInner();
        if (aimed != null && !aimed.isAir()) {
            return nameFor(aimed).copy();
        }
        return super.getName();
    }

    /** The display name of the carrier wrapping {@code inner}. */
    public static Component nameFor(BlockState inner) {
        if (inner == null || inner.isAir()) {
            return Component.translatable("block.sidedrain.rotated_block.empty");
        }
        return Component.translatable("block.sidedrain.rotated_block.named", inner.getBlock().getName());
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState();
    }
}
