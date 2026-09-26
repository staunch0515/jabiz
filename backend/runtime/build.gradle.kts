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
    // Business modules annotate process inputs with Bean Validation constraints (docs/design/06-process.md section 8).
    api("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Authentication (docs/design/10-security.md): Spring Security for WebFlux, JWT access tokens, BCrypt.
    api("org.springframework.boot:spring-boot-starter-security")
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("io.micrometer:context-propagation")
    // SQL template headers: YAML, validated against a JSON Schema (docs/design/05-sql-template.md section 2).
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.networknt:json-schema-validator:3.0.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    // JDBC: Flyway, and the precompile check of SQL templates (reads server error positions).
    implementation("org.postgresql:postgresql")
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
    // Scenario replay (docs/design/07-quality.md section 3): scenario files are YAML.
    testFixturesImplementation("tools.jackson.dataformat:jackson-dataformat-yaml")
}
