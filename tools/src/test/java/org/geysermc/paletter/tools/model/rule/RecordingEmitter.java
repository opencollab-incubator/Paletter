package org.geysermc.paletter.tools.model.rule;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.GenerationException;

import java.util.List;

/** An {@link Emitter} whose expressions are named after what they stand for, to read what a rule's {@code emit} writes. */
final class RecordingEmitter implements Emitter {
    private final CodeBlock.Builder code = CodeBlock.builder();
    private int locals;

    /** @return the statements the rule wrote */
    String written() {
        return code.build().toString().strip();
    }

    @Override
    public CodeBlock.Builder code() {
        return code;
    }

    @Override
    public String local(String prefix) {
        return prefix + locals++;
    }

    @Override
    public CodeBlock value() {
        return CodeBlock.of("value");
    }

    @Override
    public CodeBlock number() {
        return CodeBlock.of("number");
    }

    @Override
    public CodeBlock node() {
        return CodeBlock.of("out");
    }

    @Override
    public String leafPath() {
        return "leaf";
    }

    @Override
    public CodeBlock leaf() {
        return CodeBlock.of("leaf()");
    }

    @Override
    public CodeBlock holds(String jsonType) {
        return CodeBlock.of("holds($S)", jsonType);
    }

    @Override
    public Emitter as(String jsonType) {
        code.addStatement("as($S)", jsonType);
        return this;
    }

    @Override
    public CodeBlock input(String path) {
        return CodeBlock.of("input($S)", path);
    }

    @Override
    public CodeBlock enumValue() {
        return CodeBlock.of("key");
    }

    @Override
    public String enumConstant(String value) {
        return value.toUpperCase();
    }

    @Override
    public CodeBlock string() {
        return CodeBlock.of("string");
    }

    @Override
    public CodeBlock key() {
        return CodeBlock.of("key");
    }

    @Override
    public CodeBlock convert(String nbt) {
        return CodeBlock.of("convert($S)", nbt);
    }

    @Override
    public CodeBlock fromNumber(String nbt, CodeBlock number) {
        return CodeBlock.of("$L($L)", nbt, number);
    }

    @Override
    public CodeBlock strings() {
        return CodeBlock.of("strings");
    }

    @Override
    public TypeName elementType() {
        return null;
    }

    @Override
    public CodeBlock elementString(CodeBlock element) {
        return element;
    }

    @Override
    public void put(CodeBlock value) {
        code.addStatement("put($L)", value);
    }

    @Override
    public CodeBlock write(String typed) {
        return CodeBlock.of("write($S)", typed);
    }

    @Override
    public CodeBlock component() {
        return CodeBlock.of("component");
    }

    @Override
    public String path() {
        return "path";
    }

    @Override
    public List<String> documentedValues() {
        return null;
    }

    @Override
    public GenerationException error(String message) {
        return new GenerationException(message);
    }
}
