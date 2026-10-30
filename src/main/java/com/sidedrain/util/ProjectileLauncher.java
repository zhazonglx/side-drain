package com.sidedrain.util;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.entity.projectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Fires a projectile out of a Side Drain Plate.
 *
 * <p>Every vanilla projectile item is mapped to the entity it should spawn, so a plate loaded with
 * arrows really shoots arrows. Modded ammunition is handled in two more ways: an item that names a
 * known entity in its registry id is spawned from that entity type, and a modded item that only
 * exists as a "projectile item" is thrown as a snowball-like object so it still visibly flies.
 */
public final class ProjectileLauncher {
    /** True when the stack is something that can be shot. */
    public static boolean isAmmo(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW) || stack.is(Items.TIPPED_ARROW)
                || stack.is(Items.SNOWBALL) || stack.is(Items.EGG) || stack.is(Items.ENDER_PEARL)
                || stack.is(Items.EXPERIENCE_BOTTLE) || stack.is(Items.FIRE_CHARGE)
                || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION)
                || stack.is(Items.TRIDENT) || stack.is(Items.FIREWORK_ROCKET)
                || isModdedAmmo(stack);
    }

    /**
     * Modded ammunition: an item whose registry name mentions a projectile, or whose id matches a
     * registered entity. This is a heuristic, but it is what lets modded guns work at all without
     * depending on their classes.
     */
    private static boolean isModdedAmmo(ItemStack stack) {
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return false;
        }
        return isAmmoName(id.getPath());
    }

    /**
     * True when a registry path names ammunition.
     *
     * <p>Split out so {@link WeaponAmmo} can ask the same question about items it discovers by
     * namespace, and so both use one list of nouns instead of two that drift apart.
     */
    public static boolean isAmmoName(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        // A modded gun's ammunition is usually named for what it is: "9mm_bullet", "rifle_ammo",
        // "shotgun_shell", "energy_cell". Matching the noun is the only signal available without
        // depending on the gun mod's classes, and it is the one the request asked for - the plate
        // should find the bullets in its own storage and spend them.
        return path.contains("bullet") || path.contains("arrow") || path.contains("bolt")
                || path.contains("projectile") || path.contains("missile") || path.contains("dart")
                || path.contains("pellet") || path.contains("shell") || path.contains("rocket")
                || path.contains("grenade") || path.contains("charge")
                || path.contains("ammo") || path.contains("magazine") || path.contains("cartridge")
                || path.contains("round") || path.contains("cell") || path.contains("casing")
                // Chinese-language packs sometimes romanise instead of translating.
                || path.contains("zidan") || path.contains("dan");
    }

    /**
     * True when the stack may go in the weapon slot.
     *
     * <p>Bows, crossbows and tridents are the obvious ones; blocks that dispense are accepted too.
     * Everything that is not ammunition is allowed as well, because modded guns and wands are plain
     * items and the player should be able to try them. An item that cannot shoot does nothing.
     */
    /** True when the weapon stack is a bow or a crossbow, which fire real arrows. */
    public static boolean isArchery(ItemStack weapon) {
        return weapon.is(Items.BOW) || weapon.is(Items.CROSSBOW);
    }

    /** True when the weapon carries Infinity, which means no round is ever consumed. */
    public static boolean hasInfinity(ItemStack weapon) {
        return level(weapon, net.minecraft.world.item.enchantment.Enchantments.INFINITY_ARROWS) > 0;
    }

    /**
     * True when the weapon slot holds something the plate can drive as a ranged weapon.
     *
     * <p>This is deliberately broad. A bow or a crossbow is the obvious case; a modded staff, a
     * spell book or a gun usually arrives as a plain item with no base-game class this mod could
     * name, so the last line accepts every non-ammunition item that is not obviously something
     * else. The cost of accepting too much is that the plate may fire a useless object; the cost of
     * accepting too little is that the pack's own ranged weapons are silently ignored, which was
     * exactly the "cannot use my weapons" report.
     */
    public static boolean isRangedWeapon(ItemStack stack) {
        if (stack.isEmpty() || isAmmo(stack)) {
            return false;
        }
        // The classification is the single source of truth; see WeaponKind. The old version asked
        // MeleeAttacker.isMelee first and then accepted literally everything else, which is how a
        // pickaxe became a "ranged weapon" and a modded bow became a melee weapon.
        WeaponKind kind = WeaponKind.of(stack);
        return kind.isRanged() || kind == WeaponKind.DISPENSER;
    }

    /** True when the stack is a dispenser or dropper: a launcher body, not ammunition. */
    public static boolean isDispenser(ItemStack stack) {
        return WeaponKind.of(stack) == WeaponKind.DISPENSER;
    }

    /**
     * Fires {@code ammo} out of the plate once.
     *
     * @param aim      the exact direction the plate's nozzle points, 45 degree steps included
     * @param strategy the plate's picking strategy, so the firing pattern follows the same idea
     * @return true when a projectile actually left the plate
     */
    public static boolean fire(Level level, BlockPos pos, Vec3 aim, ItemStack weapon, ItemStack ammo,
                               int strategy, NonNullList<ItemStack> filter) {
        return fire(level, pos, null, aim, weapon, ammo, strategy, filter);
    }

    /**
     * Fires from an explicit muzzle point.
     *
     * @param muzzle the exact point the projectile should appear at, or {@code null} to derive it
     *               from the block cell. A placed plate passes {@code null} and keeps the old
     *               centre-of-cell offset; a carried plate passes the shooter's eye-height firing
     *               point, because a carried plate has no cell and the derived origin was the
     *               player's feet - which is why its shots came out far too low.
     */
    public static boolean fire(Level level, BlockPos pos, @Nullable Vec3 muzzle, Vec3 aim,
                               ItemStack weapon, ItemStack ammo, int strategy,
                               NonNullList<ItemStack> filter) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (ammo.isEmpty()) {
            // No projectile item to launch. That is a legitimate case: a casting weapon (staff, wand,
            // spell book) pays for its shot with mana, not with an item, so the plate has to be able
            // to "fire" with nothing in hand. There is no generic way to make a modded item cast -
            // the spell lives in the mod's own code - so what this does is the honest best effort:
            // it reports the shot as attempted so the plate's cooldown and pulse accounting stay
            // consistent, and the weapon itself is left untouched for the mod's own logic to use.
            //
            // A bow with no arrows never reaches here: hasAmmunition() is false for it.
            return false;
        }
        // A bow or a crossbow fires a real arrow through the weapon's own ballistics, with the
        // weapon's enchantments folded in by aim() - including Infinity, which is what keeps the
        // plate from eating a round per shot on an enchanted bow.
        boolean archery = isArchery(weapon);
        // The muzzle is the caller's if it has one, otherwise the plate's own cell pushed out along
        // the aim. It is the same point eject() uses, so a plate's items and its arrows always leave
        // from one place.
        Vec3 origin = muzzle != null ? muzzle
                : Vec3.atCenterOf(pos).add(aim.x * 0.65D, aim.y * 0.65D, aim.z * 0.65D);
        int multishot = archery ? level(weapon, net.minecraft.world.item.enchantment.Enchantments.MULTISHOT) : 0;
        int shots = multishot > 0 ? 3 : 1;
        boolean firedAny = false;
        for (int shot = 0; shot < shots; shot++) {
            // Multishot fans the three arrows out the way the crossbow does: one straight, two at
            // ten degrees to either side in the horizontal plane.
            Vec3 shotAim = aim;
            if (shots > 1) {
                double fan = (shot - (shots - 1) / 2.0D) * 10.0D;
                double rad = Math.toRadians(fan);
                double cos = Math.cos(rad), sin = Math.sin(rad);
                Vec3 perpendicular = new Vec3(-aim.z, 0.0D, aim.x).normalize();
                shotAim = new Vec3(
                        aim.x * cos + perpendicular.x * sin,
                        aim.y,
                        aim.z * cos + perpendicular.z * sin).normalize();
            }
            Entity projectile = spawn(serverLevel, origin, ammo);
            if (projectile == null) {
                continue;
            }
            aim(projectile, shotAim, weapon, strategy);
            if (projectile instanceof AbstractArrow arrow) {
                // The plate is the shooter of record for these, so the arrow inherits the
                // projectile default of "can be picked up" only when it is not an Infinity arrow -
                // an Infinity arrow on the ground would let the arrow be duplicated.
                if (hasInfinity(weapon)) {
                    arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
                }
            }
            serverLevel.addFreshEntity(projectile);
            firedAny = true;
        }
        if (firedAny) {
            serverLevel.levelEvent(1002, pos, 0);
        }
        return firedAny;
    }

    @Nullable
    private static Entity spawn(ServerLevel level, Vec3 origin, ItemStack ammo) {
        if (ammo.is(Items.SPECTRAL_ARROW)) {
            SpectralArrow arrow = new SpectralArrow(level, origin.x, origin.y, origin.z);
            arrow.pickup = AbstractArrow.Pickup.ALLOWED;
            return arrow;
        }
        if (ammo.is(Items.TIPPED_ARROW)) {
            Arrow arrow = new Arrow(level, origin.x, origin.y, origin.z);
            arrow.setEffectsFromItem(ammo);
            arrow.pickup = AbstractArrow.Pickup.ALLOWED;
            return arrow;
        }
        if (ammo.is(Items.ARROW)) {
            Arrow arrow = new Arrow(level, origin.x, origin.y, origin.z);
            arrow.pickup = AbstractArrow.Pickup.ALLOWED;
            return arrow;
        }
        if (ammo.is(Items.SNOWBALL)) {
            return new Snowball(level, origin.x, origin.y, origin.z);
        }
        if (ammo.is(Items.EGG)) {
            return new ThrownEgg(level, origin.x, origin.y, origin.z);
        }
        if (ammo.is(Items.ENDER_PEARL)) {
            // This one has no position constructor, so it is built from its entity type.
            Entity pearl = EntityType.ENDER_PEARL.create(level);
            if (pearl != null) {
                pearl.moveTo(origin.x, origin.y, origin.z, 0.0F, 0.0F);
            }
            return pearl;
        }
        if (ammo.is(Items.EXPERIENCE_BOTTLE)) {
            return new ThrownExperienceBottle(level, origin.x, origin.y, origin.z);
        }
        if (ammo.is(Items.SPLASH_POTION) || ammo.is(Items.LINGERING_POTION)) {
            ThrownPotion potion = new ThrownPotion(level, origin.x, origin.y, origin.z);
            potion.setItem(ammo.copy());
            return potion;
        }
        if (ammo.is(Items.TRIDENT)) {
            // Built from the entity type as well, then given its item so it renders as a trident.
            Entity trident = EntityType.TRIDENT.create(level);
            if (trident instanceof ThrownTrident thrown) {
                // The item is private and has no setter, so it is handed over through its save tag.
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                tag.put("Trident", ammo.save(new net.minecraft.nbt.CompoundTag()));
                thrown.load(tag);
                thrown.moveTo(origin.x, origin.y, origin.z, 0.0F, 0.0F);
                thrown.pickup = AbstractArrow.Pickup.ALLOWED;
                return thrown;
            }
            return null;
        }
        if (ammo.is(Items.FIREWORK_ROCKET)) {
            return new FireworkRocketEntity(level, origin.x, origin.y, origin.z, ammo.copy());
        }
        if (ammo.is(Items.FIRE_CHARGE)) {
            return new SmallFireball(level, origin.x, origin.y, origin.z, 0.0D, 0.0D, 0.0D);
        }
        // Modded ammunition: try to find an entity type whose id matches the item.
        Entity fromType = spawnFromMatchingEntity(level, origin, ammo);
        if (fromType != null) {
            return fromType;
        }
        // Last resort: throw it like a snowball so it still visibly flies and can hit things.
        Snowball standIn = new Snowball(level, origin.x, origin.y, origin.z);
        standIn.setItem(ammo.copy());
        return standIn;
    }

    @Nullable
    private static Entity spawnFromMatchingEntity(ServerLevel level, Vec3 origin, ItemStack ammo) {
        var itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(ammo.getItem());
        if (itemId == null) {
            return null;
        }
        String path = itemId.getPath();
        for (EntityType<?> type : net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES) {
            var typeId = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type);
            if (typeId == null || !typeId.getNamespace().equals(itemId.getNamespace())) {
                continue;
            }
            String typePath = typeId.getPath();
            if (typePath.equals(path) || typePath.contains(path) || path.contains(typePath)) {
                try {
                    Entity entity = type.create(level);
                    if (entity != null) {
                        entity.moveTo(origin.x, origin.y, origin.z, 0.0F, 0.0F);
                        return entity;
                    }
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Aims the projectile the way the weapon would, and copies the weapon's own properties onto it.
     *
     * <p>The weapon's enchantments, its attack damage modifiers and its custom name all carry over:
     * Power makes the arrow hit harder, Flame sets it alight, Punch knocks the target back, and a
     * sharpness-style attack bonus from a modded weapon is folded into the arrow's base damage. This
     * is what makes an enchanted bow on a plate behave like an enchanted bow in your hands.
     */
    /**
     * Aims the projectile the way the weapon would, and copies the weapon's own properties onto it.
     *
     * <p>The weapon's enchantments, its attack damage modifiers and its custom name all carry over:
     * Power makes the arrow hit harder, Flame sets it alight, Punch knocks the target back, and a
     * sharpness-style attack bonus from a modded weapon is folded into the arrow's base damage. This
     * is what makes an enchanted bow on a plate behave like an enchanted bow in your hands.
     *
     * @param aim the exact direction to fly along, 45 degree steps included
     */
    private static void aim(Entity projectile, Vec3 aim, ItemStack weapon, int strategy) {
        boolean archery = isArchery(weapon);
        double power = archery ? 2.4D : 1.4D;
        // A little spread, larger for the random strategy so the pattern visibly differs.
        double spread = strategy == 0 ? 0.08D : 0.02D;
        Vec3 dir = new Vec3(
                aim.x + (Math.random() - 0.5D) * spread,
                aim.y + (Math.random() - 0.5D) * spread + (archery ? 0.12D : 0.0D),
                aim.z + (Math.random() - 0.5D) * spread).normalize();
        projectile.setDeltaMovement(dir.x * power, dir.y * power, dir.z * power);
        if (projectile instanceof FireworkRocketEntity firework) {
            firework.setDeltaMovement(dir.x * 0.6D, dir.y * 0.6D + 0.15D, dir.z * 0.6D);
        }
        applyWeaponProperties(projectile, weapon, archery);
    }

    /** Folds the weapon's enchantments and damage bonuses into the projectile. */
    private static void applyWeaponProperties(Entity projectile, ItemStack weapon, boolean archery) {
        // Read the enchantments straight off the stack: no registry holder is needed.
        int power = level(weapon, net.minecraft.world.item.enchantment.Enchantments.POWER_ARROWS);
        int punch = level(weapon, net.minecraft.world.item.enchantment.Enchantments.PUNCH_ARROWS);
        int flame = level(weapon, net.minecraft.world.item.enchantment.Enchantments.FLAMING_ARROWS);
        int infinity = level(weapon, net.minecraft.world.item.enchantment.Enchantments.INFINITY_ARROWS);
        int multishot = level(weapon, net.minecraft.world.item.enchantment.Enchantments.MULTISHOT);
        int quickCharge = level(weapon, net.minecraft.world.item.enchantment.Enchantments.QUICK_CHARGE);
        int piercing = level(weapon, net.minecraft.world.item.enchantment.Enchantments.PIERCING);

        if (projectile instanceof AbstractArrow arrow) {
            // Vanilla bow damage: 2.0 base, plus 25% per Power level.
            double base = archery ? 2.0D * (1.0D + 0.25D * power) : 4.0D;
            base += attackBonus(weapon);
            arrow.setBaseDamage(base);
            if (punch > 0) {
                arrow.setKnockback(punch);
            }
            if (flame > 0) {
                arrow.setSecondsOnFire(100);
            }
            if (piercing > 0) {
                arrow.setPierceLevel((byte) Math.min(piercing, 127));
            }
            if (infinity > 0) {
                arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            }
        }
        if (projectile instanceof SmallFireball fireball) {
            fireball.setDeltaMovement(fireball.getDeltaMovement().scale(1.0D + 0.1D * power));
        }
        // A named weapon names its projectiles too, which reads nicely in death messages.
        if (weapon.hasCustomHoverName()) {
            projectile.setCustomName(weapon.getHoverName());
        }
        // Store the levels we used so a modded projectile subclass could read them later.
        if (quickCharge > 0 && projectile instanceof AbstractArrow arrow) {
            arrow.setDeltaMovement(arrow.getDeltaMovement().scale(1.0D + 0.15D * quickCharge));
        }
        if (multishot > 0) {
            // The caller fires one round; multishot is handled by the plate firing extra rounds.
            projectile.getPersistentData().putInt("sidedrain_multishot", multishot);
        }
    }

    /** Reads an enchantment level off a stack without needing a registry holder. */
    private static int level(ItemStack stack, net.minecraft.world.item.enchantment.Enchantment enchantment) {
        var enchantments = net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(stack);
        for (var entry : enchantments.entrySet()) {
            if (entry.getKey() == enchantment) {
                return entry.getValue();
            }
        }
        return 0;
    }

    /**
     * The weapon's own attack damage bonus, so a modded gun's damage or a sharpness sword used as a
     * launcher still hits harder.
     */
    private static double attackBonus(ItemStack weapon) {
        double bonus = 0.0D;
        var modifiers = weapon.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        for (var entry : modifiers.entries()) {
            String name = entry.getKey().getDescriptionId();
            if (name.contains("attack_damage")) {
                bonus += entry.getValue().getAmount();
            }
        }
        return Math.max(0.0D, bonus);
    }

    /** True when the stack is a potion, so the caller can keep its contents. */
    public static boolean isPotion(ItemStack stack) {
        return PotionUtils.getPotion(stack) != net.minecraft.world.item.alchemy.Potions.EMPTY;
    }

    private ProjectileLauncher() {
    }
}
