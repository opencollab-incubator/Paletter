package org.geysermc.paletter.tools.whitebox;

import org.geysermc.paletter.tools.whitebox.Listing.Event;
import org.geysermc.paletter.tools.whitebox.Listing.Kind;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The instruction bytes are what clang and MSVC emit for these statements; each line is one instruction. */
class ListingTest {
    private static final Listing.Constants NO_CONSTANTS = (address, length) -> null;

    @Test
    void readsAShortStringBuiltInPlace() {
        // std::string key("slot_count"); put(key, ...)
        List<Event> events = Listing.of(code(
            "C6 44 24 50 14",                   // mov byte ptr [rsp + 0x50], 0x14      twice the length
            "48 B8 73 6C 6F 74 5F 63 6F 75",    // movabs rax, 'slot_cou'
            "48 89 44 24 51",                   // mov qword ptr [rsp + 0x51], rax
            "66 C7 44 24 59 6E 74",             // mov word ptr [rsp + 0x59], 'nt'
            "C6 44 24 5B 00",                   // mov byte ptr [rsp + 0x5b], 0
            "E8 00 01 00 00"                    // call
        ), 0x1000, NO_CONSTANTS, false);
        assertEquals(List.of(Kind.TEXT, Kind.CALL), events.stream().map(Event::kind).toList());
        assertEquals("slot_count", events.getFirst().text());
        assertEquals(0x1000, events.getFirst().at());
        // The call is the sixth instruction, 37 bytes in; its target is relative to its end
        assertEquals(0x1000 + 37 + 0x100, events.getLast().value());
    }

    @Test
    void followsARegisterThatHoldsTheAddressOfTheString() {
        List<Event> events = Listing.of(code(
            "C6 84 24 00 01 00 00 14",          // mov byte ptr [rsp + 0x100], 0x14
            "48 8D 84 24 01 01 00 00",          // lea rax, [rsp + 0x101]
            "48 B9 6C 69 71 75 69 64 54 79",    // movabs rcx, 'liquidTy'
            "48 89 08",                         // mov qword ptr [rax], rcx
            "66 C7 40 08 70 65",                // mov word ptr [rax + 8], 'pe'
            "C6 84 24 0B 01 00 00 00",          // mov byte ptr [rsp + 0x10b], 0
            "E8 00 00 00 00"                    // call
        ), 0x1000, NO_CONSTANTS, false);
        assertEquals("liquidType", events.getFirst().text());
    }

    @Test
    void keepsAConstantInARegisterThatCallsPreserve() {
        // "allowPow" stays in r13 across a call and is used for "allowPowerDown" after it
        List<Event> events = Listing.of(code(
            "49 BD 61 6C 6C 6F 77 50 6F 77",    // movabs r13, 'allowPow'
            "E8 00 00 00 00",                   // call
            "C6 44 24 18 1C",                   // mov byte ptr [rsp + 0x18], 0x1c
            "4C 89 6C 24 19",                   // mov qword ptr [rsp + 0x19], r13
            "48 B8 6F 77 65 72 44 6F 77 6E",    // movabs rax, 'owerDown'
            "48 89 44 24 1F",                   // mov qword ptr [rsp + 0x1f], rax
            "C6 44 24 27 00",                   // mov byte ptr [rsp + 0x27], 0
            "E8 00 00 00 00"                    // call
        ), 0x1000, NO_CONSTANTS, false);
        assertTrue(events.stream().anyMatch(event -> "allowPowerDown".equals(event.text())), events.toString());
    }

    @Test
    void readsAStringCopiedFromAConstant() {
        // 16 bytes of the string come from read-only data, the rest and the length byte are instructions
        byte[] constant = "anContainLiquid\0".getBytes(StandardCharsets.US_ASCII);
        List<Event> events = Listing.of(code(
            "C6 44 24 20 20",                   // mov byte ptr [rsp + 0x20], 0x20
            "C6 44 24 21 63",                   // mov byte ptr [rsp + 0x21], 'c'
            "0F 10 05 00 10 00 00",             // movups xmm0, xmmword ptr [rip + 0x1000]
            "0F 11 44 24 22",                   // movups xmmword ptr [rsp + 0x22], xmm0
            "E8 00 00 00 00"                    // call
        ), 0x1000, (address, length) -> address == 0x1000 + 17 + 0x1000 ? constant : null, false);
        assertEquals(List.of(Kind.DATA, Kind.TEXT, Kind.CALL), events.stream().map(Event::kind).toList());
        assertEquals("canContainLiquid", events.get(1).text());
    }

    @Test
    void readsAWindowsShortString() {
        // MSVC: the characters, then the length and the capacity 15
        List<Event> events = Listing.of(code(
            "48 B8 73 6C 6F 74 5F 63 6F 75",    // mov rax, 'slot_cou'
            "48 89 44 24 20",                   // mov qword ptr [rsp + 0x20], rax
            "66 C7 44 24 28 6E 74",             // mov word ptr [rsp + 0x28], 'nt'
            "48 C7 44 24 30 0A 00 00 00",       // mov qword ptr [rsp + 0x30], 10
            "48 C7 44 24 38 0F 00 00 00",       // mov qword ptr [rsp + 0x38], 15
            "E8 00 00 00 00"                    // call
        ), 0x1000, NO_CONSTANTS, true);
        assertEquals("slot_count", events.getFirst().text());
    }

    @Test
    void reportsDataVirtualCallsAndTypeStores() {
        List<Event> events = Listing.of(code(
            "48 8D 3D 10 00 00 00",             // lea rdi, [rip + 0x10]
            "FF 50 28",                         // call qword ptr [rax + 0x28]
            "41 C7 45 60 01 00 00 00"           // mov dword ptr [r13 + 0x60], 1
        ), 0x1000, NO_CONSTANTS, false);
        assertEquals(List.of(new Event(Kind.DATA, 0x1000, 0x1017, 0, null), new Event(Kind.VIRTUAL_CALL, 0x1007, 0x28, 0, null),
            new Event(Kind.STORE, 0x100a, 1, 0x60, null)), events);
    }

    @Test
    void ignoresAnIncompleteString() {
        // The length byte says 10 characters, only 8 are written
        List<Event> events = Listing.of(code(
            "C6 44 24 50 14",                   // mov byte ptr [rsp + 0x50], 0x14
            "48 B8 73 6C 6F 74 5F 63 6F 75",    // movabs rax, 'slot_cou'
            "48 89 44 24 51",                   // mov qword ptr [rsp + 0x51], rax
            "E8 00 00 00 00"                    // call
        ), 0x1000, NO_CONSTANTS, false);
        assertEquals(List.of(Kind.CALL), events.stream().map(Event::kind).toList());
    }

    private static byte[] code(String... instructions) {
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        for (String instruction : instructions) {
            for (String hex : instruction.split(" ")) {
                code.write(Integer.parseInt(hex, 16));
            }
        }
        return code.toByteArray();
    }
}
