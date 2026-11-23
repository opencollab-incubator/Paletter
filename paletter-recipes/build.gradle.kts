// Published, as :paletter: paletter.publish-conventions and paletter.api-conventions. See docs/releasing.md.
plugins {
    id("paletter.publish-conventions")
    id("paletter.api-conventions")
}

description = "Vanilla Bedrock Edition recipes per data version, on top of Paletter's palettes"

paletterApi {
    // The descriptor requires :paletter, which is on the module path as its jar (Automatic-Module-Name, apiModuleJars)
    modulePathPrefixes = listOf("checker-qual")
}

// Vanilla recipes per data version, on top of Paletter's palettes. :paletter never depends on this module.
dependencies {
    api(project(":paletter"))
    compileOnlyApi(libs.checker.qual)
    "apiModuleJars"(project(":paletter"))
}

// RecipeStations is generated (recipes-import), beside the hand-written sources
sourceSets.main {
    java.srcDir("src/generated/java")
}
