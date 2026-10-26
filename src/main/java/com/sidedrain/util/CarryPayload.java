package com.sidedrain.util;

import com.sidedrain.block.SideDrainBlock;
import com.sidedrain.block.entity.SideDrainBlockEntity;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block plus everything clamped onto it, packed for travel.
 *
 * <p>When the Block Glove lifts a container that has Side Drain Plates bolted to its faces, the
 * plates have to come along. They are stored here with their own state and block entity data, so
 * once the block is put back down they resume draining exactly where they left off.
 *
 * <p>While the block is in the player's hands the plates are not in the world, so they are kept
 * alive by the carry manager and re-attached on placement.
 */
public final class CarryPayload {
    /** Removes every plate on {@code pos} from the world and returns them as NBT. */
    public static ListTag detachPlates(ServerLevel level, BlockPos pos) {
        ListTag plates = new ListTag();
        for (Direction side : Direction.values()) {
            BlockPos platePos = pos.relative(side);
            BlockState plateState = level.getBlockState(platePos);
            if (!(plateState.getBlock() instanceof SideDrainBlock)) {
                continue;
            }
            // A plate at pos.relative(side) was clicked on the face pointing that way, and
            // getStateForPlacement stores the clicked face as FACING - so FACING equals side.
            if (plateState.getValue(SideDrainBlock.FACING) != side) {
                continue;
            }
            CompoundTag plateTag = new CompoundTag();
            plateTag.putString("Side", side.getName());
            plateTag.put("State", NbtUtils.writeBlockState(plateState));
            BlockEntity plateEntity = level.getBlockEntity(platePos);
            if (plateEntity != null) {
                CompoundTag entityTag = plateEntity.saveWithoutMetadata();
                entityTag.remove("x");
                entityTag.remove("y");
                entityTag.remove("z");
                entityTag.remove("id");
                plateTag.put("Entity", entityTag);
            }
            plates.add(plateTag);
            level.removeBlockEntity(platePos);
            level.setBlock(platePos, Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        return plates;
    }

    /** Puts the stored plates back around {@code pos}, skipping any face that is now blocked. */
    public static void reattachPlates(ServerLevel level, BlockPos pos, ListTag plates) {
        for (int i = 0; i < plates.size(); i++) {
            CompoundTag plateTag = plates.getCompound(i);
            Direction side = Direction.byName(plateTag.getString("Side"));
            if (side == null) {
                continue;
            }
            BlockPos platePos = pos.relative(side);
            if (!level.getBlockState(platePos).canBeReplaced()) {
                continue;
            }
            BlockState plateState = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),
                    plateTag.getCompound("State"));
            if (plateState.isAir()) {
                continue;
            }
            level.setBlock(platePos, plateState, net.minecraft.world.level.block.Block.UPDATE_ALL);
            BlockEntity plateEntity = level.getBlockEntity(platePos);
            if (plateEntity != null && plateTag.contains("Entity", 10)) {
                CompoundTag entityTag = plateTag.getCompound("Entity").copy();
                entityTag.remove("x");
                entityTag.remove("y");
                entityTag.remove("z");
                entityTag.remove("id");
                plateEntity.load(entityTag);
                plateEntity.setChanged();
            }
        }
    }

    /** Every plate stored in the payload, as block entities, so they can keep draining. */
    public static List<SideDrainBlockEntity> livePlates(ServerLevel level, BlockPos pos, ListTag plates) {
        List<SideDrainBlockEntity> live = new ArrayList<>();
        for (int i = 0; i < plates.size(); i++) {
            CompoundTag plateTag = plates.getCompound(i);
            BlockState plateState = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),
                    plateTag.getCompound("State"));
            if (!(plateState.getBlock() instanceof SideDrainBlock)) {
                continue;
            }
            if (!(plateState.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock)) {
                continue;
            }
            BlockEntity created = entityBlock.newBlockEntity(pos, plateState);
            if (!(created instanceof SideDrainBlockEntity plate)) {
                continue;
            }
            plate.setLevel(level);
            // The detached plate is not in the world, so its state has to be recorded explicitly;
            // serverTick reads FACING and the block entity's own data from it.
            plate.setDetachedState(plateState);
            if (plateTag.contains("Entity", 10)) {
                CompoundTag entityTag = plateTag.getCompound("Entity").copy();
                entityTag.remove("x");
                entityTag.remove("y");
                entityTag.remove("z");
                entityTag.remove("id");
                try {
                    plate.load(entityTag);
                } catch (Exception ignored) {
                    // A plate with unreadable data still works with its defaults.
                }
            }
            live.add(plate);
        }
        return live;
    }

    /**
     * Writes the live plates back into the payload, so their progress is not lost.
     *
     * <p>Matched by side rather than by index, so a plate that failed to rebuild can never shift the
     * stored data of the ones after it.
     */
    public static void storePlates(ListTag plates, List<SideDrainBlockEntity> live) {
        for (SideDrainBlockEntity plate : live) {
            if (!(plate.getBlockState().getBlock() instanceof SideDrainBlock drainBlock)) {
                continue;
            }
            net.minecraft.core.Direction facing = plate.getBlockState().getValue(SideDrainBlock.FACING);
            CompoundTag target = findPlate(plates, facing);
            if (target == null) {
                continue;
            }
            CompoundTag saved = plate.saveWithoutMetadata();
            saved.remove("x");
            saved.remove("y");
            saved.remove("z");
            saved.remove("id");
            target.put("Entity", saved);
        }
    }

    @Nullable
    public static CompoundTag findPlate(ListTag plates, Direction side) {
        for (int i = 0; i < plates.size(); i++) {
            CompoundTag plateTag = plates.getCompound(i);
            if (side.getName().equals(plateTag.getString("Side"))) {
                return plateTag;
            }
        }
        return null;
    }

    private CarryPayload() {
    }
}
