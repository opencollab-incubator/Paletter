package dev.opencollab.paletter.tools.codegen.component;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Field;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.ObjType;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Window;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import dev.opencollab.paletter.tools.model.rule.Constant;
import dev.opencollab.paletter.tools.model.rule.Rule;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static dev.opencollab.paletter.tools.codegen.component.WireSource.WIRE_CONTEXT;
import static dev.opencollab.paletter.tools.codegen.component.WireSource.WIRE_NODE;
import static dev.opencollab.paletter.tools.codegen.component.WireSource.method;

/**
 * The encoder of one component: a body per protocol window ({@link WindowWire}), neighbours that encode the same joined,
 * and with several windows a dispatch on the protocol that covers every protocol.
 */
final class ComponentWire {
    private final Comp c;
    /** Whether the encoder is called from outside (a standalone type of the definition core, e.g. the menu category). */
    private final boolean exposed;
    /** The owner of each leaf several components of the kind write ({@link SharedLeaves}). */
    private final Map<String, String> owners;

    ComponentWire(Comp c, boolean exposed, Map<String, String> owners) {
        this.c = c;
        this.exposed = exposed;
        this.owners = owners;
    }

    private MethodSpec.Builder encoder(String name) {
        if (exposed && name.equals(method(c))) {
            return MethodSpec.methodBuilder(name).addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addJavadoc("Writes the {@link $T} of a definition, or for null what BDS sends for a definition without one.\n", ApiSource.className(c))
                .addParameter(ApiSource.nullable(ApiSource.className(c)), "c").addParameter(ApiSource.nonNull(WIRE_CONTEXT), "ctx")
                .addParameter(ApiSource.nonNull(WIRE_NODE), "out");
        }
        return MethodSpec.methodBuilder(name).addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(ApiSource.className(c), "c").addParameter(WIRE_CONTEXT, "ctx").addParameter(WIRE_NODE, "out");
    }

    /**
     * Adds the encoder methods of the component to {@code sink}.
     *
     * @return null if the component has an encoder method; else its whole encoder as the arrow body of a {@code case} of the
     *         dispatch, for an encoder of one statement or none
     */
    CodeBlock component(List<MethodSpec> sink) {
        String name = method(c);
        Set<String> transformed = transformedLeaves();
        // The body of each window; neighbours that encode the same (the shapes differ only in what the encoder ignores,
        // or the component is a hand transform that dispatches itself) are one window
        List<Window> windows = new ArrayList<>();
        List<CodeBlock> bodies = new ArrayList<>();
        for (Window w : c.windows.isEmpty() ? Collections.<Window>singletonList(null) : c.windows) {
            CodeBlock body = new WindowWire(c, w, exposed, transformed, owners).body();
            if (!bodies.isEmpty() && bodies.getLast().toString().equals(body.toString())) {
                Window last = windows.removeLast();
                windows.add(new Window(last.since(), w.until(), last.mapping()));
            } else {
                windows.add(w);
                bodies.add(body);
            }
        }
        if (windows.size() <= 1) {
            String text = bodies.getFirst().toString();
            if (!exposed && text.lines().count() <= 1 && !text.contains("{")) {
                // a component that writes one statement (or nothing) is a case of the dispatch
                return text.isEmpty() ? CodeBlock.of("{ }\n") : text.startsWith("//") ? CodeBlock.of("{ } $L", text) : bodies.getFirst();
            }
            sink.add(encoder(name).addCode(bodies.getFirst()).build());
            return null;
        }
        // The windows must cover every protocol: the last is a plain else, so a gap would be a silent hole
        for (int i = 0; i < windows.size(); i++) {
            boolean first = i == 0;
            boolean last = i == windows.size() - 1;
            if (first && windows.get(i).since() != Integer.MIN_VALUE || last && windows.get(i).until() != Integer.MAX_VALUE
                || !last && windows.get(i).until() != windows.get(i + 1).since()) {
                throw new GenerationException(c.id + ": the protocol windows are not contiguous: " + windows);
            }
        }
        CodeBlock.Builder dispatch = CodeBlock.builder().addStatement("int protocol = ctx.protocol()");
        for (int i = 0; i < windows.size(); i++) {
            Window w = windows.get(i);
            if (i == 0) {
                dispatch.beginControlFlow("if (protocol < $L)", w.until());
            } else if (i < windows.size() - 1) {
                dispatch.nextControlFlow("else if (protocol < $L)", w.until());
            } else {
                dispatch.nextControlFlow("else");
            }
            dispatch.addStatement("$N$L(c, ctx, out)", name, suffix(w));
        }
        dispatch.endControlFlow();
        sink.add(encoder(name).addCode(dispatch.build()).build());
        for (int i = 0; i < windows.size(); i++) {
            sink.add(encoder(name + suffix(windows.get(i))).addCode(bodies.get(i)).build());
        }
        return null;
    }

    /** The name suffix of a window's encoder: {@code From<since>Until<until>}, each bound left out where it is open. */
    private static String suffix(Window w) {
        return (w.since() == Integer.MIN_VALUE ? "" : "From" + w.since()) + (w.until() == Integer.MAX_VALUE ? "" : "Until" + w.until());
    }

    /**
     * The leaves the value of a transformed field (or of a field inside it) moves in any window of the component. Its
     * transform writes them in every window (it checks the protocol itself), so a window where such a leaf never varied
     * does not write it as a constant first: the windows would differ only in a value the transform overwrites.
     */
    private Set<String> transformedLeaves() {
        Set<String> leaves = new TreeSet<>();
        for (Window w : c.windows) {
            if (w.sent()) {
                transformedLeaves(c.root, w, false, leaves);
            }
        }
        return leaves;
    }

    private void transformedLeaves(ObjType o, Window w, boolean inside, Set<String> leaves) {
        for (Field f : o.fields) {
            boolean transformed = inside || f.transform != null || WireRules.writesHolder(w, f);
            if (transformed) {
                for (Rule rule : Rule.all(WireRules.mappingOf(w, f))) {
                    // not what only the field's presence switches: such a constant can be another field's leaf
                    if (!(rule instanceof Constant) || rule.mapEntry) {
                        leaves.add(WindowWire.strip(c, rule.wire));
                    }
                }
            }
            ObjType child = WireRules.objectOf(f.type);
            if (child != null) {
                transformedLeaves(child, w, transformed, leaves);
            }
        }
    }
}
