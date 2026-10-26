package com.sidedrain.util;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Shared container lookup, so the plate, the hopper logic and the wrench all agree.
 *
 * <p>A chest is deliberately returned as its own block entity rather than through
 * {@code ChestBlock.getContainer}: the combined double-chest view rebuilds itself from a block
 * entity cache that is stale for a block entity that was only just placed, which made a plate on a
 * fresh chest see nothing. The plain block entity is always correct and still exposes every slot.
 */
public final class Containers {
    @Nullable
    public static Container getContainerAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // Air is the overwhelmingly common answer - a plate clamped to a wall, a hopper pointing at
        // open space - and the entity sweep below is by far the most expensive part of this method.
        // Bailing out here turns a full AABB query into one chunk lookup.
        if (state.isAir()) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof Container container) {
            return container;
        }
        // A rotated block hides its container inside a carrier. The plate must be able to clamp onto
        // a rotated chest and keep working, so unwrap it here rather than special casing every
        // caller: the plate, the hopper logic and the wrench all go through this one method.
        if (blockEntity instanceof com.sidedrain.block.entity.RotatedBlockEntity rotated) {
            net.minecraft.world.level.block.entity.BlockEntity inner = rotated.innerEntity(level);
            if (inner instanceof Container container) {
                return container;
            }
        }
        if (state.getBlock() instanceof WorldlyContainerHolder holder) {
            return holder.getContainer(state, level, pos);
        }
        List<Entity> entities = level.getEntities((Entity) null,
                new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1),
                EntitySelector.CONTAINER_ENTITY_SELECTOR);
        if (!entities.isEmpty()) {
            return (Container) entities.get(level.random.nextInt(entities.size()));
        }
        return null;
    }

    private Containers() {
    }
}
