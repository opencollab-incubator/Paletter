package org.geysermc.paletter.api;

public record BedrockVersion(int year, int dropPatchHotfix) implements Comparable<BedrockVersion> {

    public static final BedrockVersion v26_50 = BedrockVersion.of("26.50");

    public static BedrockVersion of(String semantic) {
        String[] parts = semantic.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid semantic version: " + semantic);
        }

        int year = Integer.parseInt(parts[0]);
        int dropPatchHotfix = Integer.parseInt(parts[1]);
        return new BedrockVersion(year, dropPatchHotfix);
    }

    @Override
    public int compareTo(BedrockVersion other) {
        int result = Integer.compare(year, other.year);
        if (result != 0) return result;

        return Integer.compare(dropPatchHotfix, other.dropPatchHotfix);
    }

    @Override
    public String toString() {
        return "%d.%d".formatted(year, dropPatchHotfix);
    }
}
