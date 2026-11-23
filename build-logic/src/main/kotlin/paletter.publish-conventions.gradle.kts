// A published module (paletter, paletter-recipes): Indra, the bytecode target, the strict Javadoc, the POM, the
// repositories on repo.opencollab.dev, optional signing and the snapshot dependency check. See docs/releasing.md.
// The artifact id is the project name; group and version come from the root gradle.properties. Each module sets its
// `description` (the POM description) in its build script, after the plugins block: Indra resets it on apply.
plugins {
    id("paletter.base-conventions")
    id("net.kyori.indra")
    id("net.kyori.indra.publishing")
}

// The bytecode level of the published modules; the build JDK stays 21 (paletter.base-conventions)
val javaTarget = providers.gradleProperty("paletter.javaTarget").get().toInt()

indra {
    github("opencollab-incubator", "Paletter") {
        ci(true)
        issues(true)
        scm(true)
    }
    mitLicense()

    javaVersions {
        target(javaTarget)
        // Build, test and document with JDK 21 everywhere, not with whatever JDK runs Gradle: the Javadoc runs with
        // Werror, so local builds and CI must use the same tool. A lower target compiles with --release on JDK 21
        minimumToolchain(21)
        strictVersions(true)
    }

    // Geyser's repositories and credential properties: geysermcUsername / geysermcPassword. Indra adds the repository
    // only when both are set (and only the one matching the version); requirePublishCredentials below turns a missing
    // credential into a failure instead of a publish that uploads nothing
    publishSnapshotsTo("geysermc", "https://repo.opencollab.dev/maven-snapshots")
    publishReleasesTo("geysermc", "https://repo.opencollab.dev/maven-releases")

    configurePublications {
        pom {
            inceptionYear = "2026"
            organization {
                name = "Open Collaboration"
                url = "https://github.com/opencollab"
            }
            developers {
                developer {
                    id = "opencollab"
                    name = "Open Collaboration"
                    url = "https://github.com/opencollab"
                }
            }
        }
    }
}

// Signing is optional: only with the paletterSigningKey (ASCII-armored key or a key file) and paletterSigningPassword
// properties. Without them nothing is signed (Indra would sign releases with the gpg command); repo.opencollab.dev takes
// unsigned artifacts. A blank value (an unset CI secret) counts as unset
val signingKey = providers.gradleProperty("paletterSigningKey").filter { it.isNotBlank() }
val signingPassword = providers.gradleProperty("paletterSigningPassword").filter { it.isNotBlank() }
val signingConfigured = signingKey.isPresent && signingPassword.isPresent
if (signingConfigured) {
    indra.signWithKeyFromPrefixedProperties("paletter")
}
tasks.withType<Sign>().configureEach {
    val configured = signingConfigured // a local: the configuration cache cannot serialize references to the script
    onlyIf("paletterSigningKey and paletterSigningPassword are set") { configured }
}

// A publish to the remote repository fails without credentials, also when the repository does not exist
val credentialsSet = providers.gradleProperty("geysermcUsername").filter { it.isNotBlank() }
    .zip(providers.gradleProperty("geysermcPassword").filter { it.isNotBlank() }) { _, _ -> true }
    .orElse(false)
val requirePublishCredentials = tasks.register("requirePublishCredentials") {
    group = "publishing"
    description = "Fails unless the geysermcUsername and geysermcPassword properties are set"
    val present = credentialsSet
    doLast {
        if (!present.get()) {
            throw GradleException("Publishing to repo.opencollab.dev needs the Gradle properties geysermcUsername and " +
                "geysermcPassword (in CI: ORG_GRADLE_PROJECT_geysermcUsername / ORG_GRADLE_PROJECT_geysermcPassword). " +
                "For a local copy use publishToMavenLocal. See docs/releasing.md.")
        }
    }
}
tasks.named("publish") {
    dependsOn(requirePublishCredentials)
}
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(requirePublishCredentials)
}

// The LICENSE in every jar: classes, sources and Javadoc
tasks.withType<Jar>().configureEach {
    from(layout.settingsDirectory.file("LICENSE")) {
        into("META-INF")
    }
}

// Indra runs javac's doclint (-Xdoclint -Xdoclint:-missing) on every source set. Only the main sources are documented
// API; tests and benchmarks keep javac's defaults. Registered after evaluation, so it runs after Indra's configuration
// of each compile task, also for source sets that later plugins (jmh) add
afterEvaluate {
    tasks.withType<JavaCompile>().matching { it.name != "compileJava" }.configureEach {
        options.compilerArgumentProviders.removeIf { it.javaClass.simpleName == "IndraCompileArgumentProvider" }
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        // Indra passes -Xdoclint:-missing. Replacing its option (same name) drops it, and Xdoclint:all turns every group
        // on: an undocumented public member, or any other doclint warning, fails the build
        addBooleanOption("Xdoclint:-missing", false)
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
        // The default cap of 100 hides the real count
        addStringOption("Xmaxwarns", "10000")
    }
}

// The runtime dependency graph of the module, transitive dependencies included: what a consumer resolves.
// Always fails on a floating -SNAPSHOT (a published Paletter must name one build of each dependency); for a release
// version it also fails on a pinned (timestamped) snapshot, so no release depends on a snapshot at all
val runtimeModules = configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }.map(::resolvedModules)
val isRelease = !version.toString().endsWith("-SNAPSHOT")
val checkReleaseDependencies = tasks.register("checkReleaseDependencies") {
    group = "verification"
    description = "Fails if the runtime dependencies resolve a floating -SNAPSHOT, or, for a release version, any snapshot"
    val modules = runtimeModules
    val release = isRelease
    val projectPath = path
    doLast {
        val floating = modules.get().filter(::isFloatingSnapshot)
        if (floating.isNotEmpty()) {
            throw GradleException("$projectPath resolves floating -SNAPSHOT dependencies; pin them to a timestamped build:\n" +
                floating.joinToString("\n") { "  - $it" })
        }
        val snapshots = modules.get().filter(::isSnapshot)
        if (release && snapshots.isNotEmpty()) {
            throw GradleException("A release must not depend on snapshots; $projectPath resolves:\n" +
                snapshots.joinToString("\n") { "  - $it" })
        }
    }
}
tasks.named("check") {
    dependsOn(checkReleaseDependencies)
}
tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(checkReleaseDependencies)
}
