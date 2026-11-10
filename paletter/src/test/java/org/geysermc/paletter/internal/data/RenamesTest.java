package org.geysermc.paletter.internal.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RenamesTest {
    private static final Renames RENAMES = Renames.parse(List.of(
        "item test:a test:b", "item test:b test:c", "item test:loop test:loop2", "item test:loop2 test:loop"));

    @Test
    void anIdentifierThatWasNeverRenamedHasNoNewName() {
        assertNull(RENAMES.item("test:c"));
        assertNull(RENAMES.item("minecraft:apple"));
    }

    @Test
    void aRenameIsFollowedToTheEnd() {
        assertEquals("test:c", RENAMES.item("test:b"));
        assertEquals("test:c", RENAMES.item("test:a"));
    }

    @Test
    void aCycleEnds() {
        assertEquals("test:loop", RENAMES.item("test:loop"));
    }
}
