package org.geysermc.paletter.internal.data;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.data.BlockPropertyData;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.data.DataVersion;
import org.geysermc.paletter.property.Properties;
import org.geysermc.paletter.property.Property;
import org.geysermc.paletter.property.PropertyKind;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Every property of every item and block, stored once with the versions it is valid for. Immutable.
 * <p>
 * The bundled form is {@code properties.nbt}, a gzipped big endian NBT compound:
 * <pre>
 * format: int                      {@value #FORMAT}
 * versions: [string]               version names, oldest first
 * items: {identifier: {property: [{from: name, to: name, value: tag}]}}
 * blocks: {identifier: {property: [{from: name, to: name, value: tag}]}}
 * block_order: {version: [identifier]}   optional: the order BDS sends the data driven blocks of a version in
 * </pre>
 * A property name is the {@link PropertyKind#prefix() kind prefix} and the name. Ranges are inclusive, in version order,
 * and an equal value in consecutive versions is one range. Item component data and the property compound of a data
 * driven block are split one level deep: every entry of {@code components} is a {@link PropertyKind#COMPONENT}
 * property, every other key a {@link PropertyKind#WIRE} property. An item or block whose compound is empty has the
 * property {@code #present} instead, so it still exists (and is not {@code null}) when the data is put together again.
 * <p>
 * Behaviour properties ({@link PropertyKind#BEHAVIOUR}) have no producer yet. The capture of the Script API puts them
 * in with {@link Builder#put}, next to what {@link #build(List)} derives from the network data.
 */
@Internal
public final class PropertyStore {
    public static final int FORMAT = 1;
    private static final PropertyStore EMPTY = new PropertyStore(List.of(), Map.of(), Map.of(), Map.of());
    /** The property of an item or block whose network data is an empty compound. */
    static final String PRESENT = "#present";

    /** Whose property it is. */
    public enum Owner {
        ITEM,
        BLOCK
    }

    /** The value in versions {@code from} to {@code to}, as indexes of {@link #versions}. */
    private record Range(int from, int to, Object value) {
    }

    private final List<String> versions;
    private final Map<String, Map<String, List<Range>>> items;
    private final Map<String, Map<String, List<Range>>> blocks;
    /** Version name to the data driven blocks of it in the order BDS sends them. Properties alone are sorted by identifier. */
    private final Map<String, List<String>> blockOrder;

    private PropertyStore(List<String> versions, Map<String, Map<String, List<Range>>> items,
                          Map<String, Map<String, List<Range>>> blocks, Map<String, List<String>> blockOrder) {
        this.versions = versions;
        this.items = items;
        this.blocks = blocks;
        this.blockOrder = blockOrder;
    }

    public static @NonNull PropertyStore empty() {
        return EMPTY;
    }

    /** @return the version names, oldest first */
    public @NonNull List<String> versions() {
        return versions;
    }

    // ---- Building ----

    /**
     * Derives the store from the network data of versions: component data of items and property data of data driven
     * blocks.
     */
    public static @NonNull PropertyStore build(@NonNull List<VanillaData> versions) {
        List<VanillaData> sorted = new ArrayList<>(versions);
        sorted.sort(Comparator.comparing(VanillaData::version));
        Builder builder = new Builder(sorted.stream().map(VanillaData::version).toList());
        for (VanillaData data : sorted) {
            for (VanillaItem item : data.items()) {
                if (item.componentData() != null) {
                    builder.putCompound(Owner.ITEM, item.identifier(), data.version(), item.componentData());
                }
            }
            for (BlockPropertyData block : data.dataDrivenBlocks()) {
                builder.putCompound(Owner.BLOCK, block.getName(), data.version(), block.getProperties());
            }
            builder.blockOrder(data.version(), data.dataDrivenBlocks().stream().map(BlockPropertyData::getName).toList());
        }
        return builder.build();
    }

    /**
     * Collects property values per version. Data that is not network data (the behaviour properties of the Script API
     * capture) comes in through {@link #put}.
     */
    public static final class Builder {
        private final List<String> names = new ArrayList<>();
        private final Map<String, Integer> index = new HashMap<>();
        // owner -> identifier -> full name -> version index -> value
        private final Map<Owner, Map<String, Map<String, TreeMap<Integer, Object>>>> values = new EnumMap<>(Owner.class);
        private final Map<String, List<String>> blockOrder = new TreeMap<>();

        /**
         * @param versions the versions the store covers; a property can only have values in these
         */
        public Builder(@NonNull List<DataVersion> versions) {
            List<DataVersion> sorted = new ArrayList<>(versions);
            sorted.sort(Comparator.naturalOrder());
            for (DataVersion version : sorted) {
                if (index.put(version.name(), names.size()) != null) {
                    throw new IllegalArgumentException("Duplicate version " + version.name());
                }
                names.add(version.name());
            }
            for (Owner owner : Owner.values()) {
                values.put(owner, new TreeMap<>());
            }
        }

        /**
         * Sets one property of an item or block in one version. This is the entry for property producers: the network
         * data comes in through {@link PropertyStore#build}, behaviour properties (friction, destroy time) are put
         * here by the Script API capture.
         *
         * @param owner whether the identifier is an item or a block
         * @param identifier the item or block identifier
         * @param kind the kind of property
         * @param name the property name within its kind
         * @param version the version the value is valid in
         * @param value the value, an NBT tag value
         */
        public @NonNull Builder put(@NonNull Owner owner, @NonNull String identifier, @NonNull PropertyKind kind,
                                    @NonNull String name, @NonNull DataVersion version, @NonNull Object value) {
            return putFull(owner, identifier, kind.prefix() + name, version, value);
        }

        Builder putFull(Owner owner, String identifier, String fullName, DataVersion version, Object value) {
            Integer at = index.get(version.name());
            if (at == null) {
                throw new IllegalArgumentException("Version " + version + " is not part of the store");
            }
            values.get(owner).computeIfAbsent(identifier, k -> new TreeMap<>())
                .computeIfAbsent(fullName, k -> new TreeMap<>()).put(at, value);
            return this;
        }

        /** Splits the network data compound of an item or block into properties. */
        void putCompound(Owner owner, String identifier, DataVersion version, NbtMap compound) {
            if (compound.isEmpty()) {
                putFull(owner, identifier, PRESENT, version, (byte) 1);
                return;
            }
            compound.forEach((key, value) -> {
                if (key.equals("components") && value instanceof NbtMap components && !components.isEmpty()) {
                    components.forEach((name, component) ->
                        putFull(owner, identifier, PropertyKind.COMPONENT.prefix() + name, version, component));
                } else {
                    putFull(owner, identifier, PropertyKind.WIRE.prefix() + key, version, value);
                }
            });
        }

        /**
         * Records the order BDS sends the data driven blocks of a version in; the properties of the blocks are stored
         * sorted, so this is what {@link PropertyStore#attach} puts them back in order with.
         */
        public @NonNull Builder blockOrder(@NonNull DataVersion version, @NonNull List<String> identifiers) {
            if (!index.containsKey(version.name())) {
                throw new IllegalArgumentException("Version " + version + " is not part of the store");
            }
            blockOrder.put(version.name(), List.copyOf(identifiers));
            return this;
        }

        public @NonNull PropertyStore build() {
            return new PropertyStore(List.copyOf(names), merge(values.get(Owner.ITEM)), merge(values.get(Owner.BLOCK)),
                Map.copyOf(blockOrder));
        }

        private static Map<String, Map<String, List<Range>>> merge(Map<String, Map<String, TreeMap<Integer, Object>>> owner) {
            Map<String, Map<String, List<Range>>> result = new TreeMap<>();
            owner.forEach((identifier, properties) -> {
                Map<String, List<Range>> merged = new TreeMap<>();
                properties.forEach((name, byVersion) -> {
                    List<Range> ranges = new ArrayList<>();
                    byVersion.forEach((at, value) -> {
                        Range last = ranges.isEmpty() ? null : ranges.getLast();
                        if (last != null && last.to() == at - 1 && Objects.deepEquals(last.value(), value)) {
                            ranges.set(ranges.size() - 1, new Range(last.from(), at, last.value()));
                        } else {
                            ranges.add(new Range(at, at, value));
                        }
                    });
                    merged.put(name, List.copyOf(ranges));
                });
                result.put(identifier, merged);
            });
            return result;
        }
    }

    // ---- Lookup ----

    /** @return the properties of an item in a version; empty if the version or the item is unknown */
    public @NonNull Properties item(@NonNull String identifier, @NonNull DataVersion version) {
        return lookup(items, identifier, version);
    }

    /** @return the properties of a data driven block in a version; empty if the version or the block is unknown */
    public @NonNull Properties block(@NonNull String identifier, @NonNull DataVersion version) {
        return lookup(blocks, identifier, version);
    }

    private Properties lookup(Map<String, Map<String, List<Range>>> owner, String identifier, DataVersion version) {
        int at = versions.indexOf(version.name());
        Map<String, List<Range>> properties = owner.get(identifier);
        if (at < 0 || properties == null) {
            return Properties.EMPTY;
        }
        List<Property> result = new ArrayList<>();
        properties.forEach((fullName, ranges) -> {
            Object value = valueAt(ranges, at);
            Property property = value == null ? null : toProperty(fullName, value);
            if (property != null) {
                result.add(property);
            }
        });
        return Properties.of(result);
    }

    private static @Nullable Object valueAt(List<Range> ranges, int at) {
        for (Range range : ranges) {
            if (range.from() <= at && at <= range.to()) {
                return range.value();
            }
        }
        return null;
    }

    private static @Nullable Property toProperty(String fullName, Object value) {
        for (PropertyKind kind : PropertyKind.values()) {
            if (fullName.startsWith(kind.prefix())) {
                return new Property(kind, fullName.substring(kind.prefix().length()), value);
            }
        }
        return null;
    }

    /**
     * Puts the network data of the version of {@code data} together from the properties: the component data of items
     * that have none, and the data driven blocks if there are none. Data of a version the store does not cover is
     * returned as it is.
     */
    public @NonNull VanillaData attach(@NonNull VanillaData data) {
        int at = versions.indexOf(data.version().name());
        if (at < 0) {
            return data;
        }
        VanillaData result = data;
        if (!items.isEmpty() && data.items().stream().anyMatch(item -> item.componentData() == null)) {
            List<VanillaItem> attached = new ArrayList<>(data.items().size());
            for (VanillaItem item : data.items()) {
                NbtMap components = item.componentData();
                if (components == null) {
                    Map<String, List<Range>> properties = items.get(item.identifier());
                    components = properties == null ? null : assemble(properties, at);
                }
                attached.add(components == item.componentData() ? item
                    : new VanillaItem(item.identifier(), item.runtimeId(), item.version(), item.componentBased(), components));
            }
            result = result.withItems(attached);
        }
        if (data.dataDrivenBlocks().isEmpty()) {
            // The order BDS sends them in; blocks it does not list (put in by other producers) follow, sorted
            List<String> order = new ArrayList<>(blockOrder.getOrDefault(data.version().name(), List.of()));
            java.util.Set<String> listed = new java.util.HashSet<>(order);
            blocks.keySet().stream().filter(identifier -> !listed.contains(identifier)).forEach(order::add);
            List<BlockPropertyData> attached = new ArrayList<>();
            for (String identifier : order) {
                Map<String, List<Range>> properties = blocks.get(identifier);
                NbtMap compound = properties == null ? null : assemble(properties, at);
                if (compound != null) {
                    attached.add(new BlockPropertyData(identifier, compound));
                }
            }
            if (!attached.isEmpty()) {
                result = result.withDataDrivenBlocks(attached);
            }
        }
        return result;
    }

    /** The compound of the properties valid in a version, or null if there are none (the item or block is not there). */
    private static @Nullable NbtMap assemble(Map<String, List<Range>> properties, int at) {
        NbtMapBuilder root = NbtMap.builder();
        NbtMapBuilder components = NbtMap.builder();
        boolean[] present = {false};
        boolean[] hasComponents = {false};
        properties.forEach((fullName, ranges) -> {
            Object value = valueAt(ranges, at);
            if (value == null) {
                return;
            }
            present[0] = true;
            if (fullName.startsWith(PropertyKind.COMPONENT.prefix())) {
                components.put(fullName.substring(PropertyKind.COMPONENT.prefix().length()), value);
                hasComponents[0] = true;
            } else if (fullName.startsWith(PropertyKind.WIRE.prefix())) {
                root.put(fullName.substring(PropertyKind.WIRE.prefix().length()), value);
            }
        });
        if (!present[0]) {
            return null;
        }
        if (hasComponents[0]) {
            root.putCompound("components", components.build());
        }
        return root.build();
    }

    // ---- Serialisation ----

    public static @NonNull PropertyStore read(@NonNull InputStream stream) throws IOException {
        NbtMap root;
        try (NBTInputStream input = NbtUtils.createGZIPReader(stream)) {
            root = (NbtMap) input.readTag();
        }
        int format = root.getInt("format", -1);
        if (format != FORMAT) {
            throw new IOException("Unsupported Paletter property format " + format + ", expected " + FORMAT);
        }
        List<String> names = List.copyOf(root.getList("versions", NbtType.STRING));
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            index.put(names.get(i), i);
        }
        Map<String, List<String>> blockOrder = new TreeMap<>();
        NbtMap order = root.getCompound("block_order", NbtMap.EMPTY);
        for (String version : order.keySet()) {
            blockOrder.put(version, List.copyOf(order.getList(version, NbtType.STRING)));
        }
        return new PropertyStore(names, readOwner(root.getCompound("items", NbtMap.EMPTY), index),
            readOwner(root.getCompound("blocks", NbtMap.EMPTY), index), Map.copyOf(blockOrder));
    }

    private static Map<String, Map<String, List<Range>>> readOwner(NbtMap owner, Map<String, Integer> index) throws IOException {
        Map<String, Map<String, List<Range>>> result = new TreeMap<>();
        for (Map.Entry<String, Object> entry : owner.entrySet()) {
            NbtMap properties = (NbtMap) entry.getValue();
            Map<String, List<Range>> read = new TreeMap<>();
            for (String name : properties.keySet()) {
                List<Range> ranges = new ArrayList<>();
                for (NbtMap range : properties.getList(name, NbtType.COMPOUND)) {
                    Integer from = index.get(range.getString("from"));
                    Integer to = index.get(range.getString("to"));
                    if (from == null || to == null) {
                        throw new IOException("Property " + entry.getKey() + "/" + name + " uses an unknown version");
                    }
                    ranges.add(new Range(from, to, range.get("value")));
                }
                read.put(name, List.copyOf(ranges));
            }
            result.put(entry.getKey(), read);
        }
        return result;
    }

    /** Writes the store. Everything is sorted, so the same store always produces the same bytes. */
    public void write(@NonNull OutputStream stream) throws IOException {
        NbtMapBuilder root = NbtMap.builder()
            .putInt("format", FORMAT)
            .putList("versions", NbtType.STRING, versions)
            .putCompound("items", writeOwner(items))
            .putCompound("blocks", writeOwner(blocks));
        if (!blockOrder.isEmpty()) {
            NbtMapBuilder order = NbtMap.builder();
            new TreeMap<>(blockOrder).forEach((version, identifiers) -> order.putList(version, NbtType.STRING, identifiers));
            root.putCompound("block_order", order.build());
        }
        try (NBTOutputStream output = NbtUtils.createGZIPWriter(stream)) {
            output.writeTag(root.build());
        }
    }

    private NbtMap writeOwner(Map<String, Map<String, List<Range>>> owner) {
        NbtMapBuilder result = NbtMap.builder();
        owner.forEach((identifier, properties) -> {
            NbtMapBuilder written = NbtMap.builder();
            properties.forEach((name, ranges) -> {
                List<NbtMap> list = new ArrayList<>(ranges.size());
                for (Range range : ranges) {
                    NbtMapBuilder entry = NbtMap.builder()
                        .putString("from", versions.get(range.from()))
                        .putString("to", versions.get(range.to()));
                    entry.put("value", range.value());
                    list.add(entry.build());
                }
                written.putList(name, NbtType.COMPOUND, list);
            });
            result.putCompound(identifier, written.build());
        });
        return result.build();
    }

    /** @return the number of stored ranges, a measure of how well the data deduplicates */
    public int rangeCount() {
        return count(items) + count(blocks);
    }

    private static int count(Map<String, Map<String, List<Range>>> owner) {
        return owner.values().stream().flatMap(m -> m.values().stream()).mapToInt(List::size).sum();
    }
}
