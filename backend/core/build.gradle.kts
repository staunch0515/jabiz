import com.github.gradle.node.npm.task.NpmTask

plugins {
    id("java")
}

group = "com.jabiz"
version = "0.0.1-SNAPSHOT"

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}