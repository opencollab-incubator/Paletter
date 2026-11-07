/**
 * Internal: not part of Paletter's API, may change in any release. See {@code API.md}.
 * <p>
 * The encoders: the only code that knows the custom item and block wire format. They map the semantic
 * {@code ItemComponents} / {@code BlockComponents} onto the generated wire shapes of each palette's Bedrock version,
 * and report what a version cannot express instead of failing.
 */
package org.geysermc.paletter.internal.encode;
