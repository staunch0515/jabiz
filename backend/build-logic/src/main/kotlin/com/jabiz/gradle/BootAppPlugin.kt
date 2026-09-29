package com.jabiz.gradle

import com.github.gradle.node.NodeExtension
import com.github.gradle.node.pnpm.task.PnpmTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.project
import org.gradle.process.CommandLineArgumentProvider
import org.springframework.boot.gradle.dsl.SpringBootExtension
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

/**
 * `jabiz.boot-app`: a deployable application (docs/design/17-apps-and-branches.md section 3.1). A Spring Boot jar
 * of the runtime and the application's declarations with its single-page applications packaged in, `platformCheck`
 * wired into `check`, and the test properties of scenario replay and the OpenAPI snapshot.
 */
class BootAppPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        with(project.pluginManager) {
            apply("java")
            apply("org.springframework.boot")
            apply("com.github.node-gradle.node")
        }

        // The frontends are built with pnpm, downloaded by node-gradle (docs/design/12-frontend.md section 7).
        project.extensions.configure(NodeExtension::class.java) {
            download.set(true)
            version.set("22.12.0")
            pnpmVersion.set("10.18.0")
        }
        // Two applications (or two SPAs of one) may build the same pnpm project: never at the same time.
        val pnpm = project.gradle.sharedServices.registerIfAbsent("jabizPnpm", PnpmLock::class.java) {
            maxParallelUsages.set(1)
        }

        val app = JabizAppExtension(project.objects) { spa -> registerSpa(project, spa, pnpm) }
        app.openApiSnapshot.convention(
            project.rootProject.layout.projectDirectory.file("../frontend/openapi/openapi.json"))
        app.publicQueriesSnapshot.convention(
            project.rootProject.layout.projectDirectory.file("../frontend/openapi/public-queries.json"))
        project.extensions.add(JabizAppExtension::class.java, "jabizApp", app)

        project.extensions.configure(SpringBootExtension::class.java) { mainClass.set(app.mainClass) }
        project.tasks.named("bootRun", BootRun::class.java) {
            // Must be set before Reactor's Schedulers class loads, hence a JVM property rather than Spring config.
            systemProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", "true")
        }

        registerPlatformCheck(project, app)
        configureTests(project, app)
    }

    /**
     * platformCheck (docs/design/07-quality.md section 2): the startup self-checks, run against a freshly migrated
     * test database (JABIZ_TEST_DB_URL, else Testcontainers); prints one line per problem, fails on any error.
     */
    private fun registerPlatformCheck(project: Project, app: JabizAppExtension) {
        val runtime = project.configurations.create("platformCheckRuntime")
        project.dependencies.add(runtime.name, project.dependencies.testFixtures(project.dependencies.project(":runtime")))
        val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")

        val platformCheck = project.tasks.register("platformCheck", JavaExec::class.java) {
            group = "verification"
            description = "Runs the platform's static checks (metamodel, datasets, SQL templates, ...) against a test database."
            classpath = main.runtimeClasspath + runtime
            mainClass.set("com.jabiz.runtime.test.PlatformCheckLauncher")
            val appClass = app.mainClass
            argumentProviders.add(CommandLineArgumentProvider { listOf(appClass.get()) })
            systemProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", "true")
            TEST_DB_VARIABLES.forEach { name -> System.getenv(name)?.let { environment(name, it) } }
            // Always run: the database, not only the sources, is checked.
            outputs.upToDateWhen { false }
        }
        project.tasks.named("check") { dependsOn(platformCheck) }
    }

    private fun configureTests(project: Project, app: JabizAppExtension) {
        val providers = project.providers
        project.tasks.named("test", Test::class.java) {
            // Scenario replay (docs/design/07-quality.md section 3): snapshots live next to the scenarios in the
            // source tree; -Dscenario.update-snapshots=true (given to Gradle) rewrites the ones that differ.
            val updateScenarios = providers.systemProperty("scenario.update-snapshots").orElse("false").get()
            systemProperty("scenario.resources-dir",
                project.layout.projectDirectory.dir("src/test/resources").asFile.absolutePath)
            systemProperty("scenario.update-snapshots", updateScenarios)
            // The OpenAPI document the frontend generates its types from (docs/design/12-frontend.md section 3);
            // -Dopenapi.update-snapshot=true rewrites it when the API changed.
            val updateOpenApi = providers.systemProperty("openapi.update-snapshot").orElse("false").get()
            systemProperty("openapi.snapshot", app.openApiSnapshot.get().asFile.absolutePath)
            systemProperty("openapi.update-snapshot", updateOpenApi)
            // The catalog of the public templates (docs/design/15-public-access.md section 7), the same way.
            val updatePublicQueries = providers.systemProperty("public-queries.update-snapshot").orElse("false").get()
            systemProperty("public-queries.snapshot", app.publicQueriesSnapshot.get().asFile.absolutePath)
            systemProperty("public-queries.update-snapshot", updatePublicQueries)
            if (updateScenarios == "true" || updateOpenApi == "true" || updatePublicQueries == "true") {
                outputs.upToDateWhen { false }
            }
        }
    }

    /**
     * Installs and builds one SPA with `VITE_BASE` set to its prefix (and `JABIZ_ADMIN_EXTENSION` to the application's
     * admin pages), into a directory of this module's own (two applications can build the same frontend with
     * different bases), and packages it under `static/<prefix>`.
     */
    private fun registerSpa(project: Project, spa: SpaSpec, pnpm: Provider<PnpmLock>) {
        val sourceDir = project.layout.projectDirectory.dir(spa.sourceDir)
        val suffix = spa.name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val outDir = project.layout.buildDirectory.dir("spa/${spa.name}")
        val extensionDir = spa.extension?.let { project.layout.projectDirectory.dir(it) }

        val install = project.tasks.register("pnpmInstall$suffix", PnpmTask::class.java) {
            dependsOn("pnpmSetup")
            usesService(pnpm)
            workingDir.set(sourceDir)
            pnpmCommand.set(listOf("install", "--frozen-lockfile"))
            inputs.files(sourceDir.file("package.json"), sourceDir.file("pnpm-lock.yaml"))
            outputs.file(sourceDir.file("node_modules/.modules.yaml"))
        }

        val build = project.tasks.register("spaBuild$suffix", PnpmTask::class.java) {
            group = "build"
            description = "Builds the single-page application served under ${spa.path}."
            dependsOn(install)
            usesService(pnpm)
            workingDir.set(sourceDir)
            environment.put("VITE_BASE", spa.base)
            // The application's own admin pages (decision D22); the build type-checks them before bundling.
            extensionDir?.let { environment.put("JABIZ_ADMIN_EXTENSION", it.asFile.absolutePath) }
            pnpmCommand.set(outDir.map {
                listOf("run", "build", "--outDir", it.asFile.absolutePath, "--emptyOutDir")
            })
            inputs.property("base", spa.base)
            inputs.property("extension", spa.extension ?: "")
            extensionDir?.let { dir -> inputs.files(project.fileTree(dir) { exclude("node_modules/**") }) }
            inputs.files(
                project.fileTree(sourceDir.dir("src")),
                project.fileTree(sourceDir.dir("public")),
                project.fileTree(sourceDir.dir("openapi")),
                project.fileTree(sourceDir) {
                    include("index.html", "package.json", "pnpm-lock.yaml", "vite.config.ts", "tsconfig*.json", "scripts/**")
                },
            )
            outputs.dir(outDir)
        }

        project.tasks.named("bootJar", BootJar::class.java) {
            from(build) { into("BOOT-INF/classes/${spa.staticDir}") }
        }
    }

    /** Serializes pnpm runs across the build (see [apply]). */
    abstract class PnpmLock : BuildService<BuildServiceParameters.None>

    private companion object {
        /** Integration tests and platformCheck connect to a local PostgreSQL when these are set (CLAUDE.md section 6). */
        val TEST_DB_VARIABLES = listOf("JABIZ_TEST_DB_URL", "JABIZ_TEST_DB_USER", "JABIZ_TEST_DB_PASSWORD")
    }
}
