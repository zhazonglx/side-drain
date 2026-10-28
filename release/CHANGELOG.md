# 1.0.3

A performance release. The main change is one bug that made rotated blocks slower the longer you
played, plus a set of smaller hot-path fixes.

## Performance

- **The shape warm-up was filling a cache nothing read.** Warming a rotated block queued 63 builds
  per block, but it called `rotateAboutX`/`rotateAboutY` while every real lookup goes through
  `rotateAboutXY`. The two use different cache keys, so all of that work was thrown away - and
  worse, it filled the 2048-entry cache, after which real entries could no longer be added at all.
  Past roughly thirty distinct rotated shapes the cache went permanently cold and every collision
  query rebuilt its shape on the server thread. The warm-up now builds exactly the keys the lookups
  read, and skips the 16 quarter turns that need no cache at all.

  Measured: a warm collision query costs about 155 ns; a cold build of the same shape costs about
  67 us for the collider and 349 us for the outline. One outline build was worth 2,252 collision
  queries.

- **Caches no longer refuse new entries when full.** Both shape caches used to stop storing at their
  bound, which silently made everything added afterwards rebuild on every query. They now evict one
  entry instead, so the cache keeps working at any size. Bounds raised to 16384.

- **The client's rotated-geometry cache is bounded for the first time.** It had no limit at all, so
  a build that rotated many different blocks grew it until the client ran out of memory.

- **The decorative-block test is memoised.** It ran on every outline query - every frame while the
  player looks at a block - and asked the wrapped block for its collision shape each time.

## Fixed

- Carrying a Sophisticated Backpack (or any storage block that exposes its inventory through a
  capability rather than being a `Container`) no longer stops its side drain plates from working.
  Two causes: the carried block entity was never sent through `onLoad`, which is where storage mods
  build their item handler; and the carried container lookup only accepted block entities that are
  a `Container`, which a backpack is not.

- A glove parked in any inventory slot no longer keeps firing. Only a glove in the main or off hand
  carries, because that is the only time its block is drawn.

## Added

- **Store + Leak** mode for the Side Drain Plate: fills the plate from the container behind it and
  empties the plate out of the nozzle on the same tick, so the plate acts as a buffered pipe.

- **Key bindings** for the wrench and the glove (`G` and `R` by default), alongside the existing
  sneak + click gestures, which are unchanged. Labelled in both Chinese and English.

- Plates can now be clamped to a crafting table on all six faces.

# 1.0.0

First release.

## Features

- **Orientation Wrench** - rotate blocks on two axes in 45 degree steps. Right click tilts, left click spins.
- **Omni Hopper** (`sidedrain:omni_hopper`) - thirty orientations: funnel towards any of six faces, tail towards any of the other five.
- **Redstone Rotated Block** (`sidedrain:standing_redstone`) - standing redstone dust that still powers its neighbours.
- **Rotated Block carrier** (`sidedrain:rotated_block`) - wraps any block, including modded machines, preserving block state, block entity data and name. Restores the original untouched at zero rotation, and drops the real loot when broken.
- **Side Drain Plate** - pulls items out of a container from the side.
- **Block Glove** - pick up and carry a living block, drawn at full size in first and third person.

## Fixes

- Rotating a Sophisticated Backpack no longer freezes the server thread. The rotation union ran one full optimize pass per box, which is quadratic; it now merges first and optimizes once. A 512-box shape went from roughly 40 seconds to 0.66 seconds.
- The collision box now follows the model through all 45 degree steps on both axes, not just quarter turns. The 45 degree case is computed by clipping each slice against the rotated rectangle instead of taking the bounding box of four corners, which removed the inflation that made the box too large.
- Rotated beds can be slept in again. The carrier now exposes the bed's facing direction, which vanilla reads directly when starting and stopping sleep.
- A wrapped block that throws while handling a click, or while having its shape rotated, is now reported and skipped instead of taking the server tick down with it.
