package com.sidedrain.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * Persistent storage for the nine crafting-grid slots of every crafting table in the world.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A vanilla crafting table keeps its grid in the <em>menu</em>, not in the world: the grid is a
 * {@code TransientCraftingContainer} owned by the open {@code CraftingMenu}, and
 * {@code CraftingMenu.removed} drops whatever is on the grid at the player's feet when the screen
 * closes. A plate cannot clamp onto that - there is nothing at the block position to read.
 *
 * <p>The fix is a per-position save file: when a crafting menu closes, its grid is copied here
 * instead of being dropped; when one opens, the grid is restored from here. The table then behaves
 * like the shulker box everyone wishes it was - items left on it stay on it - and the block position
 * has a real {@link net.minecraft.world.Container} the plate can drain, feed and pair with.
 *
 * <h2>Persistence</h2>
 *
 * <p>The map hangs off the overworld's {@link DimensionDataStorage} as a {@link SavedData}, keyed by
 * dimension + position, so grids survive saves, reloads and server restarts like any other block
 * entity data.
 */
public final class CraftingGridStorage {
    /** The nine slots a crafting grid holds; matches the vanilla CraftingMenu layout. */
    public static final int GRID_SLOTS = 9;

    private static final String DATA_NAME = "sidedrain_crafting_grids";

    private CraftingGridStorage() {
    }

    /** One table's grid. */
    public static final class Grid {
        public final ItemStack[] slots = new ItemStack[GRID_SLOTS];

        public Grid() {
            for (int i = 0; i < GRID_SLOTS; i++) {
                this.slots[i] = ItemStack.EMPTY;
            }
        }

        public boolean isEmpty() {
            for (ItemStack slot : this.slots) {
                if (!slot.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** The {@link SavedData} carrying every grid, per dimension. */
    public static final class Saved extends SavedData {
        /** Key: dimension id + packed position. Value: the grid. */
        private final Map<String, ItemStack[]> grids = new ConcurrentHashMap<>();

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Map.Entry<String, ItemStack[]> entry : this.grids.entrySet()) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.putString("Key", entry.getKey());
                ListTag items = new ListTag();
                for (ItemStack slot : entry.getValue()) {
                    CompoundTag item = slot.isEmpty() ? new CompoundTag() : slot.save(new CompoundTag());
                    items.add(item);
                }
                entryTag.put("Items", items);
                list.add(entryTag);
            }
            tag.put("Grids", list);
            return tag;
        }

        private static Saved load(CompoundTag tag) {
            Saved saved = new Saved();
            ListTag list = tag.getList("Grids", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entryTag = list.getCompound(i);
                String key = entryTag.getString("Key");
                ListTag items = entryTag.getList("Items", Tag.TAG_COMPOUND);
                ItemStack[] slots = new ItemStack[GRID_SLOTS];
                for (int s = 0; s < GRID_SLOTS && s < items.size(); s++) {
                    CompoundTag item = items.getCompound(s);
                    slots[s] = item.isEmpty() ? ItemStack.EMPTY : ItemStack.of(item);
                }
                saved.grids.put(key, slots);
            }
            return saved;
        }

        private ItemStack[] gridFor(String key, boolean create) {
            ItemStack[] found = this.grids.get(key);
            if (found == null && create) {
                found = new ItemStack[GRID_SLOTS];
                for (int i = 0; i < GRID_SLOTS; i++) {
                    found[i] = ItemStack.EMPTY;
                }
                this.grids.put(key, found);
                this.setDirty();
            }
            return found;
        }
    }

    private static String key(Level level, BlockPos pos) {
        ResourceKey<Level> dimension = level.dimension();
        return dimension.location() + "@" + pos.asLong();
    }

    /** The per-dimension saved data, or {@code null} when the world is not ready. */
    @Nullable
    private static Saved saved(Level level) {
        if (level.isClientSide) {
            return null;
        }
        // The overworld's data storage is shared by every dimension on the server, which keeps the
        // grids in one file instead of scattering them across the dimensions.
        DimensionDataStorage storage = level.getServer().overworld().getDataStorage();
        return storage.computeIfAbsent(Saved::load, Saved::new, DATA_NAME);
    }

    /** Reads the grid of the table at {@code pos}, creating an empty one when absent. */
    public static ItemStack[] grid(Level level, BlockPos pos) {
        Saved data = saved(level);
        if (data == null) {
            return new Grid().slots;
        }
        return data.gridFor(key(level, pos), true);
    }

    /** Stores {@code slots} as the grid of the table at {@code pos}. */
    public static void store(Level level, BlockPos pos, ItemStack[] slots) {
        Saved data = saved(level);
        if (data == null) {
            return;
        }
        ItemStack[] target = data.gridFor(key(level, pos), true);
        for (int i = 0; i < GRID_SLOTS; i++) {
            target[i] = slots[i].copy();
        }
        data.setDirty();
    }

    /** True when the table at {@code pos} has anything on its grid. */
    public static boolean hasGrid(Level level, BlockPos pos) {
        Saved data = saved(level);
        if (data == null) {
            return false;
        }
        ItemStack[] found = data.grids.get(key(level, pos));
        if (found == null) {
            return false;
        }
        for (ItemStack slot : found) {
            if (!slot.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the table's grid on the ground and forgets it.
     *
     * <p>Called when the table block is broken: a shulker-box-style table spills its grid rather
     * than eating it. The caller is responsible for already having broken the block.
     */
    public static void spill(Level level, BlockPos pos) {
        ItemStack[] grid = grid(level, pos);
        boolean any = false;
        for (ItemStack slot : grid) {
            if (!slot.isEmpty()) {
                Containers.dropItemStack(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, slot);
                any = true;
            }
        }
        if (any) {
            clear(level, pos);
        }
    }

    /** Forgets the table's grid without dropping anything. */
    public static void clear(Level level, BlockPos pos) {
        Saved data = saved(level);
        if (data == null) {
            return;
        }
        if (data.grids.remove(key(level, pos)) != null) {
            data.setDirty();
        }
    }
}
