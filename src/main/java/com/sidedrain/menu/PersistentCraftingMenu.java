package com.sidedrain.menu;

import com.sidedrain.util.CraftingGridStorage;
import com.sidedrain.util.CraftingTableContainer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CraftingTableBlock;

/**
 * The crafting menu bound to a <b>persistent</b> table grid.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Vanilla's {@link CraftingMenu} hands the whole grid back to the player when the screen closes
 * ({@code removed} → {@code clearContainer}), because the grid is a transient container owned by the
 * menu. That behaviour is exactly what broke the plate-table integration: items left on the table did
 * not stay there, and the plate's storage-side view of the grid went stale the moment a player opened
 * the table.
 *
 * <p>This subclass keeps every vanilla crafting behaviour - the result slot, the recipe book hooks,
 * the shift-click routing - and changes one thing: {@link #removed} writes the grid into
 * {@link CraftingGridStorage} instead of returning it. The table's grid becomes a real, persistent
 * container, which is what the plate's slot-to-slot strategy pairs against.
 *
 * <h2>One grid, two owners</h2>
 *
 * <p>While this menu is open, its transient grid is the authoritative copy: it is what the player
 * sees and edits. The plate reads and writes through {@link CraftingTableContainer}, which resolves
 * this menu from the open-menu registry and touches the live slots instead of storage. Without that
 * bridge the plate wrote into storage while the player looked at a different grid, and the close path
 * then overwrote storage with the stale menu - the plate's items simply disappeared.
 *
 * <h2>Registration</h2>
 *
 * <p>The menu lives under its own {@link com.sidedrain.registry.ModMenus#PERSISTENT_CRAFTING} type so
 * the client can build it through a registered factory; the screen side reuses vanilla's
 * {@code CraftingScreen}, which accepts any {@code CraftingMenu} subclass.
 */
public class PersistentCraftingMenu extends CraftingMenu {
    /** The table this menu is bound to; null for a disconnected or modded table. */
    @Nullable
    private final BlockPos tablePos;

    /** The player this menu belongs to; needed to reach the level when persisting. */
    @Nullable
    private final Player owner;

    public PersistentCraftingMenu(int id, Inventory inventory, ContainerLevelAccess access) {
        super(id, inventory, access);
        this.tablePos = access.evaluate((level, pos) -> pos).orElse(null);
        this.owner = inventory.player;
    }

    /**
     * Client-side factory entry: the table position rides the network buffer.
     *
     * <p>The position is written <b>once</b> by the server and must be read <b>once</b> here. The
     * previous version read it twice - once for the super constructor's {@code ContainerLevelAccess}
     * and once for {@link #tablePos} - while the server only ever wrote it once. The second read ran
     * off the end of the buffer, threw inside Forge's open-screen handler, and the client never
     * built the screen at all: right-clicking a crafting table did absolutely nothing, with no error
     * the player could see. Reading it into a local and using it for both fields is the fix.
     */
    public PersistentCraftingMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, buffer.readBlockPos());
    }

    /** Shared by the client factory and the server-side menu constructor. */
    private PersistentCraftingMenu(int id, Inventory inventory, BlockPos tablePos) {
        super(id, inventory, ContainerLevelAccess.create(inventory.player.level(), tablePos));
        this.tablePos = tablePos;
        this.owner = inventory.player;
    }

    /** Menu slot index of the first grid cell; slot 0 is the craft result. */
    private static final int CRAFT_GRID_MENU_START = 1;

    /**
     * The live grid stack at {@code index}, read from the menu's own slots.
     *
     * <p>While a menu is open its transient grid is the real grid - it is what the player sees and
     * edits - so the plate's container view must read from here rather than from storage. Reading
     * storage while the player is looking at the menu shows the plate an empty table and it does
     * nothing.
     */
    public ItemStack gridStack(int index) {
        if (index < 0 || index >= CraftingGridStorage.GRID_SLOTS) {
            return ItemStack.EMPTY;
        }
        return this.getSlot(CRAFT_GRID_MENU_START + index).getItem();
    }

    /**
     * Writes one grid cell in the open menu, then persists it.
     *
     * <p>Writing only to storage would be invisible to the player until they closed the screen, and
     * the close path would then overwrite storage with whatever the menu still held. Writing the menu
     * slot and mirroring it into storage keeps both in step the moment the plate moves something.
     */
    public void setGridStack(int index, ItemStack stack) {
        if (index < 0 || index >= CraftingGridStorage.GRID_SLOTS) {
            return;
        }
        this.getSlot(CRAFT_GRID_MENU_START + index).set(stack);
        // Recompute the result slot so a recipe completed by the plate appears immediately.
        this.slotsChanged(null);
        this.persistGridNow();
        this.broadcastChanges();
    }

    /** Copies the live grid into the persistent storage immediately. */
    public void persistGridNow() {
        if (this.tablePos == null || !(this.owner.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        ItemStack[] grid = CraftingGridStorage.grid(serverLevel, this.tablePos);
        for (int i = 0; i < CraftingGridStorage.GRID_SLOTS; i++) {
            grid[i] = this.gridStack(i).copy();
        }
        CraftingGridStorage.store(serverLevel, this.tablePos, grid);
    }

    /**
     * Persists the grid instead of handing it back to the player.
     *
     * <p>The vanilla implementation hands the grid to {@code clearContainer}, which either returns
     * the stacks to the player's inventory or drops them on the ground - both wrong for a table the
     * player expects to keep its half-finished recipe. Here the grid is copied into the table's
     * persistent storage and the transient container is left empty, so nothing is returned and
     * nothing is duplicated.
     */
    @Override
    public void removed(Player player) {
        // Persist BEFORE super runs: super would clearContainer the grid. After the save the grid
        // slots are emptied, so super's clear finds nothing to return or drop.
        this.saveGrid(player);
        CraftingTableContainer.unregisterOpenMenu(player.level(), this.tablePos, this);
        super.removed(player);
    }

    private void saveGrid(Player player) {
        if (!(player.level() instanceof ServerLevel serverLevel) || this.tablePos == null) {
            return;
        }
        ItemStack[] grid = CraftingGridStorage.grid(serverLevel, this.tablePos);
        for (int i = 0; i < CraftingGridStorage.GRID_SLOTS; i++) {
            // Menu slots 1..9 are the grid (slot 0 is the result).
            grid[i] = this.getSlot(CRAFT_GRID_MENU_START + i).getItem().copy();
        }
        CraftingGridStorage.store(serverLevel, this.tablePos, grid);
        // Empty the transient grid so super.removed()'s clearContainer has nothing to move or drop.
        for (int i = 0; i < CraftingGridStorage.GRID_SLOTS; i++) {
            this.getSlot(CRAFT_GRID_MENU_START + i).set(ItemStack.EMPTY);
        }
    }

    /**
     * Restores the stored grid into this freshly opened menu.
     *
     * <p>Called from the open path, so the player sees the half-finished recipe they left on the
     * table rather than an empty grid, and so the plate's view of the table matches what is shown.
     */
    public void restoreGrid(Level level) {
        if (this.tablePos == null) {
            return;
        }
        ItemStack[] grid = CraftingGridStorage.grid(level, this.tablePos);
        for (int i = 0; i < CraftingGridStorage.GRID_SLOTS; i++) {
            this.getSlot(CRAFT_GRID_MENU_START + i).set(grid[i].copy());
        }
        // Register only after the restore, so the container view never sees a half-filled menu.
        CraftingTableContainer.registerOpenMenu(level, this.tablePos, this);
        this.slotsChanged(null);
        this.broadcastChanges();
    }

    /** The table this menu writes its grid into, or {@code null}. */
    @Nullable
    public BlockPos tablePos() {
        return this.tablePos;
    }

    @Override
    public boolean stillValid(Player player) {
        // The same test the open handler uses: any crafting table, vanilla or a modded subclass. The
        // old check compared against Blocks.CRAFTING_TABLE by identity, so a modded table opened the
        // menu and had it closed again on the very next tick - which reads as "right-click does
        // nothing".
        if (this.tablePos != null) {
            return player.level().getBlockState(this.tablePos).getBlock() instanceof CraftingTableBlock
                    && player.distanceToSqr(this.tablePos.getX() + 0.5D, this.tablePos.getY() + 0.5D,
                    this.tablePos.getZ() + 0.5D) < 64.0D;
        }
        return super.stillValid(player);
    }

    @Override
    public MenuType<?> getType() {
        // The vanilla super hardcodes MenuType.CRAFTING; this menu rides its own registered type so
        // the client factory builds the right class. Returning it here keeps the menu's own type
        // self-consistent without touching vanilla sync (which uses the container id).
        return com.sidedrain.registry.ModMenus.PERSISTENT_CRAFTING.get();
    }

    /** The title shown on the screen - vanilla's crafting title. */
    public static Component title() {
        return Component.translatable("container.crafting");
    }
}
