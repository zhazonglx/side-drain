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
    public static final int SLOT_COUNT = 27;
    public static final int FILTER_COUNT = 9;
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

    public static final int AMOUNT_ONE = 0;
    public static final int AMOUNT_STACK = 1;
    public static final int AMOUNT_COUNT = 2;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private final NonNullList<ItemStack> filter = NonNullList.withSize(FILTER_COUNT, ItemStack.EMPTY);
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

    public boolean isMeleePlayers() {
        return this.meleePlayers;
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
                        default -> {
                        }
                    }
                }

                @Override
                public int getCount() {
                    return 11;
                }
            };

    public SideDrainBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SIDE_DRAIN.get(), pos, state);
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
        return SLOT_COUNT;
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
        this.items = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
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
        this.cooldown = tag.getInt("Cooldown");
        this.fireCooldown = tag.getInt("FireCooldown");
    }

    @Override
    public void setRemoved() {
        this.invalidateContainerCache();
        super.setRemoved();
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
        tag.putInt("Cooldown", this.cooldown);
        tag.putInt("FireCooldown", this.fireCooldown);
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
        if (!plate.enabled) {
            return;
        }
        if (plate.fireCooldown > 0) {
            --plate.fireCooldown;
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
        if (draining && plate.weaponIsMelee()) {
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
            return;
        }
        // A dispenser is a launcher body, not ammunition: it fires on the plate's own transfer step
        // and never takes the "fire a projectile" path below, which is what the old code allowed
        // whenever the plate happened to hold a launcher next to a dispenser. The dispenser branch in
        // eject() is the only thing that should ever see it.
        if (draining && !plate.isDispenserMode() && plate.hasAmmunition()) {
            plate.fireWeapon();
            plate.cooldown = 2;
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
        int pairs = Math.min(SLOT_COUNT, attached.getContainerSize());
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
        for (int i = SLOT_COUNT - 1; i >= 0 && count > 0; i--) {
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
        for (int i = 0; i < SLOT_COUNT; i++) {
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
    /** Cooldown bounds for a dispenser shot, in ticks. */
    private static final int MAX_DISPENSER_INTERVAL = 8;
    private static final int MIN_DISPENSER_INTERVAL = 2;

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

    /** True when the weapon slot holds a melee weapon rather than a launcher. */
    public boolean weaponIsMelee() {
        if (!this.useWeapon || this.isDispenserMode()) {
            return false;
        }
        return com.sidedrain.util.MeleeAttacker.isMelee(this.weapon);
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

    /** True when the weapon slot holds a launcher and there is ammunition to feed it. */
    public boolean hasAmmunition() {
        if (!this.useWeapon || this.weapon.isEmpty() || this.isDispenserMode()
                || !com.sidedrain.util.ProjectileLauncher.isRangedWeapon(this.weapon)) {
            return false;
        }
        // An Infinity weapon never consumes a round, so it can fire regardless of ammunition.
        if (com.sidedrain.util.ProjectileLauncher.hasInfinity(this.weapon)) {
            return true;
        }
        return !this.findAmmo().isEmpty();
    }

    /** Fires the weapon in the projectile slot once its cooldown allows it. */
    private void fireWeapon() {
        if (!this.useWeapon || this.level == null || this.level.isClientSide
                || this.weapon.isEmpty() || this.isDispenserMode() || this.fireCooldown > 0) {
            return;
        }
        // The shot leaves along the plate's real aim, 45 degree steps and all: a plate tilted 45
        // degrees must shoot 45 degrees up, not along the nearest cardinal face. The old code
        // snapped the aim to the six axis directions here, which is exactly the "it fires at the
        // original angle, not the tilted one" report.
        Vec3 aim = this.aimVector();
        ItemStack ammo = this.takeAmmo();
        if (ammo.isEmpty()) {
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
     * The ammunition to load: filter entries win, then the plate's own slots, then the container the
     * plate is clamped onto.
     *
     * <p>This only reads; {@link #takeAmmo()} is the version that actually consumes a round, so the
     * "do I still have ammunition" check never eats a projectile.
     */
    private ItemStack findAmmo() {
        for (ItemStack entry : this.filter) {
            if (!entry.isEmpty() && com.sidedrain.util.ProjectileLauncher.isAmmo(entry)) {
                return entry.copyWithCount(1);
            }
        }
        // The plate's own twenty-seven slots count as a magazine: a player feeding the plate from a
        // dispenser-style build parks the arrows in the plate, not in the chest.
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack entry = this.items.get(i);
            if (!entry.isEmpty() && com.sidedrain.util.ProjectileLauncher.isAmmo(entry)) {
                return entry.copyWithCount(1);
            }
        }
        return this.findAmmoInAttached();
    }

    /** Reads one round from the attached container without consuming it. */
    private ItemStack findAmmoInAttached() {
        Container attached = this.getAttachedContainer();
        if (attached == null) {
            return ItemStack.EMPTY;
        }
        int slot = this.pickSlotFor(attached, com.sidedrain.util.ProjectileLauncher::isAmmo);
        if (slot < 0) {
            return ItemStack.EMPTY;
        }
        return attached.getItem(slot).copyWithCount(1);
    }

    /** Consumes one round of ammunition and returns it, or {@code EMPTY} when there is none. */
    private ItemStack takeAmmo() {
        // An Infinity weapon never consumes a round: the caller still needs a template projectile,
        // so the first matching entry is read but left exactly where it is.
        if (com.sidedrain.util.ProjectileLauncher.hasInfinity(this.weapon)) {
            for (ItemStack entry : this.filter) {
                if (!entry.isEmpty() && com.sidedrain.util.ProjectileLauncher.isAmmo(entry)) {
                    return entry.copyWithCount(1);
                }
            }
            for (int i = 0; i < SLOT_COUNT; i++) {
                ItemStack entry = this.items.get(i);
                if (!entry.isEmpty() && com.sidedrain.util.ProjectileLauncher.isAmmo(entry)) {
                    return entry.copyWithCount(1);
                }
            }
            return this.findAmmoInAttached();
        }
        for (ItemStack entry : this.filter) {
            if (!entry.isEmpty() && com.sidedrain.util.ProjectileLauncher.isAmmo(entry)) {
                return entry.copyWithCount(1);
            }
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack entry = this.items.get(i);
            if (!entry.isEmpty() && com.sidedrain.util.ProjectileLauncher.isAmmo(entry)) {
                return this.items.get(i).split(1);
            }
        }
        Container attached = this.getAttachedContainer();
        if (attached == null) {
            return ItemStack.EMPTY;
        }
        int slot = this.pickSlotFor(attached, com.sidedrain.util.ProjectileLauncher::isAmmo);
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
        for (int i = 0; i < SLOT_COUNT; i++) {
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
            for (int i = 0; i < SLOT_COUNT; i++) {
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
            return SLOT_COUNT;
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
            return FILTER_COUNT;
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
            return result;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack result = weapon;
            weapon = ItemStack.EMPTY;
            return result;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            weapon = stack;
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
        public boolean canPlaceItem(int slot, ItemStack stack) {
            // The weapon slot accepts every ranged weapon the plate can drive - bows, crossbows,
            // modded staves, spell books, guns - plus the melee weapons the plate can swing.
            return com.sidedrain.util.ProjectileLauncher.isRangedWeapon(stack)
                    || com.sidedrain.util.MeleeAttacker.isMelee(stack);
        }

        @Override
        public void clearContent() {
            weapon = ItemStack.EMPTY;
        }
    }
}
