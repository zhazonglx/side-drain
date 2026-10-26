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
     * Every glove the player owns that is holding a block.
     *
     * <p>Ordered main hand first so that {@link #getCarriedTag(ServerPlayer)} - the one the renderer
     * and the put-down path use - always agrees with what the player is holding.
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
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(com.sidedrain.registry.ModItems.BLOCK_GLOVE.get()) && gloveIsCarrying(stack)
                    && !found.contains(stack)) {
                found.add(stack);
            }
        }
        return found;
    }

    /** The glove the player is currently carrying with, or {@code null}. */
    @Nullable
    public static ItemStack activeGlove(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(com.sidedrain.registry.ModItems.BLOCK_GLOVE.get()) && gloveIsCarrying(main)) {
            return main;
        }
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

    /** A detached container view of the carried block, or {@code null} if it is not a container. */
    @Nullable
    public static net.minecraft.world.Container getCarriedContainer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        CompoundTag tag = getCarriedTag(player);
        BlockState state = getCarriedState(player, level);
        if (tag == null || state == null) {
            return null;
        }
        BlockEntity entity = buildEntity(player, state, tag.getCompound("Entity"));
        if (entity instanceof net.minecraft.world.Container container) {
            return container;
        }
        return null;
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

    @Nullable
    private static BlockEntity buildEntity(ServerPlayer player, BlockState state, CompoundTag entityTag) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
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
        try {
            entity.load(tag);
        } catch (Exception ignored) {
            return entity;
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
        ItemStack glove = player.getMainHandItem();
        if (!glove.is(com.sidedrain.registry.ModItems.BLOCK_GLOVE.get()) || gloveIsCarrying(glove)) {
            return false;
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
            display = new ItemStack(state.getBlock());
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
            if (blockTag.contains("Entity", 10)) {
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
            }
            // Put the attached side drain plates back where they were.
            if (blockTag.contains("Plates", 9)) {
                com.sidedrain.util.CarryPayload.reattachPlates(level, pos, blockTag.getList("Plates", 10));
            }
        } catch (Exception exception) {
            // A modded block whose block entity throws on load must not lock the glove.
        }
        clearCarry(player, owner);
        return true;
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
        if (state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
            @SuppressWarnings("unchecked")
            BlockEntityTicker<BlockEntity> ticker =
                    (BlockEntityTicker<BlockEntity>) entityBlock.getTicker(level, state, entity.getType());
            if (ticker != null) {
                ticker.tick(level, pos, state, entity);
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
     * Keeps the carried block's Side Drain Plates working.
     *
     * <p>While the block is in the player's hands its plates are not in the world, so they are
     * rebuilt as detached block entities at the player's position and ticked there. A plate that was
     * draining the block before it was picked up keeps draining it in your hands.
     */
    private static void tickPlates(ServerPlayer player, ServerLevel level, BlockPos pos, CompoundTag blockTag) {
        if (!blockTag.contains("Plates", 9)) {
            return;
        }
        net.minecraft.nbt.ListTag plates = blockTag.getList("Plates", 10);
        if (plates.isEmpty()) {
            return;
        }
        java.util.List<com.sidedrain.block.entity.SideDrainBlockEntity> live =
                com.sidedrain.util.CarryPayload.livePlates(level, pos, plates);
        for (com.sidedrain.block.entity.SideDrainBlockEntity plate : live) {
            com.sidedrain.block.entity.SideDrainBlockEntity.serverTick(level, pos, plate.getBlockState(), plate);
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
