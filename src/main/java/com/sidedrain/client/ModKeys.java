package com.sidedrain.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.sidedrain.SideDrain;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * The mod's own key bindings.
 *
 * <h2>A supplement, not a replacement</h2>
 *
 * <p>These are <b>extra</b> keys, added next to the gestures the mod always had. The wrench still
 * flips on sneak + right click and still spins on sneak + left click; the glove still lifts and
 * puts down on sneak + middle click. Nothing about those changed, so a player who is used to them
 * never has to learn anything new, and a pack that documents the old gestures keeps working.
 *
 * <p>What the bindings add is a way to do the same thing without holding sneak - which matters when
 * the player is already using sneak for something else (building, sneaking past mobs, placing a
 * plate), and matters more for anyone whose sneak is bound to a key they cannot comfortably hold
 * while clicking.
 *
 * <h2>Why both are kept</h2>
 *
 * <p>Replacing the gestures with bindings was tried and reverted. It looks tidier on paper, but it
 * breaks every existing habit and every written instruction at once, and it makes the tool useless
 * until the player finds the controls screen. An extra key costs one line of documentation and
 * breaks nothing.
 *
 * <h2>Why the labels are bilingual</h2>
 *
 * <p>The controls list is searched by the player's own words. A pack player looking for "旋转方块"
 * will not find "Spin Block", and vice versa, so every entry carries both - which is also what makes
 * these findable in a pack whose language the player may not have set.
 */
@OnlyIn(Dist.CLIENT)
public final class ModKeys {
    /** Category shown in Options -> Controls; also bilingual. */
    private static final String CATEGORY = "key.categories.sidedrain";

    /**
     * Spins the targeted block with the wrench, without sneaking.
     *
     * <p>Defaults to {@code G}, which is not a vanilla binding, so a fresh install has no conflict.
     * This is the same rotation as sneak + left click; the sneak gesture keeps working.
     */
    public static final KeyMapping ROTATE = new KeyMapping(
            "key.sidedrain.rotate_block",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    /**
     * Lifts or puts down the block under the cursor, without sneaking.
     *
     * <p>Defaults to {@code R}. This is the same action as sneak + middle click; that gesture keeps
     * working.
     */
    public static final KeyMapping CARRY = new KeyMapping(
            "key.sidedrain.carry_block",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            CATEGORY);

    private ModKeys() {
    }

    /** Drains a queued click so a binding never accumulates presses it will not use. */
    static void drain(KeyMapping binding) {
        while (binding.consumeClick()) {
            // The action is driven from the tick handler, not from the queue.
        }
    }

    /** Logs the bindings once at startup, which is the only way a rebind shows up in a log. */
    static void describe() {
        SideDrain.LOGGER.debug("Side Drain keys: rotate={} carry={}",
                ROTATE.getTranslatedKeyMessage().getString(),
                CARRY.getTranslatedKeyMessage().getString());
    }
}
