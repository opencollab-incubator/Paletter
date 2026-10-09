package org.geysermc.paletter.palette;

/**
 * Represents a specific version of a bedrock palette
 * @param defaultVersion The version that will be displayed and downloaded
 * @param protocolVersion The protocol version this palette belongs to
 * @param dataCommitHash The commit hash to get the palettes from
 * @param dataRepo The data repo storing the data
 */
public record PaletteVersion(
        String defaultVersion,
        int protocolVersion,
        String dataCommitHash,
        String dataRepo
) {
    public PaletteVersion(String defaultVersion,
                          int protocolVersion,
                          String dataCommitHash) {
        this(defaultVersion, protocolVersion, dataCommitHash, "CloudburstMC/Data");
    }

    public static final PaletteVersion v26_50 = new PaletteVersion(
            "26.50", 2193,
            "7046791ae9fcf056a3b26cd6605ffc1db6d97357"
    );
}
