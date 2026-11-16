package org.geysermc.paletter.internal.component;

import org.geysermc.paletter.Paletter;
import org.geysermc.paletter.internal.data.VanillaData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

    /** The cache is by instance, not by equality: equal data of another instance gets a registry of its own. */
    @Test
    void equalDataOfAnotherInstanceGetsItsOwnRegistry() {
        Paletter paletter = Paletter.bundled();
        VanillaData data = paletter.vanillaData(paletter.latest());
        VanillaData copy = data.withItems(data.items());
        assertEquals(data, copy);
        assertNotSame(data, copy);
        WireContext.Registry registry = WireContext.registry(data);
        WireContext.Registry other = WireContext.registry(copy);
        assertNotSame(registry, other);
        assertSame(other, WireContext.registry(copy));
        assertSame(registry, WireContext.registry(data));
        assertTrue(other.itemExists("apple"));
    }
}
