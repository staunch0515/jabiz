package com.jabiz.runtime.process.steps;

import com.jabiz.context.ClientCountry;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.geo.ClientAddressContext;
import com.jabiz.runtime.geo.ClientCountries;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Puts the country the request comes from into the process context (decision D74):
 * {@code .step("Where from", LoadClientCountry.into("country"))}, then {@code ctx.get("country", ClientCountry.class)}
 * in a compute step. Looked up in memory in the local address database: no statement, no call, nothing saved, so it
 * belongs before any lock. Unknown ({@link ClientCountry#UNKNOWN}) without a database, for an address it does not
 * list, and for processes not run through the process API (events, jobs, app proxies, sub-processes of those).
 * What unknown means (deny or allow) is the business rule's to say.
 */
@Component
public class LoadClientCountry<C extends ProcessContext> implements StepHandler<LoadClientCountry.Metadata, C> {

    /** @param key the context key the country is put under */
    public record Metadata(String key) {
        public Metadata {
            Objects.requireNonNull(key, "key must not be null");
        }
    }

    private final ClientCountries countries;

    public LoadClientCountry(ClientCountries countries) {
        this.countries = countries;
    }

    /** The request's country, under {@code key}. */
    public static <C extends ProcessContext> StepSpec<Metadata, C> into(String key) {
        return StepSpec.of(LoadClientCountry.class, new Metadata(key));
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return Mono.deferContextual(view -> {
            ClientCountry country = countries.countryOf(ClientAddressContext.of(view));
            ctx.put(metadata.key(), country);
            return Mono.empty();
        });
    }
}
