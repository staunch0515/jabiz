package com.jabiz.quizbuks;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.play.PlayCodes;
import com.jabiz.quizbuks.setup.ReviewCodes;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three languages have the same texts (backend/quizbuks/CLAUDE.md section 4): the platform's startup check covers
 * the rule codes; this covers every key, and the content codes of processes as well.
 */
class MessagesTest {

    private static Properties load(String language) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(MessagesTest.class.getResourceAsStream(
            "/messages_" + language + ".properties")), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @Test
    void everyLanguageHasEveryText() throws IOException {
        Properties en = load("en");
        for (String language : List.of("zh", "ja")) {
            assertThat(load(language).stringPropertyNames()).as(language)
                .containsExactlyInAnyOrderElementsOf(en.stringPropertyNames());
        }
        assertThat(en.stringPropertyNames()).containsAll(ContentCodes.ALL).containsAll(PublishingCodes.ALL)
            .contains("approval.subject.qb.publication", "qb.publication.copy-name")
            .containsAll(AccountCodes.ALL).containsAll(ReviewCodes.ALL)
            .contains("approval.subject.qb.sponsor", "task.qb.sponsor.resubmit", "process.QB_PROFILE_SAVE",
                "process.QB_SPONSOR_APPLY", "process.QB_SPONSOR_PROFILE_SAVE", "process.QB_SPONSOR_APPROVAL_RESULT",
                "process.QB_USER_BAN", "process.QB_USER_UNBAN", "mail.qb.welcome.subject", "mail.qb.welcome.body",
                "mail.qb.sponsor.approved.subject", "mail.qb.sponsor.approved.body",
                "mail.qb.sponsor.rejected.subject", "mail.qb.sponsor.rejected.body", "query.qb.me.sign-ins",
                "query.qb.admin.users", "query.qb.admin.sponsors", "entity.QbProfile", "entity.QbSponsor",
                "entity.QbUserBan", "param.qb.review.sponsor.auto");
        assertThat(en.stringPropertyNames()).containsAll(PlayCodes.ALL)
            .contains("process.QB_VIEW", "process.QB_ATTEMPT_START", "process.QB_ATTEMPT_SAVE",
                "process.QB_ATTEMPT_SUBMIT", "process.QB_ATTEMPT_EXPIRE", "process.QB_PENDING_SETTLE",
                "process.QB_PENDING_PAY", "process.QB_PUBLICATION_FUNDS_RESTORED", "process.QB_PUBLICATION_FUNDED",
                "process.QB_PUBLICATION_STATS_REFRESH", "process.QB_WALLET_DEV_CREDIT", "entity.QbAttempt",
                "entity.QbPublicationCard", "entity.QbPublicationView", "entity.QbPublicationStats",
                "entity.QbWallet", "query.qb.app.feed", "query.qb.app.publication",
                "query.qb.app.publication-tiers", "query.qb.app.history", "query.qb.admin.attempts",
                "query.qb.admin.wallets", "query.qb.system.publication-stats");
        assertThat(en.stringPropertyNames()).allSatisfy(key -> assertThat(en.getProperty(key)).as(key).isNotBlank());
    }
}
