package com.sidedrain.menu;

import com.sidedrain.block.entity.SideDrainBlockEntity;
import com.sidedrain.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Container menu for the Side Drain Plate.
 *
 * <p>Two plates share this menu: the plain one (27 storage slots, 3 x 3 filter) and the expanded one
 * (54 storage slots, 6 x 6 filter). Both are laid out by {@link PlateLayout}, so the slot geometry and
 * the frames the screen draws around them come from one place and cannot disagree.
 *
 * <h2>Why the sizes ride the opening packet</h2>
 *
 * <p>A menu's {@link ContainerData} channels are not readable when the client builds the menu: they
 * arrive in separate packets <em>after</em> the screen exists. Sizing the stub containers from them
 * therefore read zero, and the expanded plate would have opened as a plain one. The sizes are written
 * into the {@code openScreen} buffer instead, right next to the position, so both sides agree before
 * a single slot is added.
 *
 * <h2>The filter grid records items, it does not hold them</h2>
 *
 * <p>A filter cell is a <b>pin</b>: it says "this is an item the plate may move". It is not storage,
 * and the player's cursor is never spent filling it:
 *
 * <ul>
 *   <li>Click with an item on the cursor <b>records</b> that item. The cursor keeps its stack.</li>
 *   <li>Click on a cell that already records something <b>clears</b> that pin. Nothing is returned,
 *       because nothing was ever there.</li>
 *   <li>Click with an empty cursor on an empty cell does nothing.</li>
 * </ul>
 *
 * <p>The cells are real {@link Slot}s so the screen can paint them and so the click carries an
 * index, but they refuse both insertion and pickup, and {@link #clicked} intercepts them before
 * vanilla's click handling can run. That is what stops a recorded item from being picked up as a
 * free item - the bug that made the old grid behave like a tiny chest.
 */
public class SideDrainMenu extends AbstractContainerMenu {
    /** Plain plate storage: 27 slots, three rows of nine. */
    public static final int STORAGE_SLOTS = SideDrainBlockEntity.SLOT_COUNT;
    /** Plain plate filter: 9 cells, three rows of three. */
    public static final int FILTER_SLOTS = SideDrainBlockEntity.FILTER_COUNT;
    /** Expanded plate storage: 54 slots, six rows of nine. */
    public static final int EXPANDED_STORAGE_SLOTS = SideDrainBlockEntity.EXPANDED_SLOT_COUNT;
    /** Expanded plate filter: 36 cells, six rows of six. */
    public static final int EXPANDED_FILTER_SLOTS = SideDrainBlockEntity.EXPANDED_FILTER_COUNT;
    public static final int PLAYER_SLOTS = 36;

    public static final int DATA_MODE = 0;
    public static final int DATA_STRATEGY = 1;
    public static final int DATA_SLOT = 2;
    public static final int DATA_AMOUNT = 3;
    public static final int DATA_ENABLED = 4;
    public static final int DATA_WEAPON = 5;
    public static final int DATA_TARGET_SIZE = 6;
    public static final int DATA_RANGE = 7;
    public static final int DATA_USE_WEAPON = 8;
    /** How many dispensers the weapon slot holds; 0 when the plate has no launcher body installed. */
    public static final int DATA_DISPENSER = 9;
    /** The four melee targeting tags packed into one bitmask: bit0 players, 1 monsters, 2 friendlies, 3 neutrals. */
    public static final int DATA_MELEE_TAGS = 10;
    /** 0 = continuous, 1 = redstone edge. */
    public static final int DATA_ACTIVATION = 11;
    /** Storage slots this plate really has (27 or 54), so the screen can draw the right grid. */
    public static final int DATA_SLOT_COUNT = 12;
    /** Filter cells this plate really has (9 or 36). */
    public static final int DATA_FILTER_COUNT = 13;
    /** Ordinal into {@code WeaponKind}: what the weapon slot holds, classified on the server. */
    public static final int DATA_WEAPON_KIND = SideDrainBlockEntity.DATA_WEAPON_KIND;
    /** Ordinal into {@code BlockMiner.FACES}: which neighbour a tool plate digs. */
    public static final int DATA_DIG_FACE = SideDrainBlockEntity.DATA_DIG_FACE;
    /** Ordinal into {@code WeaponAmmo.Requirement}: what the weapon needs before it can fire. */
    public static final int DATA_AMMO_STATE = SideDrainBlockEntity.DATA_AMMO_STATE;
    public static final int DATA_COUNT = SideDrainBlockEntity.DATA_COUNT;

    /** Button ids. 0..11 are the switches the panel always had; the new ones are appended. */
    public static final int BUTTON_ACTIVATION = 12;
    public static final int BUTTON_CLEAR_FILTER = 13;
    /** Step the dig face backwards / forwards. Only shown while a tool is in the weapon slot. */
    public static final int BUTTON_DIG_PREV = 14;
    public static final int BUTTON_DIG_NEXT = 15;
    /** Steps the forward target scan range down and up. Only shown for a weapon needing a target. */
    public static final int BUTTON_RANGE_DOWN = 16;
    public static final int BUTTON_RANGE_UP = 17;

    private final Container container;
    private final Container filter;
    private final Container weapon;
    private final ContainerData data;
    private final BlockPos pos;
    private final int storageSlots;
    private final int filterSlots;

    /** Server-side constructor. */
    public SideDrainMenu(int id, Inventory inventory, SideDrainBlockEntity blockEntity) {
        this(id, inventory, blockEntity, blockEntity.getFilterView(), blockEntity.getWeaponView(),
                blockEntity.getBlockPos(), blockEntity.getData(),
                blockEntity.slotCount(), blockEntity.filterCount());
    }

    /**
     * Client-side constructor used by the menu type.
     *
     * <p>The position and both sizes are read here, in the order the server wrote them. The sizes
     * cannot come from the data slots: those are still empty at this point.
     */
    public SideDrainMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, buffer.readBlockPos(), buffer.readVarInt(), buffer.readVarInt());
    }

    private SideDrainMenu(int id, Inventory inventory, BlockPos pos, int storageSlots, int filterSlots) {
        this(id, inventory, new SimpleContainer(storageSlots), new SimpleContainer(filterSlots),
                new SimpleContainer(1), pos, new SimpleContainerData(DATA_COUNT), storageSlots, filterSlots);
    }

    private SideDrainMenu(int id, Inventory inventory, Container container, Container filter, Container weapon,
                          BlockPos pos, ContainerData data, int storageSlots, int filterSlots) {
        super(ModMenus.SIDE_DRAIN.get(), id);
        this.storageSlots = clamp(storageSlots, STORAGE_SLOTS, EXPANDED_STORAGE_SLOTS);
        this.filterSlots = clamp(filterSlots, FILTER_SLOTS, EXPANDED_FILTER_SLOTS);
        this.container = container;
        this.filter = filter;
        this.weapon = weapon;
        this.pos = pos;
        this.data = data;

        checkContainerSize(container, this.storageSlots);
        checkContainerSize(filter, this.filterSlots);
        checkContainerSize(weapon, 1);
        checkContainerDataCount(data, DATA_COUNT);

        PlateLayout layout = this.layout();
        int storageRows = this.storageSlots / layout.storageCols;
        for (int row = 0; row < storageRows; row++) {
            for (int col = 0; col < layout.storageCols; col++) {
                this.addSlot(new Slot(container, col + row * layout.storageCols,
                        layout.storageX + col * 18, layout.storageY + row * 18));
            }
        }
        this.addSlot(new Slot(weapon, 0, layout.weaponX, layout.weaponY) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                // One classifier decides, exactly as the block entity does.
                //
                // This used to ask isRangedWeapon || isMelee, which was correct until the tools
                // arrived: isMelee now delegates to usesTargetTags(), which is false for a pickaxe
                // by design, and isRangedWeapon is false for it too. The two together therefore
                // rejected every tool, so a pickaxe could not be put in the slot at all - the block
                // entity would have accepted it, but the slot refused it first.
                return com.sidedrain.util.WeaponKind.of(stack).ownsThePlate();
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col + row * 9 + 9,
                        layout.playerX + col * 18, layout.playerY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, layout.playerX + col * 18, layout.hotbarY));
        }
        // The filter cells are slots for exactly one reason: a slot is the only channel vanilla
        // synchronises for free. They are permanently inactive, so the screen never draws them, the
        // pointer never hovers them and no click ever reaches them - the cells are painted and
        // clicked by coordinate instead. Removing them entirely was the obvious design and it was
        // wrong: with no slots there is nothing to synchronise, and every pin rendered blank on the
        // client while the server held it correctly.
        for (int i = 0; i < this.filterSlots; i++) {
            int col = i % layout.filterCols;
            int row = i / layout.filterCols;
            this.addSlot(new SyncOnlySlot(filter, i,
                    layout.filterX + col * 18, layout.filterY + row * 18));
        }
        this.addDataSlots(data);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** The geometry this panel uses. */
    public PlateLayout layout() {
        return PlateLayout.of(this.storageSlots, this.filterSlots);
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public ContainerData getData() {
        return this.data;
    }

    /** Storage slots this plate really has. */
    public int getSlotCount() {
        return this.storageSlots;
    }

    /** Filter cells this plate really has. */
    public int getFilterCount() {
        return this.filterSlots;
    }

    public int getMode() {
        return this.data.get(DATA_MODE);
    }

    public int getStrategy() {
        return this.data.get(DATA_STRATEGY);
    }

    /** The chosen slot of the attached container. */
    public int getTargetSlot() {
        return this.data.get(DATA_SLOT);
    }

    /** How many slots the attached container has, so the screen can show "3 / 27" style hints. */
    public int getTargetSize() {
        return this.data.get(DATA_TARGET_SIZE);
    }

    public int getAmount() {
        return this.data.get(DATA_AMOUNT);
    }

    public boolean isEnabled() {
        return this.data.get(DATA_ENABLED) != 0;
    }

    public boolean hasWeapon() {
        return this.data.get(DATA_WEAPON) != 0;
    }

    /** Reach for loose items, in blocks around the plate. */
    public int getRange() {
        return this.data.get(DATA_RANGE);
    }

    /** Whether the plate may swing or fire the weapon in its weapon slot. */
    public boolean isUseWeapon() {
        return this.data.get(DATA_USE_WEAPON) != 0;
    }

    /**
     * How many dispensers the weapon slot holds, or 0 when there is no launcher body.
     *
     * <p>Only used to label the panel. The plate does not need the panel to know - it reads its own
     * weapon stack - but the player does: with a dispenser installed the plate shoots its cargo
     * instead of dropping it, and the speed and the firing rate both rise with the count.
     */
    public int getDispenserPower() {
        return this.data.get(DATA_DISPENSER);
    }

    /** Whether the weapon slot holds anything the plate can use, melee or ranged. */
    public int getDispenserDataWeapon() {
        return this.data.get(DATA_WEAPON);
    }

    /**
     * The four melee targeting switches, for the screen's labels.
     *
     * <p>These read the {@link ContainerData} bitmask, not the block entity. On the client the
     * {@code container} field is a {@link SimpleContainer} stub - the real plate never crosses the
     * network - so an {@code instanceof SideDrainBlockEntity} test silently failed on the client and
     * every switch read {@code false} no matter what the server had stored, which is exactly why the
     * switches looked dead. The data slot is the only channel that actually crosses.
     */
    public boolean isMeleePlayers() {
        return (this.data.get(DATA_MELEE_TAGS) & 1) != 0;
    }

    public boolean isMeleeMonsters() {
        return (this.data.get(DATA_MELEE_TAGS) & 2) != 0;
    }

    public boolean isMeleeFriendlies() {
        return (this.data.get(DATA_MELEE_TAGS) & 4) != 0;
    }

    public boolean isMeleeNeutrals() {
        return (this.data.get(DATA_MELEE_TAGS) & 8) != 0;
    }

    /** The four melee targeting tags as one bitmask, for the screen's switch labels. */
    public int getMeleeTagBits() {
        return this.data.get(DATA_MELEE_TAGS);
    }

    /**
     * How far in front of the plate a target may be, in blocks (1..15).
     *
     * <p>Only meaningful for weapons that cannot fire at empty air - a spell whose own logic insists
     * on a target. The screen uses it to label the range control.
     */
    public int getTargetRange() {
        return this.data.get(SideDrainBlockEntity.DATA_TARGET_RANGE);
    }

    /** True while the plate is woken up by redstone instead of running on its own clock. */
    public boolean isRedstoneActivation() {
        return this.data.get(DATA_ACTIVATION) == SideDrainBlockEntity.ACTIVATION_REDSTONE;
    }

    /** What the weapon slot holds, classified on the server. */
    public com.sidedrain.util.WeaponKind getWeaponKind() {
        com.sidedrain.util.WeaponKind[] all = com.sidedrain.util.WeaponKind.values();
        int ordinal = this.data.get(DATA_WEAPON_KIND);
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : com.sidedrain.util.WeaponKind.EMPTY;
    }

    /** True when the weapon slot holds a pickaxe, axe, shovel or hoe. */
    public boolean isTool() {
        return this.getWeaponKind().isTool();
    }

    /** True when the weapon slot holds something that attacks at range. */
    public boolean isRanged() {
        return this.getWeaponKind().isRanged();
    }

    /**
     * True when the four targeting switches are worth showing.
     *
     * <p>Deliberately NOT gated on the weapon switch being on, and not on the slot strategy: the
     * switches describe <b>what the plate may aim at</b>, and a player who has just put a staff in the
     * slot needs to set them before turning the weapon on. The classifier decides which families have
     * a use for them - casters do, bows and guns do not - so the panel always matches what the plate
     * actually does.
     */
    public boolean showsTargetTags() {
        return this.getWeaponKind().usesTargetTags();
    }

    /**
     * True when the panel should offer the forward scan range.
     *
     * <p>Two cases: a casting weapon whose spells may demand a target, and anything whose own logic
     * is known to refuse firing at nothing. A gun or a bow does not need it - an arrow into empty air
     * is a legitimate shot - so the control stays hidden for those.
     */
    public boolean showsTargetRange() {
        // Not gated on isUseWeapon(): the range describes how far the plate looks for a target, and a
        // player sets it while deciding whether the staff is worth switching on. Hiding it until the
        // switch was on meant a freshly placed staff plate showed no targeting controls at all.
        return this.getWeaponKind().isCaster();
    }

    /** What the weapon needs before it can fire. */
    public com.sidedrain.util.WeaponAmmo.Requirement getAmmoRequirement() {
        com.sidedrain.util.WeaponAmmo.Requirement[] all =
                com.sidedrain.util.WeaponAmmo.Requirement.values();
        int ordinal = this.data.get(DATA_AMMO_STATE);
        return ordinal >= 0 && ordinal < all.length
                ? all[ordinal] : com.sidedrain.util.WeaponAmmo.Requirement.NONE;
    }

    /** True when the weapon fires without needing any item at all. */
    public boolean isSelfPowered() {
        return this.getAmmoRequirement() == com.sidedrain.util.WeaponAmmo.Requirement.NONE;
    }

    /** Which of the six neighbours a tool plate digs. */
    public int getDigFace() {
        return Math.floorMod(this.data.get(DATA_DIG_FACE),
                com.sidedrain.util.BlockMiner.FACES.length);
    }

    /** The direction a tool plate digs, for the panel's label. */
    public net.minecraft.core.Direction getDigDirection() {
        return com.sidedrain.util.BlockMiner.FACES[this.getDigFace()];
    }

    /** True while the filter strategy is selected, which is when the filter grid is usable. */
    public boolean showsFilter() {
        return this.getStrategy() == SideDrainBlockEntity.STRATEGY_FILTER;
    }

    /** The item a filter cell records, or {@link ItemStack#EMPTY}. */
    public ItemStack getFilterCell(int index) {
        return index >= 0 && index < this.filterSlots ? this.filter.getItem(index) : ItemStack.EMPTY;
    }

    /** True when the filter grid has at least one pin in it. */
    public boolean hasFilterPins() {
        for (int i = 0; i < this.filterSlots; i++) {
            if (!this.filter.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which filter cell a panel-relative point lands in, or -1.
     *
     * <p>The cells are not slots, so there is no {@code hoveredSlot} to ask. The hit test is the
     * same rectangle the screen painted, taken from the same {@link PlateLayout}.
     */
    public int filterCellAt(double relativeX, double relativeY) {
        if (!this.showsFilter()) {
            return -1;
        }
        PlateLayout layout = this.layout();
        double localX = relativeX - layout.filterX;
        double localY = relativeY - layout.filterY;
        if (localX < 0.0D || localY < 0.0D) {
            return -1;
        }
        int col = (int) (localX / 18.0D);
        int row = (int) (localY / 18.0D);
        if (col < 0 || col >= layout.filterCols || row < 0 || row >= layout.filterRows) {
            return -1;
        }
        int index = col + row * layout.filterCols;
        return index < this.filterSlots ? index : -1;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.container.stillValid(player);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (this.container instanceof SideDrainBlockEntity plate) {
            plate.handleButton(id);
            return true;
        }
        return false;
    }

    /**
     * Records or clears one filter pin.
     *
     * <p>Called by the screen's click path, which is where the pointer position is known. A cell that
     * already records something is cleared first - that is the "click the shown item and it
     * disappears" gesture - and only an empty cell takes a copy of the cursor. The cursor itself is
     * never touched, which is the whole point: recording an item must not cost the player the item.
     *
     * <p>An empty cursor on an empty cell does nothing at all, so a stray click cannot wipe a filter.
     */
    public void clickFilterCell(int cell, ItemStack carried) {
        if (!this.showsFilter() || cell < 0 || cell >= this.filterSlots) {
            return;
        }
        if (!this.filter.getItem(cell).isEmpty()) {
            this.filter.setItem(cell, ItemStack.EMPTY);
            return;
        }
        if (!carried.isEmpty()) {
            this.filter.setItem(cell, carried.copyWithCount(1));
        }
    }

    /**
     * A filter cell's synchronisation channel, and nothing else.
     *
     * <p>It is permanently inactive, so vanilla's screen skips it when drawing, when hit-testing and
     * when resolving a click - which means it can never behave like a storage slot. Its only job is
     * to make {@code broadcastChanges} carry the recorded item to the client so the screen can paint
     * the pin.
     */
    private static final class SyncOnlySlot extends Slot {
        SyncOnlySlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }

    /**
     * Shift-click routing.
     *
     * <p>Filter cells are excluded by construction - they are the last slots in the list, past the
     * player inventory - so a shift-click can never pin something by accident. Everything that used
     * to route keeps routing: a stack from the plate goes to the player, a stack from the player goes
     * to the plate's storage.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            int playerStart = this.storageSlots + 1;
            int storageEnd = this.storageSlots;
            if (index < playerStart) {
                if (!this.moveItemStackTo(stack, playerStart, playerStart + PLAYER_SLOTS, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, 0, storageEnd, false)) {
                return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stack.getCount() == result.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stack);
        }
        return result;
    }
}
