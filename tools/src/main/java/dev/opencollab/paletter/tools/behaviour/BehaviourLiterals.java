package dev.opencollab.paletter.tools.behaviour;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockShape;
import dev.opencollab.paletter.tools.behaviour.CellFit.ByProperty;
import dev.opencollab.paletter.tools.behaviour.CellFit.Constant;
import dev.opencollab.paletter.tools.behaviour.CellFit.Fit;
import dev.opencollab.paletter.tools.behaviour.CellFit.Table;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Values, captures and cells as Java source: {@code 1.5f}, {@code BehaviourShapes.BOX_0_0_0_16_8_16},
 * {@code BehaviourCaptures.CLOUDBURST_DATA_26_60}, {@code BehaviourTables.ACACIA_STAIRS_COLLISION_SHAPE}, and the setter
 * calls made of them.
 */
final class BehaviourLiterals {
    static final ClassName BLOCK_SHAPE = ClassName.get(BlockShape.class);
    static final ClassName CAPTURES = ClassName.get(BehaviourCode.PACKAGE, "BehaviourCaptures");

    private final BehaviourNames names;
    /** The class each shape and table constant is in, filled when the constants are laid out. */
    final java.util.Map<String, ClassName> owners = new java.util.HashMap<>();

    BehaviourLiterals(BehaviourNames names) {
        this.names = names;
    }

    /** An int written as hexadecimal: a colour, RGBA. */
    record Hex(int value) {
        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "0x%08X", value);
        }
    }

    /** @return a value of a key as it is written: map colours as {@link Hex} */
    private static @Nullable Object written(BehaviourKey<?> key, @Nullable Object value) {
        return key.name().equals("map_color") && value instanceof Integer colour ? new Hex(colour) : value;
    }

    /** @return a value of a key, a property value or a note: a literal, or the constant of a shape */
    CodeBlock value(@Nullable Object value) {
        return switch (value) {
            case null -> CodeBlock.of("null");
            case Float f -> {
                if (f.isNaN() || f.isInfinite()) {
                    throw new IllegalArgumentException("No literal for " + f);
                }
                yield CodeBlock.of("$Lf", Float.toString(f));
            }
            case Integer i -> CodeBlock.of("$L", i);
            case Hex hex -> CodeBlock.of("$L", hex);
            case Boolean b -> CodeBlock.of("$L", b);
            case String s -> CodeBlock.of("$S", s);
            case BlockShape shape when shape.equals(BlockShape.EMPTY) -> CodeBlock.of("$T.EMPTY", BLOCK_SHAPE);
            case BlockShape shape when shape.equals(BlockShape.FULL_CUBE) -> CodeBlock.of("$T.FULL_CUBE", BLOCK_SHAPE);
            case BlockShape shape -> constant(names.shapes.get(shape));
            default -> throw new IllegalArgumentException("No literal for " + value.getClass());
        };
    }

    /** @return a capture by its index, or null */
    CodeBlock capture(@Nullable Integer capture) {
        // "$T.NAME" with the name in the format: what JavaPoet shortens to NAME under the static import
        return capture == null ? CodeBlock.of("null") : CodeBlock.of("$T." + names.captures.get(capture), CAPTURES);
    }

    /** @return a value (or capture, for {@code captures}) of a fit's cell: a literal, or a capture constant */
    CodeBlock cellValue(@Nullable Object value, boolean captures) {
        return captures ? capture((Integer) value) : value(value);
    }

    CodeBlock constant(String name) {
        return CodeBlock.of("$T." + name, owners.get(name));
    }

    /**
     * @param kind what the fit holds; a table is a shared constant
     * @return the setter call of a fit: {@code .destroyTime(1.5f)}, {@code .destroyTime("growth", 0, 0.0f, ...)},
     * {@code .destroyTime(TABLE)}, {@code .destroyTimeUnknown()} (for sources {@code ...Default()})
     */
    CodeBlock setter(BehaviourKey<?> key, @Nullable Fit fit, BehaviourNames.TableKind kind) {
        String name = BehaviourSetters.setter(key);
        boolean captures = kind == BehaviourNames.TableKind.SOURCES;
        return switch (fit) {
            case null -> CodeBlock.of(".$L$L()", name, captures ? "Default" : "Unknown");
            case Constant constant when constant.value() == null -> CodeBlock.of(".$L$L()", name, captures ? "Default" : "Unknown");
            case Constant constant -> CodeBlock.of(".$L($L)", name, cellValue(captures ? constant.value() : written(key, constant.value()), captures));
            case ByProperty map -> CodeBlock.of(".$L($L)", name, pairs(captures ? map
                : new ByProperty(map.property(), map.keys(), map.values().stream().map(v -> written(key, v)).toList()), captures));
            case Table table -> CodeBlock.of(".$L($L)", name, constant(names.tables.get(new BehaviourNames.TableKey(kind, table))));
        };
    }

    /** @return {@code "growth", 0, 0.0f, 1, 0.5f} */
    CodeBlock pairs(ByProperty map, boolean captures) {
        List<CodeBlock> parts = new ArrayList<>();
        parts.add(CodeBlock.of("$S", map.property()));
        for (int i = 0; i < map.keys().size(); i++) {
            parts.add(CodeBlock.of("$L, $L", value(map.keys().get(i)), cellValue(map.values().get(i), captures)));
        }
        // A pair per wrapping point: a long map breaks between pairs, not within one
        return CodeBlock.join(parts, ",$W");
    }

    /** @return a fit as an argument: a value, {@code Cell.map(...)} or a table constant */
    CodeBlock argument(Fit fit, BehaviourNames.TableKind kind) {
        boolean captures = kind == BehaviourNames.TableKind.SOURCES;
        return switch (fit) {
            case Constant constant -> cellValue(constant.value(), captures);
            case ByProperty map -> CodeBlock.of("$T.map($L)", BehaviourSetters.CELL, pairs(map, captures));
            case Table table -> constant(names.tables.get(new BehaviourNames.TableKey(kind, table)));
        };
    }

    /** @return how a value reads in a comment (made {@linkplain BehaviourComments#safe safe} there) */
    String comment(Object value) {
        if (value instanceof BlockShape shape) {
            return shape.equals(BlockShape.EMPTY) ? "EMPTY" : shape.equals(BlockShape.FULL_CUBE) ? "FULL_CUBE" : names.shapes.get(shape);
        }
        return value instanceof String text ? '"' + text + '"' : value.toString();
    }

    /** @return how a value reads in the report: shapes by their boxes in pixels */
    static String report(Object value) {
        if (value instanceof BlockShape shape) {
            return BehaviourNames.inPixels(shape);
        }
        return value instanceof String text ? '"' + text + '"' : value.toString();
    }

    /** @return {@code BlockBehaviour.DESTROY_TIME} */
    static CodeBlock key(BehaviourKey<?> key) {
        return CodeBlock.of("$T.$L", ClassName.get("dev.opencollab.paletter.behaviour",
            key.owner() == BehaviourKey.Owner.BLOCK ? "BlockBehaviour" : "ItemBehaviour"), key.name().toUpperCase(Locale.ROOT));
    }
}
