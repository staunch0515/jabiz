package com.jabiz.runtime.mail;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.Rbac;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Changing the texts of a template's mail (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D72),
 * by template and language:
 * <ul>
 *   <li>{@value #SAVE}: checks the texts by the {@code MailTextRules} (400 with every violation) and writes a new
 *       version, in effect at once; texts equal to the default return to it. Templates whose changes need approval
 *       (a token, a sign-in link, {@code protectedText()}) are refused (422 {@code MAIL_TEXT_NEEDS_APPROVAL}): their
 *       changes are proposed as controlled changes of {@value MailTextEntities#ENTITY}
 *       ({@link MailTextControlTarget});</li>
 *   <li>{@value #RESET}: returns to the default at once, for every template;</li>
 *   <li>{@value #TEST_SEND}: renders the given or the current texts with sample values and mails them to the
 *       operator's own verified address only, the subject marked {@value #TEST_PREFIX}, with tokens that open
 *       nothing ({@link QueueTestMail}); rate limited per person ({@code jabiz.process.rate-limits}, 10 an hour by
 *       default).</li>
 * </ul>
 * The writes hold the named lock {@code jabiz.mail-text:<template>} and take the version the page read
 * ({@code baseVersionNo}; none for a text never written): another person's change in between is a conflict (409). They
 * need {@value MailPermissions#TEXT_WRITE} and a verified address. The preview is no process: it writes nothing
 * ({@code POST /api/mail/texts/preview}, {@link MailTextController}).
 */
@Configuration
public class MailTextProcesses {

    public static final String SAVE = "MAIL_TEXT_SAVE";
    public static final String RESET = "MAIL_TEXT_RESET";
    public static final String TEST_SEND = "MAIL_TEXT_TEST_SEND";

    /** What the subject of a test mail starts with. */
    public static final String TEST_PREFIX = "[TEST] ";

    /**
     * @param baseVersionNo the version of the text the change is based on, as read; null when none was stored
     */
    public record SaveInput(@NotBlank String template, @NotBlank String locale, String subject, String body,
        Integer baseVersionNo) {}

    public record ResetInput(@NotBlank String template, @NotBlank String locale, Integer baseVersionNo) {}

    /**
     * @param kind      {@code CUSTOM} or {@code DEFAULT}: what the mail is sent with now
     * @param versionNo the version written, or the current one when nothing needed writing; null when none is stored
     */
    public record TextOutput(String template, String locale, String kind, Integer versionNo) {}

    /** @param subject with {@code body}, a draft to send; both absent: the texts in effect */
    public record TestSendInput(@NotBlank String template, @NotBlank String locale, String subject, String body) {}

    /** @param address the operator's own address the test mail goes to */
    public record TestSendOutput(String messageId, String address) {}

    static final String INPUT = "input";
    static final String TEMPLATE = "template";
    static final String LOCALE = "locale";
    static final String OUTPUT = "output";
    private static final String CURRENT = "current";
    private static final StepSpec<NoMetadata, ProcessContext> QUEUE_TEST = StepSpec.of(QueueTestMail.class,
        NoMetadata.INSTANCE);

    /** The name of the lock every write of a template's texts holds. */
    static String lock(String template) {
        return "jabiz.mail-text:" + template;
    }

    static ProcessDefinition<SaveInput, TextOutput, ProcessContext> save(MailTemplates templates, MailTexts texts) {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, TextOutput.class, ProcessContext.class, pb -> pb
            .description("Changes the subject and body of a mail in one language, in effect at once.")
            .permissions(MailPermissions.TEXT_WRITE)
            .requiresVerifiedEmail()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, TextOutput.class))
            .compute("Check the template", (metadata, ctx) -> {
                SaveInput input = ctx.get(INPUT, SaveInput.class);
                MailTemplate template = resolve(ctx, templates, texts, input.template(), input.locale());
                if (template.textChangeNeedsApproval()) {
                    throw new BusinessRuleViolationException(new Violation("template",
                        PlatformErrorCodes.MAIL_TEXT_NEEDS_APPROVAL, "The texts of mail " + template.name()
                        + " change only through a controlled change another administrator publishes",
                        Map.of("template", template.name())));
                }
            })
            .step("Lock the template", HoldLock.<ProcessContext>exclusive(ctx -> lock(ctx.get(INPUT, SaveInput.class)
                .template())))
            .step("Load the text", current())
            .compute("Save the text", (metadata, ctx) -> {
                SaveInput input = ctx.get(INPUT, SaveInput.class);
                MailTemplate template = ctx.get(TEMPLATE, MailTemplate.class);
                Locale locale = ctx.get(LOCALE, Locale.class);
                EntityInstance current = current(ctx, input.baseVersionNo());
                List<Violation> problems = texts.problems(template, input.subject(), input.body());
                if (!problems.isEmpty()) {
                    throw new ValidationException(problems);
                }
                Optional<MailTexts.Text> defaults = texts.defaults(template, locale);
                boolean isDefault = defaults.isPresent() && defaults.get().subject().equals(input.subject())
                    && defaults.get().body().equals(input.body());
                if (isDefault) {
                    ctx.put(OUTPUT, writeDefault(ctx, texts, template, locale, current));
                    return;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(MailTextEntities.KIND, MailTextEntities.CUSTOM);
                row.put(MailTextEntities.SUBJECT, input.subject());
                row.put(MailTextEntities.BODY, input.body());
                row.put(MailTextEntities.DEFAULT_HASH, texts.defaultHash(template, locale));
                ctx.put(OUTPUT, write(ctx, template, locale, current, row, MailTextEntities.CUSTOM));
            }));
    }

    static ProcessDefinition<ResetInput, TextOutput, ProcessContext> reset(MailTemplates templates, MailTexts texts) {
        return ProcessDefinition.define(RESET, 1, ResetInput.class, TextOutput.class, ProcessContext.class, pb -> pb
            .description("Returns a mail in one language to its default subject and body, at once.")
            .permissions(MailPermissions.TEXT_WRITE)
            .requiresVerifiedEmail()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, new SaveInput(input.template(), input.locale(), null, null, input.baseVersionNo()));
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, TextOutput.class))
            .compute("Check the template", (metadata, ctx) -> {
                SaveInput input = ctx.get(INPUT, SaveInput.class);
                resolve(ctx, templates, texts, input.template(), input.locale());
            })
            .step("Lock the template", HoldLock.<ProcessContext>exclusive(ctx -> lock(ctx.get(INPUT, SaveInput.class)
                .template())))
            .step("Load the text", current())
            .compute("Return to the default", (metadata, ctx) -> {
                SaveInput input = ctx.get(INPUT, SaveInput.class);
                EntityInstance current = current(ctx, input.baseVersionNo());
                ctx.put(OUTPUT, writeDefault(ctx, texts, ctx.get(TEMPLATE, MailTemplate.class),
                    ctx.get(LOCALE, Locale.class), current));
            }));
    }

    static ProcessDefinition<TestSendInput, TestSendOutput, ProcessContext> testSend(MailTemplates templates,
        MailTexts texts) {
        return ProcessDefinition.define(TEST_SEND, 1, TestSendInput.class, TestSendOutput.class, ProcessContext.class,
            pb -> pb
                .description("Sends oneself a test mail of a template's texts, with sample values.")
                .permissions(MailPermissions.TEXT_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, TestSendOutput.class))
                .compute("Check the template", (metadata, ctx) -> {
                    TestSendInput input = ctx.get(INPUT, TestSendInput.class);
                    MailTemplate template = resolve(ctx, templates, texts, input.template(), input.locale());
                    if ((input.subject() == null) != (input.body() == null)) {
                        throw new ValidationException(List.of(new Violation(input.subject() == null ? "subject"
                            : "body", PlatformErrorCodes.REQUIRED, "A draft gives its subject and its body")));
                    }
                    if (input.subject() != null) {
                        List<Violation> problems = texts.problems(template, input.subject(), input.body());
                        if (!problems.isEmpty()) {
                            throw new ValidationException(problems);
                        }
                    }
                })
                .step("Queue the test mail", QUEUE_TEST));
    }

    /** The template and language of the input, put into the context; 400 when either is unknown. */
    private static MailTemplate resolve(ProcessContext ctx, MailTemplates templates, MailTexts texts, String name,
        String language) {
        Optional<MailTemplate> template = templates.find(name);
        Locale locale = language == null ? null : Locale.of(language.strip());
        if (template.isEmpty()) {
            throw new ValidationException(List.of(new Violation("template", PlatformErrorCodes.INVALID_VALUE,
                name + " is not a mail template", Map.of("field", "template"))));
        }
        if (!texts.language(locale)) {
            throw new ValidationException(List.of(new Violation("locale", PlatformErrorCodes.INVALID_VALUE,
                language + " is not one of the application's languages", Map.of("field", "locale"))));
        }
        ctx.put(TEMPLATE, template.get());
        ctx.put(LOCALE, locale);
        return template.get();
    }

    private static StepSpec<QueryEntities.Metadata<ProcessContext>, ProcessContext> current() {
        return QueryEntities.of(MailTextEntities.DATASET, ctx -> Rbac.all(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq(MailTextEntities.TEMPLATE, ctx.get(TEMPLATE, MailTemplate.class).name()),
            new QueryPredicate.Eq(MailTextEntities.LOCALE, ctx.get(LOCALE, Locale.class).getLanguage()))),
            MailTextEntities.TEMPLATE), CURRENT);
    }

    /** The stored text, if any, after checking that it is the version the change is based on (409 otherwise). */
    @SuppressWarnings("unchecked")
    private static EntityInstance current(ProcessContext ctx, Integer baseVersionNo) {
        List<EntityInstance> found = ctx.contains(CURRENT) ? (List<EntityInstance>) ctx.get(CURRENT) : List.of();
        EntityInstance current = found.isEmpty() ? null : found.getFirst();
        Long now = current == null ? null : current.version();
        Long base = baseVersionNo == null ? null : baseVersionNo.longValue();
        if (!Objects.equals(now, base)) {
            throw ConcurrentUpdateException.stale("The text of mail " + ctx.get(TEMPLATE, MailTemplate.class).name()
                + " [" + ctx.get(LOCALE, Locale.class).getLanguage() + "] is at version " + now + ", not " + base
                + ": somebody changed it meanwhile");
        }
        return current;
    }

    /** Returns to the default; nothing to write when no override is in effect. */
    private static TextOutput writeDefault(ProcessContext ctx, MailTexts texts, MailTemplate template, Locale locale,
        EntityInstance current) {
        if (current == null || !MailTextEntities.CUSTOM.equals(current.get(MailTextEntities.KIND))) {
            return new TextOutput(template.name(), locale.getLanguage(), MailTextEntities.DEFAULT,
                current == null ? null : Math.toIntExact(current.version()));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(MailTextEntities.KIND, MailTextEntities.DEFAULT);
        row.put(MailTextEntities.SUBJECT, null);
        row.put(MailTextEntities.BODY, null);
        row.put(MailTextEntities.DEFAULT_HASH, texts.defaultHash(template, locale));
        return write(ctx, template, locale, current, row, MailTextEntities.DEFAULT);
    }

    /** Inserts the text or appends a version, with the grant that lets it pass {@link MailTextGuard}. */
    private static TextOutput write(ProcessContext ctx, MailTemplate template, Locale locale, EntityInstance current,
        Map<String, Object> row, String kind) {
        ChangeSet.Target writes = ctx.changes().in(MailTextEntities.DATASET).granted(new MailTextWriteGrant(
            template.name(), locale.getLanguage(), ctx.processSeqId(), false));
        if (current == null) {
            Map<String, Object> created = new LinkedHashMap<>(row);
            created.put(MailTextEntities.TEMPLATE, template.name());
            created.put(MailTextEntities.LOCALE, locale.getLanguage());
            writes.insert(MailTextEntities.ENTITY, created);
            return new TextOutput(template.name(), locale.getLanguage(), kind, 1);
        }
        writes.update(MailTextEntities.ENTITY, id(current), current.version(), row);
        return new TextOutput(template.name(), locale.getLanguage(), kind, Math.toIntExact(current.version() + 1));
    }

    private static Object id(EntityInstance instance) {
        return instance.id() instanceof UUID ? instance.id() : UUID.fromString(String.valueOf(instance.id()));
    }

    @Bean
    ProcessDefinition<SaveInput, TextOutput, ProcessContext> mailTextSaveProcess(MailTemplates templates,
        MailTexts texts) {
        return save(templates, texts);
    }

    @Bean
    ProcessDefinition<ResetInput, TextOutput, ProcessContext> mailTextResetProcess(MailTemplates templates,
        MailTexts texts) {
        return reset(templates, texts);
    }

    @Bean
    ProcessDefinition<TestSendInput, TestSendOutput, ProcessContext> mailTextTestSendProcess(MailTemplates templates,
        MailTexts texts) {
        return testSend(templates, texts);
    }
}
