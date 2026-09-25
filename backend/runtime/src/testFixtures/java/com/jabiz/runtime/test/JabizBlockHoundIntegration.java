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
    }
}
