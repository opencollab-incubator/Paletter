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

// Runs before the upload; fails the build otherwise
val checkPublishAllowed = tasks.register("checkPublishAllowed") {
    group = "publishing"
    description = "Fails a publish to a remote repository unless -Ppaletter.deploy=true, and a release unless the working tree is clean"
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
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(checkPublishAllowed)
}
