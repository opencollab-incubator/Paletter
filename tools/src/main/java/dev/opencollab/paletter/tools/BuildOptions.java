package dev.opencollab.paletter.tools;

import picocli.CommandLine.Option;

/** The way every command names a BDS build: {@code --version} and {@code --channel}. */
class BuildOptions {
    /** Where the builds of the two channels come from and what a run of either is called. */
    enum Channel {
        RELEASE, PREVIEW
    }

    @Option(names = "--version", description = "The BDS build number, e.g. 1.26.63.1 (where a command can resolve one: default the newest of the channel)")
    String version;

    @Option(names = "--channel", defaultValue = "RELEASE", description = "The channel of the build: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
    Channel channel;

    boolean preview() {
        return channel == Channel.PREVIEW;
    }

    /** The build number if one was given, checked. */
    String version() {
        return version == null ? null : BuildNumber.require(version);
    }

    /** The build number, which the command cannot do without. */
    String requireVersion() {
        if (version == null) {
            throw new IllegalArgumentException("--version is required: the BDS build, e.g. 1.26.63.1");
        }
        return BuildNumber.require(version);
    }
}
