plugins {
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

// A smoke test: `check` (and so the build and CI) runs every example, and fails when one throws
val runExamples = listOf("BedrockServerExample", "TranslatorExample", "AnticheatExample").map { example ->
    tasks.register<JavaExec>("run$example") {
        group = "verification"
        description = "Runs $example; fails if it throws"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("dev.opencollab.paletter.examples.$example")
    }
}

tasks.check {
    dependsOn(runExamples)
}
