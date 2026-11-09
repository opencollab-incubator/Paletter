package org.geysermc.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks Bedrock content that BDS only offers to worlds with Education Edition features: the generated vanilla constants
 * of chemistry items, element blocks, hard glass and the like. Which content that is comes from BDS itself (a world
 * started with and without {@code educationFeaturesEnabled}, see {@code tools} {@code education-capture}), not from a
 * list. The types answer it at runtime with {@code isEducation()}.
 *
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface Education {
}
