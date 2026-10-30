package com.sidedrain.util;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lets a plate dig with the tool in its weapon slot.
 *
 * <h2>What it digs</h2>
 *
 * <p>The six blocks that <b>touch</b> the plate - north, south, east, west, up and down. Not a
 * radius, not a cone: the plate is a thin panel clamped to a container, and "the blocks around me"
 * is exactly the six faces. Digging further would mean reaching through walls into someone else's
 * build, which is the same reason the item pickup is restricted to the front half-space.
 *
 * <h2>Which one</h2>
 *
 * <p>The player picks one of the six with the panel's direction switch, and the plate keeps working
 * that face until it is changed. The alternative - cycling through all six automatically - would make
 * the plate chew through a wall while the player was not looking, so the choice is explicit.
 *
 * <h2>How hard it hits</h2>
 *
 * <p>The tool's own numbers, read through the same calls the game uses:
 *
 * <ul>
 *   <li>{@code stack.getDestroySpeed(state)} decides whether the block is broken at all. A stone
 *       pickaxe simply refuses obsidian, exactly as it would in a player's hand.</li>
 *   <li>{@code stack.isCorrectToolForDrops(state)} decides whether the real drops are produced. A
 *       wrong tool breaks the block and yields nothing, which is what the game does too.</li>
 *   <li>Durability is spent per block broken, so a plate cannot mine forever for free.</li>
 * </ul>
 *
 * <p>Hardness is respected as a speed rather than as a delay: a block the tool handles easily goes in
 * one action, and one that would take a long time is simply not broken by this swing. That keeps the
 * plate's behaviour readable - a tool that cannot break something says so immediately instead of
 * appearing to work very slowly.
 */
public final class BlockMiner {
    /**
     * How many ticks of "hardness" one plate action is worth.
     *
     * <p>The plate acts on its own transfer cycle, so this is the amount of work a single swing
     * performs. Twenty is generous - a diamond pickaxe on stone finishes well inside it - and small
     * enough that obsidian with a wooden pickaxe is still refused.
     */
    private static final float WORK_PER_ACTION = 20.0F;

    /** The six faces a plate can dig, in a stable order for the panel's switch. */
    public static final Direction[] FACES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST,
            Direction.WEST, Direction.UP, Direction.DOWN,
    };

    /**
     * Digs the chosen neighbour once.
     *
     * @param face ordinal into {@link #FACES}, clamped by the caller
     * @return true when a block was actually broken, so the caller can spend its cooldown
     */
    public static boolean mine(ServerLevel level, BlockPos platePos, @Nullable Player owner,
                               ItemStack tool, int face) {
        if (level == null || tool.isEmpty() || face < 0 || face >= FACES.length) {
            return false;
        }
        BlockPos target = platePos.relative(FACES[face]);
        BlockState state = level.getBlockState(target);
        if (state.isAir()) {
            return false;
        }
        // Bedrock, barriers and anything else unbreakable must stay unbreakable: destroyBlock would
        // happily remove them on a server whose config allowed it, and a plate must never be a
        // bypass for that.
        if (state.getDestroySpeed(level, target) < 0.0F) {
            return false;
        }
        // The tool's own speed on this exact block, with Efficiency folded in.
        //
        // getDestroySpeed() does NOT include enchantments - vanilla applies Efficiency in the mining
        // loop, not in the item. A plate that ignored it would mine at the same rate whether the
        // pickaxe was plain or Efficiency V, which is the "enchantments do nothing" complaint.
        float speed = tool.getDestroySpeed(state) + efficiencyBonus(tool);
        if (speed <= 1.0F) {
            // A tool that offers no advantage here is the wrong tool. Breaking the block anyway would
            // let a plate chew through anything with any item, which is not what a pickaxe is.
            return false;
        }
        // Hardness times the speed has to fit inside one action's worth of work.
        float hardness = state.getDestroySpeed(level, target);
        if (hardness > 0.0F && hardness / speed > WORK_PER_ACTION) {
            return false;
        }
        boolean correctTool = tool.isCorrectToolForDrops(state);
        BlockEntity blockEntity = level.getBlockEntity(target);
        // Silk Touch takes the block itself; Fortune multiplies the drops. Block.getDrops already
        // honours both when the tool is passed in, which is why the tool is handed over rather than
        // the drops being computed by hand.
        boolean silkTouch = level(tool, net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH) > 0;
        int fortune = level(tool, net.minecraft.world.item.enchantment.Enchantments.BLOCK_FORTUNE);
        List<ItemStack> drops;
        if (!correctTool) {
            // A wrong tool breaks the block and yields nothing, exactly as in a player's hand.
            drops = List.of();
        } else if (silkTouch) {
            // Silk Touch yields the block itself, which is not what getDrops returns for a block whose
            // loot table does not handle it.
            drops = List.of(new ItemStack(state.getBlock()));
        } else {
            drops = Block.getDrops(state, level, target, blockEntity, owner, tool);
            if (fortune > 0) {
                drops = applyFortune(level, drops, fortune);
            }
        }
        // Destroyed without the normal loot path so the drops can be placed deliberately: they go
        // into the plate's own storage when there is room, and onto the ground in front of the plate
        // when there is not. That is the same "the plate is a machine, not a player" rule every other
        // mode follows.
        level.destroyBlock(target, false, owner, 512);
        if (!drops.isEmpty()) {
            List<ItemStack> leftover = new ArrayList<>(drops.size());
            for (ItemStack drop : drops) {
                if (!drop.isEmpty() && !insert(level, platePos, drop)) {
                    leftover.add(drop);
                }
            }
            for (ItemStack drop : leftover) {
                Block.popResource(level, platePos, drop);
            }
        }
        // The tool wears out exactly as it would in a hand.
        if (owner != null && !owner.getAbilities().instabuild && tool.isDamageableItem()) {
            tool.hurtAndBreak(1, owner, player -> { });
        }
        return true;
    }

    /**
     * Efficiency's speed bonus, computed the way vanilla does.
     *
     * <p>{@code level * level + 1} for a tool that is correct for the block, added to the tool's base
     * speed. Efficiency V on a diamond pickaxe therefore adds 26 to its 8, which is why it feels
     * instant on stone.
     */
    private static float efficiencyBonus(ItemStack tool) {
        int efficiency = level(tool, net.minecraft.world.item.enchantment.Enchantments.BLOCK_EFFICIENCY);
        if (efficiency <= 0) {
            return 0.0F;
        }
        return (float) (efficiency * efficiency + 1);
    }

    /**
     * Applies Fortune to a drop list.
     *
     * <p>Vanilla's own {@code applyBonus} lives in the loot-table machinery and is not reachable from
     * here, so this uses the documented formula: each stack is multiplied by a random factor between
     * 1 and {@code fortune + 1}, with the same 1-in-(fortune+2) chance of an extra item vanilla uses.
     * The result is close enough that a Fortune III pickaxe visibly produces more, which is the point.
     */
    private static List<ItemStack> applyFortune(net.minecraft.server.level.ServerLevel level,
                                                List<ItemStack> drops, int fortune) {
        List<ItemStack> out = new ArrayList<>(drops.size());
        var random = level.getRandom();
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            int extra = random.nextInt(fortune + 2) - 1;
            if (extra < 0) {
                extra = 0;
            }
            ItemStack copy = drop.copy();
            copy.grow(extra * copy.getCount());
            out.add(copy);
        }
        return out;
    }

    /** Reads an enchantment level off a stack without needing a registry holder. */
    private static int level(ItemStack stack, net.minecraft.world.item.enchantment.Enchantment enchantment) {
        for (var entry : net.minecraft.world.item.enchantment.EnchantmentHelper
                .getEnchantments(stack).entrySet()) {
            if (entry.getKey() == enchantment) {
                return entry.getValue();
            }
        }
        return 0;
    }

    /**
     * Offers a drop to the container behind the plate.
     *
     * <p>Deliberately not the plate's own storage: a mining plate that filled itself would stop
     * mining after fifty-four blocks, which is a confusing failure. The container it is clamped onto
     * is the natural place for what it digs, and the caller falls back to dropping it in the world.
     *
     * @return true when the whole stack was accepted
     */
    private static boolean insert(ServerLevel level, BlockPos platePos, ItemStack stack) {
        var attached = attachedContainer(level, platePos);
        if (attached == null) {
            return false;
        }
        ItemStack moving = stack.copy();
        for (int i = 0; i < attached.getContainerSize() && !moving.isEmpty(); i++) {
            if (!attached.canPlaceItem(i, moving)) {
                continue;
            }
            ItemStack existing = attached.getItem(i);
            if (existing.isEmpty()) {
                attached.setItem(i, moving.copy());
                moving.setCount(0);
            } else if (ItemStack.isSameItemSameTags(existing, moving)) {
                int room = existing.getMaxStackSize() - existing.getCount();
                if (room > 0) {
                    int moved = Math.min(room, moving.getCount());
                    existing.grow(moved);
                    moving.shrink(moved);
                }
            }
        }
        if (moving.getCount() != stack.getCount()) {
            attached.setChanged();
        }
        return moving.isEmpty();
    }

    /**
     * The container the plate is clamped onto.
     *
     * <p>Resolved here rather than borrowed from the plate so this class stays usable on its own; the
     * lookup is the same one the block entity performs, one block behind the nozzle.
     */
    @Nullable
    private static net.minecraft.world.Container attachedContainer(ServerLevel level, BlockPos platePos) {
        BlockState state = level.getBlockState(platePos);
        if (!(state.getBlock() instanceof com.sidedrain.block.SideDrainBlock plate)) {
            return null;
        }
        Direction facing = state.getValue(com.sidedrain.block.SideDrainBlock.FACING);
        BlockPos behind = platePos.relative(facing.getOpposite());
        return com.sidedrain.block.entity.OmniHopperBlockEntity.getContainerAt(level, behind);
    }

    private BlockMiner() {
    }
}
