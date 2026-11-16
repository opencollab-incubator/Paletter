package org.geysermc.paletter.internal.component;

import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.internal.data.VanillaData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireContextTest {
    @Test
    void registryIsBuiltOncePerData() {
        Paletter paletter = Paletter.bundled();
        VanillaData data = paletter.vanillaData(paletter.latest());
        WireContext.Registry registry = WireContext.registry(data);
        assertSame(registry, WireContext.registry(data));
        assertTrue(registry.itemExists("apple"));
        assertNotNull(registry.blockStates("minecraft:stone"));
        assertNull(registry.blockStates("test:nothing"));
    }
}
