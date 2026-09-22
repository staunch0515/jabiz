import com.github.gradle.node.npm.task.NpmTask

plugins {
    java
    id("org.springframework.boot") version "4.0.1"      // 换成 start.spring.io 上当前最新的 4.0.x
    id("io.spring.dependency-management") version "1.1.7"
    id("com.github.node-gradle.node") version "7.1.0"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework:spring-jdbc")               // Flyway 需要
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")                        // Flyway 用 (JDBC)
    runtimeOnly("org.postgresql:r2dbc-postgresql")                  // 业务用 (R2DBC)

    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

// 前端：仅在打包 bootJar 时构建并并入 jar，日常 bootRun / test 不触发
node {
    download.set(true)
    version.set("22.12.0")
    nodeProjectDir.set(file("../frontend"))
}

val npmBuild = tasks.register<NpmTask>("npmBuild") {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run", "build"))
    inputs.files(
        fileTree("../frontend/src"), "../frontend/index.html",
        "../frontend/package.json", "../frontend/vite.config.ts"
    )
    outputs.dir("../frontend/dist")
}

tasks.bootJar {
    dependsOn(npmBuild)
    from("../frontend/dist") { into("BOOT-INF/classes/static") }
}