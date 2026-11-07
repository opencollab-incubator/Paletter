package org.geysermc.paletter.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.lang.JoseException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Offline login (BDS {@code online-mode=false}).
 * <p>
 * Since protocol 818 (1.21.90) the login packet's auth part is a JSON object
 * {@code {"AuthenticationType": n, "Certificate": "<json>", "Token": "<jwt>"}} instead of a bare chain
 * (0 = full Xbox auth, 1 = guest, 2 = self signed). What BDS 1.26.63 accepts offline, verified:
 * <ul>
 *     <li>{@code AuthenticationType = 2}, {@code Certificate = {"chain": [""]}} and {@code Token} = a self signed
 *     ES384 JWT shaped like the multiplayer token ({@code aud api://auth-minecraft-services/multiplayer},
 *     {@code cpk} = the client's P-384 key, {@code xname}, {@code xid}, {@code mid}, {@code leguuid}). The legacy self
 *     signed chain ({@code identityPublicKey} + {@code extraData}) with an empty token is refused with
 *     {@code LOGIN_PACKET_NO_CERT};</li>
 *     <li>client data with every current field. Missing or invalid client data (we had an empty {@code PlayFabId},
 *     {@code DeviceOS = 7}, no {@code FilterProfanity}/{@code ClientIsEditorCapable}) is refused as a malformed packet
 *     ({@code PacketViolationWarning}: "Connection Request invalid. readNoHeader failed! packetId: 1"), regardless of the
 *     auth part.</li>
 * </ul>
 * The token and the client data JWT are signed with the same key, which the JWT headers name ({@code x5u}).
 */
final class LoginChain {
    private final KeyPair keyPair = EncryptionUtils.createKeyPair();
    private final String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    private final String name;
    private final UUID identity;
    private final String playFabId = java.util.HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(8).putLong(ThreadLocalRandom.current().nextLong()).array());

    KeyPair keyPair() {
        return keyPair;
    }

    String name() {
        return name;
    }

    LoginChain(String name) {
        this.name = name;
        this.identity = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    LoginPacket loginPacket(int protocolVersion, String serverAddress, String gameVersion) throws JoseException {
        LoginPacket login = new LoginPacket();
        login.setProtocolVersion(protocolVersion);
        JsonObject certificate = new JsonObject();
        JsonArray chain = new JsonArray();
        chain.add("");
        certificate.add("chain", chain);
        JsonObject auth = new JsonObject();
        auth.addProperty("AuthenticationType", 2);
        auth.addProperty("Certificate", certificate.toString());
        auth.addProperty("Token", token());
        login.setAuthPayload(new LoginSerializer.RawAuthPayload(auth.toString()));
        login.setClientJwt(sign(clientData(serverAddress, gameVersion)));
        return login;
    }

    /** The multiplayer token, self signed: what the authentication service would issue, minus its signature. */
    private String token() throws JoseException {
        long now = System.currentTimeMillis() / 1000;
        JsonObject claims = new JsonObject();
        claims.addProperty("exp", now + 6 * 3600);
        claims.addProperty("nbf", now - 6 * 3600);
        claims.addProperty("aud", "api://auth-minecraft-services/multiplayer");
        claims.addProperty("mid", playFabId);
        claims.addProperty("tid", "");
        claims.addProperty("cpk", publicKey);
        claims.addProperty("xid", "");
        claims.addProperty("xname", name);
        claims.addProperty("leguuid", identity.toString());
        return sign(claims);
    }

    private JsonObject clientData(String serverAddress, String gameVersion) {
        byte[] skin = new byte[64 * 64 * 4];
        for (int i = 3; i < skin.length; i += 4) {
            skin[i] = (byte) 0xff;
        }
        String geometry = """
            {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.humanoid.custom","texture_width":64,"texture_height":64},"bones":[]}]}""";
        Base64.Encoder b64 = Base64.getEncoder();
        JsonObject data = new JsonObject();
        data.add("AnimatedImageData", new JsonArray());
        data.addProperty("ArmSize", "wide");
        data.addProperty("CapeData", "");
        data.addProperty("CapeId", "");
        data.addProperty("CapeImageHeight", 0);
        data.addProperty("CapeImageWidth", 0);
        data.addProperty("CapeOnClassicSkin", false);
        data.addProperty("ClientRandomId", ThreadLocalRandom.current().nextLong());
        data.addProperty("CompatibleWithClientSideChunkGen", false);
        data.addProperty("CurrentInputMode", 1);
        data.addProperty("DefaultInputMode", 1);
        data.addProperty("DeviceId", UUID.randomUUID().toString());
        data.addProperty("DeviceModel", "paletter-capture");
        data.addProperty("DeviceOS", 1);
        data.addProperty("GameVersion", gameVersion);
        data.addProperty("GraphicsMode", 1);
        data.addProperty("GuiScale", 0);
        data.addProperty("FilterProfanity", false);
        data.addProperty("ClientEditorConnectionIntent", 0);
        data.addProperty("ClientIsEditorCapable", false);
        data.addProperty("LanguageCode", "en_US");
        data.addProperty("MaxViewDistance", 16);
        data.addProperty("MemoryTier", 0);
        data.addProperty("OverrideSkin", false);
        data.add("PersonaPieces", new JsonArray());
        data.addProperty("PersonaSkin", false);
        data.add("PieceTintColors", new JsonArray());
        data.addProperty("PlatformOfflineId", "");
        data.addProperty("PlatformOnlineId", "");
        data.addProperty("PlatformType", 0);
        data.addProperty("PlayFabId", playFabId);
        data.addProperty("PremiumSkin", false);
        data.addProperty("ProfileHash", "");
        data.addProperty("PartyId", "");
        data.addProperty("IsPartyLeader", false);
        data.addProperty("SelfSignedId", UUID.randomUUID().toString());
        data.addProperty("ServerAddress", serverAddress);
        data.addProperty("SkinAnimationData", "");
        data.addProperty("SkinColor", "#0");
        data.addProperty("SkinData", b64.encodeToString(skin));
        data.addProperty("SkinGeometryData", b64.encodeToString(geometry.getBytes(StandardCharsets.UTF_8)));
        data.addProperty("SkinGeometryDataEngineVersion", b64.encodeToString("0.0.0".getBytes(StandardCharsets.UTF_8)));
        data.addProperty("SkinId", UUID.randomUUID() + ".Custom");
        data.addProperty("SkinImageHeight", 64);
        data.addProperty("SkinImageWidth", 64);
        data.addProperty("SkinResourcePatch", b64.encodeToString(
            "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}".getBytes(StandardCharsets.UTF_8)));
        data.addProperty("ThirdPartyName", name);
        data.addProperty("ThirdPartyNameOnly", false);
        data.addProperty("TrustedSkin", false);
        data.addProperty("UIProfile", 0);
        return data;
    }

    private String sign(JsonObject claims) throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        jws.setHeader("x5u", publicKey);
        jws.setPayload(claims.toString());
        jws.setKey(keyPair.getPrivate());
        return jws.getCompactSerialization();
    }

    /**
     * Derives the shared key from the server's handshake JWT: its header names the server key, its payload the salt.
     */
    SecretKey handshakeKey(String jwt) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setCompactSerialization(jwt);
        String serverKey = jws.getHeader("x5u");
        JsonObject payload = JsonParser.parseString(jws.getUnverifiedPayload()).getAsJsonObject();
        byte[] salt = Base64.getDecoder().decode(payload.get("salt").getAsString());
        return EncryptionUtils.getSecretKey(keyPair.getPrivate(), EncryptionUtils.parseKey(serverKey), salt);
    }
}
