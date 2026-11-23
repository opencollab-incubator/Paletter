package dev.opencollab.paletter.tools.behaviour;

import dev.opencollab.paletter.internal.behaviour.vanilla.BehaviourData;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated behaviour classes stay well inside the JVM's limits, so data that grows fails here and not when the classes
 * load: at most half of the 64 KB of bytecode per method, and half of the 65,535 constant pool entries per class. Read from
 * the compiled classes, the class file format parsed by hand (JVMS 4.1 to 4.7).
 */
class GeneratedBehaviourLimitsTest {
    private static final Path ROOT = Path.of(System.getProperty("paletter.root", ".."));
    private static final int MAX_CODE = 32 * 1024;
    private static final int MAX_CONSTANTS = 32 * 1024;

    @Test
    void everyGeneratedClassIsWellInsideTheLimits() throws IOException {
        List<String> classes;
        try (Stream<Path> files = Files.list(ROOT.resolve(BehaviourCode.DIRECTORY))) {
            classes = files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".java")).map(f -> f.replace(".java", "")).sorted().toList();
        }
        assertTrue(classes.size() > 10, classes.toString());
        int largestCode = 0;
        int largestPool = 0;
        List<String> over = new ArrayList<>();
        for (String name : classes) {
            try (InputStream in = BehaviourData.class.getResourceAsStream(name + ".class")) {
                ClassSize size = ClassSize.read(new DataInputStream(in));
                largestCode = Math.max(largestCode, size.largestCode());
                largestPool = Math.max(largestPool, size.constants());
                if (size.largestCode() > MAX_CODE || size.constants() > MAX_CONSTANTS) {
                    over.add(name + ": " + size);
                }
            }
        }
        System.out.println("Largest method " + largestCode + " bytes, largest constant pool " + largestPool + " entries, of " + classes.size() + " classes");
        assertEquals(List.of(), over, "lower BehaviourCode.METHOD_STATEMENTS / METHOD_CHARS or BehaviourConstants.WEIGHT");
    }

    /** The constant pool count and the longest Code attribute of a class file. */
    record ClassSize(int constants, int largestCode) {
        static ClassSize read(DataInputStream in) throws IOException {
            in.readInt(); // magic
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++; // takes two entries
                    }
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("constant tag " + tag);
                }
            }
            in.skipNBytes(6); // access, this, super
            in.skipNBytes(2L * in.readUnsignedShort()); // interfaces
            int fields = in.readUnsignedShort();
            for (int i = 0; i < fields; i++) {
                in.skipNBytes(6);
                skipAttributes(in);
            }
            int largest = 0;
            int methods = in.readUnsignedShort();
            for (int i = 0; i < methods; i++) {
                in.skipNBytes(6);
                int attributes = in.readUnsignedShort();
                for (int a = 0; a < attributes; a++) {
                    String name = utf8[in.readUnsignedShort()];
                    int length = in.readInt();
                    if (name.equals("Code")) {
                        in.skipNBytes(4); // max stack, max locals
                        int code = in.readInt();
                        largest = Math.max(largest, code);
                        in.skipNBytes(length - 8);
                    } else {
                        in.skipNBytes(length);
                    }
                }
            }
            return new ClassSize(count, largest);
        }

        private static void skipAttributes(DataInputStream in) throws IOException {
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                in.skipNBytes(2);
                in.skipNBytes(in.readInt());
            }
        }
    }
}
