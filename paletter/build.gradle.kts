plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    // Cloudburst protocol types are the library's public currency: definitions, ItemData, creative and block property data.
    api(libs.protocol.codec)
    api(libs.fastutil.int.`object`)
    api(libs.fastutil.`object`.int)
    api(libs.fastutil.`object`.`object`)
    compileOnlyApi(libs.checker.qual)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1G"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "paletter"
            from(components["java"])
        }
    }
}
