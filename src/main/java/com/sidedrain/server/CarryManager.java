package com.sidedrain.server;

import com.sidedrain.network.CarrySyncPacket;
import com.sidedrain.network.ModNetwork;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sneak + middle click a block to lift it into your bare hands.
 *
 * <p>The block leaves the world but its block entity NBT is kept intact in the player's persistent
 * data. While carried the block keeps ticking, so a furnace keeps smelting and a chest keeps its
 * contents: the block stays alive in your hands. Sneak + middle click a spot to put it back.
 */
public final class CarryManager {
    /**
     * Key on the <b>glove stack itself</b> that holds what that glove is carrying.
     *
     * <p>Carry state used to live in the player's persistent data, which meant a player could hold
     * exactly one block no matter how many gloves they owned, and two gloves fought over the same
     * slot. Storing it on the glove instead makes every glove an independent carrier: switching the
     * main hand away keeps the block saved in that glove, and a second glove can carry its own
     * block without touching the first.
     */
    public static final String ROOT = "sidedrain_carry";

    /** True when this particular glove stack is holding something. */
    public static boolean gloveIsCarrying(ItemStack glove) {
        CompoundTag tag = glove.getTag();
        return tag != null && tag.contains(ROOT, 10) && tag.getCompound(ROOT).contains("Block", 10);
    }

    /** The carry payload stored on a glove stack, or {@code null}. */
    @Nullable
    public static CompoundTag gloveCarriedTag(ItemStack glove) {
        CompoundTag tag = glove.getTag();
        if (tag == null || !tag.contains(ROOT, 10)) {
            return null;
        }
        CompoundTag root = tag.getCompound(ROOT);
        return root.contains("Block", 10) ? root.getCompound("Block") : null;
    }

    /** Stores (or clears) the payload on a glove stack. */
    public static void setGloveCarriedTag(ItemStack glove, @Nullable CompoundTag blockTag) {
        if (blockTag == null) {
            if (glove.hasTag()) {
                glove.getTag().remove(ROOT);
                if (glove.getTag().isEmpty()) {
                    glove.setTag(null);
                }
            }
            return;
        }
        CompoundTag root = new CompoundTag();
        root.put("Block", blockTag);
        glove.getOrCreateTag().put(ROOT, root);
    }

    /**
     * The gloves the player is <b>holding</b> that are carrying a block.
     *
     * <p>Main hand first, then off hand - and nothing else.
     *
     * <p>This used to walk the whole inventory, which meant a glove parked in any hotbar or backpack
     * slot kept its block ticking: its plates kept draining, kept shooting, and the player was
     * "carrying" something they could not see. A glove is only carrying when it is in a hand,
     * because that is the only time its block is drawn and the only time its plates should run. The
     * payload itself stays on the stack, so stowing a loaded glove loses nothing - it simply stops
     * working until it is held again.
     */
    public static java.util.List<ItemStack> carryingGloves(ServerPlayer player) {
        java.util.List<ItemStack> found = new java.util.ArrayList<>();
        ItemStack main = player.getMainHandItem();
        if (main.is(com.sidedrain.registry.ModItems.BLOCK_GLOVE.get()) && gloveIsCarrying(main)) {
            found.add(main);
        }
        ItemStack off = player.getOffhandItem();
        if (off.is(com.sidedrain.registry.ModItems.BLOCK_GLOVE.get()) && gloveIsCarrying(off)
                && !found.contains(off)) {
            found.add(off);
        }
        return found;
    }

    /**
     * The glove the player is currently carrying with, or {@code null}.
     *
     * <p>Main hand wins, then the off hand. Both are "held", so both may carry - but only one of
     * them is the glove the renderer draws and the put-down path acts on, and that has to be the
     * main hand when it has one.
     */
    @Nullable
    public static ItemStack activeGlove(ServerPlayer player) {
        java.util.List<ItemStack> all = carryingGloves(player);
        return all.isEmpty() ? null : all.get(0);
    }

    @Nullable
    public static CompoundTag getCarriedTag(ServerPlayer player) {
        ItemStack glove = activeGlove(player);
        return glove == null ? null : gloveCarriedTag(glove);
    }

    public static boolean isCarrying(ServerPlayer player) {
        return getCarriedTag(player) != null;
    }

    @Nullable
    public static BlockState getCarriedState(ServerPlayer player, ServerLevel level) {
        return getCarriedStateOf(getCarriedTag(player), level);
    }

    /** Reads the block state out of a carry payload, whichever glove it came from. */
    @Nullable
    public static BlockState getCarriedStateOf(@Nullable CompoundTag tag, ServerLevel level) {
        if (tag == null || !tag.contains("State", 10)) {
            return null;
        }
        BlockState state = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), tag.getCompound("State"));
        return state.isAir() ? null : state;
    }

    @Nullable
    public static ItemStack getCarriedDisplay(ServerPlayer player) {
        CompoundTag tag = getCarriedTag(player);
        if (tag == null || !tag.contains("Display", 10)) {
            return null;
        }
        return ItemStack.of(tag.getCompound("Display"));
    }

    /**
     * A detached container view of the carried block, or {@code null} if it is not a container.
     *
     * <p>Two shapes are tried, in the same order {@code Containers.getContainerAt} uses for a block
     * in the world: the block entity may <em>be</em> a {@link net.minecraft.world.Container} (a
     * chest, a hopper), or it may only <em>expose</em> one through the item-handler capability
     * (Sophisticated Backpacks, Tom's Simple Storage, Refined Storage). Only the first shape used to
     * be checked, so a carried backpack - which is capability-only - resolved to no container and its
     * plates went idle. A carried barrel worked and a carried backpack did not, which is a very
     * confusing pair of behaviours to hit in the same session.
     */
    @Nullable
    public static net.minecraft.world.Container getCarriedContainer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        CompoundTag tag = getCarriedTag(player);
        BlockState state = getCarriedState(player, level);
        if (tag == null || state == null) {
            return null;
        }
        BlockEntity entity = buildEntity(player, state, tag.getCompound("Entity"));
        if (entity == null) {
            return null;
        }
        if (entity instanceof net.minecraft.world.Container container) {
            return container;
        }
        return com.sidedrain.util.Containers.containerForEntity(entity);
    }

    /** Writes the detached container's contents back into the carried NBT. */
    public static void storeCarriedContainer(ServerPlayer player, net.minecraft.world.Container container) {
        CompoundTag tag = getCarriedTag(player);
        if (tag == null) {
            return;
        }
        BlockState state = getCarriedState(player, player.serverLevel());
        if (state == null) {
            return;
        }
        BlockEntity entity = buildEntity(player, state, tag.getCompound("Entity"));
        if (entity instanceof net.minecraft.world.Container target && target != container) {
            copyContents(container, target);
        }
        if (entity != null) {
            CompoundTag saved = entity.saveWithoutMetadata();
            saved.remove("x");
            saved.remove("y");
            saved.remove("z");
            saved.remove("id");
            tag.put("Entity", saved);
        }
    }

    private static void copyContents(net.minecraft.world.Container from, net.minecraft.world.Container to) {
        int size = Math.min(from.getContainerSize(), to.getContainerSize());
        for (int i = 0; i < size; i++) {
            to.setItem(i, from.getItem(i).copy());
        }
        to.setChanged();
    }

    /**
     * Rebuilds the carried block's block entity so its container can be read and written.
     *
     * <p>A carried block entity is <b>detached</b>: it has a level but no position in that level, so
     * anything it does through {@code getBlockPos()} or {@code level.getBlockEntity(...)} is wrong.
     * For a plain chest that does not matter - its contents are its own fields. For a Sophisticated
     * Backpack it matters a great deal: its item handler is backed by a wrapper that is built in
     * {@code onLoad()} from the backpack stack, and that wrapper resolves its storage block through
     * its own position. Loading the NBT alone leaves the wrapper absent, the capability answers
     * nothing, and the plate clamped onto it has no container to drain - which is exactly why a
     * carried backpack's plates stopped shooting.
     *
     * <p>So the entity is given the payload's position and then told it is loaded, which is the same
     * sequence a chunk load performs. If a mod throws during that (several validate their wrapper
     * and refuse to be built twice) the exception is swallowed: a half-built entity still exposes
     * whatever it managed to load, which is strictly better than dropping the container entirely.
     */
    @Nullable
    private static BlockEntity buildEntity(ServerPlayer player, BlockState state, CompoundTag entityTag) {
        return buildEntity(player, player.blockPosition(), state, entityTag);
    }

    @Nullable
    private static BlockEntity buildEntity(ServerPlayer player, BlockPos pos, BlockState state,
                                           CompoundTag entityTag) {
        ServerLevel level = player.serverLevel();
        BlockEntity entity = null;
        if (state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
            entity = entityBlock.newBlockEntity(pos, state);
        }
        if (entity == null) {
            return null;
        }
        entity.setLevel(level);
        CompoundTag tag = entityTag.copy();
        tag.remove("id");
        tag.remove("x");
        tag.remove("y");
        tag.remove("z");
        try {
            entity.load(tag);
        } catch (Exception ignored) {
            // A mod that refuses to load still gets the fields it managed to read.
        }
        // The post-load hook is what most storage mods build their item handler in. The packet path
        // never calls it, and neither does a bare load(), so a carried backpack had no capability at
        // all until this was added.
        try {
            entity.onLoad();
        } catch (Exception ignored) {
            // Same reasoning: a throw here must not cost the whole container.
        }
        return entity;
    }

    public static boolean pickUp(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (!player.isShiftKeyDown()) {
            return false;
        }
        // This glove must be empty. Another glove holding a block is none of its business: each
        // glove carries independently, so "already carrying" must not block a second glove.
        //
        // Either hand may lift. Picking up only from the main hand made an off-hand glove look
        // broken: the key was accepted on the client, nothing happened on the server, and the
        // player had no way to tell why.
        ItemStack glove = player.getMainHandItem();
        if (!glove.is(com.sidedrain.registry.ModItems.BLOCK_GLOVE.get()) || gloveIsCarrying(glove)) {
            glove = player.getOffhandItem();
            if (!glove.is(com.sidedrain.registry.ModItems.BLOCK_GLOVE.get()) || gloveIsCarrying(glove)) {
                return false;
            }
        }
        if (!level.isLoaded(pos) || !level.mayInteract(player, pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        // Only air and indestructible blocks are refused; a modded block with an unusual destroy
        // speed is still liftable.
        if (state.isAir() || state.getBlock() == Blocks.BEDROCK
                || state.getBlock() == Blocks.BARRIER || state.getBlock() == Blocks.END_PORTAL_FRAME) {
            return false;
        }
        // A block whose item form is missing (Sophisticated Backpacks places its block from a plain
        // item, so there is no BlockItem to clone) still has to be carryable. The display is only a
        // hint for the client and the drop fallback, so an empty stack is acceptable now that the
        // carrying flag travels separately - but prefer a real item whenever one exists.
        ItemStack display = state.getBlock().getCloneItemStack(level, pos, state);
        if (display.isEmpty()) {
            display = new ItemStack(state.getBlock().asItem());
        }
        if (display.isEmpty()) {
            // A block placed by a mod that has no BlockItem at all - a Sophisticated Backpacks
            // backpack is exactly this: the world holds a BackpackBlock, the item that placed it is
            // an item with no block form. new ItemStack(block) yields AIR for such a block, so the
            // glove carried nothing recognisable and reported "empty" while actually holding a
            // backpack. Fall back to the block's own name on a structure-void-free placeholder so
            // the carry state is always visible and always droppable.
            display = new ItemStack(state.getBlock());
        }
        if (display.isEmpty()) {
            // A block registered without the usual BlockItem mapping - Sophisticated Backpacks is
            // the reference case - still has an item somewhere that can place it. Search the item
            // registry for a BlockItem pointing at this block before giving up.
            display = itemForBlock(state.getBlock());
        }
        if (display.isEmpty()) {
            // Last resort: a named paper is still a truthful, non-empty display for a block that
            // genuinely has no item form. The real payload is the State/Entity NBT, not this stack.
            display = new ItemStack(net.minecraft.world.item.Items.PAPER);
            display.setHoverName(state.getBlock().getName());
        }

        CompoundTag blockTag = new CompoundTag();
        blockTag.put("State", NbtUtils.writeBlockState(state));
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            CompoundTag entityTag = blockEntity.saveWithoutMetadata();
            entityTag.remove("x");
            entityTag.remove("y");
            entityTag.remove("z");
            entityTag.remove("id");
            blockTag.put("Entity", entityTag);
        }
        // A bed, a door or a tall flower is two blocks that only work as a pair. Lifting one half
        // used to leave the other half standing in the world as a ghost, and putting it back placed
        // a single half that could never be used. Record the partner's state and where it sits
        // relative to the lifted half, then remove it too.
        if (com.sidedrain.util.MultiBlockHelper.isDouble(state)) {
            BlockPos otherPos = com.sidedrain.util.MultiBlockHelper.otherHalf(pos, state);
            if (otherPos != null && level.isLoaded(otherPos)) {
                BlockState otherState = level.getBlockState(otherPos);
                if (otherState.is(state.getBlock())) {
                    CompoundTag otherTag = new CompoundTag();
                    otherTag.put("State", NbtUtils.writeBlockState(otherState));
                    // Offset of the partner from the lifted half, so putting it down can rebuild the
                    // pair at the same relative position whatever the new facing is.
                    BlockPos delta = otherPos.subtract(pos);
                    otherTag.putInt("dx", delta.getX());
                    otherTag.putInt("dy", delta.getY());
                    otherTag.putInt("dz", delta.getZ());
                    BlockEntity otherEntity = level.getBlockEntity(otherPos);
                    if (otherEntity != null) {
                        CompoundTag otherEntityTag = otherEntity.saveWithoutMetadata();
                        otherEntityTag.remove("x");
                        otherEntityTag.remove("y");
                        otherEntityTag.remove("z");
                        otherEntityTag.remove("id");
                        otherTag.put("Entity", otherEntityTag);
                    }
                    blockTag.put("Partner", otherTag);
                    level.removeBlockEntity(otherPos);
                    level.setBlock(otherPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        blockTag.put("Display", display.save(new CompoundTag()));

        // Side drain plates clamped onto this block travel with it, contents and all.
        net.minecraft.nbt.ListTag plates = com.sidedrain.util.CarryPayload.detachPlates(level, pos);
        if (!plates.isEmpty()) {
            blockTag.put("Plates", plates);
        }

        level.removeBlockEntity(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        setGloveCarriedTag(glove, blockTag);
        // The glove's own NBT changed, so the client has to redraw that stack.
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();

        ModNetwork.sendToTracking(player,
                new CarrySyncPacket(player.getUUID(), display, true, NbtUtils.writeBlockState(state)));
        return true;
    }

    /**
     * Lifts a placeable entity - a modded backpack, a chair, anything the glove can pick up that is
     * an entity rather than a block.
     *
     * <p>The entity is serialised whole, so it keeps its inventory, its name and its mod data, then
     * removed from the world and carried exactly like a block.
     */
    public static boolean pickUpEntity(ServerPlayer player, int entityId) {
        ServerLevel level = player.serverLevel();
        if (!player.isShiftKeyDown()) {
            return false;
        }
        // This glove must be empty, and it is the main hand that does the lifting. Another glove
        // holding a block is irrelevant - each glove carries independently.
        ItemStack hand = player.getMainHandItem();
        if (!hand.is(com.sidedrain.registry.ModItems.BLOCK_GLOVE.get()) || gloveIsCarrying(hand)) {
            return false;
        }
        net.minecraft.world.entity.Entity entity = level.getEntity(entityId);
        if (entity == null || !entity.isAlive() || entity instanceof ServerPlayer) {
            return false;
        }
        if (player.distanceToSqr(entity) > 64.0D) {
            return false;
        }
        CompoundTag entityTag = new CompoundTag();
        if (!entity.save(entityTag)) {
            return false;
        }
        entityTag.remove("UUID");
        entityTag.remove("Pos");
        entityTag.remove("Motion");

        ItemStack display = entityDisplay(entity);

        CompoundTag blockTag = new CompoundTag();
        blockTag.put("EntityData", entityTag);
        blockTag.putString("EntityType", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(entity.getType()).toString());
        blockTag.put("Display", display.save(new CompoundTag()));

        entity.discard();
        setGloveCarriedTag(hand, blockTag);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        ModNetwork.sendToTracking(player,
                new CarrySyncPacket(player.getUUID(), display, true, new CompoundTag()));
        return true;
    }

    /**
     * Puts a carried placeable entity back into the world at {@code pos}.
     *
     * <p>Every failure path clears the carry state. A stuck carry is far worse than a lost entity:
     * it locks the glove out for the rest of the session, which is exactly what happened when a
     * modded backpack refused to respawn.
     */
    private static boolean putDownEntity(ServerPlayer player, ServerLevel level, BlockPos pos,
                                         CompoundTag blockTag, ItemStack owner) {
        try {
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.resources.ResourceLocation.tryParse(blockTag.getString("EntityType"));
            if (id == null) {
                return dropEntityAsItem(player, level, pos, blockTag, owner);
            }
            net.minecraft.world.entity.EntityType<?> type =
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(id);
            if (type == null) {
                return dropEntityAsItem(player, level, pos, blockTag, owner);
            }
            CompoundTag data = blockTag.getCompound("EntityData").copy();
            data.remove("UUID");
            net.minecraft.world.entity.Entity entity = type.create(level);
            if (entity == null) {
                return dropEntityAsItem(player, level, pos, blockTag, owner);
            }
            entity.load(data);
            entity.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                    player.getYRot(), 0.0F);
            if (!level.addFreshEntity(entity)) {
                return dropEntityAsItem(player, level, pos, blockTag, owner);
            }
            clearCarry(player, owner);
            return true;
        } catch (Exception exception) {
            // A modded entity that throws on load must not lock the glove.
            return dropEntityAsItem(player, level, pos, blockTag, owner);
        }
    }

    /**
     * Last resort for an entity that cannot be respawned: give it back as an item and always clear
     * the carry, so the player is never left holding something invisible.
     */
    private static boolean dropEntityAsItem(ServerPlayer player, ServerLevel level, BlockPos pos,
                                            CompoundTag blockTag, ItemStack owner) {
        ItemStack display = blockTag.contains("Display", 10)
                ? ItemStack.of(blockTag.getCompound("Display")) : ItemStack.EMPTY;
        if (!display.isEmpty()) {
            net.minecraft.world.Containers.dropItemStack(level,
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, display);
        }
        clearCarry(player, owner);
        return true;
    }

    /**
     * Empties one glove and re-syncs what the player is left carrying.
     *
     * <p>Clearing the <b>active</b> glove was wrong once gloves became independent: putting down a
     * block while holding a different glove emptied the wrong one, and the block the player had
     * actually been holding stayed glued to its glove. The glove that owned the payload is passed in
     * explicitly now.
     *
     * <p>The sync that follows reports whatever is still carried rather than always "nothing", so
     * the renderer keeps drawing the second glove's block after the first one is put down.
     */
    private static void clearCarry(ServerPlayer player, @Nullable ItemStack glove) {
        if (glove != null) {
            setGloveCarriedTag(glove, null);
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        }
        syncTo(player);
    }

    /**
     * The item that places {@code block}, found by scanning the registry.
     *
     * <p>{@code Block.asItem()} only knows about items registered through {@code BlockItem}; a mod
     * that registers its block and its item separately (or places the block from a plain item) is
     * invisible to it, which is why a carried Sophisticated Backpacks backpack came back with an
     * empty display stack and the glove looked empty while it was really holding the backpack.
     */
    private static ItemStack itemForBlock(net.minecraft.world.level.block.Block block) {
        for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (item instanceof net.minecraft.world.item.BlockItem blockItem
                    && blockItem.getBlock() == block) {
                return new ItemStack(item);
            }
        }
        return ItemStack.EMPTY;
    }

    /** A display stack for a placeable entity: its spawn egg when it has one, else a name tag. */
    private static ItemStack entityDisplay(net.minecraft.world.entity.Entity entity) {
        net.minecraft.world.item.SpawnEggItem egg =
                net.minecraft.world.item.SpawnEggItem.byId(entity.getType());
        if (egg != null) {
            return new ItemStack(egg);
        }
        ItemStack named = new ItemStack(net.minecraft.world.item.Items.NAME_TAG);
        named.setHoverName(entity.getDisplayName());
        return named;
    }

    public static boolean putDown(ServerPlayer player, BlockPos pos) {
        // Resolve the glove up front: it owns the payload and is the only one that may be emptied.
        ItemStack owner = activeGlove(player);
        CompoundTag blockTag = owner == null ? null : gloveCarriedTag(owner);
        if (blockTag == null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos) || !level.mayInteract(player, pos)) {
            return false;
        }
        if (blockTag.contains("EntityType")) {
            return putDownEntity(player, level, pos, blockTag, owner);
        }
        BlockState carried = getCarriedState(player, level);
        if (carried == null) {
            // The stored state is unreadable (a modded block that vanished); do not keep carrying it.
            clearCarry(player, owner);
            return true;
        }
        // If the clicked spot is occupied, walk outwards for the nearest free cell instead of
        // refusing: refusing used to leave the player stuck holding the block.
        if (!level.getBlockState(pos).canBeReplaced()) {
            BlockPos free = findFreeSpotNear(level, pos);
            if (free == null) {
                return false;
            }
            pos = free;
        }
        try {
            level.setBlock(pos, carried, Block.UPDATE_ALL);
        } catch (RuntimeException failure) {
            // A modded block whose state refuses to be placed must not lock the glove, and must not
            // take the plates down with it: nothing was placed, so there is nothing to attach to.
            com.sidedrain.SideDrain.LOGGER.warn("Could not place the carried block at {}", pos, failure);
            clearCarry(player, owner);
            return true;
        }
        // The block entity is loaded in its own guarded step, and it is critical that a failure here
        // cannot stop the plates from being reattached below.
        //
        // This was the backpack bug. A Sophisticated Backpacks backpack validates its whole storage
        // wrapper when its block entity loads, and it can throw while doing it - on a backpack whose
        // upgrade data references something that is not installed, for instance. That throw used to
        // bubble out of the single try block that wrapped "place the block, load its entity,
        // reattach the plates", so the backpack was placed and the plates that had been packed away
        // with it were never restored. They were not merely invisible: the payload had already been
        // detached from the world during pickup, so once the glove was cleared they ceased to exist.
        if (blockTag.contains("Entity", 10)) {
            try {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    CompoundTag entityTag = blockTag.getCompound("Entity").copy();
                    entityTag.remove("x");
                    entityTag.remove("y");
                    entityTag.remove("z");
                    entityTag.remove("id");
                    blockEntity.load(entityTag);
                    blockEntity.setChanged();
                }
            } catch (RuntimeException failure) {
                // The block is in the world and the plates still come back; only the carried
                // contents were rejected, and reporting that is all that can be done.
                com.sidedrain.SideDrain.LOGGER.warn(
                        "Placed the carried block at {} but its block entity rejected its data", pos, failure);
            }
        }
        // Put the attached side drain plates back where they were. Always runs, even if the block
        // entity above failed, because the plates are independent blocks that only happen to sit on
        // this host's faces - and they carry the player's items.
        if (blockTag.contains("Plates", 9)) {
            try {
                com.sidedrain.util.CarryPayload.reattachPlates(level, pos, blockTag.getList("Plates", 10));
            } catch (RuntimeException failure) {
                com.sidedrain.SideDrain.LOGGER.warn("Could not reattach the side drain plates at {}", pos, failure);
            }
        }
        // Rebuild the other half of a two-block structure. The partner is placed at the same
        // relative offset it had before, and if that cell is blocked the partner is dropped as an
        // item rather than being silently lost - a bed that is only half placed cannot be slept
        // in, which is the bug this exists to prevent.
        if (blockTag.contains("Partner", 10)) {
            try {
                restorePartner(level, pos, blockTag.getCompound("Partner"));
            } catch (RuntimeException failure) {
                com.sidedrain.SideDrain.LOGGER.warn("Could not restore the other half of a pair at {}", pos, failure);
            }
        }
        clearCarry(player, owner);
        return true;
    }

    /**
     * Puts back the second half of a bed, door or tall flower.
     *
     * <p>The offset is stored relative to the half that was carried, so the pair is rebuilt in the
     * same shape wherever the player puts it down. If the target cell is occupied the partner is
     * dropped as an item instead of overwriting whatever is there.
     */
    private static void restorePartner(ServerLevel level, BlockPos origin, CompoundTag partnerTag) {
        BlockState partnerState = NbtUtils.readBlockState(
                level.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                partnerTag.getCompound("State"));
        if (partnerState.isAir()) {
            return;
        }
        BlockPos target = origin.offset(
                partnerTag.getInt("dx"), partnerTag.getInt("dy"), partnerTag.getInt("dz"));
        if (!level.isLoaded(target) || !level.getBlockState(target).canBeReplaced()) {
            net.minecraft.world.Containers.dropItemStack(level,
                    origin.getX() + 0.5D, origin.getY() + 0.5D, origin.getZ() + 0.5D,
                    new ItemStack(partnerState.getBlock()));
            return;
        }
        level.setBlock(target, partnerState, Block.UPDATE_ALL);
        if (partnerTag.contains("Entity", 10)) {
            BlockEntity blockEntity = level.getBlockEntity(target);
            if (blockEntity != null) {
                CompoundTag entityTag = partnerTag.getCompound("Entity").copy();
                entityTag.remove("x");
                entityTag.remove("y");
                entityTag.remove("z");
                entityTag.remove("id");
                blockEntity.load(entityTag);
                blockEntity.setChanged();
            }
        }
    }

    /**
     * Keeps every carried block alive.
     *
     * <p>Each glove carries independently, so this walks all of them rather than only the one in the
     * main hand: a furnace in the off hand keeps smelting while the player holds a sword. The active
     * glove is ticked first, which is the one the renderer draws.
     */
    public static void tickCarried(ServerPlayer player) {
        for (ItemStack glove : carryingGloves(player)) {
            tickOneGlove(player, glove);
        }
    }

    private static void tickOneGlove(ServerPlayer player, ItemStack glove) {
        CompoundTag blockTag = gloveCarriedTag(glove);
        if (blockTag == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockState state = getCarriedStateOf(blockTag, level);
        if (state == null) {
            return;
        }
        BlockEntity entity = buildEntity(player, state, blockTag.getCompound("Entity"));
        if (entity == null) {
            return;
        }
        BlockPos pos = player.blockPosition();
        // A Side Drain Plate is itself carryable, and that path is separate from the "plates clamped
        // onto a carried block" path below. A plate held in the hands has no face of a container to
        // point away from any more, so its stored FACING says nothing about where the player is
        // looking - bind the carrier's look direction for the duration of the tick, exactly the way
        // tickPlates does for plates that travel bolted onto a carried block. Without this a plate
        // lifted on its own kept spitting items along the direction it had before it was picked up.
        com.sidedrain.block.entity.SideDrainBlockEntity carriedPlate =
                entity instanceof com.sidedrain.block.entity.SideDrainBlockEntity plate ? plate : null;
        if (carriedPlate != null) {
            carriedPlate.setDetachedState(state);
            // A carried plate is not clamped to anything: its old container is gone with the world
            // block, and looking one up behind the player would drain an unrelated chest.
            carriedPlate.setCarriedContainer(null);
            carriedPlate.setCarriedAim(player.getLookAngle());
            carriedPlate.setCarriedOrigin(firingPoint(player));
        }
        try {
            if (state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
                @SuppressWarnings("unchecked")
                BlockEntityTicker<BlockEntity> ticker =
                        (BlockEntityTicker<BlockEntity>) entityBlock.getTicker(level, state, entity.getType());
                if (ticker != null) {
                    ticker.tick(level, pos, state, entity);
                }
            }
        } finally {
            if (carriedPlate != null) {
                // Clear them again so neither leaks into the NBT written back below: the plate must
                // keep the FACING it was placed with, and it must not remember a firing point that
                // belonged to one player's eyes.
                carriedPlate.setCarriedAim(null);
                carriedPlate.setCarriedOrigin(null);
            }
        }
        CompoundTag saved = entity.saveWithoutMetadata();
        saved.remove("x");
        saved.remove("y");
        saved.remove("z");
        saved.remove("id");
        blockTag.put("Entity", saved);

        tickPlates(player, level, pos, blockTag);
    }

    /**
     * Where a projectile leaves a carried plate, in world space.
     *
     * <p>This is vanilla's own answer, not a number of our own. {@code AbstractArrow}'s
     * living-entity constructor spawns an arrow at
     * {@code shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ()}, and {@code BowItem} and
     * {@code CrossbowItem} both go through it - so a player's own arrow leaves from exactly this
     * point. Using the same expression is what makes a carried plate's shots start where the
     * player's shots start, instead of around their knees.
     *
     * <p>{@code getEyeY()} already accounts for the crouch pose and for any mount the player is
     * riding, so a sneaking or mounted player still fires from their actual eyes.
     */
    private static Vec3 firingPoint(ServerPlayer player) {
        return new Vec3(player.getX(), player.getEyeY() - 0.1D, player.getZ());
    }

    /**
     * Keeps the carried block's Side Drain Plates working.
     *
     * <p>While the block is in the player's hands its plates are not in the world, so they are
     * rebuilt as detached block entities bound to the carried container and ticked there. A plate
     * that was draining the block before it was picked up keeps draining it in your hands - items
     * still flow, the weapon still swings, the mode still runs - and everything the plate changes is
     * written back into the carried NBT at the end of the tick.
     *
     * <p>What "active" means here, in order:
     *
     * <ol>
     *   <li>The carried container is resolved <b>once</b> from the carried block itself - the same
     *       detached view the carry manager already rebuilds each tick - and bound to every plate.
     *       Without the binding a detached plate has no container to clamp onto and silently idles.</li>
     *   <li>Each plate then runs its real {@code serverTick}: drain keeps pulling out of the carried
     *       container and shooting it out of the nozzle, a melee weapon keeps swinging at whatever is
     *       in front of the plate, leak keeps emptying the plate's own slots.</li>
     *   <li>Everything both the container and the plates changed is written back, so nothing is lost
     *       when the block is put back down.</li>
     * </ol>
     */
    private static void tickPlates(ServerPlayer player, ServerLevel level, BlockPos pos, CompoundTag blockTag) {
        if (!blockTag.contains("Plates", 9)) {
            return;
        }
        net.minecraft.nbt.ListTag plates = blockTag.getList("Plates", 10);
        if (plates.isEmpty()) {
            return;
        }
        // The carried container is the thing every plate is clamped onto. It is the same detached
        // view the carry manager rebuilds for the block entity tick above, so the plates and the
        // container stay in step inside a single tick.
        net.minecraft.world.Container carriedContainer = getCarriedContainer(player);
        java.util.List<com.sidedrain.block.entity.SideDrainBlockEntity> live =
                com.sidedrain.util.CarryPayload.livePlates(level, pos, plates, carriedContainer);
        // A carried plate is not on a container face any more, so the FACING it was placed with says
        // nothing about where the player is pointing. Rebind the aim to the player's look direction
        // every tick: the plate then fires, ejects and sweeps along where the player is actually
        // aiming, and turns with them as they move. The stored FACING is untouched - put the block
        // down and it points where it always did.
        Vec3 aim = player.getLookAngle();
        // The plates ride on the carried block, but they fire from the player's hands, so they leave
        // from the same point the player's own bow would: eye height, minus the small drop vanilla's
        // AbstractArrow uses. Without this a carried assembly shot from the block's own centre,
        // which for a carried block is at the player's feet.
        Vec3 origin = firingPoint(player);
        for (com.sidedrain.block.entity.SideDrainBlockEntity plate : live) {
            plate.setCarriedAim(aim);
            plate.setCarriedOrigin(origin);
            com.sidedrain.block.entity.SideDrainBlockEntity.serverTick(level, pos, plate.getBlockState(), plate);
            plate.setCarriedAim(null);
            plate.setCarriedOrigin(null);
        }
        // The container's own contents may have been changed by the plates (drain takes out, feed
        // puts in), so write them back into the carried NBT.
        if (carriedContainer != null) {
            storeCarriedContainer(player, carriedContainer);
        }
        com.sidedrain.util.CarryPayload.storePlates(plates, live);
    }

    /**
     * Emergency release: drops whatever is carried on the ground and clears the state.
     *
     * <p>Exposed as {@code /sidedrain release} so a player who somehow ends up stuck carrying
     * something invisible can always get their glove back.
     */
    public static void forceRelease(ServerPlayer player) {
        ItemStack owner = activeGlove(player);
        CompoundTag blockTag = owner == null ? null : gloveCarriedTag(owner);
        if (blockTag != null) {
            ServerLevel level = player.serverLevel();
            ItemStack display = blockTag.contains("Display", 10)
                    ? ItemStack.of(blockTag.getCompound("Display")) : ItemStack.EMPTY;
            if (!display.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(level,
                        player.getX(), player.getY() + 0.5D, player.getZ(), display);
            }
        }
        clearCarry(player, owner);
    }

    /**
     * Re-sends what the player is currently carrying to every nearby client.
     *
     * <p>Called after any change, not just on join: with independent gloves, emptying one glove can
     * leave another still full, so the packet has to describe the <b>remaining</b> payload rather
     * than unconditionally saying "nothing".
     */
    public static void syncTo(ServerPlayer player) {
        ItemStack display = getCarriedDisplay(player);
        boolean carrying = isCarrying(player);
        BlockState carriedState = carrying ? getCarriedState(player, player.serverLevel()) : null;
        ModNetwork.sendToTracking(player, new CarrySyncPacket(player.getUUID(),
                display == null ? ItemStack.EMPTY : display, carrying,
                carriedState == null ? new CompoundTag() : NbtUtils.writeBlockState(carriedState)));
    }

    /**
     * Puts every carried block back down when the player leaves, so nothing is lost.
     *
     * <p>Each glove is emptied separately: with independent gloves a player can log out holding two
     * blocks, and returning only one would quietly destroy the other.
     */
    public static void dropOnLogout(ServerPlayer player) {
        for (ItemStack glove : carryingGloves(player)) {
            dropOne(player, glove);
        }
        syncTo(player);
    }

    private static void dropOne(ServerPlayer player, ItemStack glove) {
        CompoundTag blockTag = gloveCarriedTag(glove);
        if (blockTag == null) {
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockState state = getCarriedStateOf(blockTag, level);
        ItemStack display = blockTag.contains("Display", 10)
                ? ItemStack.of(blockTag.getCompound("Display")) : ItemStack.EMPTY;
        BlockPos pos = player.blockPosition();
        if (state != null && level.isLoaded(pos)) {
            BlockPos target = findFreeSpot(level, pos);
            if (target != null) {
                level.setBlock(target, state, Block.UPDATE_ALL);
                BlockEntity blockEntity = level.getBlockEntity(target);
                if (blockEntity != null && blockTag.contains("Entity", 10)) {
                    CompoundTag entityTag = blockTag.getCompound("Entity").copy();
                    entityTag.remove("x");
                    entityTag.remove("y");
                    entityTag.remove("z");
                    entityTag.remove("id");
                    blockEntity.load(entityTag);
                    blockEntity.setChanged();
                }
                if (blockTag.contains("Plates", 9)) {
                    com.sidedrain.util.CarryPayload.reattachPlates(level, target, blockTag.getList("Plates", 10));
                }
                setGloveCarriedTag(glove, null);
                return;
            }
        }
        if (!display.isEmpty()) {
            net.minecraft.world.Containers.dropItemStack(level, player.getX(), player.getY(), player.getZ(), display);
        }
        setGloveCarriedTag(glove, null);
    }

    /** The nearest replaceable cell around {@code origin}, searching a small cube. */
    @Nullable
    private static BlockPos findFreeSpotNear(ServerLevel level, BlockPos origin) {
        for (int radius = 1; radius <= 2; radius++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos candidate = origin.offset(dx, dy, dz);
                        if (level.getBlockState(candidate).canBeReplaced()) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos findFreeSpot(ServerLevel level, BlockPos origin) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    if (level.getBlockState(candidate).canBeReplaced()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private CarryManager() {
    }
}
