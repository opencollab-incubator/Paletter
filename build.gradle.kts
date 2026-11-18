// group and version come from gradle.properties
subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        maven("https://repo.opencollab.dev/main")
        // Only the Cloudburst and opencollab snapshots come from here; everything else must resolve elsewhere
        maven("https://repo.opencollab.dev/maven-snapshots") {
            content {
                includeGroupAndSubgroups("org.cloudburstmc")
                includeGroupAndSubgroups("dev.opencollab")
            }
        }
    }

    configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1G"
        // JOL cannot read the fields of records through Unsafe without this
        jvmArgs("-Djol.magicFieldOffset=true")
        // Tests read the repository (data/, the bundled and generated files) through this root, so they are inputs too:
        // a build cache hit must not skip a test whose data changed
        systemProperty("paletter.root", rootProject.projectDir.absolutePath)
        for (dir in listOf("data", "paletter/src", "paletter/api")) {
            inputs.dir(rootProject.file(dir)).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("repository $dir")
        }
    }
}
