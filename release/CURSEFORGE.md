# Side Drain

Rotate any block. Keep its logic. Put it back.

Side Drain is a Minecraft 1.20.1 Forge mod built around one idea: a block should be able to face wherever you want it to, and still work.

## The Orientation Wrench

One item, two axes. Right click tips a block top-to-bottom; left click spins it left-to-right, both in 45 degree steps.

**Blocks with a real orientation property** turn in place - stairs, trapdoors, levers, observers, pistons, chests, furnaces, beds, rails, banners, signs and more.

**Dedicated rotated forms** cover the cases vanilla cannot express: an Omni Hopper with thirty orientations (funnel towards any face, tail towards any of the other five) and a standing Redstone block.

**Everything else** - grass, cobblestone, flowers, slabs, and modded machines - goes into a Rotated Block carrier. It draws the block tilted at any of the eight 45 degree steps on both axes, remembers its block state, block entity data and name, and restores the original block untouched when you rotate it back to zero. Breaking it drops the block's real loot.

Behaviour is delegated, not copied. A rotated slime block still bounces you, a rotated bed can still be slept in, a rotated furnace keeps smelting, and a rotated modded machine still opens its own screen. Collision follows the model through all sixty-four yaw and pitch combinations.

## The Side Drain Plate

Pulls items out of a container from the side instead of the top. Works on chests, barrels, hoppers and modded inventories.

## The Block Glove

Pick up a living block with your bare hands and carry it. Full size in first person, and visible in third person for everyone else.

## Compatibility

Minecraft 1.20.1, Forge 47.x, both sides. Tested with the Closing Song 1.6.5 modpack (298 mods) including Sophisticated Backpacks.

## License

MIT
