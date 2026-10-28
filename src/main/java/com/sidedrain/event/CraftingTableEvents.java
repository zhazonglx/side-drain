package com.sidedrain.event;

import com.sidedrain.menu.PersistentCraftingMenu;
import com.sidedrain.util.CraftingGridStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Opens the persistent crafting menu on right-click and spills the grid when a table breaks.
 *
 * <h2>Why the menu is replaced at all</h2>
 *
 * <p>Vanilla's {@code CraftingMenu} hands the grid back to the player when the screen closes -
 * {@code removed} runs before any close event can see it, so persisting through a close event can
 * never work. The clean fix is to not use that menu: this handler intercepts the right-click on a
 * crafting table, cancels vanilla's open, and opens {@link PersistentCraftingMenu} instead - a
 * vanilla-compatible menu whose {@code removed} saves the grid into
 * {@link CraftingGridStorage} rather than returning it.
 *
 * <h2>The rule that keeps the table usable</h2>
 *
 * <p>Sneaking with something placeable is never taken over by the menu. That is the gesture players
 * use to bolt a Side Drain Plate onto a container, and cancelling it - which the previous version did
 * for every right-click on a table - made the table refuse every placement and every plate, so it
 * read as a completely dead block. Simply returning is not enough either: vanilla's
 * {@code CraftingTableBlock.use} answers {@code CONSUME} and the item's own {@code useOn} is never
 * reached, so this handler denies only the <b>block's</b> use and lets the item place normally.
 *
 * <p>Only a table whose inventory really is the vanilla 3x3 grid is redirected. A machine that has
 * its own GUI, its own slots and its own recipes keeps them: swapping its menu for a plain crafting
 * grid would delete that functionality, and the container view would then be a phantom inventory
 * disconnected from the modded screen - the "items appear out of nowhere" bug in another form.
 */
public final class CraftingTableEvents {

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        BlockPos pos = event.getPos();
        // Every table is recognised here, not just the plain one. The placement rule below has to
        // hold for a modded table and for a table the wrench has wrapped in a carrier too - the
        // gesture "sneak and put a plate on the table" must not depend on which kind of table it is
        // or on which of its six faces was clicked. Only the menu takeover is restricted to tables
        // whose inventory really is the vanilla grid.
        if (!isAnyTable(event.getLevel(), pos)) {
            return;
        }
        // Sneak + right-click with something placeable is attachment. Returning here would not be
        // enough: vanilla's CraftingTableBlock.use returns CONSUME and opens the menu, and the item's
        // own useOn is never reached - which is exactly why a plate could never be bolted onto a
        // table. Denying only the BLOCK's use lets the item's placement path run, so the block or
        // plate attaches to the table the same way it attaches to a chest.
        if (event.getEntity().isSecondaryUseActive() && holdsPlaceable(event)) {
            // Two separate switches, and both are needed:
            //
            //   * DENY on the block, because CraftingTableBlock.use answers CONSUME and would
            //     otherwise swallow the click before the item is ever asked;
            //   * ALLOW on the item, because a table is a container and plenty of packs (and the
            //     modded tables that subclass the vanilla one) set DENY on the item for container
            //     clicks. Without this an item would be placeable against stone and against a chest
            //     but silently refuse on the one block that happens to be a crafting table - which
            //     reads as "this table has a face that eats blocks".
            event.setUseBlock(net.minecraftforge.eventbus.api.Event.Result.DENY);
            event.setUseItem(net.minecraftforge.eventbus.api.Event.Result.ALLOW);
            return;
        }
        // Sneak with an empty or non-placeable hand keeps vanilla behaviour.
        if (event.getEntity().isSecondaryUseActive()) {
            return;
        }
        // The menu itself. Server only: the client's own use opens its screen through the menu
        // factory registered for PERSISTENT_CRAFTING, so both sides still agree.
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        openPersistent(player, pos);
    }

    /**
     * True when a hand holds a stack that would place a block against the table.
     *
     * <p>Both hands are checked, not just the one the event names. Forge fires this event once per
     * hand, but {@code Minecraft.startUseItem} only reaches the off hand if the main hand answered
     * PASS - and a table's own {@code use} answers CONSUME, so an off-hand plate would never get its
     * turn. Answering for either hand keeps the gesture identical whether the plate is held in the
     * main hand or the off hand.
     *
     * <p>The test is {@code BlockItem}, not "any item": a stack of dirt, a shulker box and a Side
     * Drain Plate all place a block and must pass, while a wrench, a sword or a stack of arrows must
     * not - those have no placement of their own and denying the table's use for them would just
     * stop the menu from opening.
     */
    private static boolean holdsPlaceable(PlayerInteractEvent.RightClickBlock event) {
        return placesABlock(event.getItemStack())
                || placesABlock(event.getEntity().getMainHandItem())
                || placesABlock(event.getEntity().getOffhandItem());
    }

    private static boolean placesABlock(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem;
    }

    /**
     * True for any crafting table: vanilla, a modded subclass, or one wrapped in a carrier.
     *
     * <p>Used only to decide whether the <b>placement</b> gesture applies. It is deliberately wider
     * than {@link #isVanillaCraftingTable}, because the player does not know or care which kind of
     * table they are standing at when they sneak and hold a plate.
     */
    private static boolean isAnyTable(net.minecraft.world.level.Level level, BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof CraftingTableBlock) {
            return true;
        }
        if (state.getBlock() instanceof com.sidedrain.block.RotatedBlock) {
            net.minecraft.world.level.block.state.BlockState inner =
                    com.sidedrain.block.RotatedBlock.innerState(level, pos);
            return inner != null && inner.getBlock() instanceof CraftingTableBlock;
        }
        return false;
    }

    /**
     * True for a table whose inventory really is the vanilla 3x3 grid.
     *
     * <p>That is the vanilla block and any mod subclass that inherits its menu; a machine with its
     * own GUI is excluded, so its own screen and its own slots are never replaced by a plain crafting
     * grid it does not have.
     */
    private static boolean isVanillaCraftingTable(net.minecraft.world.level.block.Block block) {
        return com.sidedrain.util.CraftingTables.isPlainCraftingTable(block);
    }

    /** Opens the persistent crafting menu for the table at {@code pos}. */
    private static void openPersistent(ServerPlayer player, BlockPos pos) {
        Component title = Component.translatable("container.crafting");
        MenuProvider provider = new SimpleMenuProvider(
                (id, inventory, p) -> new PersistentCraftingMenu(id, inventory,
                        ContainerLevelAccess.create(player.level(), pos)), title);
        // The table position rides the network buffer so the client-side menu knows which table it
        // persists into. Written exactly once and read exactly once - the previous version wrote it
        // once and read it twice, which threw while the client was building the menu and left the
        // right-click looking completely dead.
        net.minecraftforge.network.NetworkHooks.openScreen(player, provider, buffer -> buffer.writeBlockPos(pos));
        // The server-side menu now owns the transient grid; restore whatever the table was holding
        // so the player sees their half-finished recipe exactly as they left it.
        if (player.containerMenu instanceof PersistentCraftingMenu persistent) {
            persistent.restoreGrid(player.level());
        }
    }

    /**
     * Breaking a table spills its grid onto the ground, the same way a shulker box spills its
     * contents.
     */
    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!isVanillaCraftingTable(event.getState().getBlock())) {
            return;
        }
        CraftingGridStorage.spill(level, event.getPos());
    }

    /** True when {@code player} has a persistent crafting menu open on {@code pos}. */
    public static boolean isOpenOn(Player player, BlockPos pos) {
        return player.containerMenu instanceof PersistentCraftingMenu menu
                && pos.equals(menu.tablePos());
    }

    private CraftingTableEvents() {
    }
}
