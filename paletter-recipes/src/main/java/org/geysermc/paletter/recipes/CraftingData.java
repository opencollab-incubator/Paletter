package org.geysermc.paletter.recipes;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.geysermc.paletter.Palette;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipes encoded for one {@link Palette}: what a {@code CraftingDataPacket} carries. Apart from the net ids and the UUIDs of
 * the recipes, the data of {@link VanillaRecipes} is what BDS sends for the same palette.
 * <ul>
 *     <li>Items resolve through the palette, so the items of its layers work, and tags are the palette's {@link Palette#itemTags()}.</li>
 *     <li>A recipe with an item the palette does not have, a tag without items, or a result block state it does not have is
 *         dropped, as BDS drops it: see {@link #dropped()}. A potion or container mix with a missing item is left out
 *         the same way: see {@link #droppedPotionMixes()} and {@link #droppedContainerMixes()}.</li>
 *     <li>The net ids are 1, 2, ... in the order the recipes were added; a dropped recipe has none.</li>
 *     <li>The UUIDs are derived from the recipe's id and station, so the same recipes give the same UUIDs on every run. The
 *         {@link Recipe.Multi multi recipes} keep their fixed UUIDs.</li>
 * </ul>
 * The data is for the protocol versions that send the recipe kinds in lists of their own (the data versions of this library).
 *
 * <pre>{@code
 * CraftingData data = CraftingData.builder(palette).recipes(vanilla.recipes()).potionMixes(vanilla.potionMixes()).build();
 * data.applyTo(craftingDataPacket);
 * }</pre>
 *
 * @since 1.0.0
 */
public final class CraftingData {
    private final CraftingDataPacket encoded;
    private final Map<Recipe, Integer> netIds;
    private final List<Recipe> dropped;
    private final List<PotionMix> droppedPotionMixes;
    private final List<ContainerMix> droppedContainerMixes;

    private CraftingData(CraftingDataPacket encoded, Map<Recipe, Integer> netIds, List<Recipe> dropped, List<PotionMix> droppedPotionMixes,
                         List<ContainerMix> droppedContainerMixes) {
        this.encoded = encoded;
        this.netIds = netIds;
        this.dropped = List.copyOf(dropped);
        this.droppedPotionMixes = List.copyOf(droppedPotionMixes);
        this.droppedContainerMixes = List.copyOf(droppedContainerMixes);
    }

    /**
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
     */
    public void applyTo(@NonNull CraftingDataPacket packet) {
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
     * @param recipe a recipe that was added to the builder
     * @return its net id, or -1 if it was dropped or not added
     */
    public int netId(@NonNull Recipe recipe) {
        return netIds.getOrDefault(recipe, -1);
    }

    /**
     * @return the recipes that were left out because the palette lacks content they need, in the order they were added
     */
    public @NonNull List<Recipe> dropped() {
        return dropped;
    }

    /**
     * @return the potion mixes that were left out because the palette lacks one of their items, in the order they were added
     */
    public @NonNull List<PotionMix> droppedPotionMixes() {
        return droppedPotionMixes;
    }

    /**
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
         * @param recipes recipes to add, e.g. {@link VanillaRecipes#recipes()}
         * @return this builder
         */
        public @NonNull Builder recipes(@NonNull List<Recipe> recipes) {
            this.recipes.addAll(recipes);
            return this;
        }

        /**
         * @param mixes brewing stand recipes to add, e.g. {@link VanillaRecipes#potionMixes()}
         * @return this builder
         */
        public @NonNull Builder potionMixes(@NonNull List<PotionMix> mixes) {
            this.potionMixes.addAll(mixes);
            return this;
        }

        /**
         * @param mixes brewing stand bottle recipes to add, e.g. {@link VanillaRecipes#containerMixes()}
         * @return this builder
         */
        public @NonNull Builder containerMixes(@NonNull List<ContainerMix> mixes) {
            this.containerMixes.addAll(mixes);
            return this;
        }

        /**
         * @return the encoded recipes. Missing content drops a recipe, it never throws
         */
        public @NonNull CraftingData build() {
            RecipeEncoder encoder = new RecipeEncoder(palette);
            CraftingDataPacket packet = new CraftingDataPacket();
            Map<Recipe, Integer> netIds = new HashMap<>();
            List<Recipe> dropped = new ArrayList<>();
            int netId = 1;
            for (Recipe recipe : recipes) {
                boolean encoded = switch (recipe) {
                    case Recipe.Shaped shaped -> add(packet.getShapedData(), encoder.shaped(shaped, netId));
                    case Recipe.Shapeless shapeless -> add(shapeless.shulkerBox() ? packet.getShapelessUserData() : packet.getShapelessData(),
                        encoder.shapeless(shapeless, netId));
                    case Recipe.Multi multi -> add(packet.getMultiData(), RecipeEncoder.multi(multi, netId));
                    case Recipe.SmithingTransform transform -> add(packet.getSmithingTransformData(), encoder.smithingTransform(transform, netId));
                    case Recipe.SmithingTrim trim -> add(packet.getSmithingTrimData(), encoder.smithingTrim(trim, netId));
                };
                if (encoded) {
                    netIds.putIfAbsent(recipe, netId++);
                } else {
                    dropped.add(recipe);
                }
            }
            List<PotionMix> droppedPotionMixes = new ArrayList<>();
            potionMixes.stream().filter(mix -> !add(packet.getPotionMixData(), encoder.potionMix(mix))).forEach(droppedPotionMixes::add);
            List<ContainerMix> droppedContainerMixes = new ArrayList<>();
            containerMixes.stream().filter(mix -> !add(packet.getContainerMixData(), encoder.containerMix(mix))).forEach(droppedContainerMixes::add);
            return new CraftingData(packet, netIds, dropped, droppedPotionMixes, droppedContainerMixes);
        }

        private static <T> boolean add(List<T> list, T value) {
            return value != null && list.add(value);
        }
    }
}
