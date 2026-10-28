package com.sidedrain.util;

import com.sidedrain.menu.PersistentCraftingMenu;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A read/write {@link Container} view over a crafting table's persistent grid.
 *
 * <h2>Layout</h2>
 *
 * <p>The view exposes <b>ten</b> slots:
 *
 * <ul>
 *   <li>slots 0..8 are the table's own grid, backed by {@link CraftingGridStorage};</li>
 *   <li>slot 9 is the <b>craft result</b> - the recipe output for whatever currently sits on the
 *       grid. It is computed on demand and never stored.</li>
 * </ul>
 *
 * <p>The plate treats slot 9 like any other slot, which is what closes the loop: a plate in a
 * draining mode sees the result appear on slot 9 as soon as the grid happens to hold a valid recipe,
 * pulls it out, and the recipe consumes the ingredients. That is automated crafting with no modded
 * machine - just a table, a plate and the mode switch.
 *
 * <h2>Write rules</h2>
 *
 * <p>Writes to slots 0..8 go to the persistent grid. Writes to slot 9 are refused: the result is
 * computed from the grid, so storing into it would either be overwritten by the next recipe check or
 * would fabricate items. A plate in a feeding mode therefore delivers ingredients into the grid
 * slots and never pushes into the result.
 */
public final class CraftingTableContainer implements Container {
    /** Total exposed slots: nine grid slots plus the craft result. */
    public static final int SLOTS = CraftingGridStorage.GRID_SLOTS + 1;
    /** The index of the craft-result slot inside this view. */
    public static final int RESULT_SLOT = CraftingGridStorage.GRID_SLOTS;

    /**
     * The grid of every crafting menu a player currently has open, keyed the same way the saved data
     * is.
     *
     * <p>This exists because a table has <b>two</b> representations of its grid while someone is
     * using it: the transient container inside the open {@code CraftingMenu}, which is what the
     * player is looking at and editing, and the persistent storage. Writing straight to storage
     * while a menu is open means the plate's items land somewhere the player cannot see, and then
     * {@code PersistentCraftingMenu.removed} overwrites storage with the menu's stale grid and the
     * plate's items vanish. Routing every read and write through the live menu while one is open
     * keeps the player, the plate and the block position on one single inventory.
     */
    private static final java.util.Map<String, PersistentCraftingMenu> OPEN_MENUS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Registers an open menu so the container view writes into it instead of storage. */
    public static void registerOpenMenu(Level level, BlockPos pos, PersistentCraftingMenu menu) {
        if (level.isClientSide) {
            return;
        }
        OPEN_MENUS.put(key(level, pos), menu);
    }

    /**
     * Drops the registration; called from the menu's {@code removed}.
     *
     * <p>Only the menu that is actually registered may unregister: two players can have the same
     * table open, and the second one's registration must not be dropped when the first one closes
     * their screen.
     */
    public static void unregisterOpenMenu(Level level, @Nullable BlockPos pos,
                                          PersistentCraftingMenu menu) {
        if (level.isClientSide || pos == null) {
            return;
        }
        OPEN_MENUS.remove(key(level, pos), menu);
    }

    private static String key(Level level, BlockPos pos) {
        return level.dimension().location() + "@" + pos.asLong();
    }

    @javax.annotation.Nullable
    private static PersistentCraftingMenu openMenu(Level level, BlockPos pos) {
        if (level.isClientSide) {
            return null;
        }
        return OPEN_MENUS.get(key(level, pos));
    }

    private final Level level;
    private final BlockPos pos;

    private CraftingTableContainer(Level level, BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    /**
     * The container view for the table at {@code pos}, or {@code null} when the block there is not a
     * crafting table.
     *
     * <p>Vanilla's table is recognised directly; a modded table is one whose block is an instance of
     * vanilla's class (the usual way mods subclass it) - anything exotic that reimplements the
     * crafting screen from scratch keeps its own storage and is untouched.
     */
    @Nullable
    public static Container of(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // Only a table that really owns a vanilla 3x3 grid gets this view. A modded machine that
        // merely extends CraftingTableBlock keeps its own inventory: handing the plate a synthetic
        // nine-slot grid there would let it feed items into an inventory the machine never reads, so
        // the items would leave the plate and appear nowhere.
        if (com.sidedrain.util.CraftingTables.isPlainCraftingTable(state)) {
            return new CraftingTableContainer(level, pos);
        }
        return null;
    }

    /** The live grid array, read straight from storage. */
    private ItemStack[] grid() {
        return CraftingGridStorage.grid(this.level, this.pos);
    }

    /**
     * The menu the player currently has open on this table, or {@code null}.
     *
     * <p>When a menu is open its transient grid is the one the player sees, so every read and write
     * has to go there. Storage is only authoritative while nobody is looking at the table.
     */
    @Nullable
    private com.sidedrain.menu.PersistentCraftingMenu liveMenu() {
        return openMenu(this.level, this.pos);
    }

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    public boolean isEmpty() {
        com.sidedrain.menu.PersistentCraftingMenu menu = this.liveMenu();
        for (int i = 0; i < CraftingGridStorage.GRID_SLOTS; i++) {
            if (!(menu != null ? menu.gridStack(i) : this.grid()[i]).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            return ItemStack.EMPTY;
        }
        if (slot == RESULT_SLOT) {
            return this.craftResult();
        }
        com.sidedrain.menu.PersistentCraftingMenu menu = this.liveMenu();
        return menu != null ? menu.gridStack(slot) : this.grid()[slot];
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        if (slot < 0 || slot >= SLOTS || count <= 0) {
            return ItemStack.EMPTY;
        }
        if (slot == RESULT_SLOT) {
            return this.takeCraftResult(count);
        }
        com.sidedrain.menu.PersistentCraftingMenu menu = this.liveMenu();
        if (menu != null) {
            // The open menu owns the real stack: split it in place so the player's screen updates on
            // the very next broadcast instead of being overwritten on close.
            ItemStack existing = menu.gridStack(slot);
            if (existing.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack taken = existing.split(Math.min(count, existing.getCount()));
            menu.setGridStack(slot, existing.isEmpty() ? ItemStack.EMPTY : existing);
            return taken;
        }
        ItemStack[] grid = this.grid();
        ItemStack existing = grid[slot];
        if (existing.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = existing.split(Math.min(count, existing.getCount()));
        if (existing.isEmpty()) {
            grid[slot] = ItemStack.EMPTY;
        }
        CraftingGridStorage.store(this.level, this.pos, grid);
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot < 0 || slot >= SLOTS) {
            return ItemStack.EMPTY;
        }
        if (slot == RESULT_SLOT) {
            return this.takeCraftResult(Integer.MAX_VALUE);
        }
        com.sidedrain.menu.PersistentCraftingMenu menu = this.liveMenu();
        if (menu != null) {
            ItemStack taken = menu.gridStack(slot);
            menu.setGridStack(slot, ItemStack.EMPTY);
            return taken;
        }
        ItemStack[] grid = this.grid();
        ItemStack taken = grid[slot];
        grid[slot] = ItemStack.EMPTY;
        CraftingGridStorage.store(this.level, this.pos, grid);
        return taken;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        if (slot == RESULT_SLOT) {
            // The result is computed, never stored - see the class comment.
            return;
        }
        com.sidedrain.menu.PersistentCraftingMenu menu = this.liveMenu();
        if (menu != null) {
            menu.setGridStack(slot, stack.copy());
            return;
        }
        ItemStack[] grid = this.grid();
        grid[slot] = stack.copy();
        CraftingGridStorage.store(this.level, this.pos, grid);
    }

    @Override
    public void setChanged() {
        // The grid is stored on every write; nothing else to flush.
    }

    @Override
    public boolean stillValid(Player player) {
        return this.level.getBlockState(this.pos).getBlock()
                instanceof net.minecraft.world.level.block.CraftingTableBlock;
    }

    @Override
    public void clearContent() {
        com.sidedrain.menu.PersistentCraftingMenu menu = this.liveMenu();
        if (menu != null) {
            for (int i = 0; i < CraftingGridStorage.GRID_SLOTS; i++) {
                menu.setGridStack(i, ItemStack.EMPTY);
            }
            return;
        }
        CraftingGridStorage.clear(this.level, this.pos);
    }

    /**
     * The recipe result for the current grid, or empty when no recipe matches.
     *
     * <p>Read-only: taking from the result slot happens through {@link #takeCraftResult}, which
     * consumes the ingredients the recipe needs.
     */
    private ItemStack craftResult() {
        if (this.level.isClientSide) {
            return ItemStack.EMPTY;
        }
        var recipe = this.level.getRecipeManager().getRecipeFor(
                net.minecraft.world.item.crafting.RecipeType.CRAFTING, this.craftingView(), this.level);
        return recipe.map(value -> value.assemble(this.craftingView(), this.level.registryAccess()).copy())
                .orElse(ItemStack.EMPTY);
    }

    /**
     * Takes the craft result and consumes its ingredients: one full craft per call.
     *
     * <p>The consumption mirrors vanilla's {@code ResultSlot.onTake}: every non-empty grid slot loses
     * exactly one item, which is what every shaped and shapeless recipe assumes. Recipes with custom
     * remainder items ({@code getRemainingItems}) are honoured through the same helper vanilla uses.
     */
    private ItemStack takeCraftResult(int count) {
        ItemStack result = this.craftResult();
        if (result.isEmpty() || count <= 0) {
            return ItemStack.EMPTY;
        }
        // Remaining items (buckets that come back, durability containers, ...) are dropped at the
        // table, mirroring what happens when a player crafts by hand and the container retunes.
        NonNullList<ItemStack> remaining = this.level.getRecipeManager()
                .getRemainingItemsFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,
                        this.craftingView(), this.level);
        com.sidedrain.menu.PersistentCraftingMenu menu = this.liveMenu();
        if (menu != null) {
            // The open menu owns the real stacks; shrink and replace them through the menu so the
            // player's screen shows the ingredients being consumed.
            for (int i = 0; i < CraftingGridStorage.GRID_SLOTS; i++) {
                ItemStack slot = menu.gridStack(i);
                if (slot.isEmpty()) {
                    continue;
                }
                slot.shrink(1);
                if (slot.isEmpty()) {
                    menu.setGridStack(i, ItemStack.EMPTY);
                } else {
                    menu.setGridStack(i, slot);
                }
            }
            for (int i = 0; i < remaining.size() && i < CraftingGridStorage.GRID_SLOTS; i++) {
                ItemStack rest = remaining.get(i);
                if (rest.isEmpty()) {
                    continue;
                }
                ItemStack current = menu.gridStack(i);
                if (current.isEmpty()) {
                    menu.setGridStack(i, rest.copy());
                } else {
                    net.minecraft.world.Containers.dropItemStack(this.level, this.pos.getX() + 0.5D,
                            this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D, rest);
                }
            }
            // One craft per take: the amount switch of the plate handles repetition by re-calling.
            return result.copyWithCount(1);
        }
        ItemStack[] grid = this.grid();
        for (int i = 0; i < CraftingGridStorage.GRID_SLOTS; i++) {
            ItemStack slot = grid[i];
            if (!slot.isEmpty()) {
                slot.shrink(1);
                if (slot.isEmpty()) {
                    grid[i] = ItemStack.EMPTY;
                }
            }
        }
        for (int i = 0; i < remaining.size() && i < CraftingGridStorage.GRID_SLOTS; i++) {
            ItemStack rest = remaining.get(i);
            if (!rest.isEmpty()) {
                if (grid[i].isEmpty()) {
                    grid[i] = rest.copy();
                } else {
                    net.minecraft.world.Containers.dropItemStack(this.level, this.pos.getX() + 0.5D,
                            this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D, rest);
                }
            }
        }
        CraftingGridStorage.store(this.level, this.pos, grid);
        // One craft per take: the amount switch of the plate handles repetition by re-calling.
        return result.copyWithCount(1);
    }

    /** A plain CraftingContainer view over the grid, for the recipe manager's matching calls. */
    private net.minecraft.world.inventory.CraftingContainer craftingView() {
        NonNullList<ItemStack> crafting = NonNullList.withSize(CraftingGridStorage.GRID_SLOTS, ItemStack.EMPTY);
        com.sidedrain.menu.PersistentCraftingMenu menu = this.liveMenu();
        if (menu != null) {
            for (int i = 0; i < CraftingGridStorage.GRID_SLOTS; i++) {
                crafting.set(i, menu.gridStack(i));
            }
        } else {
            ItemStack[] grid = this.grid();
            for (int i = 0; i < CraftingGridStorage.GRID_SLOTS; i++) {
                crafting.set(i, grid[i]);
            }
        }
        return new net.minecraft.world.inventory.TransientCraftingContainer(
                new net.minecraft.world.inventory.AbstractContainerMenu(
                        net.minecraft.world.inventory.MenuType.CRAFTING, 0) {
                    @Override
                    public net.minecraft.world.inventory.Slot addSlot(
                            net.minecraft.world.inventory.Slot slot) {
                        return slot;
                    }

                    @Override
                    public boolean stillValid(Player player) {
                        return true;
                    }

                    @Override
                    public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) {
                        return net.minecraft.world.item.ItemStack.EMPTY;
                    }
                }, 3, 3, crafting);
    }

    /** Drops the whole grid on the ground - used when the table block is broken. */
    public void spill() {
        CraftingGridStorage.spill(this.level, this.pos);
    }

    /** True when the table's grid holds at least one item. */
    public boolean hasItems() {
        com.sidedrain.menu.PersistentCraftingMenu menu = this.liveMenu();
        if (menu != null) {
            for (int i = 0; i < CraftingGridStorage.GRID_SLOTS; i++) {
                if (!menu.gridStack(i).isEmpty()) {
                    return true;
                }
            }
            return false;
        }
        return CraftingGridStorage.hasGrid(this.level, this.pos);
    }
}
