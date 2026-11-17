package org.geysermc.paletter.tools.model.rule;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import org.geysermc.paletter.tools.codegen.component.ComponentGenerator.GenerationException;

import java.util.List;

/**
 * What a rule writes its part of a generated encoder against ({@link Rule#emit}): one field's value as Java and the
 * leaf the rule puts. For a rule of one list element the value is that element; for a rule of every element of a list
 * leaf ({@code wire} ending in {@code []}) the leaf is the list.
 */
public interface Emitter {
    /** The wire helpers of the generated encoders ({@code org.geysermc.paletter.internal.component.Wire}). */
    ClassName WIRE = ClassName.get("org.geysermc.paletter.internal.component", "Wire");

    /** @return the statements being written */
    CodeBlock.Builder code();

    /** @return a local variable name, new in the encoder, starting with {@code prefix} */
    String local(String prefix);

    /** @return the value */
    CodeBlock value();

    /** @return the value as a {@code double} (a boolean as 0 or 1) */
    CodeBlock number();

    /** @return the value as a string (an enum's own value) */
    CodeBlock string();

    /** @return the value as the key of a value table: a string (an enum's own value, a boolean or number as text) */
    CodeBlock key();

    /** @return the value as sent unchanged as a leaf of the NBT type (a list of scalars as a list) */
    CodeBlock convert(String nbt);

    /** @return the leaf's current value in the node being written: a boxed number, or null if nothing wrote it yet */
    CodeBlock leaf();

    /** @return a number as a leaf of the NBT type */
    CodeBlock fromNumber(String nbt, CodeBlock number);

    /** @return the value, a list, as the list of its strings (an enum's own values) */
    CodeBlock strings();

    /** @return the type of the elements of the value; null if it is no list */
    TypeName elementType();

    /** @return an element of the value, a list, as a string */
    CodeBlock elementString(CodeBlock element);

    /** Writes the leaf. */
    void put(CodeBlock value);

    /** @return the expression (no semicolon) that writes a {@code value (type)} literal to the leaf, or removes it for {@code <not sent>} */
    CodeBlock write(String typed);

    /** @return how a report ({@code ctx.drop}) names the component */
    CodeBlock component();

    /** @return the field's JSON path */
    String path();

    /** @return the values the docs list for the field, an open enum; null if they list none */
    List<String> documentedValues();

    /** @return the error of a rule that cannot be written for the field */
    GenerationException error(String message);
}
