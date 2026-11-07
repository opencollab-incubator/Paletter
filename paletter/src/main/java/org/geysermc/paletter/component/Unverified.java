package org.geysermc.paletter.component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component type whose network shape was not observed in vanilla or captured NBT, only in production use by
 * Geyser. Fields may be incomplete; the type may change once a capture is available.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Unverified {
}
