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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack supplies the Termux `terminal-emulator` library, which carries the
        // prebuilt libtermux.so (forkpty) for all four Android ABIs. Restricted to that
        // one group so a JitPack outage can never affect the rest of the dependency graph.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.termux.*") }
        }
    }
}

rootProject.name = "NEXTERM"
include(":app")
