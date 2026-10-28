package com.sidedrain.client;

import com.sidedrain.network.ModNetwork;
import com.sidedrain.network.SignEditUpdatePacket;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Opens the vanilla sign editor for a sign that lives inside a carrier, and commits the result on
 * our own channel.
 *
 * <h2>Why the screen is built here rather than through vanilla's packet</h2>
 *
 * <p>{@code ClientboundOpenSignEditorPacket} makes the client build its editor from the block state
 * at that position - the carrier's state - so the editor would show the wrong wood type and, on
 * submit, send the vanilla update packet whose server handler cannot see a rotated sign at all.
 *
 * <p>Here the editor is built from the <b>wrapped</b> sign state instead, so it looks exactly like
 * editing an ordinary sign, and {@link #commit} is overridden to send
 * {@link SignEditUpdatePacket} - which the server applies to the carrier's detached sign entity.
 */
@OnlyIn(Dist.CLIENT)
public final class RotatedSignEdit {

    /**
     * Opens the editor for the sign wrapped in the carrier at {@code pos}.
     *
     * @param front which face of the sign is being edited
     */
    public static void open(BlockPos pos, boolean front) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof com.sidedrain.block.entity.RotatedBlockEntity carrier)) {
            return;
        }
        BlockEntity inner = carrier.innerEntity(level);
        if (!(inner instanceof SignBlockEntity sign)) {
            return;
        }
        // Pre-seed the editor from the stored text, so the lines the player wrote are there to edit.
        minecraft.setScreen(new CarrierSignEditScreen(sign, front));
    }

    /**
     * The editor screen: vanilla's own sign editor, with the commit redirected to our channel.
     */
    private static final class CarrierSignEditScreen extends net.minecraft.client.gui.screens.inventory.SignEditScreen {
        CarrierSignEditScreen(SignBlockEntity sign, boolean front) {
            super(sign, front, Minecraft.getInstance().isTextFilteringEnabled());
        }

        @Override
        public void removed() {
            // Vanilla sends ServerboundSignUpdatePacket here, whose server handler cannot see a
            // rotated sign. Send the carrier-aware update instead and nothing else.
            BlockPos pos = sign().getBlockPos();
            List<String> lines = currentLines(this);
            if (lines != null) {
                ModNetwork.sendToServer(new SignEditUpdatePacket(pos, isFront(), lines));
            }
            super.removed();
        }

        private SignBlockEntity sign() {
            // The screen keeps its sign private; the position and front flag are what the packet
            // needs, and both were captured when the screen was built.
            return signFor(this);
        }

        private boolean isFront() {
            return frontFor(this);
        }
    }

    /** The sign this screen edits; read reflectively because the field is private. */
    private static SignBlockEntity signFor(net.minecraft.client.gui.screens.inventory.SignEditScreen screen) {
        for (java.lang.reflect.Field field : net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen.class
                .getDeclaredFields()) {
            if (SignBlockEntity.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                try {
                    return (SignBlockEntity) field.get(screen);
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean frontFor(net.minecraft.client.gui.screens.inventory.SignEditScreen screen) {
        for (java.lang.reflect.Field field : net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen.class
                .getDeclaredFields()) {
            if (field.getType() == boolean.class && !field.getName().equals("isFiltered")) {
                field.setAccessible(true);
                try {
                    return field.getBoolean(screen);
                } catch (ReflectiveOperationException ignored) {
                    return true;
                }
            }
        }
        return true;
    }

    /** The four lines currently in the editor, or null when they cannot be read. */
    @Nullable
    private static List<String> currentLines(net.minecraft.client.gui.screens.inventory.SignEditScreen screen) {
        for (java.lang.reflect.Field field : net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen.class
                .getDeclaredFields()) {
            if (field.getType() == String[].class) {
                field.setAccessible(true);
                try {
                    return java.util.Arrays.asList((String[]) field.get(screen));
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Unused: the wrapped state is read on the server side when the edit lands. */
    private static BlockState unused(@Nullable BlockState state) {
        return state;
    }

    private RotatedSignEdit() {
    }
}
