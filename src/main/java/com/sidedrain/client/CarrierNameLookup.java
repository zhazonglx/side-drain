package com.sidedrain.client;

import com.sidedrain.block.RotatedBlock;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Finds the wrapped block of the carrier the local player is aiming at.
 *
 * <p>{@code Block.getName()} is asked for the name of a block with no position, which is fine for
 * every vanilla block because its name never varies. The rotated-block carrier is the exception: its
 * name is derived from whatever it wraps, so the name can only be produced by looking at the block
 * entity. The HUD is naming the block under the crosshair anyway, so reading the crosshair target
 * here gives exactly the right answer and costs nothing on a dedicated server.
 */
@OnlyIn(Dist.CLIENT)
public final class CarrierNameLookup {
    @Nullable
    public static BlockState aimedInner() {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || minecraft.hitResult == null
                || minecraft.hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = ((BlockHitResult) minecraft.hitResult).getBlockPos();
        if (!(level.getBlockState(pos).getBlock() instanceof RotatedBlock)) {
            return null;
        }
        return RotatedBlock.innerState(level, pos);
    }

    private CarrierNameLookup() {
    }
}
