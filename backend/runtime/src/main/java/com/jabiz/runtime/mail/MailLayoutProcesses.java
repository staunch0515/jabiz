package com.jabiz.runtime.mail;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailLayout;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.StepSpec;
import com.jabiz.query.QueryPredicate;
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
import java.util.UUID;

/**
 * The processes of the mail layout (docs/design/18-numbering-approvals-tasks.md section 5.6; decision D72), by
 * language. A layout of its own is only ever published as a controlled change of {@value MailLayoutEntities#ENTITY}
 * ({@link MailLayoutControlTarget}): it is on every mail, so another administrator approves it.
 * <ul>
 *   <li>{@value #RESET}: returns to the default (configuration {@code jabiz.mail.layout.<language>.*}) at once;</li>
 *   <li>{@value #TEST_SEND}: sends the operator's own verified address a sample mail (the task notification with its
 *       sample values) on the given draft or the layout in effect, the subject marked
 *       {@value MailTextProcesses#TEST_PREFIX}; rate limited per person ({@code jabiz.process.rate-limits}, 10 an hour
 *       by default).</li>
 * </ul>
 * The reset holds the named lock {@code jabiz.mail-layout:<language>} and takes the version the page read
 * ({@code baseVersionNo}); another change in between is a conflict (409). Both need {@value MailPermissions#TEXT_WRITE}
 * and a verified address. The preview writes nothing ({@code POST /api/mail/layouts/preview},
 * {@link MailLayoutController}).
 */
@Configuration
public class MailLayoutProcesses {

    public static final String RESET = "MAIL_LAYOUT_RESET";
    public static final String TEST_SEND = "MAIL_LAYOUT_TEST_SEND";

    /** @param baseVersionNo the version of the layout as read; null when none was stored */
    public record ResetInput(@NotBlank String locale, Integer baseVersionNo) {}

    /**
     * @param kind      {@code CUSTOM} or {@code DEFAULT}: what mail is sent on now
     * @param versionNo the version written, or the current one when nothing needed writing; null when none is stored
     */
    public record LayoutOutput(String locale, String kind, Integer versionNo) {}

    /**
     * @param displayName with the other parts, a draft to send on; all absent: the layout in effect
     */
    public record TestSendInput(@NotBlank String locale, String displayName, String logoUrl, String logoAlt,
        String footer) {

        boolean draft() {
            return displayName != null || logoUrl != null || logoAlt != null || footer != null;
        }

        MailLayout layout() {
            return new MailLayout(displayName, logoUrl, logoAlt, footer);
        }
    }

    private static final String INPUT = "input";
    private static final String CURRENT = "current";
    private static final String OUTPUT = "output";

    /** The name of the lock every write of a language's layout holds. */
    static String lock(String locale) {
        return "jabiz.mail-layout:" + locale;
    }

    static ProcessDefinition<ResetInput, LayoutOutput, ProcessContext> reset(MailLayouts layouts) {
        return ProcessDefinition.define(RESET, 1, ResetInput.class, LayoutOutput.class, ProcessContext.class, pb -> pb
            .description("Returns the mail layout of a language to its default, at once.")
            .permissions(MailPermissions.TEXT_WRITE)
            .requiresVerifiedEmail()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, LayoutOutput.class))
            .compute("Check the language", (metadata, ctx) -> locale(ctx, layouts,
                ctx.get(INPUT, ResetInput.class).locale()))
            .step("Lock the layout", HoldLock.<ProcessContext>exclusive(ctx -> lock(ctx.get(MailTextProcesses.LOCALE,
                Locale.class).getLanguage())))
            .step("Load the layout", current())
            .compute("Return to the default", (metadata, ctx) -> {
                Locale locale = ctx.get(MailTextProcesses.LOCALE, Locale.class);
                EntityInstance current = current(ctx, locale, ctx.get(INPUT, ResetInput.class).baseVersionNo());
                if (current == null || !MailTextEntities.CUSTOM.equals(current.get(MailLayoutEntities.KIND))) {
                    ctx.put(OUTPUT, new LayoutOutput(locale.getLanguage(), MailTextEntities.DEFAULT,
                        current == null ? null : Math.toIntExact(current.version())));
                    return;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(MailLayoutEntities.KIND, MailTextEntities.DEFAULT);
                row.put(MailLayoutEntities.DISPLAY_NAME, null);
                row.put(MailLayoutEntities.LOGO_URL, null);
                row.put(MailLayoutEntities.LOGO_ALT, null);
                row.put(MailLayoutEntities.FOOTER, null);
                row.put(MailLayoutEntities.DEFAULT_HASH, layouts.defaultHash(locale));
                ChangeSet.Target writes = ctx.changes().in(MailLayoutEntities.DATASET).granted(
                    new MailLayoutWriteGrant(locale.getLanguage(), ctx.processSeqId(), false));
                writes.update(MailLayoutEntities.ENTITY, id(current), current.version(), row);
                ctx.put(OUTPUT, new LayoutOutput(locale.getLanguage(), MailTextEntities.DEFAULT,
                    Math.toIntExact(current.version() + 1)));
            }));
    }

    static ProcessDefinition<TestSendInput, MailTextProcesses.TestSendOutput, ProcessContext> testSend(
        MailLayouts layouts) {
        return ProcessDefinition.define(TEST_SEND, 1, TestSendInput.class, MailTextProcesses.TestSendOutput.class,
            ProcessContext.class, pb -> pb
                .description("Sends oneself a sample mail on the mail layout of a language.")
                .permissions(MailPermissions.TEXT_WRITE)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(MailTextProcesses.OUTPUT, MailTextProcesses.TestSendOutput.class))
                .compute("Check the layout", (metadata, ctx) -> {
                    TestSendInput input = ctx.get(INPUT, TestSendInput.class);
                    locale(ctx, layouts, input.locale());
                    ctx.put(MailTextProcesses.TEMPLATE, PlatformMailTemplates.TASK_NOTIFICATION_MAIL);
                    if (input.draft()) {
                        List<Violation> problems = layouts.problems(input.layout());
                        if (!problems.isEmpty()) {
                            throw new ValidationException(problems);
                        }
                        ctx.put(QueueTestMail.DRAFT_LAYOUT, input.layout());
                    }
                })
                .step("Queue the test mail", MailTextProcesses.QUEUE_TEST));
    }

    /** The language of the input, put into the context; 400 when it is not one of the application's. */
    private static Locale locale(ProcessContext ctx, MailLayouts layouts, String language) {
        Locale locale = language == null ? null : Locale.of(language.strip());
        if (!layouts.language(locale)) {
            throw new ValidationException(List.of(new Violation("locale", PlatformErrorCodes.INVALID_VALUE,
                language + " is not one of the application's languages", Map.of("field", "locale"))));
        }
        ctx.put(MailTextProcesses.LOCALE, locale);
        return locale;
    }

    private static StepSpec<QueryEntities.Metadata<ProcessContext>, ProcessContext> current() {
        return QueryEntities.of(MailLayoutEntities.DATASET, ctx -> Rbac.all(new QueryPredicate.Eq(
            MailLayoutEntities.LOCALE, ctx.get(MailTextProcesses.LOCALE, Locale.class).getLanguage()),
            MailLayoutEntities.LOCALE), CURRENT);
    }

    /** The stored layout, if any, after checking that it is the version the change is based on (409 otherwise). */
    @SuppressWarnings("unchecked")
    private static EntityInstance current(ProcessContext ctx, Locale locale, Integer baseVersionNo) {
        List<EntityInstance> found = ctx.contains(CURRENT) ? (List<EntityInstance>) ctx.get(CURRENT) : List.of();
        EntityInstance current = found.isEmpty() ? null : found.getFirst();
        Long now = current == null ? null : current.version();
        Long base = baseVersionNo == null ? null : baseVersionNo.longValue();
        if (!Objects.equals(now, base)) {
            throw ConcurrentUpdateException.stale("The mail layout [" + locale.getLanguage() + "] is at version " + now
                + ", not " + base + ": somebody changed it meanwhile");
        }
        return current;
    }

    private static Object id(EntityInstance instance) {
        return instance.id() instanceof UUID ? instance.id() : UUID.fromString(String.valueOf(instance.id()));
    }

    @Bean
    ProcessDefinition<ResetInput, LayoutOutput, ProcessContext> mailLayoutResetProcess(MailLayouts layouts) {
        return reset(layouts);
    }

    @Bean
    ProcessDefinition<TestSendInput, MailTextProcesses.TestSendOutput, ProcessContext> mailLayoutTestSendProcess(
        MailLayouts layouts) {
        return testSend(layouts);
    }
}
