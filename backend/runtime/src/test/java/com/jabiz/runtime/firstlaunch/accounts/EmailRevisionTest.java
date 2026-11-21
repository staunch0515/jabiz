package com.jabiz.runtime.firstlaunch.accounts;

import com.jabiz.runtime.firstlaunch.accounts.EmailRevision.Outcome;
import com.jabiz.runtime.firstlaunch.accounts.EmailRevision.Recorded;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The one change the waiting phase takes from the configuration: a corrected address of an account not accepted. */
class EmailRevisionTest {

    private static final UUID A = UUID.fromString("0190a1b2-0000-7000-8000-00000000000a");
    private static final UUID C = UUID.fromString("0190a1b2-0000-7000-8000-00000000000c");

    private static final List<Recorded> RECORDED = List.of(
        new Recorded("A", A, "a.suzuki@example.co.jp", true),
        new Recorded("C", C, "c.yamda@example.co.jp", false));

    private static Map<String, String> configured(String a, String c) {
        Map<String, String> emails = new LinkedHashMap<>();
        emails.put("A", a);
        emails.put("C", c);
        return emails;
    }

    @Test
    void theSameAddressesChangeNothing() {
        Outcome outcome = EmailRevision.compare(RECORDED, configured("A.Suzuki@example.co.jp ",
            "c.yamda@example.co.jp"), false);
        assertThat(outcome.unchanged()).isTrue();
        assertThat(outcome.refusals()).isEmpty();
    }

    @Test
    void anAccountNotAcceptedGetsItsCorrectedAddress() {
        Outcome outcome = EmailRevision.compare(RECORDED, configured("a.suzuki@example.co.jp",
            "c.yamada@example.co.jp"), false);
        assertThat(outcome.refusals()).isEmpty();
        assertThat(outcome.revisions()).containsExactly(new EmailRevision.Revision("C", C, "c.yamada@example.co.jp"));
    }

    @Test
    void anAcceptedAccountsAddressIsNotChangedFromTheConfiguration() {
        Outcome outcome = EmailRevision.compare(RECORDED, configured("a.other@example.co.jp",
            "c.yamada@example.co.jp"), false);
        assertThat(outcome.revisions()).isEmpty();
        assertThat(outcome.refusals()).singleElement().asString().contains("A").contains("accepted");
    }

    @Test
    void anyOtherDifferenceRefusesEverything() {
        Outcome other = EmailRevision.compare(RECORDED, configured("a.suzuki@example.co.jp",
            "c.yamada@example.co.jp"), true);
        assertThat(other.revisions()).isEmpty();
        assertThat(other.refusals()).isNotEmpty();
        Map<String, String> added = configured("a.suzuki@example.co.jp", "c.yamda@example.co.jp");
        added.put("D", "d@example.co.jp");
        assertThat(EmailRevision.compare(RECORDED, added, false).refusals()).singleElement().asString().contains("D");
        assertThat(EmailRevision.compare(RECORDED, Map.of("A", "a.suzuki@example.co.jp"), false).refusals())
            .singleElement().asString().contains("C");
    }

    /** A place whose user was deleted is left out: the other accounts' corrections still apply (review of #738). */
    @Test
    void aPlaceWhoseUserWasDeletedIsNotCompared() {
        UUID run = UUID.fromString("0190a1b2-0000-7000-8000-0000000000f1");
        com.jabiz.firstlaunch.FirstLaunchConfig config = new com.jabiz.firstlaunch.FirstLaunchConfig("h", Map.of(),
            "en", List.of("example.co.jp"), List.of(
                new com.jabiz.firstlaunch.FirstLaunchAccount("a", "a.suzuki@example.co.jp", "A", null, List.of("R"),
                    true),
                new com.jabiz.firstlaunch.FirstLaunchAccount("c", "c.yamada@example.co.jp", "C", null, List.of("R"),
                    false)), new com.jabiz.firstlaunch.FirstLaunchConfig.Files() {
                @Override
                public byte[] read(String relativePath) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean exists(String relativePath) {
                    return false;
                }
            });
        List<FirstLaunchAccountStore.Recorded> accounts = List.of(
            new FirstLaunchAccountStore.Recorded(run, A, 0, List.of("R"), true),
            new FirstLaunchAccountStore.Recorded(run, C, 1, List.of("R"), false));
        Map<String, String> emails = FirstLaunchRevisions.configuredEmails(config, accounts, java.util.Set.of(C));
        assertThat(emails).containsOnlyKeys("#2");
        Outcome outcome = EmailRevision.compare(List.of(new Recorded("#2", C, "c.yamda@example.co.jp", false)), emails,
            false);
        assertThat(outcome.refusals()).isEmpty();
        assertThat(outcome.revisions()).containsExactly(new EmailRevision.Revision("#2", C, "c.yamada@example.co.jp"));
    }
}
