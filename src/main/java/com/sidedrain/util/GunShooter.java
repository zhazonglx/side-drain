package com.sidedrain.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The one place a plate fires a <b>firearm</b>.
 *
 * <h2>Why a firearm needs its own path at all</h2>
 *
 * <p>A gun is not a bow. A bow is charged, released, and produces one arrow through code the plate can
 * reach with {@code Item#use}. A gun mod keeps its own firing state - a magazine, a fire rate in
 * milliseconds, heat, recoil, a reload animation - and refuses to fire unless that state says so.
 * Calling {@code use()} on a TACZ rifle does nothing at all; calling it on a Pillagers Gun rifle runs a
 * charge animation that ends with "you have no ammunition" because the fake shooter's inventory is
 * empty.
 *
 * <h2>The three paths, strongest first</h2>
 *
 * <ol>
 *   <li><b>A dedicated shooter.</b> A gun mod that publishes a per-entity operator (TACZ's
 *       {@code IGunOperator}) is driven through that operator. This is the highest fidelity path:
 *       the mod builds its own bullet, applies its own ballistics, and reports what it spent.
 *       See {@link DedicatedShooter}.</li>
 *   <li><b>A registered dispenser behaviour.</b> Some gun mods register the vanilla
 *       {@code DispenseItemBehavior} as their "fire with no player" entry point. When one exists it is
 *       the mod's own supported way to shoot from a fixed position.</li>
 *   <li><b>The generic path.</b> The round the plate is about to bill is placed into the fake
 *       shooter's inventory, the weapon is used exactly as a player would use it (charge animation
 *       stepped to completion, cooldown applied), and the round is taken back afterwards. This is what
 *       makes a gun mod the plate has never heard of fire: the mod is handed the situation it was
 *       written for - a player holding a gun with ammunition - and does the rest itself.</li>
 * </ol>
 *
 * <h2>The bug this class exists to fix</h2>
 *
 * <p>The generic path used to be reached with the round held in the <b>caller's</b> hand and an
 * <b>empty</b> fake-player inventory. Every gun that checks its own ammunition - which is every gun -
 * answered "no ammo" and did nothing, while the plate reported a shot and billed a round for it. The
 * round has to be <b>inside</b> the shooter before {@code use()} is called, because that is where the
 * mod looks.
 *
 * <p>Nothing here creates ammunition: whatever the generic path spends is taken from the plate, and
 * whatever a dedicated shooter spends is reported back so the plate can bill it.
 */
public final class GunShooter {
    /** Cooldown after a successful shot, in ticks. Matches the other firing paths. */
    public static final int SHOT_COOLDOWN = 10;

    /** Cooldown after a refused shot: slower retry, so a broken gun cannot spin the server. */
    public static final int MISS_COOLDOWN = 20;

    /** Rounds the last {@link #fire} billed to the plate. */
    private static int lastShots;
    /** Where the last {@link #fire} was aimed, for the diagnostics line. */
    private static String lastPath = "none";

    /**
     * Fires {@code weapon} once, along {@code aim}.
     *
     * @param level  the server level the plate lives in
     * @param shooter the entity the shot is attributed to (the plate's fake player)
     * @param weapon the gun, as it sits in the plate's weapon slot
     * @param aim    the exact unit direction the plate points
     * @param rounds the round the plate is willing to spend, or {@code EMPTY} when it has none
     * @return true when a shot actually left the weapon
     */
    public static boolean fire(ServerLevel level, LivingEntity shooter, ItemStack weapon, Vec3 aim,
                               ItemStack rounds) {
        lastShots = 0;
        lastPath = "none";
        if (level == null || shooter == null || weapon == null || weapon.isEmpty()) {
            return false;
        }
        // 1. The mod's own operator, when it publishes one.
        if (DedicatedShooter.isAvailable() && GunAmmo.isGun(weapon)) {
            // The mod reads the gun from the shooter's hand, not from the argument, so the weapon has
            // to be in it. A copy is used because the mod mutates the stack it is handed - spending a
            // round, adding heat - and the result is copied back below so nothing is lost between
            // shots.
            ItemStack held = weapon.copy();
            shooter.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
            // The aim goes in explicitly: the mod builds its bullet from the pitch and yaw it is
            // handed, not from the shooter entity's rotation.
            boolean shot = DedicatedShooter.fire(shooter, held, aim);
            if (shot) {
                weapon.setCount(held.getCount());
                weapon.setTag(held.getTag());
                // Nothing is billed to the plate: DedicatedShooter has already taken the round out of
                // the gun's own magazine, and that magazine was loaded with real rounds. Charging the
                // plate as well would charge twice for one bullet - which is exactly how a plate with a
                // full chest of ammunition ran dry after a dozen shots and then stopped firing.
                lastShots = 0;
                lastPath = "operator";
                return true;
            }
            // A dedicated shooter that refuses is worth a line: its own diagnostics say why
            // (COOL_DOWN, IS_RELOADING, NO_AMMO), and falling through to the generic path would fire
            // the same gun twice if the refusal was only a timing one.
            lastPath = "operator-refused";
            return false;
        }
        // 2. A dispenser behaviour, when the mod registered one.
        if (DispenserAdapter.hasBehavior(weapon)) {
            int before = DispenserAdapter.countProjectiles(level, shooter);
            boolean accepted = DispenserAdapter.tryFire(level, shooter.blockPosition(),
                    net.minecraft.core.Direction.getNearest(aim.x, aim.y, aim.z), aim, weapon,
                    ItemStack.EMPTY);
            // The behaviour's own return value is not evidence: a dispense behaviour that quietly
            // declines still returns normally, and billing a round for it would be the same false
            // positive that made the old gun path look like it worked. A projectile in the world is.
            if (accepted && (DispenserAdapter.countProjectiles(level, shooter) > before
                    || DispenserAdapter.lastCallUsedAmmo())) {
                // A dispense behaviour was handed no round, so whatever it fired it found itself. Bill
                // the plate one round to keep "creative shooter" from becoming free ammunition.
                lastShots = 1;
                lastPath = "dispenser";
                return true;
            }
        }
        // 3. The generic path: hand the mod a loaded gun and let it shoot.
        int spent = fireWithLoadedGun(level, shooter, weapon, aim, rounds);
        if (spent > 0) {
            lastShots = spent;
            lastPath = "generic";
            return true;
        }
        lastPath = "generic-refused";
        return false;
    }

    /** Rounds the last {@link #fire} actually spent. The plate bills this many. */
    public static int takeShotCount() {
        return lastShots;
    }

    /** A short description of the path the last {@link #fire} took, for the log line. */
    public static String lastPath() {
        return lastPath;
    }

    /**
     * How many ticks the plate should wait before firing this gun again.
     *
     * <p>A player is limited by the gun's own cycle time; a plate is limited by this number, because
     * the plate has no trigger to hold and fires on its own schedule. Asking the mod is the only
     * honest answer - a rifle cycling in four ticks must not be throttled to a pistol's ten, and a
     * shotgun must not be sped up - so the mod's own shot interval is read when it publishes one.
     *
     * <p>Falls back to {@link #SHOT_COOLDOWN} for a mod that does not expose it.
     */
    public static int fireIntervalTicks(ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) {
            return SHOT_COOLDOWN;
        }
        int millis = GunAmmo.shotIntervalMillis(weapon);
        if (millis <= 0) {
            return SHOT_COOLDOWN;
        }
        // One tick is 50 ms. Rounded up, because firing early is what the mod refuses anyway.
        //
        // NOT clamped to SHOT_COOLDOWN: a machine gun's real interval can be shorter than the plate's
        // old fixed ten ticks, and clamping turned every fast gun into a slow one. The upper bound
        // exists only so a gun that reports a broken RPM cannot stall the plate for minutes.
        int ticks = (millis + 49) / 50;
        return Math.max(1, Math.min(MAX_FIRE_INTERVAL_TICKS, ticks));
    }

    /** Upper bound on a gun's own fire interval, so a broken RPM cannot stall a plate for minutes. */
    private static final int MAX_FIRE_INTERVAL_TICKS = 40;

    /**
     * Uses the gun the way a player with ammunition would, through the plate's fake shooter.
     *
     * <p>The round is moved into the shooter's inventory first and taken back afterwards, so the mod's
     * own "do I have ammunition" check passes for real rather than being bypassed with a creative
     * flag. A mod that copies the round instead of consuming it still cannot duplicate anything: the
     * plate bills one round and the copy is removed again.
     *
     * @return how many rounds were spent, 0 when the gun refused
     */
    private static int fireWithLoadedGun(ServerLevel level, LivingEntity shooter, ItemStack weapon,
                                         Vec3 aim, ItemStack rounds) {
        if (!(shooter instanceof net.minecraft.world.entity.player.Player player)) {
            // The generic path needs an inventory, which only a player has. A non-player shooter
            // (another mod's operator entity) is left to the paths above.
            return 0;
        }
        Inventory inventory = player.getInventory();
        // Where the round will be parked, so it can be taken back out of exactly that slot.
        int parkedSlot = -1;
        ItemStack parked = ItemStack.EMPTY;
        if (rounds != null && !rounds.isEmpty()) {
            parked = rounds.copyWithCount(Math.min(rounds.getCount(), 64));
            parkedSlot = findFreeSlot(inventory);
            if (parkedSlot >= 0) {
                inventory.setItem(parkedSlot, parked);
            }
        }
        // Aim the shooter before the mod reads its look vector, and put the gun in its hand: a gun mod
        // reads the weapon from the hand, not from an argument.
        DispenserAdapter.aimShooter(shooter, aim);
        ItemStack held = weapon.copy();
        shooter.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
        // Survival for the duration of the shot. The plate's fake player is kept creative for the
        // dedicated-shooter path, where that is how TACZ's own ammunition check is satisfied - but a
        // creative player makes vanilla's {@code Player#getProjectile} hand out a free arrow when it
        // finds no ammunition, and it makes every mod skip its own "consume a round" step. A gun on
        // this path has a real round parked in the inventory, so it must be seen as a player who has
        // to spend it: otherwise the plate could fire a gun it had no rounds for.
        boolean wasCreative = player.getAbilities().instabuild;
        player.getAbilities().instabuild = false;
        boolean fired = false;
        try {
            fired = DispenserAdapter.useHeldWeapon(level, player);
        } finally {
            player.getAbilities().instabuild = wasCreative;
            // Whatever happened, the plate keeps its round: it bills the shot itself, and a round left
            // inside the fake player would be duplicated the next time it is reused.
            ItemStack leftover = shooter.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (fired && !leftover.isEmpty() && leftover.is(weapon.getItem())) {
                // The mod may have written its own state back into the stack (heat, chamber, a spent
                // round). Copy it onto the plate's gun so nothing is lost between shots. Guarded on
                // the item: a mod that swaps the stack for its own loaded version must not overwrite
                // the plate's weapon with it.
                weapon.setCount(leftover.getCount());
                weapon.setTag(leftover.getTag());
            }
            shooter.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (parkedSlot >= 0) {
                inventory.setItem(parkedSlot, ItemStack.EMPTY);
            }
        }
        return fired ? 1 : 0;
    }

    /** The first empty slot of an inventory, or -1 when it is full. */
    private static int findFreeSlot(Inventory inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                return i;
            }
        }
        // A full inventory is not a refusal: the mod is also allowed to read the offhand, and many
        // guns check the whole inventory. Returning -1 simply means "nothing was parked".
        return -1;
    }

    /**
     * True when this stack is a firearm the plate should drive through this class.
     *
     * <p>Deliberately narrow. A staff and a wand are casters with their own path, and a bow is handled
     * by {@link ProjectileLauncher} with real arrow ballistics; treating either as a gun would replace
     * working behaviour with a worse one.
     */
    public static boolean isFirearm(ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) {
            return false;
        }
        // Ammunition is not a weapon. A gun mod's rounds often carry a gun noun in their id
        // ("assault_rifle_ammo"), which would otherwise classify them as a firearm and make the plate
        // try to shoot its own magazine.
        if (ProjectileLauncher.isAmmo(weapon)) {
            return false;
        }
        if (GunAmmo.isGun(weapon)) {
            // The mod itself says this is a gun. No guessing needed.
            return true;
        }
        WeaponKind kind = WeaponKind.of(weapon);
        if (kind == WeaponKind.GUN) {
            return true;
        }
        // A modded gun whose registry name says nothing useful lands in OTHER, and one that does not
        // name its ammunition is never classified GUN at all. So the third case is decided by the
        // signal that actually matters: does the plate have to feed it? A weapon that announces
        // ammunition is a firearm whatever it is called, which is what covers a pack whose guns have
        // ids like "scguns:flint".
        //
        // The four families that are also fed by an item are excluded explicitly, because routing them
        // here would replace working ballistics with a worse path: a bow, a crossbow and a trident
        // fire real projectiles the plate can spawn itself, and a caster's "ammunition" is a focus it
        // must never spend.
        if (kind == WeaponKind.BOW || kind == WeaponKind.CROSSBOW || kind == WeaponKind.TRIDENT
                || ProjectileLauncher.isArchery(weapon) || kind.isCaster()) {
            return false;
        }
        return WeaponAmmo.profile(weapon).needsItem();
    }

    private GunShooter() {
    }
}
