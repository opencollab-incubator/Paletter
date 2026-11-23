dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
    }
    // The main build's catalog, for the plugin versions
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
