plugins {
    id("paletter.base-conventions")
    application
}

// All tooling in one module, never shipped: the BDS update pipeline, the Bedrock capture client, the component model and
// the code generators. One command line (dev.opencollab.paletter.tools.Cli): ./gradlew :tools:run --args="<command> ..."
// See docs/data-pipeline.md.

// The component API generator has its own source set: it reads only data/model (and the Transforms source), so it
// runs without compiling paletter, whose generated sources it writes.
val componentApi: SourceSet = sourceSets.create("componentApi")

// The API dump (paletter/api/paletter.api) is generated from paletter's compiled classes; its own source set needs
// neither paletter nor the rest of the tools, so :paletter:apiDump / apiCheck can run it. See docs/api.md.
val apiDump: SourceSet = sourceSets.create("apiDump")

// What :paletter:apiDump runs: the classes of the apiDump source set and their dependencies
configurations.consumable("apiDumpElements") {
    extendsFrom(configurations[apiDump.implementationConfigurationName], configurations[apiDump.runtimeOnlyConfigurationName])
    outgoing.artifact(tasks.named<JavaCompile>(apiDump.compileJavaTaskName).flatMap { it.destinationDirectory })
}

dependencies {
    "apiDumpImplementation"(libs.asm.util)
    testImplementation(apiDump.output)
    testImplementation(libs.asm.util)

    "componentApiImplementation"(libs.gson)
    "componentApiImplementation"(libs.javapoet)

    implementation(componentApi.output)
    implementation(project(":paletter"))
    implementation(libs.gson)
    implementation(libs.javapoet)
    implementation(libs.picocli)
    implementation(libs.iced.x86)
    // The capture client: BDS 1.26.70+ only speaks NetherNet (WebRTC, HTTP signaling on server-port); RakNet from the same build
    implementation(libs.protocol.connection)
    implementation(libs.raknet)
    implementation(libs.nethernet)
    implementation(libs.libdatachannel)

    testImplementation(componentApi.output)
    testImplementation(project(":paletter-recipes"))
    testImplementation(libs.javapoet)
    // ConstantsGeneratorTest compiles generated sources, which use the nullness annotations
    testImplementation(libs.checker.qual)
}

tasks.test {
    // Tests of the "binary" tag read a BDS binary from the cache: ./gradlew :tools:whiteboxRegression
    // Tests of the "slow" tag recompute what a fast test checks a committed result of: ./gradlew :tools:slowTests
    useJUnitPlatform { excludeTags("binary", "slow") }
    // ./gradlew :tools:test -PupdateGolden=true rewrites the golden files of the synthetic variants and the API dump (Golden; review the diff)
    systemProperty("paletter.updateGolden", (findProperty("updateGolden") as String?) ?: "false")
    // -PupdateShapeDisagreements=true rewrites data/model/vanilla-shapes-known-<protocol>.txt (VanillaShapeOracleTest)
    systemProperty("paletter.updateShapeDisagreements", (findProperty("updateShapeDisagreements") as String?) ?: "false")
    // Beyond the repository inputs of every test (paletter.base-conventions): LocaleRootTest reads the sources of every
    // module, DeprecatedDocsTest reads docs/api.md
    val root = layout.settingsDirectory
    for (dir in listOf("paletter-recipes/src", "examples/src")) {
        inputs.dir(root.dir(dir)).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("repository $dir")
    }
    inputs.file(root.file("docs/api.md")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("repository docs/api.md")
}

application {
    mainClass.set("dev.opencollab.paletter.tools.Cli")
}

// installDist is what the workflows and scripts use (tools/build/install); the archives are about 94 MB nobody reads
tasks.distZip { enabled = false }
tasks.distTar { enabled = false }

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // update starts Bedrock Dedicated Servers; let it read stdin-less and stop them on Ctrl+C
    standardInput = System.`in`
}

// `--root <repository>`, appended to the arguments; not an input (RepositoryRootOption)
val repositoryRoot = layout.settingsDirectory.asFile

// What the check commands read under the repository root: the committed data, captures and model (data), the bundled
// resources and the generated sources they compare with (paletter/src, paletter-recipes/src) and the version
// (gradle.properties, read by the component API generator). Read from the code of the commands; a superset for all seven.
val checkInputs = listOf("data", "paletter/src", "paletter-recipes/src")

// A check task is up to date when neither these files nor the tools' classpath changed. It writes a marker file as its
// output, since the commands themselves write nothing.
fun JavaExec.checkTask() {
    val root = layout.settingsDirectory
    for (dir in checkInputs) {
        inputs.dir(root.dir(dir)).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("repository $dir")
    }
    inputs.file(root.file("gradle.properties")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("gradle.properties")
    val marker = layout.buildDirectory.file("checks/$name.ok")
    outputs.file(marker).withPropertyName("marker")
    doLast {
        marker.get().asFile.writeText("ok\n")
    }
}

fun cliTask(name: String, group: String, description: String, vararg args: String, check: Boolean = false) =
    tasks.register<JavaExec>(name) {
        this.group = group
        this.description = description
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("dev.opencollab.paletter.tools.Cli")
        this.args = args.toList()
        argumentProviders.add(RepositoryRootOption(repositoryRoot))
        workingDir = repositoryRoot
        if (check) {
            checkTask()
        }
    }

// Vanilla constants (dev.opencollab.paletter.vanilla.BedrockBlocks, BedrockStates, BedrockItems, the state enums):
// from the newest bundled release, renames.txt and constants-history.json (committed, updated).
cliTask("generateConstants", "codegen", "Regenerates the vanilla constants classes from the bundled data", "constants", "generate")
val checkConstants = cliTask("checkConstants", "verification",
    "Fails if regenerating the vanilla constants from the committed data, names and history yields a diff", "constants", "check", check = true)

// The custom item/block component API (dev.opencollab.paletter.item.component, block.component and the internal encoders)
// from the component model. Runs on the componentApi source set only, so it works while paletter does not compile.
val generateComponentApi = tasks.register<JavaExec>("generateComponentApi") {
    group = "codegen"
    description = "Regenerates the component API and its encoders from the component model"
    classpath = componentApi.runtimeClasspath
    mainClass.set("dev.opencollab.paletter.tools.codegen.component.Main")
    args = listOf("generate")
    argumentProviders.add(RepositoryRootOption(repositoryRoot))
    // reads what inferMappings writes: after it when both run
    mustRunAfter("inferMappings")
    inputs.dir(rootProject.file("data/model"))
    outputs.dir(rootProject.file("paletter/src/generated"))
    outputs.upToDateWhen { false }
}

val checkComponentApi = tasks.register<JavaExec>("checkComponentApi") {
    group = "verification"
    description = "Fails if regenerating the component API from the component model yields a diff"
    classpath = componentApi.runtimeClasspath
    mainClass.set("dev.opencollab.paletter.tools.codegen.component.Main")
    args = listOf("check")
    argumentProviders.add(RepositoryRootOption(repositoryRoot))
    checkTask()
    mustRunAfter("inferMappings", generateComponentApi)
}

// Generates into build/component-api (to look at, or while the committed API is being replaced)
tasks.register<JavaExec>("generateComponentApiPreview") {
    group = "codegen"
    description = "Generates the component API into tools/build/component-api, leaving the committed sources alone"
    classpath = componentApi.runtimeClasspath
    mainClass.set("dev.opencollab.paletter.tools.codegen.component.Main")
    args = listOf("generate", "--sources", layout.buildDirectory.dir("component-api").get().asFile.absolutePath)
    argumentProviders.add(RepositoryRootOption(repositoryRoot))
}

// The component model: the add-on JSON side from Microsoft's creator docs, the spec capture add-on generated from it, and the
// JSON -> wire mapping inferred from BDS captures of that add-on.
// Needs the docs: -Pdocs=DIR (a checkout of MicrosoftDocs/minecraft-creator) or a download of the pinned commit
tasks.register<JavaExec>("generateModel") {
    group = "spec"
    description = "Parses the creator docs into data/model/components.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.opencollab.paletter.tools.Cli")
    workingDir = repositoryRoot
    args = listOf("docs") + (findProperty("docs") as String?).let { if (it == null) listOf() else listOf("--docs", it) }
    argumentProviders.add(RepositoryRootOption(repositoryRoot))
}

tasks.register<JavaExec>("generateAddon") {
    group = "spec"
    description = "Generates the spec capture add-on from data/model into build/spec-addon"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.opencollab.paletter.tools.Cli")
    workingDir = repositoryRoot
    args = listOf("addon", "--out", layout.buildDirectory.dir("spec-addon").get().asFile.absolutePath)
    argumentProviders.add(RepositoryRootOption(repositoryRoot))
}

val inferMappings = cliTask("inferMappings", "spec", "Infers data/model/mappings.json and coverage.md from the committed captures", "infer")
inferMappings.configure {
    outputs.files(rootProject.file("data/model/mappings.json"), rootProject.file("data/model/mappings-evidence.json"),
        rootProject.file("data/model/coverage.md"))
    // It reads the captures and schemas under data/bds, which are no declared inputs: never up to date
    outputs.upToDateWhen { false }
}
val checkMappings = cliTask("checkMappings", "verification",
    "Fails if inferring the mappings from the committed model and captures yields a diff, or leaves a captured bit or wire path unexplained",
    "check-mappings", "--strict", check = true)
checkMappings.configure {
    mustRunAfter(inferMappings, generateComponentApi)
}

// What older bundled versions lack of the newest one (fallback coverage); fails on problems of the bundled data
val checkData = cliTask("checkData", "verification",
    "Fails on problems of the bundled data: what older bundled versions lack of the newest one", "check-data", check = true)

// What the code of a build can send that its captures do not show (docs/data-pipeline.md, "White-box discovery"). Reads
// only committed facts and captures, never a BDS binary, and fails on a stale report, not on a gap (that is --strict).
val checkWhitebox = cliTask("checkWhitebox", "verification",
    "Fails if a committed whitebox-coverage.md is not what the committed whitebox.json and captures give", "whitebox-coverage", "--check", check = true)

// What data/bds/builds.json says per bundled data version: the library's builds.txt and data/bds/builds.md
val checkBuilds = cliTask("checkBuilds", "verification",
    "Fails if builds.txt or data/bds/builds.md is stale against data/bds/builds.json", "builds", "table", "--check", check = true)

// The bundled recipes of paletter-recipes (recipes/<version>.nbt) from the recipe captures in data/bds
val checkRecipes = cliTask("checkRecipes", "verification",
    "Fails if the bundled recipes are not what the recipe captures in data/bds give", "recipes-import", "--check", check = true)

tasks.named("check") {
    dependsOn(checkConstants, checkComponentApi, checkMappings, checkData, checkWhitebox, checkBuilds, checkRecipes)
}

// The white-box analysis of a cached BDS binary against the committed whitebox.json. Explicit only (not in check or
// build): CI has no binary. A build whose zip is not in the cache is skipped, not downloaded.
tasks.register<Test>("whiteboxRegression") {
    group = "verification"
    description = "Runs the white-box analysis on a cached BDS binary and compares it with the committed whitebox.json"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("binary") }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("slowTests") {
    group = "verification"
    description = "Runs the slow tests, which recompute committed results (the hash collision pair of the spec add-on)"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("slow") }
    outputs.upToDateWhen { false }
}
