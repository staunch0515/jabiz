package com.jabiz.quizbuks.publishing;

import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which submissions set fees aside (F-M5, F-M6; H1 of the F-M6 review): a Sponsor's publication only. A shop's
 * storefront publication (S1) and a challenge set nothing aside and freeze no distribution price, so the settlement
 * jobs, which take only publications with a frozen price (and only a Sponsor's), never charge them.
 */
class PublicationPrepaymentsTest {

    static EntityInstance publication(String publisherKind, Boolean forSponsors) {
        Map<String, Object> values = new HashMap<>();
        values.put("publisherKind", publisherKind);
        values.put("forSponsors", forSponsors);
        return new EntityInstance("p", "QbPublication", 1, null, values);
    }

    @Test
    void onlyASponsorsPublicationThatIsNoChallengeSetsAnythingAside() {
        assertThat(PublicationPrepayments.concerned(publication(PublisherKind.SPONSOR, false))).isTrue();
        assertThat(PublicationPrepayments.concerned(publication(PublisherKind.SPONSOR, null))).isTrue();
        assertThat(PublicationPrepayments.concerned(publication(PublisherKind.SHOPIFY_SHOP, false))).isFalse();
        assertThat(PublicationPrepayments.concerned(publication(PublisherKind.SPONSOR, true))).isFalse();
        assertThat(PublicationPrepayments.concerned(null)).isFalse();
    }
}
