package com.sidedrain.util;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the enemy a weapon should be aimed at, in the volume the plate covers.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Some weapons cannot fire at empty air. Goety's spells are the clearest case: a large share of
 * them answer {@code false} to {@code ISpell#conditionsMet} unless the caster has a target, and the
 * target is found by ray-tracing along the caster's own look vector. A plate pointed at nothing has
 * no target, so the cast is refused and the wand looks broken even with a focus loaded.
 *
 * <p>A player solves that by looking at what they want to hit. A plate has no eyes, so it needs the
 * equivalent: a scan of the volume it covers, and then a look vector aimed at what the scan found.
 *
 * <h2>What "in front" means</h2>
 *
 * <p>The scan follows the plate's real aim vector - the same continuous vector
 * {@link ProjectileLauncher} fires along - so a plate tilted 45 degrees scans the cone its nozzle
 * points down, not the nearest cardinal direction. The box spans the configured range along that
 * vector and is widened across it, which is what lets a target standing beside the exact ray still
 * count.
 *
 * <h2>Which entities count</h2>
 *
 * <p>The four melee switches decide, through {@link MeleeAttacker#isTargetable}: players, monsters,
 * peaceful creatures and neutrals. Reusing that one definition is the point of the request - a
 * weapon that needs a target must aim at exactly what the player already chose, rather than at a
 * second, quietly different idea of "enemy".
 *
 * <p>The nearest valid target wins, because that is what a player shoots first.
 */
public final class TargetScanner {
    /** How far the scan reaches. Matches the documented 1-15 block setting. */
    public static final int MIN_RANGE = 1;
    public static final int MAX_RANGE = 15;
    /** Widening across the aim, so the scan is a cone and not a single ray. */
    private static final double SIDE = 1.5D;

    /**
     * The best target in front of {@code origin}, or {@code null} when there is none.
     *
     * @param origin     where the scan starts - the plate's muzzle
     * @param aim        the plate's real aim; a zero vector has no direction and finds nothing
     * @param range      how far along the aim to look, clamped to {@value #MIN_RANGE}..{@value #MAX_RANGE}
     * @param players    consider players
     * @param monsters   consider hostile mobs
     * @param friendlies consider peaceful creatures
     * @param neutrals   consider neutral creatures
     */
    @Nullable
    public static LivingEntity find(ServerLevel level, Vec3 origin, Vec3 aim, int range,
                                    boolean players, boolean monsters, boolean friendlies,
                                    boolean neutrals) {
        if (level == null || aim.lengthSqr() < 1.0E-6D) {
            return null;
        }
        int reach = clampRange(range);
        Vec3 far = origin.add(aim.x * reach, aim.y * reach, aim.z * reach);
        // inflate() after the union, so the corners of a diagonal aim are covered too - building the
        // box from the two endpoints alone would miss anything standing beside a diagonal ray.
        AABB box = new AABB(origin, far).inflate(SIDE, SIDE, SIDE);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity.isAlive() && !entity.isSpectator()
                        && MeleeAttacker.isTargetable(entity, players, monsters, friendlies, neutrals));
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            // Only things in front of the plate: the aim normal decides what the scan covers.
            Vec3 toTarget = candidate.position().subtract(origin);
            if (toTarget.dot(aim) <= 0.0D) {
                continue;
            }
            // Never aim through a wall - a spell cast at something behind one would hit the wall.
            HitResult hit = level.clip(new ClipContext(origin, candidate.getEyePosition(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, candidate));
            if (hit.getType() != HitResult.Type.MISS) {
                continue;
            }
            double distance = toTarget.lengthSqr();
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * The nearest valid target in any direction around {@code origin}.
     *
     * <p>The forward scan is the right rule for a gun - you shoot where you point - but it is the
     * wrong rule for a staff. A plate is bolted to a wall or a ceiling; the fight happens wherever it
     * happens, and a spell that refuses to cast because the mob stepped off the nozzle's centre line
     * is a spell the player will call broken. So a caster falls back to this: the nearest thing the
     * four switches allow, in any direction, within range.
     *
     * <p>Still line-of-sight checked, for the same reason as the forward scan: casting into a wall
     * that stands between the plate and the mob would be wasted, and Goety's spells check the same
     * thing internally.
     */
    @Nullable
    public static LivingEntity findNearest(ServerLevel level, Vec3 origin, int range,
                                           boolean players, boolean monsters, boolean friendlies,
                                           boolean neutrals) {
        if (level == null) {
            return null;
        }
        double reach = clampRange(range);
        AABB box = AABB.ofSize(origin, reach * 2.0D, reach * 2.0D, reach * 2.0D);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box,
                entity -> entity.isAlive() && !entity.isSpectator()
                        && MeleeAttacker.isTargetable(entity, players, monsters, friendlies, neutrals));
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates) {
            Vec3 toTarget = candidate.position().subtract(origin);
            double distance = toTarget.lengthSqr();
            // Compared before the line-of-sight clip: the clip is the expensive part, and a candidate
            // further away than the best one already found cannot win.
            if (distance >= bestDistance) {
                continue;
            }
            if (distance > reach * reach) {
                continue;
            }
            HitResult hit = level.clip(new ClipContext(origin, candidate.getEyePosition(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, candidate));
            if (hit.getType() != HitResult.Type.MISS) {
                continue;
            }
            bestDistance = distance;
            best = candidate;
        }
        return best;
    }

    /**
     * The yaw and pitch that point at {@code target}'s eye from {@code origin}.
     *
     * <p>Aimed at the eye rather than at the feet, so a spell fired from the plate actually reaches a
     * tall mob instead of stopping in the ground in front of it.
     */
    public static float[] lookAt(Vec3 origin, LivingEntity target) {
        Vec3 to = target.getEyePosition().subtract(origin);
        double horizontal = Math.sqrt(to.x * to.x + to.z * to.z);
        float yaw = (float) (Math.atan2(-to.x, to.z) * (180.0D / Math.PI));
        float pitch = (float) (-Math.atan2(to.y, horizontal) * (180.0D / Math.PI));
        return new float[] {yaw, pitch};
    }

    /** Clamps a requested range to the supported 1..15 blocks. */
    public static int clampRange(int range) {
        if (range < MIN_RANGE) {
            return MIN_RANGE;
        }
        if (range > MAX_RANGE) {
            return MAX_RANGE;
        }
        return range;
    }

    private TargetScanner() {
    }
}
