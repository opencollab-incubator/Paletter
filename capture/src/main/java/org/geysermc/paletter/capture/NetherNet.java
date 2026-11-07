package org.geysermc.paletter.capture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.HttpSignalingSettings;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetDiscovery;
import org.cloudburstmc.netty.channel.nethernet.signaling.ServerData;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPClientSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSessionFactory;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.common.util.VarInts;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * NetherNet (WebRTC data channels, HTTP signaling on {@code server-port}) for the capture client, the only transport
 * BDS 1.26.70+ offers. The transport is CloudburstMC/Network's {@code netty-transport-nethernet}; the Bedrock pipeline
 * around it follows Geyser's server side ({@code org.geysermc.geyser.network.bedrock.nethernet}): batches are framed
 * as whole messages, nothing is compressed until network settings, one packet per message, and the encryption
 * handshake is a no-op because DTLS already encrypts the channel.
 */
final class NetherNet {
    /** NetherNet compression behaves like RakNet protocol 11: none until network settings, prefixed afterwards. */
    private static final int EQUIVALENT_RAK_VERSION = 11;

    private NetherNet() {
    }

    /**
     * Whether the server serves NetherNet signaling on the address (vanilla BDS answers {@code GET /v1/join} with an
     * empty 200, so this says nothing about its protocol).
     */
    static boolean serves(InetSocketAddress address, long timeoutSeconds) {
        try {
            NetherNetHTTPClientSignaling.probe(address, HttpSignalingSettings.DEFAULT).get(timeoutSeconds, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The server's status (protocol, version, ...) from NetherNet LAN discovery: a discovery request sent straight to
     * {@code host:7551} instead of broadcast. BDS only answers with {@code enable-lan-visibility=true}.
     *
     * @return the status, or null if nothing answered in time
     */
    static PongData discover(String host, long timeoutMillis) throws InterruptedException {
        NetherNetDiscovery discovery = new NetherNetDiscovery(ThreadLocalRandom.current().nextLong());
        CompletableFuture<PongData> status = new CompletableFuture<>();
        try {
            discovery.bind(new InetSocketAddress(0));
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (!status.isDone() && System.currentTimeMillis() < deadline) {
                discovery.sendDiscoveryRequest(new InetSocketAddress(host, NetherNetConstants.DISCOVERY_PORT), (id, payload) -> {
                    try {
                        PongData data = ServerData.decode(payload);
                        if (data != null) {
                            status.complete(data);
                        }
                    } catch (Exception ignored) {
                        // a reply in a version this library does not know; others follow
                    } finally {
                        payload.release();
                    }
                });
                try {
                    return status.get(1, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException ignored) {
                    // ask again
                }
            }
            return status.getNow(null);
        } finally {
            discovery.close();
        }
    }

    /** A self signed identity acting for the capture player, with the key its login chain is signed with. */
    static org.cloudburstmc.netty.util.nethernet.OperatorIdentity identity(java.security.KeyPair keys, String name) {
        try {
            return new org.cloudburstmc.netty.util.nethernet.OperatorIdentity(keys.getPrivate(), keys.getPublic(),
                java.time.Instant.now().plus(java.time.Duration.ofHours(1)), "Paletter").forPlayer("", name);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create a NetherNet identity", e);
        }
    }

    static void preInitChannel(Channel channel) {
        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.SERVER_BOUND);
        channel.pipeline()
            .addLast("nethernet-frame-codec", new FrameCodec())
            .addLast(CompressionCodec.NAME, new CompressionCodec(new SimpleCompressionStrategy(new NoopCompression()), false));
    }

    static void initPacketCodec(Channel channel) {
        channel.pipeline().addLast("bedrock-packet-codec", new BedrockPacketCodec_v3());
    }

    static void postInitChannel(Channel channel) {
        channel.pipeline().replace("bedrock-batch-encoder", "nethernet-encoder", PacketEncoder.INSTANCE);
    }

    static BedrockPeer peer(Channel channel, BedrockSessionFactory factory) {
        return new BedrockPeer(channel, factory) {
            @Override
            public void enableEncryption(SecretKey secretKey) {
                // DTLS already encrypts the data channel
            }

            @Override
            public int getRakVersion() {
                return EQUIVALENT_RAK_VERSION;
            }
        };
    }

    /** A NetherNet message is one whole (compressed) batch. */
    private static final class FrameCodec extends MessageToMessageCodec<ByteBuf, BedrockBatchWrapper> {
        @Override
        protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
            if (msg.getCompressed() == null) {
                throw new IllegalStateException("Bedrock batch was not compressed");
            }
            // Slice so the channel, which fragments by absolute index, always sees a reader index of 0
            out.add(msg.getCompressed().retainedSlice());
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            if (msg.isReadable()) {
                out.add(BedrockBatchWrapper.newInstance(msg.readRetainedSlice(msg.readableBytes()), null));
            }
        }
    }

    /** Every packet in a batch, and so a message, of its own. */
    @ChannelHandler.Sharable
    private static final class PacketEncoder extends MessageToMessageEncoder<BedrockPacketWrapper> {
        static final PacketEncoder INSTANCE = new PacketEncoder();

        @Override
        protected void encode(ChannelHandlerContext ctx, BedrockPacketWrapper packet, List<Object> out) {
            ByteBuf message = packet.getPacketBuffer();
            if (message == null) {
                throw new IllegalArgumentException("BedrockPacket is not encoded");
            }
            CompositeByteBuf buf = ctx.alloc().compositeDirectBuffer(2);
            BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();
            try {
                ByteBuf header = ctx.alloc().ioBuffer(5);
                VarInts.writeUnsignedInt(header, message.readableBytes());
                buf.addComponent(true, header);
                buf.addComponent(true, message.retain());
                batch.addPacket(packet.retain());
                batch.setUncompressed(buf.retain());
                out.add(batch.retain());
            } finally {
                buf.release();
                batch.release();
            }
        }
    }
}
