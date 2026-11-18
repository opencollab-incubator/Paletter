package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;
import com.palantir.javapoet.WildcardTypeName;
import dev.opencollab.paletter.tools.codegen.component.BehaviourKeys.Hand;
import dev.opencollab.paletter.tools.codegen.component.BehaviourKeys.Key;
import dev.opencollab.paletter.tools.codegen.component.BehaviourKeys.Leaf;
import dev.opencollab.paletter.tools.codegen.component.BehaviourKeys.Owner;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.model.rule.Rule;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the behaviour keys of {@link BehaviourKeys}: the public constants ({@code BlockBehaviour}, {@code ItemBehaviour})
 * and the internal {@code WireRules}, which read a key's value from the component data of a block state or item.
 * <p>
 * A {@link Leaf} key is read from its wire leaf by the rule {@code mappings.json} inferred from the add-on field onto
 * that leaf, in every protocol window (the windows must agree: the reader has no protocol). Generation fails when the
 * field has no rule onto the leaf, so a renamed or moved leaf is caught here and not read as null at runtime.
 * <ul>
 *     <li>A leaf in {@code item_properties} is the item's engine property as BDS sends it (BDS fills it from the
 *     component, e.g. {@code use_duration} seconds as ticks): read as sent.</li>
 *     <li>Any other leaf holds the add-on value under the rule; the rule is inverted to read the add-on value back
 *     ({@code identity}; {@code linear}: (wire - offset) / scale). Other kinds fail generation.</li>
 *     <li>Where the newest behaviour probe capture ({@code data/bds/<build>/behaviour-probe/capture.json}) measured the
 *     engine value against the component value ({@code <key>.components}: pairs of {@code component} and {@code engine}),
 *     the linear relation fitted to the pairs converts the add-on value to engine units (friction: 1 - value). A relation
 *     that does not fit every pair within {@link #PROBE_TOLERANCE} fails generation.</li>
 * </ul>
 * A {@link Hand} key calls its method of the hand-written {@code ShapeRules}.
 */
final class BehaviourSource {
    static final String HEADER = Main.GENERATOR + " from the BehaviourKeys table (tools), data/model/mappings.json\n"
        + "and the newest behaviour probe capture. Do not edit; see docs/data-pipeline.md.";
    static final String API_PKG = "dev.opencollab.paletter.behaviour";
    static final String INTERNAL_PKG = "dev.opencollab.paletter.internal.behaviour";
    /** The probe measures to 3 decimals and checks the friction it measured within this. */
    static final double PROBE_TOLERANCE = 0.002;

    private static final ClassName BEHAVIOUR_KEY = ClassName.get(API_PKG, "BehaviourKey");
    private static final ClassName PROPERTIES = ClassName.get("dev.opencollab.paletter.property", "Properties");
    private static final ClassName NBT_MAP = ClassName.get("org.cloudburstmc.nbt", "NbtMap");
    private static final ClassName SHAPE_RULES = ClassName.get(INTERNAL_PKG, "ShapeRules");
    private static final ClassName INTERNAL = ClassName.get("dev.opencollab.paletter.annotation", "Internal");
    private static final TypeName ANY_KEY = ParameterizedTypeName.get(BEHAVIOUR_KEY, WildcardTypeName.subtypeOf(Object.class));

    /** A probe-measured relation: engine = scale * component + offset. */
    record Relation(double scale, double offset, String reference) {
    }

    private BehaviourSource() {
    }

    /**
     * @param mappings {@code data/model/mappings.json}
     * @param probe the newest {@code behaviour-probe/capture.json}, or null if there is none
     * @param probeReference where {@code probe} was read from, for the generated comments
     * @return generated sources by path relative to the source root
     */
    static Map<String, String> generate(List<Key> keys, JsonObject mappings, JsonObject probe, String probeReference) {
        return generate(keys, mappings, probe, probeReference, null);
    }

    /**
     * @param evidence what the newest BDS captures have; null skips the check of the keys' sources (tests of the writers)
     * @throws GenerationException if a key's source is not in its capture, or a property of the behaviour dump has neither
     * a key nor an entry in {@link BehaviourKeys#IGNORED}
     */
    static Map<String, String> generate(List<Key> keys, JsonObject mappings, JsonObject probe, String probeReference, BehaviourEvidence evidence) {
        if (evidence != null) {
            List<String> problems = evidence.problems(keys, BehaviourKeys.IGNORED);
            if (!problems.isEmpty()) {
                throw new GenerationException("The behaviour keys do not match the BDS captures (BehaviourKeys):\n  " + String.join("\n  ", problems));
            }
        }
        Map<String, String> files = new TreeMap<>();
        for (Owner owner : Owner.values()) {
            String name = constantsClass(owner);
            files.put(API_PKG.replace('.', '/') + "/" + name + ".java", file(API_PKG, constants(owner, keys)));
        }
        files.put(INTERNAL_PKG.replace('.', '/') + "/WireRules.java", file(INTERNAL_PKG, wireRules(keys, mappings, probe, probeReference)));
        return files;
    }

    private static String file(String pkg, TypeSpec type) {
        return JavaFile.builder(pkg, type).addFileComment("$L", HEADER).indent("    ").skipJavaLangImports(true).build().toString();
    }

    static String constantsClass(Owner owner) {
        return owner == Owner.BLOCK ? "BlockBehaviour" : "ItemBehaviour";
    }

    static String constant(Key key) {
        return key.name().toUpperCase(Locale.ROOT);
    }

    private static ClassName type(Key key) {
        return key.type().equals("BlockShape") ? ClassName.get(API_PKG, "BlockShape") : ClassName.get("java.lang", key.type());
    }

    /** @return the key class of a key: {@code FloatKey}, {@code IntKey} and {@code BooleanKey} return primitives */
    private static TypeName keyType(Key key) {
        return switch (key.type()) {
            case "Float" -> ClassName.get(API_PKG, "FloatKey");
            case "Integer" -> ClassName.get(API_PKG, "IntKey");
            case "Boolean" -> ClassName.get(API_PKG, "BooleanKey");
            default -> ParameterizedTypeName.get(ClassName.get(API_PKG, "ObjectKey"), type(key));
        };
    }

    // ---- BlockBehaviour, ItemBehaviour ---------------------------------------------------------------------------------

    private static TypeSpec constants(Owner owner, List<Key> all) {
        List<Key> keys = all.stream().filter(k -> k.owner() == owner).toList();
        String plural = owner == Owner.BLOCK ? "block" : "item";
        TypeSpec.Builder type = TypeSpec.classBuilder(constantsClass(owner)).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("$L\n\n@since 1.0.0\n", owner == Owner.BLOCK ? BehaviourKeys.BLOCK_DOC : BehaviourKeys.ITEM_DOC);
        for (Key key : keys) {
            // the ordinal is the position among every key, block and item ones alike
            CodeBlock initializer = key.type().equals("Float") || key.type().equals("Integer") || key.type().equals("Boolean")
                ? CodeBlock.of("new $T($S, $T.Owner.$L, $L)", keyType(key), key.name(), BEHAVIOUR_KEY, owner.name(), all.indexOf(key))
                : CodeBlock.of("new $T<>($S, $T.Owner.$L, $L, $T.class)", ClassName.get(API_PKG, "ObjectKey"), key.name(), BEHAVIOUR_KEY,
                    owner.name(), all.indexOf(key), type(key));
            FieldSpec.Builder field = FieldSpec.builder(keyType(key), constant(key), Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(initializer);
            String doc = doc(key);
            if (!doc.isEmpty()) {
                field.addJavadoc("$L\n", doc);
            }
            type.addField(field.build());
        }
        CodeBlock.Builder list = CodeBlock.builder().add("$T.of(", List.class);
        for (int i = 0; i < keys.size(); i++) {
            list.add(i == 0 ? "$N" : ",$W$N", constant(keys.get(i)));
        }
        type.addField(FieldSpec.builder(ParameterizedTypeName.get(ClassName.get(List.class), ANY_KEY), "KEYS",
            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer(list.add(")").build()).build());
        type.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
        type.addMethod(MethodSpec.methodBuilder("keys").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc("Lists the keys, in declaration order.\n\n@return every $L key\n", plural)
            .returns(ApiSource.nonNull(ParameterizedTypeName.get(ClassName.get(List.class), ANY_KEY))).addStatement("return KEYS").build());
        type.addMethod(MethodSpec.methodBuilder("key").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc("Finds a key by its name.\n\n@param name the key name, as {@code BehaviourKey.name()} gives it\n@return the key of a name, or null if there is none\n")
            .returns(ApiSource.nullable(ANY_KEY)).addParameter(ApiSource.nonNull(ClassName.get(String.class)), "name")
            .beginControlFlow("for ($T key : KEYS)", ANY_KEY)
            .beginControlFlow("if (key.name().equals(name))").addStatement("return key").endControlFlow()
            .endControlFlow()
            .addStatement("return null").build());
        return type.build();
    }

    /** The table's text, the BDS property the key is, and which add-on components set the key on custom content. */
    private static String doc(Key key) {
        StringBuilder doc = new StringBuilder(key.doc());
        if (!doc.isEmpty()) {
            doc.append("\n<p>\n");
        }
        doc.append("BDS: ");
        for (int i = 0; i < key.sources().size(); i++) {
            doc.append(i == 0 ? "" : i == key.sources().size() - 1 ? " and " : ", ").append(describe(key.sources().get(i)));
        }
        doc.append('.');
        if (key.wire() != null) {
            doc.append("\n<p>\n");
            doc.append("Custom ").append(key.owner() == Owner.BLOCK ? "blocks" : "items").append(" set it with ");
            List<String> components = key.wire().components();
            for (int i = 0; i < components.size(); i++) {
                doc.append(i == 0 ? "" : i == components.size() - 1 ? " and " : ", ").append("{@code ").append(components.get(i)).append('}');
            }
            doc.append('.');
        }
        return doc.toString();
    }

    /** A source of {@link BehaviourKeys} in words, for the Javadoc. */
    static String describe(String source) {
        String name = source.substring(source.indexOf(':') + 1).replace("{direction}", "<direction>");
        return switch (source.substring(0, source.indexOf(':'))) {
            case "SAPI" -> "the Script API's {@code " + name + "}";
            case "PROBE" -> "measured by {@code behaviour-probe} ({@code " + name + "})";
            case "NATIVE" -> "{@code " + name + "} (native; from third-party dumps until Paletter captures it)";
            default -> throw new GenerationException("unknown source " + source);
        };
    }

    // ---- WireRules -----------------------------------------------------------------------------------------------------

    private static TypeSpec wireRules(List<Key> keys, JsonObject mappings, JsonObject probe, String probeReference) {
        TypeSpec.Builder type = TypeSpec.classBuilder("WireRules").addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addAnnotation(INTERNAL)
            .addJavadoc("Behaviour values read from the component data BDS sends (or Paletter sends for custom content): one rule per key.\n"
                + "The same rules read vanilla wire data and custom components, so the tests check them against the vanilla dumps on\n"
                + "the data driven blocks that carry both. Scalar rules are the inferred rules of {@code data/model/mappings.json}\n"
                + "read backwards; the shapes are the hand-written {@link ShapeRules}.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());

        CodeBlock.Builder components = CodeBlock.builder();
        CodeBlock.Builder read = CodeBlock.builder();
        for (Key key : keys) {
            if (key.wire() == null) {
                continue;
            }
            ClassName owner = ClassName.get(API_PKG, constantsClass(key.owner()));
            CodeBlock.Builder list = CodeBlock.builder().add("$T.of(", List.class);
            List<String> names = key.wire().components();
            for (int i = 0; i < names.size(); i++) {
                list.add(i == 0 ? "$S" : ", $S", names.get(i));
            }
            components.beginControlFlow("if (key == $T.$N)", owner, constant(key))
                .addStatement("return $L", list.add(")").build()).endControlFlow();
            read.beginControlFlow("if (key == $T.$N)", owner, constant(key));
            switch (key.wire()) {
                case Hand hand -> read.addStatement("return $T.$N(properties)", SHAPE_RULES, hand.method());
                case Leaf leaf -> leafRead(read, key, leaf, mappings, probe, probeReference);
            }
            read.endControlFlow();
        }
        components.addStatement("return $T.of()", List.class);
        read.addStatement("return null");

        type.addMethod(MethodSpec.methodBuilder("components").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addJavadoc("@return the add-on component names (e.g. {@code minecraft:friction}) a user sets to set the key; empty if the\n"
                + "component data does not carry the key\n")
            .returns(ApiSource.nonNull(ParameterizedTypeName.get(List.class, String.class)))
            .addParameter(ApiSource.nonNull(ANY_KEY), "key").addCode(components.build()).build());
        TypeVariableName t = TypeVariableName.get("T");
        type.addMethod(MethodSpec.methodBuilder("value").addModifiers(Modifier.PUBLIC, Modifier.STATIC).addTypeVariable(t)
            .addJavadoc("@param properties the properties of a block state (permutations applied) or an item\n"
                + "@return the value, or null if the component data does not say\n")
            .returns(ApiSource.nullable(t)).addParameter(ApiSource.nonNull(ParameterizedTypeName.get(BEHAVIOUR_KEY, t)), "key")
            .addParameter(ApiSource.nonNull(PROPERTIES), "properties")
            .addStatement("return key.type().cast(read(key, properties))").build());
        type.addMethod(MethodSpec.methodBuilder("read").addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ApiSource.nullable(ClassName.OBJECT)).addParameter(ANY_KEY, "key").addParameter(PROPERTIES, "properties")
            .addCode(read.build()).build());
        type.addMethod(number("floatOf", Float.class, "floatValue"));
        type.addMethod(number("intOf", Integer.class, "intValue"));
        return type.build();
    }

    private static MethodSpec number(String name, Class<?> type, String conversion) {
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ApiSource.nullable(ClassName.get(type))).addParameter(ApiSource.nullable(NBT_MAP), "map").addParameter(String.class, "field")
            .addStatement("return map != null && map.get(field) instanceof Number number ? number.$N() : null", conversion).build();
    }

    private static void leafRead(CodeBlock.Builder read, Key key, Leaf leaf, JsonObject mappings, JsonObject probe, String probeReference) {
        String where = key.owner().name().toLowerCase(Locale.ROOT) + ":" + key.name();
        if (!key.type().equals("Float") && !key.type().equals("Integer")) {
            throw new GenerationException(where + ": a wire leaf is read as a number, the key is " + key.type());
        }
        JsonObject rule = rule(where, key.owner(), leaf, mappings);
        String wire = rule.get("wire").getAsString();
        int dot = wire.indexOf('.');
        if (dot < 0 || wire.indexOf('.', dot + 1) >= 0) {
            throw new GenerationException(where + ": the leaf " + wire + " is not <component>.<field>");
        }
        String wireComponent = wire.substring(0, dot);
        String wireField = wire.substring(dot + 1);
        String getter = key.type().equals("Float") ? "floatOf" : "intOf";
        CodeBlock get = CodeBlock.of("$N(properties.component($S), $S)", getter, wireComponent, wireField);
        String from = leaf.component() + (leaf.field().isEmpty() ? " (value form)" : " " + leaf.field());
        Relation relation = relation(where, key.name(), probe, probeReference);
        if (wireComponent.equals("item_properties")) {
            if (relation != null) {
                throw new GenerationException(where + ": the probe measured a relation, but " + wire + " is read as sent");
            }
            read.add("// $L -> $L ($L): the engine's item property, read as sent\n", from, wire, describe(rule));
            read.addStatement("return $L", get);
            return;
        }
        // value = scale * wire + offset: the inverse of the rule, then the probe's relation
        double scale;
        double offset;
        switch (rule.get("kind").getAsString()) {
            case "identity" -> {
                scale = 1;
                offset = 0;
            }
            case "linear" -> {
                double s = rule.get("scale").getAsDouble();
                double o = rule.get("offset").getAsDouble();
                if (rule.has("modulo") || s == 0) {
                    throw new GenerationException(where + ": the linear rule onto " + wire + " cannot be inverted: " + rule);
                }
                scale = 1 / s;
                offset = -o / s;
            }
            default -> throw new GenerationException(where + ": the " + rule.get("kind").getAsString() + " rule onto " + wire
                + " cannot be inverted; read the key by hand");
        }
        read.add("// $L -> $L ($L): read back\n", from, wire, describe(rule));
        if (relation != null) {
            read.add("// engine = $L, measured by behaviour-probe ($L $L.components)\n", linear("component", relation.scale(), relation.offset()),
                relation.reference(), key.name());
            offset = relation.scale() * offset + relation.offset();
            scale = relation.scale() * scale;
        }
        if (scale == 1 && offset == 0) {
            read.addStatement("return $L", get);
            return;
        }
        if (key.type().equals("Integer")) {
            throw new GenerationException(where + ": the value is " + linear("wire", scale, offset) + ", not an integer");
        }
        read.addStatement("$T value = $L", Float.class, get);
        read.addStatement("return value == null ? null : $L", linear("value", scale, offset).replaceAll("(\\d+\\.\\d+(E-?\\d+)?)", "$1f"));
    }

    /** {@code scale * x + offset}, without a unit scale or a zero offset. */
    static String linear(String x, double scale, double offset) {
        String term = scale == 1 ? x : scale == -1 ? "-" + x : scale + " * " + x;
        if (offset == 0) {
            return term;
        }
        return scale == -1 ? offset + " - " + x : term + (offset < 0 ? " - " + -offset : " + " + offset);
    }

    private static String describe(JsonObject rule) {
        String kind = rule.get("kind").getAsString();
        return kind.equals("linear") ? "linear, scale " + rule.get("scale").getAsDouble() + ", offset " + rule.get("offset").getAsDouble() : kind;
    }

    /**
     * The rule of the field onto the leaf, the same in every protocol window of the component.
     *
     * @throws GenerationException if a window has no such rule or two rules differ
     */
    static JsonObject rule(String where, Owner owner, Leaf leaf, JsonObject mappings) {
        String id = owner.name().toLowerCase(Locale.ROOT) + ":" + leaf.component();
        JsonObject component = mappings.getAsJsonObject("components").getAsJsonObject(id);
        if (component == null) {
            throw new GenerationException(where + ": mappings.json has no component " + id);
        }
        List<JsonObject> windows = dev.opencollab.paletter.tools.model.MappingWindows.of(component);
        JsonObject found = null;
        for (JsonObject window : windows) {
            List<JsonObject> rules = new ArrayList<>();
            if (leaf.field().isEmpty()) {
                // the value of the component itself: its value forms
                if (window.has("forms")) {
                    window.getAsJsonObject("forms").entrySet().forEach(form -> rules.addAll(rules(form.getValue().getAsJsonObject())));
                }
            } else if (window.has("fields") && window.getAsJsonObject("fields").has(leaf.field())) {
                rules.addAll(rules(window.getAsJsonObject("fields").getAsJsonObject(leaf.field())));
            }
            List<JsonObject> onto = rules.stream().filter(r -> r.has("wire") && (leaf.leaf() == null
                ? Rule.of(r).writesValue() : r.get("wire").getAsString().equals(leaf.leaf()))).toList();
            if (onto.isEmpty()) {
                throw new GenerationException(where + ": mappings.json has no " + (leaf.leaf() == null ? "value rule" : "rule") + " of " + id + " "
                    + (leaf.field().isEmpty() ? "(value forms)" : leaf.field()) + (leaf.leaf() == null ? "" : " onto " + leaf.leaf())
                    + (window == component ? "" : " in a protocol window") + "; the leaf was renamed or moved: update BehaviourKeys");
            }
            if (leaf.leaf() == null && onto.stream().map(r -> r.get("wire").getAsString()).distinct().count() > 1) {
                throw new GenerationException(where + ": the value rules of " + id + " " + (leaf.field().isEmpty() ? "(value forms)" : leaf.field())
                    + " write several leaves (" + onto.stream().map(r -> r.get("wire").getAsString()).distinct().toList() + "); name the leaf in BehaviourKeys");
            }
            for (JsonObject r : onto) {
                if (found == null) {
                    found = r;
                } else if (!same(found, r)) {
                    throw new GenerationException(where + ": the rules onto " + leaf.leaf() + " differ (" + found + " / " + r
                        + "); WireRules has no protocol to tell them apart");
                }
            }
        }
        return found;
    }

    private static List<JsonObject> rules(JsonObject field) {
        List<JsonObject> rules = new ArrayList<>();
        if (field.has("rules")) {
            field.getAsJsonArray("rules").forEach(r -> rules.add(r.getAsJsonObject()));
        }
        return rules;
    }

    /** Two rules read the same: kind and its numbers (tags and NBT types aside). */
    private static boolean same(JsonObject a, JsonObject b) {
        for (String member : List.of("kind", "scale", "offset", "modulo", "table", "value")) {
            if (!java.util.Objects.equals(a.get(member), b.get(member))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the linear relation of engine to component value the probe measured for a key, or null if it measured none
     */
    static Relation relation(String where, String name, JsonObject probe, String reference) {
        if (probe == null || !probe.has(name) || !probe.getAsJsonObject(name).has("components")) {
            return null;
        }
        JsonArray pairs = probe.getAsJsonObject(name).getAsJsonArray("components");
        int n = pairs.size();
        if (n < 2) {
            throw new GenerationException(where + ": the probe measured " + n + " pair(s), too few for a relation");
        }
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (JsonElement e : pairs) {
            double x = e.getAsJsonObject().get("component").getAsDouble();
            double y = e.getAsJsonObject().get("engine").getAsDouble();
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        double scale = round((n * sxy - sx * sy) / (n * sxx - sx * sx));
        double offset = round((sy - scale * sx) / n);
        for (JsonElement e : pairs) {
            double x = e.getAsJsonObject().get("component").getAsDouble();
            double y = e.getAsJsonObject().get("engine").getAsDouble();
            if (Math.abs(scale * x + offset - y) > PROBE_TOLERANCE) {
                throw new GenerationException(where + ": the probe's engine value " + y + " at component " + x + " is not "
                    + linear("component", scale, offset) + "; no linear relation fits");
            }
        }
        return new Relation(scale, offset, reference);
    }

    /** To the probe's 3 decimals; also turns -0.0 into 0.0. */
    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0 + 0.0;
    }
}
