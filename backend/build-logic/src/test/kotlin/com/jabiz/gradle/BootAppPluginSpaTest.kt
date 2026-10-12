package com.jabiz.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The SPAs of `jabizApp { spa(...) }` (ROADMAP phase 16c-2, decision D43): the build variables of each, the
 * installation of the platform frontend and `spaCheck` for an application's own SPA, and the SPAs written into the
 * jar as the server's default `jabiz.web.spa`. Configuration only: nothing is installed or built.
 */
class BootAppPluginSpaTest {

    @TempDir
    lateinit var dir: File

    /** `dir/backend` is the backend root; `dir/frontend` the platform frontend, as in the repository. */
    private val root: File get() = File(dir, "backend")

    @Test
    fun `each SPA is built with its entry, PWA mode and the other SPAs' prefixes`() {
        val out = run(":quiz:printSpas")

        assertEquals("/", out["spaBuildRoot.VITE_BASE"])
        assertEquals("admin", out["spaBuildRoot.VITE_JABIZ_ENTRY"])
        assertEquals("off", out["spaBuildRoot.VITE_JABIZ_PWA"])
        assertEquals("/portal/,/shop/", out["spaBuildRoot.VITE_JABIZ_SPA_PREFIXES"])
        assertEquals("/portal/", out["spaBuildPortal.VITE_BASE"])
        assertEquals("portal", out["spaBuildPortal.VITE_JABIZ_ENTRY"])
        assertEquals("on", out["spaBuildPortal.VITE_JABIZ_PWA"])
        assertEquals("/,/shop/", out["spaBuildPortal.VITE_JABIZ_SPA_PREFIXES"])
        assertEquals("", out["spaBuildShop.VITE_JABIZ_ENTRY"])
        assertEquals("remove", out["spaBuildShop.VITE_JABIZ_PWA"])
    }

    @Test
    fun `an application's own SPA installs the platform frontend first and is checked before it is built`() {
        val out = run(":quiz:printSpas")

        // The platform frontend is installed once, by one task, for every SPA that needs it.
        assertEquals(setOf("pnpmInstallPlatformFrontend"), out.getValue("spaBuildRoot.dependsOn").split(",").toSet())
        assertEquals(setOf("pnpmInstallPlatformFrontend", "pnpmInstallPortal", "spaCheckPortal"),
            out.getValue("spaBuildPortal.dependsOn").split(",").toSet())
        assertEquals(setOf("pnpmInstallPlatformFrontend", "pnpmInstallPortal"),
            out.getValue("spaCheckPortal.dependsOn").split(",").toSet())
        assertEquals(File(dir, "frontend").canonicalPath, out["spaCheckPortal.workingDir"])
        assertEquals("run app:check " + File(root, "quiz/portal").canonicalPath, out["spaCheckPortal.command"])
        assertEquals(File(dir, "frontend").canonicalPath, out["pnpmInstallPlatformFrontend.workingDir"])
        // The checks run with check; the platform frontend needs none.
        val check = out.getValue("check.dependsOn").split(",")
        assertTrue("spaCheckPortal" in check)
        assertTrue("spaCheckShop" in check)
        assertFalse(out.containsKey("spaCheckRoot.dependsOn"))
        // The platform's packages, which the SPA links, are inputs of its build.
        assertEquals("true", out["spaBuildPortal.inputs.packages"])
    }

    @Test
    fun `the SPAs are written into the jar as the server's default`() {
        run(":quiz:jabizAppProperties")

        val properties = File(root, "quiz/build/generated/jabiz-app/META-INF/jabiz-app.properties").readLines()
        for (prefix in listOf("jabiz.web.spa", "jabiz.build.spa")) {
            assertTrue("$prefix[0].path=/" in properties, properties.toString())
            assertTrue("$prefix[0].entry=admin" in properties)
            assertTrue("$prefix[0].pwa=off" in properties)
            assertTrue("$prefix[1].path=/portal" in properties)
            assertTrue("$prefix[1].entry=portal" in properties)
            assertTrue("$prefix[1].pwa=on" in properties)
            assertTrue("$prefix[2].path=/shop" in properties)
            assertTrue("$prefix[2].pwa=remove" in properties)
            assertFalse(properties.any { it.startsWith("$prefix[2].entry") })
        }
    }

    private fun run(task: String): Map<String, String> {
        root.mkdirs()
        File(dir, "frontend/packages/client/src").mkdirs()
        File(dir, "frontend/packages/client/src/index.ts").writeText("export {}\n")
        root.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "backend"
            include("runtime", "quiz")
            """.trimIndent(),
        )
        root.resolve("build.gradle.kts").writeText("subprojects { apply(plugin = \"java\") }\n")
        root.resolve("runtime").mkdirs()
        root.resolve("runtime/build.gradle.kts").writeText("plugins { `java-test-fixtures` }\n")
        root.resolve("quiz/portal").mkdirs()
        root.resolve("quiz/build.gradle.kts").writeText(
            """
            import com.jabiz.gradle.Pwa
            import com.github.gradle.node.pnpm.task.PnpmTask

            plugins { id("jabiz.boot-app") }

            jabizApp {
                mainClass = "com.example.quiz.QuizApp"
                spa("/", "../../frontend", entry = "admin")
                spa("/portal", "portal", entry = "portal", pwa = Pwa.ON)
                spa("/shop", "shop", pwa = Pwa.REMOVE)
            }

            tasks.register("printSpas") {
                doLast {
                    fun deps(name: String) = tasks.getByName(name).taskDependencies.getDependencies(null)
                        .map { it.name }.filter { it != "pnpmSetup" }.sorted().joinToString(",")
                    for (name in listOf("spaBuildRoot", "spaBuildPortal", "spaBuildShop")) {
                        val task = tasks.getByName(name) as PnpmTask
                        task.environment.get().forEach { (k, v) -> println("OUT " + name + "." + k + "=" + v) }
                        println("OUT " + name + ".dependsOn=" + deps(name))
                        println("OUT " + name + ".inputs.packages=" + task.inputs.files.files.any {
                            it.path.contains("frontend" + File.separator + "packages") })
                    }
                    for (name in listOf("spaCheckPortal", "check")) {
                        println("OUT " + name + ".dependsOn=" + deps(name))
                    }
                    val check = tasks.getByName("spaCheckPortal") as PnpmTask
                    println("OUT spaCheckPortal.workingDir=" + check.workingDir.get().asFile.canonicalPath)
                    println("OUT spaCheckPortal.command=" + check.pnpmCommand.get().joinToString(" "))
                    val install = tasks.getByName("pnpmInstallPlatformFrontend") as PnpmTask
                    println("OUT pnpmInstallPlatformFrontend.workingDir=" + install.workingDir.get().asFile.canonicalPath)
                }
            }
            """.trimIndent(),
        )
        val result = GradleRunner.create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments(task, "--stacktrace")
            .build()
        return result.output.lineSequence()
            .filter { it.startsWith("OUT ") }
            .map { it.removePrefix("OUT ").split('=', limit = 2) }
            .associate { (name, value) -> name to value }
    }
}
