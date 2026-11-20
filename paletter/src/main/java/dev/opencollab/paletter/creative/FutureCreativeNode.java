package dev.opencollab.paletter.creative;

/**
 * Never instantiated: it makes a switch over {@code CreativeNode} need a {@code default}, as Bedrock may add kinds of
 * creative inventory elements (docs/api.md, "Evolution").
 */
final class FutureCreativeNode implements CreativeNode {
    private FutureCreativeNode() {
    }
}
