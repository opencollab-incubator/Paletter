package dev.opencollab.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The first bundled data version ({@code DataVersion#name()}, e.g. {@code 26.40}) whose captured Bedrock server has the
 * annotated constant or component. For the oldest bundled version it means "at the latest": earlier versions are not bundled.
 * A component is only marked when an older captured server does not have it; palettes of older versions leave it out and
 * add a report warning.
 *
 * @since 1.0.0
 * @see BedrockUntil
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface BedrockSince {
    /**
     * The first bundled version with the constant or component.
     *
     * @return the name of the data version, e.g. {@code 26.40}
     */
    String value();
}
