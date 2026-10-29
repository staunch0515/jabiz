package com.jabiz.quizbuks.prospect;

import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.EntityInstance;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The prospecting mail (Q48c): to a business's published address (platform decision D55, an address that is no
 * user's), through the owner's own mail server (D56). The subject and the text are the approved draft's; the message
 * {@code mail.qb.prospect.body} adds the trial quiz's link, the sender's name, postal address and contact (the law
 * on commercial mail asks for them) and the platform's address-level unsubscribe link. The platform puts the values
 * into the parsed Markdown, so the draft cannot add a link or markup.
 */
public final class ProspectMails {

    public static final MailTemplate PROSPECT = MailTemplate.define("qb.prospect", t -> t
        .category(MailCategory.NOTIFICATION).addressesOnly().entries("sponsor")
        .param("subject", "mailBody", "trialKey", "company", "senderName", "senderAddress", "senderContact"));

    /**
     * The one follow-up (Q48d): the template's own text in the site's language, a reminder of the trial with its link
     * and the sender; no text of Claude's or a person's.
     */
    public static final MailTemplate FOLLOW_UP = MailTemplate.define("qb.prospect.follow-up", t -> t
        .category(MailCategory.NOTIFICATION).addressesOnly().entries("sponsor")
        .param("trialKey", "company", "senderName", "senderAddress", "senderContact"));

    /** The languages the mail's own texts are written in; another site's language gets English. */
    static Locale language(Object language) {
        String code = language == null ? "" : language.toString().toLowerCase(Locale.ROOT);
        return switch (code) {
            case "ja", "zh" -> Locale.forLanguageTag(code);
            default -> Locale.ENGLISH;
        };
    }

    /** The values a template declares, of a prospect and its owner's settings. */
    static Map<String, Function<ProcessContext, ?>> params(MailTemplate template,
        Function<ProcessContext, EntityInstance> prospect, Function<ProcessContext, EntityInstance> setting) {
        Map<String, Function<ProcessContext, ?>> all = params(prospect, setting);
        Map<String, Function<ProcessContext, ?>> declared = new LinkedHashMap<>();
        template.params().forEach(name -> declared.put(name, all.get(name)));
        return declared;
    }

    private static Map<String, Function<ProcessContext, ?>> params(Function<ProcessContext, EntityInstance> prospect,
        Function<ProcessContext, EntityInstance> setting) {
        Map<String, Function<ProcessContext, ?>> params = new LinkedHashMap<>();
        params.put("subject", ctx -> prospect.apply(ctx).get("mailSubject"));
        params.put("mailBody", ctx -> prospect.apply(ctx).get("mailBody"));
        params.put("trialKey", ctx -> prospect.apply(ctx).get("trialKey"));
        params.put("company", ctx -> {
            Object company = prospect.apply(ctx).get("company");
            return company == null ? prospect.apply(ctx).get("domain") : company;
        });
        params.put("senderName", ctx -> setting.apply(ctx).get("senderName"));
        params.put("senderAddress", ctx -> setting.apply(ctx).get("senderAddress"));
        params.put("senderContact", ctx -> setting.apply(ctx).get("senderContact"));
        return params;
    }

    private ProspectMails() {}
}
