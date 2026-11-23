package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.behaviour.BlockShape;
import dev.opencollab.paletter.tools.behaviour.CellFit.Table;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names of the generated constants, each kind with its own prefix:
 * <ul>
 *     <li>a capture by its source, version and the part of the source it is ({@code SCRIPT_API_26_30_MAP_COLOR}: the
 *     group of keys of that Script API capture, {@code BDS_PROBE_26_60_CLIMBING});</li>
 *     <li>a shape of one box whose corners are whole pixels by its corners ({@code BOX_0_0_0_16_8_16}), any other shape by
 *     the first block and key that use it ({@code SHAPE_ALLIUM_OUTLINE_SHAPE}, its boxes in the Javadoc);</li>
 *     <li>a table by what it holds ({@code TABLE_}, {@code SOURCES_} or {@code MARKS_}), its properties and a short hash
 *     of its values ({@code TABLE_FACING_DIRECTION_OPEN_BIT_3F2A1C}), so the name does not depend on who uses it.</li>
 * </ul>
 * The first user is the first in generation order (versions oldest first, blocks by identifier, keys in order).
 */
final class BehaviourNames {
    private static final Pattern PART = Pattern.compile("\\(([^()]*)\\)$");

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
        for (BehaviourStore.Capture capture : store.captures()) {
            String base = capture.source().name() + "_" + constant(capture.version().name());
            Matcher part = PART.matcher(capture.reference());
            String name = part.find() ? base + "_" + constant(part.group(1).split(", ")[0]) : base;
            captures.put(capture.index(), unique(name));
            versions.putIfAbsent(capture.version().name(), "V" + constant(capture.version().name()));
        }
    }

    /** Registers a shape; {@code user} is the block and key, in constant form, that names it if it is no whole-pixel box. */
    void shape(BlockShape shape, String user) {
        if (shape.equals(BlockShape.EMPTY) || shape.equals(BlockShape.FULL_CUBE) || shapes.containsKey(shape)) {
            return;
        }
        boolean pixels = shape.boxes().size() == 1 && wholePixels(shape.coordinates());
        shapes.put(shape, unique(pixels ? box(shape.boxes().getFirst()) : "SHAPE_" + user));
    }

    /** Registers a table and a user of it. */
    void table(TableKind kind, Table table, String description) {
        TableKey key = new TableKey(kind, table);
        if (!tables.containsKey(key)) {
            String prefix = switch (kind) {
                case VALUES -> "TABLE_";
                case SOURCES -> "SOURCES_";
                case MARKS -> "MARKS_";
            };
            String hash = String.format(Locale.ROOT, "%06X", table.toString().hashCode() & 0xFFFFFF);
            tables.put(key, unique(prefix + constant(String.join("_", table.properties()).replace("minecraft:", "")) + "_" + hash));
        }
        tableUsers.computeIfAbsent(key, k -> new ArrayList<>()).add(description);
    }

    private static boolean wholePixels(float[] coordinates) {
        for (float coordinate : coordinates) {
            float pixels = coordinate * 16;
            if (pixels != Math.round(pixels)) {
                return false;
            }
        }
        return true;
    }

    private static String box(BlockShape.Box box) {
        return "BOX_" + pixels(box.minX()) + "_" + pixels(box.minY()) + "_" + pixels(box.minZ()) + "_" + pixels(box.maxX())
            + "_" + pixels(box.maxY()) + "_" + pixels(box.maxZ());
    }

    private static String pixels(float coordinate) {
        int pixels = Math.round(coordinate * 16);
        return pixels < 0 ? "M" + -pixels : Integer.toString(pixels);
    }

    /** @return a shape's boxes in pixels, for its Javadoc: {@code [0, 0, 0, 16, 8, 16]} */
    static String inPixels(BlockShape shape) {
        StringBuilder out = new StringBuilder();
        for (BlockShape.Box box : shape.boxes()) {
            out.append(out.isEmpty() ? "" : " ").append('[');
            float[] corners = {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()};
            for (int i = 0; i < corners.length; i++) {
                float pixels = corners[i] * 16;
                out.append(i == 0 ? "" : ", ").append(pixels == Math.round(pixels) ? Integer.toString(Math.round(pixels)) : Float.toString(pixels));
            }
            out.append(']');
        }
        return out.isEmpty() ? "[]" : out.toString();
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
        return !name.isEmpty() && Character.isDigit(name.charAt(0)) && !text.contains(".") ? "_" + name : name;
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
