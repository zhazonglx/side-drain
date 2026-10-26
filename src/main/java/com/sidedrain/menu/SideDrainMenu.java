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
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Container menu for the Side Drain Plate.
 *
 * <p>Layout, top to bottom and never overlapping:
 * <pre>
 *   y  18   27 storage slots (3 x 9)
 *   y 108   mode / strategy buttons
 *   y 130   amount / power buttons
 *   y 152   3x3 filter grid (filter strategy only)   weapon slot at x 78
 *   y 216   player inventory (3 x 9)
 *   y 274   hotbar
 * </pre>
 *
 * <p>The target slot of the Slot strategy is a slot of the <em>attached container</em>, not of the
 * plate's own storage, so the plate reports how many slots that container has and the screen lets the
 * player pick one of those.
 */
public class SideDrainMenu extends AbstractContainerMenu {
    public static final int STORAGE_SLOTS = SideDrainBlockEntity.SLOT_COUNT;
    public static final int FILTER_SLOTS = SideDrainBlockEntity.FILTER_COUNT;
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
    public static final int DATA_COUNT = 9;

    /**
     * Pixel layout, mirrored by the screen.
     *
     * <p>Every band is separated by a caption line so no two pieces of text can collide: the title
     * owns y 5, storage starts at 20, each button row has its own 22 pixel band, the filter and
     * weapon captions sit on their own line at 142 with the slots below them at 156.
     */
    public static final int TITLE_Y = 5;
    public static final int STORAGE_Y = 20;
    public static final int BUTTON_ROW_1 = 96;
    public static final int BUTTON_ROW_2 = 118;
    public static final int BUTTON_ROW_3 = 140;
    /** The target-slot readout lives on its own row, never over the captions or the slots. */
    public static final int TARGET_ROW_Y = 160;
    public static final int CAPTION_Y = 170;
    public static final int FILTER_X = 8;
    public static final int FILTER_Y = 182;
    public static final int WEAPON_X = 78;
    public static final int WEAPON_Y = 182;
    /** The filter grid ends at 160 + 54 = 214, so the inventory starts well clear of it. */
    public static final int PLAYER_Y = 254;
    public static final int HOTBAR_Y = 312;
    public static final int IMAGE_HEIGHT = 334;

    private static final int STORAGE_START = 0;
    private static final int FILTER_START = STORAGE_START + STORAGE_SLOTS;
    private static final int WEAPON_INDEX = FILTER_START + FILTER_SLOTS;
    private static final int PLAYER_START = WEAPON_INDEX + 1;

    private final Container container;
    private final Container filter;
    private final Container weapon;
    private final ContainerData data;
    private final BlockPos pos;

    /** Server-side constructor. */
    public SideDrainMenu(int id, Inventory inventory, SideDrainBlockEntity blockEntity) {
        this(id, inventory, blockEntity, blockEntity.getFilterView(), blockEntity.getWeaponView(),
                blockEntity.getBlockPos(), blockEntity.getData());
    }

    /** Client-side constructor used by the menu type. */
    public SideDrainMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        this(id, inventory, new SimpleContainer(STORAGE_SLOTS), new SimpleContainer(FILTER_SLOTS),
                new SimpleContainer(1), buffer.readBlockPos(), new SimpleContainerData(DATA_COUNT));
    }

    private SideDrainMenu(int id, Inventory inventory, Container container, Container filter, Container weapon,
                          BlockPos pos, ContainerData data) {
        super(ModMenus.SIDE_DRAIN.get(), id);
        checkContainerSize(container, STORAGE_SLOTS);
        checkContainerSize(filter, FILTER_SLOTS);
        checkContainerSize(weapon, 1);
        checkContainerDataCount(data, DATA_COUNT);
        this.container = container;
        this.filter = filter;
        this.weapon = weapon;
        this.pos = pos;
        this.data = data;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(container, col + row * 9, 8 + col * 18, STORAGE_Y + row * 18));
            }
        }
        // 3x3 filter grid, only clickable while the filter strategy is chosen.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                this.addSlot(new Slot(filter, col + row * 3, FILTER_X + col * 18, FILTER_Y + row * 18) {
                    @Override
                    public boolean isActive() {
                        return SideDrainMenu.this.showsFilter();
                    }
                });
            }
        }
        this.addSlot(new Slot(weapon, 0, WEAPON_X, WEAPON_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                // Both ranged launchers and melee weapons are accepted: the plate either shoots or
                // swings, depending on what is loaded.
                return com.sidedrain.util.ProjectileLauncher.isLauncher(stack)
                        || com.sidedrain.util.MeleeAttacker.isMelee(stack);
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, PLAYER_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, 8 + col * 18, HOTBAR_Y));
        }
        this.addDataSlots(data);
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public ContainerData getData() {
        return this.data;
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

    /** True while the filter strategy is selected, which is when the filter grid is usable. */
    public boolean showsFilter() {
        return this.getStrategy() == SideDrainBlockEntity.STRATEGY_FILTER;
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

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < PLAYER_START) {
                if (!this.moveItemStackTo(stack, PLAYER_START, PLAYER_START + PLAYER_SLOTS, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, STORAGE_START, FILTER_START, false)) {
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
