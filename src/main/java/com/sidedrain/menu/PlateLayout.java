package com.sidedrain.menu;

import com.sidedrain.block.entity.SideDrainBlockEntity;

/**
 * Pixel geometry of the plate panel, per plate size.
 *
 * <h2>Why this is a value and not constants</h2>
 *
 * <p>There are two plates and they are not the same shape on screen: a plain plate is 27 storage
 * slots and a 3 x 3 filter, an expanded plate is 54 and 6 x 6. Stacking the bigger grids in the same
 * column would make the panel 464 pixels tall, which is taller than the 360 virtual pixels a 1080p
 * screen offers at GUI scale 3. The expanded panel therefore puts the weapon slot and the filter grid
 * in a <b>second column</b>, which keeps it no taller than the plain one while still never
 * overlapping anything.
 *
 * <p>Every coordinate the screen draws with comes from here, so a slot and the frame around it cannot
 * drift apart and the two plates cannot end up sharing half a layout.
 *
 * <h2>Bands, and why nothing collides</h2>
 *
 * <p>Every element owns a band of its own, top to bottom:
 *
 * <pre>
 *   title
 *   storage grid
 *   button row 1   mode / strategy
 *   button row 2   amount / enabled
 *   button row 3   range / weapon mode
 *   button row 4   activation / clear filter
 *   weapon row     the weapon's own name and its kind   ("剑", "弓", "镐", "未识别的武器")
 *   contextual row prev + next slot buttons, then the target readout OR the filter hint
 *   tag row        the four melee switches, OR the dig-face switch for a tool
 *   caption row    "Filter" and "Weapon"
 *   filter grid    and the weapon slot
 *   player caption
 *   player inventory
 *   hotbar
 * </pre>
 *
 * <p>The one shared band is {@code contextualRowY}: the target readout and the filter hint both use
 * it, and they belong to mutually exclusive strategies - one needs the slot strategy, the other the
 * filter strategy - so only one of them can ever be drawn at a time. Both start at
 * {@link #CONTEXT_TEXT_X}, past the two stepper buttons, so neither can reach them.
 *
 * <p>The tag row is shared in the same way: the four melee switches are shown for a melee weapon, and
 * the dig-face switch replaces them for a tool. The two can never be on screen together because a
 * weapon is either melee or a tool, never both.
 */
public final class PlateLayout {
    /** The plain plate: the panel the mod has always had, with one extra button row. */
    public static final PlateLayout PLAIN = new PlateLayout(
            176, 426, 5,
            8, 20, 9, 3,
            92, 112, 132, 152,
            176, 194, 216, 238,
            264, 8, 276, 3, 3,
            264, 78, 276,
            8, 346, 404, 334, 8);

    /**
     * The expanded plate: weapon and filter move into a second column.
     *
     * <p>Column one is storage (six rows) and the button stack; column two is the weapon slot at the
     * top, then the 6 x 6 filter grid. The player inventory spans the full width underneath both, so
     * the wider panel costs no extra height.
     */
    public static final PlateLayout EXPANDED = new PlateLayout(
            292, 396, 5,
            8, 20, 9, 6,
            132, 152, 172, 192,
            216, 234, 256, 278,
            100, 178, 112, 6, 6,
            54, 178, 66,
            8, 314, 372, 302, 8);

    /** Panel width and height, which the screen also uses as its image size. */
    public final int width;
    public final int height;
    /** Where the plate's own name is drawn. */
    public final int titleY;

    /** Storage grid: origin, columns and rows. */
    public final int storageX;
    public final int storageY;
    public final int storageCols;
    public final int storageRows;

    /** The four button rows: mode/strategy, amount/enabled, range/weapon, activation/clear-filter. */
    public final int buttonRow1;
    public final int buttonRow2;
    public final int buttonRow3;
    public final int buttonRow4;
    /**
     * The weapon description row: the name of the item in the weapon slot and what the plate thinks
     * it is. It has a band of its own so it can never reach the contextual row underneath it.
     */
    public final int weaponRowY;
    /**
     * The shared text row: the target-slot readout (slot strategy) or the filter hint (filter
     * strategy). The prev/next slot buttons sit on the left of it and the text starts to their right.
     */
    public final int contextualRowY;
    /** The four melee switches, or the dig-face switch for a tool. */
    public final int tagRowY;
    /**
     * The forward scan range: how far ahead of the plate a target may be.
     *
     * <p>Only shown for a weapon that needs a target - a staff whose spell insists on one - because
     * that distance is meaningless for a gun happily firing into the air. It sits directly under the
     * tag row, since it is the same question: what may this plate aim at, and how far.
     */
    public final int rangeRowY;

    /** The filter caption, then the grid itself. */
    public final int filterCaptionY;
    public final int filterX;
    public final int filterY;
    public final int filterCols;
    public final int filterRows;

    /** The weapon caption and its slot. */
    public final int weaponCaptionY;
    public final int weaponX;
    public final int weaponY;

    /** Player inventory, hotbar, and the "Inventory" caption above them. */
    public final int playerX;
    public final int playerY;
    public final int hotbarY;
    public final int inventoryLabelY;
    public final int inventoryLabelX;

    /** A standard button, so every control in the panel is the same size. */
    public static final int BUTTON_WIDTH = 78;
    public static final int BUTTON_HEIGHT = 18;
    /** A small stepper button, used for the target slot and the dig face. */
    public static final int STEPPER_WIDTH = 16;
    /** A melee targeting switch. */
    public static final int TAG_WIDTH = 38;
    /** Where the contextual text begins: past both stepper buttons. */
    public static final int CONTEXT_TEXT_X = 46;

    private PlateLayout(int width, int height, int titleY,
                        int storageX, int storageY, int storageCols, int storageRows,
                        int buttonRow1, int buttonRow2, int buttonRow3, int buttonRow4,
                        int weaponRowY, int contextualRowY, int tagRowY, int rangeRowY,
                        int filterCaptionY, int filterX, int filterY, int filterCols, int filterRows,
                        int weaponCaptionY, int weaponX, int weaponY,
                        int playerX, int playerY, int hotbarY,
                        int inventoryLabelY, int inventoryLabelX) {
        this.width = width;
        this.height = height;
        this.titleY = titleY;
        this.storageX = storageX;
        this.storageY = storageY;
        this.storageCols = storageCols;
        this.storageRows = storageRows;
        this.buttonRow1 = buttonRow1;
        this.buttonRow2 = buttonRow2;
        this.buttonRow3 = buttonRow3;
        this.buttonRow4 = buttonRow4;
        this.weaponRowY = weaponRowY;
        this.contextualRowY = contextualRowY;
        this.tagRowY = tagRowY;
        this.rangeRowY = rangeRowY;
        this.filterCaptionY = filterCaptionY;
        this.filterX = filterX;
        this.filterY = filterY;
        this.filterCols = filterCols;
        this.filterRows = filterRows;
        this.weaponCaptionY = weaponCaptionY;
        this.weaponX = weaponX;
        this.weaponY = weaponY;
        this.playerX = playerX;
        this.playerY = playerY;
        this.hotbarY = hotbarY;
        this.inventoryLabelY = inventoryLabelY;
        this.inventoryLabelX = inventoryLabelX;
    }

    /** The layout for a plate with {@code filterSlots} filter cells. */
    public static PlateLayout of(int storageSlots, int filterSlots) {
        return filterSlots > SideDrainBlockEntity.FILTER_COUNT ? EXPANDED : PLAIN;
    }

    /** Height of the filter grid in pixels, so the screen can frame it in one rectangle. */
    public int filterPixelHeight() {
        return this.filterRows * 18;
    }

    /** Width of the filter grid in pixels. */
    public int filterPixelWidth() {
        return this.filterCols * 18;
    }

    /** The x of the right-hand button in a row: the left one starts at {@code storageX}. */
    public int buttonRightX() {
        return this.storageX + BUTTON_WIDTH + 4;
    }
}
