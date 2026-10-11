package com.jabiz.external;

/**
 * The declared {@link ExternalService}s, for blocking steps (decision D38). Injected as a bean; business code makes no
 * network call any other way (ArchUnit).
 */
public interface ExternalServices {

    /**
     * The client of a declared service.
     *
     * @throws IllegalArgumentException when no service of that name is declared
     */
    ExternalHttp http(String service);

    /**
     * A secret of a declared service, read from its environment variable at startup. Only within a blocking step;
     * never log it or put it in the process context.
     *
     * @throws IllegalArgumentException when the service or the secret is not declared
     * @throws IllegalStateException    when the environment did not provide it, or outside a blocking step
     */
    String secret(String service, String name);

    /**
     * {@code jabiz-<processSeqId>-<suffix>}: the same for every attempt of an after-commit step of the same process,
     * so a provider that deduplicates by it performs a retried call once.
     *
     * @param suffix tells the calls of one process apart: letters, digits, {@code . _ : -}
     * @throws IllegalStateException outside a blocking step
     */
    String idempotencyKey(String suffix);
}
