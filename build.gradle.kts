plugins {
    java
}

allprojects {
    group = "org.geysermc.paletter"
    version = "1.0.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        maven("https://repo.opencollab.dev/main")
        maven("https://repo.opencollab.dev/maven-snapshots")
    }

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
