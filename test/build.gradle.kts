plugins {
    id("java")
}

repositories {
    maven("https://repo.opencollab.dev/maven-snapshots/")
    maven("https://repo.opencollab.dev/main")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":runtime"))
    implementation(project(":palette"))
    implementation(project(":cloudburst"))
    implementation("org.cloudburstmc.protocol:common:3.0.0.Beta13-20260824.114016-17")
    implementation("org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta13-20260824.114016-17")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // PaletterPalettes caches loaded palettes in static state, so test classes that load a palette
    // (DownloadTest sets a fresh cache path per run, SentinelTest/CloudburstBridgeTest use the
    // default one) can't safely share a JVM - restart it between classes to isolate that state.
    forkEvery = 1
}
