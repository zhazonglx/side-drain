package com.sidedrain.util;

import com.sidedrain.block.RotatedBlock;
import com.sidedrain.block.entity.RotatedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * Bed helpers for a bed that has been lifted into a carrier.
 *
 * <p>A carrier is not a {@link BedBlock}, so every piece of vanilla code that reads the bed off the
 * world position used to fail on a rotated bed:
 *
 * <ul>
 *   <li>{@code BedBlock.use} walks to the head with {@code getBlockState(pos.relative(FACING))} and
 *       then checks {@code state.is(this)} - on a carrier that is another carrier, so it bailed out
 *       with {@code CONSUME} and nothing happened.</li>
 *   <li>{@code ServerPlayer.startSleepInBed} does
 *       {@code level.getBlockState(pos).getValue(HorizontalDirectionalBlock.FACING)}. The carrier had
 *       no {@code FACING} property, so this threw and the interaction died.</li>
 *   <li>{@code LivingEntity.stopSleeping} does the same {@code getValue(FACING)} and also writes
 *       {@code OCCUPIED} through the block state, which would throw on the carrier too.</li>
 *   <li>{@code LivingEntity.tick} wakes the player unless {@code Block#isBed} is true.</li>
 * </ul>
 *
 * <p>Rather than reimplementing sleep, the carrier now answers all of those itself:
 * {@link RotatedBlock} exposes a real {@code FACING} property, reports {@code isBed}, and forwards
 * {@code setBedOccupied} to the wrapped state. With that in place vanilla {@code startSleepInBed}
 * works unchanged and only the head lookup in {@code use} has to be done here.
 *
 * <p>{@code FACING} always mirrors the <b>wrapped</b> bed's facing and is never rotated with the
 * carrier: the two halves of the bed still sit at the same world positions, only their model and
 * collision box are turned, so the facing that locates the other half must stay the original one.
 */
public final class RotatedBedSupport {

    /** The wrapped bed state at {@code pos}, or null when this position does not hold a wrapped bed. */
    public static BlockState bedAt(Level level, BlockPos pos) {
        BlockState inner = RotatedBlock.innerState(level, pos);
        if (inner == null || !(inner.getBlock() instanceof BedBlock)) {
            return null;
        }
        return inner;
    }

    /** The position of the head half, given either half of a wrapped bed. */
    public static BlockPos headPos(Level level, BlockPos pos) {
        BlockState bed = bedAt(level, pos);
        if (bed == null) {
            return pos;
        }
        if (bed.getValue(BedBlock.PART) == BedPart.HEAD) {
            return pos;
        }
        // The foot sits one step behind the head, so the head is one step along FACING.
        BlockPos head = pos.relative(bed.getValue(BedBlock.FACING));
        BlockState headBed = bedAt(level, head);
        return headBed != null && headBed.getValue(BedBlock.PART) == BedPart.HEAD ? head : pos;
    }

    /**
     * Copies the wrapped bed's facing onto the carrier so vanilla bed code can read it.
     *
     * <p>Called whenever a carrier is created around a bed; a carrier around a non-bed keeps the
     * default facing, which nothing reads.
     */
    public static BlockState withBedFacing(BlockState carrier, BlockState inner) {
        if (inner != null && inner.getBlock() instanceof BedBlock) {
            return carrier.setValue(RotatedBlock.FACING, inner.getValue(BedBlock.FACING));
        }
        return carrier;
    }

    /**
     * Writes {@code OCCUPIED} onto the <b>wrapped</b> bed state.
     *
     * <p>Vanilla's default {@code setBedOccupied} does {@code state.setValue(BedBlock.OCCUPIED, ...)}
     * on the block at the position - the carrier - which has no such property and would throw. The
     * wrapped state is updated instead, so the bed still reads as occupied while someone sleeps.
     */
    public static void setOccupied(Level level, BlockPos pos, boolean occupied) {
        if (level.isClientSide) {
            return;
        }
        BlockState bed = bedAt(level, pos);
        if (bed == null || bed.getValue(BedBlock.OCCUPIED) == occupied) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof RotatedBlockEntity rotated)) {
            return;
        }
        rotated.setInnerState(bed.setValue(BedBlock.OCCUPIED, occupied));
        rotated.setChanged();
        rotated.syncToClients();
    }

    /** The facing of the wrapped bed, used by {@link RotatedBlock#getBedDirection}. */
    public static Direction facing(BlockState bed) {
        return bed.getValue(BedBlock.FACING);
    }

    private RotatedBedSupport() {
    }
}
