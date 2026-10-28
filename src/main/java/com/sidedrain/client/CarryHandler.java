package com.sidedrain.client;

import com.sidedrain.item.BlockGloveItem;
import com.sidedrain.network.CarryRequestPacket;
import com.sidedrain.network.ModNetwork;
import com.sidedrain.registry.ModItems;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side view of what a player is carrying.
 *
 * <h2>Per glove, not per player</h2>
 *
 * <p>The carry payload lives on the glove stack itself, so the glove in your hand <b>is</b> the
 * answer to "what am I carrying". This class therefore reads the held stack first and only falls
 * back to the synced {@link com.sidedrain.network.CarrySyncPacket} cache when no glove is in hand.
 *
 * <p>That distinction is what makes swapping the main hand work. The previous version keyed
 * everything on the player's UUID, so switching from a glove full of dirt to a glove full of stone
 * changed nothing on screen - the renderer kept drawing whatever had last been synced - and a second
 * glove could never show its own block. Reading the stack means the picture follows the hand on the
 * very same frame, and two gloves can carry two different blocks at once.
 */
@OnlyIn(Dist.CLIENT)
public final class CarryHandler {
    /**
     * Players the server last reported as carrying something.
     *
     * <p>Diagnostic only: rendering and input both resolve the glove in hand instead, because the
     * payload lives on the stack. It is still tracked so {@link #setCarried} has something to do and
     * a future HUD can ask without touching player inventory.
     */
    private static final Set<UUID> CARRYING = new HashSet<>();

    /**
     * What a player is carrying, resolved to the two things a renderer needs.
     *
     * <p>{@code display} is empty for a carried entity with no item form and {@code state} is
     * {@code null} for one that is not a block, so either can be absent; {@link #isEmpty()} is the
     * only correct "is anything being carried" test.
     */
    public record Carried(ItemStack display, @Nullable BlockState state) {
        public boolean isEmpty() {
            return this.display.isEmpty() && this.state == null;
        }
    }

    private static final Carried NOTHING = new Carried(ItemStack.EMPTY, null);

    /** The glove the player is carrying with: main hand first, then off hand, else {@code null}. */
    @Nullable
    public static ItemStack gloveInHand(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(ModItems.BLOCK_GLOVE.get())) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        return off.is(ModItems.BLOCK_GLOVE.get()) ? off : null;
    }

    /** True when the given glove stack is itself holding something. */
    public static boolean isCarrying(ItemStack glove) {
        return glove.is(ModItems.BLOCK_GLOVE.get()) && BlockGloveItem.isCarrying(glove);
    }

    /**
     * Resolves what the given player is carrying right now. Never {@code null}.
     *
     * <p>Only a glove in hand can be carrying something. That is the whole point of the swap: hold
     * a glove and its block is drawn, hold anything else and the hands are empty. No packet cache is
     * consulted here, because a stale packet must never resurrect a block the player has already put
     * down or draw one while they are holding a sword.
     */
    public static Carried carriedBy(Player player) {
        ItemStack glove = gloveInHand(player);
        if (glove == null) {
            return NOTHING;
        }
        ItemStack display = BlockGloveItem.carriedDisplay(glove);
        BlockState state = stateOf(glove);
        // A carried ENTITY has no block state at all - its payload is EntityData plus a display
        // stack. Testing only "display empty AND state null" made such a glove look empty whenever
        // the display stack failed to round-trip, even though the server was really carrying the
        // thing. Ask the glove whether it holds anything instead of inferring it from two fields
        // that a modded payload may legitimately leave blank.
        boolean holdsSomething = BlockGloveItem.isCarrying(glove)
                || !display.isEmpty() || state != null;
        return holdsSomething ? new Carried(display, state) : NOTHING;
    }

    /** Resolves what the local player is carrying. */
    public static Carried carriedByLocalPlayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? NOTHING : carriedBy(player);
    }

    /** True when the given player is carrying something. */
    public static boolean isCarrying(UUID playerId) {
        LocalPlayer self = Minecraft.getInstance().player;
        if (self != null && self.getUUID().equals(playerId)) {
            return !carriedBy(self).isEmpty();
        }
        // Another player: their equipment is synced, so try the hand stacks before the cache.
        Player other = findPlayer(playerId);
        if (other != null) {
            return !carriedBy(other).isEmpty();
        }
        return CARRYING.contains(playerId);
    }

    /** True when the local player is carrying something. */
    public static boolean isCarrying() {
        return !carriedByLocalPlayer().isEmpty();
    }

    @Nullable
    private static Player findPlayer(UUID playerId) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        for (Player candidate : level.players()) {
            if (candidate.getUUID().equals(playerId)) {
                return candidate;
            }
        }
        return null;
    }

    /** The carried block state of a glove stack, or {@code null} when it carries no block. */
    @Nullable
    private static BlockState stateOf(ItemStack glove) {
        return parseState(BlockGloveItem.carriedStateTag(glove));
    }

    /** Stores the synced carry state. Kept for the network packet's bookkeeping. */
    public static void setCarried(UUID playerId, ItemStack stack, boolean carrying, CompoundTag stateTag) {
        if (carrying) {
            CARRYING.add(playerId);
        } else {
            CARRYING.remove(playerId);
        }
        // The payload itself is read from the glove stack, so nothing else has to be cached here.
    }

    @Nullable
    private static BlockState parseState(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        try {
            BlockState state = net.minecraft.nbt.NbtUtils.readBlockState(
                    minecraft.level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
            return state.isAir() ? null : state;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void clear() {
        CARRYING.clear();
    }

    /**
     * True when the player is holding the Block Glove in either hand.
     *
     * <p>Main hand only was wrong: an off-hand glove is held and drawn just the same, and the server
     * accepts a lift from either hand. Refusing on the client while the server would have accepted
     * is the worst of both - the key looks dead.
     */
    public static boolean hasGlove(Player player) {
        return player.getMainHandItem().is(ModItems.BLOCK_GLOVE.get())
                || player.getOffhandItem().is(ModItems.BLOCK_GLOVE.get());
    }

    /**
     * Called when the carry gesture is performed. Returns true when handled.
     *
     * <p>The action is decided by the <b>held glove's own</b> payload: an empty glove lifts, a full
     * glove puts down. That is what lets two gloves carry two blocks - lifting with the empty one no
     * longer silently puts down what the other is holding, which is what the old player-wide
     * "already carrying" test did.
     *
     * <p>Sneak is required for the <b>gesture</b> (sneak + middle click), which is what keeps a
     * plain middle click doing vanilla pick-block. The {@code R} key binding reaches this same
     * method without sneaking - it is an addition, not a replacement - and the check is therefore
     * skipped for it by {@link #onBoundKey()}.
     */
    public static boolean onPickBlock() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return false;
        }
        if (!player.isShiftKeyDown()) {
            return false;
        }
        return carryAction(minecraft, player);
    }

    /**
     * The same carry action, for the bound key.
     *
     * <p>Identical except for the sneak requirement: a bound key is already an explicit intent, so
     * demanding sneak on top of it would just be a second thing to hold.
     */
    public static boolean onBoundKey() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return false;
        }
        return carryAction(minecraft, player);
    }

    /** The shared body of both entry points. */
    private static boolean carryAction(Minecraft minecraft, LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        // The glove that will act: a carrying glove takes priority so a full hand can always empty
        // itself, then the off hand, and only then an empty main-hand glove picks something up.
        ItemStack actor = isCarrying(main) ? main : (isCarrying(off) ? off : null);
        if (actor == null && !hasGlove(player)) {
            return false;
        }

        boolean puttingDown = actor != null;
        if (minecraft.hitResult == null || minecraft.hitResult.getType() == HitResult.Type.MISS) {
            if (puttingDown) {
                ModNetwork.sendToServer(new CarryRequestPacket(
                        CarryRequestPacket.PUT_DOWN, player.blockPosition()));
                return true;
            }
            return false;
        }
        if (minecraft.hitResult.getType() != HitResult.Type.BLOCK) {
            // A placeable entity (a modded backpack, a chair) is not a block hit; try the entity
            // under the cursor so the glove can lift it too.
            if (!puttingDown && hasGlove(player)) {
                net.minecraft.world.entity.Entity target = entityUnderCursor(minecraft);
                if (target != null) {
                    ModNetwork.sendToServer(new CarryRequestPacket(
                            CarryRequestPacket.PICK_UP_ENTITY, target.getId()));
                    return true;
                }
            }
            return false;
        }
        BlockHitResult hit = (BlockHitResult) minecraft.hitResult;
        // A placeable entity can sit in front of the block the ray also hits - a backpack against a
        // wall reports a BLOCK hit and the entity is never consulted. When lifting, prefer an entity
        // that is actually in front of that block face.
        if (!puttingDown) {
            net.minecraft.world.entity.Entity target = entityUnderCursor(minecraft);
            if (target != null) {
                double entityDistance = player.distanceToSqr(target.position());
                double blockDistance = player.getEyePosition(1.0F)
                        .distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(hit.getBlockPos()));
                if (entityDistance <= blockDistance + 1.0D) {
                    ModNetwork.sendToServer(new CarryRequestPacket(
                            CarryRequestPacket.PICK_UP_ENTITY, target.getId()));
                    return true;
                }
            }
        }
        if (puttingDown) {
            // Put it down at the clicked face, or at the player's feet when that spot is occupied -
            // the server walks outwards from there, so a refusal can never strand the player.
            BlockPos target = hit.getBlockPos().relative(hit.getDirection());
            ModNetwork.sendToServer(new CarryRequestPacket(CarryRequestPacket.PUT_DOWN, target));
        } else {
            ModNetwork.sendToServer(new CarryRequestPacket(CarryRequestPacket.PICK_UP, hit.getBlockPos()));
        }
        return true;
    }

    /** The entity the crosshair is on, if any. */
    @Nullable
    private static net.minecraft.world.entity.Entity entityUnderCursor(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }
        net.minecraft.world.phys.Vec3 eye = minecraft.player.getEyePosition(1.0F);
        net.minecraft.world.phys.Vec3 look = minecraft.player.getViewVector(1.0F);
        net.minecraft.world.phys.Vec3 end = eye.add(look.scale(5.0D));
        // Do NOT filter on isPickable(): Entity.isPickable() returns false by default and most
        // placeable modded entities never override it - a Sophisticated Backpacks backpack, a chair,
        // a display stand. That filter is what made the glove report "nothing to lift" while the
        // player was staring straight at a backpack. Anything with a real hitbox and no spectator
        // state is a legitimate target.
        net.minecraft.world.phys.AABB search = minecraft.player.getBoundingBox().inflate(5.0D);
        java.util.List<net.minecraft.world.phys.EntityHitResult> candidates = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity candidate : minecraft.level.getEntities(minecraft.player, search)) {
            if (candidate.isSpectator() || candidate instanceof LocalPlayer || candidate.isRemoved()) {
                continue;
            }
            // A pickable entity is preferred, but a non-pickable one is still liftable when the
            // ray actually strikes its bounding box.
            net.minecraft.world.phys.AABB box = candidate.getBoundingBox();
            java.util.Optional<net.minecraft.world.phys.Vec3> strike = box.clip(eye, end);
            if (strike.isEmpty()) {
                continue;
            }
            double distance = eye.distanceToSqr(strike.get());
            candidates.add(new net.minecraft.world.phys.EntityHitResult(candidate, strike.get()));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(java.util.Comparator.comparingDouble(
                hit -> eye.distanceToSqr(hit.getLocation())));
        return candidates.get(0).getEntity();
    }

    private CarryHandler() {
    }
}
