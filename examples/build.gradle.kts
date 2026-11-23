plugins {
    id("paletter.base-conventions")
    application
}

// Runnable examples of the library; compiled by the build, never published.
//   ./gradlew :examples:run                                                          BedrockServerExample
//   ./gradlew :examples:run -PmainClass=dev.opencollab.paletter.examples.TranslatorExample
//   ./gradlew :examples:run -PmainClass=dev.opencollab.paletter.examples.AnticheatExample
dependencies {
    implementation(project(":paletter"))
    compileOnly(libs.checker.qual)
}

application {
    mainClass.set((findProperty("mainClass") as String?) ?: "dev.opencollab.paletter.examples.BedrockServerExample")
}

// Nobody installs the examples
tasks.distZip { enabled = false }
tasks.distTar { enabled = false }

// A smoke test: `check` (and so the build and CI) runs every example, and fails when one throws. The examples read
// nothing but their classpath (the library and its bundled data), so a run is up to date until that changes; the
// marker file is the output
val runExamples = listOf("BedrockServerExample", "TranslatorExample", "AnticheatExample").map { example ->
    tasks.register<JavaExec>("run$example") {
        group = "verification"
        description = "Runs $example; fails if it throws"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("dev.opencollab.paletter.examples.$example")
        val marker = layout.buildDirectory.file("checks/run$example.ok")
        outputs.file(marker).withPropertyName("marker")
        doLast {
            marker.get().asFile.writeText("ok\n")
        }
    }
}

tasks.check {
    dependsOn(runExamples)
}
