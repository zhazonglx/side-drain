package com.sidedrain.client;

import com.sidedrain.SideDrain;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Answers one question: "which item is the pointer resting on right now?"
 *
 * <h2>The bug this class used to have</h2>
 *
 * <p>JEI's {@code IIngredientListOverlay} has two overloads, and they return <b>different things</b>:
 *
 * <pre>
 *   Optional&lt;ITypedIngredient&lt;?&gt;&gt; getIngredientUnderMouse()
 *   &lt;T&gt; T                          getIngredientUnderMouse(IIngredientType&lt;T&gt;)
 * </pre>
 *
 * <p>The second one is generic, so erasure makes it return {@code Object} - and what it actually
 * hands back is the <b>ItemStack itself</b>, not a wrapper around one. The first version of this
 * class treated that return value as an {@code ITypedIngredient} and called {@code getItemStack()}
 * on it. That threw {@code IllegalArgumentException} on every single press, the catch below swallowed
 * it, and the feature looked completely dead: no pin, no message, no error. Hence "pressing C does
 * nothing".
 *
 * <h2>Three sources, tried in order</h2>
 *
 * <ol>
 *   <li><b>JEI's screen helper</b> - {@code getClickableIngredientUnderMouse(screen, x, y)}. This is
 *       the most complete answer, because it asks JEI about everything it knows is under the pointer:
 *       the ingredient list, the bookmark panel, and any ghost-ingredient handler a mod has
 *       registered.</li>
 *   <li><b>JEI's ingredient list overlay</b> - the right-hand list, read through the correct
 *       overload.</li>
 *   <li><b>EMI</b> - {@code EmiApi.getHoveredStack}, for packs that use EMI instead.</li>
 * </ol>
 *
 * <p>Container slots are <b>not</b> handled here: the screen checks its own hovered slot first, which
 * covers the plate's storage, the weapon slot, the player inventory and the hotbar.
 *
 * <h2>Why reflection</h2>
 *
 * <p>JEI, EMI and REI are all optional. A compile dependency on any of them would make the mod refuse
 * to build without it. Every lookup is wrapped, and each path fails independently - one broken
 * viewer must not take the others down with it.
 */
@OnlyIn(Dist.CLIENT)
public final class RecipeViewerBridge {
    /** How long to wait before retrying a probe that found nothing, in game ticks. */
    private static final long PROBE_RETRY_TICKS = 100L;

    private static boolean probedJei;
    private static long nextJeiProbe;
    /** JEI's IScreenHelper, or null. */
    private static Object jeiScreenHelper;
    private static Method jeiClickableUnderMouse;
    /** JEI's IIngredientListOverlay, or null. */
    private static Object jeiOverlay;
    private static Method jeiTypedUnderMouse;
    private static Method jeiPlainUnderMouse;
    private static Object jeiItemStackType;
    /** Shared by both JEI paths: ITypedIngredient#getItemStack() -> Optional<ItemStack>. */
    private static Method typedGetItemStack;

    private static boolean probedEmi;
    private static long nextEmiProbe;
    private static Method emiHoveredStack;

    /** True once any lookup has succeeded, so the "it works" line is logged exactly once. */
    private static boolean reported;

    /**
     * The item the pointer is over, according to whichever recipe viewer is running.
     *
     * @param screen  the screen the pointer is on
     * @param mouseX  pointer x in GUI space, as passed to {@code Screen#render}
     * @param mouseY  pointer y in GUI space
     * @return a single-item stack, or {@link ItemStack#EMPTY} when nothing was found
     */
    public static ItemStack itemUnderPointer(Screen screen, double mouseX, double mouseY) {
        ItemStack stack = fromJeiScreenHelper(screen, mouseX, mouseY);
        if (!stack.isEmpty()) {
            return stack;
        }
        stack = fromJeiList();
        if (!stack.isEmpty()) {
            return stack;
        }
        return fromEmi();
    }

    /** True when at least one viewer answered a probe, so the hint line is worth showing. */
    public static boolean anyViewerAvailable() {
        probeJei();
        probeEmi();
        return jeiScreenHelper != null || jeiOverlay != null || emiHoveredStack != null;
    }

    // ---------------------------------------------------------------- JEI

    /**
     * Asks JEI's screen helper what is under the pointer.
     *
     * <p>This is the broadest path: it covers the ingredient list, the bookmark panel and any
     * ghost-ingredient handler, and it is the only one that knows about overlays other than the main
     * list.
     */
    private static ItemStack fromJeiScreenHelper(Screen screen, double mouseX, double mouseY) {
        probeJei();
        if (jeiScreenHelper == null || jeiClickableUnderMouse == null || screen == null) {
            return ItemStack.EMPTY;
        }
        try {
            Object result = jeiClickableUnderMouse.invoke(jeiScreenHelper, screen, mouseX, mouseY);
            if (!(result instanceof Stream<?> stream)) {
                return ItemStack.EMPTY;
            }
            Optional<?> first = stream.findFirst();
            if (first.isEmpty()) {
                return ItemStack.EMPTY;
            }
            Object clickable = first.get();
            Object typed = clickable.getClass().getMethod("getTypedIngredient").invoke(clickable);
            return unwrapTyped(typed);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // A screen JEI has never seen, or a version that changed shape. Ordinary.
            SideDrain.LOGGER.debug("JEI screen-helper lookup failed", failure);
            return ItemStack.EMPTY;
        }
    }

    /** Reads JEI's ingredient list overlay through the overload that returns the stack directly. */
    private static ItemStack fromJeiList() {
        probeJei();
        if (jeiOverlay == null) {
            return ItemStack.EMPTY;
        }
        // Typed overload: returns the ingredient itself, NOT a wrapper. This is the call the first
        // version got wrong, which is why nothing ever happened.
        if (jeiTypedUnderMouse != null && jeiItemStackType != null) {
            try {
                Object direct = jeiTypedUnderMouse.invoke(jeiOverlay, jeiItemStackType);
                if (direct instanceof ItemStack stack && !stack.isEmpty()) {
                    return done(stack.copyWithCount(1));
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                SideDrain.LOGGER.debug("JEI typed lookup failed", failure);
            }
        }
        // Plain overload: Optional<ITypedIngredient<?>>, which does need unwrapping.
        if (jeiPlainUnderMouse != null) {
            try {
                Object holder = jeiPlainUnderMouse.invoke(jeiOverlay);
                if (holder instanceof Optional<?> optional && optional.isPresent()) {
                    return unwrapTyped(optional.get());
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                SideDrain.LOGGER.debug("JEI plain lookup failed", failure);
            }
        }
        return ItemStack.EMPTY;
    }

    /** Turns an {@code ITypedIngredient} into an item stack, or EMPTY when it is not an item. */
    private static ItemStack unwrapTyped(Object typed) {
        if (typed == null || typedGetItemStack == null) {
            return ItemStack.EMPTY;
        }
        try {
            // ITypedIngredient#getItemStack() returns Optional<ItemStack>. Reading it as a plain
            // stack is the other half of the same mistake: it silently yields nothing.
            Object holder = typedGetItemStack.invoke(typed);
            if (holder instanceof Optional<?> optional && optional.isPresent()
                    && optional.get() instanceof ItemStack stack && !stack.isEmpty()) {
                return done(stack.copyWithCount(1));
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            SideDrain.LOGGER.debug("JEI ingredient unwrap failed", failure);
        }
        return ItemStack.EMPTY;
    }

    /**
     * Resolves JEI's runtime, retrying if the first attempt found nothing.
     *
     * <p>JEI builds its runtime during its own startup, which can finish after the player has
     * already opened a panel. Caching "absent" forever would make the key look permanently broken in
     * exactly that window; the retry is throttled so a pack without JEI pays almost nothing.
     *
     * <p>Each piece is resolved in its own {@code try}, so a JEI version that renamed one method
     * still leaves the others working.
     */
    private static void probeJei() {
        if (probedJei) {
            if (jeiScreenHelper != null || jeiOverlay != null) {
                return;
            }
            long now = System.nanoTime() / 50_000_000L;
            if (now < nextJeiProbe) {
                return;
            }
            nextJeiProbe = now + PROBE_RETRY_TICKS;
        }
        probedJei = true;
        Object runtime = null;
        try {
            Class<?> internal = Class.forName("mezz.jei.common.Internal");
            runtime = internal.getMethod("getJeiRuntime").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            // JEI is not installed, or has not finished starting. Both are ordinary.
            return;
        }
        if (runtime == null) {
            return;
        }
        // The two shared pieces first: the item ingredient type and the unwrapper.
        try {
            jeiItemStackType = Class.forName("mezz.jei.api.constants.VanillaTypes")
                    .getField("ITEM_STACK").get(null);
            typedGetItemStack = Class.forName("mezz.jei.api.ingredients.ITypedIngredient")
                    .getMethod("getItemStack");
        } catch (ReflectiveOperationException | RuntimeException absent) {
            SideDrain.LOGGER.debug("JEI ingredient types unavailable", absent);
        }
        // Screen helper: the broadest source.
        try {
            Object helper = runtime.getClass().getMethod("getScreenHelper").invoke(runtime);
            if (helper != null) {
                Class<?> screenHelperApi = Class.forName("mezz.jei.api.runtime.IScreenHelper");
                jeiClickableUnderMouse = screenHelperApi.getMethod("getClickableIngredientUnderMouse",
                        Screen.class, double.class, double.class);
                jeiScreenHelper = helper;
            }
        } catch (ReflectiveOperationException | RuntimeException absent) {
            SideDrain.LOGGER.debug("JEI screen helper unavailable", absent);
        }
        // Ingredient list overlay: the right-hand list.
        try {
            Object overlay = runtime.getClass().getMethod("getIngredientListOverlay").invoke(runtime);
            if (overlay != null) {
                Class<?> overlayApi = Class.forName("mezz.jei.api.runtime.IIngredientListOverlay");
                Class<?> ingredientType = Class.forName("mezz.jei.api.ingredients.IIngredientType");
                jeiTypedUnderMouse = overlayApi.getMethod("getIngredientUnderMouse", ingredientType);
                jeiPlainUnderMouse = overlayApi.getMethod("getIngredientUnderMouse");
                jeiOverlay = overlay;
            }
        } catch (ReflectiveOperationException | RuntimeException absent) {
            SideDrain.LOGGER.debug("JEI ingredient list unavailable", absent);
        }
        SideDrain.LOGGER.debug("JEI bridge: screenHelper={} list={}",
                jeiScreenHelper != null, jeiOverlay != null);
    }

    // ---------------------------------------------------------------- EMI

    private static ItemStack fromEmi() {
        probeEmi();
        if (emiHoveredStack == null) {
            return ItemStack.EMPTY;
        }
        try {
            Object stack = emiHoveredStack.invoke(null, Boolean.TRUE);
            if (stack == null) {
                return ItemStack.EMPTY;
            }
            // EmiStack#getItemStack() returns a plain ItemStack.
            Object itemStack = stack.getClass().getMethod("getItemStack").invoke(stack);
            if (itemStack instanceof ItemStack direct && !direct.isEmpty()) {
                return done(direct.copyWithCount(1));
            }
        } catch (ReflectiveOperationException | RuntimeException absent) {
            SideDrain.LOGGER.debug("EMI lookup failed", absent);
        }
        return ItemStack.EMPTY;
    }

    private static void probeEmi() {
        if (probedEmi) {
            if (emiHoveredStack != null) {
                return;
            }
            long now = System.nanoTime() / 50_000_000L;
            if (now < nextEmiProbe) {
                return;
            }
            nextEmiProbe = now + PROBE_RETRY_TICKS;
        }
        probedEmi = true;
        try {
            Class<?> api = Class.forName("dev.emi.emi.api.EmiApi");
            emiHoveredStack = api.getMethod("getHoveredStack", boolean.class);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            emiHoveredStack = null;
        }
    }

    /** Logs the first successful lookup once, so a log always says whether the bridge is live. */
    private static ItemStack done(ItemStack stack) {
        if (!reported) {
            reported = true;
            SideDrain.LOGGER.info("Recipe viewer bridge is live; first lookup returned {}",
                    stack.getHoverName().getString());
        }
        return stack;
    }

    private RecipeViewerBridge() {
    }
}
