package com.sidedrain.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;

/**
 * Answers "what picture stands for this weapon?" as a texture plus a UV rectangle.
 *
 * <h2>Why the item's own model is not enough</h2>
 *
 * <p>For a vanilla item the flat picture is the model's particle sprite - what a dropped item's
 * break particles and the creative inventory use - and every generated item model defines one. A
 * modded weapon breaks that in two different ways, and both were visible in the test pack:
 *
 * <ul>
 *   <li><b>An item drawn by a custom renderer.</b> Its model is {@code {"parent": "builtin/entity"}},
 *       which bakes as a {@code BuiltInModel} whose particle sprite comes from the model's
 *       {@code particle} texture entry. Mods routinely omit that entry, so the lookup falls through
 *       to {@link MissingTextureAtlasSprite} and the plate would wear the black-and-magenta
 *       checkerboard. AlexsCaves' dreadbow is exactly this case.</li>
 *   <li><b>One item id shared by a whole family.</b> Every TACZ firearm is
 *       {@code tacz:modern_kinetic_gun} with the model chosen from NBT, so there is not even a
 *       per-weapon model to ask - the picture has to come from the mod's own data.</li>
 * </ul>
 *
 * <h2>The resolution chain</h2>
 *
 * <p>Each step is tried in order and the first real picture wins:
 *
 * <ol>
 *   <li>The gun mod's own slot art, when the stack is one of its weapons.</li>
 *   <li>The item model's particle sprite, or the sprite of its first quad.</li>
 *   <li>A conventional texture path, for custom-rendered items that define no particle sprite.</li>
 * </ol>
 *
 * <p>Every step returns {@code null} rather than a placeholder when it cannot find a real picture,
 * and the renderer then draws nothing: a plate with an unremarkable item on it must look like a bare
 * plate, never like a plate wearing a missing-texture checker.
 */
@OnlyIn(Dist.CLIENT)
public final class WeaponIcon {
    /**
     * A picture to paint: a texture, and the rectangle inside it that holds the image.
     *
     * <p>{@code u0/v0} is the top-left corner of the image and {@code u1/v1} the bottom-right, in
     * the same 0..1 texture space {@link TextureAtlasSprite} reports. A whole standalone texture is
     * the rectangle (0,0)-(1,1).
     */
    public record Icon(ResourceLocation texture, float u0, float u1, float v0, float v1) {
    }

    /**
     * The "no picture" sentinel, used only to mark a cache entry as a known miss.
     *
     * <p>Needed because {@code ConcurrentHashMap} cannot store a null value: without a sentinel an
     * item that has no picture would look like an item that has not been looked up yet, and the
     * distinction matters - a miss must stay retryable, a hit must be cached.
     */
    private static final Icon NONE = new Icon(new ResourceLocation("sidedrain", "none"), 0, 0, 0, 0);

    /** Icons of ordinary items, keyed by item: the picture never depends on a stack's NBT. */
    private static final Map<Item, Icon> BY_ITEM = new ConcurrentHashMap<>();

    /** Icons of gun-mod weapons, keyed by the mod's own gun id. */
    private static final Map<ResourceLocation, Icon> BY_GUN = new ConcurrentHashMap<>();

    /** Items already reported as having no picture, so the log line appears once, not per frame. */
    private static final java.util.Set<Item> NO_PICTURE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Gun ids already reported as having no picture; the gun miss is retried, so this guards the log. */
    private static final java.util.Set<ResourceLocation> NO_PICTURE_GUN =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The picture for {@code stack}, or {@code null} when there is no honest one to draw.
     *
     * <p>The gun-mod lookup comes first: for such an item the vanilla model lookup does not fail -
     * it confidently returns the missing-texture sprite.
     */
    @Nullable
    public static Icon resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Icon fromGunMod = gunModIcon(stack);
        if (fromGunMod != null) {
            return fromGunMod;
        }
        return ordinaryIcon(stack);
    }

    @Nullable
    private static Icon ordinaryIcon(ItemStack stack) {
        Item item = stack.getItem();
        Icon cached = BY_ITEM.get(item);
        if (cached != null) {
            return cached == NONE ? null : cached;
        }
        Icon found = atlasIcon(stack);
        if (found == null) {
            found = conventionalIcon(item);
        }
        if (found == null) {
            // Not cached, for the same reason a gun's miss is not: models are baked in stages, and
            // an item whose model is not ready yet must not be remembered as having no picture.
            // Logged only the first time so a genuinely bare item cannot fill the log.
            if (NO_PICTURE.add(item)) {
                note(item.getDescriptionId(), NONE);
            }
            return null;
        }
        trimIfNeeded();
        BY_ITEM.put(item, found);
        note(item.getDescriptionId(), found);
        return found;
    }

    // ------------------------------------------------------------------ gun mods

    /**
     * The gun's slot picture, read from the gun mod's client asset index.
     *
     * <p>Reflective and fails closed, for the same reason every other optional integration in this
     * mod is: the plate has to keep working with the gun mod absent, half-loaded, or renamed by a
     * fork.
     */
    @Nullable
    private static Icon gunModIcon(ItemStack stack) {
        ResourceLocation gunId = Tacz.gunId(stack);
        if (gunId == null) {
            return null;
        }
        Icon cached = BY_GUN.get(gunId);
        if (cached != null) {
            return cached == NONE ? null : cached;
        }
        Icon found = Tacz.slotTexture(stack, gunId);
        if (found == null) {
            // A MISS is deliberately not cached.
            //
            // A gun pack is loaded by the gun mod itself, on its own schedule: a plate can be
            // rendered - and this lookup run - before the pack's display index exists. Caching that
            // first "nothing" would then pin the plate to a bare face for the rest of the session,
            // which is exactly the kind of bug that only shows up on a slow first load. A retry is
            // one map lookup and only happens for a gun that has no picture.
            //
            // Because the miss is retried, the log line has to be guarded separately or it would be
            // written once per frame per plate.
            if (NO_PICTURE_GUN.add(gunId)) {
                note("gun " + gunId, NONE);
            }
            return null;
        }
        trimIfNeeded();
        BY_GUN.put(gunId, found);
        note("gun " + gunId, found);
        return found;
    }

    /**
     * The TACZ binding.
     *
     * <p>Everything is optional: a renamed member degrades this plate to "no picture", not to a
     * crash. Three sources are tried for the slot art, because the display id a stack names is not
     * always the key the display map is filed under:
     *
     * <ol>
     *   <li>The display id the stack carries, which is how a skinned or renamed gun finds its own
     *       art.</li>
     *   <li>{@code <gun id>_display}, which is the file naming convention of the gun pack format -
     *       {@code tacz:ak47} is drawn by {@code display/guns/ak47_display.json}.</li>
     *   <li>{@code textures/gun/slot/<gun path>.png}, the default slot art path, checked directly
     *       so a pack that ships art without a display entry still shows its gun.</li>
     * </ol>
     */
    private static final class Tacz {
        private static boolean probed;
        @Nullable
        private static Method getIGunOrNull;
        @Nullable
        private static Method getGunId;
        @Nullable
        private static Method getGunDisplayId;
        @Nullable
        private static Object assetsManager;
        @Nullable
        private static Method getGunDisplay;
        @Nullable
        private static Method getSlotTextureLocation;
        /** {@code ClientAssetsManager#getGunDisplays()} - the whole display index, for prefix search. */
        @Nullable
        private static Method getGunDisplays;

        /** The stack's gun id, or null when the stack is not a gun this build understands. */
        @Nullable
        static ResourceLocation gunId(ItemStack stack) {
            if (!probe() || getIGunOrNull == null || getGunId == null) {
                return null;
            }
            try {
                Object gun = getIGunOrNull.invoke(null, stack);
                if (gun == null) {
                    return null;
                }
                return asId(getGunId.invoke(gun, stack));
            } catch (ReflectiveOperationException | RuntimeException absent) {
                return null;
            }
        }

        /** The gun's slot art, or null when none of the three sources produces a real file. */
        @Nullable
        static Icon slotTexture(ItemStack stack, ResourceLocation gunId) {
            if (!probe()) {
                return null;
            }
            Icon fromDisplay = displayTexture(stack, gunId);
            if (fromDisplay != null) {
                return fromDisplay;
            }
            // The default slot art, probed directly. Covers a pack whose display entry is missing
            // or is filed under a key this build cannot reconstruct. This is a texture ID, not a
            // path: textureExists adds the textures/ prefix and the .png suffix itself.
            ResourceLocation direct = new ResourceLocation(gunId.getNamespace(),
                    "gun/slot/" + gunId.getPath());
            return textureExists(direct) ? whole(direct) : null;
        }

        @Nullable
        private static Icon displayTexture(ItemStack stack, ResourceLocation gunId) {
            if (assetsManager == null || getGunDisplay == null || getSlotTextureLocation == null) {
                return null;
            }
            for (ResourceLocation displayId : displayCandidates(stack, gunId)) {
                if (displayId == null) {
                    continue;
                }
                Icon icon = textureOfDisplay(displayId);
                if (icon != null) {
                    return icon;
                }
            }
            // Nothing matched by name. Ask the index what it actually holds and pick the display
            // whose id starts with this gun's, longest match first.
            //
            // This exists because the display id a stack carries is NOT always the key the index is
            // filed under. The test pack proves it: a CIBR AK-47 reports its gun id as
            // cib:cs_ak47, while the display entry for it is filed as cib:cs_ak47_display - which
            // the name candidates above should cover - and yet a third party can file it under any
            // id it likes. Rather than guess more names, ask.
            return byPrefix(gunId);
        }

        @Nullable
        private static Icon textureOfDisplay(ResourceLocation displayId) {
            try {
                Object display = getGunDisplay.invoke(assetsManager, displayId);
                if (display == null) {
                    return null;
                }
                Object value = getSlotTextureLocation.invoke(display);
                if (value instanceof ResourceLocation texture && textureExists(texture)) {
                    return whole(texture);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // One bad display must not hide the others.
            }
            return null;
        }

        /**
         * The display whose id is this gun's id plus a suffix, or the longest such match.
         *
         * <p>Gun pack authors name displays freely, so the only robust lookup is against the index
         * itself. A gun called {@code cib:cs_ak47} is matched by {@code cib:cs_ak47_display} and by
         * {@code cib:cs_ak47_skin2}, and the longest match wins so a skin cannot shadow the default.
         */
        @Nullable
        private static Icon byPrefix(ResourceLocation gunId) {
            if (getGunDisplays == null) {
                return null;
            }
            try {
                Object value = getGunDisplays.invoke(assetsManager);
                if (!(value instanceof java.util.Collection<?> displays)) {
                    return null;
                }
                ResourceLocation best = null;
                int bestLength = -1;
                for (Object entry : displays) {
                    // getGunDisplays() returns a Set<Map.Entry<ResourceLocation, GunDisplayInstance>>.
                    Object key = entry instanceof java.util.Map.Entry<?, ?> mapEntry
                            ? mapEntry.getKey() : entry;
                    if (!(key instanceof ResourceLocation id)) {
                        continue;
                    }
                    if (!id.getNamespace().equals(gunId.getNamespace())) {
                        continue;
                    }
                    String path = id.getPath();
                    String base = gunId.getPath();
                    if (!path.startsWith(base) || path.length() <= base.length()) {
                        continue;
                    }
                    // Prefer a display whose remaining suffix is a plain separator + word, so that
                    // "ak47" does not match "ak4700" by accident.
                    char next = path.charAt(base.length());
                    if (next != '_' && next != '.' && next != '/') {
                        continue;
                    }
                    if (path.length() > bestLength) {
                        bestLength = path.length();
                        best = id;
                    }
                }
                return best == null ? null : textureOfDisplay(best);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }

        /** The display keys worth trying for this stack, most specific first. */
        private static ResourceLocation[] displayCandidates(ItemStack stack, ResourceLocation gunId) {
            ResourceLocation declared = null;
            try {
                if (getGunDisplayId != null) {
                    Object gun = getIGunOrNull.invoke(null, stack);
                    if (gun != null) {
                        declared = asId(getGunDisplayId.invoke(gun, stack));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                declared = null;
            }
            return new ResourceLocation[] {
                    declared,
                    new ResourceLocation(gunId.getNamespace(), gunId.getPath() + "_display"),
                    gunId
            };
        }

        @Nullable
        private static ResourceLocation asId(@Nullable Object value) {
            return value instanceof ResourceLocation location ? location : null;
        }

        private static boolean probe() {
            if (probed) {
                return getIGunOrNull != null;
            }
            probed = true;
            try {
                Class<?> gunInterface = Class.forName("com.tacz.guns.api.item.IGun");
                getIGunOrNull = gunInterface.getMethod("getIGunOrNull", ItemStack.class);
                getGunId = optional(gunInterface, "getGunId");
                getGunDisplayId = optional(gunInterface, "getGunDisplayId");
            } catch (ReflectiveOperationException | RuntimeException absent) {
                getIGunOrNull = null;
                getGunId = null;
                getGunDisplayId = null;
                return false;
            }
            // The asset side is separate: a stripped client build may ship the item API without the
            // display index, and losing the picture is better than losing the gun detection.
            try {
                Class<?> managerClass = Class.forName("com.tacz.guns.client.resource.ClientAssetsManager");
                Field instance = managerClass.getField("INSTANCE");
                assetsManager = instance.get(null);
                getGunDisplay = managerClass.getMethod("getGunDisplay", ResourceLocation.class);
                getGunDisplays = managerClass.getMethod("getGunDisplays");
                Class<?> displayClass = Class.forName(
                        "com.tacz.guns.client.resource.pojo.display.gun.GunDisplay");
                getSlotTextureLocation = displayClass.getMethod("getSlotTextureLocation");
            } catch (ReflectiveOperationException | RuntimeException absent) {
                assetsManager = null;
                getGunDisplay = null;
                getGunDisplays = null;
                getSlotTextureLocation = null;
            }
            return true;
        }

        @Nullable
        private static Method optional(Class<?> owner, String name) {
            try {
                return owner.getMethod(name, ItemStack.class);
            } catch (NoSuchMethodException absent) {
                return null;
            }
        }

        private Tacz() {
        }
    }

    // ------------------------------------------------------------------ ordinary items

    /**
     * The flat picture of an ordinary item: its particle sprite, or failing that the sprite of its
     * first quad.
     *
     * <p>The quad fallback covers a model that omits the {@code particle} entry but does have
     * geometry - a hand-written item model can, and without the fallback such an item would be
     * invisible on the plate for no good reason.
     */
    @Nullable
    private static Icon atlasIcon(ItemStack stack) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getModelManager() == null) {
            return null;
        }
        BakedModel model = minecraft.getItemRenderer().getModel(stack, minecraft.level, null, 0);
        if (model == null) {
            return null;
        }
        TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);
        if (isMissing(sprite)) {
            sprite = firstQuadSprite(model);
        }
        if (isMissing(sprite)) {
            return null;
        }
        return new Icon(sprite.atlasLocation(), sprite.getU0(), sprite.getU1(),
                sprite.getV0(), sprite.getV1());
    }

    @Nullable
    private static TextureAtlasSprite firstQuadSprite(BakedModel model) {
        RandomSource random = RandomSource.create(42L);
        for (Direction side : Direction.values()) {
            for (BakedQuad quad : model.getQuads(null, side, random, ModelData.EMPTY, null)) {
                return quad.getSprite();
            }
        }
        for (BakedQuad quad : model.getQuads(null, null, random, ModelData.EMPTY, null)) {
            return quad.getSprite();
        }
        return null;
    }

    /**
     * A texture at a conventional path, for a custom-rendered item that has no sprite to borrow.
     *
     * <p>An item drawn by a block-entity renderer has no quads and usually no particle entry, but it
     * almost always still ships a flat picture for its inventory slot - that is what the item is
     * drawn from everywhere the 3D model is not used. The two paths below are the near-universal
     * conventions; both are probed against the resource manager, so a guess that is wrong simply
     * costs one lookup and yields nothing.
     */
    @Nullable
    private static Icon conventionalIcon(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        if (key == null) {
            return null;
        }
        String namespace = key.getNamespace();
        String path = key.getPath();
        // Texture IDs, not paths: textureExists adds the textures/ prefix and the .png suffix.
        ResourceLocation[] candidates = {
                new ResourceLocation(namespace, "item/" + path),
                new ResourceLocation(namespace, "item/" + path + "_inventory"),
                new ResourceLocation(namespace, "items/" + path)
        };
        for (ResourceLocation candidate : candidates) {
            if (textureExists(candidate)) {
                return whole(candidate);
            }
        }
        return null;
    }

    /**
     * A picture that fills a whole standalone texture, uploaded by this class.
     *
     * <h2>Why the png is uploaded by hand</h2>
     *
     * <p>A gun pack's slot art is <b>not</b> in the block atlas, so the decal has to bind a texture
     * of its own. The obvious route - hand the {@link ResourceLocation} to {@code RenderType} and
     * let {@code TextureManager#getTexture} create a {@code SimpleTexture} - does not work here, and
     * it fails in the most misleading way possible: the plate renders the purple-and-black
     * missing-texture checker.
     *
     * <p>The reason is that {@code SimpleTexture} is a <b>reload-managed</b> texture. It expects the
     * resource manager to feed it during a resource reload; created outside one, it never receives
     * its image and reports the missing texture. That is exactly the "3D texture" the bug looked
     * like from the outside: a texture object exists, so nothing throws, and it draws the checker.
     *
     * <p>So the image is read here and uploaded into a {@link DynamicTexture}, which owns its
     * pixels and needs no reload to be valid. The result is cached per texture id - the read is a
     * file load and the upload is a GL call, neither of which belongs in a render pass.
     */
    @Nullable
    private static Icon whole(ResourceLocation texture) {
        // Not computeIfAbsent: that method does not store a null result, so a texture that cannot
        // be read would be re-read and re-logged on every frame.
        ResourceLocation uploaded = UPLOADED.get(texture);
        if (uploaded == null) {
            if (UPLOAD_FAILED.contains(texture)) {
                return null;
            }
            uploaded = upload(texture);
            if (uploaded == null) {
                UPLOAD_FAILED.add(texture);
                return null;
            }
            UPLOADED.put(texture, uploaded);
        }
        return new Icon(uploaded, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    /** Textures already known to be unreadable, so the failure is reported once. */
    private static final java.util.Set<ResourceLocation> UPLOAD_FAILED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Standalone textures this class has uploaded, keyed by the source texture id. */
    private static final Map<ResourceLocation, ResourceLocation> UPLOADED = new ConcurrentHashMap<>();

    /**
     * Reads {@code source} from the client's resource manager and registers it as a dynamic texture.
     *
     * <p>Returns the registered id, or {@code null} when the image cannot be read - in which case
     * the caller draws nothing rather than a checkerboard.
     */
    @Nullable
    private static ResourceLocation upload(ResourceLocation source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null
                || minecraft.getTextureManager() == null) {
            return null;
        }
        ResourceLocation file = new ResourceLocation(source.getNamespace(),
                "textures/" + source.getPath() + ".png");
        try (java.io.InputStream stream = minecraft.getResourceManager()
                .getResource(file)
                .orElseThrow(() -> new java.io.FileNotFoundException(file.toString()))
                .open()) {
            com.mojang.blaze3d.platform.NativeImage image =
                    com.mojang.blaze3d.platform.NativeImage.read(stream);
            net.minecraft.client.renderer.texture.DynamicTexture dynamic =
                    new net.minecraft.client.renderer.texture.DynamicTexture(image);
            ResourceLocation registered = minecraft.getTextureManager()
                    .register("sidedrain_weapon_icon", dynamic);
            com.sidedrain.SideDrain.LOGGER.info(
                    "Plate weapon icon: uploaded {} as {} ({}x{})", file, registered,
                    image.getWidth(), image.getHeight());
            return registered;
        } catch (java.io.IOException | RuntimeException failed) {
            com.sidedrain.SideDrain.LOGGER.warn(
                    "Plate weapon icon: could not read {}: {}", file, failed.toString());
            return null;
        }
    }

    /** True for the checkerboard sprite, which must never be painted as if it were a picture. */
    private static boolean isMissing(@Nullable TextureAtlasSprite sprite) {
        return sprite == null || sprite.contents() == null
                || MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name());
    }

    /**
     * Whether the client's resource manager can actually see the png behind {@code texture}.
     *
     * <p>Asked before any standalone texture is bound. A {@code SimpleTexture} over a file that is
     * not there does not throw - it logs once and renders the missing-texture checker - so this is
     * the only way to keep a half-installed gun pack from painting a checkerboard onto the plate.
     *
     * <p>Takes a <b>texture id</b> - {@code ns:gun/slot/cs_ak47} - not a file path. The
     * {@code textures/} prefix and {@code .png} suffix are added here, once, and nowhere else: a
     * caller that pre-built the full path would be looking for
     * {@code textures/textures/gun/slot/cs_ak47.png.png}, which silently never matches.
     */
    private static boolean textureExists(ResourceLocation texture) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null) {
            return false;
        }
        ResourceLocation file = new ResourceLocation(texture.getNamespace(),
                "textures/" + texture.getPath() + ".png");
        return minecraft.getResourceManager().getResource(file).isPresent();
    }

    /**
     * Reports a weapon the plate could not find a picture for, once.
     *
     * <p>Only failures are logged. A success is visible on the plate itself, so logging it would be
     * noise in a pack with hundreds of weapons; a failure is not visible at all - the plate simply
     * looks empty - and is exactly the report a player cannot describe beyond "it does not show".
     * One line per distinct weapon, because the lookup is retried rather than cached on a miss.
     */
    private static void note(String source, Icon icon) {
        if (icon == NONE) {
            com.sidedrain.SideDrain.LOGGER.info(
                    "Plate weapon icon: no picture for {} - the plate stays bare", source);
        }
    }

    /**
     * How many icon entries to keep before the picture caches are dropped.
     *
     * <p>Unbounded maps are the classic leak in a mod that memoises per item: a large pack has
     * hundreds of weapons, and every one that ever passed through a plate would keep its entry
     * alive for the whole session. The cap sits far above any realistic working set, so hitting it
     * costs one re-lookup for the plates currently in view and nothing else.
     */
    private static final int ICON_CACHE_LIMIT = 512;

    /**
     * Drops the picture caches when they grow past {@link #ICON_CACHE_LIMIT}.
     *
     * <p>Clearing wholesale rather than evicting one entry at a time is deliberate: the entries are
     * cheap to rebuild (a model lookup, or a cached texture id), an LRU would cost an ordered map on
     * every read, and reaching the limit at all means the working set has already moved on.
     *
     * <p>{@link #UPLOADED} is <b>not</b> cleared here. Its entries are GL textures registered with
     * the texture manager; dropping the map would leak the texture itself, because nothing else
     * holds a reference to release it. Those are released together on a resource reload.
     */
    private static void trimIfNeeded() {
        if (BY_ITEM.size() + BY_GUN.size() > ICON_CACHE_LIMIT) {
            BY_ITEM.clear();
            BY_GUN.clear();
            NO_PICTURE.clear();
            NO_PICTURE_GUN.clear();
        }
    }

    /** Drops every cached picture; call when models or resource packs are reloaded. */
    public static void clearCaches() {
        BY_ITEM.clear();
        BY_GUN.clear();
        NO_PICTURE.clear();
        NO_PICTURE_GUN.clear();
        UPLOADED.clear();
        UPLOAD_FAILED.clear();
    }

    private WeaponIcon() {
    }
}
