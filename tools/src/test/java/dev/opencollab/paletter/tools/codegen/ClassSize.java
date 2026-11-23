package dev.opencollab.paletter.tools.codegen;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The constant pool count and the longest Code attribute of a class file, the class file format parsed by hand (JVMS 4.1
 * to 4.7): what the limits tests of the generated data classes check, so data that grows fails a test and not class loading.
 */
public record ClassSize(int constants, int largestCode) {
    /** At most half of the 64 KB of bytecode per method. */
    public static final int MAX_CODE = 32 * 1024;
    /** At most half of the 65,535 constant pool entries per class. */
    public static final int MAX_CONSTANTS = 32 * 1024;

    /**
     * @param anchor a class of the package, whose loader finds the others
     * @return the classes of {@code names} over {@link #MAX_CODE} or {@link #MAX_CONSTANTS}, and prints the largest of each
     */
    public static List<String> over(Class<?> anchor, List<String> names) throws IOException {
        int largestCode = 0;
        int largestPool = 0;
        List<String> over = new ArrayList<>();
        for (String name : names) {
            try (InputStream in = anchor.getResourceAsStream(name + ".class")) {
                if (in == null) {
                    throw new IOException("No compiled class " + name + " beside " + anchor.getName());
                }
                ClassSize size = read(new DataInputStream(in));
                largestCode = Math.max(largestCode, size.largestCode());
                largestPool = Math.max(largestPool, size.constants());
                if (size.largestCode() > MAX_CODE || size.constants() > MAX_CONSTANTS) {
                    over.add(name + ": " + size);
                }
            }
        }
        System.out.println(anchor.getPackageName() + ": largest method " + largestCode + " bytes, largest constant pool " + largestPool
            + " entries, of " + names.size() + " classes");
        return over;
    }

    public static ClassSize read(DataInputStream in) throws IOException {
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
