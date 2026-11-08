plugins {
    application
}

// The component API generator has its own source set: it reads only codegen/model (and the Transforms source), so it
// runs without compiling paletter, whose generated sources it writes.
val componentApi: SourceSet = sourceSets.create("componentApi")

dependencies {
    "componentApiImplementation"(libs.gson)
    "componentApiImplementation"(libs.javapoet)
    implementation(project(":paletter"))
    implementation(libs.gson)
    testImplementation(componentApi.output)
    testImplementation(libs.javapoet)
    // The conformance suite rebuilds the capture add-on's definitions with the spec module
    testImplementation(project(":spec"))

    // ConstantsGeneratorTest compiles generated sources, which use the nullness annotations
    testImplementation(libs.checker.qual)
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1G"
    systemProperty("paletter.root", rootProject.projectDir.absolutePath)
    // ./gradlew :codegen:test -PupdateGolden=true rewrites src/test/resources/constants-golden (review the diff)
    systemProperty("paletter.updateGolden", (findProperty("updateGolden") as String?) ?: "false")
}

application {
    mainClass.set("org.geysermc.paletter.codegen.Codegen")
}

// Wire shape inference for the update pipeline's wire change detection. Inputs: wire NBT (bundled vanilla data +
// codegen/captures), Geyser samples and the curated overrides. Output (committed): codegen/spec/inferred.json.
// Extra capture directories (e.g. -Pcaptures=../Paletter-capture/capture/captures) are read in addition to
// codegen/captures. Copy captures into codegen/captures to make them part of the committed spec.
val codegenArgs = listOf("--root", rootProject.projectDir.absolutePath) +
    (findProperty("captures") as String?).orEmpty().split(',').filter { it.isNotBlank() }
        .flatMap { listOf("--captures", rootProject.file(it).absolutePath) }

tasks.register<JavaExec>("inferWireShapes") {
    group = "codegen"
    description = "Infers the wire shapes of components from wire NBT into codegen/spec/inferred.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args = listOf("generate") + codegenArgs
}

val checkWireShapes = tasks.register<JavaExec>("checkWireShapes") {
    group = "verification"
    description = "Fails if inferring the wire shapes from the committed inputs yields a diff"
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
    dependsOn(checkWireShapes, checkConstants)
}

// The custom item/block component API (org.geysermc.paletter.item.component, block.component and the internal encoders)
// from codegen/model: components.json, mappings.json, transforms.json, bds-feedback.json, fixtures.json, api.json.
tasks.register<JavaExec>("generateComponentApi") {
    group = "codegen"
    description = "Regenerates the component API and its encoders from codegen/model"
    classpath = componentApi.runtimeClasspath
    mainClass.set("org.geysermc.paletter.codegen.component.Main")
    args = listOf("generate", "--root", rootProject.projectDir.absolutePath)
}

val checkComponentApi = tasks.register<JavaExec>("checkComponentApi") {
    group = "verification"
    description = "Fails if regenerating the component API from codegen/model yields a diff"
    classpath = componentApi.runtimeClasspath
    mainClass.set("org.geysermc.paletter.codegen.component.Main")
    args = listOf("check", "--root", rootProject.projectDir.absolutePath)
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(checkComponentApi)
}

// Generates into build/component-api (to look at, or while the committed API is being replaced)
tasks.register<JavaExec>("generateComponentApiPreview") {
    group = "codegen"
    classpath = componentApi.runtimeClasspath
    mainClass.set("org.geysermc.paletter.codegen.component.Main")
    args = listOf("generate", "--root", rootProject.projectDir.absolutePath, "--sources", layout.buildDirectory.dir("component-api").get().asFile.absolutePath)
}
