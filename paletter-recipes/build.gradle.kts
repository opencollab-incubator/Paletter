plugins {
    `java-library`
    `maven-publish`
}

// API boundary and compatibility, shared with :paletter (gradle/api-boundary.gradle.kts). The descriptor requires
// :paletter, which is on the module path as its jar (Automatic-Module-Name).
extra["apiModulePathPrefixes"] = listOf("checker-qual")
apply(from = rootProject.file("gradle/api-boundary.gradle.kts"))

// Vanilla recipes per data version, on top of Paletter's palettes. :paletter never depends on this module.
dependencies {
    api(project(":paletter"))
    compileOnlyApi(libs.checker.qual)
    "apiModuleJars"(project(":paletter"))
}

java {
    withSourcesJar()
    withJavadocJar()
}

// As :paletter's: every doclint group but `missing`, any other warning fails
tasks.javadoc {
    options {
        this as StandardJavadocDocletOptions
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Werror", true)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "paletter-recipes"
            from(components["java"])
        }
    }
}

// No binary compatibility check (japicmp, as in :paletter) until there is a release to compare with.
