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

It clamps onto all six faces of a block, including a crafting table, and it works on storages that
never implement vanilla's `Container` - Sophisticated Backpacks, Tom's Simple Storage, Refined
Storage - because it reads their item-handler capability.

### Seven modes

| Mode | 中文 | What it does |
| --- | --- | --- |
| Drain | 抽取 | pulls from the container and throws the items out of the nozzle |
| Feed | 输送 | pushes items from the plate into the container |
| Collect | 收集 | swallows item entities that land on the plate |
| Collect + Feed | 收集+输送 | vacuums loose items straight into the container |
| Drain + Store | 抽存 | pulls from the container and keeps the items in the plate's own 27 slots |
| Leak | 泄漏 | throws the plate's own 27 slots out of the nozzle |
| Store + Leak | 抽存+泄漏 | both at once - a buffered pipe from the container, through the plate, out of the nozzle |

**Store + Leak** runs the store half first and the leak half second on the same tick, so a plate that
filled up on the previous tick still gets to empty itself. That is what makes it a buffer rather than
a pass-through: the container drains at its own rate and the nozzle fires at the plate's.

A weapon in the projectile slot fires while any of the draining modes runs, so a plate can shoot
arrows out of the chest it is emptying.

### Picking strategy

Four ways to choose what moves: a single chosen slot, the fullest slot, only items matching a
nine-slot filter, or no preference at all. Plus **slot-to-slot**, which binds plate slot *i* to
container slot *i* so the two inventories stay in lockstep.

---

## The Block Glove

Bare hands that pick up a **living block** - a block that is still a block, not an item - and carry it around. The block is drawn at full size in first person, hugged against your chest by both arms, and in third person for everyone else.

The glove only works while it is in your **main or off hand**. A glove stowed in a backpack or a
hotbar slot you are not holding keeps its payload but does nothing - which is what stops a stored
glove from quietly emptying a chest across the room.

Clamp plates onto a block, then pick the block up, and the plates come with it and keep working.

---

## Controls

Both the wrench and the glove work through **sneak + click**, and both also have a key binding so
you can do the same thing without holding sneak. The gestures are unchanged; the keys are extra.

| Action | Gesture | Key (default) | 中文 |
| --- | --- | --- | --- |
| Tilt block | Sneak + Right click | - | 翻转方块（扳手） |
| Spin block | Sneak + Left click | `G` | 旋转方块（扳手） |
| Lift / put down | Sneak + Middle click | `R` | 搬起·放下方块（手套） |

Rebind either key in **Options → Controls → Side Drain**. Every entry is labelled in Chinese and
English so it can be found by searching in either language.

---

## Compatibility

- **Minecraft** 1.20.1
- **Forge** 47.x
- **Side:** both (the rotation and drain logic are server-authoritative; the rendering is client-only)

Tested against the **Closing Song 1.6.5** modpack (298 mods) including Sophisticated Backpacks, whose many-boxed block shapes are handled by the rotation budget.

---

## Installation

1. Install Minecraft Forge 47.x for 1.20.1.
2. Drop `sidedrain-1.0.3.jar` into your `mods` folder.
3. Launch.

Client and server both need the mod.

---

## Building from source

```
./gradlew build
```

The jar lands in `build/libs/sidedrain-1.0.3.jar`. Requires JDK 17.

---

## Links

- **Repository:** https://github.com/zhazonglx/side-drain
- **Releases:** https://github.com/zhazonglx/side-drain/releases
- **Issues:** https://github.com/zhazonglx/side-drain/issues

---

## License

MIT. See `LICENSE.txt`.
