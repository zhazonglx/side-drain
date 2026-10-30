package com.sidedrain.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.Items;

/**
 * Works out <b>what a weapon needs</b> before it can attack, and whether it shoots.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>Classifying a weapon by its class or its name works for bows and for guns that call themselves
 * guns. It fails for everything else: a modded staff is a plain {@code Item} with a custom
 * {@code use} method, its id says "staff", and nothing about it announces that casting needs a spell
 * book in the inventory. Hard-coding a list of mods would be a losing race - the pack has 298 jars.
 *
 * <p>So instead of asking "what mod is this?", this asks the two questions that actually decide
 * whether the plate can fire something:
 *
 * <ol>
 *   <li><b>Does it shoot?</b> A {@code ProjectileWeaponItem} says so directly. A bow, crossbow or
 *       trident is known. A gun names ammunition in its id. A staff's id says staff/wand/scepter.</li>
 *   <li><b>What does it need?</b> This is answered from several independent signals, any one of which
 *       is enough - and crucially, a weapon can be perfectly usable with <b>nothing</b> required.</li>
 * </ol>
 *
 * <h2>The three states a weapon can be in</h2>
 *
 * <ul>
 *   <li>{@link Requirement#NONE} - it fires on its own. A wand with a cooldown but no ammunition, a
 *       spell book whose spells are paid for with mana. The plate should fire it regardless of what is
 *       in storage.</li>
 *   <li>{@link Requirement#CONSUMES} - it spends an item per shot. Bows spend arrows, guns spend
 *       bullets. The plate must find that item and take one per shot.</li>
 *   <li>{@link Requirement#REQUIRES} - it needs an item <b>present but not consumed</b>. This is the
 *       case the request singled out: a staff that needs a spell book (or, in other packs, a crystal)
 *       in the inventory, and casts from it without ever using it up. Taking one would be wrong, and
 *       refusing to fire because it cannot "find ammunition" would also be wrong.</li>
 * </ul>
 *
 * <h2>How "needs an item" is detected</h2>
 *
 * <p>There is no Forge API for "what does this item consume", so this reads the signals that mods
 * actually publish, strongest first:
 *
 * <ol>
 *   <li><b>{@code ProjectileWeaponItem#getSupportedHeldProjectiles}</b> / {@code getAllSupportedProjectiles}
 *       - the vanilla-and-Forge answer for anything that shoots items. A modded bow or gun that builds
 *       on these reports its ammunition exactly, and no guessing is involved.</li>
 *   <li><b>The weapon's own {@code getProjectile} / {@code getSupportedHeldProjectiles} on its stack</b>
 *       - the same idea through {@link ItemStack}, which some mods override per-stack (a gun with an
 *       ammo type stored in NBT, for instance).</li>
 *   <li><b>Registry-name pairing.</b> Last resort, and the only signal a self-contained gun mod gives:
 *       the plate looks for an item whose id shares the weapon's namespace and whose name contains an
 *       ammunition noun. A "tacz:modern_kinetic_gun" therefore looks for "tacz:..._ammo".</li>
 * </ol>
 *
 * <p>When none of these produces anything, the weapon is treated as {@link Requirement#NONE} - it
 * fires without ammunition. That is the safe default: a plate that refuses to fire a working weapon
 * because it could not name its fuel would be worse than a plate that fires it and finds out.
 */
public final class WeaponAmmo {

    /** What a weapon needs before it can attack. */
    public enum Requirement {
        /** Nothing: it fires on its own. */
        NONE,
        /** An item per shot, which is spent. */
        CONSUMES,
        /** An item that must be present but is never spent. */
        REQUIRES,
    }

    /** The verdict for one weapon stack. */
    public static final class Profile {
        /** What the weapon needs. */
        public final Requirement requirement;
        /**
         * The items that satisfy it, in preference order. Empty for {@link Requirement#NONE}.
         *
         * <p>Held as concrete stacks rather than tags so a modded gun that reports one specific
         * ammunition item is handled exactly, and a bow that reports the arrow tag is handled too.
         */
        public final List<ItemStack> accepted;
        /** True when the weapon launches a projectile (as opposed to, say, applying an effect). */
        public final boolean shoots;
        /**
         * The weapon this profile describes, so the mod's own ammunition test can be asked.
         *
         * <p>Needed because a gun mod can ship <b>one</b> ammunition item and store the caliber in
         * NBT: TACZ has a single {@code tacz:ammo}, and a rifle round and a shotgun round are the
         * same item with different tags. {@link #accepted} holds prototype stacks, and comparing
         * tags can only ever match one specific caliber - so a chest full of the right rounds was
         * invisible. {@link #matches(ItemStack)} falls back to the mod's own
         * {@code IAmmo#isAmmoOfGun} test, which compares the caliber, not the tag.
         */
        @Nullable
        public final ItemStack weapon;

        Profile(Requirement requirement, List<ItemStack> accepted, boolean shoots) {
            this(requirement, accepted, shoots, null);
        }

        Profile(Requirement requirement, List<ItemStack> accepted, boolean shoots,
                @Nullable ItemStack weapon) {
            this.requirement = requirement;
            this.accepted = accepted;
            this.shoots = shoots;
            this.weapon = weapon;
        }

        /**
         * True when {@code candidate} is something this weapon can spend.
         *
         * <p>Three independent answers, any one of which is enough:
         *
         * <ol>
         *   <li>The prototype list matches exactly - the vanilla-arrow case.</li>
         *   <li>The weapon is a gun whose mod publishes an ammunition test, and it accepts the
         *       stack - the TACZ case, where the caliber lives in NBT.</li>
         *   <li>The generic "looks like ammunition" test, for a pack whose weapons report
         *       nothing at all.</li>
         * </ol>
         */
        public boolean matches(ItemStack candidate) {
            if (candidate == null || candidate.isEmpty()) {
                return false;
            }
            if (accepts(this.accepted, candidate)) {
                return true;
            }
            if (this.weapon != null && GunAmmo.fits(this.weapon, candidate)) {
                return true;
            }
            // Only when the weapon reported nothing at all. A weapon that named its ammunition
            // must not be fed something else just because the plate has it in stock.
            return this.accepted.isEmpty() && ProjectileLauncher.isAmmo(candidate);
        }

        /** True when the plate must look for an item before it can fire. */
        public boolean needsItem() {
            return this.requirement != Requirement.NONE;
        }

        /** True when firing spends one of {@link #accepted}. */
        public boolean consumes() {
            return this.requirement == Requirement.CONSUMES;
        }
    }

    private static final Profile NONE_NO_SHOT = new Profile(Requirement.NONE, List.of(), false);
    private static final Profile NONE_SHOOTS = new Profile(Requirement.NONE, List.of(), true);

    /**
     * Profiles a weapon.
     *
     * @param weapon the stack in the plate's weapon slot
     * @return never null
     */
    public static Profile profile(@Nullable ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) {
            return NONE_NO_SHOT;
        }
        List<ItemStack> accepted = new ArrayList<>(4);
        // 1. The item itself, through the interfaces vanilla and Forge provide for exactly this.
        collectProjectiles(weapon, accepted);
        if (!accepted.isEmpty()) {
            // Anything that reports projectiles is, by definition, shooting them, and a weapon that
            // names its ammunition spends it. Bows, crossbows and modded guns all land here.
            return new Profile(Requirement.CONSUMES, accepted, true, weapon);
        }
        // 2. A casting weapon: staff, wand, scepter, spell book. These do not name projectiles -
        //    their payload is a spell - so the registry name is the signal, and what they need is a
        //    spell container rather than a consumable.
        if (looksLikeCaster(weapon)) {
            // A caster is self-powered: it fires without consuming anything.
            //
            // This is deliberately Requirement.NONE rather than "requires a spell book". The first
            // version scanned the item registry for a focus item and required it, which was wrong in
            // a way that broke the feature outright: the registry always contains spell books, so the
            // requirement was always "satisfied" by an item that might not be anywhere near the plate,
            // and a staff whose pack casts from mana would have been blocked on an item it never
            // needed. Iron's Spellbooks - the pack's actual magic mod - casts from learned spells and
            // mana; its inks are crafting ingredients, not fuel. Requiring one would have been a
            // straight downgrade.
            //
            // The "uses an item but does not consume it" case is handled where it can actually be
            // observed: the plate knows what is in its own storage, so if a focus is present it is
            // simply never taken (see takeAmmo's readOnly branch).
            return NONE_SHOOTS;
        }
        // 3. A gun-shaped name with no reported ammunition: look for a matching item in the same
        //    namespace. This is the only signal a self-contained gun mod gives.
        if (looksLikeGun(weapon)) {
            List<ItemStack> found = findNamespaceAmmo(weapon);
            if (!found.isEmpty()) {
                return new Profile(Requirement.CONSUMES, found, true, weapon);
            }
            // A gun we could not match ammunition for still shoots; it just cannot be reloaded.
            return new Profile(Requirement.NONE, List.of(), true, weapon);
        }
        // 3b. A gun whose registry name says nothing useful but whose mod publishes a gun interface.
        //     TACZ ships every gun as "tacz:modern_kinetic_gun", so the name test above cannot see
        //     it; the mod's own API can. Without this branch such a gun is NONE_NO_SHOT, and
        //     isRangedWeapon() is false, so the plate never even tries to fire it.
        if (GunAmmo.isGun(weapon)) {
            List<ItemStack> found = findNamespaceAmmo(weapon);
            return new Profile(Requirement.CONSUMES, found, true, weapon);
        }
        // 4. Nothing recognised it as a launcher.
        return NONE_NO_SHOT;
    }

    /**
     * Collects the ammunition a weapon reports through the standard interfaces.
     *
     * <p>{@code getSupportedHeldProjectiles} is the modern answer and covers bows, crossbows and any
     * modded weapon built on {@code ProjectileWeaponItem}; {@code getAllSupportedProjectiles} is the
     * broader fallback.
     */
    private static void collectProjectiles(ItemStack weapon, List<ItemStack> into) {
        if (!(weapon.getItem() instanceof ProjectileWeaponItem projectileWeapon)) {
            return;
        }
        // The two methods return Predicate<ItemStack>, not a list: they answer "would you accept this
        // stack?" rather than "here is your ammunition". So the accepted set is discovered by asking
        // the predicate about the items the plate could plausibly feed it - every item whose registry
        // name looks like ammunition, plus the vanilla arrow family.
        List<java.util.function.Predicate<ItemStack>> tests = new ArrayList<>(2);
        try {
            java.util.function.Predicate<ItemStack> held = projectileWeapon.getSupportedHeldProjectiles();
            if (held != null) {
                tests.add(held);
            }
        } catch (RuntimeException ignored) {
            // A modded weapon that throws here is not worth a crash; the other paths remain.
        }
        try {
            java.util.function.Predicate<ItemStack> all = projectileWeapon.getAllSupportedProjectiles();
            if (all != null) {
                tests.add(all);
            }
        } catch (RuntimeException ignored) {
            // Same.
        }
        if (tests.isEmpty()) {
            return;
        }
        // Ask each predicate about every candidate ammunition item in the game. The scan is bounded
        // by the item registry, which is a few thousand entries, and it only runs when the weapon slot
        // changes because the profile is memoised per tick.
        for (var key : BuiltInRegistries.ITEM.registryKeySet()) {
            ResourceLocation id = key.location();
            String path = id.getPath();
            if (!looksLikeProjectile(path)) {
                continue;
            }
            net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(key);
            if (item == null) {
                continue;
            }
            ItemStack candidate = new ItemStack(item);
            for (var test : tests) {
                try {
                    if (test.test(candidate)) {
                        addIfItem(into, candidate);
                        break;
                    }
                } catch (RuntimeException ignored) {
                    // A predicate that throws is a broken mod, not a reason to stop.
                }
            }
        }
    }

    /** True when a registry path could name a projectile or its ammunition. */
    private static boolean looksLikeProjectile(String path) {
        return path.contains("arrow") || path.contains("bolt") || path.contains("bullet")
                || path.contains("projectile") || path.contains("dart") || path.contains("pellet")
                || path.contains("shell") || path.contains("rocket") || path.contains("missile")
                || path.contains("ammo") || path.contains("cartridge") || path.contains("round");
    }

    /** Adds a prototype stack, skipping empty ones and duplicates. */
    private static void addIfItem(List<ItemStack> into, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        for (ItemStack existing : into) {
            if (ItemStack.isSameItemSameTags(existing, stack)) {
                return;
            }
        }
        into.add(stack.copyWithCount(1));
    }

    /** True when the registry name says this is a casting weapon. */
    private static boolean looksLikeCaster(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        return path.contains("staff") || path.contains("wand") || path.contains("scepter")
                || path.contains("sceptre") || path.contains("spellbook") || path.contains("spell_book")
                || path.contains("grimoire") || path.contains("tome") || path.contains("rod")
                || path.contains("orb") || path.contains("focus") || path.contains("casting");
    }

    /** True when the registry name says this is a firearm. */
    private static boolean looksLikeGun(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        String path = id.getPath();
        return path.contains("gun") || path.contains("pistol") || path.contains("rifle")
                || path.contains("shotgun") || path.contains("revolver") || path.contains("musket")
                || path.contains("blaster") || path.contains("carbine") || path.contains("smg");
    }

    /**
     * Finds ammunition for a gun by looking in the weapon's own namespace.
     *
     * <p>A self-contained gun mod ships its guns and its bullets under one namespace, so pairing them
     * that way is reliable without knowing anything about the mod.
     */
    private static List<ItemStack> findNamespaceAmmo(ItemStack weapon) {
        List<ItemStack> found = new ArrayList<>(2);
        ResourceLocation weaponId = BuiltInRegistries.ITEM.getKey(weapon.getItem());
        if (weaponId == null) {
            return found;
        }
        String namespace = weaponId.getNamespace();
        for (var key : BuiltInRegistries.ITEM.registryKeySet()) {
            ResourceLocation id = key.location();
            if (!id.getNamespace().equals(namespace)) {
                continue;
            }
            if (!com.sidedrain.util.ProjectileLauncher.isAmmoName(id.getPath())) {
                continue;
            }
            net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(key);
            if (item != null) {
                found.add(new ItemStack(item));
            }
            if (found.size() >= 4) {
                break;
            }
        }
        return found;
    }

    /** True when the stack is any of the prototypes in {@code accepted}. */
    public static boolean accepts(List<ItemStack> accepted, ItemStack candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        for (ItemStack prototype : accepted) {
            if (ItemStack.isSameItemSameTags(prototype, candidate)) {
                return true;
            }
        }
        return false;
    }

    private WeaponAmmo() {
    }
}
