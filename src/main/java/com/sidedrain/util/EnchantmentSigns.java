package com.sidedrain.util;

import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * The enchantments that identify a weapon family.
 *
 * <p>These are the strongest available signal for a <b>modded</b> weapon, because an item only
 * accepts an enchantment if it opted into it, and a modded bow that refused Power would be useless.
 * A sword cannot carry Power, a bow cannot carry Sharpness, and a trident is the only thing that
 * takes Loyalty, Riptide, Channeling and Impaling.
 *
 * <p>Each array is deliberately the <em>exclusive</em> set for that family. Sharpness is not listed
 * for swords because every weapon accepts it; Multishot is not listed for bows because it is a
 * crossbow-only enchantment.
 */
final class EnchantmentSigns {
    /** Bow-only: Power, Punch and Flame. Infinity is shared with nothing else either. */
    static final Enchantment[] BOW = {
            Enchantments.POWER_ARROWS,
            Enchantments.PUNCH_ARROWS,
            Enchantments.FLAMING_ARROWS,
            Enchantments.INFINITY_ARROWS,
    };

    /** Crossbow-only: Multishot, Quick Charge and Piercing. */
    static final Enchantment[] CROSSBOW = {
            Enchantments.MULTISHOT,
            Enchantments.QUICK_CHARGE,
            Enchantments.PIERCING,
    };

    /**
     * Trident-only: Loyalty, Riptide, Channeling and Impaling.
     *
     * <p>This is the set the request named explicitly, and it is also the only vanilla weapon family
     * whose enchantments are truly exclusive - nothing else in the game accepts them.
     */
    static final Enchantment[] TRIDENT = {
            Enchantments.LOYALTY,
            Enchantments.RIPTIDE,
            Enchantments.CHANNELING,
            Enchantments.IMPALING,
    };

    private EnchantmentSigns() {
    }
}
