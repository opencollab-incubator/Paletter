package dev.opencollab.paletter.tools;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import dev.opencollab.paletter.tools.bds.CapturePlatform;
import dev.opencollab.paletter.tools.client.Capture;
import dev.opencollab.paletter.tools.client.CaptureClient;
import dev.opencollab.paletter.tools.client.CaptureWriter;
import dev.opencollab.paletter.tools.client.CapturedData;
import dev.opencollab.paletter.tools.client.LevelDat;
import dev.opencollab.paletter.tools.data.Diff;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import static dev.opencollab.paletter.tools.Cli.reference;

/** The commands of the Bedrock capture client: {@code capture}, {@code experiments}, {@code compare}. */
final class CaptureCommands {
    private CaptureCommands() {
    }

    @Command(name = "capture", description = "Records what a Bedrock Dedicated Server sends a client. See docs/wire-notes.md.")
    static final class CaptureCommand implements Callable<Integer> {
        @Option(names = "--out", required = true) Path out;
        @Option(names = "--custom-out") Path customOut;
        @Option(names = "--host", defaultValue = "127.0.0.1") String host;
        @Option(names = "--port", defaultValue = "19140") int port;
        @Option(names = "--reference", defaultValue = "26.60") String reference;
        @Option(names = "--bds-version", defaultValue = "unknown") String bdsVersion;
        @Option(names = "--bds-platform", description = "windows or linux: the OS the server runs on (default: this one's)") String bdsPlatform;
        @Option(names = "--timeout", defaultValue = "90") long timeout;
        @Option(names = "--transport", defaultValue = "raknet", description = "raknet or nethernet") String transport;
        @Option(names = "--protocol") Integer protocol;
        @Option(names = "--wire-protocol", description = "Speak the codec's format but announce this protocol number") Integer wireProtocol;

        @Override
        public Integer call() throws Exception {
            if (transport.equals("nethernet")) {
                InetSocketAddress address = new InetSocketAddress(host, port);
                String[] status = Capture.netherNetPing(address, 10);
                if (status == null) {
                    throw new IllegalStateException("No NetherNet signaling on " + address);
                }
                System.out.println("NetherNet status: " + String.join(", ", status));
                Capture.Summary summary = Capture.capture(address, Integer.parseInt(status[0]), reference(reference), bdsVersion,
                    platform(), out, customOut, timeout, true);
                System.out.println(summary);
                return 0;
            }
            int wanted = protocol != null ? protocol : Capture.NEWEST.getProtocolVersion();
            BedrockCodec codec = Capture.codecFor(wanted);
            if (codec.getProtocolVersion() != wanted) {
                throw new IllegalArgumentException("No codec for protocol " + wanted + "; use --wire-protocol to probe with the newest codec");
            }
            codec = Capture.clientCodec(codec);
            if (wireProtocol != null) {
                codec = codec.toBuilder().protocolVersion(wireProtocol).build();
            }
            InetSocketAddress address = new InetSocketAddress(host, port);
            System.out.println("Connecting to " + address + " with protocol " + codec.getProtocolVersion() + " (" + codec.getMinecraftVersion() + ")");
            CaptureClient.Result result = new CaptureClient(codec, address).run(timeout);
            System.out.printf("Captured: %d block properties, %d items, %d creative groups, %d creative items, packets %s%n",
                result.startGame.getBlockProperties().size(), result.items.size(), result.creative.getGroups().size(),
                result.creative.getContents().size(), result.packetCounts);
            CaptureWriter writer = new CaptureWriter(result, reference(reference), bdsVersion, platform())
                .withProtocol(codec.getProtocolVersion(), wanted);
            writer.write(out, customOut);
            System.out.println("Wrote " + out + (writer.unresolvedHashes() > 0
                ? "; WARNING: " + writer.unresolvedHashes() + " creative block ids do not match any palette hash" : "; all creative block ids match palette hashes"));
            return 0;
        }

        private String platform() {
            if (bdsPlatform == null) {
                return CapturePlatform.host();
            }
            if (!bdsPlatform.equals(CapturePlatform.WINDOWS) && !bdsPlatform.equals(CapturePlatform.LINUX)) {
                throw new IllegalArgumentException("--bds-platform must be " + CapturePlatform.WINDOWS + " or " + CapturePlatform.LINUX);
            }
            return bdsPlatform;
        }
    }

    @Command(name = "experiments", description = "Enables experiment toggles in a level.dat.")
    static final class Experiments implements Callable<Integer> {
        @Option(names = "--level-dat", required = true) Path levelDat;
        @Option(names = "--enable", required = true, split = ",", description = "name,name,...") List<String> enable;

        @Override
        public Integer call() throws Exception {
            LevelDat.enableExperiments(levelDat, enable);
            return 0;
        }
    }

    @Command(name = "compare", description = "Diffs a capture directory against a bundled version.")
    static final class CompareCommand implements Callable<Integer> {
        @Option(names = "--capture", required = true) Path capture;
        @Option(names = "--reference", defaultValue = "26.60") String reference;

        @Override
        public Integer call() throws Exception {
            Diff diff = Diff.capture(CapturedData.read(capture), reference(reference));
            diff.print(System.out);
            System.out.println(diff.identical() ? "IDENTICAL" : "DIFFERENT");
            return diff.identical() ? 0 : 1;
        }
    }
}
