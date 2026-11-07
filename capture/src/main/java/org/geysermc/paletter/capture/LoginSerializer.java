package org.geysermc.paletter.capture;

import org.cloudburstmc.protocol.bedrock.codec.v818.serializer.LoginSerializer_v818;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;

/**
 * Writes the auth part of the login packet verbatim. Cloudburst's serializer only knows a certificate chain or a
 * token, but an offline client needs both fields set (see {@link LoginChain}).
 */
final class LoginSerializer extends LoginSerializer_v818 {

    /** The complete auth JSON, as the client sends it. */
    record RawAuthPayload(String json) implements AuthPayload {
        @Override
        public AuthType getAuthType() {
            return AuthType.SELF_SIGNED;
        }
    }

    @Override
    protected String writeAuthJwt(AuthPayload payload) {
        return payload instanceof RawAuthPayload raw ? raw.json() : super.writeAuthJwt(payload);
    }
}
