# Side Drain

A Minecraft **1.20.1 Forge** mod about making blocks face the way you want them to, draining containers from the side, and carrying blocks in your bare hands.

Everything in this mod exists because a block refused to point somewhere. Rotate anything, keep its logic intact, and put it back when you are done.

---

## The Orientation Wrench

One item, two axes. Hold it and click a block:

| Input | Axis | Effect |
| --- | --- | --- |
| **Right click** | tilt | tips the block top-to-bottom in 45 degree steps |
| **Left click** | yaw | spins the block left-to-right in 45 degree steps |

Every block falls into one of three groups.

### 1. Blocks with a real orientation property

These turn **in place**, keeping their own block and their own logic:
stairs, trapdoors, levers, buttons, observers, pistons, dispensers, droppers, lanterns, pillars, chests, furnaces, beds, rails, banners, signs, pumpkins, and more.

### 2. Blocks with a dedicated rotated form

| Block | ID |
| --- | --- |
| Hopper Rotated Block | `sidedrain:omni_hopper` |
| Redstone Rotated Block | `sidedrain:standing_redstone` |

**Omni Hopper** has thirty forms: the funnel opens towards any of the six faces, and for each of those the tail points at any of the other five. 6 x 5 = 30. Its transfer logic follows the new orientation.

Two-block structures - beds, doors, tall flowers - are rebuilt as a pair, so a rotated bed keeps both halves attached and can still be slept in.

### 3. Everything else goes into a carrier

| Block | ID |
| --- | --- |
| Rotated Block | `sidedrain:rotated_block` |

Grass, cobblestone, flowers, slabs (which finally stand on edge), and modded machines. The carrier draws the block tilted at **any of the eight 45 degree steps on both axes**, remembers its block state, its block entity data and its name, and turns it back into the original block, untouched, when you rotate it back to zero.

Breaking a carrier drops the block's **real loot**, so a rotated chest still spills its contents.

### Behaviour is delegated, not copied

A rotated block keeps working. Right clicking, stepping on, fall damage, redstone output, random ticks and the block's own ticker are all forwarded to the wrapped block with the wrapped block's state:

- a rotated slime block still bounces you
- a rotated bed can still be slept in
- a rotated furnace keeps smelting
- a rotated modded machine still opens its own screen

### Collision follows the model

The collision box rotates with the block through all sixty-four yaw/pitch combinations, not just quarter turns. A block tipped 45 degrees is walkable on its real tilted face.

---

## The Side Drain Plate

A plate that pulls items out of a container **from the side** instead of the top. Point it at a chest, a barrel, a hopper, or a modded inventory and it drains.

---

## The Block Glove

Bare hands that pick up a **living block** - a block that is still a block, not an item - and carry it around. The block is drawn at full size in first person, hugged against your chest by both arms, and in third person for everyone else.

---

## Compatibility

- **Minecraft** 1.20.1
- **Forge** 47.x
- **Side:** both (the rotation and drain logic are server-authoritative; the rendering is client-only)

Tested against the **Closing Song 1.6.5** modpack (298 mods) including Sophisticated Backpacks, whose many-boxed block shapes are handled by the rotation budget.

---

## Installation

1. Install Minecraft Forge 47.x for 1.20.1.
2. Drop `sidedrain-1.0.0.jar` into your `mods` folder.
3. Launch.

Client and server both need the mod.

---

## Building from source

```
./gradlew build
```

The jar lands in `build/libs/sidedrain-1.0.0.jar`. Requires JDK 17.

---

## Links

- **Repository:** https://github.com/zhazonglx/side-drain
- **Releases:** https://github.com/zhazonglx/side-drain/releases
- **Issues:** https://github.com/zhazonglx/side-drain/issues

---

## License

MIT. See `LICENSE.txt`.
