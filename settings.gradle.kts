pluginManagement {
    // The convention plugins: paletter.base-conventions, paletter.publish-conventions, paletter.api-conventions
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
    }
}

rootProject.name = "paletter-parent"

include("paletter")
include("paletter-recipes")
include("tools")
include("examples")
