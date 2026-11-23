package dev.opencollab.paletter.tools.blocks;

import dev.opencollab.paletter.internal.vanilla.BlockDefinitions;
import dev.opencollab.paletter.internal.vanilla.VanillaBlocks;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compiles generated sources and runs their {@code load} in a class loader of their own: the generated package comes from
 * the compiled files, everything else (the runtime) from the test's class path.
 */
public final class CompiledBlocks {
    private CompiledBlocks() {
    }

    /** @return the class loader of the compiled files; close it when done */
    public static URLClassLoader compile(Map<String, String> files, Path directory, String generatedPackage) throws Exception {
        Path sources = Files.createDirectories(directory.resolve("sources"));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-nowarn", "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString()));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = sources.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
            arguments.add(path.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        if (compiler.run(null, null, errors, arguments.toArray(String[]::new)) != 0) {
            throw new AssertionError("The generated sources do not compile:\n" + errors);
        }
        String generated = generatedPackage + ".";
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, CompiledBlocks.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(generated)) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : findClass(name);
                }
            }
        };
    }

    /** @return the definitions the generated block code builds */
    static Map<String, BlockDefinitions> load(Map<String, String> files, Path directory) throws Exception {
        try (URLClassLoader loader = compile(files, directory, BlockCode.PACKAGE)) {
            VanillaBlocks.Versions versions = new VanillaBlocks.Versions();
            loader.loadClass(BlockCode.PACKAGE + ".BlockData").getMethod("load", VanillaBlocks.Versions.class).invoke(null, versions);
            return versions.versions();
        }
    }
}
