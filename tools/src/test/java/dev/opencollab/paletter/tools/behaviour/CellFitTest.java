package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.tools.behaviour.CellFit.ByProperty;
import dev.opencollab.paletter.tools.behaviour.CellFit.Constant;
import dev.opencollab.paletter.tools.behaviour.CellFit.Table;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link CellFit}: a block's values as simply as they are, keyed by property values. */
class CellFitTest {
    /** The states of a block with a bit {@code open} and an int {@code age} 0 to 2, every combination. */
    private static List<Map<String, Object>> states() {
        List<Map<String, Object>> states = new ArrayList<>();
        for (boolean open : new boolean[] {false, true}) {
            for (int age = 0; age <= 2; age++) {
                states.add(Map.of("open", open, "age", age));
            }
        }
        return states;
    }

    private static List<Object> values(Object... values) {
        return Arrays.asList(values);
    }

    @Test
    void theSameValueForEveryStateIsAConstant() {
        assertEquals(new Constant(1.5f), CellFit.fit(states(), values(1.5f, 1.5f, 1.5f, 1.5f, 1.5f, 1.5f)));
        assertEquals(new Constant(null), CellFit.fit(states(), values(null, null, null, null, null, null)));
    }

    @Test
    void aValueOfOnePropertyIsAMapOverItsValues() {
        // by age only: the open bit does not matter
        assertEquals(new ByProperty("age", List.of(0, 1, 2), values(0f, 0.5f, 1f)),
            CellFit.fit(states(), values(0f, 0.5f, 1f, 0f, 0.5f, 1f)));
        // unknown is a value too
        assertEquals(new ByProperty("open", List.of(false, true), values(7, null)), CellFit.fit(states(), values(7, 7, 7, null, null, null)));
    }

    @Test
    void aValueOfSeveralPropertiesIsATableWithTheLastFastest() {
        Table table = (Table) CellFit.fit(states(), values(1, 2, 3, 4, 5, 6));
        assertEquals(List.of("age", "open"), table.properties());
        assertEquals(List.of(List.of(0, 1, 2), List.of(false, true)), table.domains());
        assertEquals(values(1, 4, 2, 5, 3, 6), table.values());
        for (int i = 0; i < 6; i++) {
            assertEquals(i + 1, table.get(states().get(i)));
        }
    }

    @Test
    void whatMayBeAnythingDoesNotMakeAValueDependOnAProperty() {
        // the captures of the states with a value: one capture, so a constant
        assertEquals(new Constant(3), CellFit.fit(states(), values(3, CellFit.ANY, 3, CellFit.ANY, 3, 3)));
        // two captures by the open bit; the others may be anything
        assertEquals(new ByProperty("open", List.of(false, true), values(1, 2)),
            CellFit.fit(states(), values(1, CellFit.ANY, 1, 2, CellFit.ANY, 2)));
    }
}
