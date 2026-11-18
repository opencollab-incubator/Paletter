package dev.opencollab.paletter.tools.bds;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BdsInventoryTest {
    @Test
    void readsBindings() {
        // strings of the Linux bedrock_server 1.26.63.1
        assertArrayEquals(new String[]{"ScriptModuleMinecraft", "ScriptBlock", "get isSolid"}, BdsInventory.binding(
            "N9Scripting10Reflection20MemberPropertyGetterIN21ScriptModuleMinecraft11ScriptBlockELNS0_12ReturnPolicyE0EXadL_ZNKS3_7isSolidEvEEEE"));
        assertArrayEquals(new String[]{"ScriptModuleMinecraft", "ScriptBlockPermutation", "static resolve()"}, BdsInventory.binding(
            "N9Scripting10Reflection14StaticFunctionIXadL_ZN21ScriptModuleMinecraft22ScriptBlockPermutation7resolveENS_17WeakLifetimeScopeERKNSt3__112"
                + "basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEEELb0EEE"));
        assertArrayEquals(new String[]{"ScriptModuleMinecraft", "ScriptActorType", "get getId"}, BdsInventory.binding(
            "N9Scripting10Reflection20MemberPropertyGetterIN21ScriptModuleMinecraft15ScriptActorTypeELNS0_12ReturnPolicyE1EXadL_ZNKS3_5getIdEvEEEE"));
        // the editor's namespace and lambdas are not read
        assertNull(BdsInventory.binding("N9Scripting10Reflection14MemberFunctionIXadL_ZNK6Editor12ScriptModule19ScriptProjectRegion12isSolidBlockERK4Vec3EEEE"));
        assertNull(BdsInventory.binding("N9Scripting10Reflection20LambdaPropertyGetterIN6Editor12ScriptModule26ScriptDataStoreAfterEventsE"));
    }

    @Test
    void inventoryAndDiff() throws IOException {
        List<String> found = new ArrayList<>();
        byte[] binary = ("\0\0minecraft:friction\0" + "24BlockFrictionDescription\0" + "25BlockContainerDescription\0value\0"
            + "25MaxStackSizeItemComponent\0" + "N9Scripting10Reflection20MemberPropertyGetterIN21ScriptModuleMinecraft11ScriptBlockELNS0_12"
            + "ReturnPolicyE0EXadL_ZNKS3_7isSolidEvEEEE\0").getBytes(StandardCharsets.US_ASCII);
        BdsInventory.scan(new ByteArrayInputStream(binary), found::add);
        JsonObject mappings = JsonParser.parseString("{\"components\": {\"block:minecraft:friction\": {\"fields\": {\"value\": {}, "
            + "\"gone\": {\"unknownToBds\": true}}}}}").getAsJsonObject();
        JsonObject after = BdsInventory.inventory("1.0.0.1", BdsInventory.Strings.of(found), mappings);
        assertEquals("minecraft:friction", after.getAsJsonObject("blockComponents").getAsJsonObject("BlockFrictionDescription").get("name").getAsString());
        assertEquals("[\"value\"]", after.getAsJsonObject("blockComponents").getAsJsonObject("BlockFrictionDescription").get("keys").toString());
        assertEquals(true, after.getAsJsonObject("blockComponents").getAsJsonObject("BlockContainerDescription").get("name").isJsonNull());
        assertEquals("[\"get isSolid\"]", after.getAsJsonObject("scriptApi").getAsJsonObject("ScriptModuleMinecraft").get("ScriptBlock").toString());

        JsonObject before = after.deepCopy();
        before.getAsJsonObject("scriptApi").getAsJsonObject("ScriptModuleMinecraft").getAsJsonArray("ScriptBlock").set(0,
            new com.google.gson.JsonPrimitive("get isAir"));
        before.add("itemComponents", new com.google.gson.JsonArray());
        assertEquals(List.of("+ Script API ScriptModuleMinecraft ScriptBlock get isSolid", "+ item component MaxStackSizeItemComponent",
            "- Script API ScriptModuleMinecraft ScriptBlock get isAir"), BdsInventory.diff(before, after));
    }

    @Test
    void snakeCase() {
        assertEquals("destructible_by_mining", BdsInventory.snake("DestructibleByMining"));
    }
}
