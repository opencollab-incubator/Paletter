plugins {
    `kotlin-dsl`
}

// The plugins the conventions apply, at the versions of gradle/libs.versions.toml
dependencies {
    implementation(libs.indra.common)
    implementation(libs.japicmp.plugin)
}
