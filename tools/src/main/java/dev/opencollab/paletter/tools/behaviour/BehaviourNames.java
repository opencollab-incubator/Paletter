package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.behaviour.BlockShape;
import dev.opencollab.paletter.internal.behaviour.BehaviourStore;
import dev.opencollab.paletter.tools.behaviour.CellFit.Table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The names of the generated constants: a capture by its source and version ({@code CLOUDBURST_DATA_26_60}), a shape of one
 * box by its corners in pixels ({@code BOX_0_0_0_16_8_16}), any other shape and every table by the first block and key that
 * use it ({@code ACACIA_STAIRS_COLLISION_SHAPE}). The first user is the first in generation order (versions oldest first,
 * blocks by identifier, keys in order), so the names stay put while the data does.
 */
final class BehaviourNames {
    /** What a table holds: values, deciding captures, or the notes and disputes of a capture. */
    enum TableKind { VALUES, SOURCES, MARKS }

    record TableKey(TableKind kind, Table table) {
    }

    final Map<Integer, String> captures = new LinkedHashMap<>();
    final Map<String, String> versions = new LinkedHashMap<>();
    final Map<BlockShape, String> shapes = new LinkedHashMap<>();
    final Map<TableKey, String> tables = new LinkedHashMap<>();
    /** Who uses each table, for its Javadoc. */
    final Map<TableKey, List<String>> tableUsers = new LinkedHashMap<>();
    private final Set<String> used = new HashSet<>();

    BehaviourNames(BehaviourStore store) {
        Map<String, Integer> perSourceVersion = new LinkedHashMap<>();
        for (BehaviourStore.Capture capture : store.captures()) {
            String base = capture.source().name() + "_" + constant(capture.version().name());
            int n = perSourceVersion.merge(base, 1, Integer::sum);
            captures.put(capture.index(), unique(n == 1 ? base : base + "_" + n));
            versions.putIfAbsent(capture.version().name(), "V" + constant(capture.version().name()));
        }
    }

    /** Registers a shape; {@code user} is the block and key, in constant form, that names it if it has several boxes. */
    void shape(BlockShape shape, String user) {
        if (shape.equals(BlockShape.EMPTY) || shape.equals(BlockShape.FULL_CUBE) || shapes.containsKey(shape)) {
            return;
        }
        String name = shape.boxes().size() == 1 ? box(shape.boxes().getFirst()) : user;
        shapes.put(shape, unique(name));
    }

    /** Registers a table and a user of it. */
    void table(TableKind kind, Table table, String user, String description) {
        TableKey key = new TableKey(kind, table);
        if (!tables.containsKey(key)) {
            tables.put(key, unique(user + (kind == TableKind.SOURCES ? "_SOURCES" : "")));
        }
        tableUsers.computeIfAbsent(key, k -> new ArrayList<>()).add(description);
    }

    private static String box(BlockShape.Box box) {
        return "BOX_" + pixels(box.minX()) + "_" + pixels(box.minY()) + "_" + pixels(box.minZ()) + "_" + pixels(box.maxX())
            + "_" + pixels(box.maxY()) + "_" + pixels(box.maxZ());
    }

    private static String pixels(float coordinate) {
        float pixels = coordinate * 16;
        String text = pixels == (int) pixels ? Integer.toString((int) pixels) : Float.toString(pixels);
        return text.replace('.', 'P').replace('-', 'M');
    }

    private String unique(String name) {
        String candidate = name;
        for (int n = 2; !used.add(candidate); n++) {
            candidate = name + "_" + n;
        }
        return candidate;
    }

    /** @return {@code 26.60} as {@code 26_60}, {@code minecraft:acacia_stairs} as {@code ACACIA_STAIRS} */
    static String constant(String text) {
        String name = text.startsWith("minecraft:") ? text.substring("minecraft:".length()) : text;
        name = name.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT);
        return Character.isDigit(name.charAt(0)) && !text.contains(".") ? "_" + name : name;
    }

    /** @return {@code destroy_time} as {@code destroyTime} */
    static String camel(String name) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }
}
