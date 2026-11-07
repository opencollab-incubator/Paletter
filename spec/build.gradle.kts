plugins {
    application
}

// The component model: the add-on JSON side from Microsoft's creator docs, a capture add-on generated from it, and the
// JSON -> wire mapping inferred from BDS captures of that add-on. Not shipped. See spec/README.md.
dependencies {
    implementation(project(":paletter"))
    implementation(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.geysermc.paletter.spec.Main")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

val root = listOf("--root", rootProject.projectDir.absolutePath)

// Needs the docs: --docs DIR (a checkout of MicrosoftDocs/minecraft-creator) or a download of the pinned commit
tasks.register<JavaExec>("generateModel") {
    group = "spec"
    description = "Parses the creator docs into codegen/model/components.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args = listOf("docs") + root + (findProperty("docs") as String?).let { if (it == null) listOf() else listOf("--docs", it) }
}

tasks.register<JavaExec>("generateAddon") {
    group = "spec"
    description = "Generates the spec capture add-on from codegen/model into build/spec-addon"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args = listOf("addon") + root + listOf("--out", layout.buildDirectory.dir("spec-addon").get().asFile.absolutePath)
}

tasks.register<JavaExec>("inferMappings") {
    group = "spec"
    description = "Infers codegen/model/mappings.json and coverage.md from the committed captures"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args = listOf("infer") + root
}

val checkMappings = tasks.register<JavaExec>("checkMappings") {
    group = "verification"
    description = "Fails if inferring the mappings from the committed model and captures yields a diff"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args = listOf("check") + root
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(checkMappings)
}
