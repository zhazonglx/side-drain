package com.sidedrain.block.entity;

import com.sidedrain.block.SideDrainBlock;
import com.sidedrain.menu.SideDrainMenu;
import com.sidedrain.registry.ModBlockEntities;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The brain behind the Side Drain Plate.
 *
 * <p>Six modes, each a distinct direction of travel. None of them is a combination that undoes
 * itself:
 * <ul>
 *   <li>{@link #MODE_DRAIN} - pull items out of the container the plate is clamped onto and spit
 *       them into the world in front of the plate.</li>
 *   <li>{@link #MODE_FEED} - push items from the plate and its neighbours into the clamped
 *       container.</li>
 *   <li>{@link #MODE_COLLECT} - swallow item entities that land on top of the plate.</li>
 *   <li>{@link #MODE_COLLECT_FEED} - collect loose items and deliver them into the clamped
 *       container: a vacuum that fills a chest.</li>
 *   <li>{@link #MODE_DRAIN_STORE} - pull items out of the clamped container and keep them in the
 *       plate's own twenty-seven slots: a chest that empties a chest.</li>
 *   <li>{@link #MODE_LEAK} - drain the plate's <em>own</em> twenty-seven slots: the same eject
 *       behaviour as {@link #MODE_DRAIN}, but pointed at itself instead of at the container it is
 *       clamped onto.</li>
 *   <li>{@link #MODE_DRAIN_STORE_LEAK} - 抽存 + 泄漏: both of the above on one plate, store first
 *       and leak second, so the container empties itself out of the nozzle with the plate's own
 *       slots acting as a buffer.</li>
 * </ul>
 *
 * <p>{@code MODE_DRAIN_FEED} used to exist here: it drained the container and immediately fed the
 * same items back into it. That was not a function, it was a no-op loop that churned the container
 * and reset its change counters every eight ticks. It has been replaced by
 * {@link #MODE_COLLECT_FEED}, which moves items in one direction only.
 *
 * <p>Four picking strategies, three special plus one universal:
 * <ul>
 *   <li>{@link #STRATEGY_SLOT} - a single chosen slot, picked with alt + click in the GUI.</li>
 *   <li>{@link #STRATEGY_MOST} - the fullest slot.</li>
 *   <li>{@link #STRATEGY_FILTER} - only the items listed in the 9 filter slots.</li>
 *   <li>{@link #STRATEGY_RANDOM} - no preference at all, the universal fallback.</li>
 * </ul>
 *
 * <p>Two more switches: how much is taken each time ({@link #AMOUNT_ONE} / {@link #AMOUNT_STACK})
 * and whether the plate runs at all.
 *
 * <p>A weapon can be placed in the projectile slot. While the plate is draining and enabled, it
 * fires that weapon over and over; the picking strategy decides which ammunition is loaded.
 */
public class SideDrainBlockEntity extends RandomizableContainerBlockEntity {
    /** Storage of a plain plate: a small chest. */
    public static final int SLOT_COUNT = 27;
    /**
     * Storage of an expanded plate: exactly twice a plain plate, i.e. a large chest.
     *
     * <p>The slot count is per block entity rather than a constant because the expanded plate is
     * the same machine with a bigger box. Everything that walks the slots asks
     * {@link #slotCount()} - the constant would have quietly kept the expanded plate at
     * twenty-seven slots while its GUI drew fifty-four.
     */
    public static final int EXPANDED_SLOT_COUNT = 54;
    /** Filter grid of a plain plate: 3 x 3. */
    public static final int FILTER_COUNT = 9;
    /** Filter grid of an expanded plate: 6 x 6. */
    public static final int EXPANDED_FILTER_COUNT = 36;
    public static final int TRANSFER_INTERVAL = 8;

    public static final int MODE_DRAIN = 0;
    public static final int MODE_FEED = 1;
    public static final int MODE_COLLECT = 2;
    /** Collect loose items AND push everything the plate holds into the attached container. */
    public static final int MODE_COLLECT_FEED = 3;
    /** Pull items out of the attached container and keep them in the plate's own slots. */
    public static final int MODE_DRAIN_STORE = 4;
    /** Drain the plate's own slots, ejecting the items out of the nozzle. */
    public static final int MODE_LEAK = 5;
    /**
     * 抽存 + 泄漏: fill the plate from the attached container <em>and</em> empty it out of the nozzle.
     *
     * <p>The two halves are the existing {@link #MODE_DRAIN_STORE} and {@link #MODE_LEAK}, run one
     * after the other on the same tick. That makes the plate a <b>buffered pipe</b>: the container
     * behind it is slowly emptied into the plate's own twenty-seven slots, and whatever the plate
     * holds is thrown out of the nozzle. Items therefore travel container -> plate -> world, and the
     * plate keeps a working buffer instead of passing one item at a time.
     *
     * <p>Why it is a mode rather than "put two plates on the block": two plates need two faces, and
     * a container usually only offers one that is reachable. One plate doing both halves is also the
     * only way to get the buffer - two separate plates would each need their own storage.
     *
     * <p>Order matters. The store half runs first and the leak half second, so a plate that is
     * momentarily full still gets to empty itself on the same tick; the next tick then has room
     * again. Running them the other way round would leave a full plate stuck until the leak happened
     * to fire on its own cooldown.
     */
    public static final int MODE_DRAIN_STORE_LEAK = 6;
    public static final int MODE_COUNT = 7;

    public static final int STRATEGY_RANDOM = 0;
    public static final int STRATEGY_SLOT = 1;
    public static final int STRATEGY_MOST = 2;
    public static final int STRATEGY_FILTER = 3;
    /**
     * Slot-to-slot pairing: plate slot {@code i} is bound to container slot {@code i}.
     *
     * <p>Which way the items travel is the mode's decision, as with every other strategy - the
     * strategy only decides <em>which</em> slots participate. A draining mode takes container slot 5
     * and puts it in plate slot 5; a feeding mode takes plate slot 2 and puts it in container slot 2.
     * Nothing is ever re-ordered, which is what "格对格" (cell to cell) asks for: the two inventories
     * stay in lockstep, one pair per transfer.
     */
    public static final int STRATEGY_SLOT_TO_SLOT = 4;
    public static final int STRATEGY_COUNT = 5;

    /** Smallest and largest reach for loose items, in blocks. */
    public static final int MIN_RANGE = 0;
    public static final int MAX_RANGE = 5;
    public static final int DEFAULT_RANGE = 0;

    /** The forward scan range a freshly placed plate starts with, in blocks. */
    public static final int DEFAULT_TARGET_RANGE = 8;

    public static final int AMOUNT_ONE = 0;
    public static final int AMOUNT_STACK = 1;
    public static final int AMOUNT_COUNT = 2;

    /** Sized at construction: a plain plate holds 27 slots, an expanded one 54. */
    private NonNullList<ItemStack> items;
    /** Sized at construction: 3 x 3 for a plain plate, 6 x 6 for an expanded one. */
    private final NonNullList<ItemStack> filter;
    private ItemStack weapon = ItemStack.EMPTY;
    /**
     * The mode a freshly placed plate starts in.
     *
     * <p>This used to be {@link #MODE_DRAIN}, which meant a plate dropped onto a chest immediately
     * began pulling that chest's contents out and throwing them on the floor - a destructive default
     * for anyone who was only trying to place the plate. {@link #MODE_FEED} is the safe starting
     * point: a new plate feeds the container it is clamped to and does nothing else until the player
     * picks a mode in the GUI.
     *
     * <p>Only the default changes. Plates already in a world keep the mode in their NBT, so this
     * does not silently re-configure existing builds.
     */
    private int mode = MODE_FEED;
    private int strategy = STRATEGY_RANDOM;
    private int amount = AMOUNT_ONE;
    /** Slot of the attached container to pull from. */
    private int targetSlot;
    private boolean enabled = true;
    /**
     * How far the plate reaches for loose items, in blocks around itself.
     *
     * <p>Zero means the plate's own block only; five is the maximum and covers the five blocks
     * around it in every direction. The plate's own one block is always included, so this is never
     * "nothing at all".
     */
    private int range = DEFAULT_RANGE;
    /** Whether the plate is allowed to swing or fire the weapon in its weapon slot. */
    private boolean useWeapon = true;

    // Melee targeting tags. A plate swinging a weapon only hits the categories that are switched on.
    // Monsters default to on so an existing plate keeps its behaviour; everything else defaults to
    // off so a plate never turns on its owner's village by surprise.
    /** Whether the plate's melee weapon hits players. */
    private boolean meleePlayers;
    /** Whether the plate's melee weapon hits monsters (hostile mobs). */
    private boolean meleeMonsters = true;
    /** Whether the plate's melee weapon hits friendly creatures (animals, villagers, golems). */
    private boolean meleeFriendlies;
    /** Whether the plate's melee weapon hits neutral creatures (spiders, endermen, dolphins). */
    private boolean meleeNeutrals;
    /**
     * How far in front of the plate a target may be, in blocks.
     *
     * <p>Used by weapons that cannot fire at empty air - a Goety spell whose
     * {@code conditionsMet} demands a target, for instance. The plate scans that far along its aim
     * and aims at the nearest thing the four switches above allow, which is what lets such a weapon
     * fire at all. Zero-length ranges are meaningless, and the mod clamps to 1..15.
     */
    private int targetRange = DEFAULT_TARGET_RANGE;

    public boolean isMeleePlayers() {
        return this.meleePlayers;
    }

    /** How far in front of the plate a target may be, in blocks (1..15). */
    public int getTargetRange() {
        return this.targetRange;
    }

    /** Sets the forward scan range, clamped to 1..15 blocks. */
    public void setTargetRange(int range) {
        int clamped = com.sidedrain.util.TargetScanner.clampRange(range);
        if (clamped == this.targetRange) {
            return;
        }
        this.targetRange = clamped;
        this.setChanged();
    }

    /** Steps the forward scan range by {@code delta}, wrapping within 1..15. */
    public int stepTargetRange(int delta) {
        int span = com.sidedrain.util.TargetScanner.MAX_RANGE - com.sidedrain.util.TargetScanner.MIN_RANGE + 1;
        int next = com.sidedrain.util.TargetScanner.MIN_RANGE
                + Math.floorMod(this.targetRange - com.sidedrain.util.TargetScanner.MIN_RANGE + delta, span);
        this.setTargetRange(next);
        return this.targetRange;
    }

    /**
     * The nearest thing in front of the plate that the melee switches allow, or {@code null}.
     *
     * <p>This is the plate's equivalent of a player's eyes: it lets a weapon that needs a target
     * find one, instead of being refused because the plate is aimed at nothing.
     */
    @Nullable
    public net.minecraft.world.entity.LivingEntity findTargetAhead() {
        if (this.level == null) {
            return null;
        }
        Vec3 aim = this.aimVector();
        return com.sidedrain.util.TargetScanner.find((net.minecraft.server.level.ServerLevel) this.level,
                this.scanOrigin(aim), aim, this.targetRange, this.meleePlayers, this.meleeMonsters,
                this.meleeFriendlies, this.meleeNeutrals);
    }

    /**
     * Distance along {@code aim} to the nearest living thing in front of the plate, or 0 when there
     * is none inside the scan range.
     *
     * <p>Used to decide where a bullet may safely be born: a shooter standing further out than the
     * target would fire away from it, so the birth point has to be pulled back inside the target's
     * distance. The scan is deliberately generous - it ignores the four target switches and looks at
     * everything alive - because this is geometry, not targeting: a bullet has to start in front of
     * whatever is there, whether or not the plate would attack it.
     */
    private double closestTargetAlong(Vec3 aim) {
        if (this.level == null || this.level.isClientSide) {
            return 0.0D;
        }
        // Measured from the BLOCK CENTRE, and the result is a distance from the muzzle: the caller
        // adds it to muzzlePos(), so both have to agree on the same origin. Measuring from the muzzle
        // and then adding to the muzzle counted the 0.65 the muzzle already sticks out twice, which
        // pushed the birth point back past a target that was only just out of reach.
        Vec3 centre = Vec3.atCenterOf(this.worldPosition);
        Vec3 from = this.muzzlePos();
        double reach = com.sidedrain.util.TargetScanner.clampRange(this.targetRange);
        // Half a block of margin on the box, so a target standing right against the plate face is
        // still found even though its own bounding box starts at the block boundary.
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                from, from.add(aim.x * (reach + 1.0D), aim.y * (reach + 1.0D), aim.z * (reach + 1.0D)))
                .inflate(0.6D);
        double best = 0.0D;
        for (net.minecraft.world.entity.Entity entity : this.level.getEntities(
                (net.minecraft.world.entity.Entity) null, box,
                e -> e instanceof net.minecraft.world.entity.LivingEntity && e.isAlive()
                        && !e.isSpectator())) {
            Vec3 to = entity.position().add(0.0D, entity.getBbHeight() * 0.5D, 0.0D).subtract(centre);
            double alongCentre = to.dot(aim);
            if (alongCentre <= 0.0D) {
                continue;
            }
            // Only things actually in the firing cone: a target beside the plate is not in the way.
            double sideways = Math.sqrt(Math.max(0.0D, to.lengthSqr() - alongCentre * alongCentre));
            if (sideways > 1.2D) {
                continue;
            }
            // Distance measured from the muzzle, which is where the caller offsets from.
            double alongMuzzle = alongCentre - centre.distanceTo(from);
            if (alongMuzzle <= 0.0D) {
                // The target is beside or behind the muzzle already: the bullet cannot be born in
                // front of it, so the caller keeps the minimum clearance.
                continue;
            }
            if (best == 0.0D || alongMuzzle < best) {
                best = alongMuzzle;
            }
        }
        return best;
    }

    /**
     * Where a forward scan starts: the muzzle, pushed clear of the plate's own block.
     *
     * <p>{@code muzzlePos()} sits inside the plate's cell, and a line-of-sight clip that starts inside
     * a collider reports a hit on that collider - so every candidate looked blocked and the scan found
     * nothing at all. The same clearance the bullet needs is what the scan needs.
     */
    private Vec3 scanOrigin(Vec3 aim) {
        return this.muzzlePos().add(
                aim.x * MUZZLE_CLEARANCE, aim.y * MUZZLE_CLEARANCE, aim.z * MUZZLE_CLEARANCE);
    }

    public boolean isMeleeMonsters() {
        return this.meleeMonsters;
    }

    public boolean isMeleeFriendlies() {
        return this.meleeFriendlies;
    }

    public boolean isMeleeNeutrals() {
        return this.meleeNeutrals;
    }

    /** The four melee targeting tags packed into one bitmask for the panel's ContainerData. */
    public int meleeTagBits() {
        return (this.meleePlayers ? 1 : 0)
                | (this.meleeMonsters ? 2 : 0)
                | (this.meleeFriendlies ? 4 : 0)
                | (this.meleeNeutrals ? 8 : 0);
    }

    private int cooldown;
    private int fireCooldown;

    /**
     * How the plate is woken up.
     *
     * <ul>
     *   <li>{@link #ACTIVATION_CONTINUOUS} - the plate runs on its own transfer cycle, exactly as it
     *       always has.</li>
     *   <li>{@link #ACTIVATION_REDSTONE} - the plate is armed by a redstone edge. A lever flipped on
     *       or a button pressed next to it performs <b>one</b> transfer, and a plate that stays
     *       powered does not keep running: the next transfer needs a new rising edge, the way a
     *       piston or a 1.21 crafter behaves.</li>
     * </ul>
     */
    public static final int ACTIVATION_CONTINUOUS = 0;
    public static final int ACTIVATION_REDSTONE = 1;
    public static final int ACTIVATION_COUNT = 2;

    private int activation = ACTIVATION_CONTINUOUS;
    /**
     * Which of the six neighbouring faces a tool plate digs.
     *
     * <p>Only meaningful when the weapon slot holds a pickaxe, axe, shovel or hoe; the panel hides the
     * switch for every other kind, and {@link #weaponKind()} decides that.
     */
    private int digFace = 0;
    /** Memoised weapon classification, valid for one game tick. */
    @Nullable
    private com.sidedrain.util.WeaponKind cachedKind;
    private long cachedKindTick = Long.MIN_VALUE;
    /**
     * The redstone level seen on the previous tick.
     *
     * <p>Only the transition from "unpowered" to "powered" fires a pulse, so holding a lever down is
     * one transfer rather than a machine that never stops. The level itself (not a boolean) is
     * stored because a comparator or an analogue line can step a signal up and down without ever
     * going through zero, and every step up is a fresh activation.
     */
    private int lastSignal;
    /**
     * How many activations the plate still owes.
     *
     * <p>A count, not a flag. A lever flicked on-off-on faster than the transfer cooldown used to
     * lose the second edge entirely: the flag was already set, so the second rising edge was
     * indistinguishable from the first, and the plate performed one transfer where the player
     * pulled twice. Counting means every edge is honoured, in order, one transfer each.
     *
     * <p>The count is deliberately not bounded: an absurdly fast clock line will queue activations
     * and the plate will work through them, which is the behaviour a player wiring a clock expects.
     */
    private int pendingPulses;

    /** Memoised attached container; valid only for {@link #cachedContainerTick}. */
    @Nullable
    private Container cachedContainer;
    private long cachedContainerTick = -1L;
    private boolean cachedContainerResolved;

    private final Container filterView = new FilterView();
    private final Container weaponView = new WeaponView();
    /**
     * The plate's own twenty-seven slots, exposed as a {@link Container}.
     *
     * <p>Only used so {@link #leak()} can reuse {@link #pickSlot(Container)} and
     * {@code removeItem}, which keep the strategy handling in one place. Nothing else should hand
     * this out: it is a view onto {@link #items}, not a second inventory.
     */
    private final Container selfView = new SelfView();

    /** Number of channels in {@link #data}; the menu's {@code checkContainerDataCount} must match. */
    /**
     * The classification of the weapon slot, as an ordinal into {@code WeaponKind}.
     *
     * <p>Computed on the server from the actual stack and shipped to the client, because the client
     * never sees the weapon stack itself - the weapon slot is a {@code SimpleContainer} stub there.
     * Asking the client to classify would mean shipping the whole item, which is exactly what the
     * data slots exist to avoid.
     */
    public static final int DATA_WEAPON_KIND = 14;
    /** Which of the six faces a tool plate is digging, as an ordinal into {@code BlockMiner.FACES}. */
    public static final int DATA_DIG_FACE = 15;
    /**
     * What the weapon needs: 0 = nothing, 1 = consumes an item, 2 = needs one but keeps it.
     *
     * <p>The panel needs this to tell the player why a weapon is or is not firing. "It has no
     * ammunition" and "it needs nothing at all" look identical otherwise, and that ambiguity is what
     * made a working staff look broken.
     */
    public static final int DATA_AMMO_STATE = 16;
    /**
     * How far in front of the plate a target may be, in blocks (1..15).
     *
     * <p>Read by the panel so the control shows the plate's real value instead of always reading
     * zero. The panel only shows it for weapons that need a target - a gun aimed at empty air is
     * fine, a spell that insists on a target is not - and without this slot the screen could never
     * know what the server had stored.
     */
    public static final int DATA_TARGET_RANGE = 17;
    public static final int DATA_COUNT = 18;

    private final net.minecraft.world.inventory.ContainerData data =
            new net.minecraft.world.inventory.ContainerData() {
                @Override
                public int get(int index) {
                    return switch (index) {
                        case 0 -> mode;
                        case 1 -> strategy;
                        case 2 -> targetSlot;
                        case 3 -> amount;
                        case 4 -> enabled ? 1 : 0;
                        case 5 -> weapon.isEmpty() ? 0 : 1;
                        case 6 -> targetSize();
                        case 7 -> range;
                        case 8 -> useWeapon ? 1 : 0;
                        // The dispenser count, so the panel can say out loud which launcher mode the
                        // plate is in. Without it the plate looked identical with a dispenser and
                        // without one, which is exactly the "I cannot tell what it is doing" report.
                        case 9 -> dispenserPower();
                        // The four melee targeting tags packed into one bitmask, so the panel's
                        // switches show the plate's real state. Without this the switches always
                        // read zero on the client - which is why they looked dead: clicking them
                        // flipped server-side tags the screen could never see.
                        case 10 -> meleeTagBits();
                        // 0 continuous, 1 redstone. The panel needs it to label the button and to
                        // decide whether the "pulse pending" readout means anything.
                        case 11 -> activation;
                        // Slot and filter counts travel to the client so the panel can size itself:
                        // a plain plate is 27/9, an expanded one 54/36.
                        case 12 -> slotCount();
                        case 13 -> filterCount();
                        case DATA_WEAPON_KIND -> weaponKind().ordinal();
                        case DATA_DIG_FACE -> digFace;
                        case DATA_AMMO_STATE -> ammoProfile().requirement.ordinal();
                        case DATA_TARGET_RANGE -> targetRange;
                        default -> 0;
                    };
                }

                @Override
                public void set(int index, int value) {
                    switch (index) {
                        case 0 -> mode = value;
                        case 1 -> strategy = value;
                        case 2 -> targetSlot = value;
                        case 3 -> amount = value;
                        case 4 -> enabled = value != 0;
                        case 7 -> range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, value));
                        case 8 -> useWeapon = value != 0;
                        case 11 -> activation = Math.floorMod(value, ACTIVATION_COUNT);
                        // The kind and the dig face are read-only to the client: they are derived
                        // from the weapon stack, and letting the client write them would let a
                        // crafted packet claim a sword is a pickaxe.
                        default -> {
                        }
                    }
                }

                @Override
                public int getCount() {
                    return DATA_COUNT;
                }
            };

    public SideDrainBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.SIDE_DRAIN.get(), pos, state);
    }

    /**
     * Builds a plate of the size its block asks for.
     *
     * <p>The expanded plate is the same machine with a bigger box, so both blocks share this block
     * entity type; only the two inventory sizes differ, and they are read off the block instead of
     * being constants. A save written by an expanded plate that is somehow loaded on a plain plate
     * (a wrench carrier, a hand-edited save) simply gets its extra slots dropped by
     * {@link ContainerHelper#loadAllItems}, which ignores indices past the end.
     */
    public SideDrainBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
                                BlockPos pos, BlockState state) {
        super(type, pos, state);
        boolean expanded = state.getBlock() instanceof SideDrainBlock block && block.isExpanded();
        this.items = NonNullList.withSize(expanded ? EXPANDED_SLOT_COUNT : SLOT_COUNT, ItemStack.EMPTY);
        this.filter = NonNullList.withSize(expanded ? EXPANDED_FILTER_COUNT : FILTER_COUNT, ItemStack.EMPTY);
    }

    /** Storage slots this plate has: 27 plain, 54 expanded. */
    public int slotCount() {
        return this.items.size();
    }

    /** Filter cells this plate has: 9 plain (3 x 3), 36 expanded (6 x 6). */
    public int filterCount() {
        return this.filter.size();
    }

    public net.minecraft.world.inventory.ContainerData getData() {
        return this.data;
    }

    // ------------------------------------------------------------------ state

    public int getMode() {
        return this.mode;
    }

    public int getStrategy() {
        return this.strategy;
    }

    public int getTargetSlot() {
        return this.targetSlot;
    }

    public int getAmount() {
        return this.amount;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    /** Reach for loose items, in blocks around the plate. Zero still covers the plate's own block. */
    public int getRange() {
        return this.range;
    }

    public void setRange(int range) {
        this.range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, range));
        this.setChanged();
    }

    /** Steps the reach by one, wrapping back to the minimum past the maximum. */
    public int cycleRange() {
        this.range = this.range >= MAX_RANGE ? MIN_RANGE : this.range + 1;
        this.setChanged();
        return this.range;
    }

    /** Whether the weapon in the weapon slot may be used at all. */
    public boolean isUseWeapon() {
        return this.useWeapon;
    }

    public void setUseWeapon(boolean useWeapon) {
        this.useWeapon = useWeapon;
        this.setChanged();
    }

    public boolean toggleUseWeapon() {
        this.useWeapon = !this.useWeapon;
        this.setChanged();
        return this.useWeapon;
    }

    /**
     * True when the weapon slot holds a dispenser.
     *
     * <p>A dispenser is not a weapon the plate swings or fires - it is a <em>launcher body</em>.
     * With one installed, ejecting stops being a gentle drop and becomes a shot, and stacking more
     * dispensers in the slot makes that shot faster and longer ranged.
     */
    public boolean isDispenserMode() {
        return !this.weapon.isEmpty() && this.weapon.is(net.minecraft.world.level.block.Blocks.DISPENSER.asItem());
    }

    /** How many dispensers the shot is powered by; zero when not in dispenser mode. */
    public int dispenserPower() {
        return this.isDispenserMode() ? this.weapon.getCount() : 0;
    }

    /**
     * The muzzle speed a shot leaves with, for the current dispenser count.
     *
     * <p>Square root rather than linear: the first dispenser has to feel like a real upgrade, but a
     * full stack of sixty-four must not turn the plate into an artillery piece. With
     * {@code 0.30 + 0.34 * sqrt(n)} one dispenser throws about four blocks, sixteen about eleven and
     * a full stack about twenty - which is the whole point of the slot, and still inside the range
     * where the landing spot is predictable.
     */
    public double dispenserSpeed() {
        int power = this.dispenserPower();
        if (power <= 0) {
            return 0.0D;
        }
        return DISPENSER_BASE_SPEED + DISPENSER_SPEED_PER_SQRT * Math.sqrt(power);
    }

    /**
     * Ticks between two dispenser shots.
     *
     * <p>The plate's normal transfer cooldown is {@link #TRANSFER_INTERVAL}, which is tuned for
     * moving items between inventories. A dispenser is a firing mechanism, so in dispenser mode the
     * cooldown is this instead: it shortens with the number of dispensers, so more of them means both
     * a faster projectile and a faster firing cycle. It never goes below two ticks - one shot per
     * tick would drain a stack in a second and cost more entity ticks than the game can render.
     */
    public int dispenserInterval() {
        int power = this.dispenserPower();
        if (power <= 0) {
            return TRANSFER_INTERVAL;
        }
        return Math.max(MIN_DISPENSER_INTERVAL,
                MAX_DISPENSER_INTERVAL - (int) Math.round(Math.sqrt(power)));
    }

    public void setMode(int mode) {
        this.mode = Math.floorMod(mode, MODE_COUNT);
        // A mode change can point the plate at a different container, so the memo has to go.
        this.invalidateContainerCache();
        this.setChanged();
    }

    public void setStrategy(int strategy) {
        this.strategy = Math.floorMod(strategy, STRATEGY_COUNT);
        this.invalidateContainerCache();
        this.setChanged();
    }

    public void setTargetSlot(int slot) {
        this.targetSlot = Math.max(0, slot);
        this.setChanged();
    }

    public void setAmount(int amount) {
        this.amount = Math.floorMod(amount, AMOUNT_COUNT);
        this.setChanged();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.setChanged();
    }

    public Container getFilterView() {
        return this.filterView;
    }

    public Container getWeaponView() {
        return this.weaponView;
    }

    public NonNullList<ItemStack> getFilter() {
        return this.filter;
    }

    public ItemStack getWeapon() {
        return this.weapon;
    }

    /** How the plate is woken up: {@link #ACTIVATION_CONTINUOUS} or {@link #ACTIVATION_REDSTONE}. */
    public int getActivation() {
        return this.activation;
    }

    public void setActivation(int activation) {
        this.activation = Math.floorMod(activation, ACTIVATION_COUNT);
        // Switching back to continuous must not leave queued activations behind, and switching to
        // redstone must start from whatever the line says right now rather than from a stale level
        // that would fire the moment the plate is switched.
        this.pendingPulses = 0;
        this.lastSignal = 0;
        this.setChanged();
    }

    /** True when the plate owes at least one redstone activation. */
    public boolean isPulsePending() {
        return this.activation == ACTIVATION_REDSTONE && this.pendingPulses > 0;
    }

    /** Flips the activation mode; returns the new one. */
    public int toggleActivation() {
        this.setActivation(this.activation == ACTIVATION_REDSTONE ? ACTIVATION_CONTINUOUS : ACTIVATION_REDSTONE);
        return this.activation;
    }

    /** Cycles the mode; returns the new one. */
    public int cycleMode() {
        this.mode = (this.mode + 1) % MODE_COUNT;
        this.setChanged();
        return this.mode;
    }

    /** Cycles the strategy; returns the new one. */
    public int cycleStrategy() {
        this.strategy = (this.strategy + 1) % STRATEGY_COUNT;
        this.setChanged();
        return this.strategy;
    }

    /** Flips the enabled switch; returns the new state. */
    public boolean toggleEnabled() {
        this.enabled = !this.enabled;
        this.setChanged();
        return this.enabled;
    }

    // ------------------------------------------------------------------ container

    @Override
    public int getContainerSize() {
        return this.slotCount();
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }



    /**
     * Name shown in the panel. When several plates share one container the face they are clamped on
     * is appended, so two panels open on the same chest can be told apart.
     */
    @Override
    protected Component getDefaultName() {
        Component base = Component.translatable("container.sidedrain.side_drain_plate");
        int[] indexAndCount = com.sidedrain.util.PlateRegistry.indexAndCount(this);
        int count = Math.max(1, indexAndCount[1]);
        if (count <= 1) {
            return base;
        }
        int number = indexAndCount[0];
        Direction facing = this.getBlockState().getValue(SideDrainBlock.FACING);
        return base.copy().append(" ")
                .append(Component.translatable("gui.sidedrain.plate_index", number, count,
                        Component.translatable("gui.sidedrain.side." + facing.getSerializedName())));
    }

    @Override
    protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        return new SideDrainMenu(id, inventory, this);
    }

    // ------------------------------------------------------------------ nbt

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        // Re-sized, not just cleared: the constructor already picked the right size from the block,
        // but a block entity restored from a different plate keeps whatever it was built with, and
        // a mismatched size here silently drops or duplicates the tail of the inventory.
        boolean expanded = this.getBlockState().getBlock() instanceof SideDrainBlock block && block.isExpanded();
        this.items = NonNullList.withSize(expanded ? EXPANDED_SLOT_COUNT : SLOT_COUNT, ItemStack.EMPTY);
        if (!this.tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, this.items);
        }
        this.filter.clear();
        if (tag.contains("Filter", 10)) {
            ContainerHelper.loadAllItems(tag.getCompound("Filter"), this.filter);
        }
        this.weapon = tag.contains("Weapon", 10) ? ItemStack.of(tag.getCompound("Weapon")) : ItemStack.EMPTY;
        // Wrapped, not assigned raw. Adding a mode changes MODE_COUNT, and a save written by a newer
        // build then read by an older one (or a save hand-edited in a config) would hold an index the
        // switch statements have no case for - which silently falls through to MODE_DRAIN and drains
        // a chest the player never pointed at it. Clamping here keeps every loaded value valid.
        this.mode = Math.floorMod(tag.getInt("Mode"), MODE_COUNT);
        this.strategy = Math.floorMod(tag.getInt("Strategy"), STRATEGY_COUNT);
        this.amount = Math.floorMod(tag.getInt("Amount"), AMOUNT_COUNT);
        this.targetSlot = Math.max(0, tag.getInt("TargetSlot"));
        this.enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled");
        this.range = Math.max(MIN_RANGE, Math.min(MAX_RANGE,
                tag.contains("Range") ? tag.getInt("Range") : DEFAULT_RANGE));
        this.useWeapon = !tag.contains("UseWeapon") || tag.getBoolean("UseWeapon");
        // Melee targeting tags default the same way the fields do: monsters only, so a plate from
        // before this feature existed keeps hitting what it always hit.
        this.meleePlayers = tag.getBoolean("MeleePlayers");
        this.meleeMonsters = !tag.contains("MeleeMonsters") || tag.getBoolean("MeleeMonsters");
        this.meleeFriendlies = tag.getBoolean("MeleeFriendlies");
        this.meleeNeutrals = tag.getBoolean("MeleeNeutrals");
        this.targetRange = com.sidedrain.util.TargetScanner.clampRange(
                tag.contains("TargetRange")
                        ? tag.getInt("TargetRange") : DEFAULT_TARGET_RANGE);
        this.cooldown = tag.getInt("Cooldown");
        this.fireCooldown = tag.getInt("FireCooldown");
        // Clamped, for the same reason the mode is: a save from a build with more activations must
        // not load an index the switch has no case for.
        this.activation = Math.floorMod(tag.getInt("Activation"), ACTIVATION_COUNT);
        this.digFace = Math.floorMod(tag.getInt("DigFace"), com.sidedrain.util.BlockMiner.FACES.length);
        // The stored level is the plate's memory of "was I already powered". Losing it on load would
        // make a plate that is loaded while powered fire one extra time; keeping it means a lever
        // that was on when the chunk unloaded stays spent until it is flipped again.
        this.lastSignal = Math.max(0, tag.getInt("LastSignal"));
        this.pendingPulses = Math.max(0, tag.getInt("PendingPulses"));
    }

    @Override
    public void setRemoved() {
        this.invalidateContainerCache();
        super.setRemoved();
    }

    /**
     * The data a client needs to draw the plate's weapon.
     *
     * <p>A block entity's {@code saveAdditional} is only written to disk; the client receives this
     * tag instead. Without it the renderer would always see an empty weapon slot on a client, so the
     * plate would show its weapon only on a single-player host and never on a server.
     *
     * <p>It is the <b>whole</b> saved state, not just the weapon, because the client feeds this tag
     * straight into {@link #load(CompoundTag)} - a partial tag would reset the plate's mode, strategy
     * and inventory on the client every time the weapon changed. The cost is a few hundred bytes, and
     * it is only sent when the slot actually changes: see {@link #syncWeaponToClients()}.
     */
    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
                             net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            this.load(tag);
        }
    }

    /**
     * A box with room for the weapon decal.
     *
     * <p>The renderer paints the weapon's icon on the plate's decorated face, lifted a tenth of a
     * model unit clear of it. That is inside the block cell, so the default box would do - but the
     * box is also what {@code LevelRenderer} frustum-tests before it even calls the renderer, and a
     * decal sitting a hair off a face is exactly the kind of margin a strict test can disagree with
     * at glancing angles. The failure that produces is the worst one available here: an icon that
     * pops in and out as the player turns, which reads as a broken feature rather than as culling.
     *
     * <p>Half a block of slack costs one slightly larger frustum test and removes that whole class
     * of report.
     */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(this.worldPosition).inflate(0.5D);
    }

    /** Tells watching clients the weapon changed, so the renderer redraws it. */
    private void syncWeaponToClients() {
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(),
                    this.getBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!this.trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, this.items);
        }
        CompoundTag filterTag = new CompoundTag();
        ContainerHelper.saveAllItems(filterTag, this.filter);
        tag.put("Filter", filterTag);
        if (!this.weapon.isEmpty()) {
            tag.put("Weapon", this.weapon.save(new CompoundTag()));
        }
        tag.putInt("Mode", this.mode);
        tag.putInt("Strategy", this.strategy);
        tag.putInt("Amount", this.amount);
        tag.putInt("TargetSlot", this.targetSlot);
        tag.putBoolean("Enabled", this.enabled);
        tag.putInt("Range", this.range);
        tag.putBoolean("UseWeapon", this.useWeapon);
        tag.putBoolean("MeleePlayers", this.meleePlayers);
        tag.putBoolean("MeleeMonsters", this.meleeMonsters);
        tag.putBoolean("MeleeFriendlies", this.meleeFriendlies);
        tag.putBoolean("MeleeNeutrals", this.meleeNeutrals);
        tag.putInt("TargetRange", this.targetRange);
        tag.putInt("Cooldown", this.cooldown);
        tag.putInt("FireCooldown", this.fireCooldown);
        tag.putInt("Activation", this.activation);
        tag.putInt("DigFace", this.digFace);
        tag.putInt("LastSignal", this.lastSignal);
        tag.putInt("PendingPulses", this.pendingPulses);
    }

    // ------------------------------------------------------------------ menu buttons

    /**
     * Menu button ids: 0 mode, 1 strategy, 2 next slot, 3 previous slot, 4 amount, 5 enabled,
     * 6 range, 7 weapon use.
     */
    public void handleButton(int id) {
        switch (id) {
            case 0 -> this.cycleMode();
            case 1 -> this.cycleStrategy();
            case 2 -> this.stepTargetSlot(1);
            case 3 -> this.stepTargetSlot(-1);
            case 4 -> this.setAmount((this.amount + 1) % AMOUNT_COUNT);
            case 5 -> this.toggleEnabled();
            case 6 -> this.cycleRange();
            case 7 -> this.toggleUseWeapon();
            case 8 -> this.meleePlayers = !this.meleePlayers;
            case 9 -> this.meleeMonsters = !this.meleeMonsters;
            case 10 -> this.meleeFriendlies = !this.meleeFriendlies;
            case 11 -> this.meleeNeutrals = !this.meleeNeutrals;
            case 12 -> this.toggleActivation();
            // 13: clear the filter grid. With the filter cells acting as "record this item" pins
            // rather than as storage, emptying nine of them one right-click at a time is busywork.
            case 13 -> this.clearFilter();
            // 14 / 15: step the dig face. Only meaningful for a tool, and the panel only shows the
            // switches then, but a packet can still arrive - clamping keeps it harmless.
            case 14 -> this.cycleDigFace(-1);
            case 15 -> this.cycleDigFace(1);
            // 16 / 17: step the forward scan range. The panel only shows the control when the weapon
            // needs a target, but a packet can still arrive - clamping keeps it harmless.
            case 16 -> this.stepTargetRange(-1);
            case 17 -> this.stepTargetRange(1);
            default -> {
            }
        }
    }

    /** Moves the target slot inside the attached container, wrapping around its real size. */
    public void stepTargetSlot(int delta) {
        int size = this.targetSize();
        if (size <= 0) {
            this.setTargetSlot(0);
            return;
        }
        int next = Math.floorMod(this.targetSlot + delta, size);
        this.setTargetSlot(next);
    }

    // ------------------------------------------------------------------ ticking

    /**
     * State override for a plate that is not in the world.
     *
     * <p>A plate clamped onto a block reads its facing from the world. A plate travelling in a
     * player's hands has no world position, so this carries its state instead.
     */
    @Nullable
    private BlockState detachedState;

    public void setDetachedState(@Nullable BlockState state) {
        this.detachedState = state;
    }

    /**
     * The container this plate clamps onto while it is travelling in a player's hands.
     *
     * <p>A carried container keeps ticking in the carry manager, so it is alive as an object - but a
     * detached plate has no world position, and {@link #getAttachedContainer()} would otherwise go
     * looking for a container behind the player's feet. Binding the carried container here is what
     * keeps a plate on a carried chest draining that chest, keeps a carried plate with a melee
     * weapon swinging, and keeps the whole assembly working exactly as it did on the ground.
     */
    @Nullable
    private Container carriedContainer;

    public void setCarriedContainer(@Nullable Container container) {
        this.carriedContainer = container;
    }

    /**
     * Where a carried plate's muzzle is, in world space, or {@code null} when it is placed.
     *
     * <p>A placed plate has a fixed cell, so its muzzle is the centre of that cell pushed out along
     * the aim - which is right, because the plate is bolted to a wall and the nozzle really is at
     * the block's own face.
     *
     * <p>A carried plate has no cell at all. Falling back to {@code Vec3.atCenterOf(worldPosition)}
     * there meant the muzzle sat at the <b>player's feet</b>, so everything the plate spat out -
     * dropped items and fired projectiles alike - appeared around knee height and flew from there.
     * That is the "the shot comes out far too low" report. While carried, the carry manager binds
     * the shooter's real firing point here instead: the vanilla bow/crossbow one, which
     * {@code AbstractArrow}'s living-entity constructor defines as the shooter's eye height less
     * 0.1 of a block.
     */
    @Nullable
    private Vec3 carriedOrigin;

    public void setCarriedOrigin(@Nullable Vec3 origin) {
        this.carriedOrigin = origin;
    }

    /**
     * The point items and projectiles leave from.
     *
     * <p>One definition, so a carried plate cannot end up with its items and its arrows leaving
     * from two different heights - which is exactly what happened when {@code eject} and
     * {@code ProjectileLauncher} each computed their own offset from the block centre.
     */
    public Vec3 muzzlePos() {
        if (this.carriedOrigin != null) {
            return this.carriedOrigin;
        }
        Vec3 aim = this.aimVector();
        return Vec3.atCenterOf(this.worldPosition)
                .add(aim.x * 0.65D, aim.y * 0.65D, aim.z * 0.65D);
    }

    @Override
    public BlockState getBlockState() {
        // A plate that lives in the world reads its state from the world. Only a plate travelling in
        // a player's hands (no position of its own) falls back to the recorded state.
        if (this.detachedState != null && this.level != null && !this.hasWorldPosition()) {
            return this.detachedState;
        }
        return super.getBlockState();
    }

    /** True when this plate occupies a real position and its state can be read from the level. */
    private boolean hasWorldPosition() {
        return this.level != null && this.level.getBlockEntity(this.worldPosition) == this;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SideDrainBlockEntity plate) {
        // A block entity that has just been placed has no level yet; give it the one we were ticked
        // with, otherwise every container lookup silently returns null and the plate does nothing.
        if (plate.level == null) {
            plate.setLevel(level);
        }
        if (plate.detachedState != null && plate.detachedState != state) {
            plate.setDetachedState(state);
        }
        // The "enabled" switch and the redstone trigger are both about moving items. A weapon is not
        // a transfer: it is an instruction to attack, and it has its own switch (useWeapon).
        //
        // Gating weapons behind "enabled" meant a plate whose item transfer was switched off would
        // silently refuse to fire, which reads as "the gun is broken". The redstone trigger had the
        // same problem in reverse: it made every shot depend on a lever.
        // weaponKind() is memoised per game tick; calling WeaponKind.of() directly here would
        // re-run the whole classification (item checks, enchantment scans, tool-action probes) on
        // every plate every tick, and the answer is already cached for this tick.
        boolean weaponArmed = plate.useWeapon && !plate.weapon.isEmpty()
                && plate.weaponKind().ownsThePlate();
        if (!plate.enabled && !weaponArmed) {
            return;
        }
        if (plate.fireCooldown > 0) {
            --plate.fireCooldown;
        }
        // Redstone activation is checked before the transfer cooldown, and on every tick, because
        // the edge has to be caught even while the plate is still cooling down from its last
        // transfer. Missing an edge there would make a fast lever pull simply do nothing.
        if (plate.activation == ACTIVATION_REDSTONE && !weaponArmed
                && !plate.captureRedstoneEdge(level, pos)) {
            // Unpowered (or the pulse was already spent): the plate is idle. The cooldown is left
            // alone so that the single transfer a pulse buys is not eaten by a stale timer.
            return;
        }
        if (plate.cooldown > 0) {
            --plate.cooldown;
            return;
        }
        // A loaded weapon owns the plate. A melee weapon makes it swing at whatever is in front of
        // it; a launcher with ammunition makes it shoot. Either way nothing is pulled out of the
        // container until the weapon is removed or its ammunition runs out, so a gun can never empty
        // the chest it is feeding from.
        // Only the two modes that actually take items out of the attached container may own the
        // weapon. A plate that merely collects must never eat the chest it is clamped onto.
        // The draining family: the two modes that empty the attached container, plus MODE_LEAK, which
        // empties the plate's own slots. They share this flag because they all spit items out of the
        // nozzle, so the weapon slot owns them all.
        // The combined mode belongs here too: it ejects items out of the nozzle exactly like the
        // other two, so a weapon in the projectile slot must be allowed to own it.
        boolean draining = plate.mode == MODE_DRAIN
                || plate.mode == MODE_DRAIN_STORE
                || plate.mode == MODE_LEAK
                || plate.mode == MODE_DRAIN_STORE_LEAK;
        // A melee weapon owns the plate in every mode, not only the draining ones. A plate set to
        // 输送 with a sword used to keep moving items and never swing, which is why "I put a sword in
        // and nothing happened" kept coming up. A weapon in the slot is an instruction: swing it.
        if (plate.weaponIsMelee()) {
            // The cooldown is only spent on a swing that actually connected. An idle plate that
            // finds nothing in reach re-checks on the very next tick, so a monster that steps into
            // range is hit immediately instead of after the tail of a 12-tick swing it never saw;
            // and a monster that is being hit is only damaged once per swing interval, exactly as
            // a player's own attack speed would do it.
            if (plate.swingWeapon()) {
                plate.cooldown = MELEE_INTERVAL;
            } else if (plate.cooldown == 0) {
                plate.cooldown = 1;
            }
            // The swing was the activation, hit or miss: a lever that was pulled does not stay
            // armed just because the plate found nothing to hit.
            plate.spendPulse();
            return;
        }
        // A tool owns the plate the same way a weapon does: it digs the chosen neighbour instead of
        // moving items. Checked before the transfer path so a pickaxe never quietly drains a chest.
        if (plate.weaponIsTool()) {
            boolean dug = com.sidedrain.util.BlockMiner.mine(
                    (net.minecraft.server.level.ServerLevel) level, pos,
                    plate.level.getNearestPlayer(pos.getX() + 0.5D, pos.getY() + 0.5D,
                            pos.getZ() + 0.5D, 16.0D, false),
                    plate.weapon, plate.getDigFace());
            // A tool that cannot break the block retries sooner than one that just did, so a wall of
            // stone with the wrong pickaxe stops after one attempt instead of grinding every cycle.
            plate.cooldown = dug ? MELEE_INTERVAL : 2;
            plate.spendPulse();
            return;
        }
        // A dispenser is a launcher body, not ammunition: it fires on the plate's own transfer step
        // and never takes the "fire a projectile" path below, which is what the old code allowed
        // whenever the plate happened to hold a launcher next to a dispenser. The dispenser branch in
        // eject() is the only thing that should ever see it.
        // Same for a ranged weapon: it fires in every mode. The draining check that used to guard
        // this was inherited from the dispenser path, where "the plate fires instead of dropping" only
        // makes sense while it is dropping - but a bow in the slot is a request to shoot, and it was
        // being ignored outside the drain family.
        if (!plate.isDispenserMode() && plate.hasAmmunition()) {
            plate.fireWeapon();
            plate.cooldown = 2;
            plate.spendPulse();
            return;
        }
        // A firearm that cannot fire is the one failure the player cannot see the cause of: the plate
        // simply stops, with no bullet and no line in the log. Every "my gun stopped working" report
        // ended here. Rate limited to once every five seconds so a plate with no ammunition does not
        // fill the log.
        if (!plate.isDispenserMode() && !plate.weapon.isEmpty()
                && com.sidedrain.util.GunShooter.isFirearm(plate.weapon)) {
            long now = plate.level == null ? 0L : plate.level.getGameTime();
            if (now - plate.lastGunRefusalLog >= 100L
                    && com.sidedrain.SideDrain.LOGGER.isInfoEnabled()) {
                plate.lastGunRefusalLog = now;
                com.sidedrain.SideDrain.LOGGER.info(
                        "Gun idle: {} (magazine={}, loose rounds={})",
                        plate.weapon.getHoverName().getString(),
                        com.sidedrain.util.GunAmmo.loadedRounds(plate.weapon),
                        plate.findAmmo(plate.ammoProfile()).isEmpty() ? "none" : "available");
            }
        }
        // A loaded weapon means the plate is a weapon mount, not a conveyor: no transfer mode runs
        // while one is active.
        //
        // Without this guard a plate whose weapon simply could not fire on this tick - out of
        // ammunition, on cooldown, or with nothing in reach - would fall through and start moving
        // items. The weapon and the transfer would then fight over the same plate, and the player
        // would see their sword plate quietly draining a chest instead of attacking. Placed after the
        // firing attempt so a working weapon still gets its shot first.
        //
        // The dispenser is the one exception: it is a launcher body, and a dispenser plate is
        // *supposed* to keep transferring while it fires.
        if (plate.isUseWeapon() && !plate.isDispenserMode()
                && plate.weaponKind().ownsThePlate()) {
            plate.spendPulse();
            return;
        }
        boolean moved;
        if (plate.strategy == STRATEGY_SLOT_TO_SLOT) {
            // 格对格: one paired transfer per tick, whichever direction the mode defines. The mode
            // switch inside slotToSlotTransfer keeps the strategy a pure slot-pairing rule.
            //
            // The combined mode still needs its second half here: the pairing only describes the
            // store, and a slot-to-slot transfer ends with the item inside the plate, so without
            // the leak the plate would simply fill up and stop.
            boolean paired = plate.slotToSlotTransfer();
            boolean leaked = plate.mode == MODE_DRAIN_STORE_LEAK && plate.leak();
            moved = paired || leaked;
        } else {
            switch (plate.mode) {
                case MODE_FEED -> moved = plate.feed();
                case MODE_COLLECT -> moved = plate.collect();
                case MODE_COLLECT_FEED -> moved = plate.collectFeed();
                case MODE_DRAIN_STORE -> moved = plate.drainStore();
                case MODE_LEAK -> moved = plate.leak();
                case MODE_DRAIN_STORE_LEAK -> moved = plate.drainStoreLeak();
                default -> moved = plate.drain();
            }
        }
        if (draining && moved && plate.strategy == STRATEGY_SLOT_TO_SLOT) {
            // A slot-to-slot drain ends with the item inside the plate, not out of the nozzle, so
            // the weapon must not fire on a transfer that never ejected anything.
            plate.fireWeapon();
        }
        if (moved) {
            // In dispenser mode the firing cycle is the transfer cycle: more dispensers means a
            // shorter cooldown as well as a faster shot, which is the "speed follows the count"
            // behaviour the slot is supposed to have.
            plate.cooldown = plate.isDispenserMode() && draining
                    ? plate.dispenserInterval() : TRANSFER_INTERVAL;
            plate.setChanged();
        } else {
            plate.cooldown = 2;
        }
        // One activation, one attempt. In continuous mode this is a no-op; in redstone mode it
        // disarms the pulse so the plate stops until the line rises again.
        plate.spendPulse();
    }

    /**
     * Reads the redstone level around the plate and decides whether this tick is allowed to run.
     *
     * <p>The plate is a redstone <b>consumer</b> in the same sense a piston is: a rising edge arms
     * exactly one transfer. Two things follow from that and both are deliberate:
     *
     * <ul>
     *   <li>A lever left switched on does nothing after the first transfer. The level stays at 15,
     *       no new edge arrives, and the plate sits idle - which is what "拉动一次，输送一次" asks
     *       for. Flipping the lever off and on again is what buys the next transfer.</li>
     *   <li>An edge that arrives while the plate is cooling down is not lost. {@code pulsePending}
     *       stays set until a transfer actually happens, so a plate mid-cycle still performs its
     *       one transfer as soon as the cooldown clears.</li>
     * </ul>
     *
     * <p>A carried plate has no cell in the world, so there is nothing next to it to read: it keeps
     * running continuously, which is also the only behaviour that makes sense while it is flying
     * around in someone's hands.
     *
     * @return true when this tick may perform a transfer
     */
    private boolean captureRedstoneEdge(Level level, BlockPos pos) {
        if (level == null || this.detachedState != null && !this.hasWorldPosition()) {
            return true;
        }
        int signal = level.getBestNeighborSignal(pos);
        // A rising edge (including a step up an analogue line) arms one activation. A level that
        // stays put, or falls, arms nothing. Every edge counts, so flicking a lever faster than the
        // transfer cooldown performs one transfer per flick instead of silently dropping them.
        if (signal > this.lastSignal) {
            this.pendingPulses++;
            this.setChanged();
        }
        if (signal != this.lastSignal) {
            this.lastSignal = signal;
            this.setChanged();
        }
        return this.pendingPulses > 0;
    }

    /**
     * Spends the pulse that {@link #captureRedstoneEdge} armed.
     *
     * <p>Called once a transfer has actually been attempted, whether or not anything moved: a lever
     * pulled on an empty chest is still a spent activation, exactly like a piston that fires into
     * empty space. Leaving the pulse armed instead would make the plate retry forever the moment the
     * chest refilled, which is not an edge any more.
     */
    private void spendPulse() {
        if (this.activation != ACTIVATION_REDSTONE) {
            return;
        }
        if (this.pendingPulses > 0) {
            this.pendingPulses--;
            this.setChanged();
        }
    }

    /**
     * One paired transfer between the plate and the attached container: plate slot {@code i}
     * against container slot {@code i}.
     *
     * <p>The direction follows the mode, which is how the mode switch keeps its meaning under this
     * strategy:
     *
     * <ul>
     *   <li><b>Draining modes</b> (drain / drain-store): container slot {@code i} moves into plate
     *       slot {@code i}. MODE_DRAIN keeps the item in the plate's own slot - the pairing is the
     *       point - instead of ejecting it out of the nozzle.</li>
     *   <li><b>Feeding mode</b>: plate slot {@code i} moves into container slot {@code i}.</li>
     *   <li>Other modes (collect, collect-feed, leak) have no container-facing direction, so the
     *       strategy falls back to their normal behaviour - a collect plate still vacuums the floor
     *       and a leak plate still empties itself.</li>
     * </ul>
     *
     * <p>Only the first pair with something to move acts per tick, which keeps the transfer rate of
     * every strategy identical: one stack move every {@link #TRANSFER_INTERVAL} ticks, just aimed at
     * a fixed pair instead of a picked slot.
     *
     * @return true when something actually moved
     */
    private boolean slotToSlotTransfer() {
        Container attached = this.getAttachedContainer();
        if (attached == null) {
            return false;
        }
        int pairs = Math.min(this.slotCount(), attached.getContainerSize());
        for (int i = 0; i < pairs; i++) {
            ItemStack plateStack = this.items.get(i);
            ItemStack containerStack = attached.getItem(i);
            if (this.mode == MODE_FEED) {
                // Plate -> container, same slot index.
                if (plateStack.isEmpty()) {
                    continue;
                }
                // The moved count is measured BEFORE the insert mutates the working copy: tryInsert
                // shrinks the stack it is handed, so reading the count afterwards would always read
                // zero and the plate would never lose the item it just sent - the "plate output
                // never decreases while the table fills up out of nowhere" duplication.
                int before = plateStack.getCount();
                ItemStack moving = plateStack.copy();
                if (this.tryInsert(attached, i, moving)) {
                    int movedCount = before - moving.getCount();
                    if (movedCount <= 0) {
                        continue;
                    }
                    plateStack.shrink(movedCount);
                    if (plateStack.isEmpty()) {
                        this.items.set(i, ItemStack.EMPTY);
                    }
                    attached.setChanged();
                    this.setChanged();
                    return true;
                }
                continue;
            }
            // Draining modes: container -> plate, same slot index.
            if (containerStack.isEmpty()) {
                continue;
            }
            int wanted = this.amount == AMOUNT_STACK ? containerStack.getMaxStackSize() : 1;
            int taken = Math.min(wanted, containerStack.getCount());
            // Fill the paired plate slot: merge into the same item there, or land only when the
            // plate slot is empty, so the two inventories really do stay in lockstep.
            ItemStack target = this.items.get(i);
            if (target.isEmpty()) {
                this.items.set(i, attached.removeItem(i, taken));
                attached.setChanged();
                this.setChanged();
                return true;
            }
            if (ItemStack.isSameItemSameTags(target, containerStack)) {
                int room = target.getMaxStackSize() - target.getCount();
                if (room <= 0) {
                    continue;
                }
                int moved = Math.min(room, taken);
                target.grow(moved);
                attached.removeItem(i, moved);
                attached.setChanged();
                this.setChanged();
                return true;
            }
        }
        return false;
    }

    /** How many slots the attached container has, or 0 when there is none. */
    public int targetSize() {
        Container attached = this.getAttachedContainer();
        return attached == null ? 0 : attached.getContainerSize();
    }

    /**
     * The container behind the plate.
     *
     * <p>The answer is memoised for the current game tick. A single tick asks this question up to
     * six times - {@code collectFeed} calls it directly and then again through {@code feed}, the
     * panel polls {@link #targetSize()} once per broadcast, and the ammunition check adds one more -
     * and every miss costs a block state read, a block entity lookup and, for a block with no block
     * entity, a full entity AABB sweep. One lookup per tick is enough; the world cannot change under
     * us inside a single tick because all of this runs on the server thread.
     */
    @Nullable
    public Container getAttachedContainer() {
        // A carried container takes priority: while the host block is in a player's hands this is
        // the only container the plate can possibly be clamped to, and the world lookup below would
        // find whatever happens to sit behind the player's feet instead.
        if (this.carriedContainer != null) {
            return this.carriedContainer;
        }
        // A plate travelling in the hands has no block of its own in the world, so there is nothing
        // behind it to clamp onto. Falling through to the world lookup here made a carried plate
        // quietly drain whatever container happened to stand behind the player - a chest in the room
        // being emptied because someone walked past it carrying a plate. A detached plate that was
        // not handed a carried container simply has no container.
        if (this.detachedState != null && !this.hasWorldPosition()) {
            return null;
        }
        if (this.level == null) {
            return null;
        }
        long now = this.level.getGameTime();
        if (this.cachedContainerTick == now && this.cachedContainerResolved) {
            return this.cachedContainer;
        }
        // The plate is placed on the face it was clicked on, so its FACING points away from the
        // block it is clamped to; the container is behind it.
        Direction facing = this.getBlockState().getValue(SideDrainBlock.FACING);
        this.cachedContainer = com.sidedrain.util.Containers.getContainerAt(
                this.level, this.worldPosition.relative(facing.getOpposite()));
        this.cachedContainerTick = now;
        this.cachedContainerResolved = true;
        return this.cachedContainer;
    }

    /** Drops the memoised container so the next query re-reads the world. */
    public void invalidateContainerCache() {
        this.cachedContainerResolved = false;
        this.cachedContainer = null;
    }

    // ------------------------------------------------------------------ drain

    private boolean drain() {
        Container attached = this.getAttachedContainer();
        if (attached == null) {
            return false;
        }
        int slot = this.pickSlot(attached);
        if (slot < 0) {
            return false;
        }
        ItemStack source = attached.getItem(slot);
        if (source.isEmpty()) {
            return false;
        }
        int wanted = this.amount == AMOUNT_STACK ? source.getMaxStackSize() : 1;
        int taken = Math.min(wanted, source.getCount());
        ItemStack extracted = attached.removeItem(slot, taken);
        if (extracted.isEmpty()) {
            return false;
        }
        attached.setChanged();
        this.eject(extracted);
        return true;
    }

    /**
     * Drains the plate's own slots: the mirror image of {@link #drain()}.
     *
     * <p>{@code MODE_DRAIN} takes from the container behind the plate and throws the items out of the
     * nozzle. This mode takes from the plate itself and throws them out of the same nozzle, so a
     * plate that has been filled by {@code MODE_DRAIN_STORE} (or by hand) can be emptied onto the
     * floor, into a hopper, or into whatever the nozzle is aimed at.
     *
     * <p>The picking strategy, the amount switch and the filter all apply exactly as they do for
     * {@link #drain()}, because the plate's own slots are exposed through {@link #selfView} and go
     * through the same {@link #pickSlot(Container)}.
     *
     * <p>Like the other two draining modes, a loaded weapon owns the plate: it fires before this
     * method runs, so a plate with ammunition shoots instead of leaking. Ammunition is looked for in
     * the filter and in the attached container, never in the plate's own slots, so a leaking plate
     * cannot chamber its own cargo.
     */
    private boolean leak() {
        int slot = this.pickSlot(this.selfView);
        if (slot < 0) {
            return false;
        }
        ItemStack source = this.items.get(slot);
        if (source.isEmpty()) {
            return false;
        }
        int wanted = this.amount == AMOUNT_STACK ? source.getMaxStackSize() : 1;
        int taken = Math.min(wanted, source.getCount());
        ItemStack extracted = this.selfView.removeItem(slot, taken);
        if (extracted.isEmpty()) {
            return false;
        }
        this.eject(extracted);
        this.setChanged();
        return true;
    }

    /**
     * Collects loose items and delivers them into the attached container: a vacuum pointed at a
     * chest.
     *
     * <p>Items are taken from two places, in this order, and every one of them ends up in the
     * container - nothing is kept, so a plate in this mode is a pure one-way pipe from the ground
     * into the chest behind it:
     * <ol>
     *   <li>item entities resting against the plate, via {@link #collect()};</li>
     *   <li>anything the plate already holds in its own slots, via {@link #feedOwnItems(Container)}.</li>
     * </ol>
     *
     * <p>This replaces the old {@code MODE_DRAIN_FEED}. That mode drained the container and pushed
     * the very same items straight back into it, so in the common case it moved nothing at all while
     * still marking the container dirty every eight ticks. Here the source is the world and the
     * destination is the container, so there is always a real direction of travel.
     */
    private boolean collectFeed() {
        Container attached = this.getAttachedContainer();
        if (attached == null) {
            // Without a container there is nowhere to deliver, but swallowing the item and holding it
            // until a container appears is better than letting it despawn.
            return this.collect();
        }
        boolean moved = false;
        // 1. Loose items on the plate. collect() stores into the plate, so drain it back out below.
        if (this.collect()) {
            moved = true;
        }
        // 2. Everything the plate holds, including whatever collect() just picked up. Deliberately
        //    not feed(): that would also pull from the neighbouring containers, which is a different
        //    mode's job and would make this one quietly do more than "collect and deliver".
        if (this.feedOwnItems(attached)) {
            moved = true;
        }
        return moved;
    }

    /**
     * Pulls items out of the attached container and keeps them in the plate's own slots: a chest
     * that empties a chest.
     *
     * <p>This is the exact inverse of {@link #feed()}. The container is the source and the plate's
     * twenty-seven slots are the destination, so a plate in this mode slowly vacuums its neighbour
     * dry and can then be broken or carried away with the loot inside - {@code getDrops} already
     * copies the slot contents into the dropped item.
     *
     * <p>When the plate is full the mode stops on its own: {@link #insertSelf} finds no room and the
     * method returns false, so nothing is taken out of the container and dropped on the floor.
     */
    private boolean drainStore() {
        Container attached = this.getAttachedContainer();
        if (attached == null) {
            return false;
        }
        if (!this.hasRoomForAnything()) {
            // Full plate: leave the container untouched rather than removing items that cannot be
            // stored. This is the one place where "take one, then find there is no room" would
            // silently destroy items.
            return false;
        }
        int slot = this.pickSlot(attached);
        if (slot < 0) {
            return false;
        }
        ItemStack source = attached.getItem(slot);
        if (source.isEmpty()) {
            return false;
        }
        int wanted = this.amount == AMOUNT_STACK ? source.getMaxStackSize() : 1;
        // Copy first, store, and only then take that many out of the container, so a partial insert
        // can never lose the remainder. The removal goes through removeItem rather than shrinking
        // the stack in place: a modded container is allowed to hand back a copy from getItem(), and
        // shrinking that copy would silently duplicate the items.
        ItemStack moving = source.copyWithCount(Math.min(wanted, source.getCount()));
        int before = moving.getCount();
        this.insertSelf(moving);
        int stored = before - moving.getCount();
        if (stored <= 0) {
            return false;
        }
        ItemStack taken = attached.removeItem(slot, stored);
        if (taken.getCount() < stored) {
            // The container refused part of the removal. Put back whatever it did give us so the
            // plate and the container cannot drift apart.
            this.takeBack(taken.getCount());
        }
        attached.setChanged();
        this.setChanged();
        return true;
    }

    /**
     * 抽存 + 泄漏: the store half and the leak half of the plate, in one tick.
     *
     * <p>See {@link #MODE_DRAIN_STORE_LEAK} for why the two are combined and why the order is
     * store-then-leak. Both halves keep their own guarantees: {@link #drainStore()} refuses to take
     * from the container when the plate has no room, and {@link #leak()} ejects through the same
     * nozzle, with the same strategy, amount switch and filter as every other draining mode.
     *
     * <p>Returns true when either half moved something, which is what the caller uses to decide
     * whether to spend the transfer cooldown.
     */
    private boolean drainStoreLeak() {
        // Store first: a plate that filled up on the previous tick gets its chance to empty below,
        // and a plate that is full simply reports false here without touching the container.
        boolean stored = this.drainStore();
        // Leak second, and unconditionally: even when the store half found no room, whatever the
        // plate already holds should still be leaving.
        boolean leaked = this.leak();
        return stored || leaked;
    }

    /**
     * Removes up to {@code count} items from the plate's own slots.
     *
     * <p>Only used as a rollback when a container under-delivers on {@link Container#removeItem}, so
     * it is allowed to be approximate: it takes from the same tail-first order the insert used.
     */
    private void takeBack(int count) {
        for (int i = this.slotCount() - 1; i >= 0 && count > 0; i--) {
            ItemStack existing = this.items.get(i);
            if (existing.isEmpty()) {
                continue;
            }
            int take = Math.min(count, existing.getCount());
            existing.shrink(take);
            count -= take;
            if (existing.isEmpty()) {
                this.items.set(i, ItemStack.EMPTY);
            }
        }
        this.setChanged();
    }

    /** True when at least one of the plate's slots could accept any item at all. */
    private boolean hasRoomForAnything() {
        for (int i = 0; i < this.slotCount(); i++) {
            ItemStack existing = this.items.get(i);
            if (existing.isEmpty() || existing.getCount() < existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /** Chooses a source slot in {@code container} according to the active strategy. */
    private int pickSlot(Container container) {
        int size = container.getContainerSize();
        if (size <= 0) {
            return -1;
        }
        return switch (this.strategy) {
            case STRATEGY_SLOT -> {
                int slot = this.targetSlot;
                yield slot >= 0 && slot < size && !container.getItem(slot).isEmpty() ? slot : -1;
            }
            // The slot is an index into the attached container, so nothing else is needed here.
            case STRATEGY_MOST -> {
                int best = -1;
                int bestCount = 0;
                for (int i = 0; i < size; i++) {
                    ItemStack stack = container.getItem(i);
                    if (!stack.isEmpty() && stack.getCount() > bestCount) {
                        bestCount = stack.getCount();
                        best = i;
                    }
                }
                yield best;
            }
            case STRATEGY_FILTER -> {
                List<Integer> candidates = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    if (this.matchesFilter(container.getItem(i))) {
                        candidates.add(i);
                    }
                }
                if (candidates.isEmpty()) {
                    yield -1;
                }
                RandomSource random = this.level == null ? RandomSource.create() : this.level.random;
                yield candidates.get(random.nextInt(candidates.size()));
            }
            default -> {
                List<Integer> candidates = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    if (!container.getItem(i).isEmpty()) {
                        candidates.add(i);
                    }
                }
                if (candidates.isEmpty()) {
                    yield -1;
                }
                RandomSource random = this.level == null ? RandomSource.create() : this.level.random;
                yield candidates.get(random.nextInt(candidates.size()));
            }
        };
    }

    /**
     * True when {@code stack} is one of the items the filter pins.
     *
     * <p>A filter cell <b>records</b> an item; it does not hold it. The recorded stack is always a
     * single item with no count of its own, so the test is on the item and its tags, never on a
     * count - which is what makes a filter cell survive being "clicked empty" by the player.
     */
    public boolean matchesFilter(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (ItemStack entry : this.filter) {
            if (!entry.isEmpty() && ItemStack.isSameItemSameTags(entry, stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pins {@code stack}'s item into the first free filter cell.
     *
     * <p>The player's cursor is never touched: this is a <b>record</b>, not a deposit. Returns false
     * when the grid is full or already pins that exact item, so a caller can tell the difference
     * between "added" and "nothing to add" without scanning the grid itself.
     */
    public boolean addFilterItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        for (ItemStack entry : this.filter) {
            if (!entry.isEmpty() && ItemStack.isSameItemSameTags(entry, stack)) {
                return false;
            }
        }
        for (int i = 0; i < this.filter.size(); i++) {
            if (this.filter.get(i).isEmpty()) {
                // Count 1 and nothing else: the cell is a name tag, not a stack of items.
                this.filter.set(i, stack.copyWithCount(1));
                this.setChanged();
                return true;
            }
        }
        return false;
    }

    /**
     * Pins {@code stack}'s item into a specific filter cell.
     *
     * <p>Used by the panel's click gesture, which records into the cell the player actually clicked
     * rather than into the first free one. The stack is reduced to a single item for the same reason
     * {@link #addFilterItem} does it: a cell is a name tag, not a stack.
     *
     * @return true when the cell accepted the pin
     */
    public boolean setFilterSlot(int slot, ItemStack stack) {
        if (stack.isEmpty() || slot < 0 || slot >= this.filter.size()) {
            return false;
        }
        this.filter.set(slot, stack.copyWithCount(1));
        this.setChanged();
        return true;
    }

    /** Removes the filter pin in {@code slot}, if there is one. */
    public boolean clearFilterSlot(int slot) {
        if (slot < 0 || slot >= this.filter.size() || this.filter.get(slot).isEmpty()) {
            return false;
        }
        this.filter.set(slot, ItemStack.EMPTY);
        this.setChanged();
        return true;
    }

    /** Clears the whole filter grid; returns how many pins were removed. */
    public int clearFilter() {
        int removed = 0;
        for (int i = 0; i < this.filter.size(); i++) {
            if (!this.filter.get(i).isEmpty()) {
                this.filter.set(i, ItemStack.EMPTY);
                removed++;
            }
        }
        if (removed > 0) {
            this.setChanged();
        }
        return removed;
    }

    /** True when any filter slot has something in it. */
    public boolean hasFilter() {
        for (ItemStack entry : this.filter) {
            if (!entry.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pushes one stack out of the plate.
     *
     * <p>Two behaviours share this path, chosen by the weapon slot:
     *
     * <ul>
     *   <li>No dispenser - the stack is <em>dropped</em>, nudged gently along the plate's aim.</li>
     *   <li>A dispenser - the stack is <em>shot</em>: it leaves at speed instead of falling out of
     *       the plate, and every extra dispenser stacked in the weapon slot raises both the speed
     *       and the distance it travels. A full stack of sixty-four is a long, flat shot.</li>
     * </ul>
     *
     * <p>The aim itself is whatever the wrench set, so a shot fired from a 45 degree plate really
     * does travel at 45 degrees.
     *
     * <p><b>Gravity stays on, and that is the fix.</b> The shot used to set
     * {@code setNoGravity(true)}, which meant it never came down: the distance was whatever the
     * player's render distance allowed before the item despawned or the chunk unloaded, so a single
     * dispenser "reached" thirty blocks and a full stack reached the horizon. That is exactly the
     * "distance is not definite" symptom. With gravity on, the speed alone decides where the shot
     * lands, so the count of dispensers maps to a distance a player can actually aim with, and the
     * trajectory arc is the one the player expects to see.
     */
    private void eject(ItemStack stack) {
        if (this.level == null || this.level.isClientSide || stack.isEmpty()) {
            return;
        }
        // The aim comes from aimVector(), not from the stored FACING: a plate carried in a player's
        // hands points where the player looks, and reading FACING here is exactly what made a
        // carried plate keep spitting items along the face it was clamped to before it was lifted.
        Vec3 aim = this.aimVector();
        // muzzlePos(), not the block centre: a carried plate has no cell of its own, so the centre
        // of "its" position is the player's feet. See muzzlePos() for the full explanation.
        Vec3 center = this.muzzlePos();
        ItemEntity entity = new ItemEntity(this.level, center.x, center.y - 0.15D, center.z, stack);
        double speed = this.dispenserSpeed();
        if (speed > 0.0D) {
            // A dispenser shot: fast, ranged, and thrown slightly upwards so it clears the plate.
            entity.setDeltaMovement(aim.x * speed, aim.y * speed + DISPENSER_LIFT, aim.z * speed);
            entity.setPickUpDelay(10);
            if (this.level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                // Pitch rises with the muzzle speed, so a full stack audibly reports itself as more
                // powerful than a single dispenser without ever leaving the dispenser's own register.
                serverLevel.playSound(null, center.x, center.y, center.z,
                        net.minecraft.sounds.SoundEvents.DISPENSER_LAUNCH,
                        net.minecraft.sounds.SoundSource.BLOCKS, 0.7F,
                        (float) (0.9D + Math.min(0.6D, speed * 0.2D)));
            }
        } else {
            entity.setDeltaMovement(aim.x * 0.12D, aim.y * 0.12D + 0.08D, aim.z * 0.12D);
            entity.setDefaultPickUpDelay();
        }
        this.level.addFreshEntity(entity);
    }

    /** Speed a single dispenser gives a shot. */
    private static final double DISPENSER_BASE_SPEED = 0.30D;
    /**
     * Extra speed per sqrt of dispenser count.
     *
     * <p>Deliberately modest. An item entity carries no movement collision - vanilla lets it pass
     * through blocks whenever it is not currently overlapping one - so a shot that covers more than
     * a block per tick can tunnel through a wall. A vanilla arrow flies at about 3.0 blocks per tick
     * and is a proper projectile with its own hit detection; an item entity is not, so the speed is
     * kept well below that. With {@code 0.30 + 0.19 * sqrt(n)} a full stack of sixty-four reaches
     * about 1.8 blocks per tick, which is fast, flat and still short of the tunnelling range.
     */
    private static final double DISPENSER_SPEED_PER_SQRT = 0.19D;
    /**
     * Upward component added to a shot, so a flat-firing plate does not grind along the floor.
     *
     * <p>An item entity with no upward velocity is on the ground on tick one, and a grounded item
     * loses its horizontal velocity to friction more or less immediately - which made a flat shot
     * travel a couple of blocks no matter how many dispensers were installed. A tenth of a block per
     * tick is enough to clear the plate's own lip and let the projectile actually fly, and small
     * enough that it does not visibly arc.
     */
    private static final double DISPENSER_LIFT = 0.10D;
    /** The tick the "gun refused to fire" line was last written, so it is rate limited. */
    private long lastGunRefusalLog = Long.MIN_VALUE;

    /** Cooldown bounds for a dispenser shot, in ticks. */
    private static final int MAX_DISPENSER_INTERVAL = 8;
    private static final int MIN_DISPENSER_INTERVAL = 2;
    /**
     * How far the fake shooter's eye is pushed past the muzzle, in blocks.
     *
     * <p>TACZ's bullet is born at the shooter's eye and immediately ray-traces forward. {@code
     * muzzlePos()} sits 0.65 past the block centre, which leaves only 0.15 between the plate's face
     * and the bullet's own width - so a bullet born there hit the plate and vanished on its first
     * tick. The shooter therefore has to stand clear of the plate's block.
     *
     * <p>It must not stand <b>too</b> far clear, though. The clearance adds to the 0.65 the muzzle is
     * already out, and a bullet is born at the eye and flies <b>forward from there</b> - it cannot
     * travel backwards to reach something the shooter is standing past. At the old value of 1.0 the
     * birth point was 1.65 blocks out, so anything closer than that was behind the muzzle and could
     * never be hit: a mob standing against the plate was immune, which is the "the gun cannot hit the
     * enemy right in front of it" report. Half a block clears the plate's own collision box (0.5) and
     * the bullet's width, and leaves everything from just outside the plate face reachable.
     */
    private static final double MUZZLE_CLEARANCE = 0.55D;

    // ------------------------------------------------------------------ weapon

    /** Ticks between two melee swings. */
    public static final int MELEE_INTERVAL = 12;

    /** The plate's aim snapped to the nearest of the six axis directions. */
    private Direction aimDirection() {
        Vec3 aim = this.aimVector();
        Direction best = Direction.UP;
        double bestDot = -Double.MAX_VALUE;
        for (Direction candidate : Direction.values()) {
            double dot = aim.x * candidate.getStepX() + aim.y * candidate.getStepY() + aim.z * candidate.getStepZ();
            if (dot > bestDot) {
                bestDot = dot;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Where the nozzle points while this plate is carried: the carrier's own look direction.
     *
     * <p>A carried plate is not clamped to a container face any more, so the FACING it was placed
     * with has nothing to do with where the player is pointing - it kept firing along whatever
     * direction it had on the chest while the player walked around. While carried the aim follows
     * the carrier's look direction, updated every tick, so items and projectiles leave along where
     * the player is actually aiming.
     *
     * <p>Only the aim follows the player. The mode, the filter, the weapon, the pairing and the
     * stored FACING are untouched: put the block down and it points exactly where it used to.
     */
    @Nullable
    private Vec3 carriedAim;

    /** Binds the carried aim to the carrier's look direction; null for a placed plate. */
    public void setCarriedAim(@Nullable Vec3 aim) {
        this.carriedAim = aim;
    }

    /**
     * The exact unit vector the plate's nozzle points along, 45 degree steps included.
     *
     * <p>Every aim-aware consumer should use this rather than {@link #aimDirection()}: the six-axis
     * snap loses the 45 degree steps, which is what made a tilted plate sweep a box that did not
     * contain what its nozzle pointed at. A carried plate answers the carrier's look direction
     * instead of its stored facing - see {@link #setCarriedAim(Vec3)}.
     */
    private net.minecraft.world.phys.Vec3 aimVector() {
        if (this.carriedAim != null) {
            return this.carriedAim;
        }
        BlockState state = this.getBlockState();
        return com.sidedrain.util.PlateAim.ejectDirection(
                state.getValue(SideDrainBlock.FACING), state.getValue(SideDrainBlock.TILT),
                state.getValue(SideDrainBlock.SPIN));
    }

    /**
     * Drops the memoised weapon classification.
     *
     * <p>Called whenever the weapon slot changes. The cache is keyed on game time, which is right
     * for "the stack cannot change while nothing touches it", but a slot swap happens <em>during</em>
     * a tick - and for the rest of that tick the plate would keep acting on the old weapon's kind.
     */
    private void invalidateWeaponKind() {
        this.cachedKind = null;
        this.cachedKindTick = Long.MIN_VALUE;
    }

    /** What the weapon slot holds, classified once and cached for this tick. */
    private com.sidedrain.util.WeaponKind weaponKind() {
        if (this.weapon.isEmpty()) {
            return com.sidedrain.util.WeaponKind.EMPTY;
        }
        long now = this.level == null ? 0L : this.level.getGameTime();
        if (this.cachedKindTick == now && this.cachedKind != null) {
            return this.cachedKind;
        }
        this.cachedKind = com.sidedrain.util.WeaponKind.of(this.weapon);
        this.cachedKindTick = now;
        return this.cachedKind;
    }

    /**
     * True when the plate should swing at creatures instead of doing anything else.
     *
     * <p>This now covers every non-tool, non-ranged weapon, including the ones classified as
     * {@code OTHER} or {@code UNKNOWN}: a weapon nobody recognised still gets a plain swing rather
     * than leaving the slot silently inert.
     */
    public boolean weaponIsMelee() {
        if (!this.useWeapon || this.isDispenserMode()) {
            return false;
        }
        return this.weaponKind().usesTargetTags();
    }

    /** True when the weapon slot holds one of the four tool families. */
    public boolean weaponIsTool() {
        return this.useWeapon && this.weaponKind().isTool();
    }

    /** The face a tool plate digs, clamped into range. */
    public int getDigFace() {
        return Math.floorMod(this.digFace, com.sidedrain.util.BlockMiner.FACES.length);
    }

    public void setDigFace(int face) {
        this.digFace = Math.floorMod(face, com.sidedrain.util.BlockMiner.FACES.length);
        this.setChanged();
    }

    /** Steps the dig face by {@code delta}, wrapping through the six directions. */
    public int cycleDigFace(int delta) {
        this.setDigFace(this.getDigFace() + delta);
        return this.getDigFace();
    }

    /** The kind of weapon currently in the slot, for the panel. */
    public com.sidedrain.util.WeaponKind getWeaponKind() {
        return this.weaponKind();
    }

    /**
     * Swings the melee weapon at everything in front of the plate.
     *
     * @return true when at least one target was hit; the caller spends its cooldown only on a hit,
     *         so a plate that finds nothing in reach re-checks on the next tick instead of sleeping
     */
    public boolean swingWeapon() {
        if (this.level == null || this.level.isClientSide || !this.weaponIsMelee()) {
            return false;
        }
        // The swing follows the plate's real aim, so a plate tilted 45 degrees sweeps the cone its
        // nozzle points along instead of the nearest axis direction.
        net.minecraft.world.phys.Vec3 aim = this.aimVector();
        net.minecraft.world.entity.player.Player owner =
                this.level.getNearestPlayer(this.worldPosition.getX() + 0.5D,
                        this.worldPosition.getY() + 0.5D, this.worldPosition.getZ() + 0.5D, 16.0D, false);
        boolean hit = com.sidedrain.util.MeleeAttacker.attack(
                (net.minecraft.server.level.ServerLevel) this.level, this.worldPosition, aim,
                this.weapon, owner, this.meleePlayers, this.meleeMonsters,
                this.meleeFriendlies, this.meleeNeutrals);
        if (hit) {
            // Swinging wears the weapon the way a player's swing would. hurtAndBreak needs a real
            // holder, so an unowned plate simply does not consume durability rather than crashing.
            if (owner != null && !owner.getAbilities().instabuild) {
                this.weapon.hurtAndBreak(1, owner, player -> { });
            }
            this.setChanged();
        }
        return hit;
    }

    /** Memoised ammo profile, valid for one game tick alongside the weapon classification. */
    @Nullable
    private com.sidedrain.util.WeaponAmmo.Profile cachedProfile;
    /** The tick {@link #cachedProfile} was computed for. */
    private long cachedProfileTick = Long.MIN_VALUE;

    /** What the weapon in the slot needs before it can fire. */
    private com.sidedrain.util.WeaponAmmo.Profile ammoProfile() {
        long now = this.level == null ? 0L : this.level.getGameTime();
        if (this.cachedProfile != null && this.cachedProfileTick == now) {
            return this.cachedProfile;
        }
        this.cachedProfile = com.sidedrain.util.WeaponAmmo.profile(this.weapon);
        this.cachedProfileTick = now;
        return this.cachedProfile;
    }

    /**
     * True when the weapon slot holds something that can fire, and it has what it needs.
     *
     * <p>Three cases, and the distinction matters:
     *
     * <ul>
     *   <li>The weapon needs nothing (a mana-powered wand, a spell book with its own spells): it
     *       fires. Requiring ammunition that does not exist was the "my staff is ignored" report.</li>
     *   <li>The weapon needs an item <b>present but not spent</b> (a staff that casts through a spell
     *       book): it fires when that item is anywhere the plate can see, and the item is never
     *       taken. Treating it as ammunition would have destroyed the player's spell book.</li>
     *   <li>The weapon consumes an item per shot (bow, gun): it fires only while a matching item is
     *       available.</li>
     * </ul>
     */
    public boolean hasAmmunition() {
        if (!this.useWeapon || this.weapon.isEmpty() || this.isDispenserMode()) {
            return false;
        }
        // A firearm is checked first, and by GunShooter rather than by the ranged-weapon test: a gun
        // whose id says nothing useful is classified OTHER, which is not "ranged", and the old order
        // therefore refused to fire exactly the guns this mod was never taught about.
        if (com.sidedrain.util.GunShooter.isFirearm(this.weapon)) {
            return this.canFeedGun();
        }
        if (!com.sidedrain.util.ProjectileLauncher.isRangedWeapon(this.weapon)) {
            return false;
        }
        com.sidedrain.util.WeaponKind kind = com.sidedrain.util.WeaponKind.of(this.weapon);
        // A casting weapon never needs an item: a staff casts from soul energy (which the plate
        // supplies) or from mana, and its focus lives inside the wand itself. Requiring a spare
        // item here is what made a perfectly loaded wand refuse to fire.
        if (kind.isCaster()) {
            return true;
        }
        // An Infinity weapon never consumes a round, so it can fire regardless of ammunition.
        if (com.sidedrain.util.ProjectileLauncher.hasInfinity(this.weapon)) {
            return true;
        }
        com.sidedrain.util.WeaponAmmo.Profile profile = this.ammoProfile();
        if (!profile.needsItem()) {
            // Self-powered: nothing to look for.
            return true;
        }
        // Both remaining cases need the item to be reachable: CONSUMES spends one per shot,
        // REQUIRES keeps it but still has to see it.
        return !this.findAmmo(profile).isEmpty();
    }

    /**
     * True when the gun can fire: it has a round in it, or the plate can put one there.
     *
     * <h2>The gun's magazine <em>is</em> ammunition</h2>
     *
     * <p>The previous rule was the opposite - the magazine "belonged to the weapon", so the plate
     * refused to look at it and demanded loose rounds in its own storage instead. That is backwards.
     * A player who loads a rifle and sets it in the plate has loaded it: those rounds are real, they
     * were put there by hand, and the plate must be allowed to spend them. The rule as written meant a
     * perfectly loaded gun in a plate next to an empty chest fired exactly zero shots, which is the
     * "the plate will not shoot my gun" report.
     *
     * <p>So the magazine is the primary source, and the plate's storage is the <b>reload</b>: when the
     * magazine runs dry the plate moves one real round from its own slots (or the container it is
     * clamped onto) into the gun. Nothing is created - the round is taken from storage, and the gun is
     * only ever loaded with rounds the plate actually held.
     */
    private boolean canFeedGun() {
        if (com.sidedrain.util.GunAmmo.loadedRounds(this.weapon) > 0) {
            // A round is in the gun. It can fire, whatever is in storage.
            return true;
        }
        // Empty magazine: a reload needs a real round to move into it.
        return !this.findAmmo(this.ammoProfile()).isEmpty();
    }

    /**
     * Moves one real round from the plate's storage into an empty gun, the way a reload does.
     *
     * <p>Called immediately before a shot that would otherwise be refused for an empty magazine. The
     * round is taken out of storage first and only written into the gun if it was really taken, so a
     * failed reload cannot conjure ammunition.
     *
     * @return true when the gun now holds a round
     */
    private boolean reloadGun() {
        if (!com.sidedrain.util.GunShooter.isFirearm(this.weapon)) {
            return false;
        }
        if (com.sidedrain.util.GunAmmo.loadedRounds(this.weapon) > 0) {
            // Already loaded. Nothing to do, and nothing to charge for.
            return true;
        }
        ItemStack round = this.takeAmmo();
        if (round.isEmpty()) {
            return false;
        }
        if (!com.sidedrain.util.GunAmmo.loadRounds(this.weapon, 1)) {
            // A gun whose mod does not publish a magazine cannot be loaded this way - the generic
            // path handles it by parking the round in the shooter's inventory instead. The round goes
            // back rather than vanishing.
            this.returnAmmo(round);
            return false;
        }
        this.setChanged();
        return true;
    }

    /**
     * Spends one real round after a shot the plate's creative shooter fired for free.
     *
     * <p>The order mirrors {@link #findAmmo()}: the plate's own slots first, then the container it is
     * clamped onto. Only a stack the weapon actually accepts is taken, so a rifle does not eat arrows
     * from a chest that also holds a bow's supply.
     *
     * @return true when a round was found and removed
     */
    private boolean payForShot() {
        java.util.function.Predicate<ItemStack> match = this.matcherFor(this.ammoProfile());
        for (int i = 0; i < this.slotCount(); i++) {
            ItemStack entry = this.items.get(i);
            if (!entry.isEmpty() && match.test(entry)) {
                this.items.get(i).shrink(1);
                if (this.items.get(i).isEmpty()) {
                    this.items.set(i, ItemStack.EMPTY);
                }
                this.setChanged();
                return true;
            }
        }
        Container attached = this.getAttachedContainer();
        if (attached == null) {
            return false;
        }
        int slot = this.pickSlotFor(attached, match);
        if (slot < 0) {
            return false;
        }
        attached.removeItem(slot, 1);
        attached.setChanged();
        return true;
    }

    /**
     * Pulls one focus item (聚晶) out of the plate's storage or the attached container.
     *
     * <p>The item is <b>extracted</b>, not copied: a focus moved into a wand is real storage, and a
     * plate that duplicated them would be an item duplicator. The caller puts it back if the wand
     * refuses it.
     *
     * @return a single focus, or {@code EMPTY} when none is reachable
     */
    private ItemStack extractFocus() {
        for (int i = 0; i < this.slotCount(); i++) {
            ItemStack entry = this.items.get(i);
            if (!entry.isEmpty() && com.sidedrain.util.FocusLoader.isFocus(entry)) {
                return this.items.get(i).split(1);
            }
        }
        Container attached = this.getAttachedContainer();
        if (attached == null) {
            return ItemStack.EMPTY;
        }
        int slot = this.pickSlotFor(attached, com.sidedrain.util.FocusLoader::isFocus);
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = attached.removeItem(slot, 1);
        attached.setChanged();
        return taken;
    }

    /**
     * Fires the gun in the weapon slot, whichever kind of gun it is.
     *
     * <p>The fake player is the shooter of record, because gun mods key their state by living entity
     * and a plate is not one. It is concealed immediately afterwards for the same reason as in the
     * fake-player path: an unkillable entity must never stay targetable.
     *
     * <p>The actual firing is delegated to {@link com.sidedrain.util.GunShooter}, which tries the gun
     * mod's own operator first, then a registered dispenser behaviour, then a generic "load the gun
     * and use it" path. That last path is the one that was broken: it used to be reached with the round
     * still in the plate's hand and an empty shooter inventory, so every gun that checks its own
     * ammunition refused - and the plate billed a round for a shot that never happened.
     */
    private boolean fireDedicatedShooter() {
        net.minecraft.server.level.ServerLevel serverLevel =
                (net.minecraft.server.level.ServerLevel) this.level;
        net.minecraft.world.entity.LivingEntity shooter =
                this.nearestShooterTarget(serverLevel);
        if (shooter == null) {
            return false;
        }
        // The mod reads the gun from the shooter's hand, not from the argument, so the weapon has to
        // be in it. Positioned and aimed first, because these mods build their bullet from the
        // shooter's eye position and look vector.
        //
        // The eye is pushed well clear of the plate's own block. TACZ's EntityKineticBullet is born
        // at (shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ()) and ray-traces forward on its
        // first tick; muzzlePos() only clears the block face by 0.15, which is inside the plate's own
        // hitbox width, so a bullet born there hit the plate itself and vanished before it could fly
        // - the shot was "successful", a round was billed, and nothing ever left the barrel. One
        // block out is past that, and a wall that close deserves to block the shot anyway.
        Vec3 aim = this.aimVector();
        // Where the bullet may be born. TACZ's hit detection is a segment test that starts at the
        // bullet's OWN position and runs forward (EntityUtil.findEntityOnPath over the projectile's
        // bounding box moved by its motion), so a bullet born past a mob can never reach it - the mob
        // is behind the segment's start. That is why a target pressed against the plate was immune no
        // matter how the shooter was rotated.
        //
        // So the birth point is chosen from the plate face outwards, never further than the nearest
        // thing in front of it. The clearance below is only a MINIMUM (enough to leave the plate's own
        // collider); a close target pulls the birth point back toward the plate instead of pushing it
        // past the target.
        double closest = this.closestTargetAlong(aim);
        double lead = MUZZLE_CLEARANCE;
        if (closest > 0.0D) {
            // Stop just short of the target, so the bullet starts in front of it with a little room
            // rather than inside its hitbox.
            lead = Math.min(lead, Math.max(0.05D, closest - 0.35D));
        }
        // Never born inside the plate's own block: the muzzle already sits 0.65 out, so a lead of 0
        // would still be past the face - but a negative one would not, and the eye offset below is
        // subtracted from the shooter's Y. Clamped rather than trusted.
        lead = Math.max(0.0D, lead);
        Vec3 origin = this.muzzlePos().add(aim.x * lead, aim.y * lead, aim.z * lead);
        // Position first, then aim. aimShooter() keeps the shooter where it already is and only turns
        // it - so calling it after the move is what preserves the muzzle clearance above, while
        // getting the head rotation the look vector actually reads. (moveTo() writes yRot only; a
        // living entity's view direction comes from yHeadRot, which nothing but a tick would update on
        // this never-ticked fake player. That was the "the gun shoots the wrong way" report: every
        // shot left due south no matter where the plate pointed.)
        shooter.moveTo(origin.x, origin.y - shooter.getEyeHeight(), origin.z,
                shooter.getYRot(), shooter.getXRot());
        com.sidedrain.util.DispenserAdapter.aimShooter(shooter, aim);
        // The round the plate is willing to spend goes to the shooter, not to the firing call: a gun
        // mod looks for its ammunition in the shooter's inventory, and handing it over here is what
        // lets a gun the plate has never heard of fire on its own terms. It is taken back inside
        // GunShooter whether or not the shot happens.
        // Only hand a round over when the gun's own magazine is empty. If reloadGun() already put a
        // round in the magazine, that round is the one this shot spends - passing a second one would
        // charge the plate twice for a single bullet.
        ItemStack round = com.sidedrain.util.GunAmmo.loadedRounds(this.weapon) > 0
                ? ItemStack.EMPTY
                : this.findAmmo(this.ammoProfile());
        boolean fired = com.sidedrain.util.GunShooter.fire(serverLevel, shooter, this.weapon, aim, round);
        // Billing. GunShooter reports how many rounds the plate owes for this shot, and the number is
        // zero for the paths that already spent real ammunition themselves:
        //
        //   - the mod's own operator takes the round out of the gun's magazine, which was loaded with
        //     rounds the player put there, so the plate owes nothing;
        //   - the generic path spends the round the plate parked in the shooter's inventory;
        //   - only a dispenser behaviour, which is handed nothing, is billed here.
        //
        // Charging the plate for all three is what made a rifle stop firing after a dozen rounds with
        // a chest full of ammunition still sitting beside it.
        int shots = com.sidedrain.util.GunShooter.takeShotCount();
        for (int i = 0; i < shots; i++) {
            if (!this.payForShot()) {
                // The plate could not pay for the round it just fired. It is still fired - the bullet
                // is in the world - so the honest response is to stop firing until the player refills,
                // which hasAmmunition() enforces before the next shot.
                com.sidedrain.SideDrain.LOGGER.info(
                        "Plate could not pay for a shot: no ammunition left in storage");
                break;
            }
        }
        shooter.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        // The shooter is the same cached fake player the other path uses, so it must be put away for
        // the same reason: mobs would otherwise lock onto something they can never kill.
        com.sidedrain.util.DispenserAdapter.concealShooter(serverLevel, shooter);
        return fired;
    }

    /**
     * The living entity to attribute a shot to.
     *
     * <p>These mods key their per-entity shooter state on a {@code LivingEntity}, and a block entity is
     * not one. A fake player is used rather than a real player so the shot is never credited to
     * someone who did not fire it.
     */
    @Nullable
    private net.minecraft.world.entity.LivingEntity nearestShooterTarget(
            net.minecraft.server.level.ServerLevel serverLevel) {
        return com.sidedrain.util.DispenserAdapter.acquireShooter(serverLevel);
    }

    /**
     * Puts a round back when the mod's behaviour did not spend it.
     *
     * <p>The plate takes a round before calling the behaviour, because it cannot know in advance
     * whether the mod will look for one itself. If the behaviour left the weapon untouched, the round
     * is returned to the slot it came from rather than being quietly destroyed.
     */
    private void returnAmmo(ItemStack ammo) {
        if (ammo.isEmpty()) {
            return;
        }
        // A slot holding the same item with room is preferred, so the stack goes back where it was.
        for (int i = 0; i < this.slotCount(); i++) {
            ItemStack existing = this.items.get(i);
            if (existing.isEmpty()) {
                this.items.set(i, ammo.copy());
                this.setChanged();
                return;
            }
            if (ItemStack.isSameItemSameTags(existing, ammo)
                    && existing.getCount() < existing.getMaxStackSize()) {
                existing.grow(1);
                this.setChanged();
                return;
            }
        }
        // No room: drop it rather than delete it.
        if (this.level != null) {
            Block.popResource(this.level, this.worldPosition, ammo.copy());
        }
    }

    /** Fires the weapon in the projectile slot once its cooldown allows it. */
    private void fireWeapon() {
        if (!this.useWeapon || this.level == null || this.level.isClientSide
                || this.weapon.isEmpty() || this.isDispenserMode() || this.fireCooldown > 0) {
            return;
        }
        // Per-shot trace at DEBUG, and the useful line - "this weapon refused" - at INFO but rate
        // limited. A working plate fires every ten ticks; logging that at INFO buried every real
        // failure in a wall of successes, which is exactly what made the earlier gun report unreadable.
        if (com.sidedrain.SideDrain.LOGGER.isDebugEnabled()) {
            com.sidedrain.SideDrain.LOGGER.debug(
                    "fireWeapon: weapon={} kind={} dedicated={}",
                    this.weapon.getHoverName().getString(),
                    com.sidedrain.util.WeaponKind.of(this.weapon),
                    com.sidedrain.util.DedicatedShooter.isAvailable());
        }
        // The shot leaves along the plate's real aim, 45 degree steps and all: a plate tilted 45
        // degrees must shoot 45 degrees up, not along the nearest cardinal face. The old code
        // snapped the aim to the six axis directions here, which is exactly the "it fires at the
        // original angle, not the tilted one" report.
        Vec3 aim = this.aimVector();
        com.sidedrain.util.WeaponAmmo.Profile profile = this.ammoProfile();
        // A wand whose focus lives inside the item has to be loaded before it can do anything. This
        // is the "聚晶" case: Goety stores the focus in the wand, not in the player's inventory, and an
        // empty wand refuses to cast. Loading happens here so it runs once, right before firing.
        com.sidedrain.util.WeaponKind kindForShooter = com.sidedrain.util.WeaponKind.of(this.weapon);
        if (kindForShooter.isCaster()) {
            com.sidedrain.util.FocusLoader.ensureLoaded(this.weapon, this::extractFocus);
        }
        // A gun ignores the plate's generic projectile path entirely: its round is not something the
        // plate can spawn, and its firing code lives in the mod. GunShooter drives it - through the
        // mod's own operator when it has one, through a dispenser behaviour when that is registered,
        // and otherwise by loading the gun and using it the way a player would. Tried before
        // everything else because it is the only path that can actually fire such a gun, and it is
        // the single gate for every firearm, TACZ or otherwise.
        if (com.sidedrain.util.GunShooter.isFirearm(this.weapon)) {
            // An empty magazine is not a dead plate: load one real round from storage first, the way a
            // player reloads. Without this the gun was refused forever once its magazine ran dry,
            // which is why a plate with a rifle and a chest of rounds stopped firing after the first
            // magazine.
            this.reloadGun();
            if (this.fireDedicatedShooter()) {
                // The cooldown follows the weapon, not the plate: a rifle that cycles every four ticks
                // must not be throttled to a machine gun's ten, and a slow shotgun must not be sped up.
                this.fireCooldown = com.sidedrain.util.GunShooter.fireIntervalTicks(this.weapon);
                this.setChanged();
                return;
            }
            // A gun that refused is reported with the path it took, because "it will not shoot" has a
            // different cause on each of them: the mod's own cooldown, a missing magazine, or a mod
            // whose firing hook the plate cannot reach. Rate limited to one line per five seconds per
            // plate: a gun that is merely cooling down would otherwise fill the log and hide the one
            // that is genuinely stuck.
            long now = this.level == null ? 0L : this.level.getGameTime();
            if (now - this.lastGunRefusalLog >= 100L
                    && com.sidedrain.SideDrain.LOGGER.isInfoEnabled()) {
                this.lastGunRefusalLog = now;
                com.sidedrain.SideDrain.LOGGER.info("Gun refused to fire: {} (path={}, round={})",
                        this.weapon.getHoverName().getString(),
                        com.sidedrain.util.GunShooter.lastPath(),
                        this.findAmmo(this.ammoProfile()).isEmpty() ? "none" : "available");
            }
            this.fireCooldown = com.sidedrain.util.GunShooter.MISS_COOLDOWN;
            return;
        }
        // A casting weapon is fired through the mod's own use path, which is the only way a spell
        // is ever cast: the payload is not a projectile the plate can construct, it is whatever the
        // mod's spell object does. So it goes to the fake player <b>before</b> takeAmmo() - taking a
        // round first would let a focus sitting in the plate be eaten as if it were ammunition.
        if (kindForShooter.isCaster()) {
            // A spell that insists on a target needs one before it will run. The plate has no eyes,
            // so it scans forward along its aim - the range is the player's setting - and hands the
            // result to the fake player, which then looks at it. Without this the spell is refused
            // by its own conditionsMet() and a perfectly loaded wand does nothing at all.
            net.minecraft.world.entity.LivingEntity target = this.findTargetAhead();
            // Smart targeting is a feature of the plate, not of the spell: when no target is in front
            // of the nozzle, sweep the whole sphere around the plate before giving up. A staff that
            // only casts at something it can see was useless the moment the mob stepped off the
            // nozzle's centre line, which is most of the time - the plate is mounted on a wall, and
            // the fight happens wherever it happens.
            if (target == null) {
                // Same clearance as the forward scan, for the same reason.
                target = com.sidedrain.util.TargetScanner.findNearest(
                        (net.minecraft.server.level.ServerLevel) this.level,
                        this.scanOrigin(aim), this.targetRange, this.meleePlayers,
                        this.meleeMonsters, this.meleeFriendlies, this.meleeNeutrals);
            }
            if (com.sidedrain.util.DispenserAdapter.tryFire(
                    (net.minecraft.server.level.ServerLevel) this.level,
                    this.worldPosition, this.aimDirection(), aim, target, this.weapon, ItemStack.EMPTY)) {
                this.fireCooldown = 10;
                this.setChanged();
                return;
            }
            // The cast was refused. That is a real failure the player can see, so it gets a line
            // they can find: the usual cause is the spell's own conditionsMet() (a target it cannot
            // see, a dimension it will not work in), which a plate genuinely cannot supply.
            com.sidedrain.SideDrain.LOGGER.info("Caster did not cast: {} (target={})",
                    this.weapon.getHoverName().getString(),
                    target == null ? "none" : target.getName().getString());
            this.fireCooldown = 20;
            return;
        }
        ItemStack ammo = this.takeAmmo();
        // The mod's own dispenser behaviour, if it registered one, is the only way to run a modded
        // weapon's real firing code: its ammunition handling, its cooldowns, its projectile. Tried
        // first because the built-in path below can only ever throw a generic projectile.
        // aimVector(), not aimDirection(): the latter snaps to six axis directions, which made a
        // carried plate fire along the nearest cardinal instead of where the carrier was looking.
        if (com.sidedrain.util.DispenserAdapter.tryFire((net.minecraft.server.level.ServerLevel) this.level,
                this.worldPosition, this.aimDirection(), aim, this.weapon, ammo)) {
            // The mod handled its own ammunition. If it left the weapon untouched it did not spend
            // the round the plate took, so that round goes back rather than being destroyed.
            if (!ammo.isEmpty() && !com.sidedrain.util.DispenserAdapter.lastCallUsedAmmo()) {
                this.returnAmmo(ammo);
            }
            this.fireCooldown = 10;
            this.setChanged();
            return;
        }
        if (ammo.isEmpty()) {
            // A self-powered weapon has nothing to take, and that is not a failure: a wand paid for
            // with mana, or a spell book with its own spells, fires with no item at all. The old
            // code treated an empty takeAmmo() as "no ammunition" and gave up, which is why a staff
            // was silently ignored even when it was perfectly usable.
            if (profile != null && !profile.needsItem()) {
                boolean firedWithoutAmmo = com.sidedrain.util.ProjectileLauncher.fire(
                        this.level, this.worldPosition, this.muzzlePos(), aim, this.weapon,
                        ItemStack.EMPTY, this.strategy, this.filter);
                this.fireCooldown = firedWithoutAmmo ? 10 : 20;
                return;
            }
            this.fireCooldown = 20;
            return;
        }
        boolean fired = com.sidedrain.util.ProjectileLauncher.fire(
                this.level, this.worldPosition, this.muzzlePos(), aim, this.weapon, ammo,
                this.strategy, this.filter);
        // Infinity never consumes a round, so the firing cycle is not gated on ammunition either.
        boolean infinity = com.sidedrain.util.ProjectileLauncher.hasInfinity(this.weapon);
        this.fireCooldown = fired ? (infinity ? 6 : 10) : 20;
    }

    /**
     * The ammunition to load: the plate's own slots first, then the container it is clamped onto.
     *
     * <p><b>The filter grid is deliberately not a magazine any more.</b> A filter cell now records an
     * item instead of holding one, so a cell reading "arrow" means "this plate may move arrows", not
     * "this plate has an arrow". Treating it as ammunition would let a plate fire forever from a pin
     * that costs nothing - an infinite bow from a single recorded item.
     *
     * <p>This only reads; {@link #takeAmmo()} is the version that actually consumes a round, so the
     * "do I still have ammunition" check never eats a projectile.
     */
    private ItemStack findAmmo() {
        return this.findAmmo(this.ammoProfile());
    }

    /**
     * Finds what the weapon needs, using the profile the weapon itself reported.
     *
     * <p>The profile's {@code accepted} list is authoritative when it is non-empty: a modded gun that
     * says it takes one specific item must not be fed arrows just because arrows are in the chest.
     * When the list is empty the plate falls back to the generic "anything that looks like
     * ammunition" test, which is what covers packs whose weapons report nothing.
     */
    private ItemStack findAmmo(com.sidedrain.util.WeaponAmmo.Profile profile) {
        java.util.function.Predicate<ItemStack> match = this.matcherFor(profile);
        // The plate's own slots count as a magazine: a player feeding the plate from a
        // dispenser-style build parks the arrows in the plate, not in the chest.
        for (int i = 0; i < this.slotCount(); i++) {
            ItemStack entry = this.items.get(i);
            if (!entry.isEmpty() && match.test(entry)) {
                return entry.copyWithCount(1);
            }
        }
        return this.findAmmoInAttached(match);
    }

    /** Reads one round from the attached container without consuming it. */
    private ItemStack findAmmoInAttached(java.util.function.Predicate<ItemStack> match) {
        Container attached = this.getAttachedContainer();
        if (attached == null) {
            return ItemStack.EMPTY;
        }
        int slot = this.pickSlotFor(attached, match);
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        return attached.getItem(slot).copyWithCount(1);
    }

    /**
     * The test that decides whether a stack satisfies this weapon.
     *
     * <p>Preferring the weapon's own report is what makes a staff work: a staff that casts through a
     * spell book reports the book, and looking for "arrows" instead would never find it.
     */
    private java.util.function.Predicate<ItemStack> matcherFor(
            com.sidedrain.util.WeaponAmmo.Profile profile) {
        if (profile != null) {
            // Profile.matches(), not a bare prototype comparison. A gun mod may ship ONE ammunition
            // item and keep the caliber in NBT - TACZ's "tacz:ammo" - so comparing tags can only
            // ever match a single caliber and a chest of the right rounds looked empty. The profile
            // also asks the mod's own IAmmo#isAmmoOfGun test, which compares the caliber.
            return profile::matches;
        }
        return com.sidedrain.util.ProjectileLauncher::isAmmo;
    }

    /**
     * Consumes one round of ammunition and returns it, or {@code EMPTY} when there is none.
     *
     * <p>The filter grid is not consulted: see {@link #findAmmo()}. A pin is a permission, not a
     * supply, and letting it supply rounds would make a bow with one recorded arrow fire forever.
     */
    private ItemStack takeAmmo() {
        com.sidedrain.util.WeaponAmmo.Profile profile = this.ammoProfile();
        java.util.function.Predicate<ItemStack> match = this.matcherFor(profile);
        // A weapon that needs an item but does not spend it (a staff casting through a spell book)
        // must never have that item taken. It still needs a template to aim with, so the item is
        // read and left exactly where it is - the same treatment Infinity gets, for the same reason.
        boolean readOnly = com.sidedrain.util.ProjectileLauncher.hasInfinity(this.weapon)
                || (profile != null && !profile.consumes());
        if (readOnly) {
            for (int i = 0; i < this.slotCount(); i++) {
                ItemStack entry = this.items.get(i);
                if (!entry.isEmpty() && match.test(entry)) {
                    return entry.copyWithCount(1);
                }
            }
            return this.findAmmoInAttached(match);
        }
        for (int i = 0; i < this.slotCount(); i++) {
            ItemStack entry = this.items.get(i);
            if (!entry.isEmpty() && match.test(entry)) {
                return this.items.get(i).split(1);
            }
        }
        Container attached = this.getAttachedContainer();
        if (attached == null) {
            return ItemStack.EMPTY;
        }
        int slot = this.pickSlotFor(attached, match);
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        ItemStack one = attached.removeItem(slot, 1);
        attached.setChanged();
        return one;
    }

    /** Picks a slot whose contents satisfy {@code predicate}, honouring the active strategy. */
    private int pickSlotFor(Container container, java.util.function.Predicate<ItemStack> predicate) {
        int size = container.getContainerSize();
        if (size <= 0) {
            return -1;
        }
        return switch (this.strategy) {
            case STRATEGY_SLOT -> {
                int slot = this.targetSlot;
                yield slot >= 0 && slot < size && predicate.test(container.getItem(slot)) ? slot : -1;
            }
            case STRATEGY_MOST -> {
                int best = -1;
                int bestCount = 0;
                for (int i = 0; i < size; i++) {
                    ItemStack stack = container.getItem(i);
                    if (predicate.test(stack) && stack.getCount() > bestCount) {
                        bestCount = stack.getCount();
                        best = i;
                    }
                }
                yield best;
            }
            case STRATEGY_FILTER -> {
                java.util.List<Integer> candidates = new java.util.ArrayList<>();
                for (int i = 0; i < size; i++) {
                    if (predicate.test(container.getItem(i)) && this.matchesFilter(container.getItem(i))) {
                        candidates.add(i);
                    }
                }
                if (candidates.isEmpty()) {
                    yield -1;
                }
                RandomSource random = this.level == null ? RandomSource.create() : this.level.random;
                yield candidates.get(random.nextInt(candidates.size()));
            }
            default -> {
                java.util.List<Integer> candidates = new java.util.ArrayList<>();
                for (int i = 0; i < size; i++) {
                    if (predicate.test(container.getItem(i))) {
                        candidates.add(i);
                    }
                }
                if (candidates.isEmpty()) {
                    yield -1;
                }
                RandomSource random = this.level == null ? RandomSource.create() : this.level.random;
                yield candidates.get(random.nextInt(candidates.size()));
            }
        };
    }

    // ------------------------------------------------------------------ feed

    private boolean feed() {
        Container attached = this.getAttachedContainer();
        if (attached == null) {
            return false;
        }
        if (this.feedOwnItems(attached)) {
            return true;
        }
        for (Container neighbour : this.neighbourContainers()) {
            for (int i = 0; i < neighbour.getContainerSize(); i++) {
                ItemStack stack = neighbour.getItem(i);
                if (!stack.isEmpty() && this.insertIntoAttached(attached, stack)) {
                    neighbour.setChanged();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Pushes one stack from the plate's own slots into {@code attached}.
     *
     * <p>Split out of {@link #feed()} so the collect mode can deliver what it picked up without also
     * vacuuming the neighbouring containers, which is a separate job that only the plain feed mode
     * should do.
     */
    private boolean feedOwnItems(Container attached) {
        for (int i = 0; i < this.slotCount(); i++) {
            ItemStack stack = this.items.get(i);
            if (!stack.isEmpty() && this.insertIntoAttached(attached, stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean insertIntoAttached(Container attached, ItemStack stack) {
        if (this.strategy == STRATEGY_FILTER && this.hasFilter() && !this.matchesFilter(stack)) {
            return false;
        }
        int size = attached.getContainerSize();
        if (size <= 0) {
            return false;
        }
        int preferred = this.pickDestination(attached, stack);
        if (preferred >= 0 && this.tryInsert(attached, preferred, stack)) {
            return true;
        }
        for (int i = 0; i < size; i++) {
            if (i != preferred && this.tryInsert(attached, i, stack)) {
                return true;
            }
        }
        return false;
    }

    private int pickDestination(Container attached, ItemStack stack) {
        int size = attached.getContainerSize();
        return switch (this.strategy) {
            case STRATEGY_SLOT -> this.targetSlot >= 0 && this.targetSlot < size ? this.targetSlot : -1;
            case STRATEGY_MOST -> {
                int best = -1;
                int bestCount = -1;
                for (int i = 0; i < size; i++) {
                    ItemStack existing = attached.getItem(i);
                    if (ItemStack.isSameItemSameTags(existing, stack) && existing.getCount() > bestCount) {
                        bestCount = existing.getCount();
                        best = i;
                    }
                }
                yield best;
            }
            default -> -1;
        };
    }

    private boolean tryInsert(Container attached, int slot, ItemStack stack) {
        ItemStack existing = attached.getItem(slot);
        if (existing.isEmpty()) {
            if (!attached.canPlaceItem(slot, stack)) {
                return false;
            }
            ItemStack moved = stack.copy();
            int amount = Math.min(moved.getCount(), moved.getMaxStackSize());
            moved.setCount(amount);
            attached.setItem(slot, moved);
            stack.shrink(amount);
            attached.setChanged();
            return true;
        }
        if (!ItemStack.isSameItemSameTags(existing, stack)) {
            return false;
        }
        int room = existing.getMaxStackSize() - existing.getCount();
        if (room <= 0) {
            return false;
        }
        int amount = Math.min(room, stack.getCount());
        existing.grow(amount);
        stack.shrink(amount);
        attached.setChanged();
        return true;
    }

    private List<Container> neighbourContainers() {
        List<Container> found = new ArrayList<>();
        if (this.level == null) {
            return found;
        }
        Direction facing = this.getBlockState().getValue(SideDrainBlock.FACING);
        for (Direction direction : Direction.values()) {
            if (direction == facing.getOpposite()) {
                continue;
            }
            Container container = OmniHopperBlockEntity.getContainerAt(this.level, this.worldPosition.relative(direction));
            if (container != null && container != this) {
                found.add(container);
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ collect

    private boolean collect() {
        if (this.level == null || this.level.isClientSide) {
            return false;
        }
        // Range 0 is the plate's own block, so the box always covers that one block and grows by
        // whole blocks from there. A small vertical margin keeps items lying on the plate's own
        // floor reachable without letting a range-0 plate hoover up the room above it.
        double reach = this.range;
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(this.worldPosition)
                .inflate(reach, reach, reach)
                .inflate(0.6D, 0.2D, 0.6D);
        // The area is a box, but the plate only reaches FORWARD.
        //
        // A plate is clamped onto one face of a block and points outwards from it, so the block
        // behind it - and the space beyond that block - belongs to whatever is on the other side.
        // With the box alone, two plates on opposite faces of the same chest both reached through it,
        // and a plate on a wall reached into the room behind the wall. Both of those are other
        // players' builds, and a plate stealing their dropped items is a conflict no configuration
        // could avoid short of range 0.
        //
        // The half space is the one on the plate's own side of the plane through its centre, measured
        // along the aim: a small dot product against the eject direction, which is exactly the
        // direction the plate calls "in front". Half a block of tolerance keeps items resting on the
        // plate's own lip reachable.
        Vec3 aim = this.aimVector();
        Vec3 centre = Vec3.atCenterOf(this.worldPosition);
        double forwardSlack = 0.5D;
        List<ItemEntity> entities = this.level.getEntitiesOfClass(ItemEntity.class, area,
                entity -> entity.isAlive() && !entity.getItem().isEmpty()
                        && isInFrontOf(entity.position(), centre, aim, forwardSlack));
        for (ItemEntity entity : entities) {
            ItemStack stack = entity.getItem();
            if (this.insertSelf(stack)) {
                if (stack.isEmpty()) {
                    entity.discard();
                } else {
                    entity.setItem(stack);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code point} lies on the front side of the plate's aim plane.
     *
     * <p>The plane passes through the plate's centre and its normal is the eject direction, so this
     * is the half of the world the nozzle points into. The slack is subtracted rather than compared
     * directly because an item can legitimately rest against the plate's own back edge while still
     * being something the plate should pick up, and a strictly positive test would refuse it.
     */
    private static boolean isInFrontOf(Vec3 point, Vec3 centre, Vec3 aim, double slack) {
        double dot = (point.x - centre.x) * aim.x
                + (point.y - centre.y) * aim.y
                + (point.z - centre.z) * aim.z;
        return dot >= -slack;
    }

    private boolean insertSelf(ItemStack stack) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < this.slotCount(); i++) {
                ItemStack existing = this.items.get(i);
                if (pass == 0 && !existing.isEmpty() && ItemStack.isSameItemSameTags(existing, stack)) {
                    int room = existing.getMaxStackSize() - existing.getCount();
                    if (room > 0) {
                        int amount = Math.min(room, stack.getCount());
                        existing.grow(amount);
                        stack.shrink(amount);
                        if (stack.isEmpty()) {
                            return true;
                        }
                    }
                } else if (pass == 1 && existing.isEmpty()) {
                    this.items.set(i, stack.copy());
                    stack.setCount(0);
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ self view

    /**
     * Read/write view onto the plate's own slots, used by {@link #leak()}.
     *
     * <p>{@code getItem} returns the live stack rather than a copy, exactly like
     * {@link RandomizableContainerBlockEntity#getItem(int)} does, so the count checks in
     * {@link #leak()} see the real contents. All writes go through {@link ContainerHelper} so the
     * clamp to the slot's maximum stack size is the same one the rest of the plate uses.
     */
    private final class SelfView implements Container {
        @Override
        public int getContainerSize() {
            return SideDrainBlockEntity.this.slotCount();
        }

        @Override
        public boolean isEmpty() {
            return SideDrainBlockEntity.this.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return SideDrainBlockEntity.this.items.get(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            ItemStack result = ContainerHelper.removeItem(SideDrainBlockEntity.this.items, slot, amount);
            if (!result.isEmpty()) {
                SideDrainBlockEntity.this.setChanged();
            }
            return result;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return ContainerHelper.takeItem(SideDrainBlockEntity.this.items, slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            SideDrainBlockEntity.this.items.set(slot, stack);
            if (stack.getCount() > this.getMaxStackSize()) {
                stack.setCount(this.getMaxStackSize());
            }
            SideDrainBlockEntity.this.setChanged();
        }

        @Override
        public void setChanged() {
            SideDrainBlockEntity.this.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            SideDrainBlockEntity.this.items.clear();
        }
    }

    // ------------------------------------------------------------------ filter view

    private final class FilterView implements Container {
        @Override
        public int getContainerSize() {
            return SideDrainBlockEntity.this.filterCount();
        }

        @Override
        public boolean isEmpty() {
            return filter.stream().allMatch(ItemStack::isEmpty);
        }

        @Override
        public ItemStack getItem(int slot) {
            return filter.get(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            ItemStack result = ContainerHelper.removeItem(filter, slot, amount);
            if (!result.isEmpty()) {
                SideDrainBlockEntity.this.setChanged();
            }
            return result;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return ContainerHelper.takeItem(filter, slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            filter.set(slot, stack);
            if (stack.getCount() > this.getMaxStackSize()) {
                stack.setCount(this.getMaxStackSize());
            }
            SideDrainBlockEntity.this.setChanged();
        }

        @Override
        public void setChanged() {
            SideDrainBlockEntity.this.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return Container.stillValidBlockEntity(SideDrainBlockEntity.this, player);
        }

        @Override
        public void clearContent() {
            filter.clear();
        }
    }

    // ------------------------------------------------------------------ weapon view

    /** Single slot that only accepts a ranged weapon or a launcher block. */
    private final class WeaponView implements Container {
        @Override
        public int getContainerSize() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return weapon.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return weapon;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            if (weapon.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack result = weapon.split(amount);
            if (weapon.isEmpty()) {
                weapon = ItemStack.EMPTY;
            }
            SideDrainBlockEntity.this.setChanged();
            SideDrainBlockEntity.this.syncWeaponToClients();
            return result;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack result = weapon;
            weapon = ItemStack.EMPTY;
            SideDrainBlockEntity.this.invalidateWeaponKind();
            SideDrainBlockEntity.this.syncWeaponToClients();
            return result;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            weapon = stack;
            if (stack.getCount() > this.getMaxStackSize()) {
                stack.setCount(this.getMaxStackSize());
            }
            // The classification is memoised per game tick, so swapping the weapon mid-tick would
            // otherwise keep answering with the previous weapon's kind for the rest of that tick.
            SideDrainBlockEntity.this.invalidateWeaponKind();
            SideDrainBlockEntity.this.setChanged();
            // The renderer draws the weapon's item model in the middle of the plate, so the client has
            // to be told the moment the slot changes - otherwise the model only appears after the
            // chunk is reloaded.
            SideDrainBlockEntity.this.syncWeaponToClients();
        }

        @Override
        public void setChanged() {
            SideDrainBlockEntity.this.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return Container.stillValidBlockEntity(SideDrainBlockEntity.this, player);
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            // The weapon slot accepts anything the plate can drive: bows, crossbows, tridents, guns,
            // blades, and the four tool families. The classifier decides, so this cannot drift out
            // of step with what the plate actually does with the stack.
            return com.sidedrain.util.WeaponKind.of(stack).ownsThePlate();
        }

        @Override
        public void clearContent() {
            weapon = ItemStack.EMPTY;
            SideDrainBlockEntity.this.invalidateWeaponKind();
            SideDrainBlockEntity.this.syncWeaponToClients();
        }
    }
}
