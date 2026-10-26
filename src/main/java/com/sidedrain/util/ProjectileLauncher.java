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
        String path = id.getPath();
        return path.contains("bullet") || path.contains("arrow") || path.contains("bolt")
                || path.contains("projectile") || path.contains("missile") || path.contains("dart")
                || path.contains("pellet") || path.contains("shell") || path.contains("rocket")
                || path.contains("grenade") || path.contains("charge");
    }

    /**
     * True when the stack may go in the weapon slot.
     *
     * <p>Bows, crossbows and tridents are the obvious ones; blocks that dispense are accepted too.
     * Everything that is not ammunition is allowed as well, because modded guns and wands are plain
     * items and the player should be able to try them. An item that cannot shoot does nothing.
     */
    public static boolean isLauncher(ItemStack stack) {
        if (stack.isEmpty() || isAmmo(stack)) {
            return false;
        }
        if (stack.is(Items.BOW) || stack.is(Items.CROSSBOW) || stack.is(Items.TRIDENT)) {
            return true;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (block == Blocks.DISPENSER || block == Blocks.DROPPER) {
                return true;
            }
        }
        return true;
    }

    /**
     * Fires {@code ammo} out of the plate once.
     *
     * @param strategy the plate's picking strategy, so the firing pattern follows the same idea
     * @return true when a projectile actually left the plate
     */
    public static boolean fire(Level level, BlockPos pos, Direction facing, ItemStack weapon, ItemStack ammo,
                               int strategy, NonNullList<ItemStack> filter) {
        if (!(level instanceof ServerLevel serverLevel) || ammo.isEmpty()) {
            return false;
        }
        Vec3 origin = Vec3.atCenterOf(pos).add(facing.getStepX() * 0.75D, facing.getStepY() * 0.75D,
                facing.getStepZ() * 0.75D);
        Entity projectile = spawn(serverLevel, origin, ammo);
        if (projectile == null) {
            return false;
        }
        aim(projectile, facing, weapon, strategy);
        serverLevel.addFreshEntity(projectile);
        serverLevel.levelEvent(1002, pos, 0);
        return true;
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
    private static void aim(Entity projectile, Direction facing, ItemStack weapon, int strategy) {
        boolean archery = weapon.is(Items.BOW) || weapon.is(Items.CROSSBOW);
        double power = archery ? 2.4D : 1.4D;
        // A little spread, larger for the random strategy so the pattern visibly differs.
        double spread = strategy == 0 ? 0.08D : 0.02D;
        double vx = facing.getStepX() + (Math.random() - 0.5D) * spread;
        double vy = facing.getStepY() + (Math.random() - 0.5D) * spread;
        double vz = facing.getStepZ() + (Math.random() - 0.5D) * spread;
        if (archery) {
            vy += 0.12D;
        }
        projectile.setDeltaMovement(vx * power, vy * power, vz * power);
        if (projectile instanceof FireworkRocketEntity firework) {
            firework.setDeltaMovement(vx * 0.6D, vy * 0.6D + 0.15D, vz * 0.6D);
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
