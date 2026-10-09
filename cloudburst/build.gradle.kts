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

    implementation("org.cloudburstmc.protocol:common:3.0.0.Beta13-20260824.114016-17")
    implementation("org.cloudburstmc.protocol:bedrock-codec:3.0.0.Beta13-20260824.114016-17")
}
