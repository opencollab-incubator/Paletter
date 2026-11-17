package org.geysermc.paletter.tools.whitebox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A {@code bedrock_server} binary in memory: the x86-64 ELF of the Linux build or the PE of the Windows build. It knows
 * the sections, where functions start and end (from the unwind tables, so no symbols are needed), which data words are
 * pointers, and how each platform's standard library lays out a {@code std::string}.
 * <p>
 * Addresses are virtual addresses throughout.
 */
final class Image {
    /**
     * A part of the file mapped at {@code address}. The file holds its first {@code fileSize} bytes at {@code offset}; the
     * rest (all of .bss) is memory that starts as zero.
     */
    record Section(String name, long address, int offset, long size, long fileSize, boolean code) {
        boolean contains(long va) {
            return va >= address && va < address + size;
        }
    }

    /** One place in the code that refers to {@code target}. */
    record Reference(long site, long target) {
    }

    final boolean windows;
    final List<Section> sections = new ArrayList<>();
    private final byte[] bytes;
    private final ByteBuffer little;
    /** Function bounds, sorted by start. Windows leaves out leaf functions: they have no unwind entry. */
    private long[] functionStart = {};
    private long[] functionEnd = {};
    /** Linux: where the loader writes a pointer (relative relocations), sorted, and the address each gets. */
    private long[] pointerAt = {};
    private long[] pointerTo = {};
    private long imageBase;
    private long codeStart = Long.MAX_VALUE;
    private long codeEnd;

    Image(byte[] bytes) {
        this.bytes = bytes;
        this.little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length > 0x40 && bytes[0] == 'M' && bytes[1] == 'Z') {
            windows = true;
            readPe();
        } else if (bytes.length > 0x40 && bytes[0] == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F') {
            windows = false;
            readElf();
        } else {
            throw new IllegalArgumentException("Neither an ELF nor a PE file");
        }
        for (Section section : sections) {
            if (section.code()) {
                codeStart = Math.min(codeStart, section.address());
                codeEnd = Math.max(codeEnd, section.address() + section.size());
            }
        }
    }

    /** Reads the server binary from a BDS zip without extracting it, or a binary file as it is. */
    static Image load(Path file) throws IOException {
        if (!file.getFileName().toString().endsWith(".zip")) {
            return new Image(Files.readAllBytes(file));
        }
        try (InputStream in = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("bedrock_server") || entry.getName().equals("bedrock_server.exe")) {
                    return new Image(zip.readAllBytes());
                }
            }
        }
        throw new IOException(file + " has no bedrock_server");
    }

    // ----- ELF ------------------------------------------------------------------------------------------------------

    private void readElf() {
        long sectionHeaders = little.getLong(0x28);
        int entrySize = little.getShort(0x3a) & 0xffff;
        int count = little.getShort(0x3c) & 0xffff;
        int names = little.getShort(0x3e) & 0xffff;
        long nameTable = little.getLong((int) (sectionHeaders + (long) names * entrySize + 0x18));
        for (int i = 0; i < count; i++) {
            int header = (int) (sectionHeaders + (long) i * entrySize);
            long address = little.getLong(header + 0x10);
            if (address == 0) {
                continue;
            }
            int type = little.getInt(header + 4);
            boolean code = (little.getLong(header + 8) & 4) != 0;
            String name = cStringAtOffset((int) (nameTable + little.getInt(header)));
            long size = little.getLong(header + 0x20);
            sections.add(new Section(name, address, (int) little.getLong(header + 0x18), size, type == 8 ? 0 : size, code)); // 8: SHT_NOBITS
        }
        readElfRelocations();
        readElfFunctions();
    }

    /** {@code .rela.dyn}: the R_X86_64_RELATIVE entries are the pointers stored in data (vtables, type info, tables). */
    private void readElfRelocations() {
        Section section = section(".rela.dyn");
        int entries = (int) (section.size() / 24);
        long[] at = new long[entries];
        long[] to = new long[entries];
        int kept = 0;
        long previous = Long.MIN_VALUE;
        boolean sorted = true;
        for (int i = 0; i < entries; i++) {
            int entry = section.offset() + i * 24;
            if ((little.getLong(entry + 8) & 0xffffffffL) != 8) { // 8: R_X86_64_RELATIVE
                continue;
            }
            at[kept] = little.getLong(entry);
            to[kept] = little.getLong(entry + 16);
            sorted &= at[kept] > previous;
            previous = at[kept++];
        }
        if (!sorted) {
            throw new IllegalStateException(".rela.dyn is not sorted by address");
        }
        pointerAt = Arrays.copyOf(at, kept);
        pointerTo = Arrays.copyOf(to, kept);
    }

    /** {@code .eh_frame_hdr} is a sorted table of every function with unwind information: its start and its FDE. */
    private void readElfFunctions() {
        Section header = section(".eh_frame_hdr");
        Section frames = section(".eh_frame");
        int at = header.offset();
        if ((bytes[at + 2] & 0xff) != 0x03 || (bytes[at + 3] & 0xff) != 0x3b) {
            throw new IllegalStateException("Unexpected .eh_frame_hdr encoding");
        }
        int count = little.getInt(at + 8);
        functionStart = new long[count];
        functionEnd = new long[count];
        for (int i = 0; i < count; i++) {
            long start = header.address() + little.getInt(at + 12 + i * 8);
            long frame = header.address() + little.getInt(at + 16 + i * 8);
            // FDE: length, CIE pointer, start (relative), size
            long size = little.getInt((int) (frame - frames.address() + frames.offset()) + 12) & 0xffffffffL;
            functionStart[i] = start;
            functionEnd[i] = start + size;
        }
    }

    // ----- PE -------------------------------------------------------------------------------------------------------

    private void readPe() {
        int pe = little.getInt(0x3c);
        int count = little.getShort(pe + 6) & 0xffff;
        int optional = pe + 24;
        imageBase = little.getLong(optional + 24);
        int table = optional + (little.getShort(pe + 20) & 0xffff);
        for (int i = 0; i < count; i++) {
            int header = table + i * 40;
            String name = new String(bytes, header, 8, StandardCharsets.US_ASCII).replace("\0", "");
            long virtualSize = little.getInt(header + 8) & 0xffffffffL;
            long rawSize = little.getInt(header + 16) & 0xffffffffL;
            boolean code = (little.getInt(header + 36) & 0x20000000) != 0;
            sections.add(new Section(name, imageBase + (little.getInt(header + 12) & 0xffffffffL), little.getInt(header + 20),
                virtualSize, Math.min(virtualSize, rawSize), code));
        }
        // .pdata: begin, end and unwind data of every function that is not a leaf
        Section pdata = section(".pdata");
        int entries = (int) (pdata.size() / 12);
        long[] start = new long[entries];
        long[] end = new long[entries];
        int kept = 0;
        for (int i = 0; i < entries; i++) {
            long begin = little.getInt(pdata.offset() + i * 12) & 0xffffffffL;
            if (begin != 0) {
                start[kept] = imageBase + begin;
                end[kept++] = imageBase + (little.getInt(pdata.offset() + i * 12 + 4) & 0xffffffffL);
            }
        }
        functionStart = Arrays.copyOf(start, kept);
        functionEnd = Arrays.copyOf(end, kept);
    }

    // ----- Reading ---------------------------------------------------------------------------------------------------

    Section section(String name) {
        for (Section section : sections) {
            if (section.name().equals(name)) {
                return section;
            }
        }
        throw new IllegalStateException("No section " + name);
    }

    Section sectionOf(long va) {
        for (Section section : sections) {
            if (section.contains(va)) {
                return section;
            }
        }
        return null;
    }

    /** @return the file offset of an address, or -1 if the file has no bytes for it */
    int offset(long va) {
        Section section = sectionOf(va);
        return section == null || va - section.address() >= section.fileSize() ? -1 : (int) (va - section.address() + section.offset());
    }

    boolean isCode(long va) {
        return va >= codeStart && va < codeEnd;
    }

    /** @return whether the address is in memory the program can write: statics that are set up when the server starts */
    boolean isWritable(long va) {
        Section section = sectionOf(va);
        return section != null && (section.name().equals(".data") || section.name().equals(".bss"));
    }

    int u8(long va) {
        int offset = offset(va);
        return offset < 0 ? -1 : bytes[offset] & 0xff;
    }

    long u64(long va) {
        int offset = offset(va);
        return offset < 0 || offset + 8 > bytes.length ? 0 : little.getLong(offset);
    }

    long u32(long va) {
        int offset = offset(va);
        return offset < 0 || offset + 4 > bytes.length ? 0 : little.getInt(offset) & 0xffffffffL;
    }

    byte[] read(long va, int length) {
        int offset = offset(va);
        return offset < 0 || offset + length > bytes.length ? null : Arrays.copyOfRange(bytes, offset, offset + length);
    }

    /** @return the address a data word points to, or 0 if it holds no pointer */
    long pointer(long va) {
        if (windows) {
            long value = u64(va);
            return sectionOf(value) == null ? 0 : value;
        }
        int index = Arrays.binarySearch(pointerAt, va);
        return index < 0 ? 0 : pointerTo[index];
    }

    /** Calls {@code sink} with every data word that holds a pointer: where it is and what it points to. */
    void pointers(PointerSink sink) {
        if (!windows) {
            for (int i = 0; i < pointerAt.length; i++) {
                sink.accept(pointerAt[i], pointerTo[i]);
            }
            return;
        }
        Section data = section(".rdata");
        for (long va = data.address(); va + 8 <= data.address() + data.fileSize(); va += 8) {
            long value = little.getLong((int) (va - data.address() + data.offset()));
            if (isCode(value)) {
                sink.accept(va, value);
            }
        }
    }

    interface PointerSink {
        void accept(long at, long to);
    }

    /**
     * @return the printable ASCII string that starts at an address and ends with a NUL, or null. A string must start where
     * the bytes before it end (after a NUL), so the tail of a longer literal is not taken for a literal of its own
     */
    String cString(long va) {
        int offset = offset(va);
        if (offset <= 0 || !isLiteralSection(sectionOf(va)) || printable(bytes[offset - 1])) {
            return null;
        }
        return cStringAtOffset(offset);
    }

    /** String literals are in the read-only data, not in the tables the loader reads (symbol names). */
    private static boolean isLiteralSection(Section section) {
        return section.name().equals(".rodata") || section.name().equals(".rdata") || section.name().equals(".data");
    }

    private String cStringAtOffset(int offset) {
        int end = offset;
        while (end < bytes.length && end - offset < 4096 && printable(bytes[end])) {
            end++;
        }
        if (end == offset || end >= bytes.length || bytes[end] != 0) {
            return null;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static boolean printable(byte b) {
        return b >= 0x20 && b <= 0x7e;
    }

    /**
     * A {@code std::string} that the compiler initialised in the file itself (no code runs to set it up).
     * Linux (libc++): short strings are stored in place, first byte twice the length; longer ones point to a literal.
     * Windows (MSVC): 16 bytes in place, then length and capacity 15.
     *
     * @return its text, or null when the bytes are not such a string (set up at startup, or stored on the heap)
     */
    String constantString(long va) {
        byte[] raw = read(va, 32);
        if (raw == null) {
            return null;
        }
        int length;
        int from;
        if (windows) {
            ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.getLong(24) != 15 || buffer.getLong(16) > 15) {
                return null;
            }
            length = (int) buffer.getLong(16);
            from = 0;
        } else {
            if ((raw[0] & 1) != 0) {
                // On the heap at run time, so in the file the string object points to a literal: capacity, length, pointer
                String literal = cString(pointer(va + 16));
                return literal != null && literal.length() == ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getLong(8) ? literal : null;
            }
            if ((raw[0] & 0xff) > 44) {
                return null;
            }
            length = (raw[0] & 0xff) / 2;
            from = 1;
        }
        if (length == 0 || raw[from + length] != 0) {
            return null;
        }
        for (int i = 0; i < length; i++) {
            if (!printable(raw[from + i])) {
                return null;
            }
        }
        return new String(raw, from, length, StandardCharsets.US_ASCII);
    }

    /** @return the addresses of the string literals with exactly this text */
    List<Long> literals(String text) {
        byte[] needle = ("\0" + text + "\0").getBytes(StandardCharsets.US_ASCII);
        List<Long> found = new ArrayList<>();
        for (Section section : sections) {
            if (!isLiteralSection(section)) {
                continue;
            }
            int end = (int) (section.offset() + section.fileSize()) - needle.length;
            for (int i = section.offset(); i <= end; i++) {
                if (bytes[i] == 0 && bytes[i + 1] == needle[1] && matches(i, needle)) {
                    found.add(section.address() + (i + 1 - section.offset()));
                }
            }
        }
        return found;
    }

    private boolean matches(int offset, byte[] needle) {
        for (int i = 0; i < needle.length; i++) {
            if (bytes[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    // ----- Functions --------------------------------------------------------------------------------------------------

    /** @return the index of the function that contains an address, or -1 */
    private int functionIndex(long va) {
        int index = Arrays.binarySearch(functionStart, va);
        if (index < 0) {
            index = -index - 2;
        }
        return index >= 0 && va < functionEnd[index] ? index : -1;
    }

    /** @return the start of the function that contains an address, or 0 */
    long functionOf(long va) {
        int index = functionIndex(va);
        return index < 0 ? 0 : functionStart[index];
    }

    /** @return the end of the function that starts at an address, or 0 when the unwind tables do not list it */
    long functionEnd(long start) {
        int index = Arrays.binarySearch(functionStart, start);
        return index < 0 ? 0 : functionEnd[index];
    }

    /** @return the code of a function; a function the unwind tables do not list (a Windows leaf) gives its first bytes */
    byte[] code(long start) {
        long end = functionEnd(start);
        return read(start, end == 0 ? 64 : (int) Math.min(end - start, 1 << 20));
    }

    // ----- Scans over all code ---------------------------------------------------------------------------------------

    /**
     * Finds the instructions that address one of {@code targets} relative to the instruction pointer, which is how x86-64
     * code refers to all of its data. Nothing is decoded: every byte that could be the ModRM byte of such an operand is
     * tried and kept only if its target is wanted, so a chance hit needs four bytes that happen to add up to a wanted address.
     *
     * @param targets sorted addresses
     * @param span    a target matches from its address up to {@code span} bytes after it (1: only the address itself)
     */
    List<Reference> references(long[] targets, int span) {
        List<Reference> found = new ArrayList<>();
        if (targets.length == 0) {
            return found;
        }
        long lowest = targets[0];
        long highest = targets[targets.length - 1] + span;
        for (Section section : sections) {
            if (!section.code()) {
                continue;
            }
            int from = section.offset();
            int end = (int) (from + section.fileSize()) - 5;
            long delta = section.address() - from;
            for (int i = from; i < end; i++) {
                if ((bytes[i] & 0xc7) != 0x05) {
                    continue;
                }
                long target = i + 5 + delta + little.getInt(i + 1);
                if (target < lowest || target >= highest) {
                    continue;
                }
                int index = Arrays.binarySearch(targets, target);
                if (index < 0) {
                    index = -index - 2;
                }
                if (index >= 0 && target - targets[index] < span) {
                    found.add(new Reference(i + delta, target));
                }
            }
        }
        return found;
    }

    /**
     * Windows: the virtual tables, found as the addresses in read-only data that code refers to and that hold a code pointer.
     * (A Windows build without type information has nothing in front of a table to tell where it starts.)
     *
     * @return the sorted addresses
     */
    long[] codePointerTables() {
        Section data = section(".rdata");
        java.util.TreeSet<Long> found = new java.util.TreeSet<>();
        for (Section section : sections) {
            if (!section.code()) {
                continue;
            }
            int from = section.offset();
            int end = (int) (from + section.fileSize()) - 5;
            long delta = section.address() - from;
            for (int i = from; i < end; i++) {
                if ((bytes[i] & 0xc7) != 0x05) {
                    continue;
                }
                long target = i + 5 + delta + little.getInt(i + 1);
                if ((target & 7) == 0 && target >= data.address() && target - data.address() + 8 <= data.fileSize()
                    && isCode(little.getLong((int) (target - data.address() + data.offset())))) {
                    found.add(target);
                }
            }
        }
        return found.stream().mapToLong(Long::longValue).toArray();
    }

    /** @return where a byte sequence occurs in the code (keys that the compiler put into instructions as numbers) */
    List<Long> codeOccurrences(byte[] needle) {
        List<Long> found = new ArrayList<>();
        for (Section section : sections) {
            if (!section.code()) {
                continue;
            }
            int end = (int) (section.offset() + section.fileSize()) - needle.length;
            for (int i = section.offset(); i <= end; i++) {
                if (bytes[i] == needle[0] && matches(i, needle)) {
                    found.add(section.address() + (i - section.offset()));
                }
            }
        }
        return found;
    }

    /**
     * Functions that only return the address of a static: {@code lea rax, [rip + x]; ret}. This is what a virtual
     * {@code getName()} that returns a static string compiles to on both platforms.
     *
     * @return pairs of function address and the static's address
     */
    List<long[]> staticGetters() {
        List<long[]> found = new ArrayList<>();
        for (Section section : sections) {
            if (!section.code()) {
                continue;
            }
            long delta = section.address() - section.offset();
            int end = (int) (section.offset() + section.fileSize()) - 8;
            // Functions are aligned to 16 bytes
            for (int i = (int) (section.offset() + ((16 - (section.address() & 15)) & 15)); i < end; i += 16) {
                if (bytes[i] == 0x48 && (bytes[i + 1] & 0xff) == 0x8d && bytes[i + 2] == 0x05 && (bytes[i + 7] & 0xff) == 0xc3) {
                    found.add(new long[]{i + delta, i + delta + 7 + little.getInt(i + 3)});
                }
            }
        }
        return found;
    }

    // ----- Virtual tables ---------------------------------------------------------------------------------------------

    /** @return whether the data word holds the address of code */
    boolean isCodePointer(long va) {
        long to = pointer(va);
        return to != 0 && isCode(to);
    }

    /**
     * The virtual functions of the table that has a slot at {@code slot}: on Linux a table is preceded by its offset and
     * type information words, which are not code pointers, so the run of code pointers around the slot is the table.
     * Windows tables can follow each other directly; {@code starts} (sorted addresses that code refers to) cuts them apart.
     *
     * @return {start of the table, number of slots}
     */
    long[] table(long slot, long[] starts) {
        long start = slot;
        while (isCodePointer(start - 8) && (starts == null || Arrays.binarySearch(starts, start) < 0)) {
            start -= 8;
        }
        long end = slot + 8;
        while (isCodePointer(end) && (starts == null || Arrays.binarySearch(starts, end) < 0)) {
            end += 8;
        }
        return new long[]{start, (end - start) / 8};
    }

    /**
     * @return the class name the compiler stored for a virtual table (RTTI), or null when the build has none for it.
     * Linux: the word before the table points to the type information, whose second word points to the mangled name.
     * Windows: the word before the table points to a locator, which holds the offset of the type descriptor
     */
    String className(long table) {
        long before = pointer(table - 8);
        if (before == 0 || isCode(before)) {
            return null;
        }
        if (!windows) {
            long name = pointer(before + 8);
            return name == 0 ? null : Names.itanium(cStringAtOffsetOrNull(name));
        }
        if (u32(before) != 1) {
            return null;
        }
        long descriptor = imageBase + u32(before + 12);
        return Names.msvc(cStringAtOffsetOrNull(descriptor + 16));
    }

    private String cStringAtOffsetOrNull(long va) {
        int offset = offset(va);
        return offset < 0 ? null : cStringAtOffset(offset);
    }
}
