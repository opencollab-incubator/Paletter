package dev.opencollab.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The first bundled release ({@code DataVersion#name()}, e.g. {@code 26.50}) whose captured Bedrock server no longer
 * has the annotated constant, component, type or member (the experiment was released or retired there, or Bedrock
 * rejects the component from there on). Absent: the newest bundled version still has it.
 * <p>
 * It describes Bedrock, not the Java API: the annotated element stays, {@code @Deprecated}, palettes of older versions
 * still serve it, and newer ones leave it out with a report warning. It is removed only in a major Paletter release
 * (docs/api.md, "Evolution").
 *
 * @since 1.0.0
 * @see BedrockSince
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD})
public @interface BedrockUntil {
    /**
     * The first bundled version without it.
     *
     * @return the name of the data version, e.g. {@code 26.50}
     */
    String value();
}
