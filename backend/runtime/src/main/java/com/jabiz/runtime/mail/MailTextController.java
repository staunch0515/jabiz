package com.jabiz.runtime.mail;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailRenderer;
import com.jabiz.mail.MailTemplate;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.security.Permissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reading the mail texts (decision D72), with {@value MailPermissions#TEXT_READ}; neither endpoint writes anything,
 * not even an operation record:
 * <ul>
 *   <li>{@code GET /api/mail/texts}: every template with what its texts may use and must keep, whether changes need
 *       approval, and per language the default, the stored override and its state;</li>
 *   <li>{@code POST /api/mail/texts/preview} ({@code MAIL_TEXT_PREVIEW}): a draft (or, without one, the texts in
 *       effect) checked by the rules and rendered with the template's sample values and tokens that open nothing,
 *       on the mail layout in effect.
 *       The HTML is the mail's own, which a page shows in a sandboxed frame without scripts.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/mail/texts")
class MailTextController {

    /** The state of a language's texts. */
    enum State {
        /** No override: the default is sent. */
        DEFAULT,
        /** The override is sent. */
        CUSTOM,
        /** The override is sent, but the default changed since it was written. */
        DEFAULT_UPDATED,
        /** The override no longer passes the rules: the default is sent. */
        INVALID,
        /**
         * The override was saved without approval, which its template needs now (it came to have a token, a sign-in
         * link or {@code protectedText()}): the default is sent until a change is approved.
         */
        UNAPPROVED
    }

    /**
     * @param versionNo the latest stored version (an override or a return to the default); null when none
     * @param subject   the override's subject; null without one
     * @param problems  why the override is not sent ({@link State#INVALID}); empty otherwise
     */
    record LanguageTexts(String locale, String defaultSubject, String defaultBody, String defaultHash, State state,
        Integer versionNo, String subject, String body, List<Violation> problems) {}

    /**
     * @param needsApproval whether changes go through a controlled change another administrator publishes
     * @param placeholders  what subject and body may use (besides {@code {entryUrl.<entry>}})
     * @param required      what they must keep
     * @param secrets       what may stand only in links to the application
     */
    record TemplateTexts(String template, String name, String category, boolean needsApproval,
        List<String> placeholders, List<String> required, List<String> secrets, List<LanguageTexts> languages) {}

    record TextsResponse(int maxSubjectLength, int maxBodyLength, List<String> allowedLinkHosts,
        List<TemplateTexts> templates) {}

    /** @param subject with {@code body} a draft; both absent: the texts in effect */
    record MailTextPreviewRequest(String template, String locale, String subject, String body) {}

    /**
     * @param versionNo the version of the override previewed; null for a draft or the default
     * @param problems  the draft's problems by the rules (the preview renders it all the same)
     */
    record MailTextPreviewResponse(String subject, String text, String html, Integer versionNo,
        List<Violation> problems) {}

    private final MailTemplates templates;
    private final MailTexts texts;
    private final MailLayouts layouts;
    private final MessageCatalog messages;

    MailTextController(MailTemplates templates, MailTexts texts, MailLayouts layouts, MessageCatalog messages) {
        this.templates = templates;
        this.texts = texts;
        this.layouts = layouts;
        this.messages = messages;
    }

    @GetMapping
    Mono<TextsResponse> list() {
        return RequestContexts.current().flatMap(request -> {
            Permissions.require(request, MailPermissions.TEXT_READ, "Reading the mail texts");
            Locale shown = messages.supported(request.locale());
            return texts.allStored().map(stored -> {
                List<TemplateTexts> list = new ArrayList<>();
                Map<String, MailTemplate> byName = new LinkedHashMap<>();
                templates.all().forEach(template -> byName.putIfAbsent(template.name(), template));
                List<MailTemplate> declared = new ArrayList<>(byName.values());
                declared.sort(Comparator.comparing(MailTemplate::name));
                for (MailTemplate template : declared) {
                    List<LanguageTexts> languages = new ArrayList<>();
                    for (Locale locale : messages.supportedLocales()) {
                        languages.add(language(template, locale, stored.get(MailTexts.key(template.name(),
                            locale.getLanguage()))));
                    }
                    List<String> required = new ArrayList<>(template.params());
                    required.addAll(template.tokens().keySet());
                    if (template.unsubscribable()) {
                        required.add(MailTemplate.UNSUBSCRIBE_URL);
                    }
                    list.add(new TemplateTexts(template.name(), messages.find(template.nameKey(), shown)
                        .orElse(template.name()), template.category().name(), template.textChangeNeedsApproval(),
                        List.copyOf(template.placeholders()), List.copyOf(required),
                        List.copyOf(template.secretPlaceholders()), List.copyOf(languages)));
                }
                return new TextsResponse(texts.limits().maxSubjectLength(), texts.limits().maxBodyLength(),
                    texts.limits().allowedLinkHosts().stream().sorted().toList(), List.copyOf(list));
            });
        });
    }

    private LanguageTexts language(MailTemplate template, Locale locale, MailTexts.Stored stored) {
        Optional<MailTexts.Text> defaults = texts.defaults(template, locale);
        String hash = texts.defaultHash(template, locale);
        String defaultSubject = defaults.map(MailTexts.Text::subject).orElse(null);
        String defaultBody = defaults.map(MailTexts.Text::body).orElse(null);
        if (stored == null || !stored.custom()) {
            return new LanguageTexts(locale.getLanguage(), defaultSubject, defaultBody, hash, State.DEFAULT,
                stored == null ? null : stored.versionNo(), null, null, List.of());
        }
        List<Violation> problems = texts.problems(template, stored.subject(), stored.body());
        State state = !problems.isEmpty() ? State.INVALID
            : MailTexts.unapproved(template, stored) ? State.UNAPPROVED
            : hash.equals(stored.defaultHash()) ? State.CUSTOM : State.DEFAULT_UPDATED;
        return new LanguageTexts(locale.getLanguage(), defaultSubject, defaultBody, hash, state, stored.versionNo(),
            stored.subject(), stored.body(), problems);
    }

    @PostMapping("/preview")
    Mono<MailTextPreviewResponse> preview(@RequestBody MailTextPreviewRequest body) {
        return RequestContexts.current().flatMap(request -> {
            Permissions.require(request, MailPermissions.TEXT_READ, "Previewing a mail text");
            MailTemplate template = templates.find(body.template()).orElseThrow(() -> invalid("template",
                body.template() + " is not a mail template"));
            Locale locale = body.locale() == null ? null : Locale.of(body.locale().strip());
            if (!texts.language(locale)) {
                throw invalid("locale", body.locale() + " is not one of the application's languages");
            }
            if ((body.subject() == null) != (body.body() == null)) {
                throw new ValidationException(List.of(new Violation(body.subject() == null ? "subject" : "body",
                    PlatformErrorCodes.REQUIRED, "A draft gives its subject and its body")));
            }
            Mono<MailTexts.Text> text = body.subject() != null
                ? Mono.just(new MailTexts.Text(body.subject(), body.body(), null, false))
                : texts.effective(template, locale);
            return Mono.zip(text, layouts.effective(locale)).map(found -> {
                MailTexts.Text chosen = found.getT1();
                MailRenderer.Rendered mail = layouts.wrap(texts.render(template, chosen.subject(), chosen.body(),
                    texts.sampleValues(template)), found.getT2().layout());
                List<Violation> problems = body.subject() == null ? List.of()
                    : texts.problems(template, chosen.subject(), chosen.body());
                return new MailTextPreviewResponse(mail.subject(), mail.text(), mail.html(), chosen.versionNo(),
                    problems);
            });
        });
    }

    private static ValidationException invalid(String field, String detail) {
        return new ValidationException(List.of(new Violation(field, PlatformErrorCodes.INVALID_VALUE, detail,
            Map.of("field", field))));
    }
}
