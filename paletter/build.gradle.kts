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

// The binary compatibility baseline: api/baseline.version names the API version, api/paletter-<version>-api.jar holds
// its exported classes. Until the first release it is generated from this branch (./gradlew :paletter:updateApiBaseline);
// on every release it is replaced by the released API.
val baselineVersion = file("api/baseline.version").readText().trim()
val baselineJar = file("api/paletter-$baselineVersion-api.jar")

val updateApiBaseline = tasks.register<Jar>("updateApiBaseline") {
    group = "verification"
    description = "Writes the current exported API as the compatibility baseline (api/paletter-<version>-api.jar)"
    destinationDirectory.set(file("api"))
    archiveFileName.set(baselineJar.name)
    from(sourceSets.main.get().output.classesDirs) {
        exportedPackages.forEach { include(it.replace('.', '/') + "/*.class") }
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val apiCompat = tasks.register<JapicmpTask>("apiCompat") {
    group = "verification"
    description = "Fails on binary incompatible changes of the exported API against api/baseline.version"
    oldClasspath.from(baselineJar, configurations.runtimeClasspath)
    oldArchives.from(baselineJar)
    newClasspath.from(tasks.jar, configurations.runtimeClasspath)
    newArchives.from(tasks.jar)
    packageIncludes.set(exportedPackages)
    // Not covered by the promise: @Internal (not API) and @Experimental (may change in a minor release)
    annotationExcludes.set(listOf("@org.geysermc.paletter.annotation.Internal", "@org.geysermc.paletter.annotation.Experimental"))
    onlyBinaryIncompatibleModified.set(true)
    failOnModification.set(true)
    ignoreMissingClasses.set(true)
    txtOutputFile.set(layout.buildDirectory.file("reports/japicmp/api-compat.txt"))
    mustRunAfter(updateApiBaseline)
}

tasks.check {
    dependsOn(compileModuleInfo, apiCompat)
}
