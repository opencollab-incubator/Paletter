package org.geysermc.paletter.tools.client;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.codec.v407.serializer.PacketViolationWarningSerializer_v407;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.PacketViolationWarningPacket;
import org.geysermc.paletter.data.VanillaData;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Programmatic entry point of the capture module, used by the generator's {@code update} pipeline.
 */
public final class Capture {
    /** The newest codec this build of the protocol library has; used to probe servers with unknown protocols. */
    public static final BedrockCodec NEWEST = Bedrock_v2225.CODEC;

    private Capture() {
    }

    /**
     * @param protocol the protocol the client announced
     * @param codecProtocol the protocol of the codec that decoded the packets; differs from {@code protocol} when the
     *                      protocol library has no codec for the server's protocol yet (then decoding may be wrong)
     * @param minecraftVersion the codec's Minecraft version
     * @param blockNetworkIdsHashed whether the server sent hashed block network ids
     * @param resolvedHashes creative block ids that matched a palette hash
     * @param unresolvedHashes creative block ids that matched no palette hash (always all of them in sequential mode)
     * @param experiments experiments start game reported, as {@code name=enabled}
     * @param packStackExperiments experiments the resource pack stack packet reported, as {@code name=enabled}
     */
    public record Summary(int protocol, int codecProtocol, String minecraftVersion, boolean blockNetworkIdsHashed,
                          int resolvedHashes, int unresolvedHashes, List<String> experiments, List<String> packStackExperiments) {
        /** Names of the experiments start game reported as enabled. */
        public List<String> enabledExperiments() {
            return experiments.stream().filter(e -> e.endsWith("=true")).map(e -> e.substring(0, e.length() - 5)).sorted().toList();
        }

        public boolean nativeCodec() {
            return protocol == codecProtocol;
        }
    }

    /**
     * Logs into a local offline mode BDS over RakNet and writes the capture (see {@link CaptureWriter}).
     *
     * @param protocol the server's protocol version (e.g. from {@link #ping}); if the protocol library has no codec
     *                 for it, the newest codec is used with this protocol number announced
     * @param reference the palette the block hashes are resolved against
     * @param customOut where to write the custom (non {@code minecraft:}) content, or null
     */
    public static Summary capture(InetSocketAddress address, int protocol, VanillaData reference, String bdsVersion,
                                  Path out, Path customOut, long timeoutSeconds) throws Exception {
        return capture(address, protocol, reference, bdsVersion, out, customOut, timeoutSeconds, false);
    }

    /**
     * @param netherNet connect over NetherNet (HTTP signaling on {@code address}) instead of RakNet
     */
    public static Summary capture(InetSocketAddress address, int protocol, VanillaData reference, String bdsVersion,
                                  Path out, Path customOut, long timeoutSeconds, boolean netherNet) throws Exception {
        BedrockCodec codec = codecFor(protocol);
        int codecProtocol = codec.getProtocolVersion();
        if (codecProtocol != protocol) {
            codec = codec.toBuilder().protocolVersion(protocol).build();
        }
        codec = clientCodec(codec);
        System.out.println("Connecting to " + address + (netherNet ? " over NetherNet" : " over RakNet") + " with protocol " + protocol
            + (codecProtocol != protocol ? " (decoded with the " + codecProtocol + " codec)" : ""));
        CaptureClient.Result result = new CaptureClient(codec, address, netherNet).run(timeoutSeconds);
        CaptureWriter writer = new CaptureWriter(result, reference, bdsVersion);
        writer.write(out, customOut);
        List<String> experiments = result.startGame.getExperiments().stream()
            .map(experiment -> experiment.getName() + "=" + experiment.isEnabled()).toList();
        List<String> packStackExperiments = result.packStack == null ? List.of() : result.packStack.getExperiments().stream()
            .map(experiment -> experiment.getName() + "=" + experiment.isEnabled()).toList();
        return new Summary(protocol, codecProtocol, codec.getMinecraftVersion(), result.startGame.isBlockNetworkIdsHashed(),
            writer.resolvedHashes(), writer.unresolvedHashes(), experiments, packStackExperiments);
    }

    /**
     * The codec for a protocol version, found by name in the protocol library ({@code codec.vNNNN.Bedrock_vNNNN}), so
     * a protocol library bump makes new versions capturable without code changes. Falls back to the newest one known.
     */
    public static BedrockCodec codecFor(int protocol) {
        try {
            Class<?> type = Class.forName("org.cloudburstmc.protocol.bedrock.codec.v" + protocol + ".Bedrock_v" + protocol);
            return (BedrockCodec) type.getField("CODEC").get(null);
        } catch (ReflectiveOperationException e) {
            return NEWEST;
        }
    }

    /** The codecs are written for servers; let the client encode logins and decode the violation warnings BDS sends. */
    public static BedrockCodec clientCodec(BedrockCodec codec) {
        return codec.toBuilder()
            .updateSerializer(LoginPacket.class, new LoginSerializer())
            .deregisterPacket(PacketViolationWarningPacket.class)
            .registerPacket(PacketViolationWarningPacket::new, PacketViolationWarningSerializer_v407.INSTANCE, 156, PacketRecipient.BOTH)
            .build();
    }

    /**
     * The NetherNet status of a server: whether {@code address} serves HTTP signaling, and the protocol and version
     * from LAN discovery on the same host (needs {@code enable-lan-visibility=true}).
     *
     * @return {@code [protocol, version, server name]} ({@code ["0", "", ""]} when discovery did not answer), or null if
     *         the server does not serve NetherNet signaling there
     */
    public static String[] netherNetPing(InetSocketAddress address, int timeoutSeconds) throws InterruptedException {
        if (!NetherNet.serves(address, timeoutSeconds)) {
            return null;
        }
        var pong = NetherNet.discover(address.getHostString(), timeoutSeconds * 1000L);
        return pong == null ? new String[]{"0", "", ""}
            : new String[]{String.valueOf(pong.protocol()), pong.version(), pong.serverName(),
                "onlineAuth=" + pong.onlineAuth() + " selfSignedAuth=" + pong.selfSignedAuth()};
    }

    /** Switches toggles on in a world's level.dat, keeping the ones already set. See {@link LevelDat}. */
    public static void enableExperiments(Path levelDat, List<String> toggles) throws IOException {
        LevelDat.enableExperiments(levelDat, toggles);
    }

    /**
     * The RakNet unconnected pong of a server: {@code MCPE;motd;protocol;version;players;max;guid;level;mode;...}.
     *
     * BDS leaves the string empty unless {@code enable-lan-visibility=true}.
     *
     * @return the pong fields, an empty array for a pong without them, or null if nothing answered (e.g. a NetherNet
     *         only server)
     */
    public static String[] ping(InetSocketAddress address, int timeoutMillis) throws IOException {
        byte[] magic = {0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};
        ByteBuffer ping = ByteBuffer.allocate(33);
        ping.put((byte) 0x01).putLong(System.currentTimeMillis()).put(magic).putLong(0x5061_6c65_7474_6572L);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMillis);
            for (int attempt = 0; attempt < 3; attempt++) {
                socket.send(new DatagramPacket(ping.array(), ping.capacity(), address));
                byte[] buffer = new byte[2048];
                DatagramPacket pong = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(pong);
                } catch (SocketTimeoutException | java.net.PortUnreachableException e) {
                    continue;
                }
                // 0x1c, time, server guid, magic, then a length prefixed "MCPE;motd;protocol;version;..."
                String text = new String(buffer, 0, pong.getLength(), StandardCharsets.ISO_8859_1);
                int start = text.indexOf("MCPE;");
                if (buffer[0] != 0x1c) {
                    continue;
                }
                if (start < 0) {
                    return new String[0];
                }
                return new String(buffer, start, pong.getLength() - start, StandardCharsets.UTF_8).split(";");
            }
        } catch (java.net.SocketException e) {
            // Windows reports ICMP port unreachable as a reset: nothing listens there
            return null;
        }
        return null;
    }
}
