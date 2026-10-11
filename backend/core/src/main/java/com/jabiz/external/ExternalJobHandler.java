package com.jabiz.external;

/**
 * Runs one attempt of an external job (decision D38 item 3): synchronous, on a virtual thread of the platform's
 * executor, with no database transaction or connection held. Like a blocking step it does not touch the database; it
 * calls the job type's declared services through {@link ExternalJobContext#http} only, and returns the result, which
 * the platform stores and hands to the completion process. Implementations are singleton beans and keep no state.
 *
 * <p>An {@link ExternalJobException#permanent permanent} failure is not retried; any other exception, and running past
 * the timeout, fails the attempt and the job is tried again after its backoff while attempts are left. An attempt may
 * be made again after it reached the other system (an instance stopped before recording it): pass
 * {@link ExternalJobContext#idempotencyKey()} to a provider that deduplicates.
 *
 * @param <I> the job type's input
 * @param <R> the job type's result
 */
@FunctionalInterface
public interface ExternalJobHandler<I, R> {

    ExternalJobResult<R> run(I input, ExternalJobContext ctx);
}
