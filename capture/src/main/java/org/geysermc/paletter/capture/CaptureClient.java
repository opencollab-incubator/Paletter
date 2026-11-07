package org.geysermc.paletter.capture;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPClientSignaling;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemVersion;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.packet.AvailableEntityIdentifiersPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PacketViolationWarningPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A minimal headless Bedrock client: logs in (offline), accepts the resource pack negotiation without downloading
 * anything and records the registry packets the server sends after start game. It never spawns or moves.
 */
final class CaptureClient {

    /** Everything recorded from one session. Packets are kept as decoded, nothing is post processed. */
    static final class Result {
        StartGamePacket startGame;
        final List<ItemDefinition> items = new ArrayList<>();
        CreativeContentPacket creative;
        AvailableEntityIdentifiersPacket entityIdentifiers;
        BiomeDefinitionListPacket biomes;
        CraftingDataPacket crafting;
        ResourcePacksInfoPacket packsInfo;
        ResourcePackStackPacket packStack;
        final Map<String, Integer> packetCounts = new TreeMap<>();
    }

    private final BedrockCodec codec;
    private final InetSocketAddress address;
    private final LoginChain login = new LoginChain("PaletterCapture");
    private final Result result = new Result();
    private final CompletableFuture<Result> done = new CompletableFuture<>();
    private final Map<Integer, ItemDefinition> itemsById = new ConcurrentHashMap<>();
    private volatile long completeAt = Long.MAX_VALUE;

    private final boolean netherNet;

    CaptureClient(BedrockCodec codec, InetSocketAddress address) {
        this(codec, address, false);
    }

    /**
     * @param netherNet connect over NetherNet (HTTP signaling on the address, then WebRTC) instead of RakNet
     */
    CaptureClient(BedrockCodec codec, InetSocketAddress address, boolean netherNet) {
        this.codec = codec;
        this.address = address;
        this.netherNet = netherNet;
    }

    Result run(long timeoutSeconds) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap bootstrap = new Bootstrap().group(group);
            if (netherNet) {
                bootstrap.channelFactory(NetherNetChannelFactory.client(NetherNetHTTPClientSignaling::new))
                    .option(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, 15_000)
                    // A self signed identity for the offer (BDS refuses unsigned offers with MultiplayerDisabled), with
                    // the login chain's key: BDS binds the chain to the transport identity (NOT_AUTHENTICATED otherwise)
                    .option(NetherChannelOption.NETHER_CLIENT_IDENTITY, NetherNet.identity(login.keyPair(), login.name()));
            } else {
                bootstrap.channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                    .option(RakChannelOption.RAK_PROTOCOL_VERSION, codec.getRaknetProtocolVersion())
                    .option(RakChannelOption.RAK_ORDERING_CHANNELS, 1)
                    .option(RakChannelOption.RAK_SESSION_TIMEOUT, 10_000L);
            }
            Channel channel = bootstrap
                .handler(new BedrockClientInitializer() {
                    @Override
                    protected void preInitChannel(Channel channel) throws Exception {
                        if (netherNet) {
                            NetherNet.preInitChannel(channel);
                        } else {
                            super.preInitChannel(channel);
                        }
                    }

                    @Override
                    protected void initPacketCodec(Channel channel) throws Exception {
                        if (netherNet) {
                            NetherNet.initPacketCodec(channel);
                        } else {
                            super.initPacketCodec(channel);
                        }
                    }

                    @Override
                    protected void postInitChannel(Channel channel) throws Exception {
                        super.postInitChannel(channel);
                        if (netherNet) {
                            NetherNet.postInitChannel(channel);
                        }
                    }

                    @Override
                    protected org.cloudburstmc.protocol.bedrock.BedrockPeer createPeer(Channel channel) {
                        return netherNet ? NetherNet.peer(channel, this::createSession) : super.createPeer(channel);
                    }

                    @Override
                    protected void initSession(BedrockClientSession session) {
                        session.setCodec(codec);
                        session.getPeer().getCodecHelper().setEncodingSettings(org.cloudburstmc.protocol.bedrock.data.EncodingSettings.CLIENT);
                        session.getPeer().getCodecHelper().setItemDefinitions(new ItemRegistry());
                        session.getPeer().getCodecHelper().setBlockDefinitions(new AnyBlockRegistry());
                        session.setPacketHandler(new Handler(session));
                        RequestNetworkSettingsPacket request = new RequestNetworkSettingsPacket();
                        request.setProtocolVersion(codec.getProtocolVersion());
                        session.sendPacketImmediately(request);
                    }
                })
                .connect(address)
                .syncUninterruptibly()
                .channel();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!done.isDone()) {
                if (System.currentTimeMillis() >= completeAt) {
                    done.complete(result);
                    break;
                }
                if (System.nanoTime() > deadline) {
                    done.completeExceptionally(new TimeoutException("Timed out; received so far: " + result.packetCounts));
                    break;
                }
                Thread.sleep(50);
            }
            channel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            return done.get();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    private void checkComplete() {
        if (result.startGame != null && !result.items.isEmpty() && result.creative != null && completeAt == Long.MAX_VALUE) {
            // Give the server a moment for the remaining packets of the join sequence (biomes, recipes, ...)
            completeAt = System.currentTimeMillis() + 3000;
        }
    }

    private final class Handler implements BedrockPacketHandler {
        private final BedrockClientSession session;

        Handler(BedrockClientSession session) {
            this.session = session;
        }

        @Override
        public PacketSignal handlePacket(BedrockPacket packet) {
            result.packetCounts.merge(packet.getClass().getSimpleName(), 1, Integer::sum);
            try {
                return packet.handle(this);
            } catch (Exception e) {
                done.completeExceptionally(e);
                return PacketSignal.HANDLED;
            }
        }

        @Override
        public PacketSignal handle(NetworkSettingsPacket packet) {
            session.setCompression(packet.getCompressionAlgorithm());
            try {
                session.sendPacketImmediately(login.loginPacket(codec.getProtocolVersion(),
                    address.getHostString() + ":" + address.getPort(), codec.getMinecraftVersion()));
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ServerToClientHandshakePacket packet) {
            try {
                session.enableEncryption(login.handshakeKey(packet.getJwt()));
            } catch (Exception e) {
                done.completeExceptionally(e);
                return PacketSignal.HANDLED;
            }
            session.sendPacketImmediately(new ClientToServerHandshakePacket());
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(PlayStatusPacket packet) {
            System.out.println("  play status " + packet.getStatus());
            if (packet.getStatus() != PlayStatusPacket.Status.LOGIN_SUCCESS && packet.getStatus() != PlayStatusPacket.Status.PLAYER_SPAWN) {
                done.completeExceptionally(new IllegalStateException("Login failed: " + packet.getStatus()));
            }
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(PacketViolationWarningPacket packet) {
            System.out.println("  server reports packet violation: " + packet);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(DisconnectPacket packet) {
            done.completeExceptionally(new IllegalStateException("Disconnected: " + packet.getReason() + " " + packet.getKickMessage()));
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ResourcePacksInfoPacket packet) {
            result.packsInfo = packet;
            // We have none of them, but claiming to have all skips the download
            ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
            response.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
            session.sendPacket(response);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ResourcePackStackPacket packet) {
            result.packStack = packet;
            ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
            response.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
            session.sendPacket(response);
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(StartGamePacket packet) {
            result.startGame = packet;
            RequestChunkRadiusPacket radius = new RequestChunkRadiusPacket();
            radius.setRadius(2);
            radius.setMaxRadius(2);
            session.sendPacket(radius);
            checkComplete();
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(ItemComponentPacket packet) {
            result.items.addAll(packet.getItems());
            for (ItemDefinition item : packet.getItems()) {
                itemsById.put(item.getRuntimeId(), item);
            }
            checkComplete();
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(CreativeContentPacket packet) {
            result.creative = packet;
            checkComplete();
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(AvailableEntityIdentifiersPacket packet) {
            result.entityIdentifiers = packet;
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(BiomeDefinitionListPacket packet) {
            result.biomes = packet;
            return PacketSignal.HANDLED;
        }

        @Override
        public PacketSignal handle(CraftingDataPacket packet) {
            result.crafting = packet;
            return PacketSignal.HANDLED;
        }

        @Override
        public void onDisconnect(CharSequence reason) {
            if (!done.isDone() && completeAt == Long.MAX_VALUE) {
                done.completeExceptionally(new IllegalStateException("Connection closed: " + reason));
            }
        }
    }

    /** Item definitions as the item registry packet announced them; unknown ids decode as placeholders. */
    private final class ItemRegistry implements DefinitionRegistry<ItemDefinition> {
        @Override
        public ItemDefinition getDefinition(int runtimeId) {
            ItemDefinition definition = itemsById.get(runtimeId);
            return definition != null ? definition
                : new SimpleItemDefinition("unknown:" + runtimeId, runtimeId, ItemVersion.LEGACY, false, null);
        }

        @Override
        public ItemDefinition getDefinition(String identifier) {
            for (ItemDefinition definition : itemsById.values()) {
                if (definition.getIdentifier().equals(identifier)) {
                    return definition;
                }
            }
            return new SimpleItemDefinition(identifier, -1, ItemVersion.LEGACY, false, null);
        }

        @Override
        public boolean isRegistered(ItemDefinition definition) {
            return true;
        }
    }

    /** Keeps block runtime ids (hashes on BDS with block-network-ids-are-hashes=true) as they are. */
    record RawBlock(int runtimeId) implements BlockDefinition {
        @Override
        public int getRuntimeId() {
            return runtimeId;
        }
    }

    private static final class AnyBlockRegistry implements DefinitionRegistry<BlockDefinition> {
        @Override
        public BlockDefinition getDefinition(int runtimeId) {
            return new RawBlock(runtimeId);
        }

        @Override
        public boolean isRegistered(BlockDefinition definition) {
            return true;
        }
    }
}
