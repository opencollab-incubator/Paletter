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
 * Two kinds of API carry it:
 * <ul>
 *     <li>The raw NBT escape hatches ({@code ItemComponents.Builder#raw}, {@code BlockComponents.Builder#raw}, ...). With
 *     them <b>you own the wire format</b>: Paletter writes the NBT as given, for every Bedrock version, and does not adapt
 *     it when Bedrock changes the component's shape. Prefer the semantic builder methods, which Paletter encodes per
 *     version.</li>
 *     <li>Semantic API whose network behaviour is not verified against a vanilla server yet (e.g. a component only Geyser
 *     sends). It graduates, without source changes, once it is.</li>
 * </ul>
 *
 * @since 2.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Experimental {
}
