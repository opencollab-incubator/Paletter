package org.geysermc.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the components that replace a deprecated one: Bedrock's documentation or Bedrock Dedicated Server itself says
 * so (for example "Use minecraft:x instead"). Always paired with {@link Deprecated}; the javadoc {@code @deprecated}
 * tag states the evidence. The generated components record where each replacement comes from.
 *
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ReplacedBy {
    /**
     * @return the replacing component types, in the order the source names them
     */
    Class<?>[] value();
}
