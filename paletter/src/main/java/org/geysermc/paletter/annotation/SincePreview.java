package org.geysermc.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks generated API that only the bundled Bedrock <b>preview</b> has: content or components that no bundled release
 * has yet. Paletter bundles the newest preview next to the releases; its data is only served by a Paletter built with
 * {@link org.geysermc.paletter.Paletter.Builder#previews(boolean) previews(true)}. The constants exist either way, but are
 * linked to the newest release, so they are never {@code isAvailable()}. What a palette sends for them:
 * <ul>
 *     <li>An item constant: with previews, the item itself in the preview's palette. Every palette that lacks the item
 *     (all of them without previews) resolves it through the {@link org.geysermc.paletter.Fallbacks}, like any item a
 *     version lacks: its entry there, else the default, {@code minecraft:unknown} by default. The constant is the one
 *     type of its identifier, also the one the palette's lookups and {@code decode} give back.</li>
 *     <li>A block constant: its states are those of its fallback, {@code minecraft:unknown}, in every palette, so it
 *     sends that and {@code Fallbacks} entries for it do not apply: the states carry no trace of the constant, and its
 *     properties are not known without the preview's data. With previews, the preview's blocks are reached by the
 *     palette's lookups ({@code Palette#blockType}, {@code Palette#blockState}), which give types of their own, not
 *     equal to the constant.</li>
 * </ul>
 * <p>
 * The API follows the previews: when the release ships, the annotation goes away (the content is released, unchanged or
 * changed as the release has it); when a newer preview drops the content, so does the API. Like {@link Experimental},
 * it is not covered by the compatibility promise.
 *
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface SincePreview {
    /**
     * The preview that has the content.
     *
     * @return the bundled preview version that has it, e.g. {@code 26.70}
     */
    String value();
}
