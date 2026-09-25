plugins {
    `java-library`
    `java-test-fixtures`
}

// jabiz-runtime: the thin reactive layer (storage, transactions, process execution, web).
base {
    archivesName.set("jabiz-runtime")
}

dependencies {
    api(project(":core"))
    api("org.springframework.boot:spring-boot-starter-webflux")
    api("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("io.micrometer:context-propagation")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.postgresql:r2dbc-postgresql")

    // Shared by the integration tests of runtime and app (docs/design/07-quality.md section 7).
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("org.springframework.boot:spring-boot-starter-webflux-test")
    testFixturesApi("org.springframework.boot:spring-boot-starter-data-r2dbc-test")
    testFixturesApi("io.projectreactor:reactor-test")
    testFixturesApi("org.testcontainers:testcontainers-postgresql")
    testFixturesApi("org.testcontainers:testcontainers-junit-jupiter")
    testFixturesApi("org.postgresql:postgresql")
    // On the test classpath, blockhound-junit-platform installs BlockHound before any test runs.
    testFixturesApi("io.projectreactor.tools:blockhound:1.0.17.RELEASE")
    testFixturesRuntimeOnly("io.projectreactor.tools:blockhound-junit-platform:1.0.17.RELEASE")
    testFixturesRuntimeOnly("org.postgresql:r2dbc-postgresql")
}
