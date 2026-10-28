package com.sidedrain.client;

import com.sidedrain.block.entity.RotatedBlockEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side half of "the wrapped block changed, redraw the carrier".
 *
 * <p>This lives in the client package on purpose. {@code RotatedBlockEntity} is common code and is
 * loaded on a dedicated server, where {@code ClientLevel} does not exist; keeping every reference to
 * it behind this one class means the common class has no constant-pool entry for a client-only type
 * and a server never has to resolve one. The entity only calls in when
 * {@code level.isClientSide} is true, which is exactly when this class can be loaded at all.
 *
 * <h2>Why the obvious call does not work</h2>
 *
 * <p>The natural way to ask for a re-mesh is
 * {@code level.setBlocksDirty(pos, state, state)}. It does nothing.
 * {@code LevelRenderer.setBlockDirty} forwards to {@code ModelManager.requiresRender(old, new)},
 * which begins with {@code if (old == new) return false} - and passing the same state twice is
 * precisely that case. The section is then never rebuilt, the cached mesh keeps drawing the old
 * geometry, and the block still shows whatever it showed before the wrap. That is a silent no-op
 * that looks exactly like "the refresh did not run".
 *
 * <p>So the section is marked dirty directly instead, which is unconditional. Neighbouring sections
 * are included because a rotated block can reach outside its own cell - a bed pivots about the pair
 * centre, and a 45 degree tilt of a full block overshoots its cube - and a face that straddles a
 * section boundary is meshed as part of the neighbour.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientModelRefresh {
    /**
     * Re-captures this carrier's model data and re-meshes the section it stands in.
     *
     * <p>Both steps are required and they do different jobs:
     *
     * <ul>
     *   <li>{@code requestModelDataUpdate()} queues the position for the model data manager, so the
     *       next query rebuilds the cached {@code ModelData} from
     *       {@link RotatedBlockEntity#getModelData()}. The wrapped state reaches a client through
     *       the block entity update packet, whose handler calls {@code load} - and Forge refreshes
     *       model data from {@code onLoad}, which that path never calls. Without this the cache
     *       keeps returning the {@code EMPTY} instance captured when the carrier was still empty,
     *       and the model is asked to draw an air state: no quads, an invisible block, or the
     *       previous wrapped block still drawn where the new one should be.</li>
     *   <li>Marking the section dirty makes the mesher actually run again. A refreshed cache that
     *       nothing re-reads still shows the old picture.</li>
     * </ul>
     */
    public static void request(RotatedBlockEntity carrier) {
        if (!(carrier.getLevel() instanceof ClientLevel level)) {
            return;
        }
        carrier.requestModelDataUpdate();
        SectionPos section = SectionPos.of(carrier.getBlockPos());
        level.setSectionDirtyWithNeighbors(section.x(), section.y(), section.z());
    }

    private ClientModelRefresh() {
    }
}
