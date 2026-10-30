package com.sidedrain.client;

import com.sidedrain.block.entity.SideDrainBlockEntity;
import com.sidedrain.menu.PlateLayout;
import com.sidedrain.menu.SideDrainMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Container screen for the Side Drain Plate.
 *
 * <h2>Two plates, one screen</h2>
 *
 * <p>Every coordinate comes from {@link PlateLayout}, which the menu picks from the plate's real
 * sizes. The plain plate keeps the layout it always had; the expanded plate moves its 6 x 6 filter
 * grid into a second column so the panel stays 336 pixels tall instead of 464, which would push the
 * player inventory off the bottom of a 1080p window at GUI scale 3.
 *
 * <h2>Nothing overlaps, by construction</h2>
 *
 * <p>Each piece of text owns a band of its own and no band is reused by two things that can be on
 * screen at once. The one shared band is the contextual row, and the two things that can occupy it -
 * the target-slot readout and the filter key hint - belong to mutually exclusive strategies, so only
 * one of them is ever drawn.
 *
 * <h2>The filter grid is drawn, not slotted</h2>
 *
 * <p>Filter cells record an item instead of holding one. They are real slots so a click carries an
 * index, but they are drawn here as a framed grid with a dimmed ghost of the recorded item, which is
 * what tells the player at a glance that these are pins and not storage.
 */
@OnlyIn(Dist.CLIENT)
public class SideDrainScreen extends AbstractContainerScreen<SideDrainMenu> {
    private static final int SLOT_CELL = 18;

    private static final int PANEL_LIGHT = 0xFFC6C6C6;
    private static final int PANEL_DARK = 0xFF8B8B8B;
    private static final int SLOT_FILL = 0xFF8B8B8B;
    private static final int SLOT_SHADOW = 0xFF373737;
    private static final int SLOT_HIGHLIGHT = 0xFFFFFFFF;
    private static final int SLOT_OUTLINE = 0xFF2B2B2B;
    private static final int WEAPON_FRAME = 0xFF6C3A1E;
    private static final int FILTER_FRAME = 0xFF3C6C3C;
    private static final int TARGET_TEXT = 0xFF6A3A00;
    private static final int HINT_TEXT = 0xFF1E4A1E;

    private Button modeButton;
    private Button strategyButton;
    private Button amountButton;
    private Button powerButton;
    private Button rangeButton;
    private Button weaponModeButton;
    private Button targetPrevButton;
    private Button targetNextButton;
    private Button activationButton;
    private Button clearFilterButton;
    private Button meleePlayersButton;
    private Button meleeMonstersButton;
    private Button meleeFriendliesButton;
    private Button meleeNeutralsButton;
    private Button rangePrevButton;
    private Button rangeNextButton;
    private Button digPrevButton;
    private Button digNextButton;

    public SideDrainScreen(SideDrainMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        PlateLayout layout = menu.layout();
        this.imageWidth = layout.width;
        this.imageHeight = layout.height;
        this.inventoryLabelY = layout.inventoryLabelY;
        this.inventoryLabelX = layout.inventoryLabelX;
    }

    private PlateLayout layout() {
        return this.menu.layout();
    }

    @Override
    protected void init() {
        super.init();
        PlateLayout layout = this.layout();
        int left = this.leftPos;
        int top = this.topPos;
        int leftColumn = left + layout.storageX;
        int rightColumn = left + layout.buttonRightX();
        int bw = PlateLayout.BUTTON_WIDTH;
        int bh = PlateLayout.BUTTON_HEIGHT;
        this.modeButton = this.addRenderableWidget(Button.builder(modeLabel(), button -> this.sendButton(0))
                .bounds(leftColumn, top + layout.buttonRow1, bw, bh).build());
        this.strategyButton = this.addRenderableWidget(Button.builder(strategyLabel(), button -> this.sendButton(1))
                .bounds(rightColumn, top + layout.buttonRow1, bw, bh).build());
        this.amountButton = this.addRenderableWidget(Button.builder(amountLabel(), button -> this.sendButton(4))
                .bounds(leftColumn, top + layout.buttonRow2, bw, bh).build());
        this.powerButton = this.addRenderableWidget(Button.builder(powerLabel(), button -> this.sendButton(5))
                .bounds(rightColumn, top + layout.buttonRow2, bw, bh).build());
        this.rangeButton = this.addRenderableWidget(Button.builder(rangeLabel(), button -> this.sendButton(6))
                .bounds(leftColumn, top + layout.buttonRow3, bw, bh).build());
        this.weaponModeButton = this.addRenderableWidget(Button.builder(weaponModeLabel(), button -> this.sendButton(7))
                .bounds(rightColumn, top + layout.buttonRow3, bw, bh).build());
        // Row four: the activation switch, and the clear-filter button beside it. The clear button is
        // hidden unless the filter grid is actually on screen and has pins in it, but its slot in the
        // row is reserved either way, so the activation switch never moves under the pointer.
        this.activationButton = this.addRenderableWidget(Button.builder(activationLabel(),
                        button -> this.sendButton(SideDrainMenu.BUTTON_ACTIVATION))
                .bounds(leftColumn, top + layout.buttonRow4, bw, bh).build());
        this.clearFilterButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.sidedrain.filter.clear"),
                        button -> this.sendButton(SideDrainMenu.BUTTON_CLEAR_FILTER))
                .bounds(rightColumn, top + layout.buttonRow4, bw, bh).build());
        this.targetPrevButton = this.addRenderableWidget(Button.builder(Component.literal("<"), button -> this.sendButton(3))
                .bounds(leftColumn, top + layout.contextualRowY, PlateLayout.STEPPER_WIDTH, bh).build());
        this.targetNextButton = this.addRenderableWidget(Button.builder(Component.literal(">"), button -> this.sendButton(2))
                .bounds(leftColumn + PlateLayout.STEPPER_WIDTH + 2, top + layout.contextualRowY,
                        PlateLayout.STEPPER_WIDTH, bh).build());
        int tagW = PlateLayout.TAG_WIDTH;
        this.meleePlayersButton = this.addRenderableWidget(Button.builder(meleeLabel(0), b -> this.sendButton(8))
                .bounds(leftColumn, top + layout.tagRowY, tagW, bh).build());
        this.meleeMonstersButton = this.addRenderableWidget(Button.builder(meleeLabel(1), b -> this.sendButton(9))
                .bounds(leftColumn + tagW + 3, top + layout.tagRowY, tagW, bh).build());
        this.meleeFriendliesButton = this.addRenderableWidget(Button.builder(meleeLabel(2), b -> this.sendButton(10))
                .bounds(leftColumn + 2 * (tagW + 3), top + layout.tagRowY, tagW, bh).build());
        this.meleeNeutralsButton = this.addRenderableWidget(Button.builder(meleeLabel(3), b -> this.sendButton(11))
                .bounds(leftColumn + 3 * (tagW + 3), top + layout.tagRowY, tagW, bh).build());
        // Row five: the forward scan range. Two steppers and a readout, laid out exactly like the
        // target-slot row above it so the panel reads as one column of controls.
        this.rangePrevButton = this.addRenderableWidget(Button.builder(Component.literal("<"),
                        button -> this.sendButton(SideDrainMenu.BUTTON_RANGE_DOWN))
                .bounds(leftColumn, top + layout.rangeRowY, PlateLayout.STEPPER_WIDTH, bh).build());
        this.rangeNextButton = this.addRenderableWidget(Button.builder(Component.literal(">"),
                        button -> this.sendButton(SideDrainMenu.BUTTON_RANGE_UP))
                .bounds(leftColumn + PlateLayout.STEPPER_WIDTH + 2, top + layout.rangeRowY,
                        PlateLayout.STEPPER_WIDTH, bh).build());
        // The dig-face switch shares the tag row with the four melee switches. The two are mutually
        // exclusive - a weapon is either melee or a tool, never both - so the same band can carry
        // either without ever drawing both.
        int stepW = PlateLayout.STEPPER_WIDTH;
        this.digPrevButton = this.addRenderableWidget(Button.builder(Component.literal("<"),
                        b -> this.sendButton(SideDrainMenu.BUTTON_DIG_PREV))
                .bounds(leftColumn, top + layout.tagRowY, stepW, bh).build());
        this.digNextButton = this.addRenderableWidget(Button.builder(Component.literal(">"),
                        b -> this.sendButton(SideDrainMenu.BUTTON_DIG_NEXT))
                .bounds(leftColumn + stepW + 2, top + layout.tagRowY, stepW, bh).build());
        this.updateWidgetVisibility();
    }

    private void updateWidgetVisibility() {
        boolean slotMode = this.menu.getStrategy() == SideDrainBlockEntity.STRATEGY_SLOT;
        boolean filterMode = this.menu.showsFilter();
        if (this.targetPrevButton != null) {
            this.targetPrevButton.visible = slotMode;
        }
        if (this.targetNextButton != null) {
            this.targetNextButton.visible = slotMode;
        }
        // The clear button only appears when there is something to clear, so it can never invite a
        // click that does nothing - but the activation switch keeps its own slot on the row, so the
        // two never swap places.
        if (this.clearFilterButton != null) {
            this.clearFilterButton.visible = filterMode && this.menu.hasFilterPins();
        }
        // The four melee switches are shown only for a weapon that actually attacks creatures.
        //
        // Ranged weapons cannot honour them - an arrow hits whatever it hits, and the plate has no way
        // to ask it to spare the player's cows - and tools do not attack at all. Showing four
        // switches that provably do nothing was the "these buttons are broken" report, so the
        // classifier decides: a melee weapon gets the switches, everything else gets nothing.
        boolean tagsVisible = this.menu.showsTargetTags();
        if (this.meleePlayersButton != null) {
            this.meleePlayersButton.visible = tagsVisible;
        }
        if (this.meleeMonstersButton != null) {
            this.meleeMonstersButton.visible = tagsVisible;
        }
        if (this.meleeFriendliesButton != null) {
            this.meleeFriendliesButton.visible = tagsVisible;
        }
        if (this.meleeNeutralsButton != null) {
            this.meleeNeutralsButton.visible = tagsVisible;
        }
        // The dig switch takes the same band, and appears for any tool.
        //
        // Deliberately NOT gated on the weapon switch being on: a player who put a pickaxe in the
        // slot and has not yet flipped "use weapon" still needs to see and set the dig face. Hiding
        // the controls until the switch is on made the panel look like it had not recognised the
        // tool at all.
        boolean digVisible = this.menu.isTool();
        if (this.digPrevButton != null) {
            this.digPrevButton.visible = digVisible;
        }
        if (this.digNextButton != null) {
            this.digNextButton.visible = digVisible;
        }
        // The forward scan range is shown only for a weapon that needs a target: a gun aimed at empty
        // air fires fine, but a spell whose own logic insists on a target is refused outright, and
        // the range is how the player tells the plate where to look. Gating it keeps the panel from
        // offering a control that provably does nothing.
        boolean rangeVisible = this.menu.showsTargetRange();
        if (this.rangePrevButton != null) {
            this.rangePrevButton.visible = rangeVisible;
        }
        if (this.rangeNextButton != null) {
            this.rangeNextButton.visible = rangeVisible;
        }
    }

    /** The forward scan range readout, shown beside its two stepper buttons. */
    private Component targetRangeLabel() {
        return Component.translatable("gui.sidedrain.target_range").append(": ")
                .append(Component.literal(this.menu.getTargetRange() + " m"));
    }

    private Component meleeLabel(int which) {
        boolean on = switch (which) {
            case 0 -> (this.menu.getMeleeTagBits() & 1) != 0;
            case 1 -> (this.menu.getMeleeTagBits() & 2) != 0;
            case 2 -> (this.menu.getMeleeTagBits() & 4) != 0;
            default -> (this.menu.getMeleeTagBits() & 8) != 0;
        };
        String key = switch (which) {
            case 0 -> "gui.sidedrain.melee.players";
            case 1 -> "gui.sidedrain.melee.monsters";
            case 2 -> "gui.sidedrain.melee.friendly";
            default -> "gui.sidedrain.melee.neutral";
        };
        return Component.translatable(key).append(": ")
                .append(Component.translatable(on ? "gui.sidedrain.melee.on" : "gui.sidedrain.melee.off"));
    }

    private void sendButton(int id) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
        }
    }

    private Component modeLabel() {
        return Component.translatable("gui.sidedrain.mode").append(": ")
                .append(Component.translatable("gui.sidedrain.mode."
                        + Math.floorMod(this.menu.getMode(), SideDrainBlockEntity.MODE_COUNT)));
    }

    private Component strategyLabel() {
        return Component.translatable("gui.sidedrain.strategy").append(": ")
                .append(Component.translatable("gui.sidedrain.strategy."
                        + Math.floorMod(this.menu.getStrategy(), SideDrainBlockEntity.STRATEGY_COUNT)));
    }

    private Component amountLabel() {
        return Component.translatable("gui.sidedrain.amount").append(": ")
                .append(Component.translatable("gui.sidedrain.amount."
                        + Math.floorMod(this.menu.getAmount(), SideDrainBlockEntity.AMOUNT_COUNT)));
    }

    private Component powerLabel() {
        // The enabled switch shares this button with the status line, so the label has to stay short
        // enough for the 78px button: "已激活" or "已关闭" alone fits, and the launcher mode gets its
        // own readout drawn under the weapon slot instead of being crammed in here.
        return Component.translatable(this.menu.isEnabled() ? "gui.sidedrain.on" : "gui.sidedrain.off");
    }

    /** The activation switch: continuous, or one transfer per redstone edge. */
    private Component activationLabel() {
        return Component.translatable("gui.sidedrain.activation").append(": ")
                .append(Component.translatable(this.menu.isRedstoneActivation()
                        ? "gui.sidedrain.activation.redstone" : "gui.sidedrain.activation.continuous"));
    }

    /**
     * One line naming the active launcher body, drawn under the weapon slot.
     *
     * <p>The dispenser used to be invisible in the panel: a plate with one and a plate without one
     * looked the same, and the only way to find out whether the plate was in launcher mode was to
     * watch what came out of it. Naming the mode, the count and the effect makes the slot's whole
     * point readable at a glance.
     */
    /**
     * What the plate thinks is in its weapon slot, spelled out.
     *
     * <p>This replaces the old fixed "普通投放" line, which said the same thing whether the slot held
     * a sword, a pickaxe or a dispenser. Now the line names the family the classifier picked, so a
     * player can tell at a glance whether their modded weapon was recognised - and if it was not, the
     * line says so instead of pretending.
     */
    private Component weaponDescription() {
        Component base = Component.translatable("gui.sidedrain.weapon.kind").append(": ")
                .append(Component.translatable(this.menu.getWeaponKind().key));
        // For a launcher, add what it needs. Without this, "the staff does nothing" and "the staff
        // needs a book I do not have" look identical, and the player has no way to tell which.
        if (this.menu.isRanged()) {
            Component ammo = switch (this.menu.getAmmoRequirement()) {
                case NONE -> Component.translatable("gui.sidedrain.ammo.none");
                case CONSUMES -> Component.translatable("gui.sidedrain.ammo.consumes");
                case REQUIRES -> Component.translatable("gui.sidedrain.ammo.requires");
            };
            return base.copy().append("  ").append(ammo);
        }
        return base;
    }

    /** The weapon description is coloured by confidence: gold for a named family, grey for a guess. */
    private int weaponDescriptionColour() {
        com.sidedrain.util.WeaponKind kind = this.menu.getWeaponKind();
        return switch (kind) {
            case EMPTY -> 0x555555;
            case UNKNOWN -> 0x8A2B00;
            case OTHER -> 0x6A3A00;
            default -> kind.isTool() ? 0x1E4A1E : 0x6C3A1E;
        };
    }

    /**
     * The dig-face readout, drawn on the tag row beside its two steppers.
     *
     * <p>Only a tool has a face to dig, and only a tool shows this, so it can never collide with the
     * four melee switches that share the band.
     */
    private Component digLabel() {
        return Component.translatable("gui.sidedrain.dig")
                .append(": ")
                .append(Component.translatable("gui.sidedrain.side."
                        + this.menu.getDigDirection().getSerializedName()));
    }

    private Component dispenserLabel() {
        int power = this.menu.getDispenserPower();
        if (power <= 0) {
            return Component.translatable("gui.sidedrain.dispenser.none");
        }
        return Component.translatable("gui.sidedrain.dispenser.active", power);
    }

    private Component rangeLabel() {
        int range = this.menu.getRange();
        return Component.translatable("gui.sidedrain.range").append(": ")
                .append(range <= 0
                        ? Component.translatable("gui.sidedrain.range.self")
                        : Component.translatable("gui.sidedrain.range.value", range));
    }

    private Component weaponModeLabel() {
        return Component.translatable("gui.sidedrain.weapon_mode").append(": ")
                .append(Component.translatable(this.menu.isUseWeapon()
                        ? "gui.sidedrain.weapon_mode.on" : "gui.sidedrain.weapon_mode.off"));
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        PlateLayout layout = this.layout();
        graphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + this.imageHeight, PANEL_LIGHT);
        graphics.fill(this.leftPos + 3, this.topPos + 3, this.leftPos + this.imageWidth - 3,
                this.topPos + this.imageHeight - 3, PANEL_DARK);

        drawSlots(graphics, layout.storageX, layout.storageY, layout.storageCols, layout.storageRows);
        if (this.menu.showsFilter()) {
            // Only the cells this plate really has are drawn: the plain plate is 3 x 3 and the
            // expanded one 6 x 6, and the layout already carries both.
            int cells = this.menu.getFilterCount();
            for (int i = 0; i < cells; i++) {
                int col = i % layout.filterCols;
                int row = i / layout.filterCols;
                drawSlot(graphics, layout.filterX + col * SLOT_CELL - 1,
                        layout.filterY + row * SLOT_CELL - 1);
            }
            drawOutline(graphics, layout.filterX - 3, layout.filterY - 3,
                    layout.filterPixelWidth() + 2, layout.filterPixelHeight() + 2, FILTER_FRAME);
        }
        drawWeaponSlot(graphics, layout.weaponX, layout.weaponY);
        drawSlots(graphics, layout.playerX, layout.playerY, 9, 3);
        drawSlots(graphics, layout.playerX, layout.hotbarY, 9, 1);
    }

    private void drawWeaponSlot(GuiGraphics graphics, int x, int y) {
        drawSlot(graphics, x - 1, y - 1);
        drawOutline(graphics, x - 3, y - 3, SLOT_CELL + 4, SLOT_CELL + 4, WEAPON_FRAME);
    }

    private void drawOutline(GuiGraphics graphics, int x, int y, int w, int h, int color) {
        int left = this.leftPos + x;
        int top = this.topPos + y;
        graphics.fill(left, top, left + w, top + 1, color);
        graphics.fill(left, top + h - 1, left + w, top + h, color);
        graphics.fill(left, top, left + 1, top + h, color);
        graphics.fill(left + w - 1, top, left + w, top + h, color);
    }

    private void drawSlots(GuiGraphics graphics, int x, int y, int columns, int rows) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                drawSlot(graphics, x + col * SLOT_CELL - 1, y + row * SLOT_CELL - 1);
            }
        }
    }

    private void drawSlot(GuiGraphics graphics, int x, int y) {
        int left = this.leftPos + x;
        int top = this.topPos + y;
        int right = left + SLOT_CELL;
        int bottom = top + SLOT_CELL;
        graphics.fill(left, top, right, top + 1, SLOT_OUTLINE);
        graphics.fill(left, bottom - 1, right, bottom, SLOT_OUTLINE);
        graphics.fill(left, top, left + 1, bottom, SLOT_OUTLINE);
        graphics.fill(right - 1, top, right, bottom, SLOT_OUTLINE);
        graphics.fill(left + 1, top + 1, right - 1, top + 2, SLOT_SHADOW);
        graphics.fill(left + 1, top + 1, left + 2, bottom - 1, SLOT_SHADOW);
        graphics.fill(left + 2, bottom - 2, right - 1, bottom - 1, SLOT_HIGHLIGHT);
        graphics.fill(right - 2, top + 2, right - 1, bottom - 1, SLOT_HIGHLIGHT);
        graphics.fill(left + 2, top + 2, right - 2, bottom - 2, SLOT_FILL);
    }

    /**
     * Draws every filter pin as a dimmed ghost.
     *
     * <p>The tint is the whole point: a filter cell that looked like an ordinary slot read as
     * storage, and players tried to put items in it. A pin is drawn at the same size as an item but
     * washed out, so "recorded" and "stored" are visually different things.
     */
    private void renderFilterPins(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!this.menu.showsFilter()) {
            return;
        }
        PlateLayout layout = this.layout();
        int hoveredCell = this.menu.filterCellAt(mouseX - this.leftPos, mouseY - this.topPos);
        for (int i = 0; i < this.menu.getFilterCount(); i++) {
            int col = i % layout.filterCols;
            int row = i / layout.filterCols;
            int x = this.leftPos + layout.filterX + col * SLOT_CELL;
            int y = this.topPos + layout.filterY + row * SLOT_CELL;
            if (i == hoveredCell) {
                // The cells are not slots, so the vanilla highlight never runs for them. Drawing it
                // here is what tells the player the cell is clickable at all.
                graphics.fill(x - 1, y - 1, x + 17, y + 17, 0x80FFFFFF);
            }
            ItemStack pinned = this.menu.getFilterCell(i);
            if (pinned.isEmpty()) {
                continue;
            }
            graphics.renderItem(pinned, x, y);
            graphics.renderItemDecorations(this.font, pinned, x, y);
            // Wash the pin out so it reads as a record, not as an item you could take.
            graphics.fill(x, y, x + 16, y + 16, 0x66C6C6C6);
        }
    }

    /**
     * Draws a string, shortening it with an ellipsis when it would run past the panel.
     *
     * <p>Translation is not a fixed-width affair: "Press C to pin the item" is nearly twice as wide as
     * its Chinese counterpart, and a pack running a third language can be wider still. Rather than
     * tuning every string until it happens to fit the narrow panel, the text is clipped to the space
     * that exists. That keeps every label inside the window no matter which language is loaded.
     *
     * @param maxX panel-relative x the text must not pass
     */
    private void drawClipped(GuiGraphics graphics, Component text, int x, int y, int colour, int maxX) {
        String raw = text.getString();
        int available = maxX - x;
        if (this.font.width(raw) <= available) {
            graphics.drawString(this.font, raw, x, y, colour, false);
            return;
        }
        String ellipsis = "...";
        int room = available - this.font.width(ellipsis);
        if (room <= 0) {
            return;
        }
        String clipped = this.font.plainSubstrByWidth(raw, room) + ellipsis;
        graphics.drawString(this.font, clipped, x, y, colour, false);
    }

    /**
     * Draws the text, one band per line.
     *
     * <p>Every caption has its own row, taken from the layout. The title owns the top band, the
     * filter caption sits above the grid, the weapon caption above the weapon slot, and the target
     * readout and the filter key hint share the contextual row because they belong to two mutually
     * exclusive strategies.
     */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        PlateLayout layout = this.layout();
        graphics.drawString(this.font, this.title, this.titleLabelX, layout.titleY, 0x404040, false);

        if (this.menu.showsFilter()) {
            graphics.drawString(this.font, Component.translatable("gui.sidedrain.filter"),
                    layout.filterX, layout.filterCaptionY, HINT_TEXT, false);
        }
        // The weapon caption is short and sits above its slot, clear of the filter caption.
        graphics.drawString(this.font, Component.translatable("gui.sidedrain.weapon"),
                layout.weaponX, layout.weaponCaptionY, 0x6C3A1E, false);
        // The launcher readout goes directly under the weapon slot, but only when there is a
        // launcher to report: a tool plate draws the dig face there instead.
        if (!this.menu.isTool()) {
            graphics.drawString(this.font, dispenserLabel(),
                    layout.weaponX, layout.weaponY + 22,
                    this.menu.getDispenserPower() > 0 ? 0x8A2B00 : 0x555555, false);
        }
        // The weapon description: what the plate thinks is in the slot. This is the line the request
        // asked for - "普通投放" becomes "剑", "弓", "镐", "未识别的武器" and so on. Clipped to the
        // panel so a longer translation cannot run off the edge.
        drawClipped(graphics, weaponDescription(), layout.storageX, layout.weaponRowY,
                weaponDescriptionColour(), layout.width - 6);

        if (this.menu.getStrategy() == SideDrainBlockEntity.STRATEGY_SLOT) {
            int size = this.menu.getTargetSize();
            Component target = size <= 0
                    ? Component.translatable("gui.sidedrain.target.none")
                    : Component.translatable("gui.sidedrain.target",
                            this.menu.getTargetSlot() + 1, size);
            graphics.drawString(this.font, target,
                    layout.storageX + PlateLayout.CONTEXT_TEXT_X, layout.contextualRowY + 5,
                    TARGET_TEXT, false);
        } else if (this.menu.showsFilter()) {
            // The filter hint occupies the same row as the target readout would, and the two can
            // never be on screen together: one needs the slot strategy, the other the filter one.
            // The text names the real key binding, so a player who rebinds it sees the new key.
            Component hint = ModKeys.FILTER_ITEM.isUnbound()
                    ? Component.translatable("gui.sidedrain.filter.hint.nokey")
                    : Component.translatable("gui.sidedrain.filter.hint",
                            ModKeys.FILTER_ITEM.getTranslatedKeyMessage());
            drawClipped(graphics, hint, layout.storageX + PlateLayout.CONTEXT_TEXT_X,
                    layout.contextualRowY + 5, HINT_TEXT, layout.width - 6);
        }
        // The dig-face readout sits on the tag row, to the right of its two steppers. It shares the
        // band with the four melee switches, and only ever appears when those are hidden.
        if (this.menu.isTool()) {
            drawClipped(graphics, digLabel(),
                    layout.storageX + 2 * PlateLayout.STEPPER_WIDTH + 8,
                    layout.tagRowY + 5, 0x1E4A1E, layout.width - 6);
        }
        // The scan range readout: drawn beside its two steppers, in the same style as the dig label
        // above. It used to be written INTO the ">" button, which is 16 pixels wide, so the text was
        // squeezed into an unreadable blob.
        if (this.menu.showsTargetRange()) {
            drawClipped(graphics, targetRangeLabel(),
                    layout.storageX + 2 * PlateLayout.STEPPER_WIDTH + 8,
                    layout.rangeRowY + 5, 0x2A4A6A, layout.width - 6);
        }

        graphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX,
                layout.inventoryLabelY, 0x404040, false);
    }

    /**
     * Filter cells are clicked by coordinate, not by slot.
     *
     * <p>They are not slots at all: a cell records an item, and anything vanilla can click is
     * something vanilla can hand back to the player. The click is therefore caught here, before the
     * screen's parent turns it into a slot click, and reported to the server as a cell index.
     *
     * <p>Right click is deliberately not handled: the cell gesture has no secondary form, and
     * letting it fall through means the standard "split a stack" behaviour still works everywhere
     * else on the panel.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && this.menu.showsFilter()) {
            int cell = this.menu.filterCellAt(mouseX - this.leftPos, mouseY - this.topPos);
            if (cell >= 0) {
                com.sidedrain.network.ModNetwork.sendToServer(
                        com.sidedrain.network.FilterPinPacket.click(this.menu.getPos(), cell));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Where the pointer was on the last frame, which is the only place a key press can learn it. */
    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;

    /**
     * Pins whatever the pointer is resting on, wherever it is resting.
     *
     * <h2>Sources, in order</h2>
     *
     * <ol>
     *   <li><b>The panel's own slots</b> - storage, weapon, the player inventory and the hotbar. This
     *       is the case that matters most in practice: the player is looking at their own backpack.
     *       Resolved by hit-testing the pointer against the slot rectangles directly, not by reading
     *       {@code hoveredSlot}: that field is only refreshed while {@code render} runs, so a press
     *       arriving between two frames - which is exactly when a key press arrives - could read a
     *       stale slot, or {@code null} on the very first frame after opening the panel.</li>
     *   <li><b>A recipe viewer</b> - JEI's ingredient list, JEI's bookmarks, or EMI's sidebar, via
     *       {@link RecipeViewerBridge}.</li>
     * </ol>
     *
     * <p>The press is consumed only when something was actually pinned, so a player who rebound the
     * key keeps it working everywhere else, and a press that finds nothing does not silently eat the
     * key. The press is also drained from the queue, so a single press cannot fire twice.
     *
     * <p><b>The filter strategy is not required.</b> Pinning an item is a configuration gesture, not
     * a mode-specific action: a player who points at a chest full of iron and presses the key expects
     * the iron to be recorded, whether or not they have switched the strategy yet. Requiring the
     * strategy first meant the key silently did nothing in every other mode, which reads exactly like
     * "the key is broken".
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ModKeys.FILTER_ITEM.matches(keyCode, scanCode)) {
            ItemStack hovered = this.itemUnderPointer();
            if (!hovered.isEmpty()) {
                com.sidedrain.network.ModNetwork.sendToServer(
                        com.sidedrain.network.FilterPinPacket.fromViewer(this.menu.getPos(), hovered));
                ModKeys.drain(ModKeys.FILTER_ITEM);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * The item the pointer is on, from the panel first and a recipe viewer second.
     *
     * <p>Order matters: the panel's own slots are drawn on top of the viewer's list, so when both
     * could answer, the one the player is actually pointing at must win.
     */
    private ItemStack itemUnderPointer() {
        // 1. A real slot of this panel. Hit-tested from the pointer rather than read from
        //    hoveredSlot, because that field is a render-time cache and a key press can arrive
        //    between two frames.
        ItemStack slotItem = this.itemUnderPointerInSlots();
        if (!slotItem.isEmpty()) {
            return slotItem;
        }
        // 2. A filter cell that already records something: pressing the key on a pin is the same
        //    gesture as clicking it, so it is left alone rather than re-recording the same item.
        // 3. A recipe viewer's list, bookmarks or sidebar.
        return RecipeViewerBridge.itemUnderPointer(this, this.lastMouseX, this.lastMouseY);
    }

    /**
     * The item in whichever of this panel's slots the pointer is over, or {@code EMPTY}.
     *
     * <p>Slots are laid out in panel space, so the pointer is converted into the same space and each
     * slot's own rectangle is tested. Only active slots are considered: the filter cells are inactive
     * by design, and their contents are not items the player is holding.
     */
    private ItemStack itemUnderPointerInSlots() {
        if (Double.isNaN(this.lastMouseX) || Double.isNaN(this.lastMouseY)) {
            return ItemStack.EMPTY;
        }
        double relativeX = this.lastMouseX - this.leftPos;
        double relativeY = this.lastMouseY - this.topPos;
        for (Slot slot : this.menu.slots) {
            if (!slot.isActive() || !slot.hasItem()) {
                continue;
            }
            if (relativeX >= slot.x - 1 && relativeX < slot.x + 17
                    && relativeY >= slot.y - 1 && relativeY < slot.y + 17) {
                return slot.getItem().copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.modeButton != null) {
            this.modeButton.setMessage(modeLabel());
        }
        if (this.strategyButton != null) {
            this.strategyButton.setMessage(strategyLabel());
        }
        if (this.amountButton != null) {
            this.amountButton.setMessage(amountLabel());
        }
        if (this.powerButton != null) {
            this.powerButton.setMessage(powerLabel());
        }
        if (this.rangeButton != null) {
            this.rangeButton.setMessage(rangeLabel());
        }
        if (this.weaponModeButton != null) {
            this.weaponModeButton.setMessage(weaponModeLabel());
        }
        if (this.activationButton != null) {
            this.activationButton.setMessage(activationLabel());
        }
        if (this.meleePlayersButton != null) {
            this.meleePlayersButton.setMessage(meleeLabel(0));
        }
        if (this.meleeMonstersButton != null) {
            this.meleeMonstersButton.setMessage(meleeLabel(1));
        }
        if (this.meleeFriendliesButton != null) {
            this.meleeFriendliesButton.setMessage(meleeLabel(2));
        }
        if (this.meleeNeutralsButton != null) {
            this.meleeNeutralsButton.setMessage(meleeLabel(3));
        }
        // The steppers keep their own "<" and ">" labels: the readout is drawn as text beside them.
        // Writing the whole readout into a 16 pixel button is what made the range text unreadable.
        this.updateWidgetVisibility();
        // Recorded before super.render so the value is already current when a key press arrives
        // during the same frame, and so the hovered-slot test and the viewer test agree on where
        // the pointer is.
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderFilterPins(graphics, mouseX, mouseY);
        // A pinned cell has no slot, so vanilla never draws its tooltip; naming the item is what
        // makes a washed-out ghost readable.
        if (this.menu.showsFilter() && this.minecraft != null) {
            int cell = this.menu.filterCellAt(mouseX - this.leftPos, mouseY - this.topPos);
            if (cell >= 0) {
                ItemStack pinned = this.menu.getFilterCell(cell);
                if (!pinned.isEmpty()) {
                    graphics.renderTooltip(this.font, pinned, mouseX, mouseY);
                }
            }
        }
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
