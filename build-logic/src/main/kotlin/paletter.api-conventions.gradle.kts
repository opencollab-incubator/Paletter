import me.champeau.gradle.japicmp.JapicmpTask

// The API boundary of a library module (see docs/api.md). The module needs:
//   src/module/java/module-info.java   its exports are the API packages
//   api/<project name>.api             the API dump, committed
//   api/released.version               (optional) the release apiCompat compares with
//   paletterApi.modulePathPrefixes     (optional) the dependency jars the descriptor requires, by file name prefix
//   the `apiModuleJars` configuration  (optional) project dependencies that are modules of their own
// `check` runs compileModuleInfo, apiCheck and apiCompat.
//
// module-info.java is compiled against the main classes to validate it, but not packaged (Cloudburst's split fastutil
// packages keep any module graph from resolving), so the jar carries an Automatic-Module-Name instead.
plugins {
    `java-library`
    id("me.champeau.gradle.japicmp")
}

val paletterApi = extensions.create<PaletterApiExtension>("paletterApi")
paletterApi.modulePathPrefixes.convention(emptyList())

val moduleInfo = file("src/module/java/module-info.java")
val moduleInfoText = moduleInfo.readText()
val moduleName = Regex("""^\s*module\s+([\w.]+)\s*\{""", RegexOption.MULTILINE).find(moduleInfoText)?.groupValues?.get(1)
    ?: error("$moduleInfo declares no module")
val exportedPackages: List<String> = Regex("""^\s*exports\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
    .findAll(moduleInfoText).map { it.groupValues[1] }.toList()

val mainClasses = sourceSets.main.get().output.classesDirs

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to moduleName)
    }
}

// Dependencies that are modules of their own, as jars (their Automatic-Module-Name)
val apiModuleJars = configurations.create("apiModuleJars") {
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}

val compileModuleInfo = tasks.register<JavaCompile>("compileModuleInfo") {
    group = "verification"
    description = "Compiles module-info.java against the main classes, validating the declared API packages"
    source(moduleInfo)
    classpath = files()
    destinationDirectory = layout.buildDirectory.dir("module-info")
    modularity.inferModulePath = false
    dependsOn(tasks.compileJava)
    // Only the modules the descriptor requires; adding fastutil's split packages would make resolution fail
    val prefixes = paletterApi.modulePathPrefixes
    val modulePath = configurations.compileClasspath.get().filter { file ->
        prefixes.get().any { file.name.startsWith(it) }
    } + apiModuleJars
    inputs.files(modulePath)
    inputs.files(mainClasses)
    val patched = "$moduleName=" + mainClasses.asPath
    doFirst {
        options.compilerArgs.addAll(listOf(
            "-Xlint:-requires-transitive-automatic",
            "--module-path", modulePath.asPath,
            "--patch-module", patched
        ))
    }
}

// The API dump: api/<project>.api lists every public and protected type, field, constant and method of the exported
// packages (without @Internal and internal.*), generated from the compiled classes by the tools module (apiDump source
// set). It is committed, so a change of the API shows in review: `./gradlew :<project>:apiDump` regenerates it,
// `apiCheck` (part of `check`) fails with the difference when it is stale.
val apiDumpFile = file("api/${project.name}.api")
// The tool's classes and runtime classpath, from the apiDumpElements configuration of :tools (no cross-project access)
val apiDumpTool = configurations.create("apiDumpTool") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    }
}
dependencies.add(apiDumpTool.name, dependencies.project(mapOf("path" to ":tools", "configuration" to "apiDumpElements")))

fun apiDumpTask(name: String, mode: String, text: String) = tasks.register<JavaExec>(name) {
    group = "verification"
    description = text
    classpath = apiDumpTool
    mainClass = "dev.opencollab.paletter.tools.apidump.Main"
    dependsOn(tasks.classes)
    inputs.files(mainClasses)
    inputs.file(moduleInfo)
    args(mode, "--classes", mainClasses.asPath, "--module-info", moduleInfo.absolutePath, "--file", apiDumpFile.absolutePath,
        "--project", project.name)
    if (mode == "dump") {
        outputs.file(apiDumpFile)
    } else {
        inputs.files(fileTree("api") { include("${project.name}.api") })
    }
}

apiDumpTask("apiDump", "dump", "Writes the exported API as a text dump (api/${project.name}.api); commit it with the change")
val apiCheck = apiDumpTask("apiCheck", "check", "Fails with a readable diff if api/${project.name}.api is not what the compiled classes give")

// Binary compatibility against a RELEASED artifact, resolved from a Maven repository. Configured but skipped while no
// release exists: api/released.version is absent or empty. After a release, write its version there; the check then
// compares the exported packages with the published jar (japicmp; @Internal and @Experimental API excluded).
val releasedVersion = file("api/released.version").takeIf { it.exists() }?.readText()?.trim().orEmpty()
val releasedApi = configurations.create("releasedApi") {
    isCanBeConsumed = false
    isTransitive = false
}
if (releasedVersion.isNotEmpty()) {
    dependencies.add(releasedApi.name, "${project.group}:${project.name}:$releasedVersion")
}

// japicmp's own classpath must resolve with Indra's capability rules (JaxbActivationRule)
dependencies.components.withModule<JaxbActivationRule>("javax.activation:activation")

val apiCompat = tasks.register<JapicmpTask>("apiCompat") {
    group = "verification"
    description = "Fails on binary incompatible changes of the exported API against the release in api/released.version (skipped before the first release)"
    enabled = releasedVersion.isNotEmpty()
    oldClasspath.from(releasedApi, configurations.runtimeClasspath)
    oldArchives.from(releasedApi)
    newClasspath.from(tasks.jar, configurations.runtimeClasspath)
    newArchives.from(tasks.jar)
    packageIncludes = exportedPackages
    annotationExcludes = listOf("@dev.opencollab.paletter.annotation.Internal", "@dev.opencollab.paletter.annotation.Experimental")
    onlyBinaryIncompatibleModified = true
    failOnModification = true
    ignoreMissingClasses = true
    txtOutputFile = layout.buildDirectory.file("reports/japicmp/api-compat.txt")
}

tasks.check {
    dependsOn(compileModuleInfo, apiCheck, apiCompat)
}
