package org.geysermc.paletter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public type or member of an exported package that is <b>not</b> part of Paletter's API: it is public only so
 * Paletter's own packages can share it. It may change or disappear in any release, including patch releases, and is
 * excluded from the binary compatibility check. Packages under {@code org.geysermc.paletter.internal} are internal as a
 * whole and carry no annotation.
 * <p>
 * Consumers must not use anything marked {@code @Internal}. See {@code API.md}.
 *
 * @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface Internal {
}
