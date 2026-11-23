package dev.opencollab.paletter.tools.behaviour;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.TypeSpec;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BlockShape;
import dev.opencollab.paletter.tools.behaviour.BehaviourNames.TableKey;
import dev.opencollab.paletter.tools.behaviour.BehaviourNames.TableKind;
import dev.opencollab.paletter.tools.behaviour.CellFit.ByProperty;
import dev.opencollab.paletter.tools.behaviour.CellFit.Constant;
import dev.opencollab.paletter.tools.behaviour.CellFit.Fit;
import dev.opencollab.paletter.tools.behaviour.CellFit.Table;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shared constants of the generated data: every shape that is not {@code EMPTY} or {@code FULL_CUBE}, and every table
 * (a value of a block that depends on several properties), each once. Laid out in classes of at most {@link #WEIGHT}
 * array elements, so no static initializer comes near the JVM's limit of 64 KB per method.
 */
final class BehaviourConstants {
    /** The most floats or table values in one class: a few bytes of bytecode each. */
    static final int WEIGHT = 2_500;

    private BehaviourConstants() {
    }

    /** Names every shape and table the model uses, in generation order: the first user names a constant. */
    static void register(BehaviourModel model, BehaviourNames names) {
        for (BehaviourModel.Version version : model.versions) {
            for (BehaviourModel.Block block : version.blocks().values()) {
                for (int k = 0; k < block.fits().length; k++) {
                    String user = BehaviourNames.constant(block.id()) + "_" + BehaviourNames.constant(BehaviourModel.BLOCK_KEYS.get(k).name());
                    String description = block.id() + " " + BehaviourModel.BLOCK_KEYS.get(k).name();
                    fit(names, block.fits()[k], TableKind.VALUES, user, description);
                    if (block.sources()[k] != null) {
                        fit(names, block.sources()[k], TableKind.SOURCES, user, description + " (captures)");
                    }
                }
            }
        }
        for (BehaviourModel.Mark mark : model.marks) {
            String user = BehaviourNames.constant(mark.id()) + "_" + BehaviourNames.constant(mark.key().name()) + (mark.alternative() == null ? "_NOTE" : "_DISPUTE");
            fit(names, mark.value(), TableKind.MARKS, user, mark.id() + " " + mark.key().name() + (mark.alternative() == null ? " (note)" : " (dispute)"));
        }
        for (BehaviourKey<?> key : model.customDefaults().keySet()) {
            if (model.customDefaults().get(key).value() instanceof BlockShape shape) {
                names.shape(shape, "DEFAULT_" + BehaviourNames.constant(key.name()));
            }
        }
    }

    private static void fit(BehaviourNames names, Fit fit, TableKind kind, String user, String description) {
        List<Object> values = switch (fit) {
            case Constant constant -> java.util.Collections.singletonList(constant.value());
            case ByProperty map -> map.values();
            case Table table -> table.values();
        };
        for (Object value : values) {
            if (value instanceof BlockShape shape) {
                names.shape(shape, user);
            }
        }
        if (fit instanceof Table table) {
            names.table(kind, table, user, description);
        }
    }

    /** @return the classes of the shapes, and the class of each constant in {@code literals} */
    static List<TypeSpec> shapes(BehaviourNames names, BehaviourLiterals literals) {
        List<TypeSpec> types = new ArrayList<>();
        Layout layout = new Layout("BehaviourShapes", "The shapes of the generated behaviour data, each once.", types);
        for (Map.Entry<BlockShape, String> shape : names.shapes.entrySet()) {
            float[] coordinates = shape.getKey().coordinates();
            List<CodeBlock> floats = new ArrayList<>();
            for (float coordinate : coordinates) {
                floats.add(literals.value(coordinate));
            }
            layout.add(FieldSpec.builder(BehaviourLiterals.BLOCK_SHAPE, shape.getValue(), Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.of($L)", BehaviourLiterals.BLOCK_SHAPE, CodeBlock.join(floats, ", ")).build(), coordinates.length, literals);
        }
        layout.finish();
        return types;
    }

    /** @return the classes of the tables (after {@link #shapes}: a table may name a shape) */
    static List<TypeSpec> tables(BehaviourNames names, BehaviourLiterals literals) {
        List<TypeSpec> types = new ArrayList<>();
        Layout layout = new Layout("BehaviourTables", "The values of blocks that depend on several properties, each table once: "
            + "the value of each combination of the property values, the last property fastest.", types);
        for (Map.Entry<TableKey, String> entry : names.tables.entrySet()) {
            Table table = entry.getKey().table();
            List<CodeBlock> domains = new ArrayList<>();
            for (List<Object> domain : table.domains()) {
                domains.add(CodeBlock.of("{$L}", CodeBlock.join(domain.stream().map(literals::value).toList(), ", ")));
            }
            List<CodeBlock> values = table.values().stream().map(v -> literals.cellValue(v, entry.getKey().kind() == TableKind.SOURCES)).toList();
            List<String> users = names.tableUsers.get(entry.getKey());
            String doc = "Used by " + String.join(", ", users.stream().distinct().limit(6).toList())
                + (users.stream().distinct().count() > 6 ? " and " + (users.stream().distinct().count() - 6) + " more" : "") + ".\n";
            layout.add(FieldSpec.builder(BehaviourSetters.CELL, entry.getValue(), Modifier.STATIC, Modifier.FINAL).addJavadoc(doc)
                .initializer("$T.table(new String[] {$L}, new Object[][] {$L},$W$L)", BehaviourSetters.CELL,
                    CodeBlock.join(table.properties().stream().map(p -> CodeBlock.of("$S", p)).toList(), ", "),
                    CodeBlock.join(domains, ", "), CodeBlock.join(values, ",$W")).build(), values.size(), literals);
        }
        layout.finish();
        return types;
    }

    /** Fields into classes {@code Name}, {@code Name2}, ... of at most {@link #WEIGHT}. */
    private static final class Layout {
        private final String name;
        private final String doc;
        private final List<TypeSpec> types;
        private TypeSpec.Builder current;
        private int weight;

        Layout(String name, String doc, List<TypeSpec> types) {
            this.name = name;
            this.doc = doc;
            this.types = types;
        }

        void add(FieldSpec field, int fieldWeight, BehaviourLiterals literals) {
            if (current == null || weight + fieldWeight > WEIGHT) {
                finish();
                current = TypeSpec.classBuilder(className()).addModifiers(Modifier.FINAL).addJavadoc(doc + "\n")
                    .addMethod(com.palantir.javapoet.MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
                weight = 0;
            }
            current.addField(field);
            weight += fieldWeight;
            literals.owners.put(field.name(), ClassName.get(BehaviourCode.PACKAGE, className()));
        }

        private String className() {
            return types.isEmpty() ? name : name + "Part" + (types.size() + 1);
        }

        void finish() {
            if (current != null) {
                types.add(current.build());
                current = null;
            }
        }
    }
}
