package com.jabiz.quizbuks;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * Waits tolerated on non-blocking threads in the QuizBuks tests, besides the platform's
 * ({@code JabizBlockHoundIntegration}); loaded through {@code ServiceLoader} when BlockHound installs.
 *
 * <p>Ending a sampled span hands it to OpenTelemetry's batch exporter and, once a batch is due, wakes the exporter's
 * worker through a one-place {@code ArrayBlockingQueue}, whose lock the worker takes while it waits for the next
 * batch. An event loop that meets the worker there parks for the length of that hand-off, which is no I/O and cannot
 * stall it; BlockHound reported it, at random, as a blocking call and failed the request with a 500
 * ({@link TracingUnderBlockHoundTest}). Not QuizBuks code: to move into the platform's integration.
 */
public final class QbBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside("io.opentelemetry.sdk.trace.export.BatchSpanProcessor$Worker", "addSpan");
    }
}
