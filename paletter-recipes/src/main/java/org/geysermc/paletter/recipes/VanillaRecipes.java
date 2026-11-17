package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.data.PaletteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vanilla recipes of one data version, as the Bedrock Dedicated Server sends them, bundled in the library. They are
 * generated from the BDS captures in the repository's {@code data/bds}, without experiments: what a toggle changes is in
 * {@link #experiments()}. Items are {@link org.geysermc.paletter.vanilla.ItemKey}s, so they resolve through a palette
 * ({@code Palette#resolve}) like any other.
 *
 * @since 1.0.0
 */
public final class VanillaRecipes {
    private static final Map<String, VanillaRecipes> CACHE = new ConcurrentHashMap<>();

    private final DataVersion version;
    private final List<Recipe> recipes;
    private final List<PotionMix> potionMixes;
    private final List<ContainerMix> containerMixes;
    private final List<TrimPattern> trimPatterns;
    private final List<TrimMaterial> trimMaterials;
    private final Map<String, ExperimentRecipes> experiments;

    VanillaRecipes(DataVersion version, List<Recipe> recipes, List<PotionMix> potionMixes, List<ContainerMix> containerMixes,
                   List<TrimPattern> trimPatterns, List<TrimMaterial> trimMaterials, Map<String, ExperimentRecipes> experiments) {
        this.version = version;
        this.recipes = List.copyOf(recipes);
        this.potionMixes = List.copyOf(potionMixes);
        this.containerMixes = List.copyOf(containerMixes);
        this.trimPatterns = List.copyOf(trimPatterns);
        this.trimMaterials = List.copyOf(trimMaterials);
        this.experiments = Map.copyOf(experiments);
    }

    /**
     * The recipes of the newest bundled data version that is not newer than the given one (a version serves every protocol
     * from its first one up to the next version's, as for the palettes), or of the oldest bundled one if the given version is
     * older than all of them. The result is cached.
     *
     * @param version a data version, e.g. of a palette ({@code Palette#version()})
     * @return the recipes; {@link #version()} is the bundled version they belong to
     * @throws UncheckedIOException if the bundled file cannot be read (a damaged library jar)
     */
    public static @NonNull VanillaRecipes of(@NonNull DataVersion version) {
        List<DataVersion> bundled = PaletteDataSource.bundled().versions();
        DataVersion chosen = bundled.getFirst();
        for (DataVersion candidate : bundled) {
            if (candidate.compareTo(version) <= 0) {
                chosen = candidate;
            }
        }
        DataVersion served = chosen;
        return CACHE.computeIfAbsent(served.name(), name -> {
            try (InputStream stream = VanillaRecipes.class.getResourceAsStream("/paletter/recipes/" + name + ".nbt")) {
                if (stream == null) {
                    throw new IOException("The library has no recipes for the data version " + name);
                }
                return RecipeBundle.read(served, stream);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * @return the bundled data version these recipes belong to
     */
    public @NonNull DataVersion version() {
        return version;
    }

    /**
     * @return the crafting, smelting and smithing recipes in the order BDS sends them: shaped, shapeless, shulker box (in
     *         {@link Recipe.Shapeless}), multi, smithing transform, smithing trim
     */
    public @NonNull List<Recipe> recipes() {
        return recipes;
    }

    /**
     * @return the brewing stand recipes that change a potion
     */
    public @NonNull List<PotionMix> potionMixes() {
        return potionMixes;
    }

    /**
     * @return the brewing stand recipes that change a potion's bottle
     */
    public @NonNull List<ContainerMix> containerMixes() {
        return containerMixes;
    }

    /**
     * @return the armor trim patterns
     */
    public @NonNull List<TrimPattern> trimPatterns() {
        return trimPatterns;
    }

    /**
     * @return the armor trim materials
     */
    public @NonNull List<TrimMaterial> trimMaterials() {
        return trimMaterials;
    }

    /**
     * @return per experiment toggle, what it does to the recipes; only toggles that touch recipes, and only for versions whose
     *         captures recorded it
     */
    public @NonNull Map<String, ExperimentRecipes> experiments() {
        return experiments;
    }
}
