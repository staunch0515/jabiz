package com.jabiz.quizbuks.prospect;

import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobCancellation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.mail.MailRecipient;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ai.AiCost;
import com.jabiz.quizbuks.content.ai.AiQuestionsJob;
import com.jabiz.quizbuks.content.ai.AiUserKeys;
import com.jabiz.quizbuks.content.ai.ClaudeApi;
import com.jabiz.quizbuks.content.ai.ClaudeService;
import com.jabiz.quizbuks.content.ai.ClaudeWebResearch;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.mailserver.MailServerProcesses;
import com.jabiz.quizbuks.mailserver.QbMailServers;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.mail.MailEntities;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.mail.SendMail;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.security.RandomBytesStep;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * AI prospecting's processes (docs/quizbuks/plans/Q48-operators.md, Q48c).
 *
 * <p>A person's ({@code qb.prospect.use}, the owner decided by {@link #owner}: their operator when they are a current
 * member of one, headquarters ({@value QbProspects#DIRECT}) when they hold {@code qb.admin.prospect}, refused
 * otherwise): {@value #SETTINGS_SAVE}, {@value #DIRECTION_SAVE}, {@value #APPROVE} (the mail and the trial quiz as the
 * person edited them; checked as Claude's are), {@value #DISCARD} and {@value #BLOCK_ADD}.
 *
 * <p>The system's ({@code internal()}, {@code qb.prospect.system}): the items of the three jobs
 * ({@value #FIND_START}, {@value #RESEARCH_START}, {@value #SEND}: each reads its row, takes the owner's lock, reads
 * again and does nothing when the row no longer qualifies) and the outcomes of the two job types ({@value #FOUND},
 * {@value #RESEARCHED}, their failures and cancellations), which keep what was found and add what it cost to the
 * owner's month ({@link QbProspects#USAGE}: the tokens priced by {@link AiCost}, the searches at
 * {@link ClaudeWebResearch#SEARCH_PRICE_USD}; an attempt that recorded nothing charged as
 * {@link ClaudeWebResearch#unanswered}). The trial quiz's link and the sign-up's mark is the prospect's random
 * {@code trialKey}, drawn at the approval.
 */
public final class ProspectProcesses {

    public static final String SETTINGS_SAVE = "QB_PROSPECT_SETTINGS_SAVE";
    public static final String DIRECTION_SAVE = "QB_PROSPECT_DIRECTION_SAVE";
    public static final String APPROVE = "QB_PROSPECT_APPROVE";
    public static final String DISCARD = "QB_PROSPECT_DISCARD";
    public static final String BLOCK_ADD = "QB_PROSPECT_BLOCK_ADD";
    public static final String FIND_START = "QB_PROSPECT_FIND_START";
    public static final String RESEARCH_START = "QB_PROSPECT_RESEARCH_START";
    public static final String SEND = "QB_PROSPECT_SEND";
    public static final String FOUND = "QB_PROSPECT_FOUND";
    public static final String FIND_FAILED = "QB_PROSPECT_FIND_FAILED";
    public static final String FIND_CANCELLED = "QB_PROSPECT_FIND_CANCELLED";
    public static final String RESEARCHED = "QB_PROSPECT_RESEARCHED";
    public static final String RESEARCH_FAILED = "QB_PROSPECT_RESEARCH_FAILED";
    public static final String RESEARCH_CANCELLED = "QB_PROSPECT_RESEARCH_CANCELLED";

    public static final String FIND_JOB = "qb.prospect-find";
    public static final String RESEARCH_JOB = "qb.prospect-research";
    public static final String SEND_JOB = "qb.prospect-send";
    public static final String FIND_DUE_TEMPLATE = "qb.system.prospect-find-due";
    public static final String RESEARCH_DUE_TEMPLATE = "qb.system.prospect-research-due";
    public static final String SEND_DUE_TEMPLATE = "qb.system.prospect-send-due";

    /** The mails go out between these hours of the zone's day (a business's working day). */
    public static final LocalTime SEND_FROM = LocalTime.of(9, 0);
    public static final LocalTime SEND_UNTIL = LocalTime.of(18, 0);
    /** Research started a day for an owner, per mail it may send that day. */
    public static final int RESEARCH_PER_MAIL = 2;
    static final int TOKEN_BYTES = 24;

    // ---- inputs and outputs ------------------------------------------------------------------------------------------

    public record SettingsInput(boolean enabled, @Min(QbProspects.MIN_DAILY_CAP) @Max(QbProspects.MAX_DAILY_CAP)
        int dailyCap, @Min(0) @Max(100) int minScore,
        @NotNull @DecimalMin("0") @DecimalMax("100000") BigDecimal monthlyCostCap,
        @NotBlank @Size(max = QbProspects.NAME_LENGTH) String senderName,
        @NotBlank @Size(max = QbProspects.SENDER_ADDRESS_LENGTH) String senderAddress,
        @NotBlank @Size(max = QbProspects.EMAIL_LENGTH) String senderContact, Long baseRevision) {}

    public record SettingsOutput(String owner, long revision) {}

    /** @param directionId null to add one */
    public record DirectionInput(String directionId, @NotBlank @Size(max = QbProspects.NAME_LENGTH) String name,
        @NotBlank @Size(max = QbProspects.BRIEF_LENGTH) String brief, @Size(max = 2) String country,
        @Size(max = 5) String language, boolean active, @Min(1) @Max(QbProspects.MAX_PER_SEARCH) int perSearch,
        Long baseRevision) {}

    public record DirectionOutput(String directionId, long revision) {}

    /** @param trial null keeps the drafted quiz */
    public record ApproveInput(@NotNull UUID prospectId,
        @NotBlank @Size(max = ProspectAnswers.SUBJECT_LENGTH) String mailSubject,
        @NotBlank @Size(max = ProspectAnswers.BODY_LENGTH) String mailBody, @Valid ProspectJobs.Trial trial,
        @NotNull Long baseRevision) {}

    /** @param block also never write to its address and domain again (the business said no, or will not want it) */
    public record DiscardInput(@NotNull UUID prospectId, boolean block,
        @Size(max = QbProspects.NOTE_LENGTH) String note) {}

    public record BlockInput(@NotBlank String kind, @NotBlank @Size(max = QbProspects.EMAIL_LENGTH) String value,
        @NotBlank String reason, @Size(max = QbProspects.NOTE_LENGTH) String note) {}

    public record ProspectOutput(String prospectId, String status, boolean changed) {}

    public record BlockOutput(String kind, String value, boolean changed) {}

    /** The items of the jobs. */
    public record DirectionRef(@NotNull UUID directionId) {}

    public record ProspectRef(@NotNull UUID prospectId) {}

    /** What an item did: {@code started} or {@code sent}, or why not. */
    public record ItemOutput(String id, String outcome) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String OWNER = "owner";
    static final String MEMBERSHIP = "membership";
    static final String SETTING = "setting";
    static final String DIRECTIONS = "directions";
    static final String DIRECTION = "direction";
    static final String PROSPECT = "prospect";
    static final String PROSPECTS = "prospects";
    static final String BLOCKS = "blocks";
    static final String OPT_OUTS = "optOuts";
    static final String USAGE = "usage";
    static final String PARAMS = "params";
    static final String SERVER = "server";
    static final String SENT_TODAY = "sentToday";
    static final String STARTED_TODAY = "startedToday";
    static final String RANDOM = "random";
    static final String JOB_INPUT = "jobInput";
    static final String STARTING = "starting";
    static final String SENDING = "sending";

    private ProspectProcesses() {}

    // ---- whose prospecting -------------------------------------------------------------------------------------------

    /**
     * The owner of the caller's prospecting under {@link #OWNER}: their operator when they are its current member
     * (refused when it is suspended), headquarters when they hold {@code qb.admin.prospect}; refused otherwise. The
     * membership is held shared for the process (a removal takes effect at the next act).
     */
    public static <I, O> void owner(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Hold the caller's membership", HoldLock.<ProcessContext>shared(
                ctx -> QbOperators.memberLockName(ctx.request().actorId())))
            .step("Load the caller's membership", QueryEntities.<ProcessContext>of(
                QbOperators.defaultDataset(QbOperators.MEMBER), ctx -> OperatorChecks.current(ctx.request().actorId()),
                MEMBERSHIP))
            .compute("Whose prospecting", (metadata, ctx) -> ctx.put(OWNER, ownerOf(ctx)));
    }

    static String ownerOf(ProcessContext ctx) {
        EntityInstance member = first(ctx, MEMBERSHIP);
        if (member != null) {
            if (!Boolean.TRUE.equals(member.get("operatorActive"))) {
                throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.SUSPENDED,
                    "The caller's operator is suspended", Map.of()));
            }
            return String.valueOf((Object) member.get("operatorId"));
        }
        if (ctx.request().hasPermission(QbPermissions.ADMIN_PROSPECT)) {
            return QbProspects.DIRECT;
        }
        throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NOT_MEMBER,
            "The caller is no operator's member", Map.of()));
    }

    static String owner(ProcessContext ctx) {
        return ctx.get(OWNER, String.class);
    }

    // ---- the owner's mail server -------------------------------------------------------------------------------------

    public static final String MAIL_SERVER_SET = "QB_PROSPECT_MAIL_SERVER_SET";
    public static final String MAIL_SERVER_REMOVE = "QB_PROSPECT_MAIL_SERVER_REMOVE";

    /**
     * The owner's own mail server (D56): the sponsors' table under the key {@code prospect:<owner>}, tested by a mail
     * to the caller. The mails go out only through it.
     */
    public static ProcessDefinition<MailServerProcesses.SetInput, MailServerProcesses.SetOutput, ProcessContext>
        mailServerSet() {
        return MailServerProcesses.set(MAIL_SERVER_SET, "Saves the mail server prospecting mails leave through, after "
            + "a test mail.", QbPermissions.PROSPECT_USE, ProspectProcesses::owner,
            ctx -> QbProspects.senderKey(owner(ctx)));
    }

    public static ProcessDefinition<MailServerProcesses.RemoveInput, MailServerProcesses.RemoveOutput, ProcessContext>
        mailServerRemove() {
        return MailServerProcesses.remove(MAIL_SERVER_REMOVE, "Removes the prospecting mail server; no mail goes out "
            + "until another is verified.", QbPermissions.PROSPECT_USE, ProspectProcesses::owner,
            ctx -> QbProspects.senderKey(owner(ctx)));
    }

    // ---- settings and directions -------------------------------------------------------------------------------------

    public static ProcessDefinition<SettingsInput, SettingsOutput, ProcessContext> settingsSave() {
        return ProcessDefinition.define(SETTINGS_SAVE, 1, SettingsInput.class, SettingsOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Saves whether and how the owner prospects, and the sender its mails name.")
                    .permissions(QbPermissions.PROSPECT_USE)
                    .requiresVerifiedEmail()
                    .contextFactory(ProspectProcesses::start)
                    .outputMapper(ctx -> ctx.get(OUTPUT, SettingsOutput.class));
                owner(pb);
                pb.step("Lock the owner's prospecting", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbProspects.ownerLockName(owner(ctx))))
                    .step("Load the settings", QueryEntities.<ProcessContext>of(
                        QbProspects.defaultDataset(QbProspects.SETTING), ctx -> byOwner(owner(ctx), 1), SETTING))
                    .compute("Save", (metadata, ctx) -> saveSettings(ctx, ctx.get(INPUT, SettingsInput.class)));
            });
    }

    static void saveSettings(ProcessContext ctx, SettingsInput input) {
        EntityInstance setting = first(ctx, SETTING);
        if (setting != null && (input.baseRevision() == null
            || input.baseRevision() != AccountEditing.number(setting.get("revision")))) {
            ctx.reject(new Violation("baseRevision", ProspectCodes.CHANGED, "The settings were changed since they "
                + "were read", Map.of("revision", AccountEditing.number(setting.get("revision")))));
            return;
        }
        if (!email(input.senderContact())) {
            ctx.reject(new Violation("senderContact", ProspectCodes.FIELD, "Not an e-mail address", Map.of()));
            return;
        }
        long revision = setting == null ? 1 : AccountEditing.number(setting.get("revision")) + 1;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", input.enabled());
        values.put("dailyCap", BigDecimal.valueOf(input.dailyCap()));
        values.put("minScore", BigDecimal.valueOf(input.minScore()));
        values.put("monthlyCostCap", input.monthlyCostCap().setScale(2, RoundingMode.HALF_UP));
        values.put("senderName", input.senderName().strip());
        values.put("senderAddress", input.senderAddress().strip());
        values.put("senderContact", input.senderContact().strip());
        values.put("updatedAt", ctx.opTime());
        values.put("updatedBy", ctx.request().actorId());
        values.put("revision", BigDecimal.valueOf(revision));
        if (setting == null) {
            values.put("owner", owner(ctx));
            ctx.changes().insert(QbProspects.SETTING, values);
        } else {
            ctx.changes().update(QbProspects.SETTING, setting.id(), setting.version(), values);
        }
        ctx.put(OUTPUT, new SettingsOutput(owner(ctx), revision));
    }

    public static ProcessDefinition<DirectionInput, DirectionOutput, ProcessContext> directionSave() {
        return ProcessDefinition.define(DIRECTION_SAVE, 1, DirectionInput.class, DirectionOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Adds or changes a direction of search: what kind of business to look for.")
                    .permissions(QbPermissions.PROSPECT_USE)
                    .requiresVerifiedEmail()
                    .contextFactory(ProspectProcesses::start)
                    .outputMapper(ctx -> ctx.get(OUTPUT, DirectionOutput.class));
                owner(pb);
                pb.step("Lock the owner's prospecting", HoldLock.<ProcessContext>exclusive(
                        ctx -> QbProspects.ownerLockName(owner(ctx))))
                    .step("Load the directions", QueryEntities.<ProcessContext>of(
                        QbProspects.defaultDataset(QbProspects.DIRECTION),
                        ctx -> byOwner(owner(ctx), QbProspects.MAX_DIRECTIONS + 1), DIRECTIONS))
                    .compute("Save", (metadata, ctx) -> saveDirection(ctx, ctx.get(INPUT, DirectionInput.class)));
            });
    }

    static void saveDirection(ProcessContext ctx, DirectionInput input) {
        EntityInstance direction = null;
        if (input.directionId() != null && !input.directionId().isBlank()) {
            for (EntityInstance row : list(ctx, DIRECTIONS)) {
                if (String.valueOf(row.id()).equals(input.directionId().strip())) {
                    direction = row;
                }
            }
            if (direction == null) {
                throw new EntityNotFoundException(QbProspects.DIRECTION + " " + input.directionId() + " not found");
            }
            if (input.baseRevision() == null || input.baseRevision() != AccountEditing.number(direction.get("revision"))) {
                ctx.reject(new Violation("baseRevision", ProspectCodes.CHANGED, "The direction was changed since it "
                    + "was read", Map.of("revision", AccountEditing.number(direction.get("revision")))));
                return;
            }
        } else if (list(ctx, DIRECTIONS).size() >= QbProspects.MAX_DIRECTIONS) {
            ctx.reject(new Violation("name", ProspectCodes.DIRECTIONS_FULL, "The owner has its most directions",
                Map.of("max", QbProspects.MAX_DIRECTIONS)));
            return;
        }
        String country = blank(input.country()) ? null : input.country().strip().toUpperCase(Locale.ROOT);
        String language = blank(input.language()) ? null : input.language().strip().toLowerCase(Locale.ROOT);
        if (country != null && !country.matches("[A-Z]{2}")) {
            ctx.reject(new Violation("country", ProspectCodes.FIELD, "Not a country code", Map.of()));
        }
        if (language != null && !language.matches("[a-z]{2}")) {
            ctx.reject(new Violation("language", ProspectCodes.FIELD, "Not a language code", Map.of()));
        }
        if (ctx.hasViolations()) {
            return;
        }
        long revision = direction == null ? 1 : AccountEditing.number(direction.get("revision")) + 1;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", input.name().strip());
        values.put("brief", input.brief().strip());
        values.put("country", country);
        values.put("language", language);
        values.put("active", input.active());
        values.put("perSearch", BigDecimal.valueOf(input.perSearch()));
        values.put("revision", BigDecimal.valueOf(revision));
        String id;
        if (direction == null) {
            values.put("owner", owner(ctx));
            values.put("createdAt", ctx.opTime());
            values.put("createdBy", ctx.request().actorId());
            id = String.valueOf(ctx.changes().insert(QbProspects.DIRECTION, values));
        } else {
            ctx.changes().update(QbProspects.DIRECTION, direction.id(), direction.version(), values);
            id = String.valueOf(direction.id());
        }
        ctx.put(OUTPUT, new DirectionOutput(id, revision));
    }

    // ---- the person's decision ---------------------------------------------------------------------------------------

    public static ProcessDefinition<ApproveInput, ProspectOutput, ProcessContext> approve(JsonMapper json) {
        return ProcessDefinition.define(APPROVE, 1, ApproveInput.class, ProspectOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Approves a drafted mail and trial quiz, as edited: it goes out with the day's sends.")
                    .permissions(QbPermissions.PROSPECT_USE)
                    .requiresVerifiedEmail()
                    .contextFactory(ProspectProcesses::start)
                    .outputMapper(ctx -> ctx.get(OUTPUT, ProspectOutput.class))
                    .step("Draw the trial's token", RandomBytesStep.class,
                        new RandomBytesStep.Metadata(RANDOM, TOKEN_BYTES));
                owner(pb);
                lockAndLoad(pb, ctx -> ctx.get(INPUT, ApproveInput.class).prospectId());
                pb.compute("Approve", (metadata, ctx) -> approve(ctx, ctx.get(INPUT, ApproveInput.class), json));
            });
    }

    /** Locks the prospect and reads it with the blocks of its address and domain. */
    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, UUID> prospectId) {
        pb.step("Lock the prospect", HoldLock.<ProcessContext>exclusive(
                ctx -> QbProspects.prospectLockName(prospectId.apply(ctx))))
            .step("Load the prospect", QueryEntities.<ProcessContext>of(
                QbProspects.defaultDataset(QbProspects.PROSPECT), ctx -> byKey("prospectId",
                    prospectId.apply(ctx).toString()), PROSPECT))
            .step("Load its blocks", QueryEntities.<ProcessContext>of(QbProspects.defaultDataset(QbProspects.BLOCK),
                ctx -> blocksOf(first(ctx, PROSPECT)), BLOCKS));
    }

    /** The caller's owner's prospect; 404 for another owner's, or none. */
    private static EntityInstance own(ProcessContext ctx) {
        EntityInstance prospect = first(ctx, PROSPECT);
        if (prospect == null || !owner(ctx).equals(prospect.get("owner"))) {
            throw new EntityNotFoundException(QbProspects.PROSPECT + " not found");
        }
        return prospect;
    }

    static void approve(ProcessContext ctx, ApproveInput input, JsonMapper json) {
        EntityInstance prospect = own(ctx);
        if (!QbProspects.DRAFTED.equals(prospect.get("status"))) {
            ctx.reject(state(prospect));
            return;
        }
        if (input.baseRevision() != AccountEditing.number(prospect.get("revision"))) {
            ctx.reject(new Violation("baseRevision", ProspectCodes.CHANGED, "The draft was changed since it was read",
                Map.of("revision", AccountEditing.number(prospect.get("revision")))));
            return;
        }
        if (!list(ctx, BLOCKS).isEmpty()) {
            ctx.reject(new Violation("prospectId", ProspectCodes.BLOCKED, "The address or its domain is blocked",
                Map.of()));
            return;
        }
        String body = ProspectAnswers.mailBody(input.mailBody().strip());
        if (body == null) {
            ctx.reject(new Violation("mailBody", ProspectCodes.MAIL_BODY, "The mail must be plain text without links",
                Map.of()));
        }
        ProspectJobs.Trial trial = input.trial() == null ? null : ProspectAnswers.edited(input.trial());
        if (input.trial() != null && trial == null) {
            ctx.reject(new Violation("trial", ProspectCodes.TRIAL, "The trial quiz needs five questions of four "
                + "options with one right each", Map.of()));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", QbProspects.APPROVED);
        values.put("mailSubject", input.mailSubject().strip());
        values.put("mailBody", body);
        if (trial != null) {
            values.put("trial", json.writeValueAsString(trial));
        }
        values.put("trialKey", HexFormat.of().formatHex(ctx.get(RANDOM, byte[].class)));
        values.put("approvedAt", ctx.opTime());
        values.put("approvedBy", ctx.request().actorId());
        write(ctx, prospect, values);
    }

    public static ProcessDefinition<DiscardInput, ProspectOutput, ProcessContext> discard() {
        return ProcessDefinition.define(DISCARD, 1, DiscardInput.class, ProspectOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Discards a business: no mail goes out; blocked, never again.")
                    .permissions(QbPermissions.PROSPECT_USE)
                    .requiresVerifiedEmail()
                    .contextFactory(ProspectProcesses::start)
                    .outputMapper(ctx -> ctx.get(OUTPUT, ProspectOutput.class));
                owner(pb);
                lockAndLoad(pb, ctx -> ctx.get(INPUT, DiscardInput.class).prospectId());
                pb.compute("Discard", (metadata, ctx) -> {
                    DiscardInput input = ctx.get(INPUT, DiscardInput.class);
                    EntityInstance prospect = own(ctx);
                    String status = prospect.get("status");
                    if (QbProspects.SENT.equals(status) || QbProspects.RESEARCHING.equals(status)) {
                        ctx.reject(state(prospect));
                        return;
                    }
                    if (input.block()) {
                        block(ctx, QbProspects.KIND_DOMAIN, prospect.get("domain"), QbProspects.DECLINED,
                            input.note());
                        Object email = prospect.get("publicEmail");
                        if (email != null) {
                            block(ctx, QbProspects.KIND_EMAIL, email, QbProspects.DECLINED, input.note());
                        }
                    }
                    if (QbProspects.DISCARDED.equals(status)) {
                        ctx.put(OUTPUT, new ProspectOutput(String.valueOf(prospect.id()), status, false));
                        return;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("status", QbProspects.DISCARDED);
                    values.put("discardedAt", ctx.opTime());
                    values.put("discardedBy", ctx.request().actorId());
                    write(ctx, prospect, values);
                });
            });
    }

    public static ProcessDefinition<BlockInput, BlockOutput, ProcessContext> blockAdd() {
        return ProcessDefinition.define(BLOCK_ADD, 1, BlockInput.class, BlockOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Never writes to an address, or a whole domain, again: for every owner.")
                    .permissions(QbPermissions.PROSPECT_USE)
                    .requiresVerifiedEmail()
                    .contextFactory(ProspectProcesses::start)
                    .outputMapper(ctx -> ctx.get(OUTPUT, BlockOutput.class));
                owner(pb);
                pb.step("Lock the block", HoldLock.<ProcessContext>exclusive(ctx -> {
                        BlockInput input = ctx.get(INPUT, BlockInput.class);
                        return "qb.prospect-block:" + normalized(input.kind(), input.value());
                    }))
                    .step("Load the block", QueryEntities.<ProcessContext>of(
                        QbProspects.defaultDataset(QbProspects.BLOCK), ctx -> {
                            BlockInput input = ctx.get(INPUT, BlockInput.class);
                            return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("kind", input.kind().strip()),
                                new QueryPredicate.Eq("value", normalized(input.kind(), input.value())))))
                                .limit(1).build();
                        }, BLOCKS))
                    .compute("Block", (metadata, ctx) -> {
                        BlockInput input = ctx.get(INPUT, BlockInput.class);
                        String kind = input.kind().strip();
                        String value = normalized(kind, input.value());
                        if (!QbProspects.BLOCK_KINDS.contains(kind)
                            || !QbProspects.BLOCK_REASONS.contains(input.reason().strip())) {
                            ctx.reject(new Violation("kind", ProspectCodes.FIELD, "Unknown kind or reason", Map.of()));
                            return;
                        }
                        if (QbProspects.KIND_EMAIL.equals(kind) ? !email(value) : !ProspectAnswers.validDomain(value)) {
                            ctx.reject(new Violation("value", ProspectCodes.FIELD, "Not an address or a domain",
                                Map.of()));
                            return;
                        }
                        boolean changed = list(ctx, BLOCKS).isEmpty();
                        if (changed) {
                            insertBlock(ctx, kind, value, input.reason().strip(), input.note());
                        }
                        ctx.put(OUTPUT, new BlockOutput(kind, value, changed));
                    });
            });
    }

    /** Blocks an address or a domain unless the prospect's blocks have it already (read under the prospect's lock). */
    private static void block(ProcessContext ctx, String kind, Object value, String reason, String note) {
        String normalized = normalized(kind, String.valueOf(value));
        for (EntityInstance row : list(ctx, BLOCKS)) {
            if (kind.equals(row.get("kind")) && normalized.equals(row.get("value"))) {
                return;
            }
        }
        insertBlock(ctx, kind, normalized, reason, note);
    }

    private static void insertBlock(ProcessContext ctx, String kind, String value, String reason, String note) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", kind);
        values.put("value", value);
        values.put("reason", reason);
        values.put("owner", ctx.contains(OWNER) ? owner(ctx) : QbProspects.DIRECT);
        values.put("note", blank(note) ? null : note.strip());
        values.put("createdAt", ctx.opTime());
        values.put("createdBy", ctx.request().actorId());
        ctx.changes().insert(QbProspects.BLOCK, values);
    }

    static String normalized(String kind, String value) {
        String text = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return QbProspects.KIND_DOMAIN.equals(kind == null ? null : kind.strip()) ? QbProspects.domain(text) : text;
    }

    // ---- the jobs' items ---------------------------------------------------------------------------------------------

    /**
     * {@value #FIND_START}: one direction's search for the day, when its owner prospects, the direction is active,
     * not searched today, the month's cost is below the cap, and the owner has fewer businesses waiting for research
     * than it researches in a day.
     */
    public static ProcessDefinition<DirectionRef, ItemOutput, ProcessContext> findStart(ZoneId zone,
        AiUserKeys keys) {
        return ProcessDefinition.define(FIND_START, 1, DirectionRef.class, ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Starts the day's search of one direction.")
                .permissions(QbPermissions.PROSPECT_SYSTEM)
                .internal()
                .contextFactory(ProspectProcesses::start)
                .outputMapper(ProspectProcesses::itemOutput)
                .step("Load the direction", QueryEntities.<ProcessContext>of(
                    QbProspects.defaultDataset(QbProspects.DIRECTION), ctx -> byKey("directionId",
                        ctx.get(INPUT, DirectionRef.class).directionId().toString()), DIRECTION))
                .step("Lock the owner's prospecting", HoldLock.<ProcessContext>exclusive(
                    ctx -> first(ctx, DIRECTION) == null ? null
                        : QbProspects.ownerLockName(first(ctx, DIRECTION).get("owner"))))
                .step("Load the direction again and the owner's state", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbProspects.defaultDataset(QbProspects.DIRECTION), ctx -> byKey("directionId",
                        first(ctx, DIRECTION) == null ? null : ctx.get(INPUT, DirectionRef.class).directionId()
                            .toString()), DIRECTION),
                    QueryEntities.query(QbProspects.defaultDataset(QbProspects.SETTING),
                        ctx -> byOwner(ownerOfRow(ctx, DIRECTION), 1), SETTING),
                    QueryEntities.query(QbProspects.defaultDataset(QbProspects.USAGE),
                        ctx -> usageOf(ownerOfRow(ctx, DIRECTION), QbProspects.month(ctx.opTime(), zone)), USAGE),
                    QueryEntities.query(QbProspects.defaultDataset(QbProspects.PROSPECT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("owner", one(ownerOfRow(ctx, DIRECTION))))
                        .orderBy("foundAt", false).limit(QbProspects.MAX_EXCLUDED_DOMAINS).build(), PROSPECTS),
                    QueryEntities.query(QbProspects.defaultDataset(QbProspects.BLOCK), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Eq("kind", QbProspects.KIND_DOMAIN)).orderBy("createdAt", false)
                        .limit(QbProspects.MAX_EXCLUDED_DOMAINS).build(), BLOCKS),
                    paramsQuery()))
                .compute("Decide", (metadata, ctx) -> decideFind(ctx, zone, keys))
                .step("Start the search", StartExternalJob.<ProcessContext>of(ProspectJobs.FIND,
                        ctx -> ctx.get(JOB_INPUT))
                    .when(ctx -> Boolean.TRUE.equals(ctx.get(STARTING)))
                    .reference(ctx -> "direction:" + first(ctx, DIRECTION).id())));
    }

    static void decideFind(ProcessContext ctx, ZoneId zone, AiUserKeys keys) {
        EntityInstance direction = first(ctx, DIRECTION);
        String refusal = runs(ctx, direction == null ? null : direction.get("owner"));
        if (refusal == null && !Boolean.TRUE.equals(direction.get("active"))) {
            refusal = "inactive";
        }
        Instant last = instant(direction == null ? null : direction.get("lastSearchAt"));
        if (refusal == null && last != null && !last.isBefore(dayStart(ctx.opTime(), zone))) {
            refusal = "searched-today";
        }
        if (refusal != null) {
            ctx.put(OUTPUT, new ItemOutput(direction == null ? null : String.valueOf(direction.id()), refusal));
            return;
        }
        Set<String> excluded = new LinkedHashSet<>();
        list(ctx, PROSPECTS).forEach(row -> excluded.add(String.valueOf((Object) row.get("domain"))));
        for (EntityInstance block : list(ctx, BLOCKS)) {
            if (excluded.size() >= QbProspects.MAX_EXCLUDED_DOMAINS) {
                break;
            }
            excluded.add(String.valueOf((Object) block.get("value")));
        }
        Map<String, String> params = params(ctx);
        String owner = String.valueOf((Object) direction.get("owner"));
        ctx.put(JOB_INPUT, new ProspectJobs.FindInput(owner, String.valueOf(direction.id()), direction.get("brief"),
            direction.get("country"), direction.get("language"),
            (int) AccountEditing.number(direction.get("perSearch")), List.copyOf(excluded),
            params.get(QbParams.PROSPECT_MODEL), params.get(QbParams.PROSPECT_EFFORT), keys.of("prospect:" + owner)));
        ctx.put(STARTING, true);
        ctx.changes().update(QbProspects.DIRECTION, direction.id(), direction.version(),
            Map.of("lastSearchAt", ctx.opTime()));
        ctx.put(OUTPUT, new ItemOutput(String.valueOf(direction.id()), "started"));
    }

    /**
     * Why an owner does not prospect now, or null when it does: its settings say so, the switch is on with a model
     * and an effort, and the month's cost is below its cap.
     */
    static String runs(ProcessContext ctx, Object owner) {
        if (owner == null) {
            return "gone";
        }
        EntityInstance setting = first(ctx, SETTING);
        if (setting == null || !Boolean.TRUE.equals(setting.get("enabled"))) {
            return "disabled";
        }
        Map<String, String> params = params(ctx);
        if (!"true".equals(params.get(QbParams.PROSPECT_ENABLED)) || params.get(QbParams.PROSPECT_MODEL) == null
            || params.get(QbParams.PROSPECT_EFFORT) == null) {
            return "switched-off";
        }
        EntityInstance usage = first(ctx, USAGE);
        BigDecimal spent = usage == null ? BigDecimal.ZERO : decimal(usage.get("costUsd"));
        if (spent.compareTo(decimal(setting.get("monthlyCostCap"))) >= 0) {
            return "cost-cap";
        }
        return null;
    }

    /**
     * {@value #RESEARCH_START}: one found business's research, when its owner prospects and has started fewer
     * researches today than {@value #RESEARCH_PER_MAIL} per mail it may send; a business whose domain was blocked
     * meanwhile is skipped.
     */
    public static ProcessDefinition<ProspectRef, ItemOutput, ProcessContext> researchStart(ZoneId zone,
        AiUserKeys keys) {
        return ProcessDefinition.define(RESEARCH_START, 1, ProspectRef.class, ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Starts the research of one found business.")
                .permissions(QbPermissions.PROSPECT_SYSTEM)
                .internal()
                .contextFactory(ProspectProcesses::start)
                .outputMapper(ProspectProcesses::itemOutput)
                .step("Load the prospect", QueryEntities.<ProcessContext>of(
                    QbProspects.defaultDataset(QbProspects.PROSPECT), ctx -> byKey("prospectId",
                        ctx.get(INPUT, ProspectRef.class).prospectId().toString()), PROSPECT))
                .step("Lock the owner's prospecting", HoldLock.<ProcessContext>exclusive(
                    ctx -> first(ctx, PROSPECT) == null ? null
                        : QbProspects.ownerLockName(first(ctx, PROSPECT).get("owner"))))
                .step("Lock the prospect", HoldLock.<ProcessContext>exclusive(
                    ctx -> first(ctx, PROSPECT) == null ? null : QbProspects.prospectLockName(first(ctx, PROSPECT).id())))
                .step("Load it again and the owner's state", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbProspects.defaultDataset(QbProspects.PROSPECT), ctx -> byKey("prospectId",
                        first(ctx, PROSPECT) == null ? null : ctx.get(INPUT, ProspectRef.class).prospectId()
                            .toString()), PROSPECT),
                    QueryEntities.query(QbProspects.defaultDataset(QbProspects.SETTING),
                        ctx -> byOwner(ownerOfRow(ctx, PROSPECT), 1), SETTING),
                    QueryEntities.query(QbProspects.defaultDataset(QbProspects.USAGE),
                        ctx -> usageOf(ownerOfRow(ctx, PROSPECT), QbProspects.month(ctx.opTime(), zone)), USAGE),
                    QueryEntities.query(QbProspects.defaultDataset(QbProspects.PROSPECT), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("owner", one(ownerOfRow(ctx, PROSPECT))),
                            new QueryPredicate.Gte("researchStartedAt", dayStart(ctx.opTime(), zone)))))
                        .limit(QbProspects.MAX_DAILY_CAP * RESEARCH_PER_MAIL + 1).build(), STARTED_TODAY),
                    QueryEntities.query(QbProspects.defaultDataset(QbProspects.DIRECTION), ctx -> byKey("directionId",
                        first(ctx, PROSPECT) == null ? null : first(ctx, PROSPECT).get("directionId")), DIRECTION),
                    QueryEntities.query(QbProspects.defaultDataset(QbProspects.BLOCK),
                        ctx -> blocksOf(first(ctx, PROSPECT)), BLOCKS),
                    paramsQuery()))
                .compute("Decide", (metadata, ctx) -> decideResearch(ctx, keys))
                .step("Start the research", StartExternalJob.<ProcessContext>of(ProspectJobs.RESEARCH,
                        ctx -> ctx.get(JOB_INPUT))
                    .when(ctx -> Boolean.TRUE.equals(ctx.get(STARTING)))
                    .reference(ctx -> "prospect:" + first(ctx, PROSPECT).id())
                    .outputKey("externalJobId"))
                .compute("Record the start", (metadata, ctx) -> {
                    if (Boolean.TRUE.equals(ctx.get(STARTING))) {
                        EntityInstance prospect = first(ctx, PROSPECT);
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("status", QbProspects.RESEARCHING);
                        values.put("researchJobId", String.valueOf((Object) ctx.get("externalJobId")));
                        values.put("researchStartedAt", ctx.opTime());
                        write(ctx, prospect, values);
                        ctx.put(OUTPUT, new ItemOutput(String.valueOf(prospect.id()), "started"));
                    }
                }));
    }

    static void decideResearch(ProcessContext ctx, AiUserKeys keys) {
        EntityInstance prospect = first(ctx, PROSPECT);
        if (prospect == null || !QbProspects.FOUND.equals(prospect.get("status"))) {
            ctx.put(OUTPUT, new ItemOutput(prospect == null ? null : String.valueOf(prospect.id()), "not-found"));
            return;
        }
        String refusal = runs(ctx, prospect.get("owner"));
        EntityInstance setting = first(ctx, SETTING);
        if (refusal == null && list(ctx, STARTED_TODAY).size()
            >= AccountEditing.number(setting.get("dailyCap")) * RESEARCH_PER_MAIL) {
            refusal = "daily-cap";
        }
        if (refusal != null) {
            ctx.put(OUTPUT, new ItemOutput(String.valueOf(prospect.id()), refusal));
            return;
        }
        if (!list(ctx, BLOCKS).isEmpty()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", QbProspects.SKIPPED);
            values.put("failure", "blocked");
            write(ctx, prospect, values);
            ctx.put(OUTPUT, new ItemOutput(String.valueOf(prospect.id()), "blocked"));
            return;
        }
        EntityInstance direction = first(ctx, DIRECTION);
        Map<String, String> params = params(ctx);
        String owner = String.valueOf((Object) prospect.get("owner"));
        ctx.put(JOB_INPUT, new ProspectJobs.ResearchInput(owner, String.valueOf(prospect.id()), prospect.get("domain"),
            prospect.get("websiteUrl"), direction == null ? "" : direction.get("brief"),
            direction == null ? null : direction.get("language"),
            (int) AccountEditing.number(setting.get("minScore")), params.get(QbParams.PROSPECT_MODEL),
            params.get(QbParams.PROSPECT_EFFORT), keys.of("prospect:" + owner)));
        ctx.put(STARTING, true);
    }

    /**
     * {@value #SEND}: one approved mail, within the owner's working hours and day's cap, through the owner's verified
     * server; a business blocked or unsubscribed meanwhile is skipped, one whose owner has no usable server waits.
     */
    public static ProcessDefinition<ProspectRef, ItemOutput, ProcessContext> send(ZoneId zone) {
        return ProcessDefinition.define(SEND, 1, ProspectRef.class, ItemOutput.class, ProcessContext.class, pb -> pb
            .description("Sends one approved prospecting mail.")
            .permissions(QbPermissions.PROSPECT_SYSTEM)
            .internal()
            .contextFactory(ProspectProcesses::start)
            .outputMapper(ProspectProcesses::itemOutput)
            .step("Load the prospect", QueryEntities.<ProcessContext>of(
                QbProspects.defaultDataset(QbProspects.PROSPECT), ctx -> byKey("prospectId",
                    ctx.get(INPUT, ProspectRef.class).prospectId().toString()), PROSPECT))
            .step("Lock the owner's prospecting", HoldLock.<ProcessContext>exclusive(
                ctx -> first(ctx, PROSPECT) == null ? null
                    : QbProspects.ownerLockName(first(ctx, PROSPECT).get("owner"))))
            .step("Lock the prospect", HoldLock.<ProcessContext>exclusive(
                ctx -> first(ctx, PROSPECT) == null ? null : QbProspects.prospectLockName(first(ctx, PROSPECT).id())))
            .step("Load it again and the owner's state", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbProspects.defaultDataset(QbProspects.PROSPECT), ctx -> byKey("prospectId",
                    first(ctx, PROSPECT) == null ? null : ctx.get(INPUT, ProspectRef.class).prospectId().toString()),
                    PROSPECT),
                QueryEntities.query(QbProspects.defaultDataset(QbProspects.SETTING),
                    ctx -> byOwner(ownerOfRow(ctx, PROSPECT), 1), SETTING),
                QueryEntities.query(QbProspects.defaultDataset(QbProspects.USAGE),
                    ctx -> usageOf(ownerOfRow(ctx, PROSPECT), QbProspects.month(ctx.opTime(), zone)), USAGE),
                QueryEntities.query(QbProspects.defaultDataset(QbProspects.PROSPECT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("owner", one(ownerOfRow(ctx, PROSPECT))),
                        new QueryPredicate.Gte("sentAt", dayStart(ctx.opTime(), zone)))))
                    .limit(QbProspects.MAX_DAILY_CAP + 1).build(), SENT_TODAY),
                QueryEntities.query(QbProspects.defaultDataset(QbProspects.BLOCK),
                    ctx -> blocksOf(first(ctx, PROSPECT)), BLOCKS),
                QueryEntities.query(MailEntities.ADDRESS_OPT_OUT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("address", one(emailOf(first(ctx, PROSPECT))))).limit(10).build(),
                    OPT_OUTS),
                QueryEntities.query(QbMailServers.DEFAULT_DATASET, ctx -> QbMailServers.bySponsor(
                    first(ctx, PROSPECT) == null ? null : QbProspects.senderKey(first(ctx, PROSPECT).get("owner"))),
                    SERVER),
                paramsQuery()))
            .compute("Decide", (metadata, ctx) -> decideSend(ctx, zone))
            .step("Send the mail", SendMail.<ProcessContext>via(ctx -> sending(ctx)
                    ? QbProspects.senderKey(first(ctx, PROSPECT).get("owner")) : null)
                .when(ProspectProcesses::sending, ProspectMails.PROSPECT,
                    ctx -> MailRecipient.address(first(ctx, PROSPECT).get("publicEmail"),
                        ProspectMails.language(first(ctx, PROSPECT).get("language"))),
                    ProspectMails.params(ProspectProcesses::firstProspect,
                        ctx -> first(ctx, SETTING))))
            .compute("Record the send", (metadata, ctx) -> {
                if (sending(ctx)) {
                    EntityInstance prospect = first(ctx, PROSPECT);
                    write(ctx, prospect, Map.of("status", QbProspects.SENT, "sentAt", ctx.opTime()));
                    ctx.put(OUTPUT, new ItemOutput(String.valueOf(prospect.id()), "sent"));
                }
            }));
    }

    private static EntityInstance firstProspect(ProcessContext ctx) {
        return first(ctx, PROSPECT);
    }

    static boolean sending(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.get(SENDING));
    }

    static void decideSend(ProcessContext ctx, ZoneId zone) {
        EntityInstance prospect = first(ctx, PROSPECT);
        if (prospect == null || !QbProspects.APPROVED.equals(prospect.get("status"))) {
            ctx.put(OUTPUT, new ItemOutput(prospect == null ? null : String.valueOf(prospect.id()), "not-approved"));
            return;
        }
        String id = String.valueOf(prospect.id());
        // Unsubscribed or blocked meanwhile: never sent, and the unsubscription is blocked for every owner too.
        if (!list(ctx, OPT_OUTS).isEmpty() || !list(ctx, BLOCKS).isEmpty()) {
            if (!list(ctx, OPT_OUTS).isEmpty()) {
                ctx.put(OWNER, String.valueOf((Object) prospect.get("owner")));
                block(ctx, QbProspects.KIND_EMAIL, prospect.get("publicEmail"), QbProspects.UNSUBSCRIBED, null);
            }
            write(ctx, prospect, Map.of("status", QbProspects.SKIPPED, "failure", "blocked"));
            ctx.put(OUTPUT, new ItemOutput(id, "blocked"));
            return;
        }
        String refusal = sendRuns(ctx, prospect.get("owner"));
        LocalTime local = ctx.opTime().atZone(zone).toLocalTime();
        if (refusal == null && (local.isBefore(SEND_FROM) || !local.isBefore(SEND_UNTIL))) {
            refusal = "outside-hours";
        }
        EntityInstance setting = first(ctx, SETTING);
        if (refusal == null && list(ctx, SENT_TODAY).size() >= AccountEditing.number(setting.get("dailyCap"))) {
            refusal = "daily-cap";
        }
        if (refusal == null && !QbMailServers.usable(first(ctx, SERVER))) {
            refusal = "no-mail-server";
        }
        if (refusal != null) {
            ctx.put(OUTPUT, new ItemOutput(id, refusal));
            return;
        }
        ctx.put(SENDING, true);
    }

    /** As {@link #runs}, but the month's cost does not stop a mail already approved: sending costs nothing. */
    static String sendRuns(ProcessContext ctx, Object owner) {
        EntityInstance setting = first(ctx, SETTING);
        if (owner == null) {
            return "gone";
        }
        if (setting == null || !Boolean.TRUE.equals(setting.get("enabled"))) {
            return "disabled";
        }
        return "true".equals(params(ctx).get(QbParams.PROSPECT_ENABLED)) ? null : "switched-off";
    }

    // ---- the jobs' outcomes ------------------------------------------------------------------------------------------

    public static ProcessDefinition<ExternalJobCompletion, ItemOutput, ProcessContext> found(ZoneId zone) {
        return outcome(FOUND, "Keeps the businesses a search found and counts what it cost.",
            ExternalJobCompletion.class, zone, true, ctx -> {
                ExternalJobCompletion completion = ctx.get(INPUT, ExternalJobCompletion.class);
                ProspectJobs.FindResult result = completion.result(ProspectJobs.FindResult.class);
                charge(ctx, completion.usage(), completion.unrecordedAttempts(), result.servedModel(),
                    completion.finishedTime(), zone);
                EntityInstance direction = first(ctx, DIRECTION);
                Set<String> known = new java.util.HashSet<>();
                list(ctx, PROSPECTS).forEach(row -> known.add(String.valueOf((Object) row.get("domain"))));
                list(ctx, BLOCKS).forEach(row -> known.add(String.valueOf((Object) row.get("value"))));
                int added = 0;
                for (ProspectJobs.Candidate candidate : result.candidates()) {
                    if (direction == null || !known.add(candidate.domain())) {
                        continue;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("owner", direction.get("owner"));
                    values.put("directionId", String.valueOf(direction.id()));
                    values.put("domain", candidate.domain());
                    values.put("websiteUrl", candidate.url());
                    values.put("company", candidate.company());
                    values.put("status", QbProspects.FOUND);
                    values.put("foundAt", ctx.opTime());
                    values.put("revision", BigDecimal.ONE);
                    ctx.changes().insert(QbProspects.PROSPECT, values);
                    added++;
                }
                ctx.put(OUTPUT, new ItemOutput(direction == null ? null : String.valueOf(direction.id()),
                    "found " + added));
            });
    }

    public static ProcessDefinition<ExternalJobFailure, ItemOutput, ProcessContext> findFailed(ZoneId zone) {
        return outcome(FIND_FAILED, "Counts what a failed search cost.", ExternalJobFailure.class, zone, true, ctx -> {
            ExternalJobFailure failure = ctx.get(INPUT, ExternalJobFailure.class);
            charge(ctx, failure.usage(), failure.unrecordedAttempts(), null, failure.finishedTime(), zone);
        });
    }

    public static ProcessDefinition<ExternalJobCancellation, ItemOutput, ProcessContext> findCancelled(ZoneId zone) {
        return outcome(FIND_CANCELLED, "Counts what a cancelled search cost.", ExternalJobCancellation.class, zone,
            true, ctx -> {
                ExternalJobCancellation cancellation = ctx.get(INPUT, ExternalJobCancellation.class);
                charge(ctx, cancellation.usage(), cancellation.unrecordedAttempts(), null,
                    cancellation.finishedTime(), zone);
            });
    }

    public static ProcessDefinition<ExternalJobCompletion, ItemOutput, ProcessContext> researched(ZoneId zone,
        JsonMapper json) {
        return outcome(RESEARCHED, "Keeps a business's research, and its draft when it may be written to.",
            ExternalJobCompletion.class, zone, false, ctx -> {
                ExternalJobCompletion completion = ctx.get(INPUT, ExternalJobCompletion.class);
                ProspectJobs.ResearchResult result = completion.result(ProspectJobs.ResearchResult.class);
                charge(ctx, completion.usage(), completion.unrecordedAttempts(), result.servedModel(),
                    completion.finishedTime(), zone);
                keepResearch(ctx, result, json);
            });
    }

    static void keepResearch(ProcessContext ctx, ProspectJobs.ResearchResult result, JsonMapper json) {
        EntityInstance prospect = first(ctx, PROSPECT);
        if (prospect == null || !QbProspects.RESEARCHING.equals(prospect.get("status"))) {
            return;
        }
        EntityInstance setting = first(ctx, SETTING);
        int minScore = setting == null ? QbProspects.DEFAULT_MIN_SCORE
            : (int) AccountEditing.number(setting.get("minScore"));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("researchedAt", ctx.opTime());
        if (result.company() != null) {
            values.put("company", result.company());
        }
        values.put("language", result.language());
        values.put("country", result.country());
        values.put("sellsOnline", result.sellsOnline());
        values.put("shopify", result.shopify());
        values.put("publicEmail", result.publicEmail());
        values.put("refusesMarketing", result.refusesMarketing());
        values.put("score", BigDecimal.valueOf(result.score()));
        values.put("scoreReasons", json.writeValueAsString(result.reasons()));
        values.put("products", json.writeValueAsString(result.products()));
        boolean blocked = blockedEmail(ctx, result.publicEmail());
        String status;
        String failure = null;
        if (result.refusesMarketing()) {
            status = QbProspects.SKIPPED;
            failure = "refuses-marketing";
            ctx.put(OWNER, String.valueOf((Object) prospect.get("owner")));
            block(ctx, QbProspects.KIND_DOMAIN, prospect.get("domain"), QbProspects.REFUSES_MARKETING, null);
        } else if (result.publicEmail() == null) {
            status = QbProspects.SKIPPED;
            failure = "no-public-email";
        } else if (blocked) {
            status = QbProspects.SKIPPED;
            failure = "blocked";
        } else if (result.score() < minScore) {
            status = QbProspects.LOW_SCORE;
        } else if (result.trial() == null || result.mailSubject() == null) {
            status = QbProspects.SKIPPED;
            failure = "no-draft";
        } else {
            status = QbProspects.DRAFTED;
            values.put("trial", json.writeValueAsString(result.trial()));
            values.put("mailSubject", result.mailSubject());
            values.put("mailBody", result.mailBody());
        }
        values.put("status", status);
        values.put("failure", failure);
        write(ctx, prospect, values);
        ctx.put(OUTPUT, new ItemOutput(String.valueOf(prospect.id()), status));
    }

    /** Whether the address is blocked, or unsubscribed from the prospecting mail. */
    private static boolean blockedEmail(ProcessContext ctx, String email) {
        if (email == null) {
            return false;
        }
        String address = email.toLowerCase(Locale.ROOT);
        for (EntityInstance block : list(ctx, BLOCKS)) {
            if (QbProspects.KIND_EMAIL.equals(block.get("kind")) && address.equals(block.get("value"))) {
                return true;
            }
        }
        for (EntityInstance optOut : list(ctx, OPT_OUTS)) {
            if (address.equals(String.valueOf((Object) optOut.get("address")).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static ProcessDefinition<ExternalJobFailure, ItemOutput, ProcessContext> researchFailed(ZoneId zone) {
        return outcome(RESEARCH_FAILED, "Records that a business's research failed and counts what it cost.",
            ExternalJobFailure.class, zone, false, ctx -> {
                ExternalJobFailure failure = ctx.get(INPUT, ExternalJobFailure.class);
                charge(ctx, failure.usage(), failure.unrecordedAttempts(), null, failure.finishedTime(), zone);
                endResearch(ctx, failure.errorCode());
            });
    }

    public static ProcessDefinition<ExternalJobCancellation, ItemOutput, ProcessContext> researchCancelled(
        ZoneId zone) {
        return outcome(RESEARCH_CANCELLED, "Records that a business's research was cancelled and counts its cost.",
            ExternalJobCancellation.class, zone, false, ctx -> {
                ExternalJobCancellation cancellation = ctx.get(INPUT, ExternalJobCancellation.class);
                charge(ctx, cancellation.usage(), cancellation.unrecordedAttempts(), null,
                    cancellation.finishedTime(), zone);
                endResearch(ctx, "cancelled");
            });
    }

    private static void endResearch(ProcessContext ctx, String reason) {
        EntityInstance prospect = first(ctx, PROSPECT);
        if (prospect != null && QbProspects.RESEARCHING.equals(prospect.get("status"))) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", QbProspects.FAILED);
            values.put("failure", reason == null ? "failed" : reason.length() > 500 ? reason.substring(0, 500)
                : reason);
            write(ctx, prospect, values);
            ctx.put(OUTPUT, new ItemOutput(String.valueOf(prospect.id()), QbProspects.FAILED));
        }
    }

    /**
     * An outcome of a job: the row it is about from the job's reference (a direction's search, a business's
     * research), its owner's lock, then the owner's usage of the month and what {@code act} needs.
     */
    private static <I> ProcessDefinition<I, ItemOutput, ProcessContext> outcome(String name, String description,
        Class<I> input, ZoneId zone, boolean search, java.util.function.Consumer<ProcessContext> act) {
        return ProcessDefinition.define(name, 1, input, ItemOutput.class, ProcessContext.class, pb -> pb
            .description(description)
            .permissions(QbPermissions.PROSPECT_SYSTEM)
            .internal()
            .contextFactory(ProspectProcesses::start)
            .outputMapper(ProspectProcesses::itemOutput)
            .step("Load what the job was about", QueryEntities.<ProcessContext>of(
                QbProspects.defaultDataset(search ? QbProspects.DIRECTION : QbProspects.PROSPECT),
                ctx -> byKey(search ? "directionId" : "prospectId", referenced(ctx)), search ? DIRECTION : PROSPECT))
            .step("Lock the owner's prospecting", HoldLock.<ProcessContext>exclusive(
                ctx -> QbProspects.ownerLockName(ownerOfRow(ctx, search ? DIRECTION : PROSPECT))))
            .step("Lock the prospect", HoldLock.<ProcessContext>exclusive(
                ctx -> search || first(ctx, PROSPECT) == null ? null
                    : QbProspects.prospectLockName(first(ctx, PROSPECT).id())))
            .step("Load the owner's state", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbProspects.defaultDataset(search ? QbProspects.DIRECTION : QbProspects.PROSPECT),
                    ctx -> byKey(search ? "directionId" : "prospectId", referenced(ctx)), search ? DIRECTION : PROSPECT),
                QueryEntities.query(QbProspects.defaultDataset(QbProspects.SETTING),
                    ctx -> byOwner(ownerOfRow(ctx, search ? DIRECTION : PROSPECT), 1), SETTING),
                QueryEntities.query(QbProspects.defaultDataset(QbProspects.USAGE),
                    ctx -> usageOf(ownerOfRow(ctx, search ? DIRECTION : PROSPECT),
                        QbProspects.month(finishedTime(ctx), zone)), USAGE),
                paramsQuery()))
            .steps(b -> {
                if (search) {
                    b.step("Load what the owner knows of the businesses found", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbProspects.defaultDataset(QbProspects.PROSPECT),
                            ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                                new QueryPredicate.In("owner", one(ownerOfRow(ctx, DIRECTION))),
                                new QueryPredicate.In("domain", foundDomains(ctx))))).limit(QbProspects.MAX_PER_SEARCH
                                    + 1).build(), PROSPECTS),
                        QueryEntities.query(QbProspects.defaultDataset(QbProspects.BLOCK),
                            ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                                new QueryPredicate.Eq("kind", QbProspects.KIND_DOMAIN),
                                new QueryPredicate.In("value", foundDomains(ctx))))).limit(QbProspects.MAX_PER_SEARCH
                                    + 1).build(), BLOCKS)));
                } else {
                    b.step("Load the blocks of the address found", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbProspects.defaultDataset(QbProspects.BLOCK),
                            ctx -> blocksOf(first(ctx, PROSPECT), researchedEmail(ctx)), BLOCKS),
                        QueryEntities.query(MailEntities.ADDRESS_OPT_OUT_DATASET, ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.In("address", one(researchedEmail(ctx)))).limit(10).build(),
                            OPT_OUTS)));
                }
            })
            .compute("Act", (metadata, ctx) -> act.accept(ctx))
            .step("Lock the owner's usage", HoldLock.<ProcessContext>exclusive(
                ctx -> ctx.contains(CHARGE) ? QbProspects.usageLockName(ownerOfRow(ctx, search ? DIRECTION : PROSPECT))
                    : null))
            .compute("Count the cost", (metadata, ctx) -> addUsage(ctx, zone, search ? DIRECTION : PROSPECT)));
    }

    static final String CHARGE = "charge";

    /** What a job cost, kept for {@link #addUsage}: tokens, searches and dollars. */
    record Charge(ClaudeApi.Usage usage, long searches, BigDecimal costUsd) {}

    /**
     * Prices a job's usage: the tokens its attempts recorded, each attempt that recorded nothing at
     * {@link ClaudeWebResearch#unanswered} of an empty request, and the searches.
     */
    static void charge(ProcessContext ctx, Map<String, Long> metrics, int unrecordedAttempts, String servedModel,
        Instant finished, ZoneId zone) {
        long in = value(metrics, AiQuestionsJob.INPUT_TOKENS);
        long out = value(metrics, AiQuestionsJob.OUTPUT_TOKENS);
        if (unrecordedAttempts > 0) {
            ClaudeApi.Usage each = ClaudeWebResearch.unanswered(new ClaudeWebResearch.Request(null, null, "", "",
                null, 0, 0, null));
            in += unrecordedAttempts * each.inputTokens();
            out += unrecordedAttempts * each.outputTokens();
        }
        ClaudeApi.Usage usage = new ClaudeApi.Usage(in, out, value(metrics, AiQuestionsJob.FALLBACK_INPUT_TOKENS),
            value(metrics, AiQuestionsJob.FALLBACK_OUTPUT_TOKENS), value(metrics, AiQuestionsJob.LONG_INPUT_TOKENS),
            value(metrics, AiQuestionsJob.LONG_OUTPUT_TOKENS));
        long searches = value(metrics, ClaudeWebResearch.SEARCHES);
        // Priced as the model now set, or the dearest when none is (the parameter was removed meanwhile).
        String model = params(ctx).get(QbParams.PROSPECT_MODEL);
        BigDecimal tokens = AiCost.of(model == null ? ClaudeService.OPUS : model, servedModel, usage,
            AiCost.known(ClaudeService.OPUS));
        BigDecimal cost = (tokens == null ? BigDecimal.ZERO : tokens).add(ClaudeWebResearch.searchCost(searches));
        ctx.put(CHARGE, new Charge(usage, searches, cost));
    }

    private static void addUsage(ProcessContext ctx, ZoneId zone, String rowKey) {
        Charge charge = ctx.contains(CHARGE) ? ctx.get(CHARGE, Charge.class) : null;
        Object owner = ownerOfRow(ctx, rowKey);
        if (charge == null || owner == null) {
            return;
        }
        EntityInstance usage = first(ctx, USAGE);
        Map<String, Object> values = new LinkedHashMap<>();
        BigDecimal in = BigDecimal.valueOf(charge.usage().inputTokens());
        BigDecimal out = BigDecimal.valueOf(charge.usage().outputTokens());
        BigDecimal searches = BigDecimal.valueOf(charge.searches());
        BigDecimal cost = charge.costUsd().setScale(4, RoundingMode.HALF_UP);
        if (usage == null) {
            values.put("owner", owner);
            values.put("month", QbProspects.month(finishedTime(ctx), zone));
            values.put("inputTokens", in);
            values.put("outputTokens", out);
            values.put("searches", searches);
            values.put("jobs", BigDecimal.ONE);
            values.put("costUsd", cost);
            ctx.changes().insert(QbProspects.USAGE, values);
        } else {
            values.put("inputTokens", decimal(usage.get("inputTokens")).add(in));
            values.put("outputTokens", decimal(usage.get("outputTokens")).add(out));
            values.put("searches", decimal(usage.get("searches")).add(searches));
            values.put("jobs", decimal(usage.get("jobs")).add(BigDecimal.ONE));
            values.put("costUsd", decimal(usage.get("costUsd")).add(cost));
            ctx.changes().update(QbProspects.USAGE, usage.id(), usage.version(), values);
        }
    }

    /** The id the job's reference names: {@code direction:<id>} or {@code prospect:<id>}. */
    private static String referenced(ProcessContext ctx) {
        Object input = ctx.get(INPUT);
        String reference = switch (input) {
            case ExternalJobCompletion completion -> completion.reference();
            case ExternalJobFailure failure -> failure.reference();
            case ExternalJobCancellation cancellation -> cancellation.reference();
            default -> null;
        };
        return reference == null || reference.indexOf(':') < 0 ? null : reference.substring(reference.indexOf(':') + 1);
    }

    private static Instant finishedTime(ProcessContext ctx) {
        Object input = ctx.get(INPUT);
        Instant time = switch (input) {
            case ExternalJobCompletion completion -> completion.finishedTime();
            case ExternalJobFailure failure -> failure.finishedTime();
            case ExternalJobCancellation cancellation -> cancellation.finishedTime();
            default -> null;
        };
        return time == null ? ctx.opTime() : time;
    }

    private static List<Object> foundDomains(ProcessContext ctx) {
        ExternalJobCompletion completion = ctx.get(INPUT) instanceof ExternalJobCompletion c ? c : null;
        if (completion == null) {
            return List.of();
        }
        List<Object> domains = new ArrayList<>();
        completion.result(ProspectJobs.FindResult.class).candidates().forEach(c -> domains.add(c.domain()));
        return domains;
    }

    private static String researchedEmail(ProcessContext ctx) {
        ExternalJobCompletion completion = ctx.get(INPUT) instanceof ExternalJobCompletion c ? c : null;
        String email = completion == null ? null : completion.result(ProspectJobs.ResearchResult.class).publicEmail();
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    // ---- shared ------------------------------------------------------------------------------------------------------

    private static ProcessContext start(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static ItemOutput itemOutput(ProcessContext ctx) {
        ItemOutput output = ctx.contains(OUTPUT) ? (ItemOutput) ctx.get(OUTPUT) : null;
        return output == null ? new ItemOutput(null, "nothing") : output;
    }

    /** Writes a prospect with its revision moved on, and the output of a change. */
    private static void write(ProcessContext ctx, EntityInstance prospect, Map<String, Object> changes) {
        Map<String, Object> values = new LinkedHashMap<>(changes);
        long revision = AccountEditing.number(prospect.get("revision")) + 1;
        values.put("revision", BigDecimal.valueOf(revision));
        ctx.changes().update(QbProspects.PROSPECT, prospect.id(), prospect.version(), values);
        ctx.put(OUTPUT, new ItemOutput(String.valueOf(prospect.id()), String.valueOf(values.get("status"))));
        if (ctx.get(INPUT) instanceof ApproveInput || ctx.get(INPUT) instanceof DiscardInput) {
            ctx.put(OUTPUT, new ProspectOutput(String.valueOf(prospect.id()), String.valueOf(values.get("status")),
                true));
        }
    }

    private static Violation state(EntityInstance prospect) {
        return new Violation("prospectId", ProspectCodes.STATE, "The business is not in a state for this",
            Map.of("status", String.valueOf((Object) prospect.get("status"))));
    }

    /** The blocks of a prospect's domain and of an address (its own when none is given). */
    static EntityQuery blocksOf(EntityInstance prospect, String email) {
        List<QueryPredicate> any = new ArrayList<>();
        if (prospect != null) {
            any.add(new QueryPredicate.And(List.of(new QueryPredicate.Eq("kind", QbProspects.KIND_DOMAIN),
                new QueryPredicate.Eq("value", String.valueOf((Object) prospect.get("domain"))))));
        }
        String address = email != null ? email : emailOf(prospect);
        if (address != null) {
            any.add(new QueryPredicate.And(List.of(new QueryPredicate.Eq("kind", QbProspects.KIND_EMAIL),
                new QueryPredicate.Eq("value", address))));
        }
        if (any.isEmpty()) {
            return EntityQuery.builder().where(new QueryPredicate.In("blockId", List.of())).limit(1).build();
        }
        return EntityQuery.builder().where(any.size() == 1 ? any.getFirst() : new QueryPredicate.Or(any)).limit(10)
            .build();
    }

    static EntityQuery blocksOf(EntityInstance prospect) {
        return blocksOf(prospect, null);
    }

    private static String emailOf(EntityInstance prospect) {
        Object email = prospect == null ? null : prospect.get("publicEmail");
        return email == null ? null : email.toString().toLowerCase(Locale.ROOT);
    }

    private static Object ownerOfRow(ProcessContext ctx, String key) {
        EntityInstance row = first(ctx, key);
        return row == null ? null : row.get("owner");
    }

    static QueryEntities.Metadata<ProcessContext> paramsQuery() {
        return QueryEntities.query(ParamEntities.DATASET, ctx -> EntityQuery.builder()
            .where(new QueryPredicate.In(ParamEntities.KEY, new ArrayList<>(QbParams.PROSPECT_CONTROLLED)))
            .limit(QbParams.PROSPECT_CONTROLLED.size()).build(), PARAMS);
    }

    static Map<String, String> params(ProcessContext ctx) {
        Map<String, String> values = new HashMap<>();
        for (EntityInstance param : list(ctx, PARAMS)) {
            Object value = param.get(ParamEntities.VALUE);
            values.put(String.valueOf((Object) param.get(ParamEntities.KEY)), value == null ? null : value.toString());
        }
        return values;
    }

    private static EntityQuery byOwner(Object owner, int limit) {
        return EntityQuery.builder().where(new QueryPredicate.In("owner", one(owner))).limit(limit).build();
    }

    private static EntityQuery usageOf(Object owner, String month) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.In("owner", one(owner)), new QueryPredicate.Eq("month", month)))).limit(1).build();
    }

    private static EntityQuery byKey(String field, Object value) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, one(value))).limit(1).build();
    }

    private static List<Object> one(Object value) {
        List<Object> values = new ArrayList<>();
        if (value != null) {
            values.add(value);
        }
        return values;
    }

    /** The start of the zone's day of a time. */
    static Instant dayStart(Instant time, ZoneId zone) {
        return time.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }

    static boolean email(String value) {
        if (value == null) {
            return false;
        }
        String text = value.strip();
        int at = text.indexOf('@');
        return at > 0 && at == text.lastIndexOf('@') && at < text.length() - 3 && text.indexOf('.', at) > at + 1
            && text.chars().noneMatch(c -> Character.isWhitespace(c) || c == '<' || c == '>');
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static long value(Map<String, Long> metrics, String key) {
        Long value = metrics == null ? null : metrics.get(key);
        return value == null || value < 0 ? 0 : value;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal d ? d : value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object found = ctx.contains(key) ? ctx.get(key) : null;
        return found instanceof List<?> rows ? (List<EntityInstance>) rows : List.of();
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }
}
