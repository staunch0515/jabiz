package com.jabiz.runtime.test;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * The blocking calls tolerated on non-blocking threads, each with its reason
 * (docs/design/07-quality.md section 5). Loaded through {@code ServiceLoader} when BlockHound installs.
 * Anything not listed here fails the test that triggers it.
 */
public final class JabizBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        // SCRAM authentication in the R2DBC PostgreSQL driver draws its client nonce from SecureRandom,
        // which reads /dev/urandom once per new connection. It happens only while a pooled connection is
        // being opened and never waits on entropy, so it cannot stall an event loop in practice.
        builder.allowBlockingCallsInside("com.ongres.scram.common.ScramFunctions", "nonce");
        // Ending a sampled span hands it to OpenTelemetry's batch exporter and, once a batch is due, wakes the
        // exporter's worker through a one-place ArrayBlockingQueue whose lock the worker also takes while it waits
        // for the next batch. An event loop meeting the worker there parks for that thread hand-off, which is no I/O;
        // without this BlockHound failed random requests with a 500 (TracingUnderBlockHoundTest).
        builder.allowBlockingCallsInside("io.opentelemetry.sdk.trace.export.BatchSpanProcessor$Worker", "addSpan");
    }
}
