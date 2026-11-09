package org.geysermc.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component that Bedrock Dedicated Server accepts in a pack but never sends to clients, in the protocols of the
 * range. Palettes of those protocols leave the component out and add a {@code WARNING} to {@code Palette#report()}; where
 * Bedrock sends it again, the same record is encoded as usual. The range comes from the captures of BDS (the status of the
 * component's protocol windows in {@code data/model/mappings.json}), not from a list.
 * <p>
 * Without {@link #since()} and {@link #until()} the component is server only in every protocol.
 *
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ServerOnly {
    /**
     * @return the first protocol version the component is server only in, or {@link Integer#MIN_VALUE} for every protocol
     *         below {@link #until()}
     */
    int since() default Integer.MIN_VALUE;

    /**
     * @return the first protocol version after the range, where Bedrock sends the component, or {@link Integer#MAX_VALUE} if the range has no end
     */
    int until() default Integer.MAX_VALUE;
}
