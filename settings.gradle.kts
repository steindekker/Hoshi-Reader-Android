pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Hoshi Reader"
include(":app")

// hoshi-card, the card-enrichment library shared with chimahon. A sibling checkout at
// ../hoshi-card (the hoshi-suite layout) is used when present; otherwise the pinned submodule.
include(":hoshi-card")
project(":hoshi-card").projectDir = file("../hoshi-card").takeIf { it.resolve("build.gradle.kts").isFile }
    ?: file("third_party/hoshi-card")
