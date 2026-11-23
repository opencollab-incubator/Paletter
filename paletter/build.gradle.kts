// Published: paletter.publish-conventions (POM, repositories, strict Javadoc), paletter.api-conventions (module-info,
// API dump, binary compatibility). See docs/releasing.md and docs/api.md.
plugins {
    id("paletter.publish-conventions")
    id("paletter.api-conventions")
    alias(libs.plugins.jmh)
}

description = "Bedrock Edition palette library: blocks, items and components per data version"

paletterApi {
    // The jars the descriptor requires; the checker annotations are static
    modulePathPrefixes = listOf("bedrock-codec", "common-", "nbt-", "checker-qual")
}

dependencies {
    // Cloudburst protocol types are the library's public currency: definitions, ItemData, creative and block property data.
    api(libs.protocol.codec)
    // The codec's metadata asks for the floating common:3.0.0.Beta15-SNAPSHOT and, through it, math immutable:2.0-SNAPSHOT.
    // Declared here, the pinned builds win for Gradle (the higher version) and for Maven (the nearest declaration)
    api(libs.protocol.common)
    runtimeOnly(libs.math.immutable)
    api(libs.fastutil.int.`object`)
    api(libs.fastutil.`object`.int)
    api(libs.fastutil.`object`.`object`)
    compileOnlyApi(libs.checker.qual)

    // BehaviourTest reads the probe's capture (data/bds/<build>/behaviour-probe/capture.json)
    testImplementation(libs.gson)
    // Memory baseline (MemoryBaselineTest, docs/benchmarks.md): measurement only, not a runtime dependency
    testImplementation(libs.jol.core)
}

// Generated sources (component API, vanilla constants) live beside the hand-written ones; see docs/data-pipeline.md
sourceSets.main {
    java.srcDir("src/generated/java")
}

tasks.javadoc {
    // The implementation is not API; its types are not documented (and not linked: the API never exposes them, ApiBoundaryTest)
    exclude("dev/opencollab/paletter/internal/**")
}

// Microbenchmarks of the hot paths (src/jmh), not part of the build: `./gradlew :paletter:jmh`, narrowed with
// `-Pjmh.includes=<regex>`. Results go to build/results/jmh.
jmh {
    jmhVersion = libs.versions.jmh.core
    // -Pjmh.warmups=1 -Pjmh.iterations=2 -Pjmh.forks=1 for a quick run
    fork = providers.gradleProperty("jmh.forks").map(String::toInt).orElse(1)
    warmupIterations = providers.gradleProperty("jmh.warmups").map(String::toInt).orElse(3)
    warmup = "1s"
    iterations = providers.gradleProperty("jmh.iterations").map(String::toInt).orElse(5)
    timeOnIteration = "1s"
    // StartupBenchmark is one cold shot per fork: it only runs when asked for, with no warm-up
    includes = listOf(providers.gradleProperty("jmh.includes").getOrElse("HotPath|Extend|Contention"))
    jvmArgs = listOf("-Xmx2g")
    // -Pjmh.profilers=gc for the bytes allocated per operation (gc.alloc.rate.norm)
    profilers = providers.gradleProperty("jmh.profilers").map { it.split(",") }.orElse(listOf())
}
