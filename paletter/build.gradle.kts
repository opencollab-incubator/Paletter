import me.champeau.gradle.japicmp.JapicmpTask

plugins {
    `java-library`
    `maven-publish`
    id("me.champeau.gradle.japicmp") version "0.4.6"
}

dependencies {
    // Cloudburst protocol types are the library's public currency: definitions, ItemData, creative and block property data.
    api(libs.protocol.codec)
    api(libs.fastutil.int.`object`)
    api(libs.fastutil.`object`.int)
    api(libs.fastutil.`object`.`object`)
    compileOnlyApi(libs.checker.qual)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Generated sources (component API, vanilla constants) live beside the hand-written ones; see tools/README.md
sourceSets.main {
    java.srcDir("src/generated/java")
}

java {
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1G"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "paletter"
            from(components["java"])
        }
    }
}

// API boundary and compatibility; see API.md.
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
    val main = sourceSets.main.get()
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
    inputs.files(main.output.classesDirs)
    doFirst {
        options.compilerArgs.addAll(listOf(
            "-Xlint:-requires-transitive-automatic",
            "--module-path", modulePath.asPath,
            "--patch-module", "org.geysermc.paletter=" + main.output.classesDirs.asPath
        ))
    }
}

// The API dump: api/paletter.api lists every public and protected type, field, constant and method of the exported packages
// (without @Internal and internal.*), generated from the compiled classes by the tools module (apiDump source set). It is
// committed, so a change of the API shows in review: `./gradlew :paletter:apiDump` regenerates it, `apiCheck` (part of
// `check`) fails with the difference when it is stale. See API.md.
evaluationDependsOn(":tools")
val apiDumpFile = file("api/paletter.api")
val apiDumpTool = project(":tools").extensions.getByType<SourceSetContainer>()["apiDump"].runtimeClasspath

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
