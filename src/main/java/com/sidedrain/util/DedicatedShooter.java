package com.sidedrain.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Fires a gun whose mod keeps its own shooting state, instead of using {@code Item#use}.
 *
 * <h2>Why {@code use()} cannot fire a gun</h2>
 *
 * <p>A gun mod cannot work through {@code Item#use}: a gun fires repeatedly while the trigger is
 * held, its fire rate is in milliseconds, and it tracks heat, recoil and reload state. So these mods
 * bypass the vanilla item hooks and run a per-entity operator object instead.
 *
 * <p>TACZ is the pack's gun mod. Its entry point is
 * {@code com.tacz.guns.api.entity.IGunOperator#fromLivingEntity}, which hands back an operator bound
 * to a living entity. The operator then needs <b>three</b> calls before a bullet exists:
 *
 * <ol>
 *   <li>{@code initialData()} - set up the operator's caches.</li>
 *   <li>{@code draw(Supplier<ItemStack>)} - <b>raise the gun</b>. Shooting without drawing returns
 *       {@code NOT_DRAW} and does nothing at all.</li>
 *   <li>{@code shoot(spread, speed)} - fire.</li>
 * </ol>
 *
 * <h2>The draw cooldown trap</h2>
 *
 * <p>Step 2 is not enough on its own. {@code draw()} stamps {@code drawTimestamp} with
 * {@code System.currentTimeMillis()}, and {@code shoot()} begins by asking
 * {@code LivingEntityDrawGun#getDrawCoolDown()}, which answers
 * {@code drawTime * 1000 - (now - drawTimestamp) - 5}. Called back-to-back, that is
 * {@code drawTime * 1000 - 5} - a positive number - so the shot is refused with
 * {@code IS_DRAWING} no matter how much ammunition is loaded. A real player waits out the draw
 * animation; a block entity cannot, and a plate that "waits" would fire one round per second of
 * real time.
 *
 * <p>So after {@code draw()} the operator's {@code drawTimestamp} is back-dated by
 * {@link #DRAW_BACKDATE_MS}. The gun is then in the same state as one that was raised a minute ago:
 * fully drawn, no draw cooldown. This is the single change that makes every TACZ gun fire.
 *
 * <h2>Ammunition</h2>
 *
 * <p>{@code LivingEntityAmmoCheck#needCheckAmmo()} answers {@code !player.isCreative()}, so making
 * the shooter creative skips the "does this player own any rounds" question entirely - that is the
 * state a player uses to test a gun.
 *
 * <p>Creative is <b>not</b> enough by itself. {@code LivingEntityShoot#shoot} additionally requires
 * {@code IGun#getCurrentAmmoCount(stack) + (hasBulletInBarrel ? 1 : 0) >= 1} for every magazine-fed
 * gun, and returns {@code NO_AMMO} when the gun's own magazine is empty. A gun pulled out of a chest
 * has an empty magazine, so it still refused to fire - the "the gun has bullets and it still will not
 * shoot" report. The magazine is therefore topped up from the mod's own API before firing, and the
 * rounds actually consumed are measured afterwards.
 *
 * <p>Nothing is created from nothing: {@link #takeShotCount()} reports the rounds the shot really
 * spent, and the plate removes that many real rounds from its own storage or the attached container.
 */
public final class DedicatedShooter {
    /**
     * How far into the past the draw timestamp is pushed after {@code draw()}.
     *
     * <p>Comfortably longer than any gun's {@code drawTime} (a heavy machine gun is under two
     * seconds), so the draw cooldown is always zero, and short enough that nothing overflows.
     */
    private static final long DRAW_BACKDATE_MS = 60_000L;

    /** True once the probe has run. */
    private static boolean probed;
    @Nullable
    private static Class<?> operatorClass;
    @Nullable
    private static Class<?> holderClass;
    @Nullable
    private static Class<?> gunClass;
    @Nullable
    private static Method fromLivingEntity;
    @Nullable
    private static Method initialData;
    @Nullable
    private static Method draw;
    @Nullable
    private static Method shoot;
    @Nullable
    private static Method shootNoTimestamp;
    @Nullable
    private static Method getDataHolder;
    /** {@code IGun.getIGunOrNull(ItemStack)} - null for anything that is not a gun. */
    @Nullable
    private static Method gunOrNull;
    @Nullable
    private static Method setCurrentAmmoCount;
    @Nullable
    private static Field drawTimestampField;

    /** Shots the last {@link #fire} actually produced, so the plate can bill the player for them. */
    private static int lastShotCount;

    /**
     * Fires {@code weapon} using the mod's operator, if it has one.
     *
     * @param shooter the living entity doing the shooting; should already hold {@code weapon}
     * @return true when a shot was fired
     */
    public static boolean fire(LivingEntity shooter, ItemStack weapon) {
        return fire(shooter, weapon, shooter == null ? null : shooter.getLookAngle());
    }

    /**
     * Fires {@code weapon} along {@code aim}.
     *
     * <h2>Why the aim has to be passed in</h2>
     *
     * <p>{@code IGunOperator#shoot} takes two {@code Supplier<Float>} arguments, and they are the
     * <b>pitch and yaw</b> the shot is built from - TACZ hands them straight to
     * {@code ModernKineticGunScriptAPI#setPitchSupplier/setYawSupplier}, and its own client sends the
     * player's current look angles. Passing constants there (which this class did: {@code 0.0F} and
     * {@code 1.0F}) meant every shot was built from a fixed pitch of 0 and yaw of 1 degree, so a
     * bullet flew due south no matter where the plate pointed, and no amount of rotating the shooter
     * entity could change it.
     *
     * <p>So the plate's real aim vector is converted to those two angles here, and the suppliers
     * return them. The shooter entity is still aimed too - some of the mod's other code reads the
     * entity - but the angles are what the bullet is actually made from.
     */
    public static boolean fire(LivingEntity shooter, ItemStack weapon, @Nullable Vec3 aim) {
        lastShotCount = 0;
        if (!isAvailable() || weapon.isEmpty()) {
            return false;
        }
        try {
            return fireWithOperator(shooter, weapon, aim);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            com.sidedrain.SideDrain.LOGGER.info("Dedicated shooter threw", failure);
            return false;
        }
    }

    /** Yaw and pitch (in that order) that point along {@code aim}; the game's own conventions. */
    private static float[] anglesFor(@Nullable Vec3 aim) {
        if (aim == null || aim.lengthSqr() < 1.0E-6D) {
            return new float[] {0.0F, 0.0F};
        }
        Vec3 dir = aim.normalize();
        float yaw = (float) (Math.atan2(-dir.x, dir.z) * (180.0D / Math.PI));
        float horizontal = (float) Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float pitch = (float) (-Math.atan2(dir.y, horizontal) * (180.0D / Math.PI));
        return new float[] {yaw, pitch};
    }

    private static boolean fireWithOperator(LivingEntity shooter, ItemStack weapon, @Nullable Vec3 aim)
            throws ReflectiveOperationException {
        Object operator = fromLivingEntity.invoke(null, shooter);
        if (operator == null) {
            return false;
        }
        // 1. Caches. Cheap, and the operator expects it once per use.
        try {
            initialData.invoke(operator);
        } catch (RuntimeException ignored) {
            // Not fatal: some builds do this lazily.
        }
        // 2. Draw the gun. Without this the shoot call returns NOT_DRAW and nothing happens.
        draw.invoke(operator, (java.util.function.Supplier<ItemStack>) () -> weapon);
        // 2b. Clear the draw cooldown the draw just started. See the class comment: without this
        //     every shot is refused with IS_DRAWING and the gun looks broken.
        clearDrawCooldown(operator);
        // 2c. The gun's own magazine is the ammunition. A player loads it, and those rounds are real -
        //     they were put there by hand. The plate must NOT invent a magazine: the old code filled
        //     the gun to thirty rounds before every shot and then "billed" the plate for them, which
        //     meant a loaded gun sitting in a plate with no loose ammo beside it refused to fire at
        //     all, and a gun with a full magazine was reported as "out of ammunition" the moment the
        //     chest ran dry.
        //
        //     Nothing is created here: whatever the magazine holds is what was loaded into it, and the
        //     shot spends it. Only when the magazine is genuinely empty does the caller get a chance to
        //     feed it - that is the reload path, and it is billed one round per round.
        int before = GunAmmo.loadedRounds(weapon);
        if (before < 1) {
            // Nothing chambered. Leave it to the caller: a gun with no round in it cannot fire, and
            // filling it here would be manufacturing ammunition.
            com.sidedrain.SideDrain.LOGGER.debug("Gun has an empty magazine: {}",
                    weapon.getHoverName().getString());
            return false;
        }
        // 3. Fire. The two suppliers are the pitch and yaw the bullet is built from - see the class
        //    comment. They are the plate's real aim, converted to the game's angle conventions, and
        //    they are supplied fresh on every call so a rotated plate fires where it points.
        //
        //    The no-timestamp overload is preferred: it passes the operator's own clock, which is what
        //    a player's trigger pull does - and it is the only overload that satisfies the mod's
        //    network-sync timestamp window.
        float[] angles = anglesFor(aim);
        float yaw = angles[0];
        float pitch = angles[1];
        Object result = shootNoTimestamp != null
                ? shootNoTimestamp.invoke(operator,
                        (java.util.function.Supplier<Float>) () -> pitch,
                        (java.util.function.Supplier<Float>) () -> yaw)
                : shoot.invoke(operator,
                        (java.util.function.Supplier<Float>) () -> pitch,
                        (java.util.function.Supplier<Float>) () -> yaw,
                        System.currentTimeMillis());
        if (result == null) {
            return false;
        }
        String name = result.toString();
        // SUCCESS is the only state that produced a bullet. The others are diagnostics: NOT_DRAW
        // means the draw above did not take, NO_AMMO means the magazine was empty, IS_DRAWING means
        // the draw cooldown above did not clear, and so on.
        if (!name.equals("SUCCESS")) {
            // Logged at INFO, not DEBUG. Forge's default log level is INFO, so a DEBUG line is
            // discarded before anyone can read it - which is exactly why an earlier attempt left no
            // evidence of what went wrong. A weapon that will not fire is a user-visible failure and
            // deserves a line the player can find and send.
            com.sidedrain.SideDrain.LOGGER.info("Gun did not fire: {} ({})", name,
                    weapon.getHoverName().getString());
            return false;
        }
        // SUCCESS is not proof that a bullet exists. TACZ builds its projectile inside a
        // BooleanSupplier that it hands to CycleTaskHelper#addCycleTask, and that supplier bails out
        // - returning false, having created nothing - when the shooter is dead, or when the gun is
        // no longer the item in the shooter's main hand. So "SUCCESS" plus a live bullet has to be
        // verified separately: bill only what the world actually received.
        int bullets = countFreshBullets(shooter);
        if (bullets <= 0) {
            com.sidedrain.SideDrain.LOGGER.info(
                    "Gun reported SUCCESS but spawned no bullet: {}", weapon.getHoverName().getString());
            // Put the magazine back the way it was: nothing left the barrel, so nothing is owed.
            restoreRounds(weapon, before);
            return false;
        }
        // How many rounds the shot really spent, read back from the gun rather than assumed.
        int after = GunAmmo.loadedRounds(weapon);
        int spent = Math.max(before - after, bullets);
        // The mod may have skipped its own deduction (creative). Take the rounds out ourselves so the
        // magazine really is one round shorter - the gun's ammunition is finite, and a plate that
        // never decremented it would be an infinite-ammo exploit dressed up as a magazine.
        GunAmmo.deductRounds(weapon, spent);
        lastShotCount = spent;
        com.sidedrain.SideDrain.LOGGER.info("Gun fired: {} ({} bullet(s), {} round(s))",
                weapon.getHoverName().getString(), bullets, lastShotCount);
        return true;
    }

    /**
     * Counts the bullets this shot put into the world.
     *
     * <p>A TACZ shot is an {@code EntityKineticBullet} owned by the shooter, spawned at the shooter's
     * eye. Counting them around the shooter immediately after the shoot call is the only reliable
     * evidence that a projectile exists - the return value alone is not, because the mod's deferred
     * task can decline to build one while the outer call still reports success.
     *
     * <p>Done by class name so no compile-time dependency on the gun mod is needed.
     */
    private static int countFreshBullets(LivingEntity shooter) {
        try {
            if (shooter.level() == null) {
                return 0;
            }
            // The bullet starts at the eye and travels fast, so the box has to be generous enough to
            // catch it in the tick it was created. It is created at the eye position, so a tight box
            // around the shooter is enough - the projectile has not moved yet.
            var box = shooter.getBoundingBox().inflate(4.0D);
            int count = 0;
            for (var entity : shooter.level().getEntitiesOfClass(
                    net.minecraft.world.entity.Entity.class, box)) {
                String type = entity.getClass().getName();
                if (type.equals("com.tacz.guns.entity.EntityKineticBullet")
                        && entity.tickCount <= 1) {
                    count++;
                }
            }
            return count;
        } catch (RuntimeException absent) {
            return 0;
        }
    }

    /** Puts {@code rounds} back into the gun's magazine after a shot that produced nothing. */
    private static void restoreRounds(ItemStack weapon, int rounds) {
        if (rounds <= 0 || setCurrentAmmoCount == null) {
            return;
        }
        try {
            Object gun = gunOrNull == null ? null : gunOrNull.invoke(null, weapon);
            if (gun != null) {
                setCurrentAmmoCount.invoke(gun, weapon, rounds);
            }
        } catch (ReflectiveOperationException | RuntimeException absent) {
            // Best effort: the plate is about to stop billing anyway, so a stale magazine count is
            // not worth failing the shot over.
        }
    }

    /**
     * Back-dates the operator's draw timestamp so {@code getDrawCoolDown()} reports zero.
     *
     * <p>Best effort: a build that renames the field simply keeps its draw animation, and the
     * failure is visible in the log as {@code IS_DRAWING} rather than as a silent no-op.
     */
    private static void clearDrawCooldown(Object operator) {
        if (drawTimestampField == null || getDataHolder == null) {
            return;
        }
        try {
            Object holder = getDataHolder.invoke(operator);
            if (holder != null) {
                drawTimestampField.setLong(holder, System.currentTimeMillis() - DRAW_BACKDATE_MS);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // See above: a missing field is a degraded path, not a crash.
        }
    }

    /** Shots the last {@link #fire} produced. The plate deducts this many real rounds. */
    public static int takeShotCount() {
        return lastShotCount;
    }

    /** True when a dedicated shooter is present in this pack. */
    public static boolean isAvailable() {
        if (probed) {
            return operatorClass != null;
        }
        probed = true;
        try {
            operatorClass = Class.forName("com.tacz.guns.api.entity.IGunOperator");
            fromLivingEntity = operatorClass.getMethod("fromLivingEntity", LivingEntity.class);
            initialData = operatorClass.getMethod("initialData");
            draw = operatorClass.getMethod("draw", java.util.function.Supplier.class);
            shoot = operatorClass.getMethod("shoot", java.util.function.Supplier.class,
                    java.util.function.Supplier.class, long.class);
            try {
                shootNoTimestamp = operatorClass.getMethod("shoot",
                        java.util.function.Supplier.class, java.util.function.Supplier.class);
            } catch (NoSuchMethodException absent) {
                shootNoTimestamp = null;
            }
            probeAmmunitionApi();
            com.sidedrain.SideDrain.LOGGER.info("Dedicated weapon shooter available: TACZ");
        } catch (ReflectiveOperationException | RuntimeException absent) {
            operatorClass = null;
            fromLivingEntity = null;
            initialData = null;
            draw = null;
            shoot = null;
            shootNoTimestamp = null;
        }
        return operatorClass != null;
    }

    /**
     * Resolves the parts that let a gun's magazine be read and filled, plus the timestamp field.
     *
     * <p>Separate from {@link #isAvailable()} and swallowing its own failures: the operator alone is
     * enough to attempt a shot, and a build that renames {@code drawTimestamp} should lose the
     * cooldown fix rather than the whole firing path.
     */
    private static void probeAmmunitionApi() {
        try {
            holderClass = Class.forName("com.tacz.guns.entity.shooter.ShooterDataHolder");
            getDataHolder = operatorClass.getMethod("getDataHolder");
            drawTimestampField = holderClass.getField("drawTimestamp");
        } catch (ReflectiveOperationException | RuntimeException absent) {
            holderClass = null;
            getDataHolder = null;
            drawTimestampField = null;
        }
        try {
            gunClass = Class.forName("com.tacz.guns.api.item.IGun");
            gunOrNull = gunClass.getMethod("getIGunOrNull", ItemStack.class);
            setCurrentAmmoCount = gunClass.getMethod("setCurrentAmmoCount", ItemStack.class,
                    int.class);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            gunClass = null;
            gunOrNull = null;
            setCurrentAmmoCount = null;
        }
    }

    private DedicatedShooter() {
    }
}
