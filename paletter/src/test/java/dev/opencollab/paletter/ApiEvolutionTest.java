package dev.opencollab.paletter;

import dev.opencollab.paletter.creative.CreativeNode;
import dev.opencollab.paletter.item.component.RenderOffsets;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Bedrock defines stays open (docs/api.md, "Evolution"): the component packages have no public records but the shapes
 * fixed by definition, no enums of documented values, and every sealed type Bedrock adds variants to has its sentinel.
 */
class ApiEvolutionTest {
    /** The packages of the generated components and the hand-written wire-only ones. */
    private static final Set<String> COMPONENT_PACKAGES = Set.of("dev.opencollab.paletter.item.component", "dev.opencollab.paletter.block.component");
    /** Enums in the component packages: a closed legacy set Bedrock no longer extends (retired from the add-on format). */
    private static final Set<Class<?>> ALLOWED_ENUMS = Set.of(RenderOffsets.Preset.class);
    /** The sealed types Bedrock adds variants to outside the component packages. */
    private static final List<Class<?>> PROTOCOL_SEALED = List.of(CreativeNode.class);

    private static List<Class<?>> componentTypes() throws Exception {
        return ApiBoundaryTest.apiTypes().stream().filter(t -> COMPONENT_PACKAGES.contains(t.getPackageName())).toList();
    }

    /** The class that keeps a sealed type open: not public, final, named {@code Future<Type>}, no constructor but a private one. */
    private static boolean isSentinel(Class<?> sealed, Class<?> permitted) {
        return !Modifier.isPublic(permitted.getModifiers()) && Modifier.isFinal(permitted.getModifiers())
            && permitted.getSimpleName().equals("Future" + sealed.getSimpleName())
            && Arrays.stream(permitted.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers()));
    }

    /** A form of a union: a record implementing a sealed interface that has a sentinel (its shape is the one value of the form). */
    private static boolean isUnionForm(Class<?> type) {
        return Arrays.stream(type.getInterfaces()).anyMatch(i -> i.isSealed()
            && Arrays.stream(i.getPermittedSubclasses()).anyMatch(p -> isSentinel(i, p)));
    }

    @Test
    void noPublicRecordInTheComponentPackagesButVectorsAndUnionForms() throws Exception {
        List<String> records = new ArrayList<>();
        int allowed = 0;
        for (Class<?> type : componentTypes()) {
            if (type.isRecord()) {
                if (type.getSimpleName().equals("Vec3") || isUnionForm(type)) {
                    allowed++;
                } else {
                    records.add(type.getName());
                }
            }
        }
        assertEquals(List.of(), records, "a component or one of its objects is a record: make it a final class with a builder");
        assertTrue(allowed >= 10, "only " + allowed + " union forms found");
    }

    @Test
    void everyComponentValueSetIsAnOpenValueClass() throws Exception {
        List<String> enums = new ArrayList<>();
        int open = 0;
        for (Class<?> type : componentTypes()) {
            if (type.isEnum() && !ALLOWED_ENUMS.contains(type)) {
                enums.add(type.getName());
            }
            if (Arrays.stream(type.getMethods()).anyMatch(m -> m.getName().equals("isKnown"))) {
                open++;
                assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
                assertEquals(type, type.getMethod("of", String.class).getReturnType(), type.getName());
            }
        }
        assertEquals(List.of(), enums, "documented values are open value classes, not enums");
        assertTrue(open > 20, "only " + open + " open value classes found");
    }

    @Test
    void everyProtocolSealedTypeHasItsSentinel() throws Exception {
        List<Class<?>> sealed = new ArrayList<>(PROTOCOL_SEALED);
        componentTypes().stream().filter(Class::isSealed).forEach(sealed::add);
        assertTrue(sealed.size() > 10, sealed.toString());
        List<String> missing = new ArrayList<>();
        for (Class<?> type : sealed) {
            long sentinels = Arrays.stream(type.getPermittedSubclasses()).filter(p -> isSentinel(type, p)).count();
            if (sentinels != 1) {
                missing.add(type.getName() + " has " + sentinels);
            }
        }
        assertEquals(List.of(), missing, "a sealed type Bedrock adds variants to permits one Future<Type> sentinel");
    }
}
