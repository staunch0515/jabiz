package com.jabiz.runtime.mail;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.approval.ControlChanges;
import com.jabiz.runtime.approval.ControlTarget;
import com.jabiz.runtime.security.MfaPolicy;
import com.jabiz.security.MfaRequirement;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The texts of mail whose changes need approval as targets of controlled changes (decision D72 item 3; target entity
 * {@value MailTextEntities#ENTITY}, the "mail-text" control): mail with a one-time token or sign-in link, and
 * templates declared {@code protectedText()}. A change gives {@code values.template}, {@code values.locale},
 * {@code values.subject} and {@code values.body}; it is checked by the {@code MailTextRules} when proposed and again
 * when published, and records the version of the text it was proposed against: when the text has changed since (a
 * reset, another change published), the publication is refused ({@code CONTROL_TARGET_CHANGED}) and the change must
 * be proposed again. What another administrator approves is exactly the recorded subject and body. Proposing needs a
 * recent second factor as publishing does (the controlled change requires it for publishing).
 *
 * <p>Going back to the default is not a controlled change: {@code MAIL_TEXT_RESET} does it at once, as the defaults
 * were reviewed with the code. Templates whose texts change without approval take {@code MAIL_TEXT_SAVE}.
 */
@Component
class MailTextControlTarget implements ControlTarget {

    /** The fields a change gives. */
    static final Set<String> WRITABLE = Set.of(MailTextEntities.TEMPLATE, MailTextEntities.LOCALE,
        MailTextEntities.SUBJECT, MailTextEntities.BODY);
    /** Recorded with the proposal: the version it was proposed against, absent when there was none. */
    static final String BASED_ON = "basedOnVersionNo";

    private final MailTemplates templates;
    private final MailTexts texts;
    private final MfaPolicy mfa;

    MailTextControlTarget(MailTemplates templates, MailTexts texts, MfaPolicy mfa) {
        this.templates = templates;
        this.texts = texts;
        this.mfa = mfa;
    }

    @Override
    public Set<String> entities() {
        return Set.of(MailTextEntities.ENTITY);
    }

    @Override
    public String dataset(String entity) {
        return MailTextEntities.DATASET;
    }

    @Override
    public Mono<Object> load(Request request, ProcessContext ctx) {
        return Mono.defer(() -> {
            String template = text(request.values(), MailTextEntities.TEMPLATE);
            String locale = text(request.values(), MailTextEntities.LOCALE);
            if (template == null || locale == null) {
                return Mono.empty();
            }
            return texts.stored(template, Locale.of(locale)).map(Object.class::cast);
        });
    }

    @Override
    public Proposal propose(Request request, Object loaded, ProcessContext ctx) {
        requireWriter(ctx, "Proposing a change of a mail text");
        mfa.require(ctx.request(), MfaRequirement.ADMINISTRATION, "Proposing a change of a mail text");
        if (request.targetId() != null || request.delete() || request.effectiveTime() != null) {
            ctx.reject(invalid("a mail text is named by values.template and values.locale; it is neither deleted "
                + "(MAIL_TEXT_RESET returns to the default) nor scheduled"));
            return null;
        }
        MailTexts.Stored based = (MailTexts.Stored) loaded;
        Map<String, Object> stored = check(request, ctx);
        if (ctx.hasViolations()) {
            return null;
        }
        if (based != null) {
            stored.put(BASED_ON, based.versionNo());
        }
        // Shown and filtered by in the list of changes (17zn): which mail and language the change is about.
        return new Proposal(based == null ? null : based.id(), stored,
            stored.get(MailTextEntities.TEMPLATE) + " [" + stored.get(MailTextEntities.LOCALE) + "]");
    }

    @Override
    public Object publish(Request request, Object loaded, ProcessContext ctx, ChangeSet.Target writes) {
        requireWriter(ctx, "Publishing a change of a mail text");
        MailTexts.Stored based = (MailTexts.Stored) loaded;
        Object recordedBase = request.values().get(BASED_ON);
        Integer basedOn = recordedBase instanceof Number number ? number.intValue() : null;
        Integer now = based == null ? null : based.versionNo();
        String template = text(request.values(), MailTextEntities.TEMPLATE);
        if (!Objects.equals(basedOn, now) || !Objects.equals(request.targetId(), based == null ? null : based.id())) {
            ctx.reject(new Violation("changeId", PlatformErrorCodes.CONTROL_TARGET_CHANGED, "The text of mail "
                + template + " changed after this change was proposed; propose it again",
                Map.of("template", String.valueOf(template))));
            return null;
        }
        Map<String, Object> stored = check(request, ctx);
        if (ctx.hasViolations()) {
            return null;
        }
        MailTemplate declared = templates.find(template).orElseThrow();
        Locale locale = Locale.of((String) stored.get(MailTextEntities.LOCALE));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(MailTextEntities.KIND, MailTextEntities.CUSTOM);
        row.put(MailTextEntities.SUBJECT, stored.get(MailTextEntities.SUBJECT));
        row.put(MailTextEntities.BODY, stored.get(MailTextEntities.BODY));
        row.put(MailTextEntities.DEFAULT_HASH, texts.defaultHash(declared, locale));
        row.put(MailTextEntities.APPROVED, true);
        ChangeSet.Target granted = writes.granted(new MailTextWriteGrant(template, locale.getLanguage(),
            ctx.processSeqId(), true));
        if (based == null) {
            row.put(MailTextEntities.TEMPLATE, template);
            row.put(MailTextEntities.LOCALE, locale.getLanguage());
            return granted.insert(MailTextEntities.ENTITY, row);
        }
        granted.update(MailTextEntities.ENTITY, UUID.fromString(based.id()), based.versionNo(), row);
        return based.id();
    }

    /**
     * Checks a change, rejecting what is wrong with it in {@code ctx}.
     *
     * @return the values to record: template, language, subject and body
     */
    private Map<String, Object> check(Request request, ProcessContext ctx) {
        Map<String, Object> given = request.values();
        Map<String, Object> stored = new LinkedHashMap<>();
        new TreeSet<>(given.keySet()).stream().filter(field -> !WRITABLE.contains(field) && !BASED_ON.equals(field))
            .forEach(field -> ctx.reject(invalid("field '" + field + "' cannot be set")));
        String name = text(given, MailTextEntities.TEMPLATE);
        String language = text(given, MailTextEntities.LOCALE);
        Optional<MailTemplate> template = name == null ? Optional.empty() : templates.find(name);
        if (template.isEmpty()) {
            ctx.reject(invalid("values.template names no mail template"));
            return stored;
        }
        if (!template.get().textChangeNeedsApproval()) {
            ctx.reject(invalid("the texts of mail " + name + " change without approval, through MAIL_TEXT_SAVE"));
            return stored;
        }
        if (language == null || !texts.language(Locale.of(language))) {
            ctx.reject(invalid("values.locale is not one of the application's languages"));
            return stored;
        }
        String subject = given.get(MailTextEntities.SUBJECT) instanceof String s ? s : null;
        String body = given.get(MailTextEntities.BODY) instanceof String b ? b : null;
        // The texts rules' own codes, so that a page shows the same problems as for MAIL_TEXT_SAVE.
        texts.problems(template.get(), subject, body).forEach(ctx::reject);
        Optional<MailTexts.Text> defaults = texts.defaults(template.get(), Locale.of(language));
        if (defaults.isPresent() && defaults.get().subject().equals(subject) && defaults.get().body().equals(body)) {
            ctx.reject(invalid("the texts are the default; MAIL_TEXT_RESET returns to it"));
        }
        stored.put(MailTextEntities.TEMPLATE, name);
        stored.put(MailTextEntities.LOCALE, language);
        stored.put(MailTextEntities.SUBJECT, subject);
        stored.put(MailTextEntities.BODY, body);
        return stored;
    }

    private static String text(Map<String, ?> values, String field) {
        return values != null && values.get(field) instanceof String text && !text.isBlank() ? text : null;
    }

    private static Violation invalid(String detail) {
        return ControlChanges.invalid("values", detail);
    }

    /**
     * Who proposes or publishes a mail text holds what writing one needs (decision D72 item 1): the controlled
     * change's own permissions are not enough, and the address must be verified, as for MAIL_TEXT_SAVE.
     */
    private static void requireWriter(ProcessContext ctx, String what) {
        com.jabiz.runtime.security.Permissions.require(ctx.request(), MailPermissions.TEXT_WRITE, what);
        com.jabiz.runtime.security.VerifiedEmailPolicy.require(ctx.request(), true, what);
    }
}
