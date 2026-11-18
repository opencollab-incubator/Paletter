package org.geysermc.paletter.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockPropertyRangeTest {
    @Test
    void aRangeEndingBelowItsStartIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlockProperty.range("example:a", 5, 3));
        assertThrows(IllegalArgumentException.class, () -> BlockProperty.range("example:a", 5, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockProperty.range("example:a", 0, -1));
    }

    @Test
    void aRangeOfOneValueIsValid() {
        assertEquals(1, BlockProperty.range("example:a", 4, 4).values().size());
    }
}
