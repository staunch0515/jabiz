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
    // Notifications of tasks by e-mail (docs/design/18-numbering-approvals-tasks.md section 5.4); off by default.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // Authentication (docs/design/10-security.md): Spring Security for WebFlux, JWT access tokens, BCrypt.
    api("org.springframework.boot:spring-boot-starter-security")
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // Scheduled jobs (docs/design/11-ledger-events-jobs.md section 4): one instance per run, locked through R2DBC.
    // JobRunner takes a LockProvider, so the core API is part of runtime's.
    api("net.javacrumbs.shedlock:shedlock-core:7.10.1")
    implementation("net.javacrumbs.shedlock:shedlock-provider-r2dbc:7.10.1")
    implementation("io.micrometer:context-propagation")
    // Observability (docs/design/13-observability-ops.md): Micrometer observations of requests, R2DBC statements and
    // the platform's own units of work, exported as OTLP metrics, traces and logs when an endpoint is configured.
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("io.r2dbc:r2dbc-proxy")
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.21.0-alpha")
    // OpenAPI description of the web API; the frontend generates its types from it (docs/design/12-frontend.md).
    // 3.0.1 is the release built against Spring Boot 4.0.1. No UI: the document is served at /api/meta/openapi.
    implementation("org.springdoc:springdoc-openapi-starter-webflux-api:3.0.1")
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
