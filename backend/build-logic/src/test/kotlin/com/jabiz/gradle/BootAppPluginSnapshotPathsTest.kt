package com.jabiz.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The snapshot paths an application sets in `jabizApp { }` reach its `test` task (ROADMAP phase 16j), although the
 * root build, like `backend/build.gradle.kts`, realizes every Test task before the application's block runs.
 */
class BootAppPluginSnapshotPathsTest {

    @TempDir
    lateinit var root: File

    @Test
    fun `the application's snapshot paths reach the test task`() {
        fixture(
            """
            openApiSnapshot = file("src/test/resources/openapi.json")
            publicQueriesSnapshot = file("src/test/resources/public-queries.json")
            """,
        )

        val properties = snapshotProperties()

        assertEquals(File(root, "quiz/src/test/resources/openapi.json").canonicalPath, properties["openapi.snapshot"])
        assertEquals(
            File(root, "quiz/src/test/resources/public-queries.json").canonicalPath,
            properties["public-queries.snapshot"],
        )
    }

    @Test
    fun `without an override the platform frontend's snapshots are used`() {
        fixture("")

        val properties = snapshotProperties()

        // The backend root's sibling frontend/, as for backend/app.
        assertEquals(File(root, "../frontend/openapi/openapi.json").canonicalPath, properties["openapi.snapshot"])
        assertEquals(
            File(root, "../frontend/openapi/public-queries.json").canonicalPath,
            properties["public-queries.snapshot"],
        )
    }

    /** A backend root with the platform's runtime module and an application `quiz` that sets [settings]. */
    private fun fixture(settings: String) {
        root.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "backend"
            include("runtime", "quiz")
            """.trimIndent(),
        )
        // Like backend/build.gradle.kts: an eager withType action realizes each Test task when it is created.
        root.resolve("build.gradle.kts").writeText(
            """
            subprojects {
                apply(plugin = "java")
                tasks.withType<Test> { systemProperty("fixture.root-configured", "true") }
            }
            """.trimIndent(),
        )
        root.resolve("runtime").mkdirs()
        root.resolve("runtime/build.gradle.kts").writeText("plugins { `java-test-fixtures` }\n")
        root.resolve("quiz").mkdirs()
        root.resolve("quiz/build.gradle.kts").writeText(
            """
            plugins { id("jabiz.boot-app") }

            jabizApp {
                mainClass = "com.example.quiz.QuizApp"
                $settings
            }

            tasks.register("printSnapshotProperties") {
                val test = tasks.named<Test>("test")
                doLast {
                    val arguments = test.get().jvmArgumentProviders.flatMap { it.asArguments() } +
                        test.get().allJvmArgs
                    arguments.filter { it.startsWith("-D") && it.contains(".snapshot=") }
                        .forEach { println("SNAPSHOT " + it.removePrefix("-D")) }
                }
            }
            """.trimIndent(),
        )
    }

    private fun snapshotProperties(): Map<String, String> {
        val result = GradleRunner.create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments(":quiz:printSnapshotProperties", "--stacktrace")
            .build()
        return result.output.lineSequence()
            .filter { it.startsWith("SNAPSHOT ") }
            .map { it.removePrefix("SNAPSHOT ").split('=', limit = 2) }
            .associate { (name, value) -> name to File(value).canonicalPath }
    }
}
