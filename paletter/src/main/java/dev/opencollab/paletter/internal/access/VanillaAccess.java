package dev.opencollab.paletter.internal.access;

import dev.opencollab.paletter.block.BlockProperty;
import dev.opencollab.paletter.internal.CanonicalBlocks;
import dev.opencollab.paletter.internal.CanonicalItems;
import dev.opencollab.paletter.internal.item.ItemVariant;
import dev.opencollab.paletter.vanilla.BedrockBlockState;
import dev.opencollab.paletter.vanilla.BedrockBlockType;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import dev.opencollab.paletter.vanilla.BedrockItemVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/** The package-private members of {@code dev.opencollab.paletter.vanilla} that other packages use. Not API. */
public interface VanillaAccess {
    /** {@code BedrockBlockType.custom}: a new custom block type, which a {@code CustomBlock} owns. */
    @NonNull BedrockBlockType customBlockType(@NonNull String identifier, @NonNull List<BlockProperty<?>> properties,
                                              @NonNull Map<String, Object> defaultStates);

    /** {@code BedrockBlockType.fromIdentifier}: the vanilla type of an identifier, else a custom type known by it only. */
    @NonNull BedrockBlockType blockType(@NonNull String identifier);

    /** {@code BedrockBlockType.vanillaType}: the vanilla type of the newest bundled data with an identifier, or null. */
    @Nullable BedrockBlockType vanillaBlockType(@NonNull String identifier);

    /** {@code BedrockBlockState.fromCanonical}: the state of a canonical id, or null. */
    @Nullable BedrockBlockState blockState(@NonNull CanonicalBlocks canonical, int id);

    /** {@code BedrockBlockState#canonicalIn}: the canonical id of a state, or -1. */
    int canonicalId(@NonNull BedrockBlockState state, @NonNull CanonicalBlocks canonical);

    /** {@code BedrockItemType.custom}: a new custom item type, which a {@code CustomItem} or {@code CustomBlock} owns. */
    @NonNull BedrockItemType customItemType(@NonNull String identifier);

    /** {@code BedrockItemType.fromIdentifier}: the vanilla type of an identifier, else a custom type known by it only. */
    @NonNull BedrockItemType itemType(@NonNull String identifier);

    /** {@code BedrockItemVariant.fromIdentifier}: the variant of an identifier and aux value. */
    @NonNull BedrockItemVariant itemVariant(@NonNull String identifier, int aux);

    /** {@code BedrockItemVariant.fromCanonical}: the variant of a canonical id. */
    @NonNull BedrockItemVariant itemVariant(@NonNull CanonicalItems canonical, int id);

    /** {@code BedrockItemType#toItemVariant}: the internal variant of a type's base variant. */
    @NonNull ItemVariant toItemVariant(@NonNull BedrockItemType type);

    /** {@code BedrockItemVariant#toItemVariant}: the internal variant of a variant. */
    @NonNull ItemVariant toItemVariant(@NonNull BedrockItemVariant variant);

    /** {@code BedrockItemType#canonicalIn}: the canonical id of a type's base variant, or -1. */
    int canonicalId(@NonNull BedrockItemType type, @NonNull CanonicalItems canonical);

    /** {@code BedrockItemVariant#canonicalIn}: the canonical id of a variant, or -1. */
    int canonicalId(@NonNull BedrockItemVariant variant, @NonNull CanonicalItems canonical);
}
