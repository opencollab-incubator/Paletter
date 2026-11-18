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
 * linked to the newest release: they resolve through the fallbacks.
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
