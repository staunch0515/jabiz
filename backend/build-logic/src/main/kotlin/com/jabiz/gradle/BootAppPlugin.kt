package com.jabiz.gradle

import com.github.gradle.node.NodeExtension
import com.github.gradle.node.pnpm.task.PnpmTask
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
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

        lateinit var app: JabizAppExtension
        app = JabizAppExtension(project.objects) { spa -> registerSpa(project, app, spa, pnpm) }
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
        registerAppProperties(project, app)
    }

    /**
     * `META-INF/jabiz-app.properties` in the jar: the settings the server shares with the admin frontend, from the
     * same `jabizApp` block, so the two cannot disagree (decision D22 item 7), and the SPAs packaged into the jar with
     * their entries and PWA modes: the server's default `jabiz.web.spa` (decision D43 item 6).
     */
    private fun registerAppProperties(project: Project, app: JabizAppExtension) {
        val write = project.tasks.register("jabizAppProperties", AppPropertiesTask::class.java) {
            languages.set(app.languages)
            region.set(app.region.orElse(""))
            spas.set(project.provider { app.spas.map { listOf(it.path, it.entry ?: "", it.pwa.value).joinToString("|") } })
            outputDir.set(project.layout.buildDirectory.dir("generated/jabiz-app"))
        }
        val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        main.resources.srcDir(write.flatMap { it.outputDir })
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
            // The paths come from the application's jabizApp block, which may run after this (a root build that
            // configures every Test task eagerly realizes this one as soon as the java plugin creates it), so they
            // are read only when the task runs.
            val updateOpenApi = providers.systemProperty("openapi.update-snapshot").orElse("false").get()
            jvmArgumentProviders.add(SystemPropertyArgument("openapi.snapshot", app.openApiSnapshot.absolutePath()))
            systemProperty("openapi.update-snapshot", updateOpenApi)
            // The catalog of the public templates (docs/design/15-public-access.md section 7), the same way.
            val updatePublicQueries = providers.systemProperty("public-queries.update-snapshot").orElse("false").get()
            jvmArgumentProviders.add(
                SystemPropertyArgument("public-queries.snapshot", app.publicQueriesSnapshot.absolutePath()))
            systemProperty("public-queries.update-snapshot", updatePublicQueries)
            if (updateScenarios == "true" || updateOpenApi == "true" || updatePublicQueries == "true") {
                outputs.upToDateWhen { false }
            }
        }
    }

    /**
     * `pnpm install --frozen-lockfile` in [dir], once per directory in this module: the platform frontend's under
     * [PLATFORM_INSTALL], which the SPAs built from it and the application SPAs linking its packages share.
     */
    private fun installTask(project: Project, name: String, dir: Directory, pnpm: Provider<PnpmLock>): TaskProvider<PnpmTask> {
        if (name in project.tasks.names) {
            return project.tasks.named(name, PnpmTask::class.java)
        }
        return project.tasks.register(name, PnpmTask::class.java) {
            dependsOn("pnpmSetup")
            usesService(pnpm)
            workingDir.set(dir)
            pnpmCommand.set(listOf("install", "--frozen-lockfile"))
            // A pnpm workspace (decision D34): the frontend packages' manifests are installed with it.
            inputs.files(
                dir.file("package.json"),
                dir.file("pnpm-lock.yaml"),
                project.fileTree(dir) { include("pnpm-workspace.yaml", "packages/*/package.json") },
            )
            outputs.file(dir.file("node_modules/.modules.yaml"))
        }
    }

    /**
     * Installs and builds one SPA with `VITE_BASE` set to its prefix (and `JABIZ_ADMIN_EXTENSION` to the application's
     * admin pages), into a directory of this module's own (two applications can build the same frontend with
     * different bases), and packages it under `static/<prefix>`. An SPA of the application's own (not the platform
     * frontend) links the platform's packages (decision D34 item 5): the platform frontend is installed first, its
     * packages are inputs of the build, and `spaCheck<name>` (`pnpm app:check`, part of `check`) makes sure the SPA
     * resolves the very versions the platform does before it is built.
     */
    private fun registerSpa(project: Project, app: JabizAppExtension, spa: SpaSpec, pnpm: Provider<PnpmLock>) {
        val sourceDir = project.layout.projectDirectory.dir(spa.sourceDir)
        val platformDir = project.rootProject.layout.projectDirectory.dir("../frontend")
        val platform = sourceDir.asFile.canonicalFile == platformDir.asFile.canonicalFile
        val suffix = spa.name.split('-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val outDir = project.layout.buildDirectory.dir("spa/${spa.name}")
        val extensionDir = spa.extension?.let { project.layout.projectDirectory.dir(it) }

        val platformInstall = installTask(project, PLATFORM_INSTALL, platformDir, pnpm)
        val install = if (platform) platformInstall else installTask(project, "pnpmInstall$suffix", sourceDir, pnpm)
        val spaCheck = if (platform) null else project.tasks.register("spaCheck$suffix", PnpmTask::class.java) {
            group = "verification"
            description = "Checks that the SPA served under ${spa.path} uses the platform frontend's versions (app:check)."
            dependsOn(platformInstall, install)
            usesService(pnpm)
            workingDir.set(platformDir)
            pnpmCommand.set(listOf("run", "app:check", sourceDir.asFile.absolutePath))
            inputs.files(
                sourceDir.file("package.json"),
                sourceDir.file("pnpm-lock.yaml"),
                platformDir.file("pnpm-lock.yaml"),
                platformDir.file("scripts/app-check.mjs"),
                project.fileTree(platformDir) { include("packages/*/package.json") },
            )
            val marker = project.layout.buildDirectory.file("spa-check/${spa.name}.ok")
            outputs.file(marker)
            doLast { marker.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") } }
        }
        spaCheck?.let { check -> project.tasks.named("check") { dependsOn(check) } }

        val build = project.tasks.register("spaBuild$suffix", PnpmTask::class.java) {
            group = "build"
            description = "Builds the single-page application served under ${spa.path}."
            dependsOn(install, platformInstall)
            spaCheck?.let { dependsOn(it) }
            usesService(pnpm)
            workingDir.set(sourceDir)
            environment.put("VITE_BASE", spa.base)
            // The sign-in entry, the PWA mode and the other SPAs' prefixes, which its Service Worker leaves alone
            // (decision D43 items 1 and 6).
            environment.put("VITE_JABIZ_ENTRY", spa.entry ?: "")
            environment.put("VITE_JABIZ_PWA", spa.pwa.value)
            val others = project.provider { app.spas.filter { it.path != spa.path }.joinToString(",") { it.base } }
            environment.put("VITE_JABIZ_SPA_PREFIXES", others)
            inputs.property("entry", spa.entry ?: "")
            inputs.property("pwa", spa.pwa.value)
            inputs.property("otherPrefixes", others)
            // Interface languages and region (decision D22 item 7); the server gets the same from jabiz-app.properties.
            val app = project.extensions.getByType(JabizAppExtension::class.java)
            environment.put("VITE_JABIZ_LANGUAGES", app.languages.map { it.joinToString(",") })
            environment.put("VITE_JABIZ_REGION", app.region.orElse(""))
            inputs.property("languages", app.languages)
            inputs.property("region", app.region.orElse(""))
            // The commit the build is from (JABIZ_BUILD_COMMIT, set by an image build): the admin's sign-in page
            // shows it (17 section 3), so whoever signs in sees which version runs.
            val commit = project.providers.environmentVariable("JABIZ_BUILD_COMMIT").orElse("")
            environment.put("VITE_JABIZ_BUILD_COMMIT", commit)
            inputs.property("buildCommit", commit)
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
                // @jabiz/client and @jabiz/ui, built from source, linked by an application's own SPA.
                project.fileTree(platformDir.dir("packages")) { exclude("**/node_modules/**") },
                project.fileTree(sourceDir) {
                    include(
                        "index.html", "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", "vite.config.ts",
                        "tsconfig*.json", "scripts/**",
                    )
                },
            )
            outputs.dir(outDir)
        }

        project.tasks.named("bootJar", BootJar::class.java) {
            from(build) { into("BOOT-INF/classes/${spa.staticDir}") }
        }
    }

    /** Writes the settings the server shares with the frontend (see [registerAppProperties]). */
    abstract class AppPropertiesTask : DefaultTask() {
        @get:Input
        abstract val languages: ListProperty<String>

        @get:Input
        abstract val region: Property<String>

        /** The SPAs, each `path|entry|pwa`. */
        @get:Input
        abstract val spas: ListProperty<String>

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun write() {
            val file = outputDir.file("META-INF/jabiz-app.properties").get().asFile
            file.parentFile.mkdirs()
            val lines = mutableListOf("# Written by the jabiz.boot-app plugin from jabizApp { } (decision D22 item 7).")
            languages.get().takeIf { it.isNotEmpty() }?.let { lines += "jabiz.i18n.languages=${it.joinToString(",")}" }
            // Exported reports format numbers and times like the frontend (docs/design/19-reports.md section 4).
            region.get().takeIf { it.isNotBlank() }?.let { lines += "jabiz.region=$it" }
            // The SPAs in the jar (decision D43 item 6): the default of jabiz.web.spa, which an application's own
            // configuration replaces as a whole, and what was built, which the startup check compares it with.
            spas.get().forEachIndexed { i, spa ->
                val (path, entry, pwa) = spa.split('|')
                for (prefix in listOf("jabiz.web.spa[$i]", "jabiz.build.spa[$i]")) {
                    lines += "$prefix.path=$path"
                    if (entry.isNotEmpty()) lines += "$prefix.entry=$entry"
                    lines += "$prefix.pwa=$pwa"
                }
            }
            file.writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * `-D<name>=<value>` with the value read when the task runs rather than when it is configured, so that the
     * application's `jabizApp` block has run (see [configureTests]). The value is a task input, like a plain
     * system property.
     */
    class SystemPropertyArgument(
        @get:Input val name: String,
        private val value: Provider<String>,
    ) : CommandLineArgumentProvider {

        @get:Input
        val propertyValue: String get() = value.get()

        override fun asArguments(): Iterable<String> = listOf("-D$name=${value.get()}")
    }

    /** Serializes pnpm runs across the build (see [apply]). */
    abstract class PnpmLock : BuildService<BuildServiceParameters.None>

    private companion object {
        /** The task installing the platform frontend (`../frontend` of the backend root). */
        const val PLATFORM_INSTALL = "pnpmInstallPlatformFrontend"

        fun RegularFileProperty.absolutePath(): Provider<String> = map { it.asFile.absolutePath }

        /** Integration tests and platformCheck connect to a local PostgreSQL when these are set (CLAUDE.md section 6). */
        val TEST_DB_VARIABLES = listOf("JABIZ_TEST_DB_URL", "JABIZ_TEST_DB_USER", "JABIZ_TEST_DB_PASSWORD")
    }
}
