plugins {
    java
}

allprojects {
    group = "org.geysermc.paletter"
    version = "0.1.0-SNAPSHOT"
}

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

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
