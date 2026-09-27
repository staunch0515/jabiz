// Convention plugins of the platform's build (docs/design/17-apps-and-branches.md section 3.1), included by
// backend/settings.gradle.kts.
rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
