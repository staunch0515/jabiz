package com.jabiz.runtime.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/**
 * Runs {@link RestoreVerifier} once the application has started on a restored copy ({@code jabiz.ops.verify-restore.enabled}),
 * logs the report, writes it as JSON to {@code jabiz.ops.verify-restore.report} when set, and stops the application:
 * exit code 0 when every check passed, 1 otherwise. A copy that fails its startup checks never gets here: the
 * application refuses to start, as anywhere else.
 */
@Component
@ConditionalOnProperty(name = RestoreVerifier.ENABLED, havingValue = "true")
public class RestoreVerificationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RestoreVerificationRunner.class);

    private final RestoreVerifier verifier;
    private final ConfigurableApplicationContext context;
    private final JsonMapper json;
    private final String reportPath;
    private final IntConsumer exit;

    public RestoreVerificationRunner(RestoreVerifier verifier, ConfigurableApplicationContext context, JsonMapper json,
        @Value("${jabiz.ops.verify-restore.report:}") String reportPath,
        ObjectProvider<IntConsumer> exit) {
        this.verifier = verifier;
        this.context = context;
        this.json = json;
        this.reportPath = reportPath;
        this.exit = exit.getIfAvailable(() -> System::exit);
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        RestoreVerifier.Report report = verifier.verify().block();
        for (RestoreVerifier.Item item : report.items()) {
            log.info("Restore verification {} {}: {}", item.passed() ? "PASSED" : "FAILED", item.name(), item.detail());
        }
        log.info("Restore verification {}", report.passed() ? "PASSED" : "FAILED");
        if (!reportPath.isBlank()) {
            Files.writeString(Path.of(reportPath), json.writeValueAsString(report));
        }
        int code = report.passed() ? 0 : 1;
        exit.accept(SpringApplication.exit(context, () -> code));
    }
}
