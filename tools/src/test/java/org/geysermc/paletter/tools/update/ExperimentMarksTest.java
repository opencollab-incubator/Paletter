package org.geysermc.paletter.tools.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.paletter.internal.data.ExperimentContent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The experiment marks of the bundled versions, from the experiment records of the BDS captures. */
class ExperimentMarksTest {

    @Test
    void marksAreWhatCurrentTogglesAdd() {
        JsonObject record = JsonParser.parseString("""
            {
              "bdsVersion": "1.0.0", "protocol": 100,
              "toggles": {
                "drop": {"status": "current", "content": {
                  "itemsAdded": ["test:b"], "itemsChanged": ["test:changed"], "scriptItemsAdded": ["test:a", "test:b"],
                  "blocksAdded": ["test:block"], "dataDrivenBlocks": ["test:driven", "test:block"],
                  "creativeAdded": ["test:old", "test:first", "test:potion:5", "test:fresh_potion:5"]}},
                "empty": {"status": "current", "content": {"itemsAdded": [], "blocksAdded": [], "creativeAdded": []}},
                "retired": {"status": "retired"},
                "gone": {"status": "retired", "content": {"itemsAdded": ["test:ignored"]}}
              }
            }
            """).getAsJsonObject();
        Map<String, ExperimentContent> marks = ExperimentMarks.marks(record, Set.of("test:old", "test:potion"));
        assertEquals(List.of("drop"), List.copyOf(marks.keySet()));
        ExperimentContent drop = marks.get("drop");
        // Added, changed and Script API items, and the items the toggle puts into the creative inventory first (not the
        // potion variants of an item that was there)
        assertEquals(List.of("test:a", "test:b", "test:changed", "test:first", "test:fresh_potion"), drop.items());
        assertEquals(List.of("test:block", "test:driven"), drop.blocks());
        assertEquals(List.of("test:old", "test:first", "test:potion:5", "test:fresh_potion:5"), drop.creative());
    }

    /** The committed marks of every bundled version are what its captures give (run {@code experiment-marks} to refresh). */
    @Test
    void committedMarksAreWhatTheCapturesGive() throws IOException {
        Path root = Path.of(System.getProperty("paletter.root", ".."));
        assertEquals(List.of(), ExperimentMarks.differing(root));
    }
}
