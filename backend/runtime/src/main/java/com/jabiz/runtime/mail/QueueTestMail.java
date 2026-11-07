package com.jabiz.runtime.mail;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailRenderer;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.steps.EventPublisher;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The step of {@code MAIL_TEXT_TEST_SEND} (decision D72): renders the draft, or the texts in effect, with the
 * template's sample values and tokens that open nothing ({@link MailTexts#SAMPLE_TOKEN}: no hash of it is stored, so
 * no {@code sys_mail_token} row exists for it), and queues the result as a transactional message to the operator's own
 * address, the subject marked {@value MailTextProcesses#TEST_PREFIX}. The delivery sends a rendered message as it is
 * ({@code DeliverMail}): it draws no tokens and is in nobody's preferences. The address is the operator's account's,
 * never one the request names; the process requires it verified.
 */
@Component
class QueueTestMail implements StepHandler<NoMetadata, ProcessContext> {

    private final MailTexts texts;
    private final ObjectProvider<EventPublisher> publisher;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    QueueTestMail(MailTexts texts, ObjectProvider<EventPublisher> publisher, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.texts = texts;
        this.publisher = publisher;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            MailTextProcesses.TestSendInput input = ctx.get(MailTextProcesses.INPUT,
                MailTextProcesses.TestSendInput.class);
            MailTemplate template = ctx.get(MailTextProcesses.TEMPLATE, MailTemplate.class);
            Locale locale = ctx.get(MailTextProcesses.LOCALE, Locale.class);
            Mono<MailTexts.Text> text = input.subject() != null
                ? Mono.just(new MailTexts.Text(input.subject(), input.body(), null, false))
                : texts.effective(template, locale);
            return address(ctx).zipWith(text).flatMap(found -> {
                String address = found.getT1();
                MailTexts.Text chosen = found.getT2();
                MailRenderer.Rendered mail = texts.render(template, chosen.subject(), chosen.body(),
                    texts.sampleValues(template));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("template", template.name());
                // Transactional whatever the template is: the test reaches its operator, who asked for it.
                row.put("category", MailCategory.TRANSACTIONAL.name());
                row.put("userId", ctx.request().actorId());
                row.put("address", address);
                row.put("locale", locale.getLanguage());
                row.put("params", "{}");
                row.put("createdTime", ctx.opTime());
                row.put("processSeqId", BigDecimal.valueOf(ctx.processSeqId()));
                row.put("renderedSubject", MailTextProcesses.TEST_PREFIX + mail.subject());
                row.put("renderedText", mail.text());
                row.put("renderedHtml", mail.html());
                Object id = ctx.changes().insert(MailEntities.MESSAGE, row);
                ctx.put(MailTextProcesses.OUTPUT, new MailTextProcesses.TestSendOutput(String.valueOf(id), address));
                EventPublisher target = publisher.getIfUnique();
                if (target == null) {
                    return Mono.error(new IllegalStateException("No EventPublisher is configured"));
                }
                return target.publish(SendMail.QUEUED, new SendMail.Queued(String.valueOf(id)), ctx.processSeqId());
            });
        });
    }

    /**
     * The operator's own verified address; 422 {@code MAIL_NO_ADDRESS} for an operator without one, whose current
     * address is not the verified one (changed since the access token was issued), or who is no user.
     */
    private Mono<String> address(ProcessContext ctx) {
        String actor = ctx.request().actorId();
        UUID user;
        try {
            user = UUID.fromString(String.valueOf(actor));
        } catch (IllegalArgumentException e) {
            return Mono.error(noAddress(actor));
        }
        return storages.getEngine(poolRef).select("SELECT email, verified_email, email_verified_at, is_deleted"
                + " FROM sec_user_version"
                + " WHERE user_id = :user ORDER BY version_no DESC LIMIT 1", Map.of("user", BoundValue.of(user)))
            .next()
            .filter(row -> !Boolean.TRUE.equals(row.get("is_deleted")) && row.get("email_verified_at") != null
                && row.get("email") != null
                && String.valueOf(row.get("email")).equalsIgnoreCase(String.valueOf(row.get("verified_email"))))
            .map(row -> Rows.string(row.get("email")))
            .filter(email -> email != null && !email.isBlank())
            .switchIfEmpty(Mono.error(() -> noAddress(actor)));
    }

    private static BusinessRuleViolationException noAddress(String actor) {
        return new BusinessRuleViolationException(new Violation(null, PlatformErrorCodes.MAIL_NO_ADDRESS,
            "The operator " + actor + " has no e-mail address of their own to send the test mail to",
            Map.of("user", String.valueOf(actor))));
    }
}
