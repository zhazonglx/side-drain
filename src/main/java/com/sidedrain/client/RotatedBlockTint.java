package com.sidedrain.client;

import com.sidedrain.block.RotatedBlock;
import com.sidedrain.block.entity.RotatedBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Resolves the tint a <b>rotated</b> block should be drawn with.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A rotated block's quads keep the {@code tintIndex} of the block they wrap - a grass block's
 * overlay is tinted, leaves are tinted, water is tinted. But the tint is looked up from the state
 * the mesher is drawing, which is the <b>carrier</b>, not the wrapped block. The carrier has no
 * colour handler registered, so every tinted quad resolved to white: a rotated grass block turned
 * grey-white, rotated leaves turned grey, rotated redstone wire lost its power colour. That is the
 * "the green turns grey-white" report.
 *
 * <h2>How the tint is resolved</h2>
 *
 * <p>The colour of the <b>wrapped</b> block, in the <b>same biome</b> the carrier stands in. The
 * wrapped state is read from the carrier's block entity; the wrapped block's own colour handler is
 * then asked for the colour at the carrier's position, so grass stays biome-green, leaves stay
 * biome-foliage and redstone wire keeps its power colour exactly as if the block had never been
 * rotated.
 *
 * <p>Delegating to the wrapped block's handler rather than hard-coding vanilla colours is what makes
 * modded tinted blocks work too: anything a mod registered a colour handler for comes through with
 * the mod's own colour, because the mod registered it for its own block, which is the one being
 * asked.
 *
 * <p>Blocks that carry no tint at all resolve to white, which is the same as no tint, so untinted
 * blocks are unaffected by this path ever running.
 */
@OnlyIn(Dist.CLIENT)
public final class RotatedBlockTint {
    /** The colour of the wrapped block, at the carrier's position. White when there is none. */
    public static int color(@Nullable net.minecraft.world.level.block.state.BlockState carrierState,
                            @Nullable net.minecraft.world.level.BlockAndTintGetter level,
                            @Nullable BlockPos pos, int tintIndex) {
        if (carrierState == null || level == null || pos == null) {
            return -1;
        }
        BlockState inner = innerState(level, pos);
        if (inner == null || inner.isAir()) {
            return -1;
        }
        BlockColors colors = blockColors();
        if (colors == null) {
            return -1;
        }
        return colors.getColor(inner, level, pos, tintIndex);
    }

    /** The wrapped state of the carrier at {@code pos}, or {@code null} when it is not a carrier. */
    @Nullable
    private static BlockState innerState(net.minecraft.world.level.BlockAndTintGetter level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof RotatedBlockEntity carrier)) {
            return null;
        }
        return carrier.getInnerState();
    }

    /**
     * The game's block colour table.
     *
     * <p>Read off the client rather than cached: the colour handlers are registered into the same
     * instance vanilla consults, so delegating to it sees vanilla's handlers <em>and</em> every
     * modded handler registered through {@code RegisterColorHandlersEvent.Block}.
     */
    @Nullable
    private static BlockColors blockColors() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.level == null ? null : minecraft.getBlockColors();
    }

    /** True when {@code block} is the carrier, so a tint lookup must be delegated. */
    public static boolean isCarrier(net.minecraft.world.level.block.Block block) {
        return block instanceof RotatedBlock;
    }

    private RotatedBlockTint() {
    }
}
