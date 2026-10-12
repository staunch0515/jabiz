package com.jabiz.app.it;

import com.jabiz.runtime.test.PipelinedRequests;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

import static com.jabiz.runtime.test.PipelinedRequests.get;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's own server keeps reading a keep-alive connection after a request it buffered as pipelined was
 * answered at once ({@code PipelinedReadResumer}): here the Service Worker guard's 404 behind the health check, as a
 * PWA's install revalidating its shell does it behind the page's own requests (ROADMAP phase 16c-2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PipelinedRequestsIT extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void theConnectionIsStillReadAfterAPipelinedRequest() throws IOException {
        String health = get("/actuator/health");
        String guarded = get("/not-a-worker.js", "Service-Worker: script\r\n");
        for (int i = 0; i < 20; i++) {
            assertThat(PipelinedRequests.thirdAnswered(port, health, guarded, health, 5000)).as("connection %d", i)
                .isTrue();
        }
    }
}
