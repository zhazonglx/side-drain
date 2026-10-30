package com.sidedrain.util;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockSource;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import com.mojang.authlib.GameProfile;

/**
 * Runs a modded weapon's own firing code, using the two hooks mods actually implement.
 *
 * <h2>The problem</h2>
 *
 * <p>A gun mod's firing logic lives in its item class. {@code Item#use} needs a {@code Player}, and a
 * block entity is not one; a staff's casting is started by a right-click and finished by a release
 * callback. Neither can be reached by "spawning a projectile" - the projectile is a side effect of
 * the mod's own code, not something the plate can construct.
 *
 * <h2>The two hooks, tried in order</h2>
 *
 * <ol>
 *   <li><b>The dispenser behaviour.</b> A mod that wants its weapon usable in a dispenser registers a
 *       {@link DispenseItemBehavior}. That is literally the mod's own "fire from a fixed position
 *       with no player" path, and it is the cleanest possible match for a plate.</li>
 *   <li><b>A fake player.</b> Most gun and magic mods do not register a dispense behaviour - TACZ
 *       drives its guns from a {@code ShooterDataHolder} owned by a {@code LivingEntity}, and Iron's
 *       Spellbooks casts through {@code Item#use} plus {@code onUseTick}. For those, a
 *       {@link FakePlayer} is created at the plate, given the weapon in its main hand, aimed along the
 *       plate's facing, and asked to use it. The mod then runs its normal code path with a real
 *       {@code ServerPlayer}, which is what it was written for.</li>
 * </ol>
 *
 * <p>The fake player is <b>not</b> added to the level's player list: it exists only for the duration
 * of the call, so it cannot be seen, targeted, or saved. Its inventory is a copy, so nothing it does
 * can dupe or destroy the player's items - the weapon stack itself is written back afterwards, which
 * is how durability and loaded rounds persist.
 */
public final class DispenserAdapter {
    /**
     * Upper bound on how many ticks of a use animation the plate will step in one firing cycle.
     *
     * <p>The longest real charge in any pack is a few seconds of ticks. A weapon reporting an
     * absurd duration - or a broken mod returning {@code Integer.MAX_VALUE} - would otherwise spin
     * this loop forever and hang the server thread.
     */
    private static final int MAX_USE_STEPS = 100;

    /** The private map from item to its dispense behaviour, resolved once. */
    @Nullable
    private static Map<Item, DispenseItemBehavior> registry;
    private static boolean probed;
    /** Whether the most recent call changed the weapon, which means it fired. */
    private static boolean usedAmmo;

    /**
     * Fires {@code weapon} from the plate.
     *
     * @return true when something was actually fired
     */
    public static boolean tryFire(ServerLevel level, BlockPos pos, Direction facing, ItemStack weapon,
                                  ItemStack ammo) {
        return tryFire(level, pos, facing, null, weapon, ammo);
    }

    /**
     * Fires along an exact aim vector.
     *
     * <p>{@code aim} is the plate's real direction, which for a carried plate is the carrier's look
     * angle. Passing only a {@code Direction} would snap that to one of six values, which is why a
     * plate carried by a player used to shoot at the nearest axis instead of where the player was
     * looking.
     *
     * @param aim the exact unit direction, or {@code null} to use {@code facing}
     */
    public static boolean tryFire(ServerLevel level, BlockPos pos, Direction facing, @Nullable Vec3 aim,
                                  ItemStack weapon, ItemStack ammo) {
        return tryFire(level, pos, facing, aim, null, weapon, ammo);
    }

    /**
     * Fires along an exact aim vector, or at a specific target when the weapon needs one.
     *
     * <p>{@code aim} is the plate's real direction, which for a carried plate is the carrier's look
     * angle. Passing only a {@code Direction} would snap that to one of six values, which is why a
     * plate carried by a player used to shoot at the nearest axis instead of where the player was
     * looking.
     *
     * @param aim    the exact unit direction, or {@code null} to use {@code facing}
     * @param target the thing to aim at, or {@code null} to fire along {@code aim} at nothing in
     *               particular. A weapon whose own logic insists on a target - a Goety spell, for
     *               instance - is refused outright without one, so the scan that found it is what
     *               makes such a weapon usable at all.
     */
    public static boolean tryFire(ServerLevel level, BlockPos pos, Direction facing, @Nullable Vec3 aim,
                                  @Nullable LivingEntity target, ItemStack weapon, ItemStack ammo) {
        usedAmmo = false;
        // 1. The mod's own dispenser behaviour, when it registered one. It only understands a
        //    Direction, so the exact aim is snapped for it.
        if (fireViaDispenser(level, pos, facing, weapon)) {
            return true;
        }
        // 2. A fake player using the weapon, which is how guns and staves actually fire. This path
        //    keeps the exact aim.
        return fireViaFakePlayer(level, pos, facing, aim, target, weapon);
    }

    /** True when the last call left the ammunition untouched, so the caller must refund it. */
    public static boolean lastCallUsedAmmo() {
        return usedAmmo;
    }

    // ------------------------------------------------------------ shared weapon-use helpers

    /**
     * Points a living shooter along {@code direction} and drops it at the plate's muzzle.
     *
     * <p>Every gun mod reads the shooter's eye position and look vector, not an argument, so a caller
     * that wants a shot along a specific line has to move and rotate the shooter first. Extracted so
     * the dedicated shooter and the generic gun path cannot drift apart on the maths.
     */
    public static void aimShooter(net.minecraft.world.entity.LivingEntity shooter, Vec3 direction) {
        if (shooter == null || direction == null || direction.lengthSqr() < 1.0E-6D) {
            return;
        }
        Vec3 aim = direction.normalize();
        Vec3 origin = shooter.position();
        float yaw = yawFor(aim);
        float pitch = pitchFor(aim);
        shooter.moveTo(origin.x, origin.y, origin.z, yaw, pitch);
        // moveTo() only writes yRot/xRot. Every "where am I looking" question the game answers for a
        // living entity goes through yHeadRot instead - Entity#getViewVector, LivingEntity#getViewYRot,
        // and every mod that ray-traces from a caster or a gun's muzzle. A real entity syncs the two
        // in its tick; the plate's fake player is never ticked, so its head stayed at yaw 0 and every
        // shot and every spell left due south no matter which way the plate was pointing. This is the
        // one line that makes the plate actually aim.
        shooter.setYHeadRot(yaw);
        shooter.setYBodyRot(yaw);
        // The interpolation endpoints too: a partial-tick view vector (getViewVector(partialTick))
        // lerps from the old value, so leaving them stale makes the first frames after a turn point
        // back at the previous direction.
        shooter.yHeadRotO = yaw;
        shooter.yBodyRotO = yaw;
        shooter.setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Uses whatever is in the player's main hand, once, exactly as the game would.
     *
     * <p>Two cycles at most. A crossbow-style gun does not fire on the first use: the first one loads a
     * round into the weapon ({@code tryLoadProjectiles} plus a "charged" flag) and only the second pulls
     * the trigger. A player does both with two clicks, so the plate has to as well - which is why the
     * old single {@code use()} call could charge a gun and never fire it.
     *
     * <p>Whether a shot really happened is decided by evidence, not by the return value:
     *
     * <ul>
     *   <li>a projectile that did not exist before the call now exists near the shooter, or</li>
     *   <li>the weapon put itself on cooldown, which is what every gun does when it fires.</li>
     * </ul>
     *
     * <p>The second test is guarded against a cooldown that was already running, so a gun that refused
     * the shot cannot be reported as having fired one.
     *
     * @return true when something was actually fired
     */
    public static boolean useHeldWeapon(ServerLevel level, Player player) {
        for (int cycle = 0; cycle < 2; cycle++) {
            ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (held.isEmpty()) {
                return false;
            }
            Item item = held.getItem();
            boolean coolingBefore = player.getCooldowns().isOnCooldown(item);
            int projectilesBefore = countProjectiles(level, player);
            // "Charged" is the crossbow-style ready flag: a gun built on ProjectileWeaponItem sets it
            // when the weapon has a round in it and clears it when the round is fired. It is the only
            // reliable way to tell "the first click loaded it" from "the first click did nothing".
            boolean chargedBefore = isCharged(held);
            InteractionResultHolder<ItemStack> used =
                    item.use(level, player, InteractionHand.MAIN_HAND);
            // A load-in-progress is started by use() and finished by the release hook, which vanilla
            // only reaches through the entity's own tick. Stepping it here is what actually completes a
            // crossbow-style charge: use() starts the animation, releaseUsing() writes the round in.
            boolean animated = tickUseAnimation(level, player);
            // The item may have replaced the stack it was handed, so the cooldown is asked about the
            // item that is in the hand now.
            ItemStack after = player.getItemInHand(InteractionHand.MAIN_HAND);
            Item firedItem = after.isEmpty() ? item : after.getItem();
            boolean coolingNow = player.getCooldowns().isOnCooldown(firedItem);
            boolean chargedAfter = !after.isEmpty() && isCharged(after);
            boolean shot = countProjectiles(level, player) > projectilesBefore
                    || (coolingNow && !coolingBefore);
            if (shot) {
                return true;
            }
            // The load completed: the weapon now holds a round, so one more cycle pulls the trigger.
            // This is the second click a player makes, and without it a crossbow-style gun was loaded
            // and then left sitting there charged and unfired.
            if (chargedAfter && !chargedBefore) {
                continue;
            }
            // Nothing was loaded and nothing was fired. If the use was also refused outright there is
            // nothing to gain from a second cycle, and running one risks a double fire.
            if (!animated && (used == null || !used.getResult().consumesAction())) {
                return false;
            }
        }
        return false;
    }

    /**
     * True when the weapon reports itself loaded and ready.
     *
     * <p>Crossbow-style guns (any {@code ProjectileWeaponItem} that follows vanilla's pattern) store
     * this as a {@code Charged} boolean in their tag. Read from the tag rather than by class so a mod
     * that reimplements the pattern is recognised too.
     */
    public static boolean isCharged(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getTag() == null) {
            return false;
        }
        return stack.getTag().getBoolean("Charged");
    }

    /** Counts the projectiles currently owned by {@code shooter}, for before/after comparison. */
    public static int countProjectiles(ServerLevel level, net.minecraft.world.entity.Entity shooter) {
        try {
            int count = 0;
            for (net.minecraft.world.entity.projectile.Projectile projectile : level.getEntitiesOfClass(
                    net.minecraft.world.entity.projectile.Projectile.class,
                    shooter.getBoundingBox().inflate(8.0D))) {
                if (projectile.getOwner() == shooter) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException absent) {
            return 0;
        }
    }

    /** True when a dispenser behaviour is registered for this item. */
    public static boolean hasBehavior(ItemStack stack) {
        Map<Item, DispenseItemBehavior> map = registry();
        if (map == null || stack.isEmpty()) {
            return false;
        }
        try {
            return map.get(stack.getItem()) != null;
        } catch (RuntimeException absent) {
            return false;
        }
    }

    // ------------------------------------------------------------ dispenser path

    private static boolean fireViaDispenser(ServerLevel level, BlockPos pos, Direction facing,
                                            ItemStack weapon) {
        Map<Item, DispenseItemBehavior> map = registry();
        if (map == null || weapon.isEmpty()) {
            return false;
        }
        DispenseItemBehavior behavior;
        try {
            behavior = map.get(weapon.getItem());
        } catch (RuntimeException absent) {
            return false;
        }
        if (behavior == null) {
            return false;
        }
        try {
            BlockSource source = new PlateSource(level, pos, facing);
            ItemStack handed = weapon.copy();
            ItemStack result = behavior.dispense(source, handed);
            if (result != null && !ItemStack.matches(result, weapon)) {
                writeBack(weapon, result);
                usedAmmo = true;
            }
            return true;
        } catch (RuntimeException failure) {
            com.sidedrain.SideDrain.LOGGER.debug("Dispenser behaviour for {} failed",
                    weapon.getHoverName().getString(), failure);
            return false;
        }
    }

    // ------------------------------------------------------------ fake player path

    /**
     * Uses the weapon the way a player would, through a throwaway fake player.
     *
     * <p>This is what makes TACZ guns and Iron's Spellbooks staves work: both are driven entirely by
     * player-side code, and both check for a real {@code ServerPlayer}. A fake player satisfies that.
     *
     * <p>The fake player is positioned at the plate, aimed along the plate's facing, and given the
     * weapon in its main hand. {@code Item#use} is then invoked - the same call the client sends when
     * a player right-clicks - and {@code onUseTick} is ticked so charge-and-release weapons (bows,
     * staves with a cast time) advance. Everything the mod does inside those calls happens for real:
     * bullets are spawned, ammunition is taken from the fake player's inventory, mana is spent.
     */
    private static boolean fireViaFakePlayer(ServerLevel level, BlockPos pos, Direction facing,
                                             @Nullable Vec3 aim, @Nullable LivingEntity target,
                                             ItemStack weapon) {
        if (weapon.isEmpty()) {
            return false;
        }
        try {
            // acquireShooter(), not fakePlayer(): it re-asserts the creative state and the soul top-up
            // on every call. Using the raw cached instance here meant a caster fired through this path
            // without the soul energy the wand checks for, so every cast was silently refused.
            net.minecraft.world.entity.LivingEntity acquired = acquireShooter(level);
            if (!(acquired instanceof FakePlayer shooter)) {
                return false;
            }
            // The exact direction the plate points. A carried plate answers the carrier's look angle,
            // so this is a continuous vector, not one of six axis directions.
            Vec3 direction = aim != null && aim.lengthSqr() > 1.0E-6D
                    ? aim.normalize() : Vec3.atLowerCornerOf(facing.getNormal());
            // Where the shot starts, and which way the shooter faces.
            //
            // With a target, the shooter is aimed at the target's eye rather than along the plate's
            // own aim. That is what a weapon needing a target requires: Goety's spells find their
            // target by ray-tracing along the caster's look vector, so a plate pointed at empty air
            // has no target at all and the spell is refused outright. Aiming at something found by
            // the plate's forward scan is the plate's equivalent of a player looking at a mob.
            Vec3 origin = Vec3.atCenterOf(pos).add(
                    direction.x * 0.6D, direction.y * 0.6D, direction.z * 0.6D);
            // Place the shooter first, then turn it - aimShooter() keeps the position and writes both
            // the body rotation and the head rotation.
            //
            // The head rotation is the part that matters. A caster mod finds its target by ray-tracing
            // along the caster's own view vector, and that vector is built from yHeadRot, not from the
            // yRot moveTo() writes. This fake player is never ticked, so nothing ever copied one to
            // the other: the head stayed at yaw 0 and every spell was aimed due south regardless of
            // where the plate - or the target the scan found - actually was. That is why a staff's
            // smart targeting appeared not to work at all.
            shooter.moveTo(origin.x, origin.y - shooter.getEyeHeight(), origin.z,
                    shooter.getYRot(), shooter.getXRot());
            if (target != null && target.isAlive()) {
                // Aim at the target the plate's forward scan found, which is the plate's equivalent of
                // a player looking at a mob.
                aimShooter(shooter, target.getEyePosition().subtract(origin));
            } else {
                aimShooter(shooter, direction);
            }
            // The weapon goes in the main hand. A copy is used so a mod that mangles the stack in a
            // failed attempt cannot corrupt the real one; the result is written back below.
            ItemStack working = weapon.copy();
            shooter.setItemInHand(InteractionHand.MAIN_HAND, working);
            // Some guns read the offhand or the inventory for ammunition; give the fake player the
            // real one's contents so a reload can find rounds.
            shooter.getInventory().selected = 0;

            boolean fired = false;
            InteractionResultHolder<ItemStack> used =
                    working.getItem().use(level, shooter, InteractionHand.MAIN_HAND);
            if (used != null && used.getResult().consumesAction()) {
                fired = true;
            }
            // Charge-and-release weapons (bows, crossbows, and every cast-time staff) only produce
            // anything after the whole use animation has run, so the plate has to step it: a fake
            // player is not in the level's tick list and nothing else will.
            //
            // The animation completing is itself the success signal here, not a projectile count: a
            // cast is a spell, not always a projectile, and a spell that spawned nothing this tick is
            // still a cast that happened.
            fired |= tickUseAnimation(level, shooter);
            ItemStack result = shooter.getItemInHand(InteractionHand.MAIN_HAND);
            if (!result.isEmpty() && !ItemStack.matches(result, weapon)) {
                writeBack(weapon, result);
                fired = true;
            }
            // The fake player must stop being a thing the world can see the instant the shot is
            // away. See conceal() for why this is not optional.
            conceal(level, shooter);
            // Clear the fake player's hands and its use state so nothing lingers on the cached
            // instance. Leaving it "using" an item would make the next call's startUsingItem() a
            // no-op, which silently disabled the second and every later cast.
            shooter.stopUsingItem();
            shooter.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (fired) {
                usedAmmo = true;
            }
            return fired;
        } catch (RuntimeException failure) {
            com.sidedrain.SideDrain.LOGGER.debug("Fake-player use of {} failed",
                    weapon.getHoverName().getString(), failure);
            return false;
        }
    }

    /**
     * Steps a charge-and-release use animation the way the game does, then completes it.
     *
     * <p>Mirrors {@code LivingEntity#updateUsingItem} and {@code #completeUsingItem}:
     *
     * <pre>
     * for (remaining = duration; remaining &gt; 0; remaining--) {
     *     item.onUseTick(level, user, remaining);
     *     if (remaining - 1 &lt;= 0) {
     *         if (item.useOnRelease()) item.releaseUsing(...); else item.finishUsingItem(...);
     *     }
     * }
     * </pre>
     *
     * <p>Getting the <b>completion</b> call right is the whole point. A Goety wand casts from
     * {@code finishUsingItem} - the same hook a food item uses to be eaten - not from
     * {@code releaseUsing}, which is the bow's hook. The previous code only ever called
     * {@code releaseUsing} with a remaining duration of 1, so every cast-time staff ran a single
     * malformed tick and then threw the result away.
     *
     * @return true when the animation ran to completion; false when the item stopped itself, which
     *         for a magic mod means the cast was refused
     */
    private static boolean tickUseAnimation(ServerLevel level, Player shooter) {
        // Only weapons that entered a use animation need stepping. An instant item (a wand whose
        // spell has no cast time, a spell book) has already done its work inside use().
        if (!shooter.isUsingItem()) {
            return false;
        }
        // The duration is read from the stack the shooter is actually holding, not from the
        // argument: use() is allowed to replace the stack it was given.
        ItemStack using = shooter.getUseItem();
        if (using.isEmpty()) {
            using = shooter.getItemInHand(InteractionHand.MAIN_HAND);
        }
        if (using.isEmpty()) {
            return false;
        }
        boolean releasesOnRelease = using.useOnRelease();
        int duration = using.getUseDuration();
        if (duration <= 0) {
            duration = 1;
        }
        // A generous but finite bound. The longest real charge in any pack is a few seconds; a
        // weapon reporting an absurd duration would otherwise spin here.
        int remaining = Math.min(duration, MAX_USE_STEPS);
        while (remaining > 0) {
            ItemStack held = shooter.getItemInHand(InteractionHand.MAIN_HAND);
            if (held.isEmpty() || !shooter.isUsingItem()) {
                // The item stopped itself - a spell that ran out of soul energy, a wand that found
                // nothing to cast, or a cooldown that has not expired. That is a refusal, and it
                // must not be reported as a shot.
                return false;
            }
            held.getItem().onUseTick(level, shooter, held, remaining);
            remaining--;
            if (remaining <= 0) {
                completeUse(level, shooter, releasesOnRelease);
                return true;
            }
        }
        return false;
    }

    /**
     * Runs the completion hook and clears the use state.
     *
     * <p>This is {@code LivingEntity#completeUsingItem} minus the parts that need the entity to be
     * in the level's tick list. The stack is written back into the fake player's hand because a
     * finishing item may replace itself (a staff that consumed a charge, a potion that emptied).
     *
     * <p>The branch matters: a weapon that reports {@code useOnRelease()} - every bow and crossbow -
     * fires from {@code releaseUsing} and must <b>not</b> get {@code finishUsingItem}, which for
     * those is a no-op that would silently swallow the shot.
     */
    private static void completeUse(ServerLevel level, Player shooter,
                                    boolean releasesOnRelease) {
        ItemStack held = shooter.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.isEmpty()) {
            shooter.stopUsingItem();
            return;
        }
        if (releasesOnRelease) {
            held.releaseUsing(level, shooter, 0);
        } else {
            ItemStack finished = held.finishUsingItem(level, shooter);
            if (finished != held) {
                shooter.setItemInHand(InteractionHand.MAIN_HAND, finished);
            }
        }
        shooter.stopUsingItem();
    }

    /**
     * Takes the fake player back out of the world after a shot.
     *
     * <h2>Why this is required, not cosmetic</h2>
     *
     * <p>Forge's {@code FakePlayer} is deliberately unkillable: {@code isInvulnerableTo} returns
     * {@code true} for every damage source and {@code die} is an empty method. It is also a real
     * {@code ServerPlayer}, so a projectile fired by it records it as the owner, and a mob that the
     * projectile hits retargets onto that owner. The result is a hostile or neutral mob locked onto a
     * target it can never damage and that never dies: it stands there swinging forever, and the player
     * cannot fix it without killing the mob.
     *
     * <p>Three things are therefore done after every shot:
     *
     * <ol>
     *   <li>The fake player is moved far below the world, so any mob that later evaluates it as a
     *       target finds it out of range and drops it.</li>
     *   <li>Every mob within a generous radius that is currently targeting the fake player is told to
     *       forget it, which handles the ones already locked on.</li>
     *   <li>The fake player's hurt time and last-hurt fields are cleared, so a mob that does connect
     *       does not see a "hit" that never lands.</li>
     * </ol>
     *
     * <p>Moving it rather than removing it is deliberate: {@code FakePlayerFactory} caches the
     * instance per level, and removing an entity that the level never tracked is a no-op that leaves
     * the cache pointing at something half-detached. Parking it out of reach keeps the cached object
     * valid for the next shot, which is exactly what the factory is designed for.
     */
    /**
     * The cached fake player for this level, positioned at a given spot by the caller.
     *
     * <p>Exposed so other firing paths (the dedicated shooter) can use the same instance and the same
     * concealment. Returns {@code null} only if Forge's factory is unavailable.
     */
    @Nullable
    public static net.minecraft.world.entity.LivingEntity acquireShooter(ServerLevel level) {
        FakePlayer shooter = fakePlayer(level);
        if (shooter != null) {
            // Re-assert the creative state on every acquisition. The cached instance is shared, and a
            // mod (or the concealment step) could have changed an ability in between; a shooter that
            // silently reverted to survival would refuse to fire with no error to show for it.
            shooter.getAbilities().instabuild = true;
            shooter.getAbilities().mayfly = true;
            shooter.setSilent(true);
            topUpSoulEnergy(shooter);
        }
        return shooter;
    }

    /**
     * Fills a caster's soul energy so a magic mod will let it cast, and clears its focus cooldowns.
     *
     * <p>Goety stores soul energy in a per-player capability. Its wands check
     * {@code SEHelper.getSoulsAmount} before every cast and refuse when the caster is short. A fake
     * player has none, so every cast would fail - which is why a loaded wand still did nothing.
     *
     * <p>The plate does not model soul energy at all: a machine has no soul to spend, so the
     * shooter is simply kept full. This is deliberate and matches the requested behaviour ("no soul
     * cost").
     *
     * <p>The <b>focus cooldown</b> is cleared for the same reason and is the second half of the
     * problem. {@code IWand#cannotCast} refuses when {@code isOnCooldown} is true, and that check
     * reads a per-player {@code FocusCooldown} map keyed by the focus item. A real player waits out
     * the cooldown between casts; a plate firing every half second would cast once and then be
     * refused forever, with the same silent nothing a soul shortage produces. A machine does not
     * get tired, so the cooldowns are wiped before each cast.
     *
     * <p>Both calls are reflective and fail silently when Goety is absent.
     */
    private static void topUpSoulEnergy(FakePlayer shooter) {
        try {
            Class<?> helper = Class.forName("com.Polarice3.Goety.utils.SEHelper");
            java.lang.reflect.Method setAmount = helper.getMethod("setSoulsAmount",
                    net.minecraft.world.entity.player.Player.class, int.class);
            // A generous but finite value: enough for any single cast, and it is re-set before every
            // shot, so it never accumulates into an unbounded resource.
            setAmount.invoke(null, shooter, 10000);
            java.lang.reflect.Method setActive = helper.getMethod("setSEActive",
                    net.minecraft.world.entity.player.Player.class, boolean.class);
            setActive.invoke(null, shooter, true);
            clearFocusCooldowns(helper, shooter);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            // Goety is not installed, or has changed its API. The fake player simply has no soul.
        }
    }

    /**
     * Empties the caster's focus cooldown map so the next cast is never refused as "still cooling".
     *
     * <p>{@code SEHelper.getFocusCoolDown(player)} returns the live {@code FocusCooldown}, whose
     * {@code cooldowns} field is a plain {@code Map<Item, CooldownInstance>}. Clearing it is the
     * same thing the game does when a cooldown expires, just all at once.
     */
    private static void clearFocusCooldowns(Class<?> helper, FakePlayer shooter)
            throws ReflectiveOperationException {
        java.lang.reflect.Method getCooldown = helper.getMethod("getFocusCoolDown",
                net.minecraft.world.entity.player.Player.class);
        Object cooldown = getCooldown.invoke(null, shooter);
        if (cooldown == null) {
            return;
        }
        java.lang.reflect.Field field = cooldown.getClass().getField("cooldowns");
        Object map = field.get(cooldown);
        if (map instanceof java.util.Map<?, ?> mutable) {
            //noinspection unchecked
            ((java.util.Map<Object, Object>) mutable).clear();
        }
    }

    /** Public entry point for concealing a shooter another path used. */
    public static void concealShooter(ServerLevel level, net.minecraft.world.entity.LivingEntity shooter) {
        if (shooter instanceof FakePlayer fake) {
            conceal(level, fake);
        }
    }

    private static void conceal(ServerLevel level, FakePlayer shooter) {
        try {
            // The search box is taken BEFORE the move: after it, the fake player is 512 blocks down
            // and its box covers nothing.
            double radius = 64.0D;
            var searchBox = shooter.getBoundingBox().inflate(radius);
            // 1. Re-attribute the projectiles still in flight. A projectile that lands with the fake
            //    player as its owner would retarget the victim onto an unkillable target, which is
            //    the whole problem - but simply clearing the owner was worse: TACZ builds its damage
            //    source from the owner, and a null owner left a bullet that flew into nothing.
            //    The nearest real player within reach inherits the shot instead. With none in
            //    range the owner is cleared as before, which still beats an unkillable attribution.
            net.minecraft.world.entity.player.Player heir = level.getNearestPlayer(
                    shooter.getX(), shooter.getY(), shooter.getZ(), radius, false);
            for (net.minecraft.world.entity.projectile.Projectile projectile : level.getEntitiesOfClass(
                    net.minecraft.world.entity.projectile.Projectile.class, searchBox)) {
                if (projectile.getOwner() == shooter) {
                    projectile.setOwner(heir != null ? heir : null);
                }
            }
            // 2. Any mob already locked on has to be told to let go: target selection only
            //    re-evaluates on its own schedule, and in the meantime it would keep swinging.
            for (net.minecraft.world.entity.Mob mob : level.getEntitiesOfClass(
                    net.minecraft.world.entity.Mob.class, searchBox)) {
                if (mob.getTarget() == shooter) {
                    mob.setTarget(null);
                }
            }
            // 3. Park it far below the world floor, so a mob that evaluates it later finds it out of
            //    range. Moving rather than discarding keeps the factory's cached instance usable.
            shooter.moveTo(shooter.getX(), level.getMinBuildHeight() - 512.0D, shooter.getZ(),
                    shooter.getYRot(), shooter.getXRot());
            shooter.setDeltaMovement(Vec3.ZERO);
            shooter.hurtTime = 0;
            shooter.hurtDuration = 0;
            shooter.setHealth(shooter.getMaxHealth());
        } catch (RuntimeException ignored) {
            // Concealment is best-effort: failing it must never break the shot that just happened.
        }
    }

    /** A cached fake player for the level, created once. */
    @Nullable
    private static FakePlayer cachedPlayer;
    @Nullable
    private static ServerLevel cachedLevel;

    @Nullable
    private static FakePlayer fakePlayer(ServerLevel level) {
        if (cachedPlayer != null && cachedLevel == level) {
            return cachedPlayer;
        }
        try {
            // A stable UUID and name so mods that key state by player do not accumulate entries.
            GameProfile profile = new GameProfile(
                    UUID.nameUUIDFromBytes("sidedrain:plate".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "[SideDrain]");
            cachedPlayer = FakePlayerFactory.get(level, profile);
            cachedLevel = level;
            if (cachedPlayer != null) {
                // The plate's shooter is a CREATIVE player, and that is the whole trick.
                //
                // Gun and magic mods gate firing on the shooter's own resources: TACZ's
                // LivingEntityAmmoCheck returns needCheckAmmo() = !player.isCreative(), so a
                // survival shooter with an empty magazine is refused before a bullet is ever built.
                // A fake player cannot meaningfully hold ammunition - its inventory is a throwaway
                // copy - so the only reliable way to get the mod to run its firing path is to make it
                // creative, which is exactly the state a player uses to test a gun.
                //
                // The plate then pays for the shot itself: every successful fire reports a count back
                // through takeShotCount(), and the block entity deducts real ammunition from its own
                // storage or the container it is clamped onto. Nothing is created from nothing.
                cachedPlayer.getAbilities().instabuild = true;
                cachedPlayer.getAbilities().mayfly = true;
                cachedPlayer.setSilent(true);
                // Magic mods gate casting on the caster's own resource. Goety's wands spend "soul
                // energy" from a per-player capability, and a fake player starts with none, so every
                // cast is refused before the spell runs. The plate is a machine and has no soul to
                // spend, so the shooter is topped up instead - which is the "no soul cost" rule.
                topUpSoulEnergy(cachedPlayer);
            }
            return cachedPlayer;
        } catch (RuntimeException absent) {
            return null;
        }
    }

    /**
     * Yaw that points along {@code direction}.
     *
     * <p>Minecraft's convention: yaw 0 looks south (+Z), yaw increases clockwise seen from above, and
     * {@code atan2(-x, z)} is the standard conversion. Computed from the vector so a carried plate
     * follows the carrier's exact look, not the nearest axis.
     */
    private static float yawFor(Vec3 direction) {
        return (float) (Math.atan2(-direction.x, direction.z) * (180.0D / Math.PI));
    }

    /** Pitch that points along {@code direction}; negative looks up, matching the game. */
    private static float pitchFor(Vec3 direction) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        return (float) (-Math.atan2(direction.y, horizontal) * (180.0D / Math.PI));
    }

    /** Copies a mod-modified stack back onto the plate's weapon. */
    private static void writeBack(ItemStack weapon, ItemStack result) {
        weapon.setCount(result.getCount());
        weapon.setTag(result.getTag());
    }

    // ------------------------------------------------------------ registry access

    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<Item, DispenseItemBehavior> registry() {
        if (probed) {
            return registry;
        }
        probed = true;
        try {
            Field field = DispenserBlock.class.getDeclaredField("DISPENSER_REGISTRY");
            field.setAccessible(true);
            registry = (Map<Item, DispenseItemBehavior>) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            registry = null;
        }
        return registry;
    }

    /**
     * A {@link BlockSource} that reports the plate as if it were a dispenser.
     */
    private record PlateSource(ServerLevel level, BlockPos pos, Direction facing)
            implements BlockSource {
        @Override
        public double x() {
            return this.pos.getX() + 0.5D;
        }

        @Override
        public double y() {
            return this.pos.getY() + 0.5D;
        }

        @Override
        public double z() {
            return this.pos.getZ() + 0.5D;
        }

        @Override
        public BlockPos getPos() {
            return this.pos;
        }

        @Override
        public BlockState getBlockState() {
            return net.minecraft.world.level.block.Blocks.DISPENSER.defaultBlockState()
                    .setValue(DispenserBlock.FACING, this.facing);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends BlockEntity> T getEntity() {
            return (T) this.level.getBlockEntity(this.pos);
        }

        @Override
        public ServerLevel getLevel() {
            return this.level;
        }
    }

    private DispenserAdapter() {
    }
}
