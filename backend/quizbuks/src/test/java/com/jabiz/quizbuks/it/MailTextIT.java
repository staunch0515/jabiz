package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.mail.MailTextEntities;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The texts of QuizBuks's mail (F-C8, platform decision D72) as QuizBuks gives them: a content administrator saves the
 * text of an ordinary notice at once and returns to the default at once; the mail with a token and the notices about
 * money or a bank account change only when another administrator, a finance administrator, publishes what a content
 * administrator proposes; the super administrator, who holds neither side, can do none of it. The platform's
 * {@code MailTextIT} covers the mechanics; this checks that QuizBuks's roles, templates and defaults fit them.
 * Covered by the platform's {@code MailTextIT} (backend/app, mechanics that do not depend on QuizBuks): that a mail sent
 * after a save uses the new text and its delivery record carries the version
 * ({@code anOrdinaryTemplatesTextsAreInEffectOnceSavedAndTheDeliveryRecordsTheirVersion}), that the test mail goes to its
 * operator only, makes no token and is limited ({@code theTestMailGoesToItsOperatorOnlyWithoutATokenAndIsLimited}),
 * and that the same person cannot publish their own proposal ({@code securityTextsChangeOnlyThroughAControlledChangeAnotherPersonPublishes};
 * QuizBuks's {@code qb.sod.control-changes} refuses anyone holding both sides before that, see {@code MailLayoutIT}).
 */
class MailTextIT extends AccountItSupport {

    private static final String TEXTS = "/api/mail/texts";
    private static final String NOTICE = "qb.publication.approved";

    private String content() {
        return tokenOf("fc8-content-admin", QbRoles.ADMIN_CONTENT);
    }

    private String finance() {
        return tokenOf("fc8-finance-admin", QbRoles.ADMIN_FINANCE);
    }

    private String superAdmin() {
        return tokenOf("fc8-super-admin", QbRoles.ADMIN_SUPER);
    }

    /** A save on top of the text now in effect (the tests share one database, and a reset is a version too). */
    private Map<String, Object> save(String template, String subject, String body) {
        Map<String, Object> input = new java.util.HashMap<>(body("template", template, "locale", "en", "subject",
            subject, "body", body));
        input.put("baseVersionNo", english(template).get("versionNo"));
        return input;
    }

    private Map<String, Object> proposal(String template, String subject, String body) {
        return body("targetEntity", MailTextEntities.ENTITY, "reason", "F-C8 wording", "values",
            Map.of("template", template, "locale", "en", "subject", subject, "body", body));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> english(String template) {
        var result = client.get().uri(TEXTS).header(HttpHeaders.AUTHORIZATION, content()).exchange()
            .expectBody(MAP).returnResult();
        assertThat(result.getStatus().value()).as(String.valueOf(result.getResponseBody())).isEqualTo(200);
        Map<String, Object> found = ((List<Map<String, Object>>) result.getResponseBody().get("templates")).stream()
            .filter(candidate -> template.equals(candidate.get("template"))).findFirst().orElseThrow();
        return ((List<Map<String, Object>>) found.get("languages")).stream()
            .filter(language -> "en".equals(language.get("locale"))).findFirst().orElseThrow();
    }

    @Test
    void aContentAdministratorSavesAnOrdinaryNoticeAtOnceAndResetsItAtOnce() {
        Map<String, Object> before = english(NOTICE);
        assertThat(before).containsEntry("state", "DEFAULT");
        String subject = "Good news: {name} is approved";
        run("MAIL_TEXT_SAVE", content(), save(NOTICE, subject, "**{name}** passed its review. {start}"));
        Map<String, Object> after = english(NOTICE);
        assertThat(after).containsEntry("state", "CUSTOM");
        assertThat(after.toString()).contains("Good news");
        run("MAIL_TEXT_RESET", content(), body("template", NOTICE, "locale", "en", "baseVersionNo",
            after.get("versionNo")));
        assertThat(english(NOTICE)).containsEntry("state", "DEFAULT");
    }

    @Test
    void theRulesRefuseWhatAnAdministratorMustNotPutInAMail() {
        // An image would be a tracking pixel; a link to a foreign host, a way out of the site.
        var image = call("MAIL_TEXT_SAVE", content(), save(NOTICE, "Approved: {name}",
            "**{name}** {start} ![x](https://tracker.example.net/p.gif)")).expectBody(MAP).returnResult();
        assertThat(image.getStatus().value()).isEqualTo(400);
        assertThat(codes(image.getResponseBody())).contains("MAIL_TEXT_IMAGE");
        var host = call("MAIL_TEXT_SAVE", content(), save(NOTICE, "Approved: {name}",
            "**{name}** {start} [more](https://evil.example/login)")).expectBody(MAP).returnResult();
        assertThat(codes(host.getResponseBody())).contains("MAIL_TEXT_LINK_HOST");
        assertThat(english(NOTICE)).containsEntry("state", "DEFAULT");
    }

    @Test
    void mailWithATokenAndNoticesAboutMoneyChangeOnlyWhenAFinanceAdministratorPublishes() {
        // The notices that ask for a payment or speak of a payout account carry no token and are protected by
        // declaration; the verification mail carries one.
        for (String template : List.of("qb.bank-account.changed", "qb.transfer.sent", "qb.transfer.failed",
            "qb.payout.action-needed", "qb.fee.invoice-issued", "qb.fee.invoice-overdue", "qb.topup.paid-cash",
            "qb.publication.funds-short", "qb.sponsor-verification.confirm")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> language = english(template);
            assertThat(language).as(template).containsEntry("state", "DEFAULT");
            var saved = call("MAIL_TEXT_SAVE", content(), save(template, String.valueOf(language.get("defaultSubject")),
                String.valueOf(language.get("defaultBody")) + " ")).expectBody(MAP).returnResult();
            assertThat(saved.getStatus().value()).as(template).isEqualTo(422);
            assertThat(codes(saved.getResponseBody())).as(template).contains("MAIL_TEXT_NEEDS_APPROVAL");
        }

        Map<String, Object> language = english("qb.transfer.failed");
        String subject = String.valueOf(language.get("defaultSubject"));
        String text = String.valueOf(language.get("defaultBody")) + "\n\nQuestions? Write to us.";
        // The super administrator holds neither side.
        call("CONTROL_CHANGE_PROPOSE", superAdmin(), proposal("qb.transfer.failed", subject, text))
            .expectStatus().isForbidden();
        String changeId = (String) run("CONTROL_CHANGE_PROPOSE", content(), proposal("qb.transfer.failed", subject,
            text)).get("changeId");
        call("CONTROL_CHANGE_PUBLISH", content(), body("changeId", changeId)).expectStatus().isForbidden();
        call("CONTROL_CHANGE_PUBLISH", superAdmin(), body("changeId", changeId)).expectStatus().isForbidden();
        assertThat(english("qb.transfer.failed")).containsEntry("state", "DEFAULT");

        assertThat(run("CONTROL_CHANGE_PUBLISH", finance(), body("changeId", changeId)))
            .containsEntry("status", "PUBLISHED");
        Map<String, Object> custom = english("qb.transfer.failed");
        assertThat(custom).containsEntry("state", "CUSTOM");
        // Back to the default at once, by the content administrator alone: the shipped text was reviewed.
        run("MAIL_TEXT_RESET", content(), body("template", "qb.transfer.failed", "locale", "en", "baseVersionNo",
            custom.get("versionNo")));
        assertThat(english("qb.transfer.failed")).containsEntry("state", "DEFAULT");
    }

    @Test
    void aTokenIsRefusedOutsideALinkToTheSiteAndInTheSubject() {
        String template = "qb.sponsor-verification.confirm";
        Map<String, Object> language = english(template);
        String subject = String.valueOf(language.get("defaultSubject"));
        // The token in a link that does not start at the site's own address, and in the subject: refused when proposed.
        var elsewhere = call("CONTROL_CHANGE_PROPOSE", content(), proposal(template, subject,
            "{companyName} {domain} {hours}h: [Confirm](/profile/verification/confirm?token={qb.companyEmail})"))
            .expectBody(MAP).returnResult();
        assertThat(elsewhere.getStatus().value()).isEqualTo(422);
        assertThat(codes(elsewhere.getResponseBody()).toString()).contains("MAIL_TEXT_SECRET_PLACEMENT");
        var inSubject = call("CONTROL_CHANGE_PROPOSE", content(), proposal(template, "Code {qb.companyEmail}",
            String.valueOf(language.get("defaultBody")))).expectBody(MAP).returnResult();
        assertThat(inSubject.getStatus().value()).isEqualTo(422);
        assertThat(codes(inSubject.getResponseBody()).toString()).contains("MAIL_TEXT_SECRET_PLACEMENT");
        assertThat(english(template)).containsEntry("state", "DEFAULT");
    }

    @Test
    void theTextsAreOnlyAppended() {
        // The platform's triggers refuse an update and a delete of a stored version (decision D9); the first text is one.
        run("MAIL_TEXT_SAVE", content(), save(NOTICE, "Append check: {name}", "**{name}** {start}"));
        assertThatThrownBy(() -> execute("UPDATE sys_mail_text_version SET is_deleted = true"))
            .hasMessageContaining("append");
        assertThatThrownBy(() -> execute("DELETE FROM sys_mail_text_version")).hasMessageContaining("append");
        Map<String, Object> custom = english(NOTICE);
        run("MAIL_TEXT_RESET", content(), body("template", NOTICE, "locale", "en", "baseVersionNo",
            custom.get("versionNo")));
    }
}
