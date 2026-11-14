package com.jabiz.quizbuks.it;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's database connections run with PostgreSQL's JIT off (application.yml,
 * {@code spring.r2dbc.properties.options}; #701 re-review M-1): compiling the plan of the daily check of the operators'
 * settlements took 10 s of an 11 s run. Kept by this test, so that the setting is not lost unseen.
 */
class AppConnectionsIT extends ContentItSupport {

    @Autowired
    ConnectionFactory connectionFactory;

    @Test
    void theApplicationsConnectionsRunWithoutJit() {
        for (int i = 0; i < 3; i++) {
            String jit = Mono.usingWhen(connectionFactory.create(),
                (Connection connection) -> Mono.from(connection.createStatement("SHOW jit").execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> row.get(0, String.class)))),
                Connection::close).block();
            assertThat(jit).isEqualTo("off");
        }
    }
}
