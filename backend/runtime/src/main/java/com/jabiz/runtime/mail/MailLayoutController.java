package com.jabiz.runtime.mail;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailLayout;
import com.jabiz.mail.MailLayoutRules;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reading the mail layout (decision D72; 17zh-b), with {@value MailPermissions#TEXT_READ}; neither endpoint writes
 * anything:
 * <ul>
 *   <li>{@code GET /api/mail/layouts}: per language the default (configuration), the stored layout and its state;</li>
 *   <li>{@code POST /api/mail/layouts/preview}: a draft (or, without one, the layout in effect) checked by the rules
 *       and a sample mail (the task notification with its sample values) put on it. The HTML is the mail's own, which
 *       a page shows in a sandboxed frame without scripts.</li>
 * </ul>
 * A layout of its own is proposed as a controlled change of {@value MailLayoutEntities#ENTITY}; going back to the
 * default is {@code MAIL_LAYOUT_RESET}.
 */
@RestController
@RequestMapping("/api/mail/layouts")
class MailLayoutController {

    /** The state of a language's layout. */
    enum State {
        /** None stored: mail is sent on the default. */
        DEFAULT,
        /** Mail is sent on the stored layout. */
        CUSTOM,
        /** Mail is sent on the stored layout, but the default changed since it was written. */
        DEFAULT_UPDATED,
        /** The stored layout no longer passes the rules: mail is sent on the default. */
        INVALID
    }

    /**
     * @param versionNo the latest stored version (a layout or a return to the default); null when none
     * @param layout    the stored layout; null without one
     * @param problems  why the stored layout is not used ({@link State#INVALID}); empty otherwise
     */
    record LanguageLayout(String locale, MailLayout defaultLayout, String defaultHash, State state, Integer versionNo,
        MailLayout layout, List<Violation> problems) {}

    record LayoutsResponse(int maxFooterLength, int maxLineLength, List<String> allowedLinkHosts,
        List<LanguageLayout> languages) {}

    /** @param layout a draft; absent: the layout in effect */
    record PreviewRequest(String locale, MailLayout layout) {}

    /**
     * @param versionNo the version of the stored layout previewed; null for a draft or the default
     * @param problems  the draft's problems by the rules (the preview renders it all the same, on what passes)
     */
    record PreviewResponse(String subject, String text, String html, Integer versionNo, List<Violation> problems) {}

    private final MailLayouts layouts;
    private final MailTexts texts;
    private final MessageCatalog messages;

    MailLayoutController(MailLayouts layouts, MailTexts texts, MessageCatalog messages) {
        this.layouts = layouts;
        this.texts = texts;
        this.messages = messages;
    }

    @GetMapping
    Mono<LayoutsResponse> list() {
        return RequestContexts.current().flatMap(request -> {
            Permissions.require(request, MailPermissions.TEXT_READ, "Reading the mail layout");
            return layouts.allStored().map(stored -> {
                List<LanguageLayout> languages = new ArrayList<>();
                for (Locale locale : messages.supportedLocales()) {
                    languages.add(language(locale, stored.get(locale.getLanguage())));
                }
                return new LayoutsResponse(layouts.limits().maxFooterLength(),
                    MailLayoutRules.MAX_LINE,
                    layouts.limits().links().allowedLinkHosts().stream().sorted().toList(), List.copyOf(languages));
            });
        });
    }

    private LanguageLayout language(Locale locale, MailLayouts.Stored stored) {
        MailLayout defaults = layouts.defaults(locale);
        String hash = defaults.hash();
        if (stored == null || !stored.custom()) {
            return new LanguageLayout(locale.getLanguage(), defaults, hash, State.DEFAULT,
                stored == null ? null : stored.versionNo(), null, List.of());
        }
        List<Violation> problems = layouts.problems(stored.layout());
        State state = !problems.isEmpty() ? State.INVALID
            : hash.equals(stored.defaultHash()) ? State.CUSTOM : State.DEFAULT_UPDATED;
        return new LanguageLayout(locale.getLanguage(), defaults, hash, state, stored.versionNo(), stored.layout(),
            problems);
    }

    @PostMapping("/preview")
    Mono<PreviewResponse> preview(@RequestBody PreviewRequest body) {
        return RequestContexts.current().flatMap(request -> {
            Permissions.require(request, MailPermissions.TEXT_READ, "Previewing the mail layout");
            Locale locale = body.locale() == null ? null : Locale.of(body.locale().strip());
            if (!layouts.language(locale)) {
                throw new ValidationException(List.of(new Violation("locale", PlatformErrorCodes.INVALID_VALUE,
                    body.locale() + " is not one of the application's languages", Map.of("field", "locale"))));
            }
            MailTemplate sample = PlatformMailTemplates.TASK_NOTIFICATION_MAIL;
            Mono<MailLayouts.Chosen> layout = body.layout() != null
                ? Mono.just(new MailLayouts.Chosen(body.layout(), null, false)) : layouts.effective(locale);
            return Mono.zip(layout, texts.effective(sample, locale)).map(found -> {
                MailLayout chosen = found.getT1().layout();
                MailTexts.Text text = found.getT2();
                MailRenderer.Rendered mail = layouts.wrap(texts.render(sample, text.subject(), text.body(),
                    texts.sampleValues(sample)), chosen);
                List<Violation> problems = body.layout() == null ? List.of() : layouts.problems(chosen);
                return new PreviewResponse(mail.subject(), mail.text(), mail.html(), found.getT1().versionNo(),
                    problems);
            });
        });
    }
}
