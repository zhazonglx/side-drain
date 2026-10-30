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

    /**
     * True when the stack is a melee weapon the plate can swing.
     *
     * <p>Three tests, in order, and the first one that answers decides:
     *
     * <ol>
     *   <li><b>Not ammunition, not a launcher, not a block.</b> Arrows and rockets belong to the
     *       projectile path; bow/crossbow/trident have their own draw logic the plate cannot drive;
     *       a BlockItem is a block, not a weapon - this is what keeps a dispenser from being
     *       classified as a club.</li>
     *   <li><b>Any attack damage attribute.</b> Every vanilla weapon carries
     *       {@code ATTACK_DAMAGE} on its main-hand modifiers, and so does every modded blade that
     *       builds on {@code SwordItem}, {@code TieredItem} or sets the attribute directly - which
     *       is how the pack's own weapons are recognised without this mod knowing their classes.</li>
     *   <li><b>Any tool class.</b> A handful of modded weapons deal their damage through custom code
     *       without publishing an attribute; a tool that claims to be a sword/pickaxe/axe/shovel/hoe
     *       family instance is accepted on that basis, because a tool is what the player was holding
     *       to fight with.</li>
     * </ol>
     */
    public static boolean isMelee(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        // The classification is the single source of truth. This method used to re-derive the answer
        // with its own rules, which is how a modded bow ended up classified as a melee weapon here
        // while the launcher considered it ranged. Everything that needs to know now asks
        // WeaponKind once.
        return WeaponKind.of(stack).usesTargetTags();
    }

    /**
     * True when the stack publishes any main-hand attack damage.
     *
     * <p>Used by {@link WeaponKind} as the "it is a weapon of some sort, just not one I recognise"
     * test, so it is kept separate from the family decision.
     */
    public static boolean hasAttackDamage(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var modifiers = stack.getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        for (var entry : modifiers.entries()) {
            if (entry.getKey().getDescriptionId().contains("attack_damage")
                    && entry.getValue().getAmount() > 0.0D) {
                return true;
            }
        }
        return false;
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
     * Swings the weapon once at everything in front of the plate.
     *
     * <p>Unlike {@link #attack(ServerLevel,​ BlockPos,​ Direction,​ ItemStack,​ Player,​ boolean,​ boolean,​ boolean,​ boolean)},
     * this takes the plate's real aim as a vector, so a plate tilted 45 degrees sweeps exactly the
     * cone its nozzle points along.
     *
     * @param meleePlayers    hit players
     * @param meleeMonsters   hit hostile mobs
     * @param meleeFriendlies hit peaceful creatures (animals, villagers, golems)
     * @param meleeNeutrals   hit neutral creatures (spiders, endermen, bees, unowned wolves)
     * @return true when at least one target was hit, so the caller can start its cooldown
     */
    public static boolean attack(ServerLevel level, BlockPos pos, Vec3 aim, ItemStack weapon,
                                 @Nullable Player owner, boolean meleePlayers, boolean meleeMonsters,
                                 boolean meleeFriendlies, boolean meleeNeutrals) {
        if (weapon.isEmpty() || !isMelee(weapon) || aim.lengthSqr() < 1.0E-6D) {
            return false;
        }
        Vec3 origin = Vec3.atCenterOf(pos);
        // The sweep volume: a box spanning the whole reach along the aim, widened a little across it
        // so it covers the plate face rather than a single ray. Built around the real aim vector -
        // building it around a snapped axis direction is what let targets standing inside a tilted
        // plate's cone escape unhit.
        double along = REACH + SPREAD;
        Vec3 far = origin.add(aim.x * along, aim.y * along, aim.z * along);
        double side = SPREAD + 0.5D;
        AABB box = new AABB(origin, far).inflate(side, side, side);
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity.isAlive() && !entity.isSpectator()
                        && isTargetable(entity, meleePlayers, meleeMonsters, meleeFriendlies, meleeNeutrals));
        if (targets.isEmpty()) {
            return false;
        }
        boolean hitAny = false;
        for (LivingEntity target : targets) {
            // Only things in front of the plate: the aim normal decides what the sweep covers.
            Vec3 toTarget = target.position().subtract(origin);
            if (toTarget.dot(aim) < 0.0D) {
                continue;
            }
            applyHit(level, pos, weapon, target, owner);
            hitAny = true;
        }
        return hitAny;
    }

    /**
     * Whether a candidate target is one of the categories the plate is switched on for.
     *
     * <p>The categories are the vanilla spawn classifications, which is what makes them cover every
     * mob a mod can add without this mod knowing its classes - a mod registers its mob into one of
     * the vanilla categories, so the classification check reaches it for free.
     *
     * <ul>
     *   <li>players are their own case - they are not in any spawn category,</li>
     *   <li>monsters are everything in {@code MONSTER},</li>
     *   <li>friendlies are the peaceful categories - {@code CREATURE}, {@code AMBIENT},
     *       {@code WATER_CREATURE}, {@code WATER_AMBIENT} and {@code AXOLOTLS},</li>
     *   <li>neutrals are every remaining living classification - the aggressive-when-provoked ones
     *       like spiders, endermen, bees and unowned wolves.</li>
     * </ul>
     */
    /**
     * Whether a candidate target is one of the categories the plate is switched on for.
     *
     * <p>Made public so the forward-target scanner can ask the same question: a weapon that needs a
     * target must aim at exactly the things the player already chose with these four switches, not at
     * a second, quietly different definition of "enemy".
     */
    public static boolean isTargetable(LivingEntity entity, boolean players, boolean monsters,
                                        boolean friendlies, boolean neutrals) {
        if (entity instanceof Player) {
            // A plate never hits the player looking at it through this filter unless players are on.
            return players;
        }
        return switch (entity.getClassification(false)) {
            case MONSTER -> monsters;
            case CREATURE, AMBIENT, WATER_CREATURE, WATER_AMBIENT, AXOLOTLS -> friendlies;
            default -> neutrals;
        };
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
