pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Required by the LiveKit SDK (audioswitch dependency)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PTTConnect"
include(":app")
