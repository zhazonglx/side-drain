# 1.0.4

## Performance

- **The aim maths is computed once, not once per call.** `PlateAim.ejectDirection` and
  `renderMatrix` are asked for the same answers over and over - by `muzzlePos()`, the eject path, the
  target scan, the shooter aim, and the renderer every frame per plate. Each call used to build a
  `Matrix4f`, a `Matrix3f` and two vectors. There are only 6 x 8 x 8 = 384 distinct aims in the whole
  game, so they are now memoised. The public matrix accessor still returns a copy, because consumers
  hand it to `Transformation`, which keeps the reference.

- **The weapon slot is classified once per tick.** Two hot paths in `serverTick` called
  `WeaponKind.of()` directly, re-running the whole classification - item checks, enchantment scans,
  tool-action probes - on every plate on every tick, while the same answer was already memoised one
  method away. They now go through the per-tick cache. The cache is invalidated when the slot
  changes, so a swap mid-tick cannot keep acting on the previous weapon's kind.

## Memory

- **The picture caches are bounded.** A large pack has hundreds of weapons, and every one that ever
  passed through a plate kept its entry for the whole session. The caches now drop at 512 entries,
  which is far above any realistic working set. Uploaded gun textures are deliberately excluded from
  that trim: they are GL textures registered with the texture manager, so dropping the map would leak
  the texture rather than free it.

## Added

- **The plate shows its weapon.** A plate with something in its weapon slot now paints that item's
  icon onto the middle of its decorated face, carried by the plate's own aim transform - including the
  45 degree tilt and spin steps, so a plate aimed up wears an icon aimed up. It follows the slot live:
  put a sword in and the sword appears, take it out and the plate is bare again.

  Four things had to line up for this:

  1. **The weapon had to reach the client.** `saveAdditional` is written to disk only; a client gets
     the chunk data packet, which is built from `getUpdateTag`. The plate had none, so a renderer
     would have seen an empty slot on every client. It now sends its saved state, and tells watching
     clients whenever the weapon slot changes.
  2. **The state has to be the whole state.** The client feeds that tag straight into `load`, so a
     partial tag would have reset the plate's mode, strategy and inventory every time the weapon
     changed.
  3. **The icon is placed in model space**, in the same 0..16 frame the model JSON is written in, and
     pushed through the very matrix the slab's quads go through. Deriving a rotation from the aim
     vector instead - yaw to its bearing, then pitch to its elevation - sends the icon's normal the
     right way but leaves an arbitrary roll about it, so the picture comes out rotated on its own
     face. There is no roll to get wrong when the model's own matrix is reused, and the slab's
     decorated surface is 3/16 of a block in from the boundary rather than half a block.
  4. **The picture has to come from somewhere real.** See below.

## Fixed

- **A third-party weapon showed no picture, while vanilla items worked.** Two independent causes, both
  visible in the test pack:

  - **Custom-rendered items define no particle sprite.** An item whose model is
    `{"parent": "builtin/entity"}` bakes as a `BuiltInModel`, whose particle sprite comes from the
    model's `particle` texture entry - and mods routinely omit it. The lookup then falls through to
    the missing-texture sprite, so the plate would have worn the black-and-magenta checkerboard.
    AlexsCaves' dreadbow is exactly this case. The resolver now falls back to a conventional
    `textures/item/<id>.png` probe, which is what such an item still ships for its inventory slot.
  - **A gun mod's whole family shares one item id.** Every TACZ firearm is
    `tacz:modern_kinetic_gun` with the model chosen from NBT, so there is not even a per-weapon model
    to ask. The gun's own slot art is read from the mod's display index instead.

  The gun lookup itself had to be widened: a stack's gun id is not always the key the display index
  is filed under. The plate now tries the display id the stack declares, then `<gun>_display` (the
  pack format's naming convention), then the default `textures/gun/slot/<gun>.png`, and finally asks
  the index for the longest display id that begins with the gun's own - so a pack that names its
  displays freely still works.

- **A gun's icon drew as the purple-and-black missing-texture checker.** The picture was found but
  could not be uploaded. A gun pack's slot art is not in the block atlas, so the decal has to bind a
  texture of its own - and handing a `ResourceLocation` to `RenderType` and letting
  `TextureManager#getTexture` create a `SimpleTexture` does not work for it: `SimpleTexture` is a
  reload-managed texture and only receives its image during a resource reload, so created on demand it
  stays blank and reports the missing texture. The image is now read from the pack and uploaded into
  a `DynamicTexture`, which owns its pixels and needs no reload.

- **A miss could be remembered forever.** A gun pack is loaded by the gun mod on its own schedule, so
  the first render of a plate can happen before the display index exists. Caching that first "no
  picture" pinned the plate to a bare face for the rest of the session. Misses are no longer cached;
  a retry is one map lookup and only happens for a weapon that has no picture.

# 1.0.8

## Fixed

- **The gun could not hit an enemy standing against the plate.** The fake shooter was placed a full
  block past the muzzle, and a bullet is born at its eye and only travels forward from there - so
  anything closer than 1.65 blocks from the plate's centre was *behind* the muzzle and immune. The
  clearance is now half a block (enough to clear the plate's own collision box and the bullet's
  width), and when something is closer still the shooter is pulled back to just short of it, so the
  shot always starts in front of the target.

- **The scan-range readout was squeezed into a button.** The steppers are 16 pixels wide, and the
  whole "Target Range: N m" string was being written *into* the ">" button. It is now drawn as text
  beside the two steppers, in the same style as the dig-face label, and the buttons keep their "<"
  and ">" glyphs.

- **A caster's scan range and target switches were missing.** `usesTargetTags()` excluded everything
  ranged, which is exactly the family whose spells need a target; and the range controls were gated on
  the weapon switch being on. Both now appear for any casting weapon.

- **A rotated anvil or enchanting table became unusable.** Their menus re-check their own block every
  tick with `level.getBlockState(pos).is(Blocks.X)`, which a carrier can never satisfy, so the screen
  shut itself on the first tick. Blocks whose whole point is such a menu now keep their own block
  class and are only turned in place: an anvil has `FACING` and still turns; an enchanting table has
  no orientation of its own, so it is honestly refused rather than quietly broken.

# 1.0.7

Two bugs with one cause: the plate's fake shooter was never given a head rotation, and the target
scan started from inside the plate's own collider.

## Fixed

- **Shots left in the wrong direction.** A living entity's view vector is built from `yHeadRot`, not
  from the `yRot` that `moveTo()` writes. A real entity copies one to the other every tick - but the
  plate's fake shooter is never ticked, so its head stayed at yaw 0 and every bullet flew due south
  regardless of where the plate was pointing. `aimShooter()` now writes the head rotation, the body
  rotation and both interpolation endpoints, so the direction is right from the first tick.

- **A staff's smart targeting never found anything.** Two separate faults:

  1. The same missing head rotation. Goety finds its target by ray-tracing along the caster's view
     vector, so a caster whose head pointed south never saw the mob the plate was facing.
  2. The scan started at `muzzlePos()`, which sits **inside** the plate's own block. A line-of-sight
     clip that begins inside a collider reports a hit on that collider, so every candidate looked
     blocked and the scan returned nothing. The scan now starts at the same cleared point the bullet
     uses.

- **A staff only cast at whatever stood on the nozzle's centre line.** The forward cone is the right
  rule for a gun, but a plate is bolted to a wall and the fight happens wherever it happens. When the
  forward scan finds nothing, a caster now falls back to the nearest valid target in any direction
  within range, still line-of-sight checked.

## Changed

- The plate's aim is now set through one helper (`DispenserAdapter.aimShooter`) on every path - gun,
  caster and fake player - so the three cannot drift apart on the rotation again.

# 1.0.6

The 1.0.5 gun rework fixed the *plumbing* and got a TACZ rifle firing, but a plate still went silent
after a few dozen rounds and a non-TACZ gun still never fired. Both come from the same two mistakes:
the plate was charging itself twice for every bullet, and it refused to look at the gun's own magazine.

## Fixed

- **A plate stopped firing after about a dozen shots, with a chest full of ammunition beside it.**
  Two separate double-charges:

  1. **The magazine was filled for free and then billed.** Before every shot the plate wrote thirty
     rounds into the gun's magazine, then took thirty back out and charged the player for them. A gun
     that arrived loaded was therefore treated as empty, and a plate whose storage ran dry was refused
     even though the gun in it was full.
  2. **One bullet was charged twice.** The mod's operator already takes the round out of the magazine
     when it fires - those rounds are real, the player loaded them - and the plate then charged its own
     storage for the same bullet. A plate with a stack of rounds burned through it in half the shots,
     and then reported itself out of ammunition while the gun was still full.

  The rule is now simple: **the gun's magazine is the ammunition**. A loaded gun fires from its own
  magazine and the plate charges nothing. When the magazine is empty the plate *reloads* it - one real
  round moved out of storage into the gun - and only that round is billed. Nothing is ever manufactured.

- **A non-TACZ gun never fired at all.** The gun path was gated on a dedicated shooter being present,
  so with TACZ installed every TACZ gun fired and everything else was handed to the generic projectile
  launcher, which threw its rounds like snowballs. `GunShooter.isFirearm` now recognises a gun by the
  mod's own interface, by classification, or by the fact that it announces ammunition - no particular
  mod required.

- **A crossbow-style gun loaded and never fired.** Those guns load on the first click and shoot on the
  second. The plate called `use()` once, so the weapon ended up charged and unfired. It now detects the
  `Charged` flag flipping and runs the second cycle, which is the second click a player makes.

- **The plate's shooter was creative on the generic path.** Creative mode makes vanilla hand out a free
  arrow and makes gun mods skip their own "consume a round" step. The shooter is now survival for a
  generic shot, and creative only for the dedicated path, where TACZ's own ammunition check needs it.

- **Fire rate was the plate's, not the gun's.** The interval was read from `IGun#getShootInterval`,
  which does not exist - TACZ publishes `getRPM` - so every gun silently fell back to a fixed ten
  ticks and then got clamped anyway. The real rounds-per-minute now drives the cooldown.

## Added

- **A gun that cannot fire now says why.** Previously the plate simply stopped with no bullet and no
  line in the log, which is why "my gun will not shoot" reports had nothing to go on. A rate-limited
  `Gun idle` line reports the magazine count and whether loose rounds are reachable, and a refusal
  reports which path was tried.

# 1.0.5

A gun-focused fix release. Every firearm is now driven through one path, and the path that was
supposed to make guns the plate had never heard of fire no longer reports a shot it never made.

## Fixed

- **A gun the plate has no dedicated support for could not fire at all.** The generic gun path handed
  the round to the *caller* and left the fake shooter's inventory empty, then called `Item#use`. Every
  gun mod answers that with "no ammunition", so nothing was fired - and the plate still reported a shot
  and billed a round for it. The round is now placed **inside** the shooter's inventory before the
  weapon is used, and taken back afterwards, which is the situation a gun mod is written for.

- **The plate's shooter was creative on that path.** Creative mode makes vanilla hand out a free arrow
  and makes gun mods skip their own "consume a round" step, so a gun could fire rounds the plate did
  not have. The shooter is now survival for the duration of a generic shot, and creative only for the
  dedicated TACZ path, where TACZ's own ammunition check requires it.

- **A crossbow-style gun only loaded, never fired.** Those guns load on the first use (a round goes
  into the weapon and it becomes charged) and shoot on the second. The plate called `use()` once, so it
  charged the gun and stopped. A shot is now up to two use cycles, exactly what a player produces with
  two clicks, and the shot is only accepted when there is evidence of it: a new projectile, or the
  weapon putting itself on cooldown.

- **A gun was only recognised when TACZ was installed.** `hasAmmunition()` and `fireWeapon()` both
  gated the gun path on a dedicated shooter being available, so a pack whose guns come from another
  mod fell through to the generic projectile launcher and had its rounds thrown as snowballs. Both now
  ask `GunShooter`, which needs no particular mod.

- **Ammunition could be classified as a gun.** A round named `assault_rifle_ammo` contains "rifle", so
  the name test called it a firearm and the plate tried to shoot its own supply. Anything that names
  itself as a round, shell, cell or cartridge is now excluded from weapon classification.

- **The plate fired every gun on the same clock.** Ten ticks for a rifle, a shotgun and a pistol alike.
  The cooldown now follows the weapon's own shot interval when the mod publishes one.

- **The log was unreadable.** A working plate wrote a line per shot at INFO, burying the failures. The
  per-shot trace is DEBUG again, and a refusal is logged at INFO but at most once every five seconds
  per plate, with the path that was tried and whether a round was available.

## Changed

- **One entry point for firearms.** `GunShooter` owns the decision: the mod's own operator (TACZ)
  first, then a registered dispenser behaviour, then the generic load-and-use path. `WeaponKind`,
  `hasAmmunition()` and `fireWeapon()` all ask the same question, so the classification, the
  ammunition gate and the firing path can no longer disagree about what a gun is.

- **The shared weapon-use helpers moved to `DispenserAdapter`** (`useHeldWeapon`, `aimShooter`), so the
  caster path and the gun path run the same use-animation code instead of two copies that drifted.

# 1.0.4

A feature release: modded weapons fire, tools mine, and the plate has a redstone trigger. (Versions
1.1.x and 1.2.0 were internal builds that were never published; this is the first public release
since 1.0.3.)

## Fixed

- **TACZ guns would not fire, even with a full magazine.** Three separate causes, all fixed:

  1. **The draw cooldown.** `IGunOperator#draw` stamps the operator's `drawTimestamp` with the
     current time, and `shoot` immediately refuses with `IS_DRAWING` while
     `drawTime * 1000 - (now - drawTimestamp) - 5` is positive. Called back to back that is always
     positive, so every shot was rejected no matter what. The plate now back-dates the timestamp
     after drawing, which is the state a gun is in once its draw animation has played.
  2. **The magazine.** Creative mode skips TACZ's "does this player own any rounds" check but not its
     "is there a round in the magazine" check, so a gun taken out of a chest still answered `NO_AMMO`.
     The plate now fills the magazine through `IGun#setCurrentAmmoCount` before firing.
  3. **The ammunition item.** TACZ ships **one** ammunition item, `tacz:ammo`, and stores the caliber
     in NBT - so two stacks for different calibers are different rounds. The plate was matching by
     item and tag, which can only ever hit one specific caliber, and a chest full of the correct
     rounds looked empty. It now asks the mod's own `IAmmo#isAmmoOfGun`, which compares the caliber.
     A new `GunAmmo` helper owns all of this and fails closed when TACZ is absent.

- **Guns were classified as 未识别 and fell back to a melee swing.** Every TACZ firearm shares the
  item id `tacz:modern_kinetic_gun`, which no noun in the name test matches. The classifier now asks
  the mod's own `IGun` interface before giving up.

- **The plate would have fired a gun for free.** `hasAmmunition()` answered `true` for every ranged
  weapon as soon as a dedicated shooter was present, so a plate with a rifle and an empty chest fired
  forever. It now requires the plate to hold a round it can actually spend, which is what it bills
  after the shot. The round is taken from the plate's storage or the attached container - never from
  the gun's own magazine, which belongs to the weapon.

- **Cast-time staves never cast.** A Goety wand casts from `finishUsingItem` - the same hook a food
  item uses - and the plate was only ever calling `releaseUsing`, the bow's hook. Worse, it stepped
  the animation with a single `onUseTick(remaining = 1)`, which a mod reads as "the animation is
  already over". The plate now walks the animation from its full duration down to 1 and then calls
  the completion hook, which is the sequence a player holding the trigger produces.

- **A wand's focus was never loaded.** `SoulUsingItemHandler.get` throws when the stack has no item
  handler, and that throw was escaping into the caller's broad catch, so every load attempt reported
  failure and a focus sitting in the chest was never moved into the wand.

- **A cast could be refused by a cooldown the machine does not have.** Goety's `cannotCast` checks a
  per-player focus cooldown map. The plate fires several times a second, so a caster cast once and
  was then refused forever. The fake player's cooldowns are now cleared before each cast, alongside
  its soul top-up. Soul energy itself is still supplied for free, as requested: a machine has no soul
  to spend.

- **A caster's focus could be eaten as ammunition.** The casting path ran after `takeAmmo()`, so a
  focus in the plate's storage could be taken as if it were a round. Casters are now fired before
  ammunition is touched.

- **Mobs attacked an unkillable target.** Firing a modded weapon uses a fake player, and Forge's
  fake player is deliberately unkillable: `isInvulnerableTo` returns true for everything and `die`
  is empty. A projectile fired by it records it as the owner, so any hostile or neutral mob it hit
  retargeted onto something it could never damage and that never died - standing there swinging
  forever. After every shot the plate now clears the fake player from any projectile still in flight,
  tells every nearby mob to drop it as a target, and parks it below the world floor so it cannot be
  selected again.

- **A carried plate fired along fixed axes.** The mod-weapon path passed a `Direction`, which has
  only six values, so a plate held by a player fired along the nearest cardinal instead of where the
  player was looking. Yaw and pitch are now computed from the plate's exact aim vector, so a carried
  plate follows the carrier's look continuously.

- **Guns, staves, wands and spell books fired nothing.** The plate could recognise them but had no
  way to run their code. It now uses the dispenser behaviour when a mod registered one, and otherwise
  drives the weapon through a fake player that uses, ticks and releases it - the same sequence a
  player produces.

- **Enchantments did not reach mining.** Efficiency is now folded into the destroy speed (vanilla
  applies it in the mining loop, not on the item), Silk Touch yields the block itself, and Fortune
  multiplies the drops.

- **Tools could not be put in the weapon slot.** The menu still used the old two-way test, and both
  halves reject a pickaxe. It now asks the same classifier the block entity uses.

- **Weapons only worked in the draining modes.** A plate set to 输送 with a sword in the slot kept
  moving items and never swung.

## Added

- **Weapon classification.** Enchantments first (a modded bow must accept Power/Punch/Flame, a
  trident is the only thing that takes Loyalty/Riptide/Channeling/Impaling), then Forge tool actions,
  then registry names. The panel names the result: 剑, 弓, 弩, 三叉戟, 枪械, 法杖, 魔杖, 法术书, 镐,
  斧, 锹, 锄, 发射器, 其他, or 未识别的武器.

- **Ammunition model.** Three states rather than a guess: 无需消耗 (staffs, wands, spell books fire on
  their own), 消耗弹药 (bows, guns take one per shot), 需要但不消耗 (an item must be present but is
  never taken). The panel shows which applies.

- **Tools mine.** A pickaxe, axe, shovel or hoe digs the block the plate faces, chosen with a
  two-button switch shown only for a tool. The tool's real destroy speed and correct-tool test decide
  what breaks, durability is spent, and unbreakable blocks are refused. Drops go into the container
  the plate is clamped onto.

- **A loaded weapon stops the transfer.** While a weapon is active the plate is a weapon mount, not a
  conveyor, so a sword plate cannot quietly drain a chest while it swings. The dispenser is the one
  exception.

## Also in this release

- **Redstone activation** - a lever next to the plate performs one transfer per rising edge, like a
  piston or a 1.21 crafter.
- **The filter grid records items** - clicking a cell records the item while your cursor keeps it,
  clicking the pin clears it. A pin is a permission, not storage.
- **The C key pins an item** from JEI, EMI, or any slot in the panel.
- **扩容侧漏板 (Expanded Side Drain Plate)** - plate + any chest, 54 storage slots and a 6 x 6 filter
  grid, crafting from a loaded plate carries its contents across.

# 1.2.0 (unpublished)

Modded weapons actually fire now, and enchantments reach the plate.

## Fixed

- **Guns, staves, wands and spell books fired nothing.** The plate could recognise them and could
  find their ammunition, but it had no way to run their firing code - that code lives in the mod's
  own item class and is reached through a player. It now drives them through the two hooks mods
  actually implement, tried in order:

  1. **The dispenser behaviour.** A mod that wants its weapon usable in a dispenser registers one,
     and that behaviour is by definition "fire from a fixed position with no player". This is the
     cleanest match for a plate and is used whenever it exists.
  2. **A fake player.** Most gun and magic mods never register a dispense behaviour - TACZ drives its
     guns from a shooter object owned by a living entity, and Iron's Spellbooks casts through
     `Item#use` plus `onUseTick`. For those, a throwaway fake player is placed at the plate, aimed
     along its facing, given the weapon, and asked to use it; then the use is ticked and released, so
     charge-and-release weapons finish too. The fake player is never added to the level, so it cannot
     be seen, targeted or saved, and its inventory is a copy.

  The weapon stack is written back afterwards, so durability, loaded rounds and cooldowns the mod
  tracks on the item all persist. Ammunition the plate took but the mod did not use is returned
  rather than destroyed.

## Changed

- **Ammunition-less launchers are no longer labelled 枪械.** Recognising that an item shoots
  something is not the same as knowing it is a firearm - it could be a slingshot or a blowpipe - so
  an unrecognised launcher reports **其他** instead of claiming to be a gun.
- **Casting weapons have their own labels.** A staff, wand and spell book now say 法杖 / 魔杖 /
  法术书 rather than being lumped in with firearms.

## Added

- **Enchantments now apply to mining.** Efficiency is folded into the destroy speed (vanilla applies
  it in the mining loop, not on the item, so a plain `getDestroySpeed` ignored it), Silk Touch
  yields the block itself, and Fortune multiplies the drops. Previously a pickaxe mined at exactly
  the same rate whether it was plain or Efficiency V.

- **The weapon UI is visible before the weapon is switched on.** A pickaxe in the slot shows its dig
  face selector whether or not "use weapon" is enabled, so the plate no longer looks like it failed
  to recognise the tool.

- **A loaded weapon stops the transfer.** While a weapon is active the plate is a weapon mount, not a
  conveyor: no transfer mode runs, so a sword plate cannot quietly drain a chest while it swings, and
  a gun plate cannot fight with its own feed. The dispenser is the one exception - it is a launcher
  body, and a dispenser plate is meant to keep transferring while it fires.

# 1.1.1

Casting weapons work, and tools can finally go in the slot.

## Fixed

- **Tools could not be put in the weapon slot.** The block entity had been switched to the new
  classifier, but the menu's slot still asked `isRangedWeapon || isMelee` - and by then `isMelee`
  delegated to `usesTargetTags()`, which is false for a pickaxe *by design*. Both halves said no, so
  every tool was rejected. The two now ask the classifier and cannot drift apart again.

- **Casting weapons were ignored.** A staff, wand or spell book is a plain item with a custom
  `use` method; nothing about it announces that it shoots. It is now recognised by name and driven
  like any other launcher.

## Added

- **Ammunition requirement model.** The plate no longer asks "is this a gun?" - it asks what the
  weapon *needs*, which is the question that actually decides whether it can fire. Three answers:

  | state | meaning | example |
  |---|---|---|
  | 无需消耗 | fires with nothing | staff, wand, mana spell book |
  | 消耗弹药 | takes one item per shot | bow, crossbow, gun |
  | 需要但不消耗 | needs an item present, never takes it | a focus / casting item |

  The panel shows which one applies next to the weapon type, so "it is not firing" and "it needs
  something I do not have" are no longer indistinguishable.

- **A weapon that declares its ammunition is believed.** `ProjectileWeaponItem#getSupportedHeldProjectiles`
  and `getAllSupportedProjectiles` are the standard way to say what a launcher eats. The plate asks
  them, so a modded gun that reports one specific bullet is fed that bullet and not arrows.

- **Self-contained gun mods.** When a gun names no ammunition, the plate looks for an item in the
  same namespace whose id reads like ammunition, which is how a mod that ships guns and bullets
  together is supported without knowing anything about it.

## Notes on Iron's Spellbooks

The pack's magic mod casts from learned spells paid for with mana; its inks are crafting ingredients
for making spells, not fuel for casting. So a staff is treated as **self-powered** and fires without
consuming anything.

An earlier draft of this feature scanned the item registry for a "spell focus" and required it. That
was wrong twice over: the registry always contains spell books, so the requirement was satisfied by
an item that might be nowhere near the plate, and a mana-powered staff would have been blocked
waiting for fuel it never needed. The scan is gone.

# 1.1.0

The weapon slot grew up. It now classifies what you put in it, and acts on that classification.

## Added

- **Weapon classification.** One classifier decides what the slot holds, and every consumer asks it
  instead of guessing. Three signals, strongest first:

  - **Enchantments.** A modded bow has to accept Power/Punch/Flame to be worth anything, a crossbow
    accepts Multishot/Quick Charge/Piercing, and a trident is the only thing in the game that takes
    Loyalty, Riptide, Channeling and Impaling. This is what recognises a modded weapon whose class
    tells you nothing.
  - **Forge tool actions.** `PICKAXE_DIG`, `AXE_DIG`, `SHOVEL_DIG`, `HOE_DIG` - the same test
    Forge uses to decide whether an item digs a block. This is what stops a modded pickaxe from being
    treated as "a melee weapon with high damage".
  - **Registry names.** The last resort, and usually the only signal a gun mod offers.

  The panel now names the result: **剑**, **弓**, **弩**, **三叉戟**, **镐**, **斧**, **锹**, **锄**,
  **枪械**, **发射器**, **其他**, or **未识别的武器**. The old fixed "普通投放" line is gone - it said
  the same thing whether the slot held a sword or a dispenser.

- **Tools dig.** A pickaxe, axe, shovel or hoe mines the block the plate is facing, chosen with a
  two-button switch that appears only for a tool. The tool's own numbers decide everything: its
  destroy speed, whether it is the correct tool for that block, and its durability, so a plate cannot
  mine forever for free. Unbreakable blocks are refused, and the drops go into the container the plate
  is clamped onto rather than into the plate's own storage - a mining plate that filled itself would
  stop mining after 54 blocks.

- **Guns spend their ammunition.** Ammunition matching now covers `ammo`, `magazine`, `cartridge`,
  `round`, `cell` and `casing` as well as the older nouns, so a gun mod's bullets are found in the
  plate's storage, in the filter, or in the attached container, and one round is consumed per shot.

## Fixed

- **Weapons only worked in the draining modes.** A plate set to 输送 with a sword in the slot kept
  moving items and never swung; a bow was ignored outside the drain family. A weapon in the slot is
  an instruction, so melee and ranged now work in every mode.

- **A tool was classified as a ranged weapon.** The old `isRangedWeapon` asked "is it melee?" and then
  accepted everything else, which made a pickaxe a launcher. Both questions now come from the
  classifier.

## Changed

- **The four melee switches are hidden for ranged weapons and tools.** A launched arrow hits whatever
  it hits, and a tool does not attack at all, so four switches that provably do nothing are gone.
- Labels are clipped to the panel width, so a longer translation cannot run off the edge of the
  narrow plate's window.

# 1.0.5

A bug-fix release for the filter shortcut. **Pressing the pin key did nothing at all** in 1.0.4; it
now works everywhere the pointer can rest.

## Fixed

- **The pin key never worked.** JEI's ingredient-list overlay has two overloads that look alike and
  return different things:

  ```
  Optional<ITypedIngredient<?>> getIngredientUnderMouse()
  <T> T                          getIngredientUnderMouse(IIngredientType<T>)
  ```

  The second is generic, so erasure makes it return `Object` - and what it hands back is the
  **ingredient itself** (the ItemStack), not a wrapper around one. The 1.0.4 code treated that value
  as an `ITypedIngredient` and called `getItemStack()` on it. That threw on every single press, the
  catch swallowed it, and the feature was silently dead: no pin, no message, no error.

  Both paths are now read correctly, and the broadest one - JEI's screen helper - is tried first,
  which also covers bookmarks and any ghost-ingredient handler a mod has registered.

- **The key only worked in filter mode.** Requiring the filter strategy first meant the key did
  nothing in every other mode, which reads exactly like "the key is broken". Pinning is a
  configuration gesture, not a mode-specific action, so the gate is gone.

- **The pointer's position was read from a render-time cache.** `hoveredSlot` is reset to null at the
  start of every frame and only filled in while drawing, so a key press - which always arrives
  *between* two frames - could read a stale slot, or null on the first frame after opening the panel.
  The panel's own slots are now hit-tested directly from the recorded pointer position.

## Added

- **Action-bar feedback.** Pinning has no visible target, so the server now answers out loud:
  "Filter recorded: Iron Ingot", "Already filtered: …", or "The filter grid is full". Without this
  there is no way to tell a successful pin from a press that did nothing.
- The pin key now also works with the pointer over **the plate's own slots**: storage, weapon, the
  player inventory and the hotbar, as well as JEI's list, JEI's bookmarks and EMI's sidebar.

# 1.0.4

A feature release: a second plate size, a redstone trigger, and a filter grid that records items
instead of holding them.

## Added

- **Redstone activation.** The plate can now be woken by a redstone edge instead of running on its
  own clock, exactly like a piston or a 1.21 crafter: flip a lever next to it and it performs **one**
  transfer. A lever left switched on does nothing after that first transfer - the next one needs the
  line to rise again. Edges are counted rather than flagged, so flicking a lever faster than the
  transfer cooldown performs one transfer per flick instead of silently dropping them. The switch is
  the new "Trigger" button in the panel, and it is saved with the plate.

- **The filter grid records items.** A filter cell is now a **pin**, not a storage slot:

  - click a cell while holding an item and the cell records it - **the item stays on your cursor**;
  - click the recorded item and the pin is cleared - nothing is handed back, because nothing was
    ever there;
  - clicking an empty cell with an empty cursor does nothing at all;
  - the "Clear" button empties the whole grid at once.

  This also fixes a real duplication bug: the old grid let vanilla's click handling pick the recorded
  item up, so a filter cell could be milked for free copies of whatever it recorded. It also means a
  pin is no longer ammunition - a plate can't fire arrows forever from a filter cell that recorded an
  arrow.

- **Recipe-viewer shortcut.** While the panel is open on the filter strategy, hovering an item in
  JEI's sidebar and pressing **C** pins it. The hint line in the panel names the actual bound key, so
  a rebind shows the new one. JEI and EMI are read reflectively, so the mod still builds and runs with
  neither installed.

- **Expanded Side Drain Plate** (`sidedrain:expanded_side_drain_plate`): plate + any chest. Every
  function is identical to the plain plate, with **54 storage slots** (a large chest) and a **6 x 6
  filter grid**. Crafting it from a loaded plate carries its contents across instead of destroying
  them. The panel puts the weapon slot and the filter grid in a second column, so the bigger grids
  cost no extra height and the player inventory stays on screen at GUI scale 3.

## Fixed

- A carried plate's item and projectile muzzle no longer disagree about where the plate is.
- The panel's player-inventory caption no longer collides with the filter grid.

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