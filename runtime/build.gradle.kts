plugins {
    id("java")
}

dependencies {
    implementation(project(":api"))
    implementation(project(":palette"))
    implementation("org.cloudburstmc:nbt:3.0.5.Final")
}
