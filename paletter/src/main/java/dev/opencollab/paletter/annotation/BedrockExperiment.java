package dev.opencollab.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component for which the Minecraft creator docs name an experiment toggle ("Upcoming Creator Features" and the
 * like), in some format versions. The toggle id is the one the captured Bedrock Dedicated Server gives that name; see
 * the constants of {@code BedrockExperiments}, and enable one with {@code VanillaContent#experiments(String...)}.
 * <p>
 * The gating comes from the docs. Where a BDS capture of the component in a world without experiments exists, the component's
 * Javadoc says <b>BDS-verified</b> and for which builds it holds; without one it says <b>unverified against BDS</b>.
 * <p>
 * This is separate from {@link Experimental}: that one means the Paletter API itself may change.
 *
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BedrockExperiment {
    /**
     * The experiment toggle of the component.
     *
     * @return the toggle id, one of the constants of {@code BedrockExperiments}, e.g. {@code upcoming_creator_features}
     */
    String value();
}
