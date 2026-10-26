package com.sidedrain.block.entity;

import com.sidedrain.registry.ModBlockEntities;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Nameable;

/**
 * Holds the original block that the {@link com.sidedrain.block.RotatedBlock} carrier is tilting.
 *
 * <p>The original state, its block entity data and its display name are all kept, so putting the
 * block back loses nothing and breaking the carrier can run the block's real loot table.
 *
 * <p>The wrapped block entity is also kept alive as a real object, not just as NBT: that is what lets
 * the carrier forward ticking, container access and interaction to the original block.
 */
public class RotatedBlockEntity extends BlockEntity implements Nameable {
    private BlockState inner = Blocks.AIR.defaultBlockState();
    private CompoundTag innerEntity;
    private String innerName = "";

    /** The live detached block entity of the wrapped block, rebuilt on demand. */
    @Nullable
    private BlockEntity cachedEntity;
    private BlockState cachedFor = Blocks.AIR.defaultBlockState();

    public RotatedBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ROTATED_BLOCK.get(), pos, state);
    }

    public BlockState getInnerState() {
        return this.inner;
    }

    @Nullable
    public CompoundTag getInnerEntity() {
        return this.innerEntity;
    }

    public String getInnerName() {
        return this.innerName;
    }

    /**
     * Sets the wrapped state.
     *
     * <p>This deliberately does <b>not</b> push a block update. It is called three times in a row
     * when the wrench wraps a block ({@code setInnerState}, {@code setInnerEntity},
     * {@code setInnerName}), and each one used to send a full {@code UPDATE_ALL} packet - with
     * {@link #getUpdateTag()} carrying the entire inner block entity NBT, inventory included. Call
     * {@link #syncToClients()} once when the whole change is finished.
     */
    public void setInnerState(BlockState state) {
        if (!this.inner.equals(state)) {
            this.cachedEntity = null;
            this.cachedFor = Blocks.AIR.defaultBlockState();
        }
        this.inner = state;
        this.setChanged();
    }

    public void setInnerEntity(@Nullable CompoundTag tag) {
        this.innerEntity = tag;
        this.cachedEntity = null;
        this.cachedFor = Blocks.AIR.defaultBlockState();
        this.setChanged();
    }

    public void setInnerName(String name) {
        this.innerName = name == null ? "" : name;
        this.setChanged();
    }

    /** Tells nearby clients that the wrapped state or name changed; call once per operation. */
    public void syncToClients() {
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(),
                    this.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Marks the stored NBT stale without serialising anything.
     *
     * <p>The wrapped block entity is ticked every tick; rebuilding its NBT twenty times a second was
     * the main source of lag for a rotated container. The live entity stays authoritative and is
     * written back by {@link #saveAdditional(CompoundTag)} when the chunk is saved.
     */
    public void markInnerDirty() {
        this.setChanged();
    }

    /**
     * The live detached block entity of the wrapped block.
     *
     * <p>It is cached, so ticking and container edits go to the same object instead of a fresh copy
     * every time. {@link #saveInnerEntity(BlockEntity)} writes it back into the stored NBT.
     */
    @Nullable
    public BlockEntity innerEntity(Level level) {
        if (this.inner.isAir()) {
            return null;
        }
        if (this.cachedEntity != null && this.cachedFor.equals(this.inner)) {
            return this.cachedEntity;
        }
        if (!(this.inner.getBlock() instanceof EntityBlock entityBlock)) {
            return null;
        }
        BlockEntity created = entityBlock.newBlockEntity(this.worldPosition, this.inner);
        if (created == null) {
            return null;
        }
        created.setLevel(level);
        if (this.innerEntity != null) {
            CompoundTag tag = this.innerEntity.copy();
            tag.remove("id");
            tag.remove("x");
            tag.remove("y");
            tag.remove("z");
            try {
                created.load(tag);
            } catch (Exception ignored) {
                // A block entity that rejects the stored data still works with its defaults.
            }
        }
        this.cachedEntity = created;
        this.cachedFor = this.inner;
        return created;
    }

    /** Writes the live block entity back into the stored NBT, so it survives a save. */
    public void saveInnerEntity(BlockEntity entity) {
        if (entity == null) {
            return;
        }
        CompoundTag saved = entity.saveWithoutMetadata();
        saved.remove("x");
        saved.remove("y");
        saved.remove("z");
        saved.remove("id");
        this.innerEntity = saved;
        this.setChanged();
    }

    /**
     * Builds a detached block entity for the wrapped block, so its loot table and its own logic can
     * run with the original data.
     */
    @Nullable
    public BlockEntity createInnerEntity(ServerLevel level) {
        return this.innerEntity(level);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Inner", 10) && this.level != null) {
            this.inner = NbtUtils.readBlockState(this.level.holderLookup(Registries.BLOCK), tag.getCompound("Inner"));
        }
        this.innerEntity = tag.contains("InnerEntity", 10) ? tag.getCompound("InnerEntity") : null;
        this.innerName = tag.getString("InnerName");
        this.cachedEntity = null;
        this.cachedFor = Blocks.AIR.defaultBlockState();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        // The live wrapped entity is the source of truth; write it back once, here, rather than on
        // every tick.
        if (this.cachedEntity != null) {
            this.saveInnerEntity(this.cachedEntity);
        }
        tag.put("Inner", NbtUtils.writeBlockState(this.inner));
        if (this.innerEntity != null) {
            tag.put("InnerEntity", this.innerEntity.copy());
        }
        if (!this.innerName.isEmpty()) {
            tag.putString("InnerName", this.innerName);
        }
    }

    /**
     * Only what a client needs to draw the carrier.
     *
     * <p>{@code saveAdditional} was used here, which shipped the wrapped block's full block entity
     * NBT - a chest's whole inventory, a backpack's contents - in every block update packet. The
     * client renderer only needs the state and the display name; the contents live on the server and
     * reach the client through the normal container menu when it is opened.
     */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        if (!this.inner.isAir()) {
            tag.put("Inner", NbtUtils.writeBlockState(this.inner));
        }
        if (!this.innerName.isEmpty()) {
            tag.putString("InnerName", this.innerName);
        }
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ------------------------------------------------------------------ naming

    /**
     * The carrier's own name: the wrapped block's name plus a "Rotated Block" suffix.
     *
     * <p>Rotated dirt is therefore "泥土 旋转方块" rather than plain dirt, which is what tells a
     * player that this is the turned carrier and not the original block. A custom name set on the
     * wrapped block entity wins over the derived name, exactly as it would on the original block.
     */
    @Override
    public Component getName() {
        return Component.translatable("block.sidedrain.rotated_block.named", this.innerDisplayName());
    }

    private Component innerDisplayName() {
        if (!this.innerName.isEmpty()) {
            return Component.literal(this.innerName);
        }
        if (this.inner.isAir()) {
            return Component.translatable("block.sidedrain.rotated_block.empty");
        }
        return this.inner.getBlock().getName();
    }

    @Override
    public boolean hasCustomName() {
        return !this.innerName.isEmpty();
    }

    @Nullable
    @Override
    public Component getCustomName() {
        return this.innerName.isEmpty() ? null : Component.literal(this.innerName);
    }

}
