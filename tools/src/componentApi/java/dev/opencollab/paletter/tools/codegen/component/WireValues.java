package dev.opencollab.paletter.tools.codegen.component;

import com.palantir.javapoet.CodeBlock;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Scalar;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ScalarKind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Type;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;

import static dev.opencollab.paletter.tools.codegen.component.WireSource.NBT_MAP;
import static dev.opencollab.paletter.tools.codegen.component.WireSource.WIRE;

/**
 * The Java expressions of the encoders that turn an API value into a wire value: conversions by NBT type, map keys,
 * strings, and the typed literals of {@code mappings.json}.
 */
final class WireValues {
    private WireValues() {
    }

    /** A list field's value as a wire list of its NBT type. */
    static CodeBlock listValue(String id, Type element, String nbt, CodeBlock list) {
        return switch (element) {
            case EnumRef e -> CodeBlock.of("$T.strings($L.stream().map(x -> x.$N()).toList())", WIRE, list, e.type().accessor);
            case Scalar s when s.kind() == ScalarKind.STRING -> CodeBlock.of("$T.strings($L)", WIRE, list);
            case Scalar s when s.kind() == ScalarKind.INT -> CodeBlock.of("$T.ints($L)", WIRE, list);
            case Scalar s when s.kind() == ScalarKind.FLOAT -> CodeBlock.of("$T.floats($L)", WIRE, list);
            default -> throw new GenerationException(id + ": unsupported list element " + element + " (" + nbt + ")");
        };
    }

    /** A value as the key of a table rule. */
    static CodeBlock key(Type t, CodeBlock expr) {
        return switch (t) {
            case EnumRef e -> string(e, expr);
            case Scalar s when s.kind() == ScalarKind.STRING -> expr;
            case Scalar s when s.kind() == ScalarKind.BOOL -> CodeBlock.of("$T.valueOf($L)", String.class, expr);
            default -> CodeBlock.of("$T.key($L)", WIRE, expr);
        };
    }

    static CodeBlock stringOf(Type t, CodeBlock expr) {
        return t instanceof EnumRef e ? string(e, expr) : CodeBlock.of("$T.valueOf($L)", String.class, expr);
    }

    /** The add-on string of an enum value: {@code value()} of a generated value class, or the external enum's accessor. */
    static CodeBlock string(EnumRef e, CodeBlock expr) {
        return CodeBlock.of("$L.$N()", expr, e.type().accessor);
    }

    static CodeBlock numeric(Type t, CodeBlock expr) {
        return t instanceof Scalar s && s.kind() == ScalarKind.BOOL ? CodeBlock.of("($L ? 1 : 0)", expr) : CodeBlock.of("(double) $L", expr);
    }

    /**
     * A value as a leaf of an NBT type, for an identity rule.
     *
     * @param where the component and field, for the error
     */
    static CodeBlock convert(String where, Type t, CodeBlock expr, String nbt) {
        if (t instanceof EnumRef e) {
            if (!nbt.equals("string")) {
                throw new GenerationException(where + ": an enum written as " + nbt + " needs a table rule");
            }
            return string(e, expr);
        }
        if (t instanceof Scalar s) {
            return switch (s.kind()) {
                case STRING -> {
                    if (!nbt.equals("string")) {
                        throw new GenerationException(where + ": a string written as " + nbt);
                    }
                    yield expr;
                }
                case BOOL -> switch (nbt) {
                    case "byte" -> CodeBlock.of("$T.bool($L)", WIRE, expr);
                    case "string" -> CodeBlock.of("$T.valueOf($L)", String.class, expr);
                    default -> fromNumber(where, nbt, CodeBlock.of("($L ? 1 : 0)", expr));
                };
                case INT, FLOAT -> nbt.equals("string") ? CodeBlock.of("$T.valueOf($L)", String.class, expr) : fromNumber(where, nbt, expr);
            };
        }
        throw new GenerationException(where + ": an identity rule on " + t + " needs a transform");
    }

    /** A number as a leaf of a numeric NBT type. */
    static CodeBlock fromNumber(String where, String nbt, CodeBlock expr) {
        return switch (nbt) {
            case "byte" -> CodeBlock.of("$T.toByte($L)", WIRE, expr);
            case "short" -> CodeBlock.of("$T.toShort($L)", WIRE, expr);
            case "int" -> CodeBlock.of("$T.toInt($L)", WIRE, expr);
            case "long" -> CodeBlock.of("(long) ($L)", expr);
            case "float" -> CodeBlock.of("$T.toFloat($L)", WIRE, expr);
            case "double" -> CodeBlock.of("(double) ($L)", expr);
            default -> throw new GenerationException(where + ": a number written as " + nbt);
        };
    }

    /** A {@code value (type)} literal of mappings.json as Java. */
    static CodeBlock literal(String typed) {
        int at = typed.lastIndexOf(" (");
        if (at < 0 || !typed.endsWith(")")) {
            throw new GenerationException("not a typed wire value: " + typed);
        }
        String value = typed.substring(0, at);
        String type = typed.substring(at + 2, typed.length() - 1);
        return switch (type) {
            case "byte" -> CodeBlock.of("(byte) $L", Long.parseLong(value));
            case "short" -> CodeBlock.of("(short) $L", Long.parseLong(value));
            case "int" -> CodeBlock.of("$L", Long.parseLong(value));
            case "long" -> CodeBlock.of("$LL", Long.parseLong(value));
            case "float" -> CodeBlock.of("$L", Docs.floatLiteral(Double.parseDouble(value)));
            case "double" -> CodeBlock.of("$Ld", Double.parseDouble(value));
            case "string" -> CodeBlock.of("$S", value);
            case "compound" -> {
                if (!value.equals("{}")) {
                    throw new GenerationException("only empty compounds can be literals: " + typed);
                }
                yield CodeBlock.of("$T.EMPTY", NBT_MAP);
            }
            default -> {
                if (type.startsWith("list<") && value.equals("[]")) {
                    yield CodeBlock.of("$T.EMPTY_LIST", WIRE);
                }
                throw new GenerationException("unsupported wire literal: " + typed);
            }
        };
    }
}
