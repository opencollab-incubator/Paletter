plugins {
    application
}

// Headless Bedrock client that records what a (local, offline mode) Bedrock Dedicated Server sends. Not shipped.
dependencies {
    implementation(project(":paletter"))
    implementation(libs.protocol.connection)
    implementation(libs.gson)
}

application {
    mainClass.set("org.geysermc.paletter.capture.Main")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}


dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
