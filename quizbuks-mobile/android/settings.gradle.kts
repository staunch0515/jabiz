// The QuizBuks Android app (docs/quizbuks/plans/Q49b-android.md): a Trusted Web Activity opening the takers' app.
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
    }
}

rootProject.name = "quizbuks-android"
include(":app")
