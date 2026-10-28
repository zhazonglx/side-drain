package com.sidedrain.item;

import com.sidedrain.block.OmniHopperBlock;
import com.sidedrain.block.RotatedBlock;
import com.sidedrain.block.StandingRedstoneBlock;
import com.sidedrain.block.entity.RotatedBlockEntity;
import com.sidedrain.registry.ModBlocks;
import com.sidedrain.util.BlockRotator;
import com.sidedrain.util.HopperOrientation;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Orientation Wrench.
 *
 * <p>Shift + right click a block: flip it over ("from top to bottom").
 * <p>Shift + left click a block: spin it around ("from left to right").
 *
 * <p>Every block ends up in one of three families:
 * <ul>
 *   <li>Blocks with an orientation property are turned in place by {@link BlockRotator}.</li>
 *   <li>Hoppers and redstone dust have a dedicated rotatable block, so they gain orientations vanilla
 *       never had and their logic follows the new orientation.</li>
 *   <li>Everything else - including slabs, which this way can finally stand on edge - is lifted into
 *       the {@link RotatedBlock} carrier, which draws it tilted and remembers the original block, its
 *       data and its drops. Turning it back to zero restores it.</li>
 * </ul>
 */
public class OrientationWrenchItem extends Item {
    public OrientationWrenchItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (context.getPlayer() == null || !context.getPlayer().isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        // The use packet is sent either way; returning SUCCESS on the client just stops the block's
        // own use (opening a chest, flipping a lever, ...) from firing first.
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (context.getPlayer().getCooldowns().isOnCooldown(this)) {
            return InteractionResult.SUCCESS;
        }
        context.getPlayer().getCooldowns().addCooldown(this, 4);
        applyRotation(level, pos, true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.sidedrain.orientation_wrench.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.sidedrain.orientation_wrench.2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.sidedrain.orientation_wrench.3").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Rotates the block at {@code pos}. {@code pitch} true = flip over, false = spin around. */
    public static boolean applyRotation(Level level, BlockPos pos, boolean pitch) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }

        // A bed, door or tall flower is two positions; turning one half would break the pair.
        if (com.sidedrain.util.MultiBlockHelper.isDouble(state)) {
            // A double plant - tall grass, a large fern, a tall flower - has no orientation property
            // of its own, so there is nothing to rotate in place: the wrench has to lift BOTH halves
            // into carriers and turn them together. Handling one half alone leaves the other behind
            // as an orphaned vanilla half, which is why these used to do nothing at all.
            if (com.sidedrain.util.MultiBlockHelper.isDoublePlant(state)) {
                return rotateDoublePlant(level, pos, state, pitch);
            }
            return rotatePair(level, pos, state, pitch);
        }

        // Dedicated rotatable families first: they carry their own logic with them.
        BlockState promoted = promoteSpecial(level, pos, state, pitch);
        if (promoted != null) {
            level.setBlock(pos, promoted, Block.UPDATE_ALL);
            level.playSound(null, pos, promoted.getSoundType().getHitSound(),
                    SoundSource.BLOCKS, 0.9F, pitch ? 0.8F : 1.25F);
            return true;
        }

        if (state.getBlock() instanceof RotatedBlock) {
            // A wrapped bed is two carriers that must stay a pair. Once wrapped the block is no
            // longer a BedBlock, so the isDouble() check above no longer catches it - without this
            // the two halves would be stepped independently and drift apart.
            BlockState wrappedBed = RotatedBlock.innerState(level, pos);
            if (wrappedBed != null && wrappedBed.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                return rotateBed(level, pos, state, pitch);
            }
            return turnCarrier(level, pos, state, pitch);
        }

        BlockState rotated = BlockRotator.rotate(state, pitch);
        // Yaw can always be done in place; a flip only when the block really has an upside-down form.
        if (rotated != null && rotated != state && (!pitch || BlockRotator.hasVerticalForm(state))) {
            level.setBlock(pos, rotated, Block.UPDATE_ALL);
            level.playSound(null, pos, rotated.getSoundType().getHitSound(),
                    SoundSource.BLOCKS, 0.9F, pitch ? 0.8F : 1.25F);
            return true;
        }

        return wrap(level, pos, state, pitch);
    }

    /**
     * Turns a two-block plant - tall grass, a large fern, a tall flower - by lifting both halves
     * into carriers and stepping them together.
     *
     * <p>A double plant has no orientation property at all: its state is only {@code HALF}, which
     * says which of the two blocks it is and is not a direction. That is why the wrench previously
     * skipped it entirely - there was nothing to turn. The carrier gives it the missing axes: both
     * halves become carriers holding the LOWER and UPPER states, and every click steps their shared
     * yaw and pitch, so the plant leans, lies down or stands on its head as one rigid object.
     *
     * <p>The pair is always wrapped and unwrapped as a pair. If either half fails to convert, the
     * other is put straight back, so a plant is never left half-carried - the same invariant the bed
     * path keeps.
     */
    private static boolean rotateDoublePlant(Level level, BlockPos pos, BlockState state, boolean pitch) {
        // Resolve to the lower half: it is the anchor, and it is the half whose block decides what
        // the plant is. Whichever half was clicked, both are taken from the world.
        BlockPos lowerPos = state.getValue(net.minecraft.world.level.block.DoublePlantBlock.HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                ? pos : pos.below();
        BlockState lowerState = level.getBlockState(lowerPos);
        BlockPos upperPos = lowerPos.above();
        BlockState upperState = level.getBlockState(upperPos);
        if (!lowerState.is(state.getBlock()) || !upperState.is(state.getBlock())) {
            // An unpaired half - a plant whose other block is already gone. Nothing sensible to do.
            return false;
        }
        // Already carried on both halves: step them together, exactly like the bed path.
        boolean lowerWrapped = lowerState.getBlock() instanceof RotatedBlock;
        boolean upperWrapped = upperState.getBlock() instanceof RotatedBlock;
        if (lowerWrapped != upperWrapped) {
            return false;
        }
        if (lowerWrapped) {
            return turnWrappedDoublePlant(level, lowerPos, upperPos, pitch);
        }
        return wrapDoublePlant(level, lowerPos, upperPos, lowerState, upperState, pitch);
    }

    /**
     * Lifts both halves of a double plant into carriers, then steps them once.
     *
     * <p>The anchor is the <b>lower</b> half's cell, and the upper half always sits one block above
     * it, because that is how a double plant is laid out in the world. Turning does not move either
     * half to a different cell - only the model and the shape tilt - so the pair keeps its footprint
     * and the plant stays one object.
     */
    private static boolean wrapDoublePlant(Level level, BlockPos lowerPos, BlockPos upperPos,
                                           BlockState lowerState, BlockState upperState, boolean pitch) {
        boolean okLower = wrapOne(level, lowerPos, lowerState);
        boolean okUpper = okLower && wrapOne(level, upperPos, upperState);
        if (!okUpper) {
            if (okLower) {
                restoreCarrier(level, lowerPos);
            }
            return false;
        }
        return turnWrappedDoublePlant(level, lowerPos, upperPos, pitch);
    }

    /**
     * Steps both carrier halves of a wrapped double plant by one yaw or pitch step.
     *
     * <p>The two halves share one {@code YAW}/{@code PITCH} pair, so they stay parallel at every
     * angle. Both going back to zero unwraps them as a pair, which is what keeps a plant whole.
     */
    private static boolean turnWrappedDoublePlant(Level level, BlockPos lowerPos, BlockPos upperPos,
                                                  boolean pitch) {
        BlockState lowerCarrier = level.getBlockState(lowerPos);
        BlockState upperCarrier = level.getBlockState(upperPos);
        if (!(lowerCarrier.getBlock() instanceof RotatedBlock)
                || !(upperCarrier.getBlock() instanceof RotatedBlock)) {
            return false;
        }
        int yaw = lowerCarrier.getValue(RotatedBlock.YAW);
        int pitchValue = lowerCarrier.getValue(RotatedBlock.PITCH);
        int nextYaw = pitch ? yaw : (yaw + 1) % 8;
        int nextPitch = pitch ? (pitchValue + 1) % 8 : pitchValue;
        if (nextYaw == 0 && nextPitch == 0) {
            boolean okLower = unwrap(level, lowerPos);
            boolean okUpper = unwrap(level, upperPos);
            return okLower && okUpper;
        }
        level.setBlock(lowerPos,
                lowerCarrier.setValue(RotatedBlock.YAW, nextYaw).setValue(RotatedBlock.PITCH, nextPitch),
                Block.UPDATE_ALL);
        level.setBlock(upperPos,
                upperCarrier.setValue(RotatedBlock.YAW, nextYaw).setValue(RotatedBlock.PITCH, nextPitch),
                Block.UPDATE_ALL);
        level.playSound(null, lowerPos, lowerCarrier.getSoundType().getHitSound(),
                SoundSource.BLOCKS, 0.9F, pitch ? 0.8F : 1.25F);
        return true;
    }

    /**
     * Turns a two-block structure by rebuilding both halves in the new orientation.
     *
     * <p>A bed is the interesting case: the two halves are the head and the foot, and vanilla renders
     * the whole bed from the head, so turning the foot alone leaves an invisible half and a head that
     * still points the old way. Both halves are therefore removed and re-placed together.
     */
    private static boolean rotatePair(Level level, BlockPos pos, BlockState state, boolean pitch) {
        // A bed is the one pair that can be tilted arbitrarily: it is not rebuilt from its two
        // vanilla halves but lifted into two carriers, one per half, which remember which half they
        // are. That is what finally lets a bed lie on its side, stand on its head, or face any of
        // the eight yaw directions - vanilla only ever had four horizontal facings.
        if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
            return rotateBed(level, pos, state, pitch);
        }
        // A door or tall flower stays on the vanilla path: its two halves are a vertical pair and
        // the carrier would have to invent a second anchor for the upper half.
        // A bed is always rebuilt from its head, whichever half was clicked: the foot's position is
        // derived from the head plus the new facing, so anchoring on the foot would put the pair
        // back in the same place.
        BlockPos anchorPos = pos;
        BlockState anchorState = state;
        if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                && state.getValue(net.minecraft.world.level.block.BedBlock.PART)
                != net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
            BlockPos headPos = com.sidedrain.util.MultiBlockHelper.otherHalf(pos, state);
            if (headPos == null) {
                return false;
            }
            BlockState headState = level.getBlockState(headPos);
            if (!(headState.getBlock() instanceof net.minecraft.world.level.block.BedBlock)
                    || headState.getValue(net.minecraft.world.level.block.BedBlock.PART)
                    != net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                return false;
            }
            anchorPos = headPos;
            anchorState = headState;
        }
        BlockPos oldPartnerPos = com.sidedrain.util.MultiBlockHelper.otherHalf(anchorPos, anchorState);
        if (oldPartnerPos == null || !level.getBlockState(oldPartnerPos).is(anchorState.getBlock())) {
            return false;
        }
        BlockState rotatedHead = BlockRotator.rotate(anchorState, pitch);
        if (rotatedHead == null || rotatedHead == anchorState) {
            return false;
        }
        BlockState rotatedPartner = com.sidedrain.util.MultiBlockHelper.partnerOf(rotatedHead, anchorState);
        if (rotatedPartner == null) {
            return false;
        }
        BlockPos newPartnerPos = com.sidedrain.util.MultiBlockHelper.otherHalf(anchorPos, rotatedHead);
        if (newPartnerPos == null) {
            return false;
        }
        // Both destination cells must be usable; the pair's own cells always are.
        for (BlockPos target : new BlockPos[]{anchorPos, newPartnerPos}) {
            if (target.equals(anchorPos) || target.equals(oldPartnerPos)) {
                continue;
            }
            if (!level.getBlockState(target).canBeReplaced()) {
                return false;
            }
        }
        level.removeBlock(oldPartnerPos, false);
        level.removeBlock(anchorPos, false);
        level.setBlock(anchorPos, rotatedHead, Block.UPDATE_ALL);
        level.setBlock(newPartnerPos, rotatedPartner, Block.UPDATE_ALL);
        level.playSound(null, anchorPos, rotatedHead.getSoundType().getHitSound(),
                SoundSource.BLOCKS, 0.9F, pitch ? 0.8F : 1.25F);
        return true;
    }

    /**
     * Turns a bed into its two carrier halves, or steps the carriers of a bed that already has them.
     *
     * <p>A bed is two positions, so both halves move together: each half becomes one
     * {@link RotatedBlock} carrier holding that half's own bed state, which keeps {@code PART} and
     * {@code FACING} intact and therefore keeps the pair a real, sleepable bed. The two carriers are
     * then stepped by the same yaw/pitch value, so they stay attached and keep facing each other.
     *
     * <p>Once wrapped, further clicks step the orientation in 45 degree increments in both axes -
     * eight yaws and eight pitches, the same resolution the side drain plate uses - instead of the
     * four horizontal facings vanilla allows.
     */
    private static boolean rotateBed(Level level, BlockPos pos, BlockState state, boolean pitch) {
        // Resolve the pair to its two vanilla bed states. A bed may be two plain BedBlocks or two
        // carriers; both are unwrapped here so the rest of the method works on bed states only.
        boolean clickedWrapped = state.getBlock() instanceof RotatedBlock;
        BlockState clickedBed = clickedWrapped ? RotatedBlock.innerState(level, pos) : state;
        if (clickedBed == null || !(clickedBed.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
            return false;
        }
        BlockPos headPos = clickedBed.getValue(net.minecraft.world.level.block.BedBlock.PART)
                == net.minecraft.world.level.block.state.properties.BedPart.HEAD
                ? pos : com.sidedrain.util.MultiBlockHelper.otherHalf(pos, clickedBed);
        if (headPos == null) {
            return false;
        }
        BlockState headState = level.getBlockState(headPos);
        BlockState headBed = headState.getBlock() instanceof RotatedBlock
                ? RotatedBlock.innerState(level, headPos) : headState;
        if (headBed == null || !(headBed.getBlock() instanceof net.minecraft.world.level.block.BedBlock)
                || headBed.getValue(net.minecraft.world.level.block.BedBlock.PART)
                != net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
            return false;
        }
        BlockPos footPos = com.sidedrain.util.MultiBlockHelper.otherHalf(headPos, headBed);
        if (footPos == null) {
            return false;
        }
        BlockState footState = level.getBlockState(footPos);
        BlockState footBed = footState.getBlock() instanceof RotatedBlock
                ? RotatedBlock.innerState(level, footPos) : footState;
        if (footBed == null || !(footBed.getBlock() instanceof net.minecraft.world.level.block.BedBlock)) {
            return false;
        }
        boolean headWrapped = headState.getBlock() instanceof RotatedBlock;
        boolean footWrapped = footState.getBlock() instanceof RotatedBlock;
        if (headWrapped != footWrapped) {
            return false;
        }

        if (pitch) {
            // Shift + right click: tilt. The bed becomes two carriers and steps its pitch by 45
            // degrees. A bed is ONE object two blocks long, so both halves turn about the middle of
            // the whole bed rather than about their own centres - that is what keeps the two halves
            // parallel and one block apart at every angle. Each carrier expresses that same world
            // point in its own local frame, so the two halves move by one rigid transform.
            if (!headWrapped) {
                return wrapBed(level, headPos, headBed, footPos, footBed, true);
            }
            return turnWrappedBed(level, headPos, footPos, true);
        }

        // Shift + left click: a plain quarter turn about the vertical axis, staying on the cell
        // grid. The head stays put and the foot swings around it, so both halves remain ordinary
        // blocks in ordinary cells with the vanilla model and collision box.
        if (headWrapped) {
            // Already tilted into carriers: step the yaw on both halves together, which keeps the
            // pair rigid instead of re-gridding it.
            return turnWrappedBed(level, headPos, footPos, false);
        }
        Direction facing = headBed.getValue(net.minecraft.world.level.block.BedBlock.FACING);
        // FACING runs foot -> head, so the foot sits one block behind the head along it.
        Direction newFacing = facing.getClockWise();
        BlockPos newFootPos = headPos.relative(newFacing.getOpposite());
        if (!newFootPos.equals(footPos)) {
            BlockState target = level.getBlockState(newFootPos);
            if (!target.canBeReplaced()) {
                return false;
            }
            level.removeBlock(footPos, false);
        }
        level.setBlock(headPos, headBed
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, newFacing)
                .setValue(net.minecraft.world.level.block.BedBlock.PART,
                        net.minecraft.world.level.block.state.properties.BedPart.HEAD), Block.UPDATE_ALL);
        level.setBlock(newFootPos, footBed
                .setValue(net.minecraft.world.level.block.BedBlock.FACING, newFacing)
                .setValue(net.minecraft.world.level.block.BedBlock.PART,
                        net.minecraft.world.level.block.state.properties.BedPart.FOOT), Block.UPDATE_ALL);
        level.playSound(null, headPos, headBed.getSoundType().getHitSound(),
                SoundSource.BLOCKS, 0.9F, 1.25F);
        return true;
    }

    /**
     * Lifts both halves of a vanilla bed into carriers, keeping {@code PART} and {@code FACING}.
     *
     * <p>Wrapping is done head first and the pair is restored as a pair: if either half fails to
     * convert, the already converted half is put back, so a bed is never left half-carried.
     */
    private static boolean wrapBed(Level level, BlockPos headPos, BlockState headState,
                                   BlockPos footPos, BlockState footState, boolean pitch) {
        boolean okHead = wrapOne(level, headPos, headState);
        boolean okFoot = okHead && wrapOne(level, footPos, footState);
        if (!okFoot) {
            if (okHead) {
                // Put the head back the way it was: a lone carrier is worse than no rotation.
                restoreCarrier(level, headPos);
            }
            return false;
        }
        // Step once so the very first click already changes something the player can see.
        return turnWrappedBed(level, headPos, footPos, pitch);
    }

    /** Wraps a single block position in a carrier, keeping its state and block entity data. */
    private static boolean wrapOne(Level level, BlockPos pos, BlockState state) {
        CompoundTag innerEntity = null;
        String innerName = "";
        BlockEntity existing = level.getBlockEntity(pos);
        if (existing instanceof net.minecraft.world.Nameable nameable && nameable.hasCustomName()) {
            innerName = nameable.getCustomName().getString();
        }
        if (existing != null) {
            innerEntity = existing.saveWithoutMetadata();
            innerEntity.remove("x");
            innerEntity.remove("y");
            innerEntity.remove("z");
            innerEntity.remove("id");
        }
        level.removeBlockEntity(pos);
        level.setBlock(pos, com.sidedrain.util.RotatedBedSupport.withBedFacing(
                ModBlocks.ROTATED_BLOCK.get().defaultBlockState()
                        .setValue(RotatedBlock.YAW, 0)
                        .setValue(RotatedBlock.PITCH, 0), state), Block.UPDATE_ALL);
        BlockEntity fresh = level.getBlockEntity(pos);
        if (!(fresh instanceof RotatedBlockEntity rotated)) {
            return false;
        }
        rotated.setInnerState(state);
        rotated.setInnerEntity(innerEntity);
        rotated.setInnerName(innerName);
        rotated.syncToClients();
        com.sidedrain.util.ShapeWarmup.warmRotatedShapes(state.getShape(level, pos));
        return true;
    }

    /** Puts a carrier's wrapped block back into the world, used to undo a half-finished wrap. */
    private static void restoreCarrier(Level level, BlockPos pos) {
        BlockState inner = RotatedBlock.innerState(level, pos);
        level.removeBlockEntity(pos);
        level.setBlock(pos, inner == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : inner,
                Block.UPDATE_ALL);
    }

    /**
     * Steps both carrier halves of a wrapped bed by one yaw or pitch step.
     *
     * <p>Both halves receive the same {@code YAW}/{@code PITCH} value, so they stay parallel and the
     * pair still lines up. {@code pitch} walks the pitch axis, otherwise the yaw axis, exactly like
     * the wrench does for every other carrier.
     */
    private static boolean turnWrappedBed(Level level, BlockPos headPos, BlockPos footPos, boolean pitch) {
        BlockState headCarrier = level.getBlockState(headPos);
        BlockState footCarrier = level.getBlockState(footPos);
        if (!(headCarrier.getBlock() instanceof RotatedBlock)
                || !(footCarrier.getBlock() instanceof RotatedBlock)) {
            return false;
        }
        int yaw = headCarrier.getValue(RotatedBlock.YAW);
        int pitchValue = headCarrier.getValue(RotatedBlock.PITCH);
        if (pitch) {
            pitchValue = (pitchValue + 1) % 8;
        } else {
            yaw = (yaw + 1) % 8;
        }
        // Both axes back at zero: give the bed back as a real bed, both halves at once.
        // Unwrapping only the clicked half would leave one carrier and one vanilla half, which is
        // exactly the broken pair this method exists to prevent.
        if (yaw == 0 && pitchValue == 0) {
            boolean okHead = unwrap(level, headPos);
            boolean okFoot = unwrap(level, footPos);
            return okHead && okFoot;
        }
        BlockState newHead = headCarrier.setValue(RotatedBlock.YAW, yaw).setValue(RotatedBlock.PITCH, pitchValue);
        BlockState newFoot = footCarrier.setValue(RotatedBlock.YAW, yaw).setValue(RotatedBlock.PITCH, pitchValue);
        // Both halves must keep advertising the bed's own facing, otherwise the head lookup and
        // startSleepInBed would read a stale direction after the pair is turned.
        BlockState headInner = RotatedBlock.innerState(level, headPos);
        BlockState footInner = RotatedBlock.innerState(level, footPos);
        newHead = com.sidedrain.util.RotatedBedSupport.withBedFacing(newHead, headInner);
        newFoot = com.sidedrain.util.RotatedBedSupport.withBedFacing(newFoot, footInner);
        level.setBlock(headPos, newHead, Block.UPDATE_ALL);
        level.setBlock(footPos, newFoot, Block.UPDATE_ALL);
        level.playSound(null, headPos, newHead.getSoundType().getHitSound(),
                SoundSource.BLOCKS, 0.9F, pitch ? 0.8F : 1.25F);
        return true;
    }

    // ------------------------------------------------------------------ dedicated rotatables

    /**
     * Swaps a block for its rotatable counterpart and turns that counterpart one step.
     *
     * @return the new state, or {@code null} when this block is not one of the special families
     */
    @Nullable
    private static BlockState promoteSpecial(Level level, BlockPos pos, BlockState state, boolean pitch) {
        if (state.getBlock() instanceof HopperBlock && !(state.getBlock() instanceof OmniHopperBlock)) {
            return turnHopper(promoteHopper(level, pos, state), pitch);
        }
        if (state.getBlock() instanceof OmniHopperBlock) {
            return turnHopper(state, pitch);
        }
        if (state.getBlock() instanceof RedStoneWireBlock || state.getBlock() instanceof StandingRedstoneBlock) {
            return turnRedstone(state, pitch);
        }
        if (state.getBlock() instanceof com.sidedrain.block.SideDrainBlock) {
            return turnPlate(state, pitch);
        }
        return null;
    }

    /**
     * Aims a Side Drain Plate's nozzle in 45 degree steps.
     *
     * <p>Shift + right walks the aim up through the eight steps; shift + left walks it back down.
     * The plate stays clamped to the same container face throughout - only where it points changes -
     * so a plate on a wall can be aimed straight out, diagonally up, or straight up, and items leave
     * along exactly the angle that was chosen. The collision box follows the same value, so the
     * shape you bump into is the plate you can see.
     */
    private static BlockState turnPlate(BlockState state, boolean pitch) {
        int tilt = state.getValue(com.sidedrain.block.SideDrainBlock.TILT);
        int steps = com.sidedrain.util.PlateAim.STEPS;
        // Right click is the pitch click, so it must walk the aim downwards (top to bottom) and the
        // left click must walk it back up. These were the other way round, which made the two clicks
        // look swapped to anyone comparing them with how the wrench turns every other block.
        int next = pitch ? (tilt + steps - 1) % steps : (tilt + 1) % steps;
        return state.setValue(com.sidedrain.block.SideDrainBlock.TILT, next);
    }

    /**
     * Turns one half of the hopper.
     *
     * <p>Pitch walks the bucket around the six faces; yaw walks the tail around its five remaining
     * directions. Between them the two clicks reach all thirty forms.
     */
    private static BlockState turnHopper(BlockState state, boolean pitch) {
        HopperOrientation current = state.getValue(OmniHopperBlock.FACING);
        return state.setValue(OmniHopperBlock.FACING, pitch ? current.nextBody() : current.nextSpout());
    }

    /**
     * Dust and the standing panel are one family: every pitch step walks the panel through its six
     * facings, and the step that would come back around lays it flat as dust again.
     */
    /**
     * Cycles a redstone panel through its six facings, and back to flat dust.
     *
     * <p>The cycle is a single ring of seven steps - UP, DOWN, NORTH, SOUTH, EAST, WEST, and then
     * flat dust - so every click moves it and six clicks always return it to ordinary dust. The
     * previous version had two separate tables that disagreed: the pitch table never reached
     * {@code EAST} on some paths and the yaw table mapped {@code EAST} onto {@code NORTH}, so a
     * panel could be walked into a facing it could never leave, which is what made redstone
     * "impossible to reset".
     *
     * <p>Both clicks walk the same ring. Pitch walks it forward, yaw walks it backward, which keeps
     * the two clicks distinguishable without ever breaking the cycle.
     */
    private static BlockState turnRedstone(BlockState state, boolean pitch) {
        if (state.getBlock() instanceof RedStoneWireBlock) {
            // Flat dust always stands up first, keeping its power so the wire does not blink.
            return ModBlocks.STANDING_REDSTONE.get().defaultBlockState()
                    .setValue(StandingRedstoneBlock.FACING, Direction.UP)
                    .setValue(StandingRedstoneBlock.POWER, state.getValue(RedStoneWireBlock.POWER));
        }
        Direction facing = state.getValue(StandingRedstoneBlock.FACING);
        // The ring, in the order a player meets it: floor, ceiling, then the four walls.
        Direction[] ring = {
                Direction.UP, Direction.DOWN,
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        int index = 0;
        for (int i = 0; i < ring.length; i++) {
            if (ring[i] == facing) {
                index = i;
                break;
            }
        }
        int next = pitch ? index + 1 : index - 1;
        if (next < 0) {
            next = ring.length - 1;
        }
        if (next >= ring.length) {
            // Walked off the end of the ring: back to flat dust, which is the reset the wrench needs.
            return net.minecraft.world.level.block.Blocks.REDSTONE_WIRE.defaultBlockState()
                    .setValue(RedStoneWireBlock.POWER, state.getValue(StandingRedstoneBlock.POWER));
        }
        return state.setValue(StandingRedstoneBlock.FACING, ring[next]);
    }

    // ------------------------------------------------------------------ carrier

    /**
     * Tilts a block that has no orientation of its own by moving it into the carrier.
     *
     * <p>The original state, block entity data and display name are stored so putting the block back
     * loses nothing and breaking the carrier can run the block's real loot table.
     */
    private static boolean wrap(Level level, BlockPos pos, BlockState state, boolean pitch) {
        CompoundTag innerEntity = null;
        String innerName = "";
        BlockEntity existing = level.getBlockEntity(pos);
        if (existing instanceof net.minecraft.world.Nameable nameable && nameable.hasCustomName()) {
            innerName = nameable.getCustomName().getString();
        }
        if (existing != null) {
            innerEntity = existing.saveWithoutMetadata();
            innerEntity.remove("x");
            innerEntity.remove("y");
            innerEntity.remove("z");
            innerEntity.remove("id");
        }
        level.removeBlockEntity(pos);
        level.setBlock(pos, com.sidedrain.util.RotatedBedSupport.withBedFacing(
                ModBlocks.ROTATED_BLOCK.get().defaultBlockState()
                        .setValue(RotatedBlock.YAW, 0)
                        .setValue(RotatedBlock.PITCH, 0), state), Block.UPDATE_ALL);
        BlockEntity fresh = level.getBlockEntity(pos);
        if (fresh instanceof RotatedBlockEntity rotated) {
            rotated.setInnerState(state);
            rotated.setInnerEntity(innerEntity);
            rotated.setInnerName(innerName);
            // One sync for the whole swap: the three setters used to each push a full block update,
            // and every one of them carried the inner block entity NBT to every nearby player.
            rotated.syncToClients();
            // The block is about to be rotated, and the first rotation of a large collision shape is
            // the expensive one. Build every combination now, on the background worker, so the turn
            // itself and every later query are cache reads.
            com.sidedrain.util.ShapeWarmup.warmRotatedShapes(state.getShape(level, pos));
        }
        return turnCarrier(level, pos, level.getBlockState(pos), pitch);
    }

    /** Advances the carrier's tilt; at zero on both axes the original block comes back. */
    private static boolean turnCarrier(Level level, BlockPos pos, BlockState state, boolean pitch) {
        int yaw = state.getValue(RotatedBlock.YAW);
        int tilt = state.getValue(RotatedBlock.PITCH);
        int nextYaw = pitch ? yaw : (yaw + 1) % 8;
        int nextTilt = pitch ? (tilt + 1) % 8 : tilt;
        if (nextYaw == 0 && nextTilt == 0) {
            return unwrap(level, pos);
        }
        BlockState rotated = state.setValue(RotatedBlock.YAW, nextYaw).setValue(RotatedBlock.PITCH, nextTilt);
        // FACING stays put: it is the wrapped bed's facing, not the model's heading. Re-sync it here
        // so a carrier whose inner state was replaced still answers the bed lookup correctly.
        BlockState inner = RotatedBlock.innerState(level, pos);
        if (inner != null) {
            rotated = com.sidedrain.util.RotatedBedSupport.withBedFacing(rotated, inner);
        }
        level.setBlock(pos, rotated, Block.UPDATE_ALL);
        level.playSound(null, pos, state.getSoundType().getHitSound(),
                SoundSource.BLOCKS, 0.9F, pitch ? 0.8F : 1.25F);
        return true;
    }

    /** Puts the wrapped block back exactly as it was, data and name included. */
    private static boolean unwrap(Level level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof RotatedBlockEntity rotated)) {
            return false;
        }
        BlockState inner = rotated.getInnerState();
        if (inner.isAir()) {
            return false;
        }
        CompoundTag innerEntity = rotated.getInnerEntity();
        level.removeBlockEntity(pos);
        level.setBlock(pos, inner, Block.UPDATE_ALL);
        if (innerEntity != null) {
            BlockEntity restored = level.getBlockEntity(pos);
            if (restored != null) {
                CompoundTag tag = innerEntity.copy();
                tag.remove("id");
                tag.remove("x");
                tag.remove("y");
                tag.remove("z");
                restored.load(tag);
                restored.setChanged();
            }
        }
        level.playSound(null, pos, inner.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.9F, 1.1F);
        return true;
    }

    // ------------------------------------------------------------------ hopper

    /** Replaces a vanilla hopper with the rotatable one, preserving its contents. */
    private static BlockState promoteHopper(Level level, BlockPos pos, BlockState state) {
        BlockEntity oldEntity = level.getBlockEntity(pos);
        CompoundTag saved = oldEntity == null ? null : oldEntity.saveWithoutMetadata();
        Direction facing = state.getValue(HopperBlock.FACING);

        // A vanilla hopper opens upwards and points its tail at FACING.
        BlockState promoted = ModBlocks.OMNI_HOPPER.get().defaultBlockState()
                .setValue(OmniHopperBlock.FACING, HopperOrientation.of(Direction.UP, facing))
                .setValue(OmniHopperBlock.ENABLED, state.getValue(HopperBlock.ENABLED));
        // Empty the old hopper first: replacing the block would otherwise spill its contents.
        if (oldEntity instanceof net.minecraft.world.Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                container.setItem(i, ItemStack.EMPTY);
            }
        }
        level.removeBlockEntity(pos);
        level.setBlock(pos, promoted, Block.UPDATE_ALL);
        if (saved != null) {
            BlockEntity fresh = level.getBlockEntity(pos);
            if (fresh != null) {
                saved.remove("id");
                saved.remove("x");
                saved.remove("y");
                saved.remove("z");
                fresh.load(saved);
                fresh.setChanged();
            }
        }
        return promoted;
    }
}
