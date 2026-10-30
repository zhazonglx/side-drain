package com.sidedrain.util;

import javax.annotation.Nullable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What the plate thinks is in its weapon slot.
 *
 * <h2>Why classification, not a pile of instanceof checks</h2>
 *
 * <p>The plate has to behave differently for four very different kinds of object: something it
 * swings, something it shoots, something it digs with, and a block that merely dispenses. Before this
 * class existed every consumer asked its own question - {@code MeleeAttacker.isMelee} here,
 * {@code ProjectileLauncher.isRangedWeapon} there - and the two disagreed about the same stack, which
 * is why a modded bow could be classified as a melee weapon and a modded sword as a launcher. One
 * classifier, one answer, one place to fix.
 *
 * <h2>How a modded weapon is recognised</h2>
 *
 * <p>Three independent signals, strongest first. A weapon only has to match one:
 *
 * <ol>
 *   <li><b>Vanilla classes and enchantments.</b> Bows, crossbows and tridents have their own classes;
 *       everything else is asked about its enchantments. An item carrying Power/Punch/Flame/Infinity
 *       is a bow, Multishot/Quick Charge/Piercing is a crossbow, Loyalty/Riptide/Channeling/Impaling is
 *       a trident. Enchantments are the single best signal because a modded bow <em>has</em> to accept
 *       the bow enchantments to be useful, whatever class it is built on.</li>
 *   <li><b>Forge tool actions.</b> {@code ToolActions.PICKAXE_DIG} and friends are what Forge itself
 *       uses to decide whether an item digs a block, and modded tools publish them. This is how a
 *       modded pickaxe is recognised as a pickaxe rather than as "a melee weapon with high damage".</li>
 *   <li><b>Registry names.</b> Last resort, for items that publish nothing. A gun mod's pistol usually
 *       has no vanilla signal at all; its registry id is the only thing that says what it is.</li>
 * </ol>
 *
 * <p>When none of the three answers, the item is {@link #UNKNOWN}. That is a real answer, not a
 * failure: the panel says so, and the plate falls back to a plain melee swing so the slot is never
 * silently inert.
 */
public enum WeaponKind {
    /** Nothing in the weapon slot. */
    EMPTY("gui.sidedrain.weapon.kind.empty"),
    /** A sword or another blade: swung at whatever is in front. */
    SWORD("gui.sidedrain.weapon.kind.sword"),
    /** A trident: thrown as a real projectile, with its own enchantments. */
    TRIDENT("gui.sidedrain.weapon.kind.trident"),
    /** A bow: fires arrows with Power, Punch and Flame. */
    BOW("gui.sidedrain.weapon.kind.bow"),
    /** A crossbow: fires arrows, honours Multishot, Quick Charge and Piercing. */
    CROSSBOW("gui.sidedrain.weapon.kind.crossbow"),
    /** A firearm: fires its own ammunition. */
    GUN("gui.sidedrain.weapon.kind.gun"),
    /** A staff: a casting weapon. */
    STAFF("gui.sidedrain.weapon.kind.staff"),
    /** A wand: a casting weapon, usually shorter ranged than a staff. */
    WAND("gui.sidedrain.weapon.kind.wand"),
    /** A spell book or similar: casts from stored spells. */
    SPELLBOOK("gui.sidedrain.weapon.kind.spellbook"),
    /** A pickaxe: mines the selected neighbour. */
    PICKAXE("gui.sidedrain.weapon.kind.pickaxe"),
    /** An axe: mines the selected neighbour. */
    AXE("gui.sidedrain.weapon.kind.axe"),
    /** A shovel: mines the selected neighbour. */
    SHOVEL("gui.sidedrain.weapon.kind.shovel"),
    /** A hoe: mines the selected neighbour. */
    HOE("gui.sidedrain.weapon.kind.hoe"),
    /** A dispenser or dropper: the plate fires its cargo instead of dropping it. */
    DISPENSER("gui.sidedrain.weapon.kind.dispenser"),
    /** Recognised as a weapon or tool, but no specific family. Falls back to a swing. */
    OTHER("gui.sidedrain.weapon.kind.other"),
    /** Nothing recognised it. Falls back to a swing so the slot is not inert. */
    UNKNOWN("gui.sidedrain.weapon.kind.unknown");

    /** Translation key for the panel label. */
    public final String key;

    WeaponKind(String key) {
        this.key = key;
    }

    /** True for the four families that mine instead of attacking. */
    public boolean isTool() {
        return this == PICKAXE || this == AXE || this == SHOVEL || this == HOE;
    }

    /** True for everything that attacks at range, where per-target tags make no sense. */
    public boolean isRanged() {
        return this == BOW || this == CROSSBOW || this == GUN || this == TRIDENT
                || this == STAFF || this == WAND || this == SPELLBOOK;
    }

    /** True for the three casting families, which fire without consuming an item. */
    public boolean isCaster() {
        return this == STAFF || this == WAND || this == SPELLBOOK;
    }

    /**
     * True when the four targeting switches are worth showing.
     *
     * <p>A caster is the case this has to get right, and the old rule got it backwards: it excluded
     * everything {@link #isRanged()}, which is exactly the family whose spells need a target. A staff
     * fired at empty air has its cast refused by the spell's own conditions, so the four switches and
     * the scan range are the only way the player can say what the plate should aim at - hiding them
     * left a caster panel with no targeting controls at all.
     *
     * <p>A bow, a crossbow, a trident and a gun still do not get them: an arrow or a bullet hits
     * whatever it hits, and the plate cannot make it spare the player's cows.
     */
    public boolean usesTargetTags() {
        if (this == EMPTY || this == DISPENSER || isTool()) {
            return false;
        }
        // Casting weapons pick their own target, so the switches are meaningful.
        if (isCaster()) {
            return true;
        }
        return !isRanged();
    }

    /**
     * True when the plate should run its weapon instead of moving items.
     *
     * <p>A dispenser is not in this set: it is a launcher body, and the plate keeps transferring items
     * while it fires them.
     */
    public boolean ownsThePlate() {
        return this != EMPTY && this != DISPENSER;
    }

    /**
     * Classifies a stack.
     *
     * @param stack the contents of the weapon slot
     * @return never null; {@link #EMPTY} for an empty slot
     */
    public static WeaponKind of(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        // 1. Dispensers and droppers are launcher bodies, checked before anything else so a
        //    dispenser is never mistaken for a blunt weapon.
        if (stack.is(Items.DISPENSER) || stack.is(Items.DROPPER)) {
            return DISPENSER;
        }
        // 2. Vanilla classes, which are unambiguous when they apply.
        if (stack.is(Items.BOW)) {
            return BOW;
        }
        if (stack.is(Items.CROSSBOW)) {
            return CROSSBOW;
        }
        if (stack.is(Items.TRIDENT)) {
            return TRIDENT;
        }
        // 3. Enchantments. This is what recognises a modded bow or trident built on its own class:
        //    a modded bow has to accept the bow enchantments to be worth anything.
        if (hasAny(stack, EnchantmentSigns.BOW)) {
            return BOW;
        }
        if (hasAny(stack, EnchantmentSigns.CROSSBOW)) {
            return CROSSBOW;
        }
        if (hasAny(stack, EnchantmentSigns.TRIDENT)) {
            return TRIDENT;
        }
        // 4. Forge tool actions: the same test Forge uses to decide whether an item digs a block.
        //    A modded pickaxe publishes PICKAXE_DIG even when its class says nothing useful.
        if (stack.canPerformAction(net.minecraftforge.common.ToolActions.PICKAXE_DIG)) {
            return PICKAXE;
        }
        if (stack.canPerformAction(net.minecraftforge.common.ToolActions.AXE_DIG)) {
            return AXE;
        }
        if (stack.canPerformAction(net.minecraftforge.common.ToolActions.SHOVEL_DIG)) {
            return SHOVEL;
        }
        if (stack.canPerformAction(net.minecraftforge.common.ToolActions.HOE_DIG)) {
            return HOE;
        }
        // 5. Vanilla tool classes, for anything that predates tool actions.
        if (stack.getItem() instanceof net.minecraft.world.item.PickaxeItem) {
            return PICKAXE;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.AxeItem) {
            return AXE;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.ShovelItem) {
            return SHOVEL;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.HoeItem) {
            return HOE;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.SwordItem) {
            return SWORD;
        }
        // 6. Registry names. The only signal a gun mod usually offers.
        WeaponKind byName = fromRegistryName(stack);
        if (byName != null) {
            return byName;
        }
        // 6b. A mod that publishes its own gun interface. This is what recognises a pack whose guns
        //     all share one generic item id - TACZ ships every firearm as "tacz:modern_kinetic_gun",
        //     which no noun in the name test can match, so without this a TACZ rifle was UNKNOWN and
        //     the plate fell back to a melee swing.
        if (com.sidedrain.util.GunAmmo.isGun(stack)) {
            return GUN;
        }
        // 7. A weapon that reports projectile ammunition is a launcher whatever it is called. This
        //    is what catches a modded gun whose id says nothing useful: if it declares what it
        //    shoots, it shoots. Reported as OTHER rather than GUN because "it shoots something" does
        //    not make it a firearm - it could be a slingshot or a blowpipe - and claiming GUN for
        //    those would be a worse answer than admitting the plate does not know the family.
        if (com.sidedrain.util.WeaponAmmo.profile(stack).needsItem()) {
            return OTHER;
        }
        // 7. A melee weapon that publishes attack damage but matches nothing above.
        if (com.sidedrain.util.MeleeAttacker.hasAttackDamage(stack)) {
            return OTHER;
        }
        return UNKNOWN;
    }

    /** Reads the registry id for the last-resort classification. */
    @Nullable
    private static WeaponKind fromRegistryName(ItemStack stack) {
        var id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        String path = id.getPath();
        // Ammunition is not a weapon, and its name is full of weapon nouns: "assault_rifle_ammo"
        // contains "rifle", so the noun test below would classify a magazine as a firearm and the
        // plate would try to shoot its own supply. Anything that names itself as a round, a shell or
        // a cell is left for the ammunition matcher, not the weapon classifier.
        if (com.sidedrain.util.ProjectileLauncher.isAmmoName(path)) {
            return null;
        }
        // Ranged families first: "machinegun" contains "gun", and "bullet" would otherwise win.
        if (path.contains("gun") || path.contains("pistol") || path.contains("rifle")
                || path.contains("shotgun") || path.contains("revolver") || path.contains("musket")
                || path.contains("cannon") || path.contains("blaster") || path.contains("launcher")
                || path.contains("cannon") || path.contains("minigun")) {
            return GUN;
        }
        if (path.contains("bow") && !path.contains("crossbow")) {
            return BOW;
        }
        if (path.contains("crossbow")) {
            return CROSSBOW;
        }
        if (path.contains("trident") || path.contains("spear") || path.contains("lance")) {
            return TRIDENT;
        }
        // Casting weapons get their own kinds, so the panel can say which one it is instead of
        // lumping a spell book in with a rifle. Checked before the melee nouns so "spellblade" is
        // not read as a blade.
        if (path.contains("spellbook") || path.contains("spell_book")
                || path.contains("grimoire") || path.contains("tome")) {
            return SPELLBOOK;
        }
        if (path.contains("wand") || path.contains("scepter") || path.contains("sceptre")) {
            return WAND;
        }
        if (path.contains("staff")) {
            return STAFF;
        }
        if (path.contains("pickaxe") || path.contains("pick") || path.contains("drill")) {
            return PICKAXE;
        }
        if (path.contains("axe") || path.contains("hatchet")) {
            return AXE;
        }
        if (path.contains("shovel") || path.contains("spade")) {
            return SHOVEL;
        }
        if (path.contains("hoe") || path.contains("scythe")) {
            return HOE;
        }
        if (path.contains("sword") || path.contains("blade") || path.contains("katana")
                || path.contains("dagger") || path.contains("rapier") || path.contains("saber")) {
            return SWORD;
        }
        return null;
    }

    private static boolean hasAny(ItemStack stack, net.minecraft.world.item.enchantment.Enchantment[] set) {
        var enchantments = net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(stack);
        for (var entry : enchantments.entrySet()) {
            for (var candidate : set) {
                if (entry.getKey() == candidate && entry.getValue() > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
