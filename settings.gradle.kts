pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack is limited to the groups that are only published there: Fossify's mmslib and
        // Reprint, the one legacy library the vendored commons still pulls in. No other
        // coordinate can be resolved from it, and mavenLocal() is not used at all.
        // F-Droid (QD-31): google() and mavenCentral() are allowed repositories. JitPack serves binaries it built
        // itself, so F-Droid accepts it only when its recipe builds these libraries from source (srclibs) instead of
        // downloading them; the F-Droid metadata has to do that for mmslib and Reprint.
        exclusiveContent {
            forRepository { maven { setUrl("https://www.jitpack.io") } }
            filter {
                includeGroup("org.fossify")
                includeGroupAndSubgroups("com.github.tibbi")
            }
        }
    }
}
include(":app")

// Fossify Commons 7.1.3 with the anti-fake-app warnings removed (see third_party/commons/PATCHES.md).
includeBuild("third_party/commons") {
    dependencySubstitution {
        substitute(module("org.fossify:commons")).using(project(":commons"))
    }
}
