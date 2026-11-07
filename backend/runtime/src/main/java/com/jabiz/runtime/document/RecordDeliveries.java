package com.jabiz.runtime.document;

import com.jabiz.document.DocumentRecipients;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.mail.MailRenderer;
import com.jabiz.mail.MailTemplate;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.mail.MailLayouts;
import com.jabiz.runtime.mail.MailTexts;
import com.jabiz.runtime.mail.PlatformMailTemplates;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.security.Permissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The first step of {@code DOCUMENT_SEND} (docs/design/22-documents.md section 5): checks the send and records one
 * delivery per address in the process's transaction; {@link SendDocuments} sends them after the commit. Mail must be
 * on, the caller must be able to see the document, and every address must be a plain address - one the document's
 * data names, or any address with {@code document.send.any}. The subject and body are the mail template
 * {@code jabiz.document-mail} in the document's language, read where every mail's texts are (decision D72): an
 * override administrators wrote, else the layout's messages {@code document.<layout>.mail.subject|body}, else the
 * template's; each delivery records the version of the override it was written with.
 */
@Component
class RecordDeliveries implements StepHandler<NoMetadata, ProcessContext> {

    private static final int MAX_SUBJECT = 300;
    private static final int MAX_BODY = 4000;

    private final DocumentRuns runs;
    private final DocumentAccess access;
    private final DocumentDeliveries deliveries;
    private final MessageCatalog messages;
    private final MailTexts texts;
    private final MailLayouts layouts;
    private final EntityIdGenerator ids;
    private final boolean mailEnabled;
    private final boolean development;

    RecordDeliveries(DocumentRuns runs, DocumentAccess access, DocumentDeliveries deliveries, MessageCatalog messages,
        MailTexts texts, MailLayouts layouts, EntityIdGenerator ids,
        @Value("${jabiz.mail.enabled:false}") boolean mailEnabled, Environment environment) {
        this.runs = runs;
        this.access = access;
        this.deliveries = deliveries;
        this.messages = messages;
        this.texts = texts;
        this.layouts = layouts;
        this.ids = ids;
        this.mailEnabled = mailEnabled;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            DocumentProcesses.SendInput input = ctx.get(DocumentProcesses.INPUT, DocumentProcesses.SendInput.class);
            if (!mailEnabled) {
                return Mono.error(new BusinessRuleViolationException(new Violation(null,
                    PlatformErrorCodes.MAIL_DISABLED, "E-mail is not enabled (jabiz.mail.enabled)")));
            }
            UUID runId;
            try {
                runId = UUID.fromString(input.runId());
            } catch (IllegalArgumentException e) {
                return Mono.error(new EntityNotFoundException("Unknown document: " + input.runId()));
            }
            return runs.find(runId)
                .filter(run -> access.visible(run, ctx.request()))
                .switchIfEmpty(Mono.error(new EntityNotFoundException("Unknown document: " + input.runId())))
                .flatMap(run -> record(run, input, ctx));
        });
    }

    private Mono<Void> record(DocumentRun run, DocumentProcesses.SendInput input, ProcessContext ctx) {
        List<String> asked = input.to() == null ? List.of() : input.to();
        // The kept addresses were plain when issued; checked again, so that nothing else ever reaches a header.
        List<String> addresses = asked.isEmpty()
            ? run.recipients().stream().filter(DocumentRecipients::valid).toList() : DocumentRecipients.distinct(
            asked.stream().map(a -> a == null ? "" : a.trim()).toList());
        List<Violation> invalid = new ArrayList<>();
        for (int i = 0; i < asked.size(); i++) {
            if (!DocumentRecipients.valid(asked.get(i) == null ? null : asked.get(i).trim())) {
                invalid.add(new Violation("to[" + i + "]", PlatformErrorCodes.INVALID_VALUE,
                    "not a plain e-mail address"));
            }
        }
        if (addresses.size() > DocumentRecipients.MAX) {
            invalid.add(new Violation("to", PlatformErrorCodes.INVALID_VALUE,
                "at most " + DocumentRecipients.MAX + " addresses"));
        }
        if (!invalid.isEmpty()) {
            return Mono.error(new ValidationException(invalid));
        }
        if (addresses.isEmpty()) {
            return Mono.error(new BusinessRuleViolationException(new Violation("to",
                PlatformErrorCodes.DOCUMENT_NO_RECIPIENT, "No address to send document " + run.runId() + " to")));
        }
        List<String> outside = DocumentRecipients.outside(addresses, run.recipients());
        if (!outside.isEmpty()
            && !Permissions.allowsDeclared(ctx.request(), DocumentPermissions.SEND_ANY, development)) {
            return Mono.error(new BusinessRuleViolationException(new Violation("to",
                PlatformErrorCodes.DOCUMENT_RECIPIENT_NOT_ALLOWED, "The document's data does not name "
                + outside.getFirst(), Map.of("address", outside.getFirst()))));
        }
        Locale language = messages.supported(Locale.forLanguageTag(run.language()));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("title", run.title());
        params.put("number", run.documentNo() == null ? "" : run.documentNo());
        params.put("company", run.content().company() == null ? "" : run.content().company());
        MailTemplate template = PlatformMailTemplates.DOCUMENT_MAIL_TEMPLATE;
        MailTexts.Text defaults = new MailTexts.Text(text(template, run.layoutId(), "subject", language),
            text(template, run.layoutId(), "body", language), null, false);
        List<String> recorded = new ArrayList<>();
        return Mono.zip(texts.effective(template, language, defaults), layouts.effective(language))
            .flatMapMany(found -> {
                MailTexts.Text chosen = found.getT1();
                // The layout's version is recorded now and the mail put on it when sent (decision D72 item 4).
                Integer layoutVersion = found.getT2().versionNo();
                MailRenderer.Rendered mail = texts.render(template, chosen.subject(), chosen.body(), params);
                // The renderer makes the subject one line: whatever the data holds, nothing but it reaches that header.
                String subject = cut(mail.subject(), MAX_SUBJECT);
                String body = cut(mail.text().strip(), MAX_BODY);
                return Flux.fromIterable(addresses).concatMap(address -> {
                    UUID deliveryId = UUID.fromString(String.valueOf(ids.next(null)));
                    recorded.add(deliveryId.toString());
                    return deliveries.insert(deliveryId, run.runId(), address, subject, body, ctx.request().actorId(),
                        ctx.opTime(), ctx.processSeqId(), chosen.versionNo(), layoutVersion);
                });
            })
            .then(Mono.fromRunnable(() -> {
                ctx.put(DocumentProcesses.DELIVERIES, List.copyOf(recorded));
                ctx.put(DocumentProcesses.OUTPUT, new DocumentProcesses.SendOutput(List.copyOf(recorded),
                    List.copyOf(addresses)));
            }));
    }

    /** The default text: {@code document.<layout>.mail.<part>}, else the template's {@code mail.<name>.<part>}. */
    private String text(MailTemplate template, String layoutId, String part, Locale language) {
        return messages.find("document." + layoutId + ".mail." + part, language)
            .or(() -> messages.find("mail." + template.name() + "." + part, language))
            .orElse("{title} {number}");
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
