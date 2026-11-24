package dev.opencollab.paletter.internal.access;

import dev.opencollab.paletter.Origin;
import dev.opencollab.paletter.ResolutionKind;
import dev.opencollab.paletter.block.RegisteredBlockState;
import dev.opencollab.paletter.internal.component.WireContext;
import dev.opencollab.paletter.internal.item.ItemRegistry;
import dev.opencollab.paletter.internal.item.ItemVariant;
import dev.opencollab.paletter.item.CustomItem;
import dev.opencollab.paletter.item.ItemOverride;
import dev.opencollab.paletter.item.RegisteredItem;
import dev.opencollab.paletter.item.ResolvedItem;
import dev.opencollab.paletter.item.component.ItemComponent;
import dev.opencollab.paletter.vanilla.BedrockItemType;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;

import java.util.List;

/** The package-private members of {@code dev.opencollab.paletter.item} that other packages use. Not API. */
public interface ItemAccess {
    /** The constructor of {@link RegisteredItem}. */
    @NonNull RegisteredItem registeredItem(@NonNull String identifier, int runtimeId, @NonNull ItemVersion version, boolean componentBased,
                                           @Nullable NbtMap componentData, @NonNull Origin origin, @NonNull List<ItemComponent> components,
                                           @Nullable CustomItem customItem, @Nullable BedrockItemType type);

    /** The constructor of {@link ResolvedItem}. */
    @NonNull ResolvedItem resolvedItem(@NonNull RegisteredItem definition, int aux, @Nullable RegisteredBlockState block,
                                       @NonNull ResolutionKind kind, @NonNull ItemRegistry registry);

    /** {@code ResolvedItem#sameAs}: whether two resolutions have the same parts, whichever palette made them. */
    boolean sameAs(@NonNull ResolvedItem a, @NonNull ResolvedItem b);

    /** The constructor of {@link ItemOverride}: an override of an item, which a layer's callback edits. */
    @NonNull ItemOverride override(@NonNull RegisteredItem original);

    /** {@code ItemOverride#changesDefinition()}. */
    boolean changesDefinition(@NonNull ItemOverride override);

    /** {@code ItemOverride#setsBlock()}. */
    boolean setsBlock(@NonNull ItemOverride override);

    /** {@code ItemOverride#block()}. */
    @Nullable NbtMap block(@NonNull ItemOverride override);

    /** {@code ItemOverride#apply(WireContext)}. */
    @NonNull RegisteredItem apply(@NonNull ItemOverride override, @NonNull WireContext context);

    /** {@code CustomItem#variant()}: the internal variant of a custom item. */
    @NonNull ItemVariant variant(@NonNull CustomItem item);
}
