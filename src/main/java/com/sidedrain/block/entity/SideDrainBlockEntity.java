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
 *       clamped onto. Pair it with {@code MODE_DRAIN_STORE} on the same plate to leak a chest out
 *       onto the floor one stack at a time.</li>
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
    public static final int MODE_COUNT = 6;

    public static final int STRATEGY_RANDOM = 0;
    public static final int STRATEGY_SLOT = 1;
    public static final int STRATEGY_MOST = 2;
    public static final int STRATEGY_FILTER = 3;
    public static final int STRATEGY_COUNT = 4;

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
                    return 9;
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
        return this.weapon.is(net.minecraft.world.level.block.Blocks.DISPENSER.asItem());
    }

    /** How many dispensers the shot is powered by; zero when not in dispenser mode. */
    public int dispenserPower() {
        return this.isDispenserMode() ? this.weapon.getCount() : 0;
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
        this.mode = tag.getInt("Mode");
        this.strategy = tag.getInt("Strategy");
        this.amount = tag.getInt("Amount");
        this.targetSlot = tag.getInt("TargetSlot");
        this.enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled");
        this.range = Math.max(MIN_RANGE, Math.min(MAX_RANGE,
                tag.contains("Range") ? tag.getInt("Range") : DEFAULT_RANGE));
        this.useWeapon = !tag.contains("UseWeapon") || tag.getBoolean("UseWeapon");
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
        boolean draining = plate.mode == MODE_DRAIN
                || plate.mode == MODE_DRAIN_STORE
                || plate.mode == MODE_LEAK;
        if (draining && plate.weaponIsMelee()) {
            plate.swingWeapon();
            plate.cooldown = MELEE_INTERVAL;
            return;
        }
        if (draining && plate.hasAmmunition()) {
            plate.fireWeapon();
            plate.cooldown = 2;
            return;
        }
        boolean moved = switch (plate.mode) {
            case MODE_FEED -> plate.feed();
            case MODE_COLLECT -> plate.collect();
            case MODE_COLLECT_FEED -> plate.collectFeed();
            case MODE_DRAIN_STORE -> plate.drainStore();
            case MODE_LEAK -> plate.leak();
            default -> plate.drain();
        };
        if (draining) {
            plate.fireWeapon();
        }
        if (moved) {
            plate.cooldown = TRANSFER_INTERVAL;
            plate.setChanged();
        } else {
            plate.cooldown = 2;
        }
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
     *       and the distance it travels. A full stack of sixteen is a long, flat shot.</li>
     * </ul>
     *
     * <p>The aim itself is whatever the wrench set, so a shot fired from a 45 degree plate really
     * does travel at 45 degrees.
     */
    private void eject(ItemStack stack) {
        if (this.level == null || this.level.isClientSide || stack.isEmpty()) {
            return;
        }
        BlockState state = this.getBlockState();
        Vec3 aim = com.sidedrain.util.PlateAim.ejectDirection(
                state.getValue(SideDrainBlock.FACING), state.getValue(SideDrainBlock.TILT));
        Vec3 center = Vec3.atCenterOf(this.worldPosition)
                .add(aim.x * 0.65D, aim.y * 0.65D, aim.z * 0.65D);
        ItemEntity entity = new ItemEntity(this.level, center.x, center.y - 0.15D, center.z, stack);
        int power = this.dispenserPower();
        if (power > 0) {
            // A dispenser shot: flat, fast and ranged. sqrt keeps the first few dispensers feeling
            // worthwhile while a full stack does not turn into an absurd cannon.
            double speed = DISPENSER_BASE_SPEED + DISPENSER_SPEED_PER_SQRT * Math.sqrt(power);
            entity.setDeltaMovement(aim.x * speed, aim.y * speed, aim.z * speed);
            entity.setNoGravity(true);
            entity.setPickUpDelay(10);
            if (this.level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                serverLevel.playSound(null, center.x, center.y, center.z,
                        net.minecraft.sounds.SoundEvents.DISPENSER_LAUNCH,
                        net.minecraft.sounds.SoundSource.BLOCKS, 0.7F, 1.0F + power * 0.03F);
            }
        } else {
            entity.setDeltaMovement(aim.x * 0.12D, aim.y * 0.12D + 0.08D, aim.z * 0.12D);
            entity.setDefaultPickUpDelay();
        }
        this.level.addFreshEntity(entity);
    }

    /** Speed a single dispenser gives a shot. */
    private static final double DISPENSER_BASE_SPEED = 0.55D;
    /** Extra speed per sqrt of dispenser count. */
    private static final double DISPENSER_SPEED_PER_SQRT = 0.30D;

    // ------------------------------------------------------------------ weapon

    /** Ticks between two melee swings. */
    public static final int MELEE_INTERVAL = 12;

    /**
     * The plate's aim snapped to the nearest of the six axis directions.
     *
     * <p>The weapon helpers take a {@link Direction}, but the aim is continuous. Snapping keeps a
     * diagonal plate shooting at the closest face it points at instead of silently using the
     * container face, which is what made a tilted plate fire backwards.
     */
    private Direction aimDirection() {
        BlockState state = this.getBlockState();
        Vec3 aim = com.sidedrain.util.PlateAim.ejectDirection(
                state.getValue(SideDrainBlock.FACING), state.getValue(SideDrainBlock.TILT));
        Direction best = state.getValue(SideDrainBlock.FACING);
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

    /** True when the weapon slot holds a melee weapon rather than a launcher. */
    public boolean weaponIsMelee() {
        if (!this.useWeapon || this.isDispenserMode()) {
            return false;
        }
        return com.sidedrain.util.MeleeAttacker.isMelee(this.weapon);
    }

    /** Swings the melee weapon at everything in front of the plate. */
    public void swingWeapon() {
        if (this.level == null || this.level.isClientSide || !this.weaponIsMelee()) {
            return;
        }
        // The swing follows the plate's aim, so a plate tilted 45 degrees hits what it points at.
        Direction facing = aimDirection();
        net.minecraft.world.entity.player.Player owner =
                this.level.getNearestPlayer(this.worldPosition.getX() + 0.5D,
                        this.worldPosition.getY() + 0.5D, this.worldPosition.getZ() + 0.5D, 16.0D, false);
        if (com.sidedrain.util.MeleeAttacker.attack((net.minecraft.server.level.ServerLevel) this.level,
                this.worldPosition, facing, this.weapon, owner)) {
            // Swinging wears the weapon the way a player's swing would. hurtAndBreak needs a real
            // holder, so an unowned plate simply does not consume durability rather than crashing.
            if (owner != null && !owner.getAbilities().instabuild) {
                this.weapon.hurtAndBreak(1, owner, player -> { });
            }
            this.setChanged();
        }
    }

    /** True when the weapon slot holds a launcher and there is ammunition to feed it. */
    public boolean hasAmmunition() {
        if (!this.useWeapon || this.weapon.isEmpty() || this.isDispenserMode()
                || !com.sidedrain.util.ProjectileLauncher.isLauncher(this.weapon)) {
            return false;
        }
        return !this.findAmmo().isEmpty();
    }

    /** Fires the weapon in the projectile slot once its cooldown allows it. */
    private void fireWeapon() {
        if (!this.useWeapon || this.level == null || this.level.isClientSide
                || this.weapon.isEmpty() || this.isDispenserMode() || this.fireCooldown > 0) {
            return;
        }
        Direction facing = aimDirection();
        ItemStack ammo = this.takeAmmo();
        if (ammo.isEmpty()) {
            this.fireCooldown = 20;
            return;
        }
        boolean fired = com.sidedrain.util.ProjectileLauncher.fire(
                this.level, this.worldPosition, facing, this.weapon, ammo, this.strategy, this.filter);
        this.fireCooldown = fired ? 10 : 20;
    }

    /**
     * The ammunition to load: filter entries win, then the container the plate is clamped onto.
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
        for (ItemStack entry : this.filter) {
            if (!entry.isEmpty() && com.sidedrain.util.ProjectileLauncher.isAmmo(entry)) {
                return entry.copyWithCount(1);
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
        List<ItemEntity> entities = this.level.getEntitiesOfClass(ItemEntity.class, area,
                entity -> entity.isAlive() && !entity.getItem().isEmpty());
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
            return com.sidedrain.util.ProjectileLauncher.isLauncher(stack);
        }

        @Override
        public void clearContent() {
            weapon = ItemStack.EMPTY;
        }
    }
}
