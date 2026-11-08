plugins {
    application
}

// Runnable examples of the library; compiled by the build, never published.
//   ./gradlew :examples:run                                                          BedrockServerExample
//   ./gradlew :examples:run -PmainClass=org.geysermc.paletter.examples.TranslatorExample
dependencies {
    implementation(project(":paletter"))
    compileOnly(libs.checker.qual)
}

application {
    mainClass.set((findProperty("mainClass") as String?) ?: "org.geysermc.paletter.examples.BedrockServerExample")
}
