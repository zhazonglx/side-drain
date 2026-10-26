package com.sidedrain.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Pre-computes rotated collision shapes off the server thread.
 *
 * <p>Wrapping a block in a {@link com.sidedrain.block.RotatedBlock} carrier means every later
 * {@code getShape} call has to rotate the wrapped block's shape by the carrier's yaw and pitch.
 * {@link ShapeRotator} memoises that, but the very first query still pays for the whole build - and
 * the first query happens the moment the block is placed, on the server thread, in the middle of a
 * tick. For a machine with a large collision shape that is a visible hitch.
 *
 * <p>So the moment a block is wrapped, every combination the carrier can actually use is queued
 * here and built on a small background pool. By the time the player rotates the block or an entity
 * sweeps against it, the answer is already in the cache and the server thread only does a map read.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This is deliberately conservative, because block shapes are game objects and touching them
 * from the wrong thread is how mods corrupt worlds:
 *
 * <ul>
 *   <li>Only pure geometry is done here. {@link ShapeRotator} works on {@code AABB} copies and
 *       immutable {@code VoxelShape} objects, never on a {@code Level}, a chunk or a block entity.</li>
 *   <li>The source shape is read on the <b>calling</b> (server) thread, before the job is queued, so
 *       the worker never asks a block for its shape from off-thread.</li>
 *   <li>Results go into the {@code ConcurrentHashMap} in {@link ShapeRotator}, which the render and
 *       server threads read lock-free.</li>
 *   <li>If the queue is full the job is dropped rather than blocking; the cache simply misses later
 *       and the shape is built on demand exactly as before. Nothing breaks, it is only slower.</li>
 * </ul>
 *
 * <p>The pool uses daemon threads, so it can never keep the game process alive, and it is created
 * lazily so a client that never wraps a block never pays for it.
 */
public final class ShapeWarmup {
    /**
     * Carrier rotation states worth pre-building: 8 yaw steps x 8 pitch steps of 45 degrees.
     *
     * <p>This used to be four and four, which matched the old quarter-turn-only shape rotation.
     * Once the collision shape started following the model through all eight steps, every warmed key
     * was for an angle the carrier no longer produces, so <b>every</b> query missed the cache and
     * rebuilt the shape on the server thread - exactly the hitch this class exists to prevent.
     */
    private static final int STEPS = 8;

    private static final int QUEUE_CAPACITY = 512;

    /**
     * Shapes with more boxes than this are not warmed.
     *
     * <p>The 45 degree steps are approximated by slicing, which costs {@code slices x boxes} work.
     * Warming 63 combinations of a shape with hundreds of boxes would flood the pool and delay the
     * real work; such a shape simply builds on demand and is cached from then on.
     */
    private static final int WARM_BOX_LIMIT = 48;

    private static volatile ThreadPoolExecutor pool;

    /**
     * Shapes already handed to the worker.
     *
     * <p>{@code warmRotatedShapes} is called both when a block is wrapped and again on every cache
     * miss, and the same shape can easily be wrapped dozens of times (a row of rotated chests all
     * share one vanilla chest shape). Tracking what has been queued keeps the queue from filling
     * with duplicates and having real work dropped by the discard policy.
     *
     * <p>Keyed on identity, like the shape cache itself.
     */
    private static final java.util.Set<VoxelShape> QUEUED =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor existing = pool;
        if (existing != null) {
            return existing;
        }
        synchronized (ShapeWarmup.class) {
            if (pool == null) {
                AtomicInteger counter = new AtomicInteger();
                ThreadPoolExecutor created = new ThreadPoolExecutor(1, 2, 30L, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                            Thread thread = new Thread(runnable,
                                    "sidedrain-shape-warmup-" + counter.incrementAndGet());
                            thread.setDaemon(true);
                            // Below normal priority: this is opportunistic work and must never
                            // compete with the server or the render thread.
                            thread.setPriority(Thread.MIN_PRIORITY);
                            return thread;
                        }, new ThreadPoolExecutor.DiscardPolicy());
                pool = created;
            }
            return pool;
        }
    }

    /**
     * Queues the rotation shapes the carrier will need for {@code raw}.
     *
     * <p>The caller passes the source shape it already read from the block, on the thread that owns
     * the level. The worker only ever sees that immutable shape, never the block, the level or the
     * block entity, which is what makes running it off-thread safe.
     */
    public static void warmRotatedShapes(@javax.annotation.Nullable VoxelShape raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        // A WeakHashMap keyed on identity: the entry disappears with the shape, so a server that
        // loads many worlds does not accumulate dead keys.
        if (!QUEUED.add(raw)) {
            return;
        }
        if (raw.toAabbs().size() > WARM_BOX_LIMIT) {
            return;
        }
        ThreadPoolExecutor executor = pool();
        for (int pitch = 0; pitch < STEPS; pitch++) {
            for (int yaw = 0; yaw < STEPS; yaw++) {
                if (pitch == 0 && yaw == 0) {
                    continue;
                }
                // The yaw step is a pure quarter turn at every 90 degrees and a slice otherwise;
                // pre-building only the states that cost real work keeps the pool from spending
                // its whole budget on combinations nobody asked for.
                final int p = pitch;
                final int y = yaw;
                executor.execute(() -> {
                    // Mirror exactly what RotatedBlock.rotateShape does, so the cache keys match.
                    VoxelShape pitched = ShapeRotator.rotateAboutX(raw, p * 45.0D);
                    ShapeRotator.rotateAboutY(pitched, -y * 45.0D);
                });
            }
        }
    }

    /** Stops the worker pool; called on client and server shutdown. */
    public static void shutdown() {
        ThreadPoolExecutor existing = pool;
        pool = null;
        QUEUED.clear();
        if (existing != null) {
            existing.shutdownNow();
        }
    }

    private ShapeWarmup() {
    }
}
