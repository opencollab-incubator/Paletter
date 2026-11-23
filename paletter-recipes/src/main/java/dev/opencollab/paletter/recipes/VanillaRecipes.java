package dev.opencollab.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vanilla recipes of one data version, as the Bedrock Dedicated Server sends them, bundled in the library: crafting,
 * smelting and smithing recipes and the brewing stand mixes. They are generated from captures of the Bedrock Dedicated
 * Server of each version, without experiments. Items are {@link dev.opencollab.paletter.vanilla.ItemKey}s, so they resolve through
 * a palette ({@code Palette#resolve}) like any other.
 * <p>
 * There is a bundle for every data version of {@code Paletter#versions()} of the bundled data, previews included.
 *
 * @since 1.0.0
 */
public final class VanillaRecipes {
    /**
     * Read recipes by version name, held softly: a consumer typically builds its {@code CraftingData} from them once per
     * palette and keeps that, so the recipes (about 3.8 MB per version, see {@code RecipesMemoryTest}) are not pinned for the life of the JVM. While
     * someone holds them, or memory is plentiful, {@link #of} returns the same instance; after a collection under memory
     * pressure it reads the version again.
     */
    private static final Map<String, SoftReference<VanillaRecipes>> CACHE = new ConcurrentHashMap<>();

    private final DataVersion version;
    private final List<Recipe> recipes;
    private final List<PotionMix> potionMixes;
    private final List<ContainerMix> containerMixes;

    VanillaRecipes(DataVersion version, List<Recipe> recipes, List<PotionMix> potionMixes, List<ContainerMix> containerMixes) {
        this.version = version;
        this.recipes = List.copyOf(recipes);
        this.potionMixes = List.copyOf(potionMixes);
        this.containerMixes = List.copyOf(containerMixes);
    }

    /**
     * Lists the data versions that have recipes.
     *
     * @return the data versions of the bundled data that have recipes, oldest first
     */
    public static @NonNull List<DataVersion> versions() {
        return PaletteDataSource.bundled().versions().stream().filter(VanillaRecipes::hasBundle).toList();
    }

    /**
     * The recipes of a data version. They are cached softly: the same instance while it is in use, read again after memory
     * ran short. Keep what is built from them (a {@code CraftingData}), not these.
     *
     * @param version a data version of {@link #versions()}
     * @return its recipes
     * @throws IllegalArgumentException if the version has no bundled recipes (use {@link #of(DataVersion, VanillaRecipes)} to get
     *         a fallback instead)
     * @throws UncheckedIOException if the bundled file cannot be read (a damaged library jar)
     */
    public static @NonNull VanillaRecipes of(@NonNull DataVersion version) {
        VanillaRecipes recipes = of(version, null);
        if (recipes == null) {
            throw new IllegalArgumentException("No bundled recipes for the data version " + version + "; the versions with recipes are " + versions());
        }
        return recipes;
    }

    /**
     * Finds the recipes of a data version, cached softly like {@link #of(DataVersion)}.
     *
     * @param version a data version
     * @param orElse what to return for a version without bundled recipes
     * @return the recipes of the version, or {@code orElse}
     * @throws UncheckedIOException if the bundled file cannot be read (a damaged library jar)
     */
    public static @Nullable VanillaRecipes of(@NonNull DataVersion version, @Nullable VanillaRecipes orElse) {
        if (!hasBundle(version)) {
            return orElse;
        }
        VanillaRecipes cached = cached(version);
        if (cached != null) {
            return cached;
        }
        synchronized (CACHE) {
            cached = cached(version);
            if (cached == null) {
                try (InputStream stream = VanillaRecipes.class.getResourceAsStream(path(version))) {
                    cached = RecipeBundle.read(version, stream);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                CACHE.put(version.name(), new SoftReference<>(cached));
            }
            return cached;
        }
    }

    private static @Nullable VanillaRecipes cached(DataVersion version) {
        SoftReference<VanillaRecipes> reference = CACHE.get(version.name());
        return reference == null ? null : reference.get();
    }

    /** Whether the data source has the version (exactly) and the library has its recipes. */
    private static boolean hasBundle(DataVersion version) {
        return PaletteDataSource.bundled().versions().contains(version) && VanillaRecipes.class.getResource(path(version)) != null;
    }

    private static String path(DataVersion version) {
        return "/paletter/recipes/" + version.name() + ".nbt";
    }

    /**
     * Returns the data version of these recipes.
     *
     * @return the data version these recipes belong to
     */
    public @NonNull DataVersion version() {
        return version;
    }

    /**
     * Lists the crafting, smelting and smithing recipes.
     *
     * @return the recipes in the order BDS sends them: shaped, shapeless, shulker box (in {@link Recipe.Shapeless}),
     *         multi, smithing transform, smithing trim
     */
    public @NonNull List<Recipe> recipes() {
        return recipes;
    }

    /**
     * Lists the brewing stand recipes that change a potion.
     *
     * @return the potion mixes
     */
    public @NonNull List<PotionMix> potionMixes() {
        return potionMixes;
    }

    /**
     * Lists the brewing stand recipes that change a potion's bottle.
     *
     * @return the container mixes
     */
    public @NonNull List<ContainerMix> containerMixes() {
        return containerMixes;
    }
}
