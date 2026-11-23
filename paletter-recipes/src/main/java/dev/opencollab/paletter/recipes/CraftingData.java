package dev.opencollab.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.CraftingDataType;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.MultiRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.RecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapedRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.ShapelessRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.SmithingTransformRecipeData;
import org.cloudburstmc.protocol.bedrock.data.inventory.crafting.recipe.SmithingTrimRecipeData;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import dev.opencollab.paletter.Palette;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Recipes encoded for one {@link Palette}: what a {@code CraftingDataPacket} carries. Apart from the net ids and the UUIDs of
 * the recipes, the data of {@link VanillaRecipes} is what BDS sends for the same palette.
 * <ul>
 *     <li>Items resolve through the palette, so the items of its layers work, and tags are the palette's {@link Palette#itemTags()}.</li>
 *     <li>A recipe with an item the palette does not have, a tag without items, or a result block state it does not have is
 *         dropped, as BDS drops it: see {@link #dropped()}. A potion or container mix with a missing item is left out
 *         the same way: see {@link #droppedPotionMixes()} and {@link #droppedContainerMixes()}.</li>
 *     <li>The net ids are 1, 2, ... in the order the recipes were added; a dropped recipe has none. A recipe that equals an
 *         earlier one (records compare by value) is not encoded again: it has that one's net id, or is dropped with it.</li>
 *     <li>The UUIDs are derived from the recipe's id and station, so the same recipes give the same UUIDs on every run. Two
 *         different recipes with the same id and station get the same UUID, which the client may not accept: ids are unique in
 *         {@link VanillaRecipes}, but for your own recipes it is up to you. The {@link Recipe.Multi multi recipes} keep their
 *         fixed UUIDs.</li>
 *     <li>{@link Ingredient.Alias aliases} are sent as they are: they are not checked against the palette.</li>
 * </ul>
 * Protocols from 2168 on get the recipe kinds in lists of their own. For older palettes (protocol below 2168, e.g. 26.30)
 * {@link #applyTo} also fills the single {@code craftingData} list those protocols read, in the order the recipes were added
 * (BDS interleaves the kinds in that list, so the order differs from BDS; the recipes themselves are checked against a BDS
 * capture of 26.30 apart from that order).
 *
 * <pre>{@code
 * CraftingData data = CraftingData.builder(palette).recipes(vanilla.recipes()).potionMixes(vanilla.potionMixes()).build();
 * data.applyTo(craftingDataPacket);
 * }</pre>
 *
 * @since 1.0.0
 */
public final class CraftingData {
    /** The first protocol with a list per recipe kind (2168); older ones read one list. */
    private static final int SPLIT_LISTS_FROM = 2168;

    private final CraftingDataPacket encoded;
    /** Net id by recipe, -1 for the others. */
    private final Object2IntMap<Recipe> netIds;
    private final List<Recipe> dropped;
    private final List<PotionMix> droppedPotionMixes;
    private final List<ContainerMix> droppedContainerMixes;

    private CraftingData(CraftingDataPacket encoded, Object2IntMap<Recipe> netIds, List<Recipe> dropped, List<PotionMix> droppedPotionMixes,
                         List<ContainerMix> droppedContainerMixes) {
        this.encoded = encoded;
        this.netIds = netIds;
        this.dropped = List.copyOf(dropped);
        this.droppedPotionMixes = List.copyOf(droppedPotionMixes);
        this.droppedContainerMixes = List.copyOf(droppedContainerMixes);
    }

    /**
     * Starts collecting recipes for a palette.
     *
     * @param palette the palette whose items, blocks and tags the recipes use
     * @return a builder
     */
    public static @NonNull Builder builder(@NonNull Palette palette) {
        return new Builder(palette);
    }

    /**
     * Adds the recipes to a packet: the lists of each recipe kind, the potion and container mixes. The packet's lists are added to,
     * not replaced, and {@code cleanRecipes} is set (the client forgets its earlier recipes), as BDS does.
     *
     * @param packet the packet to fill
     * @throws NullPointerException if the packet is null
     */
    public void applyTo(@NonNull CraftingDataPacket packet) {
        packet.getCraftingData().addAll(encoded.getCraftingData());
        packet.getShapedData().addAll(encoded.getShapedData());
        packet.getShapelessData().addAll(encoded.getShapelessData());
        packet.getShapelessUserData().addAll(encoded.getShapelessUserData());
        packet.getMultiData().addAll(encoded.getMultiData());
        packet.getSmithingTransformData().addAll(encoded.getSmithingTransformData());
        packet.getSmithingTrimData().addAll(encoded.getSmithingTrimData());
        packet.getPotionMixData().addAll(encoded.getPotionMixData());
        packet.getContainerMixData().addAll(encoded.getContainerMixData());
        packet.setCleanRecipes(true);
    }

    /**
     * Finds the net id of a recipe.
     *
     * @param recipe a recipe that was added to the builder
     * @return its net id, or -1 if it was dropped or not added
     */
    public int netId(@NonNull Recipe recipe) {
        return netIds.getInt(recipe);
    }

    /**
     * Lists the recipes that were left out.
     *
     * @return the recipes that were left out because the palette lacks content they need, in the order they were added
     */
    public @NonNull List<Recipe> dropped() {
        return dropped;
    }

    /**
     * Lists the potion mixes that were left out.
     *
     * @return the potion mixes that were left out because the palette lacks one of their items, in the order they were added
     */
    public @NonNull List<PotionMix> droppedPotionMixes() {
        return droppedPotionMixes;
    }

    /**
     * Lists the container mixes that were left out.
     *
     * @return the container mixes that were left out because the palette lacks one of their items, in the order they were added
     */
    public @NonNull List<ContainerMix> droppedContainerMixes() {
        return droppedContainerMixes;
    }

    /**
     * Collects recipes and mixes; each method adds to what is there.
     *
     * @since 1.0.0
     */
    public static final class Builder {
        private final Palette palette;
        private final List<Recipe> recipes = new ArrayList<>();
        private final List<PotionMix> potionMixes = new ArrayList<>();
        private final List<ContainerMix> containerMixes = new ArrayList<>();

        private Builder(Palette palette) {
            this.palette = palette;
        }

        /**
         * Adds recipes.
         *
         * @param recipes recipes to add, e.g. {@link VanillaRecipes#recipes()}
         * @return this builder
         * @throws NullPointerException if the list is null
         */
        public @NonNull Builder recipes(@NonNull List<Recipe> recipes) {
            this.recipes.addAll(recipes);
            return this;
        }

        /**
         * Adds brewing stand recipes.
         *
         * @param mixes brewing stand recipes to add, e.g. {@link VanillaRecipes#potionMixes()}
         * @return this builder
         * @throws NullPointerException if the list is null
         */
        public @NonNull Builder potionMixes(@NonNull List<PotionMix> mixes) {
            this.potionMixes.addAll(mixes);
            return this;
        }

        /**
         * Adds brewing stand bottle recipes.
         *
         * @param mixes brewing stand bottle recipes to add, e.g. {@link VanillaRecipes#containerMixes()}
         * @return this builder
         * @throws NullPointerException if the list is null
         */
        public @NonNull Builder containerMixes(@NonNull List<ContainerMix> mixes) {
            this.containerMixes.addAll(mixes);
            return this;
        }

        /**
         * Encodes the recipes for the palette. Missing content drops a recipe, it never throws.
         *
         * @return the encoded recipes
         */
        public @NonNull CraftingData build() {
            RecipeEncoder encoder = new RecipeEncoder(palette);
            CraftingDataPacket packet = new CraftingDataPacket();
            Object2IntMap<Recipe> netIds = new Object2IntOpenHashMap<>(recipes.size());
            netIds.defaultReturnValue(-1);
            Set<Recipe> dropped = new LinkedHashSet<>();
            boolean legacy = palette.version().protocolVersion() < SPLIT_LISTS_FROM;
            int netId = 1;
            for (Recipe recipe : recipes) {
                if (netIds.containsKey(recipe) || dropped.contains(recipe)) {
                    continue;
                }
                RecipeData data = switch (recipe) {
                    case Recipe.Shaped shaped -> encoder.shaped(shaped, netId);
                    case Recipe.Shapeless shapeless -> encoder.shapeless(shapeless, netId);
                    case Recipe.Multi multi -> RecipeEncoder.multi(multi, netId);
                    case Recipe.SmithingTransform transform -> encoder.smithingTransform(transform, netId);
                    case Recipe.SmithingTrim trim -> encoder.smithingTrim(trim, netId);
                    default -> throw new IllegalArgumentException(recipe.toString());
                };
                if (data == null) {
                    dropped.add(recipe);
                    continue;
                }
                netIds.put(recipe, netId++);
                add(packet, data);
                if (legacy) {
                    packet.getCraftingData().add(data);
                }
            }
            List<PotionMix> droppedPotionMixes = new ArrayList<>();
            potionMixes.stream().filter(mix -> !add(packet.getPotionMixData(), encoder.potionMix(mix))).forEach(droppedPotionMixes::add);
            List<ContainerMix> droppedContainerMixes = new ArrayList<>();
            containerMixes.stream().filter(mix -> !add(packet.getContainerMixData(), encoder.containerMix(mix))).forEach(droppedContainerMixes::add);
            return new CraftingData(packet, netIds, List.copyOf(dropped), droppedPotionMixes, droppedContainerMixes);
        }

        /** The list of its own that the protocols from 2168 on read. */
        private static void add(CraftingDataPacket packet, RecipeData data) {
            switch (data) {
                case ShapedRecipeData shaped -> packet.getShapedData().add(shaped);
                case ShapelessRecipeData shapeless -> (shapeless.getType() == CraftingDataType.SHULKER_BOX ? packet.getShapelessUserData() : packet.getShapelessData()).add(shapeless);
                case MultiRecipeData multi -> packet.getMultiData().add(multi);
                case SmithingTransformRecipeData transform -> packet.getSmithingTransformData().add(transform);
                case SmithingTrimRecipeData trim -> packet.getSmithingTrimData().add(trim);
                default -> { }
            }
        }

        private static <T> boolean add(List<T> list, T value) {
            return value != null && list.add(value);
        }
    }
}
