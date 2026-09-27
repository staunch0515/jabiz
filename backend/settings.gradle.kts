pluginManagement {
    // The convention plugin jabiz.boot-app (docs/design/17-apps-and-branches.md section 3.1).
    includeBuild("build-logic")
}

rootProject.name = "backend"

// The platform's modules and the demo application.
val platformModules = listOf("core", "ext-geo", "runtime", "app")
platformModules.forEach { include(it) }

// Every other direct subdirectory with a build script is a module too, so that an application branch adds its
// modules (e.g. backend/culture) without editing this file (docs/design/17-apps-and-branches.md section 3.1).
val notModules = setOf("build-logic", "buildSrc", "gradle")
rootDir.listFiles()!!
    .filter { it.isDirectory && it.name !in platformModules && it.name !in notModules && !it.name.startsWith(".") }
    .filter { it.resolve("build.gradle.kts").isFile }
    .map { it.name }
    .sorted()
    .forEach { include(it) }
