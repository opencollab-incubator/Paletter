package org.geysermc.paletter.internal.behaviour;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.geysermc.paletter.annotation.Internal;
import org.geysermc.paletter.behaviour.BehaviourKey;
import org.geysermc.paletter.behaviour.BlockBehaviour;
import org.geysermc.paletter.behaviour.BlockShape;
import org.geysermc.paletter.behaviour.ItemBehaviour;
import org.geysermc.paletter.behaviour.Provenance;
import org.geysermc.paletter.data.DataVersion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The behaviour values of vanilla blocks and items of every version, each stored once. Immutable.
 * <p>
 * A <em>capture</em> is what one source said about one data version: the block states (by state hash) and items (by
 * identifier) it covers and, per key, their values. Captures are kept in application order: by version, and within a
 * version the weaker source first. A capture stores only the values that differ from what the captures before it
 * resolve to, so a value is stored once for all the versions it stays the same in.
 * <p>
 * The value of a key for an id in version {@code v}: the last capture of a version up to {@code v} that covers the id
 * decides, with the value it stored, else the value it confirmed (the one before it). That capture is the provenance;
 * a version without such a capture of its own inherits the value, visibly ({@link Provenance#inherited()}).
 * <p>
 * The bundled form is {@code behaviour.nbt}, gzipped big endian NBT:
 * <pre>
 * format: int                       {@value #FORMAT}
 * versions: [string], protocols: int[]   oldest first
 * captures: [{version, source, reference, blocks_added: int[], blocks_removed: int[], items_added: [string], items_removed: [string]}]
 *                                   coverage as a change to the previous capture of the same source
 * shapes: [[float]]                 every distinct shape once, six floats per box
 * blocks: {key: [{capture: int, mask?: byte[], values}]}   mask: a bit per id the capture covers (sorted), set if
 *                                   the column has a value for it; no mask: every covered id
 * items: {key: [{capture: int, mask?: byte[], values}]}
 * </pre>
 * Values by key type: float list, int array, byte array (booleans), string list, int array of shape indexes.
 */
@Internal
public final class BehaviourStore {
    public static final int FORMAT = 1;
    private static final BehaviourStore EMPTY = new BehaviourStore(List.of(), new int[0], List.of(), Map.of(), Map.of());

    /**
     * One source's capture of one version.
     *
     * @param order the block states it covers in storage order: the order of the previous capture of the same source
     *              without the states it lost, then the new ones in the order the importer gave (palette order, so the
     *              states of a block sit together and their values compress well)
     * @param blocks the same states, sorted, for lookups
     */
    public record Capture(@NonNull String version, int versionIndex, Provenance.@NonNull Source source,
                          @NonNull String reference, int @NonNull [] order, int @NonNull [] blocks, @NonNull Set<String> items) {
        Capture(String version, int versionIndex, Provenance.Source source, String reference, int[] order, Set<String> items) {
            this(version, versionIndex, source, reference, order, Arrays.stream(order).sorted().toArray(), items);
        }

        boolean covers(int block) {
            return Arrays.binarySearch(blocks, block) >= 0;
        }
    }

    /** @return the storage order of a capture following {@code before} (null: the first of its source) */
    private static int[] order(@Nullable Capture before, int[] removed, int[] added) {
        Set<Integer> gone = boxed(removed);
        java.util.stream.IntStream kept = before == null ? java.util.stream.IntStream.empty()
            : Arrays.stream(before.order()).filter(id -> !gone.contains(id));
        return java.util.stream.IntStream.concat(kept, Arrays.stream(added)).toArray();
    }

    /** The values a capture stored for one key: ids sorted, {@code values[i]} the value of {@code ids[i]}. */
    private record Column<I>(int capture, I ids, Object[] values) {
    }

    /** A value and the capture that decided it. */
    public record Resolved(@NonNull Object value, @NonNull Capture capture) {
    }

    private final List<String> versions;
    private final int[] protocols;
    private final List<Capture> captures;
    private final Map<BehaviourKey<?>, List<Column<int[]>>> blocks;
    private final Map<BehaviourKey<?>, List<Column<String[]>>> items;

    private BehaviourStore(List<String> versions, int[] protocols, List<Capture> captures,
                           Map<BehaviourKey<?>, List<Column<int[]>>> blocks, Map<BehaviourKey<?>, List<Column<String[]>>> items) {
        this.versions = versions;
        this.protocols = protocols;
        this.captures = captures;
        this.blocks = blocks;
        this.items = items;
    }

    public static @NonNull BehaviourStore empty() {
        return EMPTY;
    }

    public @NonNull List<String> versions() {
        return versions;
    }

    public @NonNull List<Capture> captures() {
        return captures;
    }

    /** @return the keys with data */
    public @NonNull Set<BehaviourKey<?>> keys() {
        Set<BehaviourKey<?>> keys = new java.util.LinkedHashSet<>(blocks.keySet());
        keys.addAll(items.keySet());
        return keys;
    }

    // ---- Lookup ----

    /** @return the index of the newest stored version at or before {@code version}, or -1 */
    private int versionIndex(DataVersion version) {
        int index = versions.indexOf(version.name());
        if (index >= 0) {
            return index;
        }
        for (int i = protocols.length - 1; i >= 0; i--) {
            if (protocols[i] <= version.protocolVersion()) {
                return i;
            }
        }
        return -1;
    }

    /** @return the value of a block state (by state hash) in a version, or null if no capture covers it */
    public @Nullable Resolved block(@NonNull BehaviourKey<?> key, int stateHash, @NonNull DataVersion version) {
        List<Column<int[]>> columns = blocks.get(key);
        int at = versionIndex(version);
        if (columns == null || at < 0) {
            return null;
        }
        for (int i = columns.size() - 1; i >= 0; i--) {
            Capture capture = captures.get(columns.get(i).capture());
            if (capture.versionIndex() <= at && capture.covers(stateHash)) {
                for (int j = i; j >= 0; j--) {
                    Column<int[]> column = columns.get(j);
                    int found = Arrays.binarySearch(column.ids(), stateHash);
                    if (found >= 0) {
                        return new Resolved(column.values()[found], capture);
                    }
                }
                return null;
            }
        }
        return null;
    }

    /** @return the value of an item in a version, or null if no capture covers it */
    public @Nullable Resolved item(@NonNull BehaviourKey<?> key, @NonNull String identifier, @NonNull DataVersion version) {
        List<Column<String[]>> columns = items.get(key);
        int at = versionIndex(version);
        if (columns == null || at < 0) {
            return null;
        }
        for (int i = columns.size() - 1; i >= 0; i--) {
            Capture capture = captures.get(columns.get(i).capture());
            if (capture.versionIndex() <= at && capture.items().contains(identifier)) {
                for (int j = i; j >= 0; j--) {
                    Column<String[]> column = columns.get(j);
                    int found = Arrays.binarySearch(column.ids(), identifier);
                    if (found >= 0) {
                        return new Resolved(column.values()[found], capture);
                    }
                }
                return null;
            }
        }
        return null;
    }

    // ---- Building ----

    /**
     * Collects captures in application order and stores each key's values as changes. Used by the importer.
     */
    public static final class Builder {
        private final List<String> names = new ArrayList<>();
        private final int[] protocols;
        private final List<Capture> captures = new ArrayList<>();
        private final Map<BehaviourKey<?>, List<Column<int[]>>> blocks = new LinkedHashMap<>();
        private final Map<BehaviourKey<?>, List<Column<String[]>>> items = new LinkedHashMap<>();
        /** The value each id resolves to after the captures added so far, per key. */
        private final Map<BehaviourKey<?>, Map<Object, Object>> current = new HashMap<>();

        public Builder(@NonNull List<DataVersion> versions) {
            List<DataVersion> sorted = versions.stream().sorted().toList();
            sorted.forEach(version -> names.add(version.name()));
            protocols = sorted.stream().mapToInt(DataVersion::protocolVersion).toArray();
        }

        /**
         * Adds a capture after the ones added before; its version must not be older than theirs.
         *
         * @return the capture's index, for {@link #blocks} and {@link #items}
         */
        public int capture(@NonNull DataVersion version, Provenance.@NonNull Source source, @NonNull String reference,
                           @NonNull Collection<Integer> blocks, @NonNull Collection<String> items) {
            int index = names.indexOf(version.name());
            if (index < 0) {
                throw new IllegalArgumentException("Version " + version + " is not part of the store");
            }
            if (!captures.isEmpty() && captures.getLast().versionIndex() > index) {
                throw new IllegalArgumentException("Captures must be added oldest version first: " + version);
            }
            Capture before = null;
            for (Capture capture : captures) {
                before = capture.source() == source ? capture : before;
            }
            Set<Integer> now = new java.util.LinkedHashSet<>(blocks);
            Set<Integer> then = before == null ? Set.of() : boxed(before.blocks());
            int[] added = now.stream().filter(id -> !then.contains(id)).mapToInt(Integer::intValue).toArray();
            captures.add(new Capture(version.name(), index, source, reference, order(before, sortedDifference(then, now), added),
                Set.copyOf(items)));
            return captures.size() - 1;
        }

        /**
         * @param values the value of every block state (by state hash) the capture covers for this key
         */
        public <T> @NonNull Builder blocks(@NonNull BehaviourKey<T> key, int capture, @NonNull Map<Integer, T> values) {
            check(key, BehaviourKey.Owner.BLOCK, capture, values.keySet(), id -> captures.get(capture).covers((Integer) id));
            Map<Object, Object> changed = changes(key, values);
            int[] ids = changed.keySet().stream().mapToInt(id -> (Integer) id).sorted().toArray();
            blocks.computeIfAbsent(key, k -> new ArrayList<>()).add(new Column<>(capture, ids, valuesOf(ids.length, i -> changed.get(ids[i]))));
            return this;
        }

        /**
         * @param values the value of every item the capture covers for this key
         */
        public <T> @NonNull Builder items(@NonNull BehaviourKey<T> key, int capture, @NonNull Map<String, T> values) {
            check(key, BehaviourKey.Owner.ITEM, capture, values.keySet(), id -> captures.get(capture).items().contains(id));
            Map<Object, Object> changed = changes(key, values);
            String[] ids = changed.keySet().stream().map(String.class::cast).sorted().toArray(String[]::new);
            items.computeIfAbsent(key, k -> new ArrayList<>()).add(new Column<>(capture, ids, valuesOf(ids.length, i -> changed.get(ids[i]))));
            return this;
        }

        private void check(BehaviourKey<?> key, BehaviourKey.Owner owner, int capture, Set<?> ids, java.util.function.Predicate<Object> covered) {
            if (key.owner() != owner) {
                throw new IllegalArgumentException(key + " is not a " + owner + " key");
            }
            List<? extends Column<?>> columns = owner == BehaviourKey.Owner.BLOCK ? blocks.get(key) : items.get(key);
            if (columns != null && columns.getLast().capture() >= capture) {
                throw new IllegalArgumentException(key + ": values must be added in capture order");
            }
            for (Object id : ids) {
                if (!covered.test(id)) {
                    throw new IllegalArgumentException(key + ": " + id + " is not covered by capture " + capture);
                }
            }
        }

        private Map<Object, Object> changes(BehaviourKey<?> key, Map<?, ?> values) {
            Map<Object, Object> resolved = current.computeIfAbsent(key, k -> new HashMap<>());
            Map<Object, Object> changed = new HashMap<>();
            values.forEach((id, value) -> {
                if (!key.type().isInstance(value)) {
                    throw new IllegalArgumentException(key + ": " + id + " has a " + value.getClass().getSimpleName());
                }
                if (!Objects.equals(resolved.put(id, value), value)) {
                    changed.put(id, value);
                }
            });
            return changed;
        }

        private static Object[] valuesOf(int size, java.util.function.IntFunction<Object> value) {
            Object[] values = new Object[size];
            for (int i = 0; i < size; i++) {
                values[i] = value.apply(i);
            }
            return values;
        }

        public @NonNull BehaviourStore build() {
            return new BehaviourStore(List.copyOf(names), protocols.clone(), List.copyOf(captures), copy(blocks), copy(items));
        }

        private static <I> Map<BehaviourKey<?>, List<Column<I>>> copy(Map<BehaviourKey<?>, List<Column<I>>> map) {
            Map<BehaviourKey<?>, List<Column<I>>> copy = new LinkedHashMap<>();
            map.forEach((key, columns) -> copy.put(key, List.copyOf(columns)));
            return copy;
        }
    }

    // ---- Serialisation ----

    public static @NonNull BehaviourStore read(@NonNull InputStream stream) throws IOException {
        NbtMap root;
        try (NBTInputStream input = NbtUtils.createGZIPReader(stream)) {
            root = (NbtMap) input.readTag();
        }
        int format = root.getInt("format", -1);
        if (format != FORMAT) {
            throw new IOException("Unsupported Paletter behaviour format " + format + ", expected " + FORMAT);
        }
        List<String> versions = List.copyOf(root.getList("versions", NbtType.STRING));
        List<Capture> captures = new ArrayList<>();
        Map<String, Capture> previous = new HashMap<>();
        for (NbtMap entry : root.getList("captures", NbtType.COMPOUND)) {
            Provenance.Source source = Provenance.Source.valueOf(entry.getString("source"));
            Capture before = previous.get(source.name());
            Set<String> items = new TreeSet<>();
            if (before != null) {
                items.addAll(before.items());
            }
            items.removeAll(entry.getList("items_removed", NbtType.STRING));
            items.addAll(entry.getList("items_added", NbtType.STRING));
            String version = entry.getString("version");
            Capture capture = new Capture(version, versions.indexOf(version), source, entry.getString("reference"),
                order(before, entry.getIntArray("blocks_removed"), entry.getIntArray("blocks_added")), Set.copyOf(items));
            captures.add(capture);
            previous.put(source.name(), capture);
        }
        List<BlockShape> shapes = new ArrayList<>();
        for (NbtList<?> shape : root.getList("shapes", NbtType.LIST)) {
            float[] coordinates = new float[shape.size()];
            for (int i = 0; i < coordinates.length; i++) {
                coordinates[i] = (Float) shape.get(i);
            }
            shapes.add(BlockShape.of(coordinates));
        }
        Map<BehaviourKey<?>, List<Column<int[]>>> blocks = new LinkedHashMap<>();
        NbtMap blockKeys = root.getCompound("blocks");
        for (String name : blockKeys.keySet()) {
            BehaviourKey<?> key = BlockBehaviour.key(name);
            if (key == null) {
                continue; // a key of a newer library
            }
            Map<Object, Object> pool = new HashMap<>();
            List<Column<int[]>> columns = new ArrayList<>();
            for (NbtMap column : blockKeys.getList(name, NbtType.COMPOUND)) {
                int capture = column.getInt("capture");
                int[] covered = captures.get(capture).order();
                int[] ids = column.containsKey("mask") ? unmask(column.getByteArray("mask"), covered.length)
                    .map(i -> covered[i]).toArray() : covered;
                Object[] values = readValues(key, column, ids.length, shapes, pool);
                // stored in coverage order, looked up sorted
                Integer[] byId = new Integer[ids.length];
                Arrays.setAll(byId, i -> i);
                Arrays.sort(byId, java.util.Comparator.comparingInt(i -> ids[i]));
                columns.add(new Column<>(capture, Arrays.stream(byId).mapToInt(i -> ids[i]).toArray(),
                    Arrays.stream(byId).map(i -> values[i]).toArray()));
            }
            blocks.put(key, List.copyOf(columns));
        }
        Map<BehaviourKey<?>, List<Column<String[]>>> items = new LinkedHashMap<>();
        NbtMap itemKeys = root.getCompound("items");
        for (String name : itemKeys.keySet()) {
            BehaviourKey<?> key = ItemBehaviour.key(name);
            if (key == null) {
                continue;
            }
            Map<Object, Object> pool = new HashMap<>();
            List<Column<String[]>> columns = new ArrayList<>();
            for (NbtMap column : itemKeys.getList(name, NbtType.COMPOUND)) {
                int capture = column.getInt("capture");
                String[] covered = captures.get(capture).items().stream().sorted().toArray(String[]::new);
                String[] ids = column.containsKey("mask") ? unmask(column.getByteArray("mask"), covered.length)
                    .mapToObj(i -> covered[i]).toArray(String[]::new) : covered;
                columns.add(new Column<>(capture, ids, readValues(key, column, ids.length, shapes, pool)));
            }
            items.put(key, List.copyOf(columns));
        }
        return new BehaviourStore(versions, root.getIntArray("protocols"), List.copyOf(captures), blocks, items);
    }

    private static Object[] readValues(BehaviourKey<?> key, NbtMap column, int size, List<BlockShape> shapes,
                                       Map<Object, Object> pool) throws IOException {
        Class<?> type = key.type();
        java.util.function.IntFunction<Object> read;
        if (type == Float.class) {
            List<Float> list = column.getList("values", NbtType.FLOAT);
            read = list::get;
        } else if (type == Integer.class) {
            int[] array = column.getIntArray("values");
            read = i -> array[i];
        } else if (type == Boolean.class) {
            byte[] array = column.getByteArray("values");
            read = i -> array[i] != 0;
        } else if (type == String.class) {
            List<String> list = column.getList("values", NbtType.STRING);
            read = list::get;
        } else if (type == BlockShape.class) {
            int[] array = column.getIntArray("values");
            read = i -> shapes.get(array[i]);
        } else {
            throw new IOException("Unsupported value type of " + key);
        }
        Object[] values = new Object[size];
        for (int i = 0; i < size; i++) {
            values[i] = pool.computeIfAbsent(read.apply(i), v -> v);
        }
        return values;
    }

    /** Writes the store. Keys, captures and ids keep their order, so the same store always produces the same bytes. */
    public void write(@NonNull OutputStream stream) throws IOException {
        List<NbtMap> captureList = new ArrayList<>();
        Map<Provenance.Source, Capture> previous = new HashMap<>();
        for (Capture capture : captures) {
            Capture before = previous.put(capture.source(), capture);
            Set<Integer> now = boxed(capture.blocks());
            Set<Integer> then = before == null ? Set.of() : boxed(before.blocks());
            Set<String> thenItems = before == null ? Set.of() : before.items();
            captureList.add(NbtMap.builder()
                .putString("version", capture.version())
                .putString("source", capture.source().name())
                .putString("reference", capture.reference())
                .putIntArray("blocks_added", Arrays.stream(capture.order()).filter(id -> !then.contains(id)).toArray())
                .putIntArray("blocks_removed", sortedDifference(then, now))
                .putList("items_added", NbtType.STRING, new ArrayList<>(new TreeSet<>(difference(capture.items(), thenItems))))
                .putList("items_removed", NbtType.STRING, new ArrayList<>(new TreeSet<>(difference(thenItems, capture.items()))))
                .build());
        }
        Map<BlockShape, Integer> shapes = new LinkedHashMap<>();
        shapes.put(BlockShape.EMPTY, 0);
        NbtMapBuilder blockKeys = NbtMap.builder();
        blocks.forEach((key, columns) -> {
            List<NbtMap> list = new ArrayList<>();
            for (Column<int[]> column : columns) {
                NbtMapBuilder entry = NbtMap.builder().putInt("capture", column.capture());
                int[] covered = captures.get(column.capture()).order();
                if (column.ids().length != covered.length) {
                    entry.putByteArray("mask", mask(covered.length, i -> Arrays.binarySearch(column.ids(), covered[i]) >= 0));
                }
                Object[] values = Arrays.stream(covered).map(id -> Arrays.binarySearch(column.ids(), id)).filter(i -> i >= 0)
                    .mapToObj(i -> column.values()[i]).toArray();
                list.add(writeValues(key, entry, values, shapes).build());
            }
            blockKeys.putList(key.name(), NbtType.COMPOUND, list);
        });
        NbtMapBuilder itemKeys = NbtMap.builder();
        items.forEach((key, columns) -> {
            List<NbtMap> list = new ArrayList<>();
            for (Column<String[]> column : columns) {
                NbtMapBuilder entry = NbtMap.builder().putInt("capture", column.capture());
                String[] covered = captures.get(column.capture()).items().stream().sorted().toArray(String[]::new);
                if (column.ids().length != covered.length) {
                    entry.putByteArray("mask", mask(covered.length, i -> Arrays.binarySearch(column.ids(), covered[i]) >= 0));
                }
                list.add(writeValues(key, entry, column.values(), shapes).build());
            }
            itemKeys.putList(key.name(), NbtType.COMPOUND, list);
        });
        @SuppressWarnings("rawtypes")
        List<NbtList> shapeList = new ArrayList<>();
        for (BlockShape shape : shapes.keySet()) {
            float[] coordinates = shape.coordinates();
            List<Float> boxed = new ArrayList<>(coordinates.length);
            for (float coordinate : coordinates) {
                boxed.add(coordinate);
            }
            shapeList.add(new NbtList<>(NbtType.FLOAT, boxed));
        }
        NbtMap root = NbtMap.builder()
            .putInt("format", FORMAT)
            .putList("versions", NbtType.STRING, versions)
            .putIntArray("protocols", protocols)
            .putList("captures", NbtType.COMPOUND, captureList)
            .putList("shapes", NbtType.LIST, shapeList)
            .putCompound("blocks", blockKeys.build())
            .putCompound("items", itemKeys.build())
            .build();
        try (NBTOutputStream output = NbtUtils.createGZIPWriter(stream)) {
            output.writeTag(root);
        }
    }

    private static NbtMapBuilder writeValues(BehaviourKey<?> key, NbtMapBuilder entry, Object[] values, Map<BlockShape, Integer> shapes) {
        Class<?> type = key.type();
        if (type == Float.class) {
            entry.putList("values", NbtType.FLOAT, Arrays.stream(values).map(Float.class::cast).toList());
        } else if (type == Integer.class) {
            entry.putIntArray("values", Arrays.stream(values).mapToInt(v -> (Integer) v).toArray());
        } else if (type == Boolean.class) {
            byte[] bytes = new byte[values.length];
            for (int i = 0; i < values.length; i++) {
                bytes[i] = (byte) ((Boolean) values[i] ? 1 : 0);
            }
            entry.putByteArray("values", bytes);
        } else if (type == String.class) {
            entry.putList("values", NbtType.STRING, Arrays.stream(values).map(String.class::cast).toList());
        } else if (type == BlockShape.class) {
            entry.putIntArray("values", Arrays.stream(values).mapToInt(v -> shapes.computeIfAbsent((BlockShape) v, s -> shapes.size())).toArray());
        } else {
            throw new IllegalArgumentException("Unsupported value type of " + key);
        }
        return entry;
    }

    /** A bit per covered id, in coverage order: set if the column stores a value for it. */
    private static byte[] mask(int size, java.util.function.IntPredicate stored) {
        byte[] mask = new byte[(size + 7) / 8];
        for (int i = 0; i < size; i++) {
            if (stored.test(i)) {
                mask[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        return mask;
    }

    private static java.util.stream.IntStream unmask(byte[] mask, int size) {
        return java.util.stream.IntStream.range(0, size).filter(i -> (mask[i >> 3] & (1 << (i & 7))) != 0);
    }

    private static Set<Integer> boxed(int[] values) {
        Set<Integer> set = new java.util.HashSet<>();
        for (int value : values) {
            set.add(value);
        }
        return set;
    }

    private static int[] sortedDifference(Set<Integer> a, Set<Integer> b) {
        return a.stream().filter(v -> !b.contains(v)).mapToInt(Integer::intValue).sorted().toArray();
    }

    private static <T> Set<T> difference(Set<T> a, Set<T> b) {
        Set<T> result = new java.util.HashSet<>(a);
        result.removeAll(b);
        return result;
    }

    /** @return a readable summary: captures, keys and stored values */
    @Override
    public String toString() {
        Map<String, Integer> counts = new TreeMap<>();
        blocks.forEach((key, columns) -> counts.put(key.toString(), columns.stream().mapToInt(c -> c.ids().length).sum()));
        items.forEach((key, columns) -> counts.put(key.toString(), columns.stream().mapToInt(c -> c.ids().length).sum()));
        return "BehaviourStore" + versions + " captures=" + captures.size() + " values=" + counts;
    }
}
