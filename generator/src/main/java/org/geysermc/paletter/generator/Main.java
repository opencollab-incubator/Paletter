package org.geysermc.paletter.generator;

import org.geysermc.paletter.PaletterPalettes;
import org.geysermc.paletter.palette.PaletteVersion;

import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        PaletterPalettes.init();

        Path sourceRoot = Path.of("..", "api", "src", "main", "java");
        List<Generator<?>> generators = List.of(
                new BlocksGenerator(),
                new ItemsGenerator(),
                new ItemComponentsGenerator(),
                new ItemTagsGenerator(),
                new BlockComponentsGenerator()
        );

        for (Generator<?> generator : generators) {
            Path written = generator.writeTo(sourceRoot, PaletteVersion.v26_50);
            System.out.println("Wrote " + written.toAbsolutePath().normalize());
        }
    }
}
