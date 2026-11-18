package org.geysermc.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks API that is public and supported, but <b>not covered by the compatibility promise</b>: it may change or be removed
 * in a minor release without a deprecation cycle. Changes are listed in the release notes.
 * <p>
 * It also marks API whose behaviour is not settled or not verified against the Bedrock client, such as vanilla item
 * overrides ({@code PaletteLayer.Builder#overrideItem}).
 *
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Experimental {
}
