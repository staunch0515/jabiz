plugins {
    id("java")
}

group = "com.jabiz"
version = "0.0.1-SNAPSHOT"

dependencies {
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    // Plain JDBC in tests: per-class schema lifecycle and assertions on raw table contents.
    testImplementation("org.postgresql:postgresql")
}
