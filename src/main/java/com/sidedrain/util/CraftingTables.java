package com.sidedrain.util;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.MenuProvider;
import net.minecraft.core.BlockPos;

/**
 * Recognises the crafting tables whose grid this mod may take over.
 *
 * <h2>Why "is a CraftingTableBlock" is not enough</h2>
 *
 * <p>Two very different blocks both pass an {@code instanceof CraftingTableBlock} test:
 *
 * <ul>
 *   <li>a table that behaves exactly like the vanilla one - the vanilla block itself, or a mod's
 *       subclass that only changes the appearance or the recipe and inherits
 *       {@code getMenuProvider} unchanged. Its inventory <em>is</em> the vanilla 3x3 grid, so the
 *       persistent storage and the container view describe the same thing;</li>
 *   <li>a machine that has its own GUI, its own slots and its own recipes and only happens to
 *       extend the vanilla class. Replacing its menu with a plain crafting grid would delete its
 *       functionality, and giving the plate a synthetic 9-slot view would hand it an inventory that
 *       does not exist anywhere the player can see - items would appear to move into and out of
 *       nowhere.</li>
 * </ul>
 *
 * <p>The deciding question is whether the block inherits its menu provider from
 * {@link CraftingTableBlock}. A subclass that overrides it has its own screen and is left alone; a
 * subclass that does not has the vanilla grid and is handled exactly like vanilla.
 *
 * <h2>Why the method is found by signature, not by name</h2>
 *
 * <p>In a production Forge environment the method is renamed to its SRG id ({@code m_54763_}), so a
 * literal {@code getDeclaredMethod("getMenuProvider", ...)} lookup would throw and every modded
 * subclass would silently fall into the "leave it alone" branch. Matching on the parameter and
 * return types instead is stable across both development and production names.
 */
public final class CraftingTables {

    /** True when {@code block} uses the vanilla 3x3 crafting grid as its inventory. */
    public static boolean isPlainCraftingTable(Block block) {
        if (!(block instanceof CraftingTableBlock)) {
            return false;
        }
        if (block == net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) {
            return true;
        }
        return !overridesMenuProvider(block.getClass());
    }

    /** True when {@code state} is a table that uses the vanilla grid. */
    public static boolean isPlainCraftingTable(BlockState state) {
        return isPlainCraftingTable(state.getBlock());
    }

    private static final java.util.Map<Class<?>, Boolean> OVERRIDES = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean overridesMenuProvider(Class<?> type) {
        return OVERRIDES.computeIfAbsent(type, CraftingTables::computeOverridesMenuProvider);
    }

    private static boolean computeOverridesMenuProvider(Class<?> type) {
        for (Class<?> current = type; current != null && current != CraftingTableBlock.class;
                current = current.getSuperclass()) {
            for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                if (!MenuProvider.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 3
                        && parameters[0] == BlockState.class
                        && parameters[1] == Level.class
                        && parameters[2] == BlockPos.class) {
                    // Declared between this class and CraftingTableBlock: it is a different menu.
                    return true;
                }
            }
        }
        return false;
    }

    private CraftingTables() {
    }
}
