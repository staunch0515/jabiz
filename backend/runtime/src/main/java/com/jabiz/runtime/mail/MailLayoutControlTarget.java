package com.jabiz.runtime.mail;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailLayout;
import com.jabiz.process.ChangeSet;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.approval.ControlChanges;
import com.jabiz.runtime.approval.ControlTarget;
import com.jabiz.runtime.security.MfaPolicy;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.security.VerifiedEmailPolicy;
import com.jabiz.security.MfaRequirement;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The mail layout of a language as the target of controlled changes (decision D72 item 3; target entity
 * {@value MailLayoutEntities#ENTITY}, the "mail-layout" control): the layout is on every mail, verification and
 * password reset included, so it changes only when another administrator publishes the change. A change gives
 * {@code values.locale}, {@code values.displayName}, {@code values.logoUrl}, {@code values.logoAlt} and
 * {@code values.footer}; it is checked by the {@code MailLayoutRules} when proposed and again when published, and
 * records the version it was proposed against: when the layout has changed since (a reset, another change published),
 * the publication is refused ({@code CONTROL_TARGET_CHANGED}). Proposing and publishing need {@code mail.text.write},
 * a verified address and a recent second factor.
 *
 * <p>Going back to the default is not a controlled change: {@code MAIL_LAYOUT_RESET} does it at once, as the default
 * is configuration, reviewed with the deployment.
 */
@Component
class MailLayoutControlTarget implements ControlTarget {

    /** The fields a change gives. */
    static final Set<String> WRITABLE = Set.of(MailLayoutEntities.LOCALE, MailLayoutEntities.DISPLAY_NAME,
        MailLayoutEntities.LOGO_URL, MailLayoutEntities.LOGO_ALT, MailLayoutEntities.FOOTER);
    /** Recorded with the proposal: the version it was proposed against, absent when there was none. */
    static final String BASED_ON = "basedOnVersionNo";

    private final MailLayouts layouts;
    private final MfaPolicy mfa;

    MailLayoutControlTarget(MailLayouts layouts, MfaPolicy mfa) {
        this.layouts = layouts;
        this.mfa = mfa;
    }

    @Override
    public Set<String> entities() {
        return Set.of(MailLayoutEntities.ENTITY);
    }

    @Override
    public String dataset(String entity) {
        return MailLayoutEntities.DATASET;
    }

    @Override
    public Mono<Object> load(Request request, ProcessContext ctx) {
        return Mono.defer(() -> {
            String locale = text(request.values(), MailLayoutEntities.LOCALE);
            return locale == null ? Mono.empty() : layouts.stored(Locale.of(locale)).map(Object.class::cast);
        });
    }

    @Override
    public Proposal propose(Request request, Object loaded, ProcessContext ctx) {
        requireWriter(ctx, "Proposing a change of the mail layout");
        mfa.require(ctx.request(), MfaRequirement.ADMINISTRATION, "Proposing a change of the mail layout");
        if (request.targetId() != null || request.delete() || request.effectiveTime() != null) {
            ctx.reject(invalid("a mail layout is named by values.locale; it is neither deleted (MAIL_LAYOUT_RESET "
                + "returns to the default) nor scheduled"));
            return null;
        }
        MailLayouts.Stored based = (MailLayouts.Stored) loaded;
        Map<String, Object> stored = check(request, ctx);
        if (ctx.hasViolations()) {
            return null;
        }
        if (based != null) {
            stored.put(BASED_ON, based.versionNo());
        }
        // Shown and filtered by in the list of changes (17zn): the language whose layout changes.
        return new Proposal(based == null ? null : based.id(), stored, (String) stored.get(MailLayoutEntities.LOCALE));
    }

    @Override
    public Object publish(Request request, Object loaded, ProcessContext ctx, ChangeSet.Target writes) {
        requireWriter(ctx, "Publishing a change of the mail layout");
        MailLayouts.Stored based = (MailLayouts.Stored) loaded;
        Object recordedBase = request.values().get(BASED_ON);
        Integer basedOn = recordedBase instanceof Number number ? number.intValue() : null;
        Integer now = based == null ? null : based.versionNo();
        if (!Objects.equals(basedOn, now) || !Objects.equals(request.targetId(), based == null ? null : based.id())) {
            ctx.reject(new Violation("changeId", PlatformErrorCodes.CONTROL_TARGET_CHANGED, "The mail layout "
                + "changed after this change was proposed; propose it again",
                Map.of("locale", String.valueOf(request.values().get(MailLayoutEntities.LOCALE)))));
            return null;
        }
        Map<String, Object> stored = check(request, ctx);
        if (ctx.hasViolations()) {
            return null;
        }
        Locale locale = Locale.of((String) stored.get(MailLayoutEntities.LOCALE));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(MailLayoutEntities.KIND, MailTextEntities.CUSTOM);
        for (String field : WRITABLE) {
            if (!MailLayoutEntities.LOCALE.equals(field)) {
                row.put(field, stored.get(field));
            }
        }
        row.put(MailLayoutEntities.DEFAULT_HASH, layouts.defaultHash(locale));
        ChangeSet.Target granted = writes.granted(new MailLayoutWriteGrant(locale.getLanguage(), ctx.processSeqId(),
            true));
        if (based == null) {
            row.put(MailLayoutEntities.LOCALE, locale.getLanguage());
            return granted.insert(MailLayoutEntities.ENTITY, row);
        }
        granted.update(MailLayoutEntities.ENTITY, UUID.fromString(based.id()), based.versionNo(), row);
        return based.id();
    }

    /** Checks a change, rejecting what is wrong with it in {@code ctx}; the values to record. */
    private Map<String, Object> check(Request request, ProcessContext ctx) {
        Map<String, Object> given = request.values();
        Map<String, Object> stored = new LinkedHashMap<>();
        new TreeSet<>(given.keySet()).stream().filter(field -> !WRITABLE.contains(field) && !BASED_ON.equals(field))
            .forEach(field -> ctx.reject(invalid("field '" + field + "' cannot be set")));
        String language = text(given, MailLayoutEntities.LOCALE);
        if (language == null || !layouts.language(Locale.of(language))) {
            ctx.reject(invalid("values.locale is not one of the application's languages"));
            return stored;
        }
        MailLayout layout = new MailLayout(string(given, MailLayoutEntities.DISPLAY_NAME),
            string(given, MailLayoutEntities.LOGO_URL), string(given, MailLayoutEntities.LOGO_ALT),
            string(given, MailLayoutEntities.FOOTER));
        layouts.problems(layout).forEach(ctx::reject);
        layouts.unknownEntries(layout).forEach(entry -> ctx.reject(new Violation(MailLayoutEntities.FOOTER,
            PlatformErrorCodes.MAIL_TEXT_UNKNOWN_PLACEHOLDER, "The footer names the sign-in entry " + entry
            + ", which is not configured", Map.of("placeholder", "entryUrl." + entry))));
        if (layout.equals(layouts.defaults(Locale.of(language)))) {
            ctx.reject(invalid("the layout is the default; MAIL_LAYOUT_RESET returns to it"));
        }
        stored.put(MailLayoutEntities.LOCALE, language);
        stored.put(MailLayoutEntities.DISPLAY_NAME, layout.displayName());
        stored.put(MailLayoutEntities.LOGO_URL, layout.logoUrl());
        stored.put(MailLayoutEntities.LOGO_ALT, layout.logoAlt());
        stored.put(MailLayoutEntities.FOOTER, layout.footer());
        return stored;
    }

    private static String string(Map<String, ?> values, String field) {
        return values != null && values.get(field) instanceof String text ? text : "";
    }

    private static String text(Map<String, ?> values, String field) {
        return values != null && values.get(field) instanceof String text && !text.isBlank() ? text : null;
    }

    private static Violation invalid(String detail) {
        return ControlChanges.invalid("values", detail);
    }

    private static void requireWriter(ProcessContext ctx, String what) {
        Permissions.require(ctx.request(), MailPermissions.TEXT_WRITE, what);
        VerifiedEmailPolicy.require(ctx.request(), true, what);
    }
}
