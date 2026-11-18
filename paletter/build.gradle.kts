import me.champeau.gradle.japicmp.JapicmpTask

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.japicmp)
    alias(libs.plugins.jmh)
}

dependencies {
    // Cloudburst protocol types are the library's public currency: definitions, ItemData, creative and block property data.
    api(libs.protocol.codec)
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

java {
    withSourcesJar()
    // So `build` builds the Javadoc too, and a broken reference fails before merge
    withJavadocJar()
}

tasks.javadoc {
    options {
        this as StandardJavadocDocletOptions
        // Every doclint group but `missing` (undocumented members, 2,775 when this was set); any other warning fails.
        // The JDK is linked by javadoc's built-in platform links (docs.oracle.com, no download).
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Werror", true)
        // The default cap of 100 hides the real count
        addStringOption("Xmaxwarns", "10000")
    }
}

// Microbenchmarks of the hot paths (src/jmh), not part of the build: `./gradlew :paletter:jmh`, narrowed with
// `-Pjmh.includes=<regex>`. Results go to build/results/jmh.
jmh {
    jmhVersion.set("1.37")
    // -Pjmh.warmups=1 -Pjmh.iterations=2 -Pjmh.forks=1 for a quick run
    fork.set(providers.gradleProperty("jmh.forks").map(String::toInt).orElse(1))
    warmupIterations.set(providers.gradleProperty("jmh.warmups").map(String::toInt).orElse(3))
    warmup.set("1s")
    iterations.set(providers.gradleProperty("jmh.iterations").map(String::toInt).orElse(5))
    timeOnIteration.set("1s")
    providers.gradleProperty("jmh.includes").orNull?.let { includes.set(listOf(it)) }
}

// Publication, POM metadata and the publishing guards: gradle/publishing.gradle.kts, docs/releasing.md
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

// API boundary and compatibility (module-info, API dump, binary compatibility); shared with the other modules, see
// gradle/api-boundary.gradle.kts and docs/api.md.
// The jars the descriptor requires; the checker annotations are static
extra["apiModulePathPrefixes"] = listOf("bedrock-codec", "common-", "nbt-", "checker-qual")
apply(from = rootProject.file("gradle/api-boundary.gradle.kts"))
@Suppress("UNCHECKED_CAST")
val apiPackages = extra["apiPackages"] as List<String>

// Binary compatibility against a RELEASED artifact, resolved from a Maven repository. Configured but skipped while no
// release exists: api/released.version is absent or empty. After the first release, write its version there; the check then
// compares the exported packages with the published jar (japicmp; @Internal and @Experimental API excluded).
val releasedVersion = file("api/released.version").takeIf { it.exists() }?.readText()?.trim().orEmpty()
val releasedApi = configurations.create("releasedApi") {
    isCanBeConsumed = false
    isTransitive = false
}
if (releasedVersion.isNotEmpty()) {
    dependencies.add("releasedApi", "org.geysermc.paletter:paletter:$releasedVersion")
}

val apiCompat = tasks.register<JapicmpTask>("apiCompat") {
    group = "verification"
    description = "Fails on binary incompatible changes of the exported API against the released artifact in api/released.version (skipped before the first release)"
    enabled = releasedVersion.isNotEmpty()
    oldClasspath.from(releasedApi, configurations.runtimeClasspath)
    oldArchives.from(releasedApi)
    newClasspath.from(tasks.jar, configurations.runtimeClasspath)
    newArchives.from(tasks.jar)
    packageIncludes.set(apiPackages)
    annotationExcludes.set(listOf("@org.geysermc.paletter.annotation.Internal", "@org.geysermc.paletter.annotation.Experimental"))
    onlyBinaryIncompatibleModified.set(true)
    failOnModification.set(true)
    ignoreMissingClasses.set(true)
    txtOutputFile.set(layout.buildDirectory.file("reports/japicmp/api-compat.txt"))
}

tasks.check {
    dependsOn(apiCompat)
}
