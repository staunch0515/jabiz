package com.jabiz.runtime.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal application for the runtime's own integration tests: the platform with no business module.
 * Tests in this package find it by Spring Boot's upward package search.
 */
@SpringBootApplication
public class RuntimeTestApplication {
}
