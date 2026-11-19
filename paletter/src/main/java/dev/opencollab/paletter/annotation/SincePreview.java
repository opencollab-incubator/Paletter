package dev.opencollab.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks generated API that only the bundled Bedrock <b>preview</b> has: content or components that no bundled release
 * has yet. Paletter bundles the newest preview next to the releases; its data is only served by a Paletter built with
 * {@link dev.opencollab.paletter.Paletter.Builder#previews(boolean) previews(true)}. The constants exist either way, but are
 * linked to the newest release, so they are never {@code isAvailable()}. Item and block constants behave alike:
 * <ul>
 *     <li>With previews, the preview's palette sends the item or block itself.</li>
 *     <li>Every palette that lacks the content (all of them without previews) resolves it through the
 *     {@link dev.opencollab.paletter.Fallbacks}, like any content a version lacks: its entry there, else the default,
 *     {@code minecraft:unknown} by default.</li>
 *     <li>The constant is the one key of its content: the palette's lookups ({@code Palette#blockType},
 *     {@code Palette#blockState}, {@code Palette#itemType}) and {@code decode} give back the constant and its states.
 *     A block constant has the preview's properties and states, which were written into it when the constants were
 *     generated: a palette without previews loads no preview data for them.</li>
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
