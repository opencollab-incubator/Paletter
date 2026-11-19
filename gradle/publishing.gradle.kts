// Publishing of a library module, applied with `apply(from = rootProject.file("gradle/publishing.gradle.kts"))` by
// :paletter and :paletter-recipes (a new module is published by adding that one line; it needs `maven-publish` and
// withSourcesJar/withJavadocJar). The artifact id is the project name; group, version and the POM metadata come from the
// root gradle.properties. See docs/releasing.md.
//
// Guards:
//   - Publishing to mavenLocal always works.
//   - Any publish to a remote repository fails unless -Ppaletter.deploy=true is given.
//   - A release (non-SNAPSHOT version) goes to the releases repository only with a clean working tree; a SNAPSHOT version
//     goes to the snapshots repository only. The repository follows the version, it is never chosen by hand.
//   - A release is signed (keys from properties or environment only) and has no SNAPSHOT runtime dependency
//     (checkReleaseDependencies).

fun prop(name: String): String = providers.gradleProperty(name).orNull ?: error("gradle.properties has no $name")

val moduleVersion = project.version.toString()
val isSnapshot = moduleVersion.endsWith("-SNAPSHOT")
val deployEnabled = providers.gradleProperty("paletter.deploy").orNull == "true"
val pomUrl = prop("paletter.pom.url")
val pomScm = prop("paletter.pom.scm")
val pomOrganization = prop("paletter.pom.organization")
val pomOrganizationUrl = prop("paletter.pom.organizationUrl")
val repoUrl = prop(if (isSnapshot) "paletter.repo.snapshots" else "paletter.repo.releases")
val projectName = project.name
val projectDescription = project.description

extensions.configure<PublishingExtension> {
    publications {
        create<MavenPublication>("maven") {
            artifactId = projectName
            from(components["java"])
            pom {
                name.set(projectName)
                description.set(projectDescription ?: "Bedrock Edition palette library: blocks, items and components per data version")
                url.set(pomUrl)
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("$pomUrl/blob/main/LICENSE")
                        distribution.set("repo")
                    }
                }
                organization {
                    name.set(pomOrganization)
                    url.set(pomOrganizationUrl)
                }
                developers {
                    developer {
                        id.set("opencollab")
                        name.set(pomOrganization)
                        url.set(pomOrganizationUrl)
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/$pomScm.git")
                    developerConnection.set("scm:git:ssh://git@github.com/$pomScm.git")
                    url.set(pomUrl)
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("$pomUrl/issues")
                }
            }
        }
    }
    repositories {
        maven {
            name = "remote"
            url = uri(repoUrl)
            credentials {
                username = providers.gradleProperty("paletterRepoUser").orNull
                password = providers.gradleProperty("paletterRepoPassword").orNull
            }
        }
    }
}

// Signing: keys come only from Gradle properties or environment variables, never from the repository. The properties are
// signingKey (ASCII-armored private key) and signingPassword; the environment variables SIGNING_KEY and SIGNING_PASSWORD
// work too (or ORG_GRADLE_PROJECT_signingKey, which Gradle maps to the property). Signing is optional: without a key
// the sign tasks are skipped, so publishToMavenLocal and snapshots work. A release publish to a remote repository
// requires the key (checked in checkPublishAllowed).
apply(plugin = "signing")

val signingKey = providers.gradleProperty("signingKey").orElse(providers.environmentVariable("SIGNING_KEY"))
val signingPassword = providers.gradleProperty("signingPassword").orElse(providers.environmentVariable("SIGNING_PASSWORD"))

extensions.configure<SigningExtension> {
    isRequired = false
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
    }
    sign(extensions.getByType<PublishingExtension>().publications["maven"])
}

// Fails if a published runtime dependency (the POM compile/runtime scope: api, implementation and runtimeOnly, which is
// the runtimeElements configuration) is a SNAPSHOT. Project dependencies count with this project's version.
// A pinned snapshot has a timestamped version (3.0.0.Beta15-20261116.161745-6) instead of the -SNAPSHOT suffix. It names one
// uploaded build, so it is reproducible and allowed; only a floating -SNAPSHOT fails.
val snapshotDependencies = providers.provider {
    configurations.getByName("runtimeElements").allDependencies
        .filter { val v = it.version; v != null && v.endsWith("-SNAPSHOT") }
        .map { "${it.group}:${it.name}:${it.version}" }
        .distinct()
}
val checkReleaseDependencies = tasks.register("checkReleaseDependencies") {
    group = "publishing"
    description = "Fails if a published runtime dependency of this module is a floating -SNAPSHOT version"
    val snapshots = snapshotDependencies
    val projectPath = project.path
    doLast {
        if (snapshots.get().isNotEmpty()) {
            throw GradleException("A release must not depend on SNAPSHOT versions; $projectPath publishes these runtime " +
                "dependencies (POM compile/runtime scope):\n" + snapshots.get().joinToString("\n") { "  - $it" })
        }
    }
}

// Runs before the upload; fails the build otherwise
val checkPublishAllowed = tasks.register("checkPublishAllowed") {
    group = "publishing"
    description = "Fails a publish to a remote repository unless -Ppaletter.deploy=true, and a release unless the working tree is " +
        "clean, signed and free of SNAPSHOT dependencies"
    if (!isSnapshot) {
        dependsOn(checkReleaseDependencies)
    }
    val hasSigningKey = signingKey.isPresent
    val gitStatus = providers.exec {
        commandLine("git", "status", "--porcelain", "--untracked-files=no")
        workingDir = rootProject.projectDir
        isIgnoreExitValue = true
    }.standardOutput.asText
    doLast {
        if (!deployEnabled) {
            throw GradleException("Publishing to a remote repository is blocked. Only publishToMavenLocal is allowed; " +
                "a deploy needs -Ppaletter.deploy=true (docs/releasing.md).")
        }
        if (!isSnapshot && gitStatus.get().isNotBlank()) {
            throw GradleException("A release ($moduleVersion) needs a clean working tree; uncommitted changes:\n${gitStatus.get()}")
        }
        if (!isSnapshot && !hasSigningKey) {
            throw GradleException("A release ($moduleVersion) must be signed: set the Gradle properties signingKey and " +
                "signingPassword (or the environment variables SIGNING_KEY and SIGNING_PASSWORD), see docs/releasing.md.")
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(checkPublishAllowed)
}
