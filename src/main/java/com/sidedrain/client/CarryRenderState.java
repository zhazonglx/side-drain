package com.sidedrain.client;

import java.util.HashSet;
import java.util.Set;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Per-frame bookkeeping for the carried-block rendering.
 *
 * <p>The hand render event fires once for the main hand and once for the off hand, and the player
 * render event fires once per player. Both need to know "has this already been drawn this frame",
 * but a plain static boolean is wrong: it survives across frames and then silently stops drawing.
 *
 * <p>The key here is a real frame counter, not the game time. Game time only advances twenty times a
 * second, so at any frame rate above twenty the flag stayed false for most frames and the block
 * flickered or never appeared at all. The counter below is bumped by the render events themselves.
 */
@OnlyIn(Dist.CLIENT)
public final class CarryRenderState {
    private static int frame;
    private static int handFrame = -1;
    private static boolean handDrawn;
    private static int playerFrame = -1;
    /** Entity ids already drawn this frame; keyed per player, not globally. */
    private static final Set<Integer> PLAYERS_DRAWN = new HashSet<>();

    /** Starts a new render frame; called from the level render hook. */
    public static void beginFrame() {
        frame++;
    }

    /** True the first time it is called in a given frame, false afterwards. */
    public static boolean firstHandPass() {
        if (frame != handFrame) {
            handFrame = frame;
            handDrawn = false;
        }
        if (handDrawn) {
            return false;
        }
        handDrawn = true;
        return true;
    }

    /**
     * True the first time it is called for a given player in a given frame.
     *
     * <p>This used to ignore {@code entityId} entirely, which meant only <b>one</b> carrying player
     * was drawn per frame: in third person with two people carrying blocks, or in a multiplayer view,
     * everybody else's block was missing and the ones that did draw flickered between players.
     */
    public static boolean firstPlayerPass(int entityId) {
        if (frame != playerFrame) {
            playerFrame = frame;
            PLAYERS_DRAWN.clear();
        }
        return PLAYERS_DRAWN.add(entityId);
    }

    private CarryRenderState() {
    }
}
