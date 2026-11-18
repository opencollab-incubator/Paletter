package dev.opencollab.paletter.tools.model.rule;

import com.google.gson.JsonElement;

import java.util.Map;

/**
 * What a rule is fitted from: a JSON value BDS was given (null if the variant left it out) and the wire leaves it sent.
 *
 * @param label the variant, for messages
 */
public record Observation(JsonElement json, Map<String, Leaf> wire, String label) {
}
