package com.sidedrain.util;

import java.lang.reflect.Method;
import javax.annotation.Nullable;
import net.minecraft.world.item.ItemStack;

/**
 * Loads a casting weapon with its focus before the plate tries to fire it.
 *
 * <h2>The problem</h2>
 *
 * <p>Goety's wands do not read a focus from the player's inventory. The focus - 聚晶 - is stored
 * <b>inside the wand item itself</b>, in a {@code SoulUsingItemHandler} attached through the item's
 * capability. A wand with an empty focus slot cannot cast at all: its {@code use} method asks
 * {@code IWand.getFocus(stack)}, gets nothing, and returns without doing anything.
 *
 * <p>That is why a plate holding a loaded wand still did nothing: the wand was empty. The focus the
 * player had in their chest was never put into it, because nothing ever does that except the player.
 *
 * <h2>What this does</h2>
 *
 * <p>Before firing a casting weapon, the plate checks whether the weapon has an empty focus slot. If
 * it does, and the plate's storage or the attached container holds a focus, that focus is moved into
 * the wand. The focus is <b>moved, not copied</b>: it is real storage, and a plate that duplicated
 * focuses would be an item duplicator.
 *
 * <p>When the focus is spent or the wand is emptied, nothing is returned - the player can take the
 * wand out and retrieve the focus, exactly as if they had loaded it by hand.
 *
 * <h2>Why reflection</h2>
 *
 * <p>Goety is one mod out of nearly three hundred in the pack. Compiling against it would tie the mod
 * to one pack. The capability is reached by class name and fails silently when Goety is absent.
 */
public final class FocusLoader {
    private static boolean probed;
    @Nullable
    private static Class<?> handlerClass;
    @Nullable
    private static Method getHandler;
    @Nullable
    private static Method getSlot;
    @Nullable
    private static Method insertFocus;

    /**
     * Tries to put a focus into {@code weapon} from the stacks the caller can reach.
     *
     * @param weapon the casting weapon
     * @param source a callback that finds and extracts one focus, or {@code null} when none is
     *               available. The loader calls it only when the weapon actually has an empty slot.
     * @return true when the weapon now holds a focus
     */
    public static boolean ensureLoaded(ItemStack weapon, java.util.function.Supplier<ItemStack> source) {
        if (weapon.isEmpty() || !isAvailable()) {
            return false;
        }
        try {
            if (!hasEmptySlot(weapon)) {
                // Already loaded, which is the normal case for a wand the player prepared.
                return true;
            }
            ItemStack focus = source.get();
            if (focus == null || focus.isEmpty()) {
                return false;
            }
            Object handler = getHandler.invoke(null, weapon);
            if (handler == null) {
                return false;
            }
            // insertItem takes the whole stack and returns what it refused. A focus is a single
            // item, so anything left over means the slot was not actually free.
            ItemStack leftover = (ItemStack) insertFocus.invoke(handler, focus.copyWithCount(1));
            boolean accepted = leftover == null || leftover.isEmpty();
            if (accepted) {
                // Only consume the source item once the wand really took it.
                focus.shrink(1);
            }
            return accepted;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            com.sidedrain.SideDrain.LOGGER.debug("Focus loading failed", failure);
            return false;
        }
    }

    /**
     * True when the weapon has a focus slot that is currently empty.
     *
     * <p>{@code getSlot()} returns {@code ItemStack.EMPTY} for an empty slot, never {@code null},
     * so the old {@code slot == null || slot.isEmpty()} test was right - but it was reached only
     * through a handler that could throw. {@code SoulUsingItemHandler.get} raises
     * {@code IllegalArgumentException} when the stack has no item-handler capability, which is the
     * normal case for a stack that was just constructed rather than read from a player. That throw
     * escaped this method and was caught by {@link #ensureLoaded}'s broad catch, so every attempt
     * reported "could not load" and a focus in the chest was never moved into the wand.
     *
     * <p>Now the capability lookup is guarded on its own, so a stack that genuinely has no handler
     * answers "not loaded" without poisoning the caller's result.
     */
    private static boolean hasEmptySlot(ItemStack weapon) throws ReflectiveOperationException {
        Object handler;
        try {
            handler = getHandler.invoke(null, weapon);
        } catch (java.lang.reflect.InvocationTargetException absent) {
            // No item-handler capability on this stack: it is not a Goety wand.
            return false;
        }
        if (handler == null) {
            return false;
        }
        ItemStack slot = (ItemStack) getSlot.invoke(handler);
        return slot == null || slot.isEmpty();
    }

    /** True when Goety is present, so the plate knows whether loading is even possible. */
    public static boolean isAvailable() {
        if (probed) {
            return handlerClass != null;
        }
        probed = true;
        try {
            handlerClass = Class.forName(
                    "com.Polarice3.Goety.common.items.handler.SoulUsingItemHandler");
            getHandler = handlerClass.getMethod("get", ItemStack.class);
            getSlot = handlerClass.getMethod("getSlot");
            insertFocus = handlerClass.getMethod("insertItem", ItemStack.class);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            handlerClass = null;
            getHandler = null;
            getSlot = null;
            insertFocus = null;
        }
        return handlerClass != null;
    }

    /** True when the stack is a focus item (聚晶), detected by the mod's own marker interface. */
    public static boolean isFocus(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            Class<?> focusInterface = Class.forName("com.Polarice3.Goety.api.items.magic.IFocus");
            return focusInterface.isInstance(stack.getItem());
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return false;
        }
    }

    private FocusLoader() {
    }
}
