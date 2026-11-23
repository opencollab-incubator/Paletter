package dev.opencollab.paletter.tools.behaviour;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.opencollab.paletter.behaviour.BehaviourKey;
import dev.opencollab.paletter.behaviour.BooleanKey;
import dev.opencollab.paletter.behaviour.FloatKey;
import dev.opencollab.paletter.behaviour.IntKey;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Locale;

/**
 * The typed setters the generated data is written with, one per key: {@code .destroyTime(1.5f)} for a value of every state,
 * {@code .destroyTime("growth", 0, 0.0f, 7, 0.5f)} for one that depends on one property, {@code .destroyTime(TABLE)} for a
 * shared {@code Cell}, {@code .destroyTimeUnknown()} where nothing is known. The source setters take the deciding capture
 * the same way ({@code ...Default()}: the version's default capture). Generated from the keys, like the data.
 */
final class BehaviourSetters {
    static final ClassName BLOCK_SETTERS = ClassName.get(BehaviourCode.PACKAGE, "BlockSetters");
    static final ClassName ITEM_SETTERS = ClassName.get(BehaviourCode.PACKAGE, "ItemSetters");
    static final ClassName BLOCK_SOURCES = ClassName.get(BehaviourCode.PACKAGE, "BlockSourceSetters");
    static final ClassName ITEM_SOURCES = ClassName.get(BehaviourCode.PACKAGE, "ItemSourceSetters");
    static final ClassName CHANGES = ClassName.get(BehaviourCode.PACKAGE, "Changes");
    static final ClassName CELL = ClassName.get("dev.opencollab.paletter.internal.behaviour", "Cell");
    static final ClassName CAPTURE = ClassName.get("dev.opencollab.paletter.internal.behaviour", "Capture");
    static final ClassName VANILLA_VERSION = ClassName.get("dev.opencollab.paletter.internal.behaviour", "VanillaVersion");
    static final ClassName VERSION_BUILDER = ClassName.get("dev.opencollab.paletter.internal.behaviour", "VanillaVersionBuilder");
    private static final ArrayTypeName OBJECTS = ArrayTypeName.of(Object.class);

    private BehaviourSetters() {
    }

    static List<TypeSpec> types() {
        return List.of(setters(BLOCK_SETTERS, BehaviourModel.BLOCK_KEYS, false, true), setters(ITEM_SETTERS, BehaviourModel.ITEM_KEYS, false, false),
            setters(BLOCK_SOURCES, BehaviourModel.BLOCK_KEYS, true, true), setters(ITEM_SOURCES, BehaviourModel.ITEM_KEYS, true, false), changes());
    }

    /** @return the name of a key's setter */
    static String setter(BehaviourKey<?> key) {
        return BehaviourNames.camel(key.name());
    }

    private static TypeSpec setters(ClassName name, List<BehaviourKey<?>> keys, boolean sources, boolean states) {
        String owner = keys.getFirst().owner().name().toLowerCase(Locale.ROOT);
        TypeSpec.Builder type = TypeSpec.classBuilder(name).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc(sources ? "Sets the captures that decided the values of a " + owner + ", by key, or of the version's default.\n"
                : "Sets the behaviour values of a " + owner + " (or of every " + owner + "), by key.\n")
            .addField(OBJECTS, "values", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(OBJECTS, "values")
                .addStatement("this.values = values").build());
        ClassName keyClass = ClassName.get("dev.opencollab.paletter.behaviour", keys.getFirst().owner() == BehaviourKey.Owner.BLOCK
            ? "BlockBehaviour" : "ItemBehaviour");
        for (BehaviourKey<?> key : keys) {
            String constant = key.name().toUpperCase(Locale.ROOT);
            TypeName valueType = sources ? CAPTURE : key instanceof FloatKey ? TypeName.FLOAT : key instanceof IntKey ? TypeName.INT
                : key instanceof BooleanKey ? TypeName.BOOLEAN : ClassName.get(key.type());
            type.addMethod(set(name, setter(key), keyClass, constant, ParameterSpec.builder(valueType, "value").build(), "value",
                "{@link $T#$L}: one value for every state"));
            if (states) {
                type.addMethod(MethodSpec.methodBuilder(setter(key)).addModifiers(Modifier.PUBLIC).returns(name).varargs()
                    .addJavadoc("{@link $T#$L}: a value per value of one property, {@code pairs} a property value, then its value, and so on\n",
                        keyClass, constant)
                    .addParameter(String.class, "property").addParameter(OBJECTS, "pairs")
                    .addStatement("values[$T.index($T.$L)] = $T.map(property, pairs)", VANILLA_VERSION, keyClass, constant, CELL)
                    .addStatement("return this").build());
                type.addMethod(set(name, setter(key), keyClass, constant, ParameterSpec.builder(CELL, "cell").build(), "cell",
                    "{@link $T#$L}: a value per state"));
            }
            type.addMethod(MethodSpec.methodBuilder(setter(key) + (sources ? "Default" : "Unknown")).addModifiers(Modifier.PUBLIC).returns(name)
                .addJavadoc(sources ? "{@link $T#$L}: the version's default capture (of the defaults: none)\n"
                    : "{@link $T#$L}: no value is known\n", keyClass, constant)
                .addStatement("values[$T.index($T.$L)] = null", VANILLA_VERSION, keyClass, constant)
                .addStatement("return this").build());
        }
        return type.build();
    }

    private static MethodSpec set(ClassName type, String method, ClassName keyClass, String constant, ParameterSpec parameter, String argument,
                                  String doc) {
        return MethodSpec.methodBuilder(method).addModifiers(Modifier.PUBLIC).returns(type).addParameter(parameter)
            .addJavadoc(doc + "\n", keyClass, constant)
            .addStatement("values[$T.index($T.$L)] = $L", VANILLA_VERSION, keyClass, constant, argument)
            .addStatement("return this").build();
    }

    /** The calls of one version, with the typed setters. */
    private static TypeSpec changes() {
        TypeSpec.Builder type = TypeSpec.classBuilder(CHANGES).addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("The calls of the generated data for one version: {@code VanillaVersionBuilder} with the typed setters.\n")
            .addField(VERSION_BUILDER, "version", Modifier.PRIVATE, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(VERSION_BUILDER, "version")
                .addStatement("this.version = version").build());
        type.addMethod(call("everyBlock", BLOCK_SETTERS, "Values of every block so far, and of every block added after them"));
        type.addMethod(call("block", BLOCK_SETTERS, "A block (replacing one of the same identifier) from the defaults", "identifier", "schema"));
        type.addMethod(call("change", BLOCK_SETTERS, "Changes a block of the version before", "identifier"));
        type.addMethod(call("remove", null, "Removes a block: the version has no values for it", "identifier"));
        type.addMethod(call("everyItem", ITEM_SETTERS, "Values of every item so far, and of every item added after them"));
        type.addMethod(call("item", ITEM_SETTERS, "An item (replacing one of the same identifier) from the defaults", "identifier"));
        type.addMethod(call("changeItem", ITEM_SETTERS, "Changes an item of the version before", "identifier"));
        type.addMethod(call("removeItem", null, "Removes an item: the version has no values for it", "identifier"));
        type.addMethod(MethodSpec.methodBuilder("blockSources").addModifiers(Modifier.PUBLIC).returns(BLOCK_SOURCES)
            .addJavadoc("The capture that decides a key of blocks without one of their own\n")
            .addStatement("return new $T(version.blockSourceDefaults())", BLOCK_SOURCES).build());
        type.addMethod(call("blockSources", BLOCK_SOURCES, "The captures of a block where they are not the default", "identifier"));
        type.addMethod(MethodSpec.methodBuilder("itemSources").addModifiers(Modifier.PUBLIC).returns(ITEM_SOURCES)
            .addJavadoc("The capture that decides a key of items without one of their own\n")
            .addStatement("return new $T(version.itemSourceDefaults())", ITEM_SOURCES).build());
        type.addMethod(call("itemSources", ITEM_SOURCES, "The captures of an item where they are not the default", "identifier"));
        return type.build();
    }

    private static MethodSpec call(String name, ClassName returns, String doc, String... parameters) {
        MethodSpec.Builder method = MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC).addJavadoc(doc + "\n");
        for (String parameter : parameters) {
            method.addParameter(String.class, parameter);
        }
        String call = "version." + name + "(" + String.join(", ", parameters) + ")";
        if (returns == null) {
            return method.addStatement(call).build();
        }
        return method.returns(returns).addStatement("return new $T(" + call + ")", returns).build();
    }
}
