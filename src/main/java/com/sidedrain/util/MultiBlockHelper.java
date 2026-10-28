package com.sidedrain.util;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Finds the other half of a block that is built out of two blocks.
 *
 * <p>Beds, doors and tall flowers are not one block: they are two block positions that only work as
 * a pair. Turning one half alone leaves a broken ghost behind, so every operation that moves or
 * rotates a block has to move the whole pair together.
 */
public final class MultiBlockHelper {
    /** True when this block occupies two positions and must always be handled as a pair. */
    public static boolean isDouble(BlockState state) {
        return state.getBlock() instanceof BedBlock
                || state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof DoublePlantBlock;
    }

    /**
     * True for the two-block plants: tall grass, large fern, tall flowers, tall seagrass.
     *
     * <p>These differ from a door in an important way. A door has a real orientation
     * ({@code FACING} plus {@code OPEN}/{@code HINGE}), so both halves can be rebuilt in the new
     * orientation and the pair stays a legal door. A double plant's only property is
     * {@code HALF}, which says which block of the pair it is and is not a direction - there is no
     * orientation to rotate at all. Such a plant therefore cannot be turned in place and has to be
     * lifted into carriers, which is what {@link #isDoublePlant} lets the wrench distinguish.
     */
    public static boolean isDoublePlant(BlockState state) {
        return state.getBlock() instanceof DoublePlantBlock;
    }

    /**
     * The position of the other half, or {@code null} when this block is a single block.
     *
     * <p>Each family records the pair differently: a bed stores which half it is and points at the
     * other one with {@code FACING}, a door stores the half and the other one is always one block up
     * or down, and a tall flower is the same.
     */
    @Nullable
    public static BlockPos otherHalf(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof BedBlock) {
            // Vanilla places the FOOT where the player clicked and the HEAD one step along FACING
            // (BedBlock.setPlacedBy does exactly that), and getNeighbourDirection(FOOT) = FACING,
            // getNeighbourDirection(HEAD) = the opposite. So the head's partner lies at
            // head.relative(FACING.getOpposite()) and the foot's partner at foot.relative(FACING).
            Direction facing = state.getValue(BedBlock.FACING);
            return state.getValue(BedBlock.PART) == BedPart.HEAD
                    ? pos.relative(facing.getOpposite())
                    : pos.relative(facing);
        }
        if (state.getBlock() instanceof DoorBlock) {
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        }
        if (state.getBlock() instanceof DoublePlantBlock) {
            return state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        }
        return null;
    }

    /**
     * The state the partner half should take, given the rotated primary half.
     *
     * <p>The partner is the same block pointing the same way, differing only in which half it is.
     * Deriving it this way instead of rotating it on its own keeps the pair attached: a bed's head
     * and foot both point at the same FACING, and only PART tells them apart.
     */
    @Nullable
    public static BlockState partnerOf(BlockState rotatedPrimary, BlockState otherState) {
        // The partner is the same block pointing the same way; only its PART/HALF differs. The
        // partner's own old state is not used beyond confirming it is the same block, because a
        // bed's head and foot must agree on FACING.
        if (rotatedPrimary.getBlock() != otherState.getBlock()) {
            return null;
        }
        if (rotatedPrimary.getBlock() instanceof BedBlock) {
            BedPart part = rotatedPrimary.getValue(BedBlock.PART) == BedPart.HEAD
                    ? BedPart.FOOT : BedPart.HEAD;
            return rotatedPrimary.setValue(BedBlock.PART, part);
        }
        if (rotatedPrimary.getBlock() instanceof DoorBlock) {
            DoubleBlockHalf half = rotatedPrimary.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER;
            return rotatedPrimary.setValue(DoorBlock.HALF, half);
        }
        if (rotatedPrimary.getBlock() instanceof DoublePlantBlock) {
            DoubleBlockHalf half = rotatedPrimary.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER
                    ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER;
            return rotatedPrimary.setValue(DoublePlantBlock.HALF, half);
        }
        return null;
    }

    /** True when the other half is present and is the same kind of block. */
    public static boolean hasOtherHalf(Level level, BlockPos pos, BlockState state) {
        BlockPos other = otherHalf(pos, state);
        if (other == null) {
            return false;
        }
        BlockState otherState = level.getBlockState(other);
        return otherState.is(state.getBlock());
    }

    /** Both positions of the pair, primary half first. */
    public static List<BlockPos> bothHalves(BlockPos pos, BlockState state) {
        List<BlockPos> halves = new ArrayList<>(2);
        halves.add(pos);
        BlockPos other = otherHalf(pos, state);
        if (other != null) {
            halves.add(other);
        }
        return halves;
    }

    /**
     * The half that should carry the rotated state.
     *
     * <p>A bed's head is the one that owns the whole model, so the head is always the primary half;
     * a door or flower keeps whichever half was clicked.
     */
    public static boolean isPrimary(BlockState state) {
        if (state.getBlock() instanceof BedBlock) {
            return state.getValue(BedBlock.PART) == BedPart.HEAD;
        }
        return true;
    }

    private MultiBlockHelper() {
    }
}
