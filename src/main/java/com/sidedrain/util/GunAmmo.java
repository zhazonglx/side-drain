package com.sidedrain.util;

import java.lang.reflect.Method;
import javax.annotation.Nullable;
import net.minecraft.world.item.ItemStack;

/**
 * Answers "is this stack a round this gun can chamber?" for TACZ, using the mod's own API.
 *
 * <h2>Why a name match is not good enough here</h2>
 *
 * <p>{@link ProjectileLauncher#isAmmoName} recognises ammunition by its registry path. That works
 * for a bow and for a gun mod that ships one bullet item per caliber, but TACZ ships <b>one</b>
 * ammunition item - {@code tacz:ammo} - and stores the caliber in the stack's {@code AmmoId} NBT
 * tag. Two stacks of {@code tacz:ammo} for different calibers are different rounds, and
 * {@link ItemStack#isSameItemSameTags} can only ever match the one with an identical tag. A rifle
 * therefore found no ammunition in a chest full of rifle rounds, and the plate refused to fire.
 *
 * <h2>What this uses instead</h2>
 *
 * <p>TACZ publishes the exact test on {@code com.tacz.guns.api.item.IAmmo}:
 * {@code isAmmoOfGun(gunStack, ammoStack)}, which compares the gun's configured {@code ammoId}
 * against the round's {@code AmmoId}. That is the same test the mod itself runs when a player
 * reloads, so a round the plate accepts is a round the gun would have accepted.
 *
 * <p>Everything is reflective and fails closed: with TACZ absent, {@link #isAvailable()} is false
 * and the plate keeps its old heuristics.
 */
public final class GunAmmo {
    /** True once the probe has run. */
    private static boolean probed;
    @Nullable
    private static Class<?> ammoInterface;
    @Nullable
    private static Class<?> gunInterface;
    /** {@code IAmmo.getIAmmoOrNull(ItemStack)} - null for anything that is not a round. */
    @Nullable
    private static Method ammoOrNull;
    /** {@code IGun.getIGunOrNull(ItemStack)} - null for anything that is not a gun. */
    @Nullable
    private static Method gunOrNull;
    /** {@code IAmmo.isAmmoOfGun(ItemStack gun, ItemStack ammo)}. */
    @Nullable
    private static Method isAmmoOfGun;
    /** {@code IGun.getCurrentAmmoCount(ItemStack)} - optional, absent in a stripped build. */
    @Nullable
    private static Method getAmmoCount;
    /** {@code IGun.setCurrentAmmoCount(ItemStack, int)} - optional. */
    @Nullable
    private static Method setAmmoCount;
    /** {@code IGun.hasBulletInBarrel(ItemStack)} - optional. */
    @Nullable
    private static Method hasBarrelRound;

    /** True when the stack is a gun this adapter understands. */
    public static boolean isGun(@Nullable ItemStack stack) {
        if (!isAvailable() || stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return gunOrNull.invoke(null, stack) != null;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return false;
        }
    }

    /** True when {@code candidate} is a round {@code gun} can chamber. */
    public static boolean fits(@Nullable ItemStack gun, @Nullable ItemStack candidate) {
        if (!isAvailable() || gun == null || gun.isEmpty()
                || candidate == null || candidate.isEmpty()) {
            return false;
        }
        try {
            Object ammo = ammoOrNull.invoke(null, candidate);
            if (ammo == null) {
                // Not a TACZ round at all. A gun mod that ships its own ammo item per caliber is
                // still handled by the registry-name path in WeaponAmmo, so this is not a refusal.
                return false;
            }
            return Boolean.TRUE.equals(isAmmoOfGun.invoke(ammo, gun, candidate));
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return false;
        }
    }

    /**
     * The gun's own shot interval in milliseconds, or 0 when the mod does not publish one.
     *
     * <p>Read from {@code IGun#getRPM(ItemStack)} - rounds per minute - which is the same number the mod
     * uses to gate a player's next trigger pull. A plate that fires faster than this is refused with
     * {@code COOL_DOWN}; one that fires slower is a machine gun behaving like a pistol.
     */
    public static int shotIntervalMillis(@Nullable ItemStack gun) {
        if (!isAvailable() || gun == null || gun.isEmpty()) {
            return 0;
        }
        try {
            Object handle = gunOrNull.invoke(null, gun);
            if (handle == null) {
                return 0;
            }
            // IGun publishes the rate as getRPM (rounds per minute), not as a millisecond interval.
            // Asking for a method that does not exist silently produced 0, so every gun fell back to
            // the plate's default cadence and a slow shotgun fired as fast as a rifle.
            Method accessor = rpmAccessor;
            if (accessor == null) {
                accessor = gunInterface.getMethod("getRPM", ItemStack.class);
                rpmAccessor = accessor;
            }
            Object value = accessor.invoke(handle, gun);
            int rpm = value instanceof Number number ? number.intValue() : 0;
            if (rpm <= 0) {
                return 0;
            }
            // 60_000 ms per minute divided by rounds per minute is one round's interval.
            return Math.max(1, 60_000 / rpm);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return 0;
        }
    }

    /** {@code IGun#getRPM(ItemStack)} - optional, absent in a stripped build. */
    @Nullable
    private static Method rpmAccessor;

    /** True when TACZ (or a compatible gun mod) is present. */
    public static boolean isAvailable() {
        if (probed) {
            return ammoInterface != null;
        }
        probed = true;
        try {
            ammoInterface = Class.forName("com.tacz.guns.api.item.IAmmo");
            gunInterface = Class.forName("com.tacz.guns.api.item.IGun");
            ammoOrNull = ammoInterface.getMethod("getIAmmoOrNull", ItemStack.class);
            gunOrNull = gunInterface.getMethod("getIGunOrNull", ItemStack.class);
            isAmmoOfGun = ammoInterface.getMethod("isAmmoOfGun", ItemStack.class, ItemStack.class);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            ammoInterface = null;
            gunInterface = null;
            ammoOrNull = null;
            gunOrNull = null;
            isAmmoOfGun = null;
            return false;
        }
        // The magazine accessors are optional: a build without them still fires, it just cannot
        // read or bill the gun's own rounds.
        try {
            getAmmoCount = gunInterface.getMethod("getCurrentAmmoCount", ItemStack.class);
            setAmmoCount = gunInterface.getMethod("setCurrentAmmoCount", ItemStack.class, int.class);
            hasBarrelRound = gunInterface.getMethod("hasBulletInBarrel", ItemStack.class);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            getAmmoCount = null;
            setAmmoCount = null;
            hasBarrelRound = null;
        }
        return true;
    }

    /**
     * Rounds currently in the gun: the magazine plus the chambered round.
     *
     * <p>Both halves matter. A closed-bolt gun fires the chambered round first and only then takes
     * one out of the magazine, so the magazine alone under-reports a gun that is ready to fire.
     */
    public static int loadedRounds(@Nullable ItemStack gun) {
        if (!isAvailable() || gun == null || gun.isEmpty() || getAmmoCount == null) {
            return 0;
        }
        try {
            Object handle = gunOrNull.invoke(null, gun);
            if (handle == null) {
                return 0;
            }
            int magazine = ((Number) getAmmoCount.invoke(handle, gun)).intValue();
            boolean chambered = hasBarrelRound != null
                    && Boolean.TRUE.equals(hasBarrelRound.invoke(handle, gun));
            return magazine + (chambered ? 1 : 0);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return 0;
        }
    }

    /**
     * Puts exactly {@code rounds} into the gun's own magazine.
     *
     * <p>Used to load one real round from the plate into an empty gun, which is the same thing a
     * player does with a reload - not a top-up from nothing. The magazine is set, never grown beyond
     * what was handed over, so a gun cannot be talked into more ammunition than the plate paid for.
     */
    public static boolean loadRounds(@Nullable ItemStack gun, int rounds) {
        if (!isAvailable() || gun == null || gun.isEmpty() || setAmmoCount == null || rounds < 0) {
            return false;
        }
        try {
            Object handle = gunOrNull.invoke(null, gun);
            if (handle == null) {
                return false;
            }
            setAmmoCount.invoke(handle, gun, rounds);
            return true;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return false;
        }
    }

    /**
     * Rounds in the magazine alone, without the chambered round.
     *
     * <p>{@link #loadedRounds} is the number a player sees and the number a shot is billed from, but
     * {@code setCurrentAmmoCount} writes the <b>magazine</b>. Reloading needs the two kept apart, or a
     * gun with a round in the chamber is topped up one round short every time.
     */
    public static int magazineRounds(@Nullable ItemStack gun) {
        if (!isAvailable() || gun == null || gun.isEmpty() || getAmmoCount == null) {
            return 0;
        }
        try {
            Object handle = gunOrNull.invoke(null, gun);
            if (handle == null) {
                return 0;
            }
            return ((Number) getAmmoCount.invoke(handle, gun)).intValue();
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return 0;
        }
    }

    /** The gun's magazine size, or 0 when the mod does not publish one. */
    public static int magazineSize(@Nullable ItemStack gun) {
        if (!isAvailable() || gun == null || gun.isEmpty()) {
            return 0;
        }
        try {
            Object handle = gunOrNull.invoke(null, gun);
            if (handle == null) {
                return 0;
            }
            Method accessor = magazine;
            if (accessor == null) {
                accessor = gunInterface.getMethod("getMagazineSize", ItemStack.class);
                magazine = accessor;
            }
            Object value = accessor.invoke(handle, gun);
            return value instanceof Number number ? number.intValue() : 0;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return 0;
        }
    }

    /** {@code IGun#getMagazineSize(ItemStack)} - optional. */
    @Nullable
    private static Method magazine;

    /**
     * Removes up to {@code rounds} from the gun's own magazine.
     *
     * <p>Needed because the plate's shooter is creative: TACZ's {@code consumesAmmoOrNot()} answers
     * false for a creative player (unless the pack's config says otherwise), so a shot fired through
     * the plate does not decrement the magazine. The rounds are real - the player loaded them - so
     * the plate bills them itself rather than leaving the gun permanently full.
     *
     * @return how many rounds were actually removed
     */
    public static int deductRounds(@Nullable ItemStack gun, int rounds) {
        if (!isAvailable() || gun == null || gun.isEmpty() || getAmmoCount == null
                || setAmmoCount == null || rounds <= 0) {
            return 0;
        }
        try {
            Object handle = gunOrNull.invoke(null, gun);
            if (handle == null) {
                return 0;
            }
            int magazine = ((Number) getAmmoCount.invoke(handle, gun)).intValue();
            int taken = Math.min(magazine, rounds);
            if (taken > 0) {
                setAmmoCount.invoke(handle, gun, magazine - taken);
            }
            return taken;
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return 0;
        }
    }

    private GunAmmo() {
    }
}
