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
    // The implementation is not API; its types are not documented (and not linked: the API never exposes them, ApiBoundaryTest)
    exclude("org/geysermc/paletter/internal/**")
    options {
        this as StandardJavadocDocletOptions
        // Every doclint group, `missing` included: an undocumented public member, or any other warning, fails the build.
        // The JDK is linked by javadoc's built-in platform links (docs.oracle.com, no download).
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
        // The default cap of 100 hides the real count
        addStringOption("Xmaxwarns", "10000")
    }
}

// Microbenchmarks of the hot paths (src/jmh), not part of the build: `./gradlew :paletter:jmh`, narrowed with
// `-Pjmh.includes=<regex>`. Results go to build/results/jmh.
jmh {
    jmhVersion.set("1.37")
    fork.set(1)
    warmupIterations.set(3)
    warmup.set("1s")
    iterations.set(5)
    timeOnIteration.set("1s")
    providers.gradleProperty("jmh.includes").orNull?.let { includes.set(listOf(it)) }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "paletter"
            from(components["java"])
        }
    }
}

// API boundary and compatibility; see docs/api.md.
//
// src/module/java/module-info.java declares the API: its exports are the API packages. It is compiled against the main
// classes to validate it, but not packaged (Cloudburst's split fastutil packages keep any module graph from resolving),
// so the jar carries an Automatic-Module-Name instead.
val moduleInfo = file("src/module/java/module-info.java")
val exportedPackages: List<String> = Regex("""^\s*exports\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
    .findAll(moduleInfo.readText()).map { it.groupValues[1] }.toList()

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.geysermc.paletter")
    }
}

val compileModuleInfo = tasks.register<JavaCompile>("compileModuleInfo") {
    group = "verification"
    description = "Compiles module-info.java against the main classes, validating the declared API packages"
    val mainClasses = sourceSets.main.get().output.classesDirs
    source(moduleInfo)
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("module-info"))
    modularity.inferModulePath.set(false)
    dependsOn(tasks.compileJava)
    // Only the modules the descriptor requires; adding fastutil's split packages would make resolution fail
    val modulePath = configurations.compileClasspath.get().filter {
        it.name.startsWith("bedrock-codec") || it.name.startsWith("common-") || it.name.startsWith("nbt-") || it.name.startsWith("checker-qual")
    }
    inputs.files(modulePath)
    inputs.files(mainClasses)
    doFirst {
        options.compilerArgs.addAll(listOf(
            "-Xlint:-requires-transitive-automatic",
            "--module-path", modulePath.asPath,
            "--patch-module", "org.geysermc.paletter=" + mainClasses.asPath
        ))
    }
}

// The API dump: api/paletter.api lists every public and protected type, field, constant and method of the exported packages
// (without @Internal and internal.*), generated from the compiled classes by the tools module (apiDump source set). It is
// committed, so a change of the API shows in review: `./gradlew :paletter:apiDump` regenerates it, `apiCheck` (part of
// `check`) fails with the difference when it is stale. See docs/api.md.
val apiDumpFile = file("api/paletter.api")
// The tool's classes and runtime classpath, from the apiDumpElements configuration of :tools (no cross-project access)
val apiDumpTool = configurations.create("apiDumpTool") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}
dependencies.add(apiDumpTool.name, dependencies.project(mapOf("path" to ":tools", "configuration" to "apiDumpElements")))

fun apiDumpTask(name: String, mode: String, text: String) = tasks.register<JavaExec>(name) {
    group = "verification"
    description = text
    classpath = apiDumpTool
    mainClass.set("org.geysermc.paletter.tools.apidump.Main")
    val classes = sourceSets.main.get().output.classesDirs
    dependsOn(tasks.classes)
    inputs.files(classes)
    inputs.file(moduleInfo)
    args(mode, "--classes", classes.asPath, "--module-info", moduleInfo.absolutePath, "--file", apiDumpFile.absolutePath)
    if (mode == "dump") {
        outputs.file(apiDumpFile)
    } else {
        inputs.files(fileTree("api") { include("paletter.api") })
    }
}

val apiDump = apiDumpTask("apiDump", "dump", "Writes the exported API as a text dump (api/paletter.api); commit it with the change")
val apiCheck = apiDumpTask("apiCheck", "check", "Fails with a readable diff if api/paletter.api is not what the compiled classes give")

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
    packageIncludes.set(exportedPackages)
    annotationExcludes.set(listOf("@org.geysermc.paletter.annotation.Internal", "@org.geysermc.paletter.annotation.Experimental"))
    onlyBinaryIncompatibleModified.set(true)
    failOnModification.set(true)
    ignoreMissingClasses.set(true)
    txtOutputFile.set(layout.buildDirectory.file("reports/japicmp/api-compat.txt"))
}

tasks.check {
    dependsOn(compileModuleInfo, apiCheck, apiCompat)
}
