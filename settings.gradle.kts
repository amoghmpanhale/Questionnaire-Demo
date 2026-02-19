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
    }
}

val opencvsdk = File(rootProject.projectDir, "local.properties")
    .inputStream().use { java.util.Properties().apply { load(it) } }
    .getProperty("opencv.sdk.path")

include(":sdk")
project(":sdk").projectDir = File("$opencvsdk")

rootProject.name = "Questionnaire-Demo"
include(":app")

