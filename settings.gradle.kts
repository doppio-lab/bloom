rootProject.name = "bloom"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":core", ":data", ":agents", ":app")
