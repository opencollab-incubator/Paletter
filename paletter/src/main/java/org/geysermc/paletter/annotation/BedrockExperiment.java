package org.geysermc.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component, or a field of one, that Bedrock only accepts in a world with an experiment toggle enabled ("Upcoming
 * Creator Features" and the like). Enable the toggle with {@code VanillaContent#experiments(String...)} (see the constants
 * of {@code BedrockExperiments}); without it the client or the server rejects the content.
 * <p>
 * The toggle is derived from what the captured Bedrock Dedicated Server calls it. This is separate from {@link Experimental}:
 * that one means the Paletter API itself may change; an experiment is Bedrock's, and graduates when Bedrock releases it.
 *
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface BedrockExperiment {
    /**
     * @return the toggle id, one of the constants of {@code BedrockExperiments}, e.g. {@code upcoming_creator_features}
     */
    String value();
}
