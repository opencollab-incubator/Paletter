plugins {
    id("java")
    application
}

application {
    mainClass.set("org.geysermc.paletter.generator.Main")
}

dependencies {
    implementation(project(":palette"))
    implementation("org.cloudburstmc:nbt:3.0.5.Final")
}