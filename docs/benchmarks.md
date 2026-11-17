# Benchmarks and memory

Numbers for the hot paths and for what a palette retains, so changes can be judged against them. Neither is part of
`./gradlew build` except the memory guard test.

## Speed (JMH)

`paletter/src/jmh` holds the microbenchmarks: `HotPathBenchmark` (the per-packet lookups: `resolveBlock`, `resolve`,
`behaviour().get`, `runtimeId`, `blockState` and `itemVariant` with `Lookup.EXACT`, `encode`, `decode`, `sameStack`) and
`ExtendBenchmark` (extending by a session layer).

```
./gradlew :paletter:jmh                                  # full run, slow and memory-heavy
./gradlew :paletter:jmh -Pjmh.includes=HotPath -Pjmh.warmups=1 -Pjmh.iterations=2 -Pjmh.forks=1   # quick
```

Results go to `paletter/build/results/jmh`. Inputs are visited in a shuffled order, so the numbers include cache misses.
Compare runs on the same machine only.

## Memory (JOL)

`MemoryBaselineTest` measures with JOL (`org.openjdk.jol`, a test dependency only) the retained size of the bundled data
index, one compiled vanilla palette, and what one `extend` with a small layer adds, which is the cost a server pays per
player with content of its own. It prints the numbers; run it with

```
./gradlew :paletter:test --tests '*MemoryBaselineTest' -i
```

and read the `MEMORY` lines. The same test fails when an extension retains more than its limit (a constant in the test,
about 50% above the measured value, which is noted beside it). Raise it only for a reason, and say so in the commit.
