// The API boundary of a library module, applied with `apply(from = rootProject.file("gradle/api-boundary.gradle.kts"))`
// by :paletter and :paletter-recipes; see docs/api.md. The module needs `java-library` and:
//   src/module/java/module-info.java   its exports are the API packages
//   api/<project name>.api             the API dump, committed
//   extra["apiModulePathPrefixes"]     (optional) the dependency jars the descriptor requires, by file name prefix
//   the `apiModuleJars` configuration  (optional) project dependencies that are modules of their own
// It sets extra["apiPackages"], the exported packages.
//
// The binary compatibility check (japicmp) stays in each module: an applied script cannot see the plugin's task type.
// `check` runs :compileModuleInfo and :apiCheck.
//
// module-info.java is compiled against the main classes to validate it, but not packaged (Cloudburst's split fastutil
// packages keep any module graph from resolving), so the jar carries an Automatic-Module-Name instead.
val moduleInfo = file("src/module/java/module-info.java")
val moduleInfoText = moduleInfo.readText()
val moduleName = Regex("""^\s*module\s+([\w.]+)\s*\{""", RegexOption.MULTILINE).find(moduleInfoText)?.groupValues?.get(1)
    ?: error("$moduleInfo declares no module")
val exportedPackages: List<String> = Regex("""^\s*exports\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
    .findAll(moduleInfoText).map { it.groupValues[1] }.toList()
// For the module's own checks (apiCompat)
extra["apiPackages"] = exportedPackages
@Suppress("UNCHECKED_CAST")
val modulePathPrefixes = (extra.properties["apiModulePathPrefixes"] as List<String>?) ?: emptyList()

val sourceSets = the<SourceSetContainer>()
val mainClasses = sourceSets.getByName("main").output.classesDirs

tasks.named<Jar>("jar") {
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
    destinationDirectory.set(layout.buildDirectory.dir("module-info"))
    modularity.inferModulePath.set(false)
    dependsOn(tasks.named("compileJava"))
    // Only the modules the descriptor requires; adding fastutil's split packages would make resolution fail
    val prefixes = modulePathPrefixes // a local: the configuration cache cannot serialize references to the script
    val modulePath = configurations.getByName("compileClasspath").filter { file ->
        prefixes.any { file.name.startsWith(it) }
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

// The API dump: api/<project>.api lists every public and protected type, field, constant and method of the exported packages
// (without @Internal and internal.*), generated from the compiled classes by the tools module (apiDump source set). It is
// committed, so a change of the API shows in review: `./gradlew :<project>:apiDump` regenerates it, `apiCheck` (part of
// `check`) fails with the difference when it is stale.
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
    mainClass.set("org.geysermc.paletter.tools.apidump.Main")
    dependsOn(tasks.named("classes"))
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

tasks.named("check") {
    dependsOn(compileModuleInfo, apiCheck)
}
