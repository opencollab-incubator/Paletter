package org.geysermc.paletter.runtime;

import org.geysermc.paletter.api.BedrockVersion;
import org.geysermc.paletter.palette.PaletteVersion;

import java.util.Map;
import java.util.Optional;

// Placeholder table for the prototype; the real one is generator-produced from packaged bedrock-samples/dump releases.
public final class SupportedVersions {
    private SupportedVersions() {
    }

    static final Map<BedrockVersion, PaletteVersion> BY_BEDROCK_VERSION = Map.of(
            BedrockVersion.of("26.50"), PaletteVersion.v26_50
    );

    // PaletteVersion stays internal to this module - this is the only bit of the table the
    // cloudburst bridge (or any other consumer) needs, to map a codec's protocol version back.
    public static Optional<BedrockVersion> byProtocolVersion(int protocolVersion) {
        return BY_BEDROCK_VERSION.entrySet().stream()
                .filter(entry -> entry.getValue().protocolVersion() == protocolVersion)
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
