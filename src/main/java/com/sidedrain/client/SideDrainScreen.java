package com.sidedrain.client;

import com.sidedrain.block.entity.SideDrainBlockEntity;
import com.sidedrain.menu.SideDrainMenu;
import com.sidedrain.network.ModNetwork;
import com.sidedrain.network.SetTargetSlotPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Container screen for the Side Drain Plate.
 *
 * <p>Everything is laid out on a fixed vertical rhythm so nothing can overlap: storage, then two
 * button rows, then the optional filter grid and the weapon slot, then the player inventory.
 *
 * <p>The Slot strategy refers to a slot of the <em>attached container</em>. Its index is changed with
 * the two arrow buttons and with Alt + the mouse wheel, and the screen always spells out which slot
 * of how many is selected.
 */
@OnlyIn(Dist.CLIENT)
public class SideDrainScreen extends AbstractContainerScreen<SideDrainMenu> {
    private static final int WIDTH = 176;
    /**
     * The player inventory caption sits directly above the player slots (253).
     *
     * <p>The filter grid is three 18px rows starting at {@code FILTER_Y}, so it occupies
     * {@code FILTER_Y .. FILTER_Y + 54} and its last slot's frame ends at {@code FILTER_Y + 53}
     * (235). The caption therefore has to move down while the grid is on screen, otherwise it is
     * drawn straight through the bottom row of filter slots.
     */
    private static final int INVENTORY_LABEL_Y = 222;
    /** Caption row used instead while the filter grid is visible; clears the grid and the slots. */
    private static final int INVENTORY_LABEL_Y_FILTERED = 240;
    /** Where the plate's own name is drawn, on its own band above the storage grid. */
    private static final int TITLE_Y = SideDrainMenu.TITLE_Y;

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

    private Button modeButton;
    private Button strategyButton;
    private Button amountButton;
    private Button powerButton;
    private Button rangeButton;
    private Button weaponModeButton;
    private Button targetPrevButton;
    private Button targetNextButton;

    public SideDrainScreen(SideDrainMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = WIDTH;
        this.imageHeight = SideDrainMenu.IMAGE_HEIGHT;
        this.inventoryLabelY = inventoryLabelY();
    }

    /** Keeps the player inventory caption clear of the filter grid when it is shown. */
    private int inventoryLabelY() {
        return this.menu.showsFilter() ? INVENTORY_LABEL_Y_FILTERED : INVENTORY_LABEL_Y;
    }

    @Override
    protected void init() {
        super.init();
        this.modeButton = this.addRenderableWidget(Button.builder(modeLabel(), button -> this.sendButton(0))
                .bounds(this.leftPos + 8, this.topPos + SideDrainMenu.BUTTON_ROW_1, 78, 18).build());
        this.strategyButton = this.addRenderableWidget(Button.builder(strategyLabel(), button -> this.sendButton(1))
                .bounds(this.leftPos + 90, this.topPos + SideDrainMenu.BUTTON_ROW_1, 78, 18).build());
        this.amountButton = this.addRenderableWidget(Button.builder(amountLabel(), button -> this.sendButton(4))
                .bounds(this.leftPos + 8, this.topPos + SideDrainMenu.BUTTON_ROW_2, 78, 18).build());
        this.powerButton = this.addRenderableWidget(Button.builder(powerLabel(), button -> this.sendButton(5))
                .bounds(this.leftPos + 90, this.topPos + SideDrainMenu.BUTTON_ROW_2, 78, 18).build());
        this.rangeButton = this.addRenderableWidget(Button.builder(rangeLabel(), button -> this.sendButton(6))
                .bounds(this.leftPos + 8, this.topPos + SideDrainMenu.BUTTON_ROW_3, 78, 18).build());
        this.weaponModeButton = this.addRenderableWidget(Button.builder(weaponModeLabel(), button -> this.sendButton(7))
                .bounds(this.leftPos + 90, this.topPos + SideDrainMenu.BUTTON_ROW_3, 78, 18).build());
        // Target slot steppers, only shown while the Slot strategy is active.
        this.targetPrevButton = this.addRenderableWidget(Button.builder(Component.literal("<"), button -> this.sendButton(3))
                .bounds(this.leftPos + 8, this.topPos + SideDrainMenu.FILTER_Y + 8, 16, 18).build());
        this.targetNextButton = this.addRenderableWidget(Button.builder(Component.literal(">"), button -> this.sendButton(2))
                .bounds(this.leftPos + 26, this.topPos + SideDrainMenu.FILTER_Y + 8, 16, 18).build());
        this.updateWidgetVisibility();
    }

    private void updateWidgetVisibility() {
        boolean slotMode = this.menu.getStrategy() == SideDrainBlockEntity.STRATEGY_SLOT;
        if (this.targetPrevButton != null) {
            this.targetPrevButton.visible = slotMode;
        }
        if (this.targetNextButton != null) {
            this.targetNextButton.visible = slotMode;
        }
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
        return Component.translatable(this.menu.isEnabled() ? "gui.sidedrain.on" : "gui.sidedrain.off");
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
        graphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + this.imageHeight, PANEL_LIGHT);
        graphics.fill(this.leftPos + 3, this.topPos + 3, this.leftPos + this.imageWidth - 3,
                this.topPos + this.imageHeight - 3, PANEL_DARK);

        drawSlots(graphics, 8, SideDrainMenu.STORAGE_Y, 9, 3);
        if (this.menu.showsFilter()) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    drawSlot(graphics, SideDrainMenu.FILTER_X + col * SLOT_CELL - 1,
                            SideDrainMenu.FILTER_Y + row * SLOT_CELL - 1);
                }
            }
            drawOutline(graphics, SideDrainMenu.FILTER_X - 3, SideDrainMenu.FILTER_Y - 3,
                    3 * SLOT_CELL + 2, 3 * SLOT_CELL + 2, FILTER_FRAME);
        }
        drawWeaponSlot(graphics, SideDrainMenu.WEAPON_X, SideDrainMenu.WEAPON_Y);
        drawSlots(graphics, 8, SideDrainMenu.PLAYER_Y, 9, 3);
        drawSlots(graphics, 8, SideDrainMenu.HOTBAR_Y, 9, 1);
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
     * Draws the text, one band per line.
     *
     * <p>The title used to share its row with the first storage slots, and the filter caption used to
     * sit on top of the weapon frame. Each caption now has a band of its own: title at 5, filter and
     * weapon captions at 142, slots from 156, the target readout below the captions.
     */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, TITLE_Y, 0x404040, false);

        if (this.menu.showsFilter()) {
            // "Filter" is left-aligned over the 3x3 grid; the weapon caption starts at x=78, so the
            // two never touch even though they share a row.
            graphics.drawString(this.font, Component.translatable("gui.sidedrain.filter"),
                    SideDrainMenu.FILTER_X, SideDrainMenu.CAPTION_Y, 0x1E4A1E, false);
        }
        // The weapon caption is short and sits above its slot, clear of the filter caption on the left.
        graphics.drawString(this.font, Component.translatable("gui.sidedrain.weapon"),
                SideDrainMenu.WEAPON_X, SideDrainMenu.CAPTION_Y, 0x6C3A1E, false);

        if (this.menu.getStrategy() == SideDrainBlockEntity.STRATEGY_SLOT) {
            // On its own row, full width, so it can never overlap the weapon caption or the slots.
            int size = this.menu.getTargetSize();
            Component target = size <= 0
                    ? Component.translatable("gui.sidedrain.target.none")
                    : Component.translatable("gui.sidedrain.target",
                            this.menu.getTargetSlot() + 1, size);
            graphics.drawString(this.font, target,
                    SideDrainMenu.FILTER_X, SideDrainMenu.TARGET_ROW_Y, TARGET_TEXT, false);
        }

        graphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX,
                inventoryLabelY(), 0x404040, false);
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
        this.updateWidgetVisibility();
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}
