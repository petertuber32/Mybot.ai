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
        mavenLocal() // local fallback first: harmless on CI (empty), useful on constrained networks
        google()
        mavenCentral()
    }
}

rootProject.name = "Mybot.ai"
include(":app")
