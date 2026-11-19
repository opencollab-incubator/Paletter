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
```

Results go to `paletter/build/results/jmh`. Inputs are visited in a shuffled order, so the numbers include cache misses.
Compare runs on the same machine only.

The table is from the quick run (2 warm-ups, 4 iterations, one fork) on a shared laptop, so the errors are large: read
`extend` as "a few hundred microseconds", not as a precise figure. Before is the code before the runtime scaling work
(`073d43f9`), after is the code with it.

| Benchmark | Before | After |
|-----------|--------|-------|
| `ExtendBenchmark.extend` (a layer of one block) | 1,335 us | 186 us |
| `ExtendBenchmark.extendStacked` (a layer over 20 custom blocks) | 1,894 us | 295 us |
| `HotPathBenchmark.resolveState` | 79 ns (error 162) | 19 ns |
| `HotPathBenchmark.resolve` (an item) | 28 ns | 27 ns |
| `HotPathBenchmark.resolveItemBehaviour` | 172 ns (error 217) | 105 ns |
| `HotPathBenchmark.blockBehaviour` | 114 ns (error 149) | 72 ns |
| `ContentionBenchmark.extend` (eight threads, per call) | not measured | 532 us |
| `ContentionBenchmark.resolveStateBehaviour` (eight threads) | not measured | 37 ns |
| `ContentionBenchmark.resolveItemBehaviour` (eight threads) | not measured | 195 ns |

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

| What | Before | After | Limit |
|------|--------|-------|-------|
| `extend` by a layer of two blocks (no creative placement) | 206 to 601 KB | 40 to 53 KB | 100 KB |
| `extend` by a layer with an item and a block in creative | not measured | about 290 KB | 450 KB |
| vanilla palette, newest version | 17.8 MB | 17.1 MB | 18.8 MB |
| vanilla palette, oldest version | 18.3 MB | 17.6 MB | 19.4 MB |
| a Paletter serving all versions but the oldest, every palette compiled | 27.0 MB | 23.7 MB | 26.0 MB |

The last row is what an application serving a few versions holds (the shared bundled instance it loads through included).
From before to after: the map that interns states across versions is released once the served versions are loaded (it
was kept for good, because the bundled instance waited for a version nobody loaded), 1.2 MB; the block states of the
creative stacks are the version's own instances and identifiers are shared strings, 2.1 MB (0.7 MB of a single palette).

An extension that places nothing in creative shares the creative content, the item table and the pages of the canonical
block table with its parent; one that does compiles the creative content again, which is what its 290 KB are. A vanilla
palette is mostly the `NbtMap`s of its block states (about half of the 17.8 MB), which is the library's representation.

The lookups themselves allocate nothing, except the item data that `encode` returns: `HotPathTest` counts the bytes of each.
