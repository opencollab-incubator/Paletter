package dev.opencollab.paletter.tools.codegen.component;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumRef;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.EnumType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ListOf;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Type;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.model.rule.Emitter;
import dev.opencollab.paletter.tools.model.rule.Rule;

import java.util.List;
import java.util.Set;

import static dev.opencollab.paletter.tools.codegen.component.WireSource.WIRE;

/**
 * What a rule of a field writes against ({@link Rule#emit}): the field's value (narrowed to the element of an element
 * rule) and the leaf, relative to the node, in the code of one window ({@link WindowWire}).
 */
final class LeafEmitter implements Emitter {
    private final WindowWire window;
    private final Field f;
    private final Type t;
    private final CodeBlock expr;
    private final String node;
    private final String rel;
    /** Whether the leaf is the list of a rule of every element: the value is written as a list. */
    private final boolean list;

    LeafEmitter(WindowWire window, Field f, Type t, CodeBlock expr, String node, String rel, boolean list) {
        this.window = window;
        this.f = f;
        this.t = t;
        this.expr = expr;
        this.node = node;
        this.rel = rel;
        this.list = list;
    }

    private String where() {
        return window.id() + " " + f.path;
    }

    @Override
    public CodeBlock.Builder code() {
        return window.code();
    }

    @Override
    public String local(String prefix) {
        return window.local(prefix);
    }

    @Override
    public CodeBlock value() {
        return expr;
    }

    @Override
    public CodeBlock number() {
        return WireValues.numeric(t, expr);
    }

    @Override
    public CodeBlock node() {
        return CodeBlock.of("$N", node);
    }

    @Override
    public String leafPath() {
        return rel;
    }

    @Override
    public CodeBlock holds(String jsonType) {
        throw error("a union rule needs the sealed type of the union, which the API does not generate yet");
    }

    @Override
    public Emitter as(String jsonType) {
        throw error("a union rule needs the sealed type of the union, which the API does not generate yet");
    }

    @Override
    public CodeBlock enumValue() {
        enumType();
        return expr;
    }

    @Override
    public String enumConstant(String value) {
        EnumType type = enumType();
        int i = type.values.indexOf(value);
        if (i < 0) {
            throw error("the enum has no value " + value + " (it has " + type.values + ")");
        }
        return type.constants.get(i);
    }

    private EnumType enumType() {
        if (list || !(t instanceof EnumRef r)) {
            throw error("an enum table needs an enum field");
        }
        return r.type();
    }

    @Override
    public CodeBlock string() {
        return WireValues.stringOf(t, expr);
    }

    @Override
    public CodeBlock key() {
        return WireValues.key(t, expr);
    }

    @Override
    public CodeBlock convert(String nbt) {
        if (!list) {
            return WireValues.convert(where(), t, expr, nbt);
        }
        if (t instanceof ListOf l) {
            return WireValues.listValue(window.id(), l.element(), nbt, expr);
        }
        return CodeBlock.of("$T.floats($T.of($L.x(), $L.y(), $L.z()))", WIRE, ApiSource.LIST, expr, expr, expr);
    }

    @Override
    public CodeBlock fromNumber(String nbt, CodeBlock number) {
        return WireValues.fromNumber(where(), nbt, number);
    }

    @Override
    public CodeBlock strings() {
        if (!list || !(t instanceof ListOf l)) {
            throw error("a rule of the strings of a list needs a list field");
        }
        return l.element() instanceof EnumRef ? CodeBlock.of("$L.stream().map(x -> x.value()).toList()", expr) : expr;
    }

    @Override
    public TypeName elementType() {
        return t instanceof ListOf l ? ApiSource.typeName(window.component(), l.element(), true) : null;
    }

    @Override
    public CodeBlock elementString(CodeBlock element) {
        return WireValues.stringOf(((ListOf) t).element(), element);
    }

    @Override
    public CodeBlock leaf() {
        return CodeBlock.of("$N.get($S)", node, rel);
    }

    @Override
    public void put(CodeBlock value) {
        window.put(node, rel, value);
    }

    @Override
    public CodeBlock write(String typed) {
        return typed.equals(Rule.NOT_SENT) ? CodeBlock.of("$N.remove($S)", node, rel) : CodeBlock.of("$N.put($S, $L)", node, rel, WireValues.literal(typed));
    }

    @Override
    public CodeBlock component() {
        return window.name();
    }

    @Override
    public CodeBlock unknown(Rule rule, CodeBlock value) {
        List<Rule> all = window.rules(f).stream().filter(Rule::writesValue).toList();
        Set<String> known = rule.knownValues(f.documentedValues);
        // The rules of the field that know the same values, written in the same place: the first of them reports
        List<Rule> group = all.stream().filter(r -> known.equals(r.knownValues(f.documentedValues))).toList();
        if (!group.isEmpty() && !identity(group.getFirst()).equals(identity(rule))) {
            return null;
        }
        // Leaves any value is written to: through them the value still reaches the wire
        List<String> sent = all.stream().filter(r -> r.knownValues(f.documentedValues) == null).map(r -> window.strip(r.wire)).distinct().toList();
        if (sent.isEmpty()) {
            return CodeBlock.of("ctx.drop($L, $S + $L + $S)", window.name(), f.path + " = ", value, " has no known wire value");
        }
        List<String> derived = group.stream().map(r -> window.strip(r.wire)).distinct().toList();
        return CodeBlock.of("ctx.changed($L, $S + $L + $S)", window.name(), f.path + " = ", value, " is sent as " + String.join(", ", sent)
            + ", but " + String.join(", ", derived) + (derived.size() == 1 ? " has" : " have") + " no known wire value for it and keep"
            + (derived.size() == 1 ? "s its" : " their") + " default");
    }

    private static String identity(Rule r) {
        return r.kind() + " " + r.wire + " " + r.element;
    }

    @Override
    public String path() {
        return f.path;
    }

    @Override
    public List<String> documentedValues() {
        return f.documentedValues;
    }

    @Override
    public GenerationException error(String message) {
        return new GenerationException(where() + ": " + message);
    }
}
