plugins {
    application
}

dependencies {
    implementation(project(":paletter"))
    implementation(project(":capture"))
    implementation(project(":codegen"))
    implementation(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.geysermc.paletter.generator.Main")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    // update starts Bedrock Dedicated Servers; let it read stdin-less and stop them on Ctrl+C
    standardInput = System.`in`
}
