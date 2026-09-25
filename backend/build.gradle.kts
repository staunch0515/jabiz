import com.github.gradle.node.npm.task.NpmTask
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.0.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.github.node-gradle.node") version "7.1.0" apply false
}

// Settings shared by all modules. Dependencies are declared per module: core is pure Java and must not
// see Spring, Reactor or R2DBC on its compile classpath (docs/design/01-core-vs-runtime.md).
subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    group = "com.jabiz"
    version = "0.0.1-SNAPSHOT"

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    repositories {
        mavenCentral()
    }

    // The Spring Boot BOM only aligns versions; it adds no dependencies by itself.
    configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
        }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Integration tests connect to a local PostgreSQL when these are set, otherwise to Testcontainers.
        listOf("JABIZ_TEST_DB_URL", "JABIZ_TEST_DB_USER", "JABIZ_TEST_DB_PASSWORD").forEach { name ->
            System.getenv(name)?.let { environment(name, it) }
        }
        // Must be set before Reactor's Schedulers class loads, hence a JVM property rather than Spring config.
        systemProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", "true")
        // BlockHound instruments JDK classes; JDK 13+ requires this flag for it.
        jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Boot application: runtime + business declarations, packaged together with the frontend.
project(":app") {
    apply(plugin = "org.springframework.boot")
    apply(plugin = "com.github.node-gradle.node")

    configure<com.github.gradle.node.NodeExtension> {
        download.set(true)
        version.set("22.12.0")
        nodeProjectDir.set(file("${rootProject.projectDir}/../frontend"))
    }

    val npmBuild = tasks.register<NpmTask>("npmBuild") {
        dependsOn(tasks.named("npmInstall"))
        npmCommand.set(listOf("run", "build"))
        inputs.files(
            fileTree("${rootProject.projectDir}/../frontend/src"),
            "${rootProject.projectDir}/../frontend/index.html",
            "${rootProject.projectDir}/../frontend/package.json",
            "${rootProject.projectDir}/../frontend/vite.config.ts"
        )
        outputs.dir("${rootProject.projectDir}/../frontend/dist")
    }

    tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
        dependsOn(npmBuild)
        from("${rootProject.projectDir}/../frontend/dist") {
            into("BOOT-INF/classes/static")
        }
    }

    tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
        systemProperty("reactor.schedulers.defaultBoundedElasticOnVirtualThreads", "true")
    }
}
