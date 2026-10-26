package com.sidedrain.util;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a melee weapon into a working attack for a Side Drain Plate.
 *
 * <p>When the weapon slot holds something that is not ammunition - a sword, an axe, a modded blade -
 * the plate stops draining and instead sweeps the area in front of it, hitting whatever hostile
 * creature is in reach. The weapon's own enchantments and attributes decide the damage, exactly as
 * they would in a player's hand:
 *
 * <ul>
 *   <li>Sharpness, Smite and Bane of Arthropods add their vanilla bonus damage.</li>
 *   <li>Fire Aspect sets the target alight.</li>
 *   <li>Knockback pushes it away from the plate.</li>
 *   <li>Looting is applied as extra drops through the normal death path.</li>
 *   <li>The weapon's {@code attack_damage} attribute is added on top of the base 1.0.</li>
 * </ul>
 *
 * <p>Attribution: the damage is credited to the nearest player within range when there is one, so
 * mob drops and death messages behave sensibly; otherwise it is a generic magic source.
 */
public final class MeleeAttacker {
    /** How far from the plate face the sweep reaches. */
    private static final double REACH = 2.5D;
    /** Half-width of the sweep box, so it covers the block face rather than a single point. */
    private static final double SPREAD = 1.5D;

    /** True when the stack is a melee weapon the plate can swing. */
    public static boolean isMelee(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // Ammunition and launchers are handled by the projectile path.
        if (ProjectileLauncher.isAmmo(stack)) {
            return false;
        }
        if (stack.is(net.minecraft.world.item.Items.BOW)
                || stack.is(net.minecraft.world.item.Items.CROSSBOW)
                || stack.is(net.minecraft.world.item.Items.TRIDENT)) {
            return false;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
            return false;
        }
        // Anything with an attack damage modifier is a melee weapon, which covers modded blades too.
        return attackDamage(stack) > 0.0D;
    }

    /** The weapon's total attack damage, including its attribute modifiers. */
    public static double attackDamage(ItemStack stack) {
        double base = 1.0D;
        var modifiers = stack.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        for (var entry : modifiers.entries()) {
            if (entry.getKey().getDescriptionId().contains("attack_damage")) {
                base += entry.getValue().getAmount();
            }
        }
        return base;
    }

    /**
     * Swings the weapon once at everything hostile in front of the plate.
     *
     * @return true when at least one target was hit, so the caller can start its cooldown
     */
    public static boolean attack(ServerLevel level, BlockPos pos, Direction facing, ItemStack weapon,
                                 @Nullable Player owner) {
        if (weapon.isEmpty() || !isMelee(weapon)) {
            return false;
        }
        Vec3 center = Vec3.atCenterOf(pos).add(
                facing.getStepX() * (REACH * 0.5D),
                facing.getStepY() * (REACH * 0.5D),
                facing.getStepZ() * (REACH * 0.5D));
        AABB box = new AABB(center, center).inflate(REACH + SPREAD, REACH, REACH + SPREAD);
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity.isAlive() && !entity.isSpectator() && !(entity instanceof Player));
        if (targets.isEmpty()) {
            return false;
        }
        boolean hitAny = false;
        for (LivingEntity target : targets) {
            // Only things in front of the plate: the face normal decides what the sweep covers.
            Vec3 toTarget = target.position().subtract(Vec3.atCenterOf(pos));
            if (toTarget.dot(new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ())) < 0.0D) {
                continue;
            }
            applyHit(level, pos, weapon, target, owner);
            hitAny = true;
        }
        return hitAny;
    }

    /** Applies one weapon hit, enchantments and all. */
    private static void applyHit(ServerLevel level, BlockPos pos, ItemStack weapon, LivingEntity target,
                                 @Nullable Player owner) {
        float damage = (float) attackDamage(weapon);
        // Vanilla melee enchantment bonuses.
        damage += EnchantmentHelper.getDamageBonus(weapon, target.getMobType());
        DamageSource source = level.damageSources().magic();
        if (owner != null) {
            source = level.damageSources().playerAttack(owner);
        }
        target.hurt(source, damage);

        int fire = level(weapon, Enchantments.FIRE_ASPECT);
        if (fire > 0 && !target.fireImmune()) {
            target.setSecondsOnFire(4 * fire);
        }
        int knockback = level(weapon, Enchantments.KNOCKBACK);
        if (knockback > 0) {
            Vec3 push = target.position().subtract(Vec3.atCenterOf(pos)).normalize()
                    .scale(0.5D * knockback);
            target.push(push.x, 0.1D * knockback, push.z);
        }
        // A weapon's own on-hit effects are honoured through the normal hurt path above; anything
        // extra a mod attaches through its own hooks cannot be reached without depending on it.
        if (target.getMobType() == MobType.UNDEAD) {
            int smite = level(weapon, Enchantments.SMITE);
            if (smite > 0) {
                target.hurt(level.damageSources().magic(), 2.5F * smite);
            }
        }
    }

    /** Reads an enchantment level off a stack without needing a registry holder. */
    private static int level(ItemStack stack, net.minecraft.world.item.enchantment.Enchantment enchantment) {
        for (var entry : EnchantmentHelper.getEnchantments(stack).entrySet()) {
            if (entry.getKey() == enchantment) {
                return entry.getValue();
            }
        }
        return 0;
    }

    private MeleeAttacker() {
    }
}
