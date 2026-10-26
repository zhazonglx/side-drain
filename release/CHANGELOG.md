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
