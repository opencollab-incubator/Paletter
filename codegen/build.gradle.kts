plugins {
    application
}

dependencies {
    implementation(project(":paletter"))
    implementation(libs.gson)

    // ConstantsGeneratorTest compiles generated sources, which use the nullness annotations
    testImplementation(libs.checker.qual)
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.geysermc.paletter.codegen.Codegen")
}

// Inputs: wire NBT (bundled vanilla data + codegen/captures), Geyser samples and the curated overrides.
// Outputs (committed): codegen/spec/inferred.json, codegen/needs-capture.txt and the generated component sources.
// Extra capture directories (e.g. -Pcaptures=../Paletter-capture/capture/captures) are read in addition to
// codegen/captures. Copy captures into codegen/captures to make them part of the committed spec.
val codegenArgs = listOf("--root", rootProject.projectDir.absolutePath) +
    (findProperty("captures") as String?).orEmpty().split(',').filter { it.isNotBlank() }
        .flatMap { listOf("--captures", rootProject.file(it).absolutePath) }

tasks.register<JavaExec>("generateComponents") {
    group = "codegen"
    description = "Infers component shapes from wire NBT and regenerates the component types"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args = listOf("generate") + codegenArgs
}

val checkComponents = tasks.register<JavaExec>("checkComponents") {
    group = "verification"
    description = "Fails if regenerating the component types from the committed inputs yields a diff"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args = listOf("check") + codegenArgs
    outputs.upToDateWhen { false }
}

// Vanilla constants (org.geysermc.paletter.vanilla.BedrockBlocks, BedrockStates, BedrockItems, the state enums):
// from the newest bundled release, codegen/names.json and codegen/constants-history.json (committed, updated).
val constantsArgs = listOf("--root", rootProject.projectDir.absolutePath)

tasks.register<JavaExec>("generateConstants") {
    group = "codegen"
    description = "Regenerates the vanilla constants classes from the bundled data"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.geysermc.paletter.codegen.constants.Main")
    args = listOf("generate") + constantsArgs
}

val checkConstants = tasks.register<JavaExec>("checkConstants") {
    group = "verification"
    description = "Fails if regenerating the vanilla constants from the committed data, names and history yields a diff"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.geysermc.paletter.codegen.constants.Main")
    args = listOf("check") + constantsArgs
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(checkComponents, checkConstants)
}
