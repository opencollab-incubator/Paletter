package org.geysermc.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The first bundled data version ({@code DataVersion#name()}, e.g. {@code 26.40}) whose captured Bedrock server has the
 * annotated constant. For the oldest bundled version it means "at the latest": earlier versions are not bundled.
 *
 * @since 1.0.0
 * @see Until
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Since {
    /**
     * @return the name of the data version, e.g. {@code 26.40}
     */
    String value();
}
