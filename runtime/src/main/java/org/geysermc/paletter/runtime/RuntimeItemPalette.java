package org.geysermc.paletter.runtime;

import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.api.component.BedrockComponentType;
import org.geysermc.paletter.api.component.BedrockComponents;
import org.geysermc.paletter.api.data.ItemDefinition;
import org.geysermc.paletter.api.item.BedrockItem;
import org.geysermc.paletter.api.item.CustomBedrockItem;
import org.geysermc.paletter.api.item.VersionedBedrockItem;
import org.geysermc.paletter.api.palette.ItemPalette;
import org.geysermc.paletter.palette.item.Item;
import org.geysermc.paletter.palette.item.ItemComponentData;
import org.geysermc.paletter.palette.item.ItemComponentPalette;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

final class RuntimeItemPalette implements ItemPalette {
    private final BedrockVersion version;
    private final Map<BedrockItem, Entry> byItem;
    private final Map<Integer, BedrockItem> byRuntimeId;
    private final Map<BedrockItem, BedrockItem> fallbacks;

    RuntimeItemPalette(BedrockVersion version, org.geysermc.paletter.palette.item.ItemPalette raw, ItemComponentPalette rawComponents) {
        this.version = version;

        Map<String, ItemComponentData> componentsByIdentifier = rawComponents.items().stream()
                .collect(Collectors.toMap(ItemComponentData::identifier, Function.identity(), (first, duplicate) -> first));

        Map<BedrockItem, Entry> byItem = new LinkedHashMap<>();
        Map<Integer, BedrockItem> byRuntimeId = new HashMap<>();
        for (Item item : raw.items()) {
            BedrockItem identity = BedrockItem.of(item.name());
            BedrockComponents components = toComponents(componentsByIdentifier.get(item.name()));

            byItem.put(identity, new Entry(item.id(), item.componentBased(), components));
            byRuntimeId.put(item.id(), identity);
        }

        this.byItem = Map.copyOf(byItem);
        this.byRuntimeId = Map.copyOf(byRuntimeId);
        this.fallbacks = Map.of();
    }

    private RuntimeItemPalette(BedrockVersion version, Map<BedrockItem, Entry> byItem, Map<Integer, BedrockItem> byRuntimeId, Map<BedrockItem, BedrockItem> fallbacks) {
        this.version = version;
        this.byItem = byItem;
        this.byRuntimeId = byRuntimeId;
        this.fallbacks = fallbacks;
    }

    private static BedrockComponents toComponents(ItemComponentData data) {
        if (data == null) {
            return BedrockComponents.builder().build();
        }

        BedrockComponents.Builder builder = BedrockComponents.builder();
        for (String key : data.components().keySet()) {
            if (key.indexOf(':') < 0) {
                // Skips "item_properties"/"item_tags" - synthetic buckets, not real component types.
                continue;
            }
            putRaw(builder, key, data.components().get(key));
        }
        return builder.build();
    }

    // The component's real value type isn't known here (see the generator's ItemComponentsGenerator,
    // which emits BedrockComponentType<?> for the same reason) - reading it back is necessarily unchecked.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putRaw(BedrockComponents.Builder builder, String key, Object value) {
        BedrockComponentType type = BedrockComponentType.of(key);
        builder.set(type, value);
    }

    // Copy-on-write, same as RuntimeBlockPalette.withCustoms - customs get a synthetic runtime id
    // continuing past the highest vanilla one, fallbacks are recorded for get()/byIdentifier() to consult.
    RuntimeItemPalette withCustomsAndFallbacks(List<CustomBedrockItem> customs, Map<BedrockItem, BedrockItem> newFallbacks) {
        if (customs.isEmpty() && newFallbacks.isEmpty()) {
            return this;
        }

        Map<BedrockItem, Entry> byItem = new LinkedHashMap<>(this.byItem);
        Map<Integer, BedrockItem> byRuntimeId = new HashMap<>(this.byRuntimeId);
        Map<BedrockItem, BedrockItem> fallbacks = new HashMap<>(this.fallbacks);
        fallbacks.putAll(newFallbacks);

        int nextRuntimeId = byRuntimeId.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        for (CustomBedrockItem custom : customs) {
            BedrockItem identity = BedrockItem.of(custom.identifier());
            int runtimeId = nextRuntimeId++;

            byItem.put(identity, new Entry(runtimeId, true, custom.components()));
            byRuntimeId.put(runtimeId, identity);
        }

        return new RuntimeItemPalette(version, Map.copyOf(byItem), Map.copyOf(byRuntimeId), Map.copyOf(fallbacks));
    }

    @Override
    public BedrockVersion version() {
        return version;
    }

    @Override
    public VersionedBedrockItem get(BedrockItem item) {
        Entry entry = byItem.get(item);
        if (entry != null) {
            return dataFor(item, entry, false);
        }

        BedrockItem fallback = fallbacks.get(item);
        if (fallback == null) {
            throw new IllegalArgumentException("Unknown item: " + item.identifier());
        }

        Entry fallbackEntry = byItem.get(fallback);
        if (fallbackEntry == null) {
            throw new IllegalArgumentException("Fallback target is itself unknown: " + fallback.identifier());
        }
        return dataFor(fallback, fallbackEntry, true);
    }

    private VersionedBedrockItem dataFor(BedrockItem item, Entry entry, boolean isFallback) {
        ItemDefinition definition = new ItemDefinition(entry.runtimeId(), item.identifier(), entry.componentBased());
        return new SimpleBedrockItemData(version, item, definition, entry.components(), isFallback);
    }

    @Override
    public Optional<VersionedBedrockItem> byRuntimeId(int runtimeId) {
        BedrockItem item = byRuntimeId.get(runtimeId);
        return item == null ? Optional.empty() : Optional.of(get(item));
    }

    @Override
    public Optional<VersionedBedrockItem> byIdentifier(String identifier) {
        BedrockItem item = BedrockItem.of(identifier);
        return byItem.containsKey(item) ? Optional.of(get(item)) : Optional.empty();
    }

    @Override
    public Collection<VersionedBedrockItem> items() {
        return byItem.keySet().stream().map(this::get).toList();
    }

    @Override
    public Builder toBuilder() {
        return new RuntimeItemPaletteBuilder(this);
    }

    private record Entry(int runtimeId, boolean componentBased, BedrockComponents components) {
    }
}
