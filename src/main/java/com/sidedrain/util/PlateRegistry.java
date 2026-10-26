package com.sidedrain.util;

import com.sidedrain.block.SideDrainBlock;
import com.sidedrain.block.entity.SideDrainBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Keeps track of every Side Drain Plate clamped onto the same container.
 *
 * <p>A chest can carry up to six plates, one per face. Each one has its own target slot, so the
 * player needs to know which plate they are looking at: the plate's own position decides its index,
 * and the index is shown in the panel title.
 *
 * <h2>Why the result is memoised</h2>
 *
 * <p>{@link #platesOn} costs six block entity lookups and a sort. The panel name needs the index and
 * the count, and the old code asked for them with two separate scans - so opening a panel on a busy
 * container ran twelve lookups to answer one question. The scan result is now cached per container
 * position for a single game tick, which is enough because plates cannot be placed or broken inside
 * one tick without going through the server thread that owns this cache.
 */
public final class PlateRegistry {
    /** How long a scan stays valid, in game ticks. */
    private static final int CACHE_TICKS = 1;
    /** Bounded so exploring a large world cannot grow the cache without limit. */
    private static final int CACHE_LIMIT = 512;

    private static final java.util.Map<BlockPos, Entry> CACHE = new java.util.HashMap<>();

    private record Entry(List<BlockPos> plates, long tick) {
    }

    /** The container block a plate is clamped onto. */
    public static BlockPos attachedPos(SideDrainBlockEntity plate) {
        Direction facing = plate.getBlockState().getValue(SideDrainBlock.FACING);
        return plate.getBlockPos().relative(facing.getOpposite());
    }

    /** Every plate on the same container, ordered by face so the numbering never changes. */
    public static List<BlockPos> platesOn(Level level, BlockPos containerPos) {
        if (level == null) {
            return List.of();
        }
        long now = level.getGameTime();
        Entry cached = CACHE.get(containerPos);
        if (cached != null && cached.tick() == now) {
            return cached.plates();
        }
        List<BlockPos> found = new ArrayList<>(6);
        for (Direction side : Direction.values()) {
            BlockPos platePos = containerPos.relative(side);
            if (level.getBlockEntity(platePos) instanceof SideDrainBlockEntity plate
                    && plate.getBlockState().getValue(SideDrainBlock.FACING) == side) {
                found.add(platePos);
            }
        }
        found.sort(Comparator.comparingInt(pos -> {
            Direction side = Direction.fromDelta(
                    pos.getX() - containerPos.getX(),
                    pos.getY() - containerPos.getY(),
                    pos.getZ() - containerPos.getZ());
            return side == null ? 99 : side.ordinal();
        }));
        if (CACHE.size() > CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(containerPos.immutable(), new Entry(found, now));
        return found;
    }

    /** 1-based index of this plate among the plates on its container, or 0 when it is alone. */
    public static int plateNumber(SideDrainBlockEntity plate) {
        Level level = plate.getLevel();
        if (level == null) {
            return 0;
        }
        List<BlockPos> plates = platesOn(level, attachedPos(plate));
        int index = plates.indexOf(plate.getBlockPos());
        return plates.size() <= 1 || index < 0 ? 0 : index + 1;
    }

    /** How many plates share this plate's container. */
    public static int plateCount(SideDrainBlockEntity plate) {
        Level level = plate.getLevel();
        if (level == null) {
            return 1;
        }
        return Math.max(1, platesOn(level, attachedPos(plate)).size());
    }

    /**
     * Index and count in one scan.
     *
     * <p>The panel name needs both, and asking for them separately meant scanning the same six
     * neighbours twice for one string.
     *
     * @return {@code {index, count}} where {@code index} is 1-based and 0 when the plate is alone
     */
    public static int[] indexAndCount(SideDrainBlockEntity plate) {
        Level level = plate.getLevel();
        if (level == null) {
            return new int[]{0, 1};
        }
        List<BlockPos> plates = platesOn(level, attachedPos(plate));
        int index = plates.indexOf(plate.getBlockPos());
        if (plates.size() <= 1 || index < 0) {
            return new int[]{0, plates.size()};
        }
        return new int[]{index + 1, plates.size()};
    }

    private PlateRegistry() {
    }
}
