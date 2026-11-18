package dev.opencollab.paletter.internal;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/**
 * The vanilla canonical ids of one lineage mapped to those of another and back, computed once: {@code to[id]} is the id
 * of the same content in {@code reference}, {@code from[referenceId]} the id here, -1 where there is none.
 */
record Translation(Object reference, int[] to, int[] from) {
    static Translation of(Object reference, int size, int referenceSize, IntUnaryOperator referenceIdOf) {
        int[] to = new int[size];
        int[] from = new int[referenceSize];
        Arrays.fill(from, -1);
        for (int id = 0; id < size; id++) {
            int other = referenceIdOf.applyAsInt(id);
            to[id] = other;
            if (other != -1) {
                from[other] = id;
            }
        }
        return new Translation(reference, to, from);
    }
}
