package dev.opencollab.paletter.tools.codegen.component;

import com.google.gson.JsonObject;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.Inputs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The encoders the packed bits, Molang and hash ordered set rules write, on a synthetic component. */
class PackedMolangHashEncoderTest {
    private static JsonObject json(String text) {
        return ComponentGeneratorTest.json(text);
    }

    private static String cube() {
        Inputs in = ComponentGeneratorTest.inputs();
        in.components().getAsJsonArray("components").add(json("""
            {"id": "block:test:cube", "kind": "block", "category": "component", "name": "test:cube", "status": "active",
             "doc": "A cube.", "page": "", "experiments": [], "forms": [{"type": "object"}], "notes": [],
             "fields": [
               {"path": "dimming", "types": [{"type": "boolean"}], "defaultValue": true, "required": false, "enumValues": [], "examples": [], "doc": "A bit."},
               {"path": "isotropic", "types": [{"type": "boolean"}], "defaultValue": false, "required": false, "enumValues": [], "examples": [], "doc": "A bit."},
               {"path": "culled", "types": [{"type": "boolean"}], "defaultValue": false, "required": false, "enumValues": [], "examples": [], "doc": "A bit."},
               {"path": "visible", "types": [{"type": "string"}], "required": false, "enumValues": [], "examples": [], "doc": "A Molang expression."},
               {"path": "bones", "types": [{"type": "map", "items": {"type": "string"}}], "required": false, "enumValues": [], "examples": [], "doc": "Molang by bone."},
               {"path": "locked", "types": [{"type": "array", "items": {"type": "string"}}], "required": false, "enumValues": [], "examples": [], "doc": "Bones."}
             ]}"""));
        in.mappings().getAsJsonObject("components").add("block:test:cube", json("""
            {"status": "sent", "wire": ["test:cube"], "protocols": [100, 200],
             "fields": {
               "dimming": {"status": "inferred", "rules": [{"kind": "packed-bits", "mask": 1, "base": 5, "nbt": "byte", "tags": [], "wire": "test:cube.packed"}],
                           "whenAbsent": {"test:cube.packed": "5 (byte)"}},
               "isotropic": {"status": "inferred", "rules": [{"kind": "packed-bits", "mask": 2, "base": 5, "nbt": "byte", "tags": [], "wire": "test:cube.packed"}],
                             "whenAbsent": {"test:cube.packed": "5 (byte)"}},
               "culled": {"status": "inferred", "rules": [{"kind": "packed-bits", "mask": 4, "base": 5, "inverted": true, "nbt": "byte", "tags": [], "wire": "test:cube.packed"}],
                          "whenAbsent": {"test:cube.packed": "5 (byte)"}},
               "visible": {"status": "inferred", "rules": [{"kind": "molang", "nbt": "string", "tags": [], "wire": "test:cube.visible"}]},
               "bones": {"status": "inferred", "rules": [{"kind": "molang", "nbt": "string", "tags": [], "wire": "test:cube.bones{}"}]},
               "locked": {"status": "inferred", "rules": [{"kind": "hash-ordered-set", "nbt": "string", "tags": [], "wire": "test:cube.locked[]"}]}
             }}"""));
        String wire = ComponentGenerator.generate(in).get("dev/opencollab/paletter/internal/component/BlockWire.java");
        String cube = wire.substring(wire.indexOf("private static void cube("));
        return cube.substring(0, cube.indexOf("\n    }\n") + 1);
    }

    @Test
    void packedBitsSetOrClearTheirBitInWhatTheLeafHolds() {
        String cube = cube();
        // the leaf's current value, or the base of the fields at their default; then the bit, whichever order the fields go in
        assertTrue(cube.contains("out.get(\"test:cube.packed\") instanceof Number"), cube);
        assertTrue(cube.contains("? n") && cube.contains(".intValue() : 5;"), cube);
        assertTrue(cube.contains(" = c.dimming() ? bits") && cube.contains("| 1 : bits") && cube.contains("& ~1"), cube);
        assertTrue(cube.contains("| 2 : bits") && cube.contains("& ~2"), cube);
        // a bit that false sets
        assertTrue(cube.contains(" = !c.culled() ? bits") && cube.contains("| 4 : bits") && cube.contains("& ~4"), cube);
        assertTrue(cube.contains("out.put(\"test:cube.packed\", Wire.toByte(bits"), cube);
    }

    @Test
    void molangSendsTheExpressionNormalisedForAFieldAndForEveryKeyOfAMap() {
        String cube = cube();
        assertTrue(cube.contains("out.put(\"test:cube.visible\", Wire.molang("), cube);
        assertTrue(cube.contains("out.put(\"test:cube.bones.\" + x") && cube.contains("Wire.molang(x") && cube.contains(".getValue())"), cube);
    }

    @Test
    void hashOrderedSetSendsTheNamesOrderedByTheirHash() {
        String cube = cube();
        assertTrue(cube.contains("out.put(\"test:cube.locked\", Wire.strings(Wire.byNameHash("), cube);
    }
}
