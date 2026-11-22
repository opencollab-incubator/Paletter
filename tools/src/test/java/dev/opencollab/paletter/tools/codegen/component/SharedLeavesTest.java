package dev.opencollab.paletter.tools.codegen.component;

import dev.opencollab.paletter.tools.codegen.component.ApiModel.Comp;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Kind;
import dev.opencollab.paletter.tools.codegen.component.ApiModel.Window;
import dev.opencollab.paletter.tools.codegen.component.ComponentGenerator.GenerationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static dev.opencollab.paletter.tools.codegen.component.ComponentGeneratorTest.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SharedLeaves}: the owner of a leaf several components write, and the encoders that keep its value. */
class SharedLeavesTest {
    private static Comp item(String name, String rules) {
        Comp c = new Comp();
        c.id = "item:" + name;
        c.name = name;
        c.kind = Kind.ITEM;
        c.windows.add(new Window(Integer.MIN_VALUE, Integer.MAX_VALUE,
            json("{\"status\": \"sent\", \"fields\": {\"value\": {\"status\": \"inferred\", \"rules\": " + rules + "}}}")));
        return c;
    }

    private static final String IDENTITY = "[{\"kind\": \"identity\", \"nbt\": \"int\", \"wire\": \"item_properties.max\"}]";
    private static final String TABLE = "[{\"kind\": \"table\", \"table\": {\"a\": \"1\", \"b\": \"64\"}, \"nbt\": \"int\", \"wire\": \"item_properties.max\"}]";

    @Test
    void theComponentThatCarriesTheValueOwnsTheLeaf() {
        ApiModel model = new ApiModel();
        model.components.add(item("test:max", IDENTITY));
        model.components.add(item("test:wear", TABLE));
        assertEquals(Map.of("item_properties.max", "item:test:max"), SharedLeaves.owners(model, Kind.ITEM));
    }

    @Test
    void aLeafOfOneComponentIsNotShared() {
        ApiModel model = new ApiModel();
        model.components.add(item("test:wear", TABLE));
        assertEquals(Map.of(), SharedLeaves.owners(model, Kind.ITEM));
    }

    @Test
    void aLeafWithoutASingleOwnerFails() {
        ApiModel two = new ApiModel();
        two.components.add(item("test:max", IDENTITY));
        two.components.add(item("test:other", IDENTITY));
        assertTrue(assertThrows(GenerationException.class, () -> SharedLeaves.owners(two, Kind.ITEM)).getMessage().contains("item_properties.max"));
        ApiModel none = new ApiModel();
        none.components.add(item("test:wear", TABLE));
        none.components.add(item("test:other", TABLE));
        assertThrows(GenerationException.class, () -> SharedLeaves.owners(none, Kind.ITEM));
    }

    /** The committed model: max_stack_size and allow_off_hand own their item_properties leaves, wearable's table defers. */
    @Test
    void theCommittedEncodersKeepTheOwnersValue() throws IOException {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        String wire = ComponentGenerator.generate(ComponentGenerator.read(root.resolve("data/model"), Main.version(root), Main.context(root)))
            .get("dev/opencollab/paletter/internal/component/ItemWire.java");
        assertTrue(wire.contains("out.own(\"item_properties.max_stack_size\", Wire.toInt(v0));"), wire);
        assertTrue(wire.contains("out.own(\"item_properties.allow_off_hand\", Wire.bool(v0));"), wire);
        assertTrue(wire.contains("case \"slot.armor.head\" -> out.putUnowned(\"item_properties.max_stack_size\", 1);"), wire);
        assertTrue(wire.contains("case \"slot.weapon.offhand\" -> out.putUnowned(\"item_properties.allow_off_hand\", (byte) 1);"), wire);
    }
}
