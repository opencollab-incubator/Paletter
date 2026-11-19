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

The definitions cost memory: a vanilla palette's table has 65,536 slots. With int positions the definition array added
256 KB; positions are chars in a table of fewer than 65,536 states (every vanilla table), which halves that to 128 KB (newest
version 14,415,160 to 14,546,272 bytes, oldest 14,843,072 to 14,974,184, the served versions 20,907,864 to 21,301,200; an
extension 50,808 to 50,976 bytes, the creative one 299,144 to 299,200). All within the limits. With char positions, the same
quick run gave `decodeRuntimeId` 48.0 ns (error 9.5) and `blockBehaviour` 61.5 ns (error 13.9), 0 B/op: within the errors of
the int version.

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

| What | Before | After | After memory cuts | After compile release | After state hashes | Limit |
|------|--------|-------|-------------------|-----------------------|--------------------|-------|
| `extend` by a layer of two blocks (no creative placement) | 206 to 601 KB | 40 to 53 KB | 50.8 KB | 50.8 KB | 51.0 KB | 56 KB |
| `extend` by a layer with an item and a block in creative | not measured | about 290 KB | 299 KB | 299 KB | 299 KB | 330 KB |
| vanilla palette, newest version | 17.8 MB | 14.4 MB | 14.4 MB | 14.4 MB | 12.9 MB | 14.2 MB |
| vanilla palette, oldest version | 18.3 MB | 14.8 MB | 14.8 MB | 14.8 MB | 13.6 MB | 14.9 MB |
| a Paletter serving all versions but the oldest, every palette compiled | 27.0 MB | 20.9 MB | 20.9 MB | 18.1 MB | 16.7 MB | 18.4 MB |

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
| what only compiling uses is released once every served palette is compiled: the data source's property store (1.1 MB), the value sets that forward states of other versions (0.8 MB) and those that resolve states per version (0.6 MB), the interner's block and page maps (0.4 MB). A palette compiled later builds what it needs again; `extend` uses none of it | -2.8 MB (20,907,864 to 18,109,600 bytes) | none |

"After compile release" is the code with that last change, measured the same way; the other rows did not change (equal to
the byte). The definitions the runtime id tables gained after that (see "Runtime id decode") raised the served versions to
18,502,936 bytes and the palettes to 14,546,272 and 14,974,184.

**State hashes.** The indexes of block states (the interner's states of every version, the canonical ids, the per-version
check of `blockState` lookups) used to be hash maps keyed by the `NbtMap`s. `NbtMap#hashCode` and `equals` iterate the map,
and iterating makes the `NbtMap` and the unmodifiable map inside it keep an entry set view each: about 48 bytes on every kept
state, once anything hashed it. The indexes are now open addressing tables over a hash of the state's two values
(`BlockStates#hash`: the name's and the `states` compound's cached hash codes), compared by identity first and then by those
two values, so a kept state is never iterated; the per-version check asks the palette's block table. Measured before and
after the change (bytes):

| What | Before | After |
|------|--------|-------|
| served versions | 18,502,936 | 16,697,128 (-1.8 MB) |
| vanilla palette, newest version | 14,546,272 | 12,918,680 (-1.6 MB) |
| vanilla palette, oldest version | 14,974,184 | 13,566,112 (-1.4 MB) |
| `extend`, two blocks / creative | 50,976 / 299,200 | 50,984 / 299,208 |

Of the 1.8 MB, the entry set views are 1.2 MB and the interner's map 0.6 MB (a `ConcurrentHashMap` of 1.15 MB, now two
arrays of 0.5 MB). The hash of a state's values is weak: states of one block whose values differ in two places often share it
(23,960 states, 14,982 hashes, up to 70 alike), which is why a lookup tries identity before comparing values.

HotPath quick run (3 warm-ups, 5 iterations, two forks), same machine, before and after: `blockStateExact` (a state's
`NbtMap` to its handle) 3,262 ns (error 370) to 371 ns (error 48). The other lookups do not touch these indexes and stayed
within their errors, except the item lookups `resolve` (19.5 ns to 25.1 ns) and `resolveItemBehaviour` (85.9 to 97.3 ns);
three forks each again gave 22.3 and 86.7 ns before, 24.7 and 94.8 ns after. Their path (`ItemRegistry#resolve` by canonical
id) did not change, so this is the layout of the heap rather than code, but it is there in every run.

## Startup (JMH, cold)

`StartupBenchmark` is one cold shot per fork: build a `Paletter` and compile the palette of every bundled release version
with `warmUp()`, then look each one up. `withCustomLayer` adds one layer of 20 blocks and
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

`warmUp()` compiles the versions in parallel (one thread per version, at most one per processor; 16 here). Two runs of
sequential (before) and parallel (after) on the same laptop, five cold JVMs each, median:

| Benchmark | Before, run 1 | After, run 1 | Before, run 2 | After, run 2 |
|-----------|---------------|--------------|---------------|--------------|
| `vanilla` | 3,839 ms | 2,423 ms | 2,736 ms | 2,475 ms |
| `withCustomLayer` | 3,873 ms | 3,548 ms | 6,379 ms | 2,667 ms |

The laptop was shared with other builds: the forks of a run range up to 2x (run 2 before, `withCustomLayer`: 3,679 to
7,455 ms), so these are not precise. The steadiest pair, `vanilla` of run 2, gives -261 ms (-10%); the Geyser study
measured -485 ms (-13%) of its `bedrockTotal` for the same change.

An extension that places nothing in creative shares the creative content, the item table and the pages of the canonical
block table with its parent; one that does compiles the creative content again, which is what its 299 KB are. The
`NbtMap`s of the block states are still a good part of a vanilla palette (about 130 bytes each, a `NbtMap` over an
unmodifiable map over the `StateMap`, about 25,000 of them), which is the library's representation.

The lookups themselves allocate nothing, except the item data that `encode` returns: `HotPathTest` counts the bytes of each.
