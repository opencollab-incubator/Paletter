package dev.opencollab.paletter.behaviour;

import dev.opencollab.paletter.Paletter;
import dev.opencollab.paletter.data.DataVersion;
import dev.opencollab.paletter.data.PaletteDataSource;
import dev.opencollab.paletter.internal.behaviour.vanilla.BehaviourData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled behaviour values are generated classes ({@code internal.behaviour.vanilla}), loaded by the first
 * {@code VanillaBehaviour.bundled()}: a {@code Paletter} built with {@code behaviour(false)} never loads one. Checked in a
 * class loader of its own that loads the library's classes itself, so what other tests of this JVM loaded does not count.
 */
class BehaviourLoadingTest {
    private static final String PACKAGE = BehaviourData.class.getPackageName();

    /** Loads the library's classes itself (child first) and says which it loaded; everything else comes from the parent. */
    private static final class Isolated extends URLClassLoader {
        Isolated() throws URISyntaxException, IOException {
            super(urls(), BehaviourLoadingTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("dev.opencollab.paletter.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException e) {
                        return super.loadClass(name, resolve);
                    }
                }
                return loaded;
            }
        }

        List<String> loaded(List<String> names) {
            return names.stream().filter(name -> findLoadedClass(name) != null).toList();
        }
    }

    /** The library's classes and resources: where {@link Paletter} and the bundled {@code versions.txt} are. */
    private static URL[] urls() throws URISyntaxException, IOException {
        URL classes = Paletter.class.getProtectionDomain().getCodeSource().getLocation();
        URL versions = Paletter.class.getResource("/paletter/versions.txt");
        URL resources = versions.getProtocol().equals("file") ? Path.of(versions.toURI()).getParent().getParent().toUri().toURL() : classes;
        return new URL[] {classes, resources};
    }

    /** @return the generated classes, by binary name */
    private static List<String> generated() throws IOException, URISyntaxException {
        Path directory = Path.of(BehaviourData.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve(PACKAGE.replace('.', '/'));
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".class"))
                .map(f -> PACKAGE + "." + f.substring(0, f.length() - ".class".length())).sorted().toList();
        }
    }

    /** Builds a Paletter of the newest release in the loader, with behaviour on or off, and uses it. */
    private static void build(Isolated loader, boolean behaviour) throws ReflectiveOperationException {
        String newest = PaletteDataSource.bundled().versions().stream().filter(v -> !v.isPreview()).reduce((a, b) -> b).map(DataVersion::toString).orElseThrow();
        Predicate<Object> versions = version -> version.toString().equals(newest);
        Class<?> paletter = loader.loadClass(Paletter.class.getName());
        Object builder = paletter.getMethod("builder").invoke(null);
        builder.getClass().getMethod("behaviour", boolean.class).invoke(builder, behaviour);
        builder.getClass().getMethod("versions", Predicate.class).invoke(builder, versions);
        Object built = builder.getClass().getMethod("build").invoke(builder);
        assertEquals(1, ((List<?>) paletter.getMethod("versions").invoke(built)).size());
    }

    @Test
    void withoutBehaviourTheGeneratedClassesAreNeverLoaded() throws Exception {
        List<String> generated = generated();
        assertTrue(generated.size() > 10, generated.toString());
        try (Isolated off = new Isolated()) {
            build(off, false);
            assertTrue(off.loaded(List.of(Paletter.class.getName())).size() == 1, "the library was loaded by the isolated loader");
            assertEquals(List.of(), off.loaded(generated));
        }
        try (Isolated on = new Isolated()) {
            build(on, true);
            assertTrue(on.loaded(generated).contains(BehaviourData.class.getName()), "the build loads the values when they are on");
        }
    }
}
