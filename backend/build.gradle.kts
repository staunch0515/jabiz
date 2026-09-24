import com.github.gradle.node.npm.task.NpmTask

plugins {
    java
    id("org.springframework.boot") version "4.0.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.github.node-gradle.node") version "7.1.0" apply false
}

// 所有子模块（包括 core 和 app）共享依赖与 Java 环境
subprojects {
    // 1. 显式为子模块应用插件，彻底解决 "Extension with name 'java' does not exist"
    apply(plugin = "java")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")

    group = "com.example"
    version = "0.0.1-SNAPSHOT"

    // 2. 配置 Java 21 Toolchain
    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    repositories {
        mavenCentral()
    }

    // 3. 所有子模块都享有这些核心依赖（WebFlux, R2DBC, Validation 等）
    dependencies {
        "implementation"("org.springframework.boot:spring-boot-starter-webflux")
        "implementation"("org.springframework.boot:spring-boot-starter-data-r2dbc")
        "implementation"("org.springframework.boot:spring-boot-starter-validation")
        "implementation"("org.springframework.boot:spring-boot-starter-actuator")
        "implementation"("org.springframework.boot:spring-boot-starter-flyway")
        "implementation"("org.springframework:spring-jdbc")
        "runtimeOnly"("org.flywaydb:flyway-database-postgresql")
        "runtimeOnly"("org.postgresql:postgresql")
        "runtimeOnly"("org.postgresql:r2dbc-postgresql")

        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("org.springframework.boot:spring-boot-starter-webflux-test")
        "testImplementation"("org.springframework.boot:spring-boot-starter-data-r2dbc-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // Integration tests connect to a local PostgreSQL when these are set, otherwise to Testcontainers.
        listOf("JABIZ_TEST_DB_URL", "JABIZ_TEST_DB_USER", "JABIZ_TEST_DB_PASSWORD").forEach { name ->
            System.getenv(name)?.let { environment(name, it) }
        }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// core is a library, not an application: it has no main class, so only the plain jar is built.
project(":core") {
    tasks.named("bootJar") { enabled = false }
    tasks.named<Jar>("jar") { enabled = true }
}

// 针对实际运行和打包前端的启动模块（假设名字叫 app）
project(":app") {
    apply(plugin = "com.github.node-gradle.node")

    dependencies {
        // app 依赖 core
        "implementation"(project(":core"))
    }

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
}