plugins {
    application
}

// All tooling in one module, never shipped: the BDS update pipeline, the Bedrock capture client, the component model and
// the code generators. One command line (org.geysermc.paletter.tools.Cli): ./gradlew :tools:run --args="<command> ..."
// See tools/README.md.

// The component API generator has its own source set: it reads only data/model (and the Transforms source), so it
// runs without compiling paletter, whose generated sources it writes.
val componentApi: SourceSet = sourceSets.create("componentApi")

// The API dump (paletter/api/paletter.api) is generated from paletter's compiled classes; its own source set needs
// neither paletter nor the rest of the tools, so :paletter:apiDump / apiCheck can run it. See API.md.
val apiDump: SourceSet = sourceSets.create("apiDump")

dependencies {
    "apiDumpImplementation"(libs.asm.util)
    testImplementation(apiDump.output)
    testImplementation(libs.asm.util)

    "componentApiImplementation"(libs.gson)
    "componentApiImplementation"(libs.javapoet)

    implementation(componentApi.output)
    implementation(project(":paletter"))
    implementation(libs.gson)
    implementation(libs.picocli)
    // The capture client: BDS 1.26.70+ only speaks NetherNet (WebRTC, HTTP signaling on server-port); RakNet from the same build
    implementation(libs.protocol.connection)
    implementation(libs.raknet)
    implementation(libs.nethernet)
    implementation(libs.libdatachannel)

    testImplementation(componentApi.output)
    testImplementation(libs.javapoet)
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
    // ./gradlew :tools:test -PupdateGolden=true rewrites src/test/resources/constants-golden (review the diff)
    systemProperty("paletter.updateGolden", (findProperty("updateGolden") as String?) ?: "false")
}

application {
    mainClass.set("org.geysermc.paletter.tools.Cli")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // update starts Bedrock Dedicated Servers; let it read stdin-less and stop them on Ctrl+C
    standardInput = System.`in`
}

val root = listOf("--root", rootProject.projectDir.absolutePath)

fun cliTask(name: String, group: String, description: String, vararg args: String, check: Boolean = false) =
    tasks.register<JavaExec>(name) {
        this.group = group
        this.description = description
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("org.geysermc.paletter.tools.Cli")
        this.args = args.toList() + root
        workingDir = rootProject.projectDir
        if (check) {
            outputs.upToDateWhen { false }
        }
    }

// Vanilla constants (org.geysermc.paletter.vanilla.BedrockBlocks, BedrockStates, BedrockItems, the state enums):
// from the newest bundled release, names.json and constants-history.json (committed, updated).
cliTask("generateConstants", "codegen", "Regenerates the vanilla constants classes from the bundled data", "constants", "generate")
val checkConstants = cliTask("checkConstants", "verification",
    "Fails if regenerating the vanilla constants from the committed data, names and history yields a diff", "constants", "check", check = true)

// The custom item/block component API (org.geysermc.paletter.item.component, block.component and the internal encoders)
// from the component model. Runs on the componentApi source set only, so it works while paletter does not compile.
tasks.register<JavaExec>("generateComponentApi") {
    group = "codegen"
    description = "Regenerates the component API and its encoders from the component model"
    classpath = componentApi.runtimeClasspath
    mainClass.set("org.geysermc.paletter.tools.codegen.component.Main")
    args = listOf("generate") + root
}

val checkComponentApi = tasks.register<JavaExec>("checkComponentApi") {
    group = "verification"
    description = "Fails if regenerating the component API from the component model yields a diff"
    classpath = componentApi.runtimeClasspath
    mainClass.set("org.geysermc.paletter.tools.codegen.component.Main")
    args = listOf("check") + root
    outputs.upToDateWhen { false }
}

// Generates into build/component-api (to look at, or while the committed API is being replaced)
tasks.register<JavaExec>("generateComponentApiPreview") {
    group = "codegen"
    classpath = componentApi.runtimeClasspath
    mainClass.set("org.geysermc.paletter.tools.codegen.component.Main")
    args = listOf("generate") + root + listOf("--sources", layout.buildDirectory.dir("component-api").get().asFile.absolutePath)
}

// The component model: the add-on JSON side from Microsoft's creator docs, a capture add-on generated from it, and the
// JSON -> wire mapping inferred from BDS captures of that add-on.
// Needs the docs: -Pdocs=DIR (a checkout of MicrosoftDocs/minecraft-creator) or a download of the pinned commit
tasks.register<JavaExec>("generateModel") {
    group = "spec"
    description = "Parses the creator docs into data/model/components.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.geysermc.paletter.tools.Cli")
    workingDir = rootProject.projectDir
    args = listOf("docs") + root + (findProperty("docs") as String?).let { if (it == null) listOf() else listOf("--docs", it) }
}

tasks.register<JavaExec>("generateAddon") {
    group = "spec"
    description = "Generates the spec capture add-on from data/model into build/spec-addon"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.geysermc.paletter.tools.Cli")
    workingDir = rootProject.projectDir
    args = listOf("addon") + root + listOf("--out", layout.buildDirectory.dir("spec-addon").get().asFile.absolutePath)
}

cliTask("inferMappings", "spec", "Infers data/model/mappings.json and coverage.md from the committed captures", "infer")
val checkMappings = cliTask("checkMappings", "verification",
    "Fails if inferring the mappings from the committed model and captures yields a diff", "check-mappings", check = true)

tasks.named("check") {
    dependsOn(checkConstants, checkComponentApi, checkMappings)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xmaxerrs")
    options.compilerArgs.add("2000")
}
