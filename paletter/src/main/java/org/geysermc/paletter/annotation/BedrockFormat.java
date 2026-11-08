package org.geysermc.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The minimum add-on JSON {@code format_version} the creator documentation names for a component or field. Informational:
 * Paletter sends the wire form of each palette's protocol whatever the format version, so this only tells which Bedrock
 * releases understand the definition at all. On generated component types.
 *
 * @since 2.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
public @interface BedrockFormat {
    /**
     * @return the format version, e.g. {@code 1.20.0}
     */
    String value();
}
