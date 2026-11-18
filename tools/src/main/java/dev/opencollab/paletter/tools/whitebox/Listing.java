package dev.opencollab.paletter.tools.whitebox;

import com.github.icedland.iced.x86.Code;
import com.github.icedland.iced.x86.FlowControl;
import com.github.icedland.iced.x86.Instruction;
import com.github.icedland.iced.x86.OpKind;
import com.github.icedland.iced.x86.Register;
import com.github.icedland.iced.x86.dec.Decoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What one function does that the discovery cares about, in the order of its instructions: the data it refers to, the
 * functions it calls, the virtual calls it makes, and the short strings it builds in place.
 * <p>
 * The last one needs explaining. The Linux build (clang, libc++) does not keep a short NBT key such as {@code slot_count}
 * as a string in the file. A {@code std::string} of up to 22 characters lives inside the string object, so the compiler
 * writes it there with a few instructions: one byte with twice the length, then the characters as numbers, then a NUL.
 * This class follows those writes and reads the string back once it is complete. The Windows build (MSVC) does the
 * same for strings of up to 15 characters with another layout: 16 bytes for the characters, then the length and the
 * capacity 15 as 64-bit numbers.
 */
final class Listing {
    enum Kind {
        /** The instruction refers to data at {@code value}. */
        DATA,
        /** A direct call or tail call of the function at {@code value}. */
        CALL,
        /** A call through a virtual table: the function pointer is read at offset {@code value} of the table. */
        VIRTUAL_CALL,
        /** A short string built in place: {@code text}. */
        TEXT,
        /** The small number {@code value} is stored into a 32-bit field at offset {@code extra} of an object. */
        STORE
    }

    record Event(Kind kind, long at, long value, long extra, String text) {
    }

    /** A string under construction is given up after this many calls. */
    private static final int PENDING_CALLS = 3;

    /** A string under construction at {@code slot}. {@code length} is -1 for the Windows layout, which stores it apart. */
    private record Pending(long slot, int length, long at, int calls) {
    }

    /** The constants a function can load: the read-only data of the binary. */
    interface Constants {
        /** @return the bytes at an address, or null where the file has none or the program can change them */
        byte[] read(long address, int length);
    }

    private final Constants constants;
    private final boolean windows;
    private final List<Event> events = new ArrayList<>();
    /** Bytes written with constants, by base register and offset. */
    private final Map<Long, Byte> memory = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    /** Registers that hold a constant, until an instruction writes them or a call may. */
    private final Map<Integer, Long> registerConstant = new HashMap<>();
    private final Map<Integer, byte[]> vectorConstant = new HashMap<>();
    /** Registers that hold the address of a place in an object: {@code lea rax, [rsp + 0x101]}. register -> slot */
    private final Map<Integer, Long> registerAddress = new HashMap<>();

    private Listing(Constants constants, boolean windows) {
        this.constants = constants;
        this.windows = windows;
    }

    /** @return the events of the function that starts at {@code start}, or an empty list when there is no code there */
    static List<Event> of(Image image, long start) {
        byte[] code = image.code(start);
        if (code == null) {
            return List.of();
        }
        Listing listing = new Listing((address, length) -> image.isWritable(address) ? null : image.read(address, length), image.windows);
        listing.decode(code, start, image.functionEnd(start) == 0);
        return listing.events;
    }

    /**
     * @param code      the instructions of one function
     * @param start     the address of the first one
     * @param windows   whether the code is of the Windows build, which lays out strings differently
     * @return the events of the code
     */
    static List<Event> of(byte[] code, long start, Constants constants, boolean windows) {
        Listing listing = new Listing(constants, windows);
        listing.decode(code, start, false);
        return listing.events;
    }

    private void decode(byte[] code, long start, boolean untilReturn) {
        Decoder decoder = new Decoder(64, code);
        decoder.setIP(start);
        Instruction instruction = new Instruction();
        long end = start + code.length;
        while (decoder.getIP() < end) {
            decoder.decode(instruction);
            if (instruction.isInvalid()) {
                continue;
            }
            step(instruction, start, end);
            int flow = instruction.getFlowControl();
            if (untilReturn && (flow == FlowControl.RETURN || flow == FlowControl.UNCONDITIONAL_BRANCH || flow == FlowControl.INDIRECT_BRANCH
                || instruction.getCode() == Code.INT3)) {
                break;
            }
        }
        finishStrings(true);
    }

    private void step(Instruction instruction, long start, long end) {
        long at = instruction.getIP();
        int code = instruction.getCode();
        boolean memoryOperand = false;
        for (int i = 0; i < instruction.getOpCount(); i++) {
            memoryOperand |= instruction.getOpKind(i) == OpKind.MEMORY;
        }
        boolean relative = memoryOperand && instruction.isIPRelativeMemoryOperand();
        if (relative) {
            events.add(new Event(Kind.DATA, at, instruction.ipRelativeMemoryAddress(), 0, null));
        }

        // A call ends the construction of the strings that are its arguments
        int flow = instruction.getFlowControl();
        if (code == Code.CALL_REL32_64 || (code == Code.JMP_REL32_64 && (instruction.getNearBranchTarget() < start || instruction.getNearBranchTarget() >= end))) {
            finishStrings(false);
            events.add(new Event(Kind.CALL, at, instruction.getNearBranchTarget(), 0, null));
            return;
        }
        if ((flow == FlowControl.INDIRECT_CALL || code == Code.JMP_RM64) && memoryOperand && !relative
            && instruction.getMemoryIndex() == Register.NONE && instruction.getMemoryBase() != Register.NONE) {
            finishStrings(false);
            events.add(new Event(Kind.VIRTUAL_CALL, at, instruction.getMemoryDisplacement64(), 0, null));
            return;
        }
        if (flow == FlowControl.INDIRECT_CALL) {
            finishStrings(false);
            return;
        }

        boolean plain = memoryOperand && !relative && instruction.getMemoryIndex() == Register.NONE && instruction.getMemoryBase() != Register.NONE;
        boolean store = plain && instruction.getOp0Kind() == OpKind.MEMORY;
        long slot = 0;
        if (plain) {
            // A store through a register that holds an address goes to the place that address names
            Long address = registerAddress.get(instruction.getMemoryBase());
            slot = address != null ? address + instruction.getMemoryDisplacement64() : slot(instruction.getMemoryBase(), instruction.getMemoryDisplacement64());
        }
        if (relative && instruction.getOp0Kind() == OpKind.REGISTER && (code == Code.MOV_R64_RM64 || code == Code.MOV_R32_RM32
            || code == Code.MOV_R16_RM16 || code == Code.MOV_R8_RM8 || code == Code.MOVZX_R32_RM8 || code == Code.MOVZX_R32_RM16)) {
            // A register loaded from read-only data holds a constant: this is how the Windows build copies a short literal
            forget(instruction.getOp0Register());
            byte[] bytes = constants.read(instruction.ipRelativeMemoryAddress(), 8);
            if (bytes != null) {
                int width = code == Code.MOV_R64_RM64 ? 8 : code == Code.MOV_R32_RM32 ? 4 : code == Code.MOV_R16_RM16 || code == Code.MOVZX_R32_RM16 ? 2 : 1;
                long value = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
                registerConstant.put(Register.getFullRegister(instruction.getOp0Register()), width == 8 ? value : value & ((1L << (8 * width)) - 1));
            }
            return;
        }
        switch (code) {
            case Code.LEA_R64_M -> {
                forget(instruction.getOp0Register());
                if (plain) {
                    registerAddress.put(instruction.getOp0Register(), slot);
                }
                return;
            }
            case Code.MOV_R64_IMM64, Code.MOV_R32_IMM32 -> {
                long value = code == Code.MOV_R64_IMM64 ? instruction.getImmediate64() : instruction.getImmediate32() & 0xffffffffL;
                forget(instruction.getOp0Register());
                registerConstant.put(Register.getFullRegister(instruction.getOp0Register()), value);
                return;
            }
            case Code.MOV_RM8_IMM8, Code.MOV_RM16_IMM16, Code.MOV_RM32_IMM32, Code.MOV_RM64_IMM32 -> {
                if (store) {
                    int width = code == Code.MOV_RM8_IMM8 ? 1 : code == Code.MOV_RM16_IMM16 ? 2 : code == Code.MOV_RM32_IMM32 ? 4 : 8;
                    long value = instruction.getImmediate(1);
                    write(slot, value, width);
                    if (!windows && width == 1 && (value & 1) == 0 && value >= 2 && value <= 44) {
                        pending.add(new Pending(slot, (int) value / 2, at, 0));
                    }
                    if (windows && width == 8 && value == 15) {
                        pending.add(new Pending(slot - 24, -1, at, 0));
                    }
                    int base = instruction.getMemoryBase();
                    if (width == 4 && value >= 0 && value <= 15 && base != Register.RSP && base != Register.RBP) {
                        events.add(new Event(Kind.STORE, at, value, instruction.getMemoryDisplacement64(), null));
                    }
                }
                return;
            }
            case Code.MOV_RM8_R8, Code.MOV_RM16_R16, Code.MOV_RM32_R32, Code.MOV_RM64_R64 -> {
                if (store) {
                    Long constant = registerConstant.get(Register.getFullRegister(instruction.getOp1Register()));
                    if (constant != null) {
                        write(slot, constant, code == Code.MOV_RM8_R8 ? 1 : code == Code.MOV_RM16_R16 ? 2 : code == Code.MOV_RM32_R32 ? 4 : 8);
                    }
                    return;
                }
            }
            case Code.MOVUPS_XMM_XMMM128, Code.MOVAPS_XMM_XMMM128, Code.MOVDQU_XMM_XMMM128, Code.MOVDQA_XMM_XMMM128,
                 Code.VEX_VMOVUPS_XMM_XMMM128, Code.VEX_VMOVAPS_XMM_XMMM128, Code.VEX_VMOVDQU_XMM_XMMM128 -> {
                byte[] constant = relative ? constants.read(instruction.ipRelativeMemoryAddress(), 16) : null;
                if (constant != null) {
                    vectorConstant.put(instruction.getOp0Register(), constant);
                } else {
                    vectorConstant.remove(instruction.getOp0Register());
                }
                return;
            }
            case Code.MOVUPS_XMMM128_XMM, Code.MOVAPS_XMMM128_XMM, Code.MOVDQU_XMMM128_XMM, Code.MOVDQA_XMMM128_XMM,
                 Code.VEX_VMOVUPS_XMMM128_XMM, Code.VEX_VMOVAPS_XMMM128_XMM, Code.VEX_VMOVDQU_XMMM128_XMM -> {
                byte[] constant = store ? vectorConstant.get(instruction.getOp1Register()) : null;
                if (constant != null) {
                    for (int i = 0; i < constant.length; i++) {
                        memory.put(slot + i, constant[i]);
                    }
                }
                return;
            }
            default -> {
            }
        }
        // Any other instruction that writes a register ends the constant in it
        if (instruction.getOpCount() > 0 && instruction.getOp0Kind() == OpKind.REGISTER) {
            forget(instruction.getOp0Register());
        }
    }

    private void forget(int register) {
        registerConstant.remove(Register.getFullRegister(register));
        registerAddress.remove(Register.getFullRegister(register));
        vectorConstant.remove(register);
    }

    private static long slot(int base, long displacement) {
        return ((long) base << 40) + (displacement & 0xffffffffffL);
    }

    private void write(long slot, long value, int width) {
        for (int i = 0; i < width; i++) {
            memory.put(slot + i, (byte) (value >>> (8 * i)));
        }
    }

    /** Emits the strings whose length byte, characters and NUL have all been written; gives up on stale ones. */
    private void finishStrings(boolean last) {
        // A call may change the registers that are not preserved across calls
        for (int register : new int[]{Register.RAX, Register.RCX, Register.RDX, Register.RSI, Register.RDI, Register.R8, Register.R9,
            Register.R10, Register.R11}) {
            registerAddress.remove(register);
            registerConstant.remove(register);
        }
        vectorConstant.clear();
        List<Pending> kept = new ArrayList<>();
        for (Pending string : pending) {
            String text = text(string);
            if (text != null) {
                events.add(new Event(Kind.TEXT, string.at(), 0, 0, text));
            } else if (!last && string.calls() + 1 < PENDING_CALLS) {
                kept.add(new Pending(string.slot(), string.length(), string.at(), string.calls() + 1));
            }
        }
        pending.clear();
        pending.addAll(kept);
    }

    private String text(Pending string) {
        int count = string.length();
        int first = 1;
        if (count < 0) {
            // Windows: characters at the slot, their number 16 bytes after it
            Byte length = memory.get(string.slot() + 16);
            if (length == null || length < 1 || length > 15) {
                return null;
            }
            count = length;
            first = 0;
        } else {
            Byte length = memory.get(string.slot());
            Byte end = memory.get(string.slot() + 1 + count);
            if (length == null || length != count * 2 || end == null || end != 0) {
                return null;
            }
        }
        byte[] text = new byte[count];
        for (int i = 0; i < text.length; i++) {
            Byte b = memory.get(string.slot() + first + i);
            if (b == null || b < 0x20 || b > 0x7e) {
                return null;
            }
            text[i] = b;
        }
        return new String(text, StandardCharsets.US_ASCII);
    }
}
