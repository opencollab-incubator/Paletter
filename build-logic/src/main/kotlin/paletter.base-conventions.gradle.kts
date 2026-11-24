// Every module: Java, repositories, the build JDK, JUnit and what the tests read from the repository.
// The published modules add paletter.publish-conventions on top; tools and examples use only this one.
plugins {
    java
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

repositories {
    mavenCentral()
    // Only what Maven Central does not have: the Cloudburst and opencollab artifacts (their releases and snapshots) and
    // the natives Cloudburst's network library needs; everything else must resolve from Maven Central
    maven("https://repo.opencollab.dev/main") {
        content {
            includeGroupAndSubgroups("org.cloudburstmc")
            includeGroupAndSubgroups("dev.opencollab")
            includeGroup("com.nukkitx")
        }
    }
    // Only the Cloudburst and opencollab snapshots come from here; everything else must resolve elsewhere
    maven("https://repo.opencollab.dev/maven-snapshots") {
        content {
            includeGroupAndSubgroups("org.cloudburstmc")
            includeGroupAndSubgroups("dev.opencollab")
        }
    }
}

java {
    // The JDK that builds, tests and runs everything. The bytecode level of the published modules is a separate
    // setting: paletter.javaTarget in gradle.properties (paletter.publish-conventions)
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1G"
    // JOL cannot read the fields of records through Unsafe without this
    jvmArgs("-Djol.magicFieldOffset=true")
    // Tests read the repository through the paletter.root system property. The path itself is no input (a cache entry
    // must not depend on where the checkout is); what is read under it is, so a cache hit never skips a test whose
    // data changed
    val root = layout.settingsDirectory
    jvmArgumentProviders.add(RepositoryRootProperty(root.asFile))
    for (dir in listOf("data", "paletter/src", "paletter/api")) {
        inputs.dir(root.dir(dir)).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("repository $dir")
    }
    // The API boundary tests read their module's build script
    inputs.file(layout.projectDirectory.file("build.gradle.kts")).withPathSensitivity(PathSensitivity.NONE)
        .withPropertyName("build script")
}
