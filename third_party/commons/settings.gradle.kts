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
        // uMessage: JitPack only for Reprint (com.github.tibbi), the one dependency published nowhere else. F-Droid
        // builds every dependency from source; nothing else may come from a repository that hosts prebuilt binaries.
        exclusiveContent {
            forRepository { maven { setUrl("https://jitpack.io") } }
            filter { includeGroupAndSubgroups("com.github.tibbi") }
        }
    }
}
rootProject.name = "Fossify-Commons"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":commons")
