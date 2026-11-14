package org.geysermc.paletter.tools.bds;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptModulesTest {
    // versions as npm lists them for @minecraft/server
    private static final JsonObject VERSIONS = JsonParser.parseString("{\"2.10.0-beta.1.26.43-stable\": {}, \"2.10.0-beta.1.26.44-stable\": {},"
        + "\"2.11.0\": {}, \"2.12.0-beta.1.26.63-stable\": {}, \"2.13.0-beta.1.26.70-preview.24\": {}}").getAsJsonObject();

    @Test
    void theBetaOfTheBuild() {
        assertEquals("2.12.0-beta", ScriptModules.betaVersion(VERSIONS, "1.26.63.1"));
    }

    @Test
    void aBuildNpmSkippedTakesItsReleaseLine() {
        assertEquals("2.10.0-beta", ScriptModules.betaVersion(VERSIONS, "1.26.45.1"));
    }

    @Test
    void noGuessWithoutTheLine() {
        assertThrows(IllegalStateException.class, () -> ScriptModules.betaVersion(VERSIONS, "1.26.52.3"));
    }
}
