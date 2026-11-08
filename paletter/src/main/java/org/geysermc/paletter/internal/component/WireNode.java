package org.geysermc.paletter.internal.component;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A mutable NBT compound the component encoders write into by dotted path ({@code minecraft:durability.damage_chance.min}).
 * Keys never contain dots in the wire format, so a dot always separates two keys. Lists of compounds grow by
 * {@link #element}; everything else is a leaf value (boxed NBT number, string, {@link NbtMap}, {@link NbtList}).
 */
public final class WireNode {
    private final Map<String, Object> entries = new LinkedHashMap<>();

    public WireNode() {
    }

    /**
     * @return a node holding a copy of the compound (nested compounds and compound lists become nodes)
     */
    public static @NonNull WireNode of(@NonNull NbtMap map) {
        WireNode node = new WireNode();
        map.forEach((key, value) -> node.entries.put(key, mutable(value)));
        return node;
    }

    private static Object mutable(Object value) {
        if (value instanceof NbtMap map) {
            return of(map);
        }
        if (value instanceof NbtList<?> list && list.getType() == NbtType.COMPOUND) {
            List<WireNode> nodes = new ArrayList<>();
            list.forEach(e -> nodes.add(of((NbtMap) e)));
            return new Elements(nodes);
        }
        return value;
    }

    /** A list of compounds being built. */
    private record Elements(List<WireNode> nodes) {
    }

    /**
     * Sets a leaf, creating the compounds on the way.
     */
    public @NonNull WireNode put(@NonNull String path, @NonNull Object value) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            entries.put(path, value);
        } else {
            child(path.substring(0, dot)).put(path.substring(dot + 1), value);
        }
        return this;
    }

    /**
     * Removes a leaf (or compound) if present.
     */
    public @NonNull WireNode remove(@NonNull String path) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            entries.remove(path);
        } else if (entries.get(path.substring(0, dot)) instanceof WireNode node) {
            node.remove(path.substring(dot + 1));
        }
        return this;
    }

    /**
     * @return the compound at a path, created if missing (an existing leaf is replaced)
     */
    public @NonNull WireNode child(@NonNull String path) {
        int dot = path.indexOf('.');
        String key = dot < 0 ? path : path.substring(0, dot);
        Object existing = entries.get(key);
        WireNode node;
        if (existing instanceof WireNode n) {
            node = n;
        } else {
            node = new WireNode();
            entries.put(key, node);
        }
        return dot < 0 ? node : node.child(path.substring(dot + 1));
    }

    /**
     * Makes sure a list of compounds exists at the path (sent as an empty list if no element is added).
     */
    public @NonNull WireNode list(@NonNull String path) {
        elements(path);
        return this;
    }

    /**
     * Appends a compound to the list at a path.
     *
     * @return the new element
     */
    public @NonNull WireNode element(@NonNull String path) {
        WireNode node = new WireNode();
        elements(path).nodes.add(node);
        return node;
    }

    private Elements elements(String path) {
        int dot = path.lastIndexOf('.');
        WireNode parent = dot < 0 ? this : child(path.substring(0, dot));
        String key = dot < 0 ? path : path.substring(dot + 1);
        if (parent.entries.get(key) instanceof Elements elements) {
            return elements;
        }
        Elements elements = new Elements(new ArrayList<>());
        parent.entries.put(key, elements);
        return elements;
    }

    /**
     * @return the value at a path: a leaf, a {@link WireNode}, or null
     */
    public @Nullable Object get(@NonNull String path) {
        int dot = path.indexOf('.');
        Object value = entries.get(dot < 0 ? path : path.substring(0, dot));
        if (dot < 0) {
            return value instanceof Elements elements ? elements.nodes : value;
        }
        return value instanceof WireNode node ? node.get(path.substring(dot + 1)) : null;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public @NonNull NbtMap build() {
        NbtMapBuilder builder = NbtMap.builder();
        entries.forEach((key, value) -> builder.put(key, built(value)));
        return builder.build();
    }

    private static Object built(Object value) {
        if (value instanceof WireNode node) {
            return node.build();
        }
        if (value instanceof Elements elements) {
            if (elements.nodes.isEmpty()) {
                return Wire.EMPTY_LIST;
            }
            List<NbtMap> maps = new ArrayList<>(elements.nodes.size());
            elements.nodes.forEach(n -> maps.add(n.build()));
            return new NbtList<>(NbtType.COMPOUND, maps);
        }
        return value;
    }

    @Override
    public String toString() {
        return build().toString();
    }
}
