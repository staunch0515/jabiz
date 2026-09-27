import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    // Puts the plugins of the build conventions (backend/build-logic, with their versions) on the classpath.
    id("jabiz.boot-app") apply false
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

    // Spring resolves @PathVariable/@RequestParam names from parameter metadata. The Boot plugin adds this
    // flag only where it is applied (app), so it is set for every module here.
    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
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
