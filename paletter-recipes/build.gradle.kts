import me.champeau.gradle.japicmp.JapicmpTask

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.japicmp)
}

// API boundary and compatibility, shared with :paletter (gradle/api-boundary.gradle.kts). The descriptor requires
// :paletter, which is on the module path as its jar (Automatic-Module-Name).
extra["apiModulePathPrefixes"] = listOf("checker-qual")
apply(from = rootProject.file("gradle/api-boundary.gradle.kts"))
@Suppress("UNCHECKED_CAST")
val apiPackages = extra["apiPackages"] as List<String>


// Vanilla recipes per data version, on top of Paletter's palettes. :paletter never depends on this module.
dependencies {
    api(project(":paletter"))
    compileOnlyApi(libs.checker.qual)
    "apiModuleJars"(project(":paletter"))
}

java {
    withSourcesJar()
    // withJavadocJar() and the javadoc settings of :paletter come with the first public type: javadoc fails without one
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "paletter-recipes"
            from(components["java"])
        }
    }
}

// Binary compatibility against a RELEASED artifact, resolved from a Maven repository. Configured but skipped while no
// release exists: api/released.version is absent or empty. After the first release, write its version there; the check then
// compares the exported packages with the published jar (japicmp; @Internal and @Experimental API excluded).
val releasedVersion = file("api/released.version").takeIf { it.exists() }?.readText()?.trim().orEmpty()
val releasedApi = configurations.create("releasedApi") {
    isCanBeConsumed = false
    isTransitive = false
}
if (releasedVersion.isNotEmpty()) {
    dependencies.add("releasedApi", "org.geysermc.paletter:paletter-recipes:$releasedVersion")
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
