package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.security.Sensitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A signed-in user's own provider accounts (docs/design/10-security.md section 12; decision D39 item 2; ROADMAP phase
 * 16f): list them, link one through the provider, remove one. Only the user's own, and only providers offered to the
 * sign-in entry of the session (decision D43 item 5). An access token outliving its session acts on nothing here
 * (401, section 15.9).
 *
 * <p>Linking starts only once the caller has proven again who they are: a second factor within
 * {@code jabiz.security.mfa.step-up-max-age}, or the current password ({@value IdentityProcesses#LINK_BEGIN}, which
 * counts a wrong one towards the lock); neither is 422 {@code REAUTHENTICATION_REQUIRED}. The state of a link names
 * the caller and their entry and is accepted by this callback only (the sign-in callback refuses it, and this one a
 * sign-in's). Every failure of the round trip itself is 422 {@code IDENTITY_LINK_FAILED}, never 401: the caller's
 * session is fine, and a 401 would sign them out.
 */
@RestController
class IdentityLinkController {

    static final String BASE = "/api/auth/account/identities";

    private static final Logger log = LoggerFactory.getLogger(IdentityLinkController.class);

    /**
     * One linked provider account.
     *
     * @param id       the link's id (what {@code DELETE} takes)
     * @param label    the provider's label in the caller's language; its id when it is no longer configured
     * @param kind     the provider's kind ({@code generic}, {@code google}), or null when it is no longer configured
     * @param email    the address the provider gave when it was linked by the user or the provisioning, or null
     * @param linkedAt when it was linked
     */
    record IdentityView(String id, String provider, String label, String kind, String email, Instant linkedAt) {}

    /**
     * @param currentPassword the caller's password, needed unless the session passed a second factor recently
     */
    record StartRequest(String provider, @Sensitive String currentPassword) {
        @Override
        public String toString() {
            return "StartRequest[provider=" + provider + ", currentPassword=***]";
        }
    }

    record CallbackRequest(String state, String code, String binder) {
        @Override
        public String toString() {
            return "CallbackRequest[***]";
        }
    }

    /** @param status {@code LINKED}, or {@code ALREADY_LINKED} when it was linked to the caller before */
    record LinkResult(String status, String provider) {}

    private final OidcProviders providers;
    private final OidcClient client;
    private final OidcExchange exchange;
    private final OidcStateStore states;
    private final ProcessExecutor processes;
    private final RefreshTokenStore refreshTokens;
    private final SignUpRateLimits limits;
    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final Reauthentication reauthentication;
    private final ProcessDefinition<IdentityProcesses.BeginInput, IdentityProcesses.BeginOutput, ProcessContext> begin;
    private final ProcessDefinition<IdentityProcesses.LinkInput, IdentityProcesses.LinkOutput, ProcessContext> link;
    private final ProcessDefinition<IdentityProcesses.UnlinkInput, IdentityProcesses.UnlinkOutput, ProcessContext>
        unlink;

    IdentityLinkController(OidcProviders providers, OidcClient client, OidcExchange exchange, OidcStateStore states,
        ProcessExecutor processes, RefreshTokenStore refreshTokens, SignUpRateLimits limits,
        DatasetEntityManager entities, DatasetRegistry datasets,
        StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        Reauthentication reauthentication,
        @Qualifier("secIdentityLinkBeginProcess")
        ProcessDefinition<IdentityProcesses.BeginInput, IdentityProcesses.BeginOutput, ProcessContext> begin,
        ProcessDefinition<IdentityProcesses.LinkInput, IdentityProcesses.LinkOutput, ProcessContext> link,
        ProcessDefinition<IdentityProcesses.UnlinkInput, IdentityProcesses.UnlinkOutput, ProcessContext> unlink) {
        this.providers = providers;
        this.client = client;
        this.exchange = exchange;
        this.states = states;
        this.processes = processes;
        this.refreshTokens = refreshTokens;
        this.limits = limits;
        this.entities = entities;
        this.datasets = datasets;
        this.storages = storages;
        this.poolRef = poolRef;
        this.reauthentication = reauthentication;
        this.begin = begin;
        this.link = link;
        this.unlink = unlink;
    }

    /** The caller's linked provider accounts, by provider. */
    @GetMapping(BASE)
    Mono<List<IdentityView>> mine() {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                String language = context.locale() == null ? "en" : context.locale().getLanguage();
                EntityQuery query = Rbac.all(new QueryPredicate.Eq("userId", user), "provider");
                return entities.query(datasets.findById(SecurityEntities.USER_IDENTITY_DATASET).orElseThrow(),
                        SecurityEntities.SEC_USER_IDENTITY, query).collectList()
                    .zipWith(linkedAt(user))
                    .map(found -> found.getT1().stream()
                        .map(identity -> view(identity, found.getT2().get(String.valueOf(identity.id())), language))
                        .sorted(Comparator.comparing(IdentityView::provider).thenComparing(IdentityView::id))
                        .toList());
            })));
    }

    /**
     * Starts linking an account of {@code provider} (offered to the session's entry; otherwise 404): answers the
     * provider's page and the binder, as a sign-in's start does. Counted per user (429 {@code RATE_LIMITED}).
     */
    @PostMapping(BASE + "/start")
    Mono<OidcController.StartResponse> start(@RequestBody(required = false) StartRequest request,
        ServerHttpRequest http) {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                limits.identityLink(user.toString());
                StartRequest body = request == null ? new StartRequest(null, null) : request;
                String entry = entryOf(context);
                OidcProvider provider = providers.find(body.provider()).filter(p -> p.offers(entry))
                    .orElseThrow(() -> new EntityNotFoundException("No identity provider " + body.provider()
                        + " at entry " + entry));
                return reauthenticated(context, user, body, http)
                    .then(states.start(provider.id(), entry, OidcStateStore.Purpose.LINK, user.toString()))
                    .flatMap(started -> client.authorizationUrl(provider, started, provider.providerRedirectUri(entry))
                        .map(url -> new OidcController.StartResponse(url, started.binder())))
                    .onErrorMap(OidcClient.ProviderException.class, e -> {
                        log.warn("Identity provider {} unavailable: {}", provider.id(), e.getMessage());
                        return linkFailed("Identity provider unavailable");
                    });
            })));
    }

    /**
     * The provider sent the user back: use the state once (a link's, the caller's, at the caller's entry), exchange
     * the code, check the ID token and link the account ({@value IdentityProcesses#LINK_SELF}).
     */
    @PostMapping(BASE + "/callback")
    Mono<LinkResult> callback(@RequestBody(required = false) CallbackRequest request) {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                if (request == null || blank(request.code())) {
                    return Mono.error(linkFailed("Missing code"));
                }
                String entry = entryOf(context);
                String language = context.locale() == null ? "en" : context.locale().getLanguage();
                return states.consume(request.state(), request.binder())
                    .onErrorMap(OidcStateStore.InvalidStateException.class, e -> linkFailed(e.getMessage()))
                    .flatMap(pending -> {
                        if (pending.purpose() != OidcStateStore.Purpose.LINK
                            || !user.toString().equals(pending.linkUserId()) || !entry.equals(pending.entry())) {
                            return Mono.error(linkFailed("The state is not a link of this user at this entry"));
                        }
                        OidcProvider provider = providers.find(pending.providerId()).orElse(null);
                        if (provider == null) {
                            return Mono.error(linkFailed("The provider of the state is no longer configured"));
                        }
                        return exchange.identity(provider, request.code(), pending)
                            .onErrorMap(e -> e instanceof OidcClient.ProviderException
                                || e instanceof IdTokenValidator.InvalidIdTokenException
                                || e instanceof IdTokenValidator.UnknownKeyException
                                || e instanceof IllegalStateException, e -> linkFailed(e.getMessage()))
                            .flatMap(exchanged -> processes.executeAtEntry(link, new IdentityProcesses.LinkInput(
                                user.toString(), provider.id(), exchanged.identity().subject(),
                                exchanged.identity().email(), provider.label(language), exchanged.refreshToken())))
                            // Linked to someone else at the same time.
                            .onErrorMap(IdentityLinkController::raceLost, e -> new BusinessRuleViolationException(
                                new Violation(null, PlatformErrorCodes.IDENTITY_IN_USE, "The provider account was "
                                    + "linked at the same time")))
                            .map(output -> new LinkResult(output.status(), provider.id()));
                    });
            })));
    }

    /** Removes one of the caller's links: 204; 404 for any other id; 422 when it is the last way to sign in. */
    @DeleteMapping(BASE + "/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    Mono<Void> unlink(@PathVariable("id") String id) {
        return RequestContexts.current().flatMap(context -> CurrentSession.open(refreshTokens, context)
            .then(Mono.defer(() -> {
                UUID user = requireUser(context);
                limits.identityLink(user.toString());
                return processes.executeAtEntry(unlink, new IdentityProcesses.UnlinkInput(
                    user.toString(), id)).then();
            })));
    }

    private Mono<Void> reauthenticated(RequestContext context, UUID user, StartRequest body, ServerHttpRequest http) {
        return reauthentication.require(context, user, body.currentPassword(), http, begin);
    }

    /** When each of the user's links was made: the start of its first version. */
    private Mono<Map<String, Instant>> linkedAt(UUID user) {
        return storages.getEngine(poolRef).select("""
                SELECT user_identity_id::text AS id, min(effect_start_time) AS linked_at
                FROM sec_user_identity_version WHERE user_id = :user GROUP BY user_identity_id""",
                Map.of("user", BoundValue.of(user)))
            .collectList()
            .map(rows -> {
                Map<String, Instant> linked = new HashMap<>();
                rows.forEach(row -> linked.put(String.valueOf(row.get("id")), Rows.instant(row.get("linked_at"))));
                return linked;
            });
    }

    private IdentityView view(EntityInstance identity, Instant linkedAt, String language) {
        String id = identity.get("provider");
        OidcProvider provider = providers.find(id).orElse(null);
        return new IdentityView(String.valueOf(identity.id()), id, provider == null ? id : provider.label(language),
            provider == null ? null : provider.kind(), identity.get("email"), linkedAt);
    }

    private static BusinessRuleViolationException linkFailed(String reason) {
        log.info("Linking a provider account refused: {}", reason);
        return new BusinessRuleViolationException(new Violation(null, PlatformErrorCodes.IDENTITY_LINK_FAILED,
            "The provider account could not be linked"));
    }

    private static boolean raceLost(Throwable e) {
        return e instanceof ValidationException v && v.violations().stream()
            .anyMatch(violation -> PlatformErrorCodes.UNIQUE_VIOLATION.equals(violation.ruleCode()));
    }

    private static String entryOf(RequestContext context) {
        return context.entry() == null || context.entry().isBlank() ? RequestContext.DEFAULT_ENTRY : context.entry();
    }

    private static UUID requireUser(RequestContext context) {
        return ActingUser.userId(context).orElseThrow(() -> new PermissionDeniedException(SecurityPermissions.ACCOUNT,
            "Only users of the platform link provider accounts"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
