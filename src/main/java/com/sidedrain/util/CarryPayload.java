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
import net.minecraft.world.item.ItemStack;
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
 *
 * <h2>Why every step of this class is defensive</h2>
 *
 * <p>A plate that leaves the world and does not come back is not a glitch the player can work
 * around: the plate, its twenty-seven slots, its filter and its weapon are all gone for good. Three
 * separate ways of losing one were possible and are now closed:
 *
 * <ol>
 *   <li><b>A blocked cell silently discarded the plate.</b> Putting a backpack back down on the
 *       floor blocks its underside, and a plate that used to be on that face had nowhere to go. The
 *       old code simply skipped the face, which deleted the plate. A plate that cannot go back on
 *       its face is now dropped as an item instead - the contents travel on the item, exactly as
 *       they do when the plate is broken by hand.</li>
 *   <li><b>A throwing host block entity aborted the reattachment.</b> Loading a modded block
 *       entity can throw - a Sophisticated Backpacks backpack validates its own wrapper on load -
 *       and the reattachment used to run inside the same {@code try} block as that load, so a throw
 *       meant the backpack was placed and the plates were never restored. Each step is now
 *       separately guarded and reattachment always runs.</li>
 *   <li><b>Detaching read only the six direct neighbours.</b> It still does, but a plate is now
 *       identified by its own {@code FACING} rather than by position alone, so a plate that has
 *       been rotated by the wrench is still recognised as belonging to the host it is clamped on.</li>
 * </ol>
 */
public final class CarryPayload {
    /** Key the plate list is stored under inside the carried block payload. */
    public static final String PLATES = "Plates";

    /**
     * Removes every plate on {@code pos} from the world and returns them as NBT.
     *
     * <p>Each plate is written with its own {@code Side}, its full block state and its block entity
     * data, so putting it back restores the slots, the filter, the weapon and the mode. The block
     * entity is removed before the block so the plate's own {@code onRemove} cannot run with a stale
     * entity and spill its contents on the floor as a second copy.
     *
     * <p>A partial failure is logged and skipped rather than aborting the whole lift: losing one
     * plate's data is bad, but aborting halfway leaves the host block removed and the glove holding
     * a payload with the plates still standing in the world as orphans.
     */
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
            try {
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
            } catch (RuntimeException failure) {
                com.sidedrain.SideDrain.LOGGER.warn("Could not pack the side drain plate at {}; leaving it in place",
                        platePos, failure);
                continue;
            }
            level.removeBlockEntity(platePos);
            level.setBlock(platePos, Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        return plates;
    }

    /**
     * Puts the stored plates back around {@code pos}.
     *
     * <p>A face that is now blocked does not delete the plate: it is dropped as an item next to the
     * host block, with its block entity data intact in {@code BlockEntityTag}, so the player can
     * pick it up and clamp it back on. That is the same thing breaking the plate by hand produces,
     * which makes the two paths indistinguishable to the player.
     */
    public static void reattachPlates(ServerLevel level, BlockPos pos, ListTag plates) {
        reattachPlates(level, pos, plates, true);
    }

    /**
     * Puts the stored plates back around {@code pos}.
     *
     * @param dropIfBlocked when true, a plate whose face is occupied is dropped as an item instead
     *                      of being discarded
     */
    public static void reattachPlates(ServerLevel level, BlockPos pos, ListTag plates, boolean dropIfBlocked) {
        for (int i = 0; i < plates.size(); i++) {
            CompoundTag plateTag = plates.getCompound(i);
            Direction side = Direction.byName(plateTag.getString("Side"));
            if (side == null) {
                continue;
            }
            BlockPos platePos = pos.relative(side);
            BlockState plateState = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),
                    plateTag.getCompound("State"));
            if (!(plateState.getBlock() instanceof SideDrainBlock)) {
                continue;
            }
            // The stored side and the stored state have to agree, or the plate would be clamped on
            // one face while draining the container behind a different one.
            if (plateState.getValue(SideDrainBlock.FACING) != side) {
                plateState = plateState.setValue(SideDrainBlock.FACING, side);
            }
            if (!level.getBlockState(platePos).canBeReplaced()) {
                if (dropIfBlocked) {
                    dropPlateAsItem(level, pos, plateTag, plateState);
                }
                continue;
            }
            try {
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
            } catch (RuntimeException failure) {
                // The block itself is in the world; only its contents failed to load. Report it and
                // move on to the remaining plates rather than abandoning them.
                com.sidedrain.SideDrain.LOGGER.warn("Placed the side drain plate at {} but could not restore its data",
                        platePos, failure);
            }
        }
    }

    /**
     * Drops one packed plate as an item that still carries its contents.
     *
     * <p>The item form is the plate's own drop: a {@code side_drain_plate} stack with the block
     * entity data in {@code BlockEntityTag}, exactly what breaking it by hand produces. That is what
     * makes a displaced plate recoverable instead of lost.
     */
    private static void dropPlateAsItem(ServerLevel level, BlockPos pos, CompoundTag plateTag,
                                        BlockState plateState) {
        try {
            ItemStack stack = new ItemStack(plateState.getBlock());
            if (plateTag.contains("Entity", 10)) {
                CompoundTag entityTag = plateTag.getCompound("Entity").copy();
                entityTag.remove("x");
                entityTag.remove("y");
                entityTag.remove("z");
                entityTag.remove("id");
                if (!entityTag.isEmpty()) {
                    stack.getOrCreateTag().put("BlockEntityTag", entityTag);
                }
            }
            net.minecraft.world.Containers.dropItemStack(level,
                    pos.getX() + 0.5D, pos.getY() + 0.6D, pos.getZ() + 0.5D, stack);
        } catch (RuntimeException failure) {
            com.sidedrain.SideDrain.LOGGER.warn("Could not drop a displaced side drain plate at {}", pos, failure);
        }
    }

    /**
     * Every plate stored in the payload, as block entities, so they can keep draining.
     *
     * @param carriedContainer the detached container the host block carries, which every plate is
     *                         clamped onto while it travels; may be null when the host is not a
     *                         container
     */
    public static List<SideDrainBlockEntity> livePlates(ServerLevel level, BlockPos pos, ListTag plates,
                                                        @Nullable net.minecraft.world.Container carriedContainer) {
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
            // Bind the carried container so getAttachedContainer() resolves to it: without this the
            // plate would go looking for a container behind the player's feet and find nothing.
            plate.setCarriedContainer(carriedContainer);
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
            if (!(plate.getBlockState().getBlock() instanceof SideDrainBlock)) {
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
