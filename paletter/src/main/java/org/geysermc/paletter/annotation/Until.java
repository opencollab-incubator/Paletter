package org.geysermc.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The first bundled data version ({@code DataVersion#name()}, e.g. {@code 26.50}) whose captured Bedrock server no longer
 * has the annotated constant (the experiment was released or retired there). Absent: the newest bundled version still has it.
 *
 * @since 1.0.0
 * @see Since
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Until {
    /**
     * @return the name of the data version, e.g. {@code 26.50}
     */
    String value();
}
