package dev.opencollab.paletter.bench;

import dev.opencollab.paletter.internal.data.PaletteDataCodec;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Reading a bundled gzipped NBT file into its root compound: through the reader the library uses
 * ({@link PaletteDataCodec#readGzip}, buffered on the inflated side) and through {@code NbtUtils.createGZIPReader} (no
 * buffer: every primitive is an {@code Inflater} call), on the same bytes in memory.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ReadBenchmark {
    @Param({"26.60.nbt", "properties.nbt"})
    public String file;
    private byte[] bytes;

    @Setup
    public void setUp() throws IOException {
        try (InputStream stream = ReadBenchmark.class.getResourceAsStream("/paletter/" + file)) {
            bytes = stream.readAllBytes();
        }
    }

    @Benchmark
    public NbtMap buffered() throws IOException {
        return PaletteDataCodec.readGzip(new ByteArrayInputStream(bytes));
    }

    @Benchmark
    public NbtMap unbuffered() throws IOException {
        try (NBTInputStream input = NbtUtils.createGZIPReader(new ByteArrayInputStream(bytes))) {
            return (NbtMap) input.readTag();
        }
    }
}
