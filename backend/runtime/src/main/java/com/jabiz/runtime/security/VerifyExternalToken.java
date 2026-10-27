package com.jabiz.runtime.security;

import com.jabiz.process.ComputeStep;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.StepSpec;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.ExternalTokenRules;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Verifies a token another platform signed (decision D59; docs/design/10-security.md section 15.12) inside a process and
 * requires it to be of the operator: the identity it names ({@code ext:<provider>}, {@code <tenant>/<sub>}) is linked to
 * the operator's user. The context then holds the {@link Verified} token: its tenant, subject and the token itself, for
 * a blocking step that hands it on (such as exchanging it for an access token at the platform that signed it). It does
 * not use the token up: the platform that signed it is the one to judge reuse. Anything else is 403, whatever failed.
 * The step names carry the target key, so that a process may verify more than one token.
 * <pre>{@code
 * .steps(VerifyExternalToken.of("shop", ctx -> input(ctx).sessionToken(), "verified"))
 * }</pre>
 * The input component carrying the token is {@code @Sensitive}.
 */
@Component
public class VerifyExternalToken<C extends ProcessContext>
    implements ComputeStep<VerifyExternalToken.Metadata<C>, C> {

    /** The verified token of the operator. {@code token} is the token as given, for handing on; never log it. */
    public record Verified(String provider, String tenant, String subject, String token) {
        @Override
        public String toString() {
            return "Verified[provider=" + provider + ", tenant=" + tenant + ", subject=" + subject + ", token=***]";
        }
    }

    /**
     * @param provider  the provider's name ({@code jabiz.security.external-tokens.<name>})
     * @param token     the token as given
     * @param targetKey the context key receiving the {@link Verified} token
     */
    public record Metadata<C>(String provider, Function<C, String> token, String targetKey) {
        public Metadata {
            Objects.requireNonNull(provider, "provider must not be null");
            Objects.requireNonNull(token, "token must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    /** The steps: verify the token, find whom its identity is linked to, require the operator. */
    public static <I, O, C extends ProcessContext> Consumer<ProcessDefinitionBuilder<I, O, C>> of(String provider,
        Function<C, String> token, String targetKey) {
        Metadata<C> metadata = new Metadata<>(provider, token, targetKey);
        String links = targetKey + ".links";
        return pb -> pb
            .step("Verify the external token " + targetKey, StepSpec.of(VerifyExternalToken.class, metadata))
            .step("Find whom the external identity " + targetKey + " is linked to", QueryEntities.<C>of(
                SecurityEntities.USER_IDENTITY_DATASET, ctx -> Rbac.all(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("provider", ExternalTokenProvider.IDENTITY_PREFIX + provider),
                    new QueryPredicate.Eq("subject", identitySubject(ctx.get(targetKey, Verified.class))))),
                    "provider"), links))
            .compute("Require the operator's external identity " + targetKey, (none, ctx) -> requireOperator(ctx,
                targetKey, links));
    }

    static String identitySubject(Verified verified) {
        return ExternalTokenProvider.identitySubject(verified.tenant(), verified.subject());
    }

    @SuppressWarnings("unchecked")
    static void requireOperator(ProcessContext ctx, String targetKey, String links) {
        Verified verified = ctx.get(targetKey, Verified.class);
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(links);
        String actor = ctx.request() == null ? null : ctx.request().actorId();
        boolean own = actor != null && found != null && found.size() == 1
            && actor.equals(String.valueOf(found.getFirst().<Object>get("userId")));
        if (!own) {
            throw refused(verified.provider(), "The external identity is not linked to the operator");
        }
    }

    private static PermissionDeniedException refused(String provider, String message) {
        return new PermissionDeniedException("external-token:" + provider, message);
    }

    private final ExternalTokenProviders providers;
    private final ExternalTokens tokens;

    public VerifyExternalToken(ExternalTokenProviders providers, ExternalTokens tokens) {
        this.providers = providers;
        this.tokens = tokens;
    }

    @Override
    public void compute(Metadata<C> metadata, C ctx) {
        ExternalTokenProvider provider = providers.find(metadata.provider())
            .orElseThrow(() -> refused(metadata.provider(), "The external token provider is unavailable"));
        String token = metadata.token().apply(ctx);
        ExternalTokenRules.Verified verified;
        try {
            verified = tokens.verify(provider, token);
        } catch (ExternalTokenRules.RefusedException e) {
            throw refused(provider.name(), e.getMessage());
        }
        ctx.put(metadata.targetKey(), new Verified(provider.name(), verified.tenant(), verified.subject(), token));
    }
}
