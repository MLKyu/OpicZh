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
        // sherpa-onnx (온디바이스 STT) — JitPack이 공식 릴리스 AAR을 그대로 서빙한다
        maven(url = "https://jitpack.io") {
            content { includeGroup("com.github.k2-fsa") }
        }
    }
}

rootProject.name = "OpicZh"
include(":app")
include(":core:common")
include(":core:model")
include(":core:data")
include(":core:ai")
include(":core:speech")
include(":core:designsystem")
include(":feature:home")
include(":feature:exam")
include(":feature:study")
include(":feature:settings")
