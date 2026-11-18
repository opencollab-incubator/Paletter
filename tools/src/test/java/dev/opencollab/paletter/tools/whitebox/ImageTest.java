package dev.opencollab.paletter.tools.whitebox;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads a small ELF file built here: a class with two virtual functions, one of which returns a static string, as the
 * Linux build of BDS lays it out.
 */
class ImageTest {
    private static final long RODATA = 0x1000;
    private static final long TEXT = 0x2000;
    private static final long TABLES = 0x3000;
    /** The string object: libc++ keeps a short string in place, the first byte is twice the length. */
    private static final long NAME = RODATA + 0x20;

    @Test
    void readsSectionsPointersFunctionsAndStrings() {
        Image image = new Image(elf());
        assertFalse(image.windows);
        assertEquals(".text", image.sectionOf(TEXT + 3).name());
        assertTrue(image.isCode(TEXT));
        assertFalse(image.isCode(RODATA));
        assertTrue(image.isWritable(0x7000));

        // The virtual table: two words that are not code pointers, then the two functions
        assertEquals(TEXT, image.pointer(TABLES + 0x10));
        assertEquals(TEXT + 0x10, image.pointer(TABLES + 0x18));
        assertEquals(0, image.pointer(TABLES + 8));
        assertArrayEquals(new long[]{TABLES + 0x10, 2}, image.table(TABLES + 0x18, null));
        assertNull(image.className(TABLES + 0x10));

        assertEquals(TEXT, image.functionOf(TEXT + 5));
        assertEquals(TEXT + 8, image.functionEnd(TEXT));
        assertEquals(0, image.functionOf(TEXT + 9));
        assertEquals(TEXT + 0x10, image.functionOf(TEXT + 0x11));

        assertEquals("minecraft:geometry", image.cString(RODATA + 1));
        assertNull(image.cString(RODATA + 2), "the tail of a literal is not a literal");
        assertEquals(List.of(RODATA + 1), image.literals("minecraft:geometry"));
        assertEquals("minecraft:friction", image.constantString(NAME));
        assertNull(image.constantString(RODATA + 1));
    }

    @Test
    void findsStaticGettersAndReferences() {
        Image image = new Image(elf());
        List<long[]> getters = image.staticGetters();
        assertEquals(1, getters.size());
        assertArrayEquals(new long[]{TEXT, NAME}, getters.getFirst());
        List<Image.Reference> references = image.references(new long[]{NAME}, 1);
        assertEquals(List.of(new Image.Reference(TEXT + 2, NAME)), references);
        assertTrue(image.references(new long[]{NAME + 8}, 1).isEmpty());
        assertEquals(1, image.references(new long[]{NAME - 4}, 8).size(), "a reference into an object of 8 bytes");
        List<Long> pointers = new ArrayList<>();
        image.pointers((at, to) -> pointers.add(at));
        assertEquals(List.of(TABLES + 0x10, TABLES + 0x18), pointers);
    }

    @Test
    void rejectsOtherFiles() {
        assertThrows(IllegalArgumentException.class, () -> new Image(new byte[0x100]));
    }

    /** Sections: .rodata, .text, .data.rel.ro, .rela.dyn, .eh_frame_hdr, .eh_frame, .data and the section names. */
    private static byte[] elf() {
        byte[] rodata = new byte[0x40];
        put(rodata, 1, "minecraft:geometry");
        rodata[0x20] = (byte) ("minecraft:friction".length() * 2);
        put(rodata, 0x21, "minecraft:friction");

        byte[] text = new byte[0x20];
        // lea rax, [rip + NAME]; ret
        text[0] = 0x48;
        text[1] = (byte) 0x8d;
        text[2] = 0x05;
        ByteBuffer.wrap(text).order(ByteOrder.LITTLE_ENDIAN).putInt(3, (int) (NAME - (TEXT + 7)));
        text[7] = (byte) 0xc3;
        // mov al, 1; ret
        text[0x10] = (byte) 0xb0;
        text[0x11] = 1;
        text[0x12] = (byte) 0xc3;

        byte[] tables = new byte[0x20];
        ByteBuffer relocations = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        relocations.putLong(TABLES + 0x10).putLong(8).putLong(TEXT);
        relocations.putLong(TABLES + 0x18).putLong(8).putLong(TEXT + 0x10);

        // The unwind tables: a sorted table of (function, its entry), and per entry the size of the function at +12
        ByteBuffer frames = ByteBuffer.allocate(0x20).order(ByteOrder.LITTLE_ENDIAN);
        frames.putInt(12, 8).putInt(0x10 + 12, 3);
        ByteBuffer header = ByteBuffer.allocate(12 + 16).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 1).put((byte) 0x1b).put((byte) 0x03).put((byte) 0x3b).putInt(0).putInt(2);
        header.putInt((int) (TEXT - 0x5000)).putInt(0x6000 - 0x5000);
        header.putInt((int) (TEXT + 0x10 - 0x5000)).putInt(0x6010 - 0x5000);

        String[] names = {"", ".rodata", ".text", ".data.rel.ro", ".rela.dyn", ".eh_frame_hdr", ".eh_frame", ".data", ".shstrtab"};
        long[] addresses = {0, RODATA, TEXT, TABLES, 0x4000, 0x5000, 0x6000, 0x7000, 0};
        byte[][] contents = {new byte[0], rodata, text, tables, relocations.array(), header.array(), frames.array(), new byte[8],
            (String.join("\0", names) + "\0").getBytes(StandardCharsets.US_ASCII)};

        int size = 64;
        for (byte[] content : contents) {
            size += content.length;
        }
        ByteBuffer file = ByteBuffer.allocate(size + names.length * 64).order(ByteOrder.LITTLE_ENDIAN);
        file.put(new byte[]{0x7f, 'E', 'L', 'F', 2, 1, 1});
        file.putLong(0x28, size).putShort(0x3a, (short) 64).putShort(0x3c, (short) names.length).putShort(0x3e, (short) (names.length - 1));
        int offset = 64;
        int name = 0;
        for (int i = 0; i < names.length; i++) {
            file.put(offset, contents[i]);
            int sectionHeader = size + i * 64;
            file.putInt(sectionHeader, name).putInt(sectionHeader + 4, 1).putLong(sectionHeader + 8, names[i].equals(".text") ? 4 : 0);
            file.putLong(sectionHeader + 0x10, addresses[i]).putLong(sectionHeader + 0x18, offset).putLong(sectionHeader + 0x20, contents[i].length);
            offset += contents[i].length;
            name += names[i].length() + 1;
        }
        return file.array();
    }

    private static void put(byte[] target, int at, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, at, bytes.length);
    }
}
