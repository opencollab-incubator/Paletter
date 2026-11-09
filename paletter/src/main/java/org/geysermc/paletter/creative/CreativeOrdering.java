package org.geysermc.paletter.creative;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.TreeSet;

/**
 * How a layer places its custom items and block items in the creative inventory when their {@link CreativePlacement}
 * has no anchor ({@link CreativePlacement#after()} is null). A placement with an anchor is an explicit override and
 * goes right after the anchor under every policy.
 * <p>
 * The rule BDS follows, and the evidence for it, is in {@code data/bds/CREATIVE-ORDER.md}.
 *
 * @since 1.0.0
 */
public enum CreativeOrdering {
    /**
     * Exactly where Bedrock Dedicated Server (Windows build, verified on 1.26.63.1) puts add-on content, the default:
     * <ol>
     *     <li>Block items first, in the iteration order of BDS's map of data driven blocks (see {@link #blockOrder}),
     *     then items in the order the layer defines them.</li>
     *     <li>With a group: at the end of the group of that name <i>in the placement's category</i>; a {@code minecraft:}
     *     prefix of the group name is ignored ({@code minecraft:itemGroup.name.sword} is the vanilla sword group). If the
     *     category has no such group, a new one is created at the end of the category, also when a group of that name
     *     exists in another category. New groups have no icon (BDS sends air; Paletter compiles the default icon, the
     *     first stack).</li>
     *     <li>Without a group: loose, at the end of the category.</li>
     * </ol>
     * A custom block without a {@link org.geysermc.paletter.block.CustomBlock#creative() creative placement} but with
     * a {@link org.geysermc.paletter.block.CustomBlock.Menu menu category} is placed by its menu category, like BDS
     * does.
     */
    BDS,
    /**
     * The behaviour before 1.0.0: items, then block items, each in definition order; a group is looked up by name in
     * any category and created at the end of the category if missing; loose stacks go to the end of the category.
     * Blocks without a creative placement are not placed.
     */
    APPEND;

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * The order in which this policy places custom block items.
     * <p>
     * For {@link #BDS}: BDS keeps data driven blocks in a {@code std::unordered_map} keyed by identifier (MSVC:
     * FNV-1a 64 hash, power of two bucket count starting at 8, growing x8 below 512 buckets and x2 above, load factor 1)
     * and adds creative entries for custom blocks while iterating it. Custom blocks are inserted first, by block id,
     * then the vanilla data driven blocks in descending identifier order. This reproduces the map's iteration order.
     * BDS numbers custom blocks in descending order of their <i>file names</i>; Paletter, which has no files, numbers
     * them by identifier, descending, which is the same for the usual add-on layout of one file per block named after
     * it (see {@code data/bds/CREATIVE-ORDER.md}).
     *
     * @param customBlocks every custom block identifier of the palette, in block id order
     * @param vanillaDataDrivenBlocks the identifiers of the version's vanilla data driven blocks
     * @return {@code customBlocks} in placement order
     * @since 1.0.0
     */
    public @NonNull List<String> blockOrder(@NonNull List<String> customBlocks, @NonNull Collection<String> vanillaDataDrivenBlocks) {
        if (this == APPEND) {
            return List.copyOf(customBlocks);
        }
        MsvcUnorderedMap map = new MsvcUnorderedMap();
        customBlocks.forEach(map::insert);
        new TreeSet<>(vanillaDataDrivenBlocks).descendingSet().forEach(map::insert);
        List<String> order = new ArrayList<>(customBlocks.size());
        Set<String> custom = new HashSet<>(customBlocks);
        for (String key : map.keys()) {
            if (custom.contains(key)) {
                order.add(key);
            }
        }
        return order;
    }

    static long fnv1a64(String key) {
        long hash = FNV_OFFSET_BASIS;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /**
     * The element order of MSVC's {@code std::unordered_map}: one list of all elements, each bucket a contiguous
     * range of it. A new element goes to the front of its bucket's range, or to the end of the list if its bucket is
     * empty. A rehash walks the list and moves every element to the front of its new bucket's range, or leaves it in
     * place (relative to the elements before it) if the bucket is still empty.
     */
    private static final class MsvcUnorderedMap {
        private record Node(String key, long hash) {
        }

        private List<Node> nodes = new LinkedList<>();
        private final Set<String> present = new HashSet<>();
        private int buckets = 8;

        void insert(String key) {
            if (!present.add(key)) {
                return;
            }
            int size = present.size();
            if (size > buckets) {
                int grown = buckets < 512 ? buckets * 8 : buckets * 2;
                while (grown < size) {
                    grown *= 2;
                }
                buckets = grown;
                List<Node> rehashed = new LinkedList<>();
                nodes.forEach(node -> place(rehashed, node));
                nodes = rehashed;
            }
            place(nodes, new Node(key, fnv1a64(key)));
        }

        List<String> keys() {
            return nodes.stream().map(Node::key).toList();
        }

        private void place(List<Node> list, Node node) {
            long mask = buckets - 1;
            long bucket = node.hash & mask;
            ListIterator<Node> iterator = list.listIterator();
            while (iterator.hasNext()) {
                if ((iterator.next().hash & mask) == bucket) {
                    iterator.previous();
                    iterator.add(node);
                    return;
                }
            }
            list.add(node);
        }
    }
}
