package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-app notice of a new version of a legal text (Q7c, the item Q7b left for it; Q7-12: no mass mail): published
 * with {@code announce}, a version is announced to those it concerns (the takers' terms to the app, the privacy policy
 * to the app and the sponsor console), sent by the minute's job, as a system announcement everyone matched sees
 * (registered later too); without it, nothing is announced. A class of its own: once texts are in effect, money and
 * contract actions wait for consent.
 */
class LegalAnnounceIT extends InboxItSupport {

    private String legalAdmin() {
        return TestTokens.bearer(tokens, "legal-admin", QbPermissions.ADMIN_CONTENT_WRITE);
    }

    private Map<String, Object> publish(String kind, boolean announce) {
        Map<String, Object> draft = LegalItSupport.draftOf(kind);
        return run("QB_LEGAL_PUBLISH", legalAdmin(), body("docId", String.valueOf(draft.get("doc_id")),
            "requiresConsent", true, "baseRevision", ((Number) draft.get("version_no")).longValue(), "announce",
            announce, "effectiveAt", clock.instant().plus(java.time.Duration.ofDays(7)).toString()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aNewVersionIsAnnouncedInTheInboxesItConcernsOnlyWhenAsked() {
        String taker = newTaker(unique("t-legal"), "JP");
        String sponsor = newSponsor(unique("s-legal"));
        Map<String, Object> quiet = publish("TERMS_APP", false);
        assertThat((List<Object>) quiet.get("announcements")).isEmpty();

        Map<String, Object> privacy = publish("PRIVACY", true);
        List<Object> ids = (List<Object>) privacy.get("announcements");
        assertThat(ids).hasSize(2);
        assertThat(ids).allSatisfy(id -> assertThat(broadcast(id)).containsEntry("status", "SCHEDULED")
            .containsEntry("type", "SYSTEM").containsEntry("sender_kind", "PLATFORM"));
        runDispatch();
        assertThat(ids).allSatisfy(id -> assertThat(broadcast(id)).containsEntry("status", "SENT"));
        assertThat(inbox(taker(taker))).filteredOn(item -> ids.contains(item.get("itemId"))).singleElement()
            .satisfies(item -> {
                assertThat(String.valueOf(item.get("title"))).contains("Privacy policy", "version 1");
                assertThat(String.valueOf(item.get("title"))).contains("プライバシーポリシー", "隐私政策");
                assertThat(String.valueOf(item.get("body"))).contains("JST");
            });
        assertThat(itemIds(consoleInbox(console(sponsor)))).containsAnyElementsOf(ids);
        // Someone who registers later still sees it (a system announcement, not a promotion).
        String later = newTaker(unique("t-legal-later"), "JP");
        assertThat(itemIds(inbox(taker(later)))).containsAnyElementsOf(ids);
    }
}
