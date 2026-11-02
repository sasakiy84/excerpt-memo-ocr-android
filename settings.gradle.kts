pluginManagement { repositories { google(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android.*")
                includeGroup("com.google.testing.platform")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "ExcerptMemo"
include(":app")
