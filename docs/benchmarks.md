# Benchmarks and memory

Numbers for the hot paths and for what a palette retains, so changes can be judged against them. Neither is part of
`./gradlew build` except the memory guard test.

## Speed (JMH)

`paletter/src/jmh` holds the microbenchmarks: `HotPathBenchmark` (the per-packet lookups: `resolveBlock`, `resolve`,
`behaviour().get`, `runtimeId`, `blockState` and `itemVariant` with `Lookup.EXACT`, `encode`, `decode`, `sameStack`),
`ExtendBenchmark` (extending by a session layer) and `ContentionBenchmark` (the main lookups and `extend` with eight threads
on one palette: a number far above the single threaded one means a lock or a written cache line is shared).

```
./gradlew :paletter:jmh                                  # full run, slow and memory-heavy
./gradlew :paletter:jmh -Pjmh.includes=HotPath -Pjmh.warmups=1 -Pjmh.iterations=2 -Pjmh.forks=1   # quick
./gradlew :paletter:jmh -Pjmh.includes=HotPath -Pjmh.profilers=gc   # with the bytes allocated per operation
```

Results go to `paletter/build/results/jmh`. Inputs are visited in a shuffled order, so the numbers include cache misses.
Compare runs on the same machine only.

The table is from the quick run (2 warm-ups, 4 iterations, one fork) on a shared laptop, so the errors are large: read
`extend` as "a few hundred microseconds", not as a precise figure. Before is the code before the runtime scaling work
(`073d43f9`), after is the code with it.

| Benchmark | Before | After | After memory cuts |
|-----------|--------|-------|-------------------|
| `ExtendBenchmark.extend` (a layer of one block) | 1,335 us | 186 us | 121 us |
| `ExtendBenchmark.extendStacked` (a layer over 20 custom blocks) | 1,894 us | 295 us | 173 us |
| `HotPathBenchmark.resolveState` | 79 ns (error 162) | 19 ns | 13 ns |
| `HotPathBenchmark.resolve` (an item) | 28 ns | 27 ns | 23 ns |
| `HotPathBenchmark.resolveItemBehaviour` | 172 ns (error 217) | 105 ns | 83 ns |
| `HotPathBenchmark.blockBehaviour` | 114 ns (error 149) | 72 ns | 56 ns |
| `ContentionBenchmark.extend` (eight threads, per call) | not measured | 532 us | 195 us (error 283) |
| `ContentionBenchmark.resolveStateBehaviour` (eight threads) | not measured | 37 ns | 20 ns |
| `ContentionBenchmark.resolveItemBehaviour` (eight threads) | not measured | 195 ns | 57 ns |

"After memory cuts" is the code at `7899a97e` (the memory cuts merged), the same quick run on the same machine. Other lookups of
the run: `runtimeIdOfState` 8 ns, `byRuntimeId` 51 ns, `encode` 53 ns, `decode` 60 ns, `itemVariantExact` 117 ns,
`blockStateExactValues` 226 ns, `blockStateExact` 2.5 us, `vanillaKey` 174 ns, `customKey` 34 ns, `customRuntimeId` 26 ns,
`sameStack` 7 ns.

**Runtime id decode.** `decodeRuntimeId` is a block runtime id to its definition through the registry the codec decodes with
(`Palette#applyTo`). Each block table keeps the definition next to the key (`RuntimeIdTable`: parallel key, definition and
position arrays, a power of two in size, at most half full), so the decode reads the key and the definition of one slot,
independent reads, instead of the position and then the definition at it. A layered palette still asks its layers' small
tables first. Quick run (2 warm-ups, 4 iterations, one fork, `-Pjmh.profilers=gc`), same machine, before and after:

| Benchmark | Before | After | Allocation |
|-----------|--------|-------|------------|
| `HotPathBenchmark.decodeRuntimeId` | 52.5 ns (error 10.6) | 45.9 ns (error 1.8) | 0 B/op before and after |
| `HotPathBenchmark.byRuntimeId` | 54.8 ns (error 10.2) | 46.6 ns (error 9.4) | 0 B/op before and after |
| `HotPathBenchmark.blockBehaviour` (by position, unchanged path) | 59.7 ns (error 11.5) | 63.6 ns (error 9.6) | 0 B/op before and after |

The definitions cost memory: a vanilla palette's table has 65,536 slots, and the definition array adds 256 KB to it (newest
version 14,415,160 to 14,677,344 bytes, oldest 14,843,072 to 15,105,256, the served versions 20,907,864 to 21,694,416; an
extension 50,808 to 51,104 bytes, the creative one 299,144 to 299,216). All within the limits.

The lookups changed little: the differences are inside the errors. These are warm figures; the first `extend` of a JVM, before
the code is compiled, takes several milliseconds (5.7 ms was noted for the code before).

## Memory (JOL)

`MemoryBaselineTest` measures with JOL (`org.openjdk.jol`, a test dependency only) the retained size of one compiled vanilla
palette (of the newest and of the oldest version, in a JVM of its own, so what other tests loaded does not count), and what
one `extend` adds, which is the cost a server pays per player with content of its own. It prints the numbers; run it with

```
./gradlew :paletter:test --tests '*MemoryBaselineTest' -i
```

and read the `MEMORY` lines. The same test fails when something retains more than its limit (a constant in the test, with the
measured value noted beside it). Raise one only for a reason, and say so in the commit.

| What | Before | After | After memory cuts | Limit |
|------|--------|-------|-------------------|-------|
| `extend` by a layer of two blocks (no creative placement) | 206 to 601 KB | 40 to 53 KB | 50.8 KB | 56 KB |
| `extend` by a layer with an item and a block in creative | not measured | about 290 KB | 299 KB | 330 KB |
| vanilla palette, newest version | 17.8 MB | 14.4 MB | 14.4 MB | 15.9 MB |
| vanilla palette, oldest version | 18.3 MB | 14.8 MB | 14.8 MB | 16.3 MB |
| a Paletter serving all versions but the oldest, every palette compiled | 27.0 MB | 20.9 MB | 20.9 MB | 23.0 MB |

Every measurement runs once in a JVM of its own with pinned flags (serial collector, a young generation that holds the whole
run), so no collection comes between the two JOL walks of an extension. In the shared test JVM the same walks gave anything
from -981 KB to +946 KB; in the forked JVM three runs gave 50,808 and 299,144 bytes every time (spread 0). The limits are the
measured value plus about 10%. The "After" figures of the vanilla and served rows were taken after the memory cuts already, so
the new column repeats them (re-measured, equal to the byte).

The last row is what an application serving a few versions holds (the shared bundled instance it loads through included).
From before to after:

| Change | Served versions | One palette |
|--------|-----------------|-------------|
| the map that interns states across versions is released once the served versions are loaded (it was kept for good: the bundled instance waited for a version nobody loaded) | -1.2 MB | none |
| the block states of the creative stacks are the version's own instances; identifiers are shared strings | -2.1 MB | -0.7 MB |
| a block state's `NbtMap` holds its two entries in two fields (`StateMap`), not in a `LinkedHashMap` | -2.8 MB | -2.7 MB |

## Startup (JMH, cold)

`StartupBenchmark` is one cold shot per fork: build a `Paletter` and compile the palette of every bundled release version, the
way Geyser does at startup (`BlockRegistryPopulator.registerBedrockBlocks`). `withCustomLayer` adds one layer of 20 blocks and
20 items. It is left out of the other runs (a warm-up would defeat it); run it with

```
./gradlew :paletter:jmh -Pjmh.includes=Startup -Pjmh.warmups=0 -Pjmh.iterations=1 -Pjmh.forks=5
```

| Benchmark | Five cold JVMs (ms) | Median |
|-----------|---------------------|--------|
| `vanilla` | 2,598, 2,714, 2,483, 2,526, 2,534 | 2,534 ms |
| `withCustomLayer` | 2,695, 2,683, 3,024, 2,873, 2,920 | 2,873 ms |

The JVMs run with `-Xmx2g` on the same shared laptop; the spread is about 10%, so read the custom layer's cost (about 0.3 s)
as an estimate.

An extension that places nothing in creative shares the creative content, the item table and the pages of the canonical
block table with its parent; one that does compiles the creative content again, which is what its 299 KB are. The
`NbtMap`s of the block states are still a good part of a vanilla palette (about 130 bytes each, a `NbtMap` over an
unmodifiable map over the `StateMap`, about 25,000 of them), which is the library's representation.

The lookups themselves allocate nothing, except the item data that `encode` returns: `HotPathTest` counts the bytes of each.
