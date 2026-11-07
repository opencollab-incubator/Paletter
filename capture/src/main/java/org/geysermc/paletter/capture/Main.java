package org.geysermc.paletter.capture;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.geysermc.paletter.data.PaletteDataCodec;
import org.geysermc.paletter.data.VanillaData;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records what a Bedrock Dedicated Server sends a client. See capture/WIRE-NOTES.md.
 * <pre>
 * capture     --out DIR [--custom-out DIR] [--host 127.0.0.1] [--port 19140] [--reference 26.60]
 *             [--bds-version 1.26.63.1] [--timeout 90]
 * experiments --level-dat FILE --enable name,name,...
 * compare     --capture DIR --reference 26.60
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: capture --out DIR [--custom-out DIR] [--port 19140] | experiments --level-dat FILE --enable a,b | compare --capture DIR --reference 26.60");
            return;
        }
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected --option value, got " + args[i]);
            }
            options.put(args[i].substring(2), args[i + 1]);
        }
        switch (args[0]) {
            case "capture" -> capture(options);
            case "experiments" -> LevelDat.enableExperiments(Path.of(require(options, "level-dat")),
                List.of(require(options, "enable").split(",")));
            case "compare" -> System.exit(new Compare(loadCapture(Path.of(require(options, "capture"))),
                reference(options.getOrDefault("reference", "26.60"))).run() ? 0 : 1);
            default -> throw new IllegalArgumentException("Unknown command " + args[0]);
        }
    }

    private static void capture(Map<String, String> options) throws Exception {
        if (options.getOrDefault("transport", "raknet").equals("nethernet")) {
            InetSocketAddress address = new InetSocketAddress(options.getOrDefault("host", "127.0.0.1"),
                Integer.parseInt(options.getOrDefault("port", "19140")));
            String[] status = Capture.netherNetPing(address, 10);
            if (status == null) {
                throw new IllegalStateException("No NetherNet signaling on " + address);
            }
            System.out.println("NetherNet status: " + String.join(", ", status));
            Capture.Summary summary = Capture.capture(address, Integer.parseInt(status[0]),
                reference(options.getOrDefault("reference", "26.60")), options.getOrDefault("bds-version", "unknown"),
                Path.of(require(options, "out")), options.containsKey("custom-out") ? Path.of(options.get("custom-out")) : null,
                Long.parseLong(options.getOrDefault("timeout", "90")), true);
            System.out.println(summary);
            return;
        }
        int protocol = Integer.parseInt(options.getOrDefault("protocol", String.valueOf(Capture.NEWEST.getProtocolVersion())));
        BedrockCodec codec = Capture.codecFor(protocol);
        if (codec.getProtocolVersion() != protocol) {
            throw new IllegalArgumentException("No codec for protocol " + protocol + "; use --wire-protocol to probe with the newest codec");
        }
        codec = Capture.clientCodec(codec);
        if (options.containsKey("wire-protocol")) {
            // Speak the codec's format, but announce another protocol number (probing a server's version)
            codec = codec.toBuilder().protocolVersion(Integer.parseInt(options.get("wire-protocol"))).build();
        }
        InetSocketAddress address = new InetSocketAddress(options.getOrDefault("host", "127.0.0.1"),
            Integer.parseInt(options.getOrDefault("port", "19140")));
        System.out.println("Connecting to " + address + " with protocol " + codec.getProtocolVersion() + " (" + codec.getMinecraftVersion() + ")");
        CaptureClient.Result result = new CaptureClient(codec, address).run(Long.parseLong(options.getOrDefault("timeout", "90")));
        System.out.printf("Captured: %d block properties, %d items, %d creative groups, %d creative items, packets %s%n",
            result.startGame.getBlockProperties().size(), result.items.size(), result.creative.getGroups().size(),
            result.creative.getContents().size(), result.packetCounts);
        CaptureWriter writer = new CaptureWriter(result, reference(options.getOrDefault("reference", "26.60")),
            options.getOrDefault("bds-version", "unknown"));
        writer.write(Path.of(require(options, "out")), options.containsKey("custom-out") ? Path.of(options.get("custom-out")) : null);
        System.out.println("Wrote " + options.get("out") + (writer.unresolvedHashes() > 0
            ? "; WARNING: " + writer.unresolvedHashes() + " creative block ids do not match any palette hash" : "; all creative block ids match palette hashes"));
    }

    static VanillaData reference(String name) throws IOException {
        try (InputStream stream = Main.class.getResourceAsStream("/paletter/" + name + ".nbt")) {
            if (stream == null) {
                throw new IOException("No bundled Paletter version " + name);
            }
            return PaletteDataCodec.read(stream);
        }
    }

    static CapturedData loadCapture(Path directory) throws IOException {
        return CapturedData.read(directory);
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing --" + name);
        }
        return value;
    }
}
