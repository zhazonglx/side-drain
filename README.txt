Side Drain (sidedrain) - Minecraft 1.20.1 Forge
================================================

ROTATION FAMILY
---------------
The Orientation Wrench turns a block one of two ways:
  Shift + Right Click : flip it over / stand it up
  Shift + Left Click  : spin it around

Every block lands in one of three groups:

1. Blocks with a real orientation property turn in place.
   Stairs, trapdoors, levers, buttons, observers, pistons, dispensers, lanterns,
   pillars, chests, furnaces, beds, rails, banners, signs, pumpkins.

2. Blocks with a dedicated rotatable form:
     Hopper Rotated Block    (sidedrain:omni_hopper)
     Redstone Rotated Block  (sidedrain:standing_redstone)
   Their logic follows the new orientation - see below.

   Two-block structures - beds, doors and tall flowers - are rebuilt as a pair, so
   a rotated bed keeps both halves attached and can still be slept in.

3. Everything else is lifted into a carrier:
     Rotated Block           (sidedrain:rotated_block)
   Grass, cobblestone, flowers, slabs (which finally stand on edge), modded
   machines. The carrier draws the block tilted and remembers its block state,
   its block entity data and its name. Turning the carrier back to zero restores
   the original block untouched; breaking it drops the block's real loot, so a
   rotated chest still spills its contents.

   Behaviour is delegated, not copied: right clicking, bouncing, stepping on,
   fall damage, redstone output, random ticks and the block's own ticker are all
   forwarded to the wrapped block with the wrapped block's state. A rotated slime
   block still bounces, a rotated bed can still be slept in, a rotated furnace
   keeps smelting and a rotated modded machine still works.

HOPPER ROTATED BLOCK  (sidedrain:omni_hopper)
  Thirty forms: the bucket opens towards any of the six faces, and for each of
  those the tail points at any of the other five. 6 x 5 = 30.
      Shift + Right Click : walk the BODY around the six faces
      Shift + Left Click  : walk the SPOUT around its five directions
  Body and spout are independent, so a hopper can hang from a ceiling, lie on a
  wall, or open east while its tail points north.
  Logistics follow the form: it pulls out of the container behind the bucket and
  pushes into the container the tail points at, in all thirty forms.
  Not craftable: the wrench turns a vanilla hopper into this the first time it is
  rotated, keeping its contents. Breaking it gives a plain hopper back.

REDSTONE ROTATED BLOCK  (sidedrain:standing_redstone)
  Redstone dust raised onto its edge, in all six facings.
  Shift + right click dust and it stands up; each further step walks it through
  up -> north -> down -> south -> west -> east and back. Shift + left click spins
  it around the vertical axis.
  The plate is anchored to the face its normal points at - flat dust sits on the
  floor, a stood-up panel clings to the wall - so it never floats in the middle of
  a block.
  Right click a panel while holding redstone to grow another panel of the same
  facing in the direction you point, so a wire can be drawn by hand.
  Free standing: no wall or floor needed, it works in mid air.
  Uses the vanilla redstone dust sprite, tinted by power like real dust, and
  powers every neighbour it touches in every facing.

BLOCK GLOVE  (sidedrain:block_glove)
  Required to lift blocks. Crafted only in a 3x3 table:
      R . R
      D I L        R = redstone, D = dirt, I = iron ingot, L = any log
      R . R

SIDE DRAIN PLATE  (sidedrain:side_drain_plate)
  Place it on a container face like any other block, then right click it to open
  the panel: 27 storage slots, a 3x3 filter grid, one weapon slot.
  Sneak + right click the plate toggles it on and off.

  Modes      : Drain        - pull out of the container and spit the items out
               Feed         - push the plate's items into the container
               Collect      - swallow items lying on the plate
               Drain+Feed   - both at once: drain the container and feed it back, a two-way
                              valve that keeps the contents circulating
  Strategies : Random   - each pull takes a random non-empty slot
               Slot     - a slot of the ATTACHED CONTAINER, chosen with the
                          < and > buttons; the panel shows "Container slot 3/27"
               Most     - the slot holding the largest stack
               Filter   - only items listed in the 3x3 grid
  Amount     : One (a single item) or Stack (a whole stack)
  Power      : Active / Paused

  Several plates on one container are numbered: the panel title reads
  "#2/3 (top face)" so two open panels can never be confused.

  Breaking the plate keeps its contents, filter and weapon inside.

  Weapon slot: bows, crossbows, dispensers, modded guns or wands, and melee
  weapons. A melee weapon makes the plate swing at everything in front of it
  instead of draining, using that weapon's own damage, Sharpness/Smite/Bane,
  Fire Aspect, Knockback and attribute modifiers.
  While the plate is in Drain mode and active, it keeps firing arrows, spectral
  and tipped arrows, snowballs, eggs, ender pearls, experience bottles, potions,
  fire charges, tridents, fireworks, and modded ammunition.
  A loaded weapon takes priority: while it still has ammunition the plate stops
  draining entirely and only shoots, so a gun never empties the chest it feeds
  from. Draining resumes once the weapon is removed or the ammunition runs out.
  The weapon's own enchantments and attack damage carry over to the projectile:
  Power raises damage, Flame sets it alight, Punch knocks targets back, Piercing
  and Infinity behave as they do in a real bow, and a modded weapon's attack
  bonus is added on top.

CARRY
-----
  Hold the Block Glove, sneak + middle click a block to lift it into both hands.
  Placeable entities (a modded backpack, a chair) can be lifted the same way by
  looking at them rather than at a block.
  The block stays alive: its own ticker keeps running.
  Side Drain Plates clamped onto the block travel with it, and keep draining while
  you carry the block; they are put back on the same faces when you set it down.
  Sneak + middle click a spot to put it back down.
  The block is drawn once, at full size, hugged against the chest by both arms -
  in first person and in third person for every player who can see you.
  Blocks with no item form at all (a Sophisticated Backpack places its block from
  a plain item) still carry, draw and drop correctly: "carrying" is tracked as its
  own flag and the block state is sent to the client, so the hands never disagree
  with the server.
  If a carry ever gets stuck, /sidedrain release drops it and frees the glove.

RECIPES
-------
  Wrench:  . I . / I I I / I . I     (I = iron ingot)
  Plate:   R . R / . C . / R . R     (R = redstone, C = chest)
  Glove:   R . R / D I L / R . R     (R = redstone, D = dirt, I = iron, L = log)
