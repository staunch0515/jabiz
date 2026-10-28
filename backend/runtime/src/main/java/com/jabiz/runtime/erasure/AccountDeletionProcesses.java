package com.jabiz.runtime.erasure;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailRecipient;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.mail.MailMessageParams;
import com.jabiz.runtime.mail.MailTokens;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.security.LoadUserByEmail;
import com.jabiz.runtime.security.Rbac;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.SecurityPermissions;
import com.jabiz.runtime.security.SignInEntries;
import com.jabiz.runtime.security.protect.ProtectedChangeEntities;
import com.jabiz.runtime.security.protect.ProtectedGrants;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.security.Sensitive;
import com.jabiz.security.erasure.ErasureNotice;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Requests to delete an account (docs/design/10-security.md section 17.5; decision D62 item 2). A request waits out the
 * cooling-off period ({@code jabiz.security.account-deletion.cooling-off}, 14 days), during which the account works as
 * before and the request can be withdrawn; the daily job {@value #JOB} then runs {@code ACCOUNT_ERASE} for it.
 * <ul>
 *   <li>{@value #REQUEST} / {@value #CANCEL}: the signed-in user, at an entry with {@code account-deletion}, after
 *       proving again who they are (the controller checks both).</li>
 *   <li>{@value #MAIL} / {@value #CONFIRM}: without the application, from a mailed link. Asking answers alike for every
 *       address; only an enabled user at the verified address gets the link. Using it makes the request.</li>
 *   <li>{@value #SCHEDULE} / {@value #UNSCHEDULE}: an administrator who may erase accounts, for a user.</li>
 *   <li>{@value #EXECUTE}: the job's item, the system only.</li>
 * </ul>
 * Holders of a protected permission (decision D48) are refused by every path: their protected roles go first, through
 * the four-eyes change. A request by the user lists the applications' notices ({@link ErasureNotices}) it read; one it
 * did not is refused with each notice as a violation. A user's requests are made, withdrawn and carried out one at a
 * time ({@value #LOCK}).
 */
@Configuration
public class AccountDeletionProcesses {

    public static final String REQUEST = "SEC_ACCOUNT_DELETION_REQUEST";
    public static final String CANCEL = "SEC_ACCOUNT_DELETION_CANCEL";
    public static final String MAIL = "SPONSOR_ACCOUNT_DELETION_MAIL";
    public static final String CONFIRM = "SPONSOR_ACCOUNT_DELETION_CONFIRM";
    public static final String SCHEDULE = "SEC_USER_DELETION_SCHEDULE";
    public static final String UNSCHEDULE = "SEC_USER_DELETION_CANCEL";
    public static final String EXECUTE = "SEC_ACCOUNT_DELETION_EXECUTE";

    /** The daily job that carries out the due requests. */
    public static final String JOB = "jabiz.account-deletion";
    static final String DUE = "jabiz.account_deletion_due";

    /** The mailed link that makes a request without the application: the entry its parameter. */
    public static final String LINK_TEMPLATE = "jabiz.auth.account-deletion";
    /** Tells the user a request was made, and when it is carried out: the date (UTC) its parameter. */
    public static final String REQUESTED_TEMPLATE = "jabiz.auth.account-deletion-requested";
    /** The token of the link: its purpose and placeholder, in the platform's namespace. */
    public static final String TOKEN = "jabiz.accountDeletion";
    /** The parameter naming the sign-in entry of the link. */
    public static final String ENTRY = "entry";
    /** The parameter with the day the account is deleted (UTC, {@code yyyy-MM-dd}). */
    public static final String DUE_DATE = "due";

    /** A user id that is nobody's: what a link that was not good stands for. */
    static final String NOBODY = "00000000-0000-0000-0000-000000000000";

    /** Name of the lock that serializes a user's requests (the platform's own namespace). */
    static final String LOCK = "jabiz.platform.account-deletion:";

    /** Through which a request came. */
    public enum Channel { APP, WEB, ADMIN }

    /**
     * @param userId              the caller
     * @param acknowledgedNotices the codes of the notices the user read ({@link ErasureNotices})
     */
    public record RequestInput(@NotNull String userId, List<String> acknowledgedNotices) {}

    /** @param userId the caller, or (an administrator's) the user */
    public record UserInput(@NotNull String userId) {}

    /**
     * @param entry the sign-in entry whose pages the link opens; it must let users ask for the deletion
     * @param email the account's address
     */
    public record MailInput(String entry, String email) {}

    /**
     * @param token               the mailed link's token
     * @param acknowledgedNotices the codes of the notices the user read
     */
    public record ConfirmInput(@Sensitive String token, List<String> acknowledgedNotices) {
        @Override
        public String toString() {
            return "ConfirmInput[token=***, acknowledgedNotices=" + acknowledgedNotices + "]";
        }
    }

    /** @param requestId the request to carry out */
    public record ExecuteInput(@NotNull UUID requestId) {}

    /** @param dueTime when the account is deleted, unless the request is withdrawn before */
    public record DeletionRequested(String requestId, Instant dueTime) {}

    public record DeletionCancelled(String requestId) {}

    /** @param erasureId the erasure, or null when the request was not (or no longer) due */
    public record ExecuteOutput(String requestId, String erasureId) {}

    /** Nothing to tell: the answer is alike for every address. */
    public record Done() {}

    // Context keys.
    static final String USER_ID = "user_id";
    static final String USER = "user";
    static final String USERS = "users";
    static final String ACKNOWLEDGED = "acknowledged";
    static final String ENTRY_NAME = "entry";
    static final String EMAIL = "email";
    static final String TOKEN_VALUE = "token";
    static final String CONSUMED = "consumed";
    static final String MAIL_PARAMS = "mail_params";
    static final String TO_USER = "to_user";
    static final String REQUESTED = "requested";
    static final String CANCELLED = "cancelled";
    static final String REQUEST_ID = "request_id";
    static final String PENDING = "pending";
    static final String ERASED = "erased";

    // ---- the user's own -------------------------------------------------------------------------------------------

    static ProcessDefinition<RequestInput, DeletionRequested, ProcessContext> request(MailTemplate requested) {
        return ProcessDefinition.define(REQUEST, 1, RequestInput.class, DeletionRequested.class, ProcessContext.class,
            pb -> pb
                .description("Asks for the deletion of the signed-in user's own account, after the cooling-off"
                    + " period.")
                .permissions(SecurityPermissions.ACCOUNT)
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(USER_ID, input.userId());
                    ctx.put(ACKNOWLEDGED, input.acknowledgedNotices() == null ? List.of()
                        : List.copyOf(input.acknowledgedNotices()));
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(REQUESTED, DeletionRequested.class))
                .compute("Check the caller", (metadata, ctx) -> checkOwn(ctx))
                .step("One request at a time", HoldLock.<ProcessContext>exclusive(ctx -> LOCK + ctx.get(USER_ID)))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Record the request", AccountDeletionStep.request(Channel.APP))
                .step("Tell the user", requestedMail(requested)));
    }

    static ProcessDefinition<UserInput, DeletionCancelled, ProcessContext> cancel() {
        return ProcessDefinition.define(CANCEL, 1, UserInput.class, DeletionCancelled.class, ProcessContext.class, pb -> pb
            .description("Withdraws the signed-in user's pending request to delete the account.")
            .permissions(SecurityPermissions.ACCOUNT)
            .entryOnly()
            .contextFactory((start, input) -> userContext(start, input))
            .outputMapper(ctx -> ctx.get(CANCELLED, DeletionCancelled.class))
            .compute("Check the caller", (metadata, ctx) -> checkOwn(ctx))
            .step("One request at a time", HoldLock.<ProcessContext>exclusive(ctx -> LOCK + ctx.get(USER_ID)))
            .step("Withdraw the request", AccountDeletionStep.cancel()));
    }

    // ---- by a mailed link ------------------------------------------------------------------------------------------

    static ProcessDefinition<MailInput, Done, ProcessContext> mail(MailTemplate link, SignInEntries entries) {
        return ProcessDefinition.define(MAIL, 1, MailInput.class, Done.class, ProcessContext.class, pb -> pb
            .description("Mails a link to ask for the deletion of the account to the address, if it may have one.")
            .permissions(SecurityPermissions.SIGN_UP)
            .entryOnly()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(ENTRY_NAME, input.entry());
                ctx.put(EMAIL, input.email() == null ? null : input.email().strip());
                return ctx;
            })
            .outputMapper(ctx -> new Done())
            .step("Find the address", LoadUserByEmail.into(ctx -> (String) ctx.get(EMAIL), USERS))
            .compute("Decide", (metadata, ctx) -> {
                if (entries.find((String) ctx.get(ENTRY_NAME)).filter(SignInEntries.Entry::accountDeletion).isEmpty()) {
                    return;
                }
                // Only the user who proved the address: anyone can type someone else's.
                EntityInstance user = LoadUserByEmail.only(ctx, USERS);
                if (user != null && Rbac.enabled(user) && user.get("erasedAt") == null
                    && SecurityEntities.emailVerified(user)) {
                    ctx.put(TO_USER, user.id());
                }
            })
            .step("Send the link", SendMail.<ProcessContext>when(ctx -> ctx.get(TO_USER) != null, link,
                ctx -> MailRecipient.user(ctx.get(TO_USER)),
                Map.of(ENTRY, ctx -> ctx.get(ENTRY_NAME)))));
    }

    static ProcessDefinition<ConfirmInput, DeletionRequested, ProcessContext> confirm(MailTemplate requested,
        SignInEntries entries) {
        return ProcessDefinition.define(CONFIRM, 1, ConfirmInput.class, DeletionRequested.class, ProcessContext.class,
            pb -> pb
                .description("Uses a mailed link: asks for the deletion of the account it was sent for.")
                .permissions(SecurityPermissions.SIGN_UP)
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(TOKEN_VALUE, input.token());
                    ctx.put(ACKNOWLEDGED, input.acknowledgedNotices() == null ? List.of()
                        : List.copyOf(input.acknowledgedNotices()));
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(REQUESTED, DeletionRequested.class))
                // A refusal (notices not acknowledged, say) rolls the use back: the link works again.
                .step("Use the link", MailTokens.<ProcessContext>consume(TOKEN, ctx -> (String) ctx.get(TOKEN_VALUE),
                    CONSUMED))
                .step("Read the mail", MailMessageParams.<ProcessContext>of(ctx -> consumed(ctx) == null ? null
                    : consumed(ctx).messageId(), MAIL_PARAMS))
                // The steps run on after a refusal: a link that was not good looks for nobody.
                .compute("Find the user", (metadata, ctx) -> ctx.put(USER_ID, consumed(ctx) == null
                    || consumed(ctx).userId() == null ? NOBODY : consumed(ctx).userId()))
                .step("One request at a time", HoldLock.<ProcessContext>exclusive(ctx -> LOCK + ctx.get(USER_ID)))
                .step("Load the user", LoadEntity.optional(SecurityEntities.USER_DATASET, USER_ID, USER))
                .compute("Check the link", (metadata, ctx) -> checkLink(ctx, entries))
                .step("Record the request", AccountDeletionStep.request(Channel.WEB))
                .step("Tell the user", requestedMail(requested)));
    }

    // ---- by an administrator ---------------------------------------------------------------------------------------

    static ProcessDefinition<UserInput, DeletionRequested, ProcessContext> schedule(MailTemplate requested) {
        return ProcessDefinition.define(SCHEDULE, 1, UserInput.class, DeletionRequested.class, ProcessContext.class,
            pb -> pb
                .description("Schedules the deletion of a user's account, after the cooling-off period.")
                .permissions(ErasurePermissions.ERASE)
                .actsOn(SecurityEntities.USER, "userId")
                .contextFactory((start, input) -> userContext(start, input))
                .outputMapper(ctx -> ctx.get(REQUESTED, DeletionRequested.class))
                .step("One request at a time", HoldLock.<ProcessContext>exclusive(ctx -> LOCK + ctx.get(USER_ID)))
                .step("Load the user", LoadEntity.by(SecurityEntities.USER_DATASET, USER_ID, USER))
                .step("Record the request", AccountDeletionStep.request(Channel.ADMIN))
                .step("Tell the user", requestedMail(requested)));
    }

    static ProcessDefinition<UserInput, DeletionCancelled, ProcessContext> unschedule() {
        return ProcessDefinition.define(UNSCHEDULE, 1, UserInput.class, DeletionCancelled.class, ProcessContext.class,
            pb -> pb
                .description("Withdraws the pending request to delete a user's account.")
                .permissions(ErasurePermissions.ERASE)
                .actsOn(SecurityEntities.USER, "userId")
                .contextFactory((start, input) -> userContext(start, input))
                .outputMapper(ctx -> ctx.get(CANCELLED, DeletionCancelled.class))
                .step("One request at a time", HoldLock.<ProcessContext>exclusive(ctx -> LOCK + ctx.get(USER_ID)))
                .step("Withdraw the request", AccountDeletionStep.cancel()));
    }

    // ---- carrying a request out ------------------------------------------------------------------------------------

    static ProcessDefinition<ExecuteInput, ExecuteOutput, ProcessContext> execute() {
        return ProcessDefinition.define(EXECUTE, 1, ExecuteInput.class, ExecuteOutput.class, ProcessContext.class,
            pb -> pb
                .description("Carries out a due request to delete an account: erases it.")
                .permissions(ErasurePermissions.ERASE)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(REQUEST_ID, input.requestId().toString());
                    return ctx;
                })
                .outputMapper(ctx -> new ExecuteOutput((String) ctx.get(REQUEST_ID), erasureOf(ctx)))
                .step("Find the user", AccountDeletionStep.load())
                .step("One request at a time", HoldLock.<ProcessContext>exclusive(ctx -> LOCK + ctx.get(USER_ID)))
                // Again under the lock: a withdrawal may have committed in between.
                .step("Load the request", AccountDeletionStep.load())
                // A protected role granted during the cooling-off period stops the erasure: the request stays pending
                // (the item fails, every day, until an administrator withdraws it or the role goes).
                .step("Check the user's permissions", AccountDeletionStep.guard())
                .step("Erase the account", CallProcess.<ProcessContext>when(ctx -> ctx.get(PENDING) != null
                        && ctx.get(ERASED) == null && !ctx.hasViolations(), AccountErasure.NAME, 1,
                    ctx -> new AccountErasure.EraseInput(UUID.fromString((String) ctx.get(USER_ID)),
                        pending(ctx).requestedBy()), ERASED))
                .step("End the request", AccountDeletionStep.end()));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static ProcessContext userContext(com.jabiz.process.ProcessStart start, UserInput input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(USER_ID, input.userId());
        return ctx;
    }

    /** Only the caller's own account; another user's is refused whatever the caller holds. */
    static void checkOwn(ProcessContext ctx) {
        if (!String.valueOf(ctx.get(USER_ID)).equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(SecurityPermissions.ACCOUNT, "Only the user asks for the deletion of"
                + " their own account");
        }
    }

    private static MailTokens.Consumed consumed(ProcessContext ctx) {
        return ctx.get(CONSUMED, MailTokens.Consumed.class);
    }

    /**
     * The link stands for an enabled user still at the verified address it was sent to, at an entry that still lets
     * users ask; anything else is 422 {@code TOKEN_INVALID}, as for any mailed link.
     */
    static void checkLink(ProcessContext ctx, SignInEntries entries) {
        MailTokens.Consumed consumed = consumed(ctx);
        if (consumed == null) {
            return;
        }
        EntityInstance user = ctx.get(USER, EntityInstance.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = ctx.get(MAIL_PARAMS, Map.class);
        Object entry = params == null ? null : params.get(ENTRY);
        boolean entryOpen = entry != null && entries.find(entry.toString())
            .filter(SignInEntries.Entry::accountDeletion).isPresent();
        if (user == null || !entryOpen || !Rbac.enabled(user) || user.get("erasedAt") != null
            || !SecurityEntities.emailVerified(user) || !com.jabiz.security.EmailVerification.verified(
                user.get("email"), consumed.address())) {
            ctx.reject(new Violation(null, PlatformErrorCodes.TOKEN_INVALID, "Invalid token (user, address or entry"
                + " changed)"));
        }
    }

    /** Tells the user about the request, when the account has an address to tell. */
    private static StepSpec<SendMail.Metadata<ProcessContext>, ProcessContext> requestedMail(MailTemplate requested) {
        return SendMail.<ProcessContext>when(ctx -> ctx.get(REQUESTED) != null && !ctx.hasViolations()
                && ctx.get(USER, EntityInstance.class).get("email") != null, requested,
            ctx -> MailRecipient.user(ctx.get(USER, EntityInstance.class).id()),
            Map.of(DUE_DATE, ctx -> ctx.get(REQUESTED, DeletionRequested.class).dueTime().atOffset(ZoneOffset.UTC)
                .toLocalDate().toString()));
    }

    private static AccountDeletions.Request pending(ProcessContext ctx) {
        return ctx.get(PENDING, AccountDeletions.Request.class);
    }

    private static String erasureOf(ProcessContext ctx) {
        Object erased = ctx.get(ERASED);
        if (erased instanceof AccountErasure.EraseOutput output) {
            return output.erasureId();
        }
        return erased == null ? null : erased.toString();
    }

    /**
     * The step of the requests: records one, withdraws one, finds one to carry out, or ends it. A platform I/O step
     * (it reads the protected grants and the requests, and writes the append-only rows itself).
     */
    @Component
    public static class AccountDeletionStep implements StepHandler<AccountDeletionStep.Metadata, ProcessContext> {

        enum Mode { REQUEST, CANCEL, LOAD, GUARD, END }

        public record Metadata(Mode mode, Channel channel) {
            public Metadata {
                Objects.requireNonNull(mode, "mode must not be null");
            }
        }

        static StepSpec<Metadata, ProcessContext> request(Channel channel) {
            return StepSpec.of(AccountDeletionStep.class, new Metadata(Mode.REQUEST, channel));
        }

        static StepSpec<Metadata, ProcessContext> cancel() {
            return StepSpec.of(AccountDeletionStep.class, new Metadata(Mode.CANCEL, null));
        }

        static StepSpec<Metadata, ProcessContext> load() {
            return StepSpec.of(AccountDeletionStep.class, new Metadata(Mode.LOAD, null));
        }

        static StepSpec<Metadata, ProcessContext> guard() {
            return StepSpec.of(AccountDeletionStep.class, new Metadata(Mode.GUARD, null));
        }

        static StepSpec<Metadata, ProcessContext> end() {
            return StepSpec.of(AccountDeletionStep.class, new Metadata(Mode.END, null));
        }

        private final AccountDeletions deletions;
        private final ProtectedGrants grants;
        private final ErasureNotices notices;
        private final EntityIdGenerator ids;
        private final EntityDefinitionRegistry entities;
        private final Duration coolingOff;

        public AccountDeletionStep(AccountDeletions deletions, ProtectedGrants grants, ErasureNotices notices,
            EntityIdGenerator ids, EntityDefinitionRegistry entities,
            @Value("${jabiz.security.account-deletion.cooling-off:P14D}") Duration coolingOff) {
            this.deletions = deletions;
            this.grants = grants;
            this.notices = notices;
            this.ids = ids;
            this.entities = entities;
            this.coolingOff = coolingOff;
        }

        @Override
        public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
            return Mono.defer(() -> {
                if (ctx.hasViolations()) {
                    return Mono.empty();
                }
                return switch (metadata.mode()) {
                    case REQUEST -> request(ctx, metadata.channel());
                    case CANCEL -> cancel(ctx);
                    case LOAD -> load(ctx);
                    case GUARD -> guard(ctx);
                    case END -> end(ctx);
                };
            });
        }

        /** A due request of a user who holds a protected permission now (or is to): refused, kept pending. */
        private Mono<Void> guard(ProcessContext ctx) {
            if (pending(ctx) == null || ctx.get(ERASED) != null) {
                return Mono.empty();
            }
            return protectedHoldings(ctx, pending(ctx).userId()).doOnNext(held -> {
                if (!held.isEmpty()) {
                    ctx.reject(new Violation(null, PlatformErrorCodes.ACCOUNT_DELETION_PROTECTED, "The user holds"
                        + " protected permissions " + held + " since the request was made"));
                }
            }).then();
        }

        /** The protected permissions the user holds, now or by a change scheduled or pending (decision D48). */
        private Mono<java.util.Set<String>> protectedHoldings(ProcessContext ctx, UUID userId) {
            return grants.assess(new ProtectedGrants.Draft(ProtectedChangeEntities.PASSWORD, userId, null, null),
                    ctx.request().actorId(), ctx.processSeqId(), ctx.opTime())
                .map(ProtectedGrants.Assessment::gained);
        }

        private Mono<Void> request(ProcessContext ctx, Channel channel) {
            EntityInstance user = ctx.get(USER, EntityInstance.class);
            if (user.get("erasedAt") != null) {
                ctx.reject(new Violation(null, PlatformErrorCodes.ACCOUNT_ERASED, "The account was erased already"));
                return Mono.empty();
            }
            UUID userId = UUID.fromString(String.valueOf(user.id()));
            // The holdings now and those scheduled, as for a credential reset (decision D48).
            return protectedHoldings(ctx, userId)
                .flatMap(held -> {
                    if (!held.isEmpty()) {
                        ctx.reject(new Violation(null, PlatformErrorCodes.ACCOUNT_DELETION_PROTECTED, "The user holds"
                            + " protected permissions " + held));
                        return Mono.empty();
                    }
                    return deletions.pending(userId).flatMap(pending -> {
                        if (pending.isPresent()) {
                            ctx.reject(new Violation(null, PlatformErrorCodes.ACCOUNT_DELETION_PENDING,
                                "A request is pending, due " + pending.get().dueTime()));
                            return Mono.empty();
                        }
                        // An administrator decides without the user's notices; the user reads them first.
                        Mono<List<ErasureNotice>> unread = channel == Channel.ADMIN ? Mono.just(List.of())
                            : notices.of(userId.toString()).map(all -> ErasureNotices.unacknowledged(all,
                                acknowledged(ctx)));
                        return unread.flatMap(missing -> {
                            if (!missing.isEmpty()) {
                                ctx.reject(new Violation("acknowledgedNotices",
                                    PlatformErrorCodes.ACCOUNT_DELETION_NOTICES, "Notices not acknowledged"));
                                missing.forEach(notice -> ctx.reject(ErasureNotices.violation(notice)));
                                return Mono.empty();
                            }
                            UUID requestId = UUID.fromString(String.valueOf(ids.next(
                                entities.getOrThrow(AccountDeletionEntities.DELETION))));
                            AccountDeletions.Request request = new AccountDeletions.Request(requestId, userId,
                                ctx.opTime(), ctx.opTime().plus(coolingOff), channel.name(),
                                ctx.request().actorId());
                            return deletions.insert(request, ctx.processSeqId()).then(Mono.fromRunnable(() ->
                                ctx.put(REQUESTED, new DeletionRequested(requestId.toString(), request.dueTime()))));
                        });
                    });
                });
        }

        private Mono<Void> cancel(ProcessContext ctx) {
            UUID userId = UUID.fromString(String.valueOf(ctx.get(USER_ID)));
            return deletions.pending(userId).flatMap(pending -> {
                if (pending.isEmpty()) {
                    ctx.reject(new Violation(null, PlatformErrorCodes.ACCOUNT_DELETION_NONE, "No request is pending"));
                    return Mono.empty();
                }
                UUID requestId = pending.get().requestId();
                return deletions.end(requestId, AccountDeletions.Outcome.CANCELLED, null, ctx.request().actorId(),
                        ctx.opTime(), ctx.processSeqId())
                    .then(Mono.fromRunnable(() -> ctx.put(CANCELLED, new DeletionCancelled(requestId.toString()))));
            });
        }

        /**
         * The request, when it is pending and due ({@link #PENDING}); its user ({@link #USER_ID}); and, when the user
         * was erased meanwhile (by another request or an administrator), that erasure ({@link #ERASED}).
         */
        private Mono<Void> load(ProcessContext ctx) {
            UUID requestId = UUID.fromString((String) ctx.get(REQUEST_ID));
            ctx.put(PENDING, null);
            return deletions.request(requestId).flatMap(found -> {
                if (found.isEmpty()) {
                    return Mono.empty();
                }
                AccountDeletions.Request request = found.get().request();
                ctx.put(USER_ID, request.userId().toString());
                if (found.get().ended() || request.dueTime().isAfter(ctx.opTime())) {
                    return Mono.empty();
                }
                ctx.put(PENDING, request);
                return deletions.erasureOf(request.userId()).doOnNext(erasure -> ctx.put(ERASED, erasure)).then();
            });
        }

        private Mono<Void> end(ProcessContext ctx) {
            AccountDeletions.Request request = pending(ctx);
            String erasureId = erasureOf(ctx);
            if (request == null || erasureId == null) {
                return Mono.empty();
            }
            return deletions.end(request.requestId(), AccountDeletions.Outcome.ERASED, UUID.fromString(erasureId),
                ctx.request().actorId(), ctx.opTime(), ctx.processSeqId());
        }

        @SuppressWarnings("unchecked")
        private static List<String> acknowledged(ProcessContext ctx) {
            Object value = ctx.get(ACKNOWLEDGED);
            return value == null ? List.of() : (List<String>) value;
        }
    }

    /** The requests and their ends, read and written through the engine of the running transaction. */
    @Component
    public static class AccountDeletions {

        public enum Outcome { CANCELLED, ERASED }

        public record Request(UUID requestId, UUID userId, Instant requestedTime, Instant dueTime, String channel,
            String requestedBy) {}

        public record Found(Request request, boolean ended) {}

        private static final String COLUMNS = "d.request_id, d.user_id, d.requested_time, d.due_time, d.channel,"
            + " d.requested_by";

        private final StorageAdapterRegistry storages;
        private final String poolRef;

        public AccountDeletions(StorageAdapterRegistry storages,
            @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
            this.storages = storages;
            this.poolRef = poolRef;
        }

        private StorageEngine engine() {
            return storages.getEngine(poolRef);
        }

        /** The user's pending request, if any. */
        public Mono<Optional<Request>> pending(UUID userId) {
            return engine().select("SELECT " + COLUMNS + " FROM sys_account_deletion d WHERE d.user_id = :user"
                    + " AND NOT EXISTS (SELECT 1 FROM sys_account_deletion_end e WHERE e.request_id = d.request_id)"
                    + " ORDER BY d.requested_time DESC LIMIT 1", Map.of("user", BoundValue.of(userId)))
                .next()
                .map(row -> Optional.of(request(row)))
                .defaultIfEmpty(Optional.empty());
        }

        /** A request, and whether it ended. */
        public Mono<Optional<Found>> request(UUID requestId) {
            return engine().select("SELECT " + COLUMNS + ", EXISTS (SELECT 1 FROM sys_account_deletion_end e"
                    + " WHERE e.request_id = d.request_id) AS ended FROM sys_account_deletion d"
                    + " WHERE d.request_id = :id", Map.of("id", BoundValue.of(requestId)))
                .next()
                .map(row -> Optional.of(new Found(request(row), Boolean.TRUE.equals(row.get("ended")))))
                .defaultIfEmpty(Optional.empty());
        }

        /** The latest erasure of the user, if the account was erased. */
        public Mono<String> erasureOf(UUID userId) {
            return engine().select("SELECT erasure_id FROM sys_erasure WHERE user_id = :user"
                    + " ORDER BY erased_time DESC LIMIT 1", Map.of("user", BoundValue.of(userId)))
                .next()
                .map(row -> String.valueOf(row.get("erasure_id")));
        }

        Mono<Void> insert(Request request, long processSeqId) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("request_id", request.requestId());
            row.put("user_id", request.userId());
            row.put("requested_time", request.requestedTime());
            row.put("due_time", request.dueTime());
            row.put("channel", request.channel());
            row.put("requested_by", request.requestedBy());
            row.put("process_seq_id", processSeqId);
            return engine().insert("sys_account_deletion", row);
        }

        Mono<Void> end(UUID requestId, Outcome outcome, UUID erasureId, String endedBy, Instant time,
            long processSeqId) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("request_id", requestId);
            row.put("ended_time", time);
            row.put("outcome", outcome.name());
            row.put("erasure_id", erasureId);
            row.put("ended_by", endedBy);
            row.put("process_seq_id", processSeqId);
            return engine().insert("sys_account_deletion_end", row);
        }

        private static Request request(Map<String, Object> row) {
            return new Request(Rows.uuid(row.get("request_id")), Rows.uuid(row.get("user_id")),
                Rows.instant(row.get("requested_time")), Rows.instant(row.get("due_time")),
                Rows.string(row.get("channel")), Rows.string(row.get("requested_by")));
        }
    }

    // ---- templates, processes and the job --------------------------------------------------------------------------

    /** The link: one token, valid {@code jabiz.security.account-deletion.link-ttl} (1 hour); the entry. */
    static MailTemplate linkTemplate(Duration validity) {
        return MailTemplate.define(LINK_TEMPLATE, t -> t.category(MailCategory.TRANSACTIONAL).param(ENTRY)
            .token(TOKEN, validity));
    }

    /** Tells the user a deletion was requested, and the day it happens: no token. */
    public static final MailTemplate REQUESTED_MAIL = MailTemplate.define(REQUESTED_TEMPLATE,
        t -> t.category(MailCategory.TRANSACTIONAL).param(DUE_DATE));

    @Bean
    MailTemplate authAccountDeletionMail(
        @Value("${jabiz.security.account-deletion.link-ttl:PT1H}") Duration validity) {
        return linkTemplate(validity);
    }

    @Bean
    MailTemplate authAccountDeletionRequestedMail() {
        return REQUESTED_MAIL;
    }

    @Bean
    ProcessDefinition<RequestInput, DeletionRequested, ProcessContext> secAccountDeletionRequestProcess() {
        return request(REQUESTED_MAIL);
    }

    @Bean
    ProcessDefinition<UserInput, DeletionCancelled, ProcessContext> secAccountDeletionCancelProcess() {
        return cancel();
    }

    @Bean
    ProcessDefinition<MailInput, Done, ProcessContext> sponsorAccountDeletionMailProcess(
        @Qualifier("authAccountDeletionMail") MailTemplate link, SignInEntries entries) {
        return mail(link, entries);
    }

    @Bean
    ProcessDefinition<ConfirmInput, DeletionRequested, ProcessContext> sponsorAccountDeletionConfirmProcess(
        SignInEntries entries) {
        return confirm(REQUESTED_MAIL, entries);
    }

    @Bean
    ProcessDefinition<UserInput, DeletionRequested, ProcessContext> secUserDeletionScheduleProcess() {
        return schedule(REQUESTED_MAIL);
    }

    @Bean
    ProcessDefinition<UserInput, DeletionCancelled, ProcessContext> secUserDeletionCancelProcess() {
        return unschedule();
    }

    @Bean
    ProcessDefinition<ExecuteInput, ExecuteOutput, ProcessContext> secAccountDeletionExecuteProcess() {
        return execute();
    }

    /** Every day (03:41 UTC unless {@code jabiz.security.account-deletion.cron}): the requests due by then. */
    @Bean
    JobDefinition<ExecuteInput> accountDeletionJob(
        @Value("${jabiz.security.account-deletion.cron:0 41 3 * * *}") String cron,
        @Qualifier("secAccountDeletionExecuteProcess")
        ProcessDefinition<ExecuteInput, ExecuteOutput, ProcessContext> execute) {
        return JobDefinition.forEach(JOB, cron, ZoneOffset.UTC,
            JobItems.template(DUE, scheduledTime -> Map.of("dueBy", scheduledTime), "requestId"),
            execute, (scheduledTime, row) -> new ExecuteInput(Rows.uuid(row.get("requestId"))));
    }
}
