package com.jabiz.runtime.firstlaunch.accounts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The only change the first launch takes from its configuration once the content phase is done (plan F-LAUNCH section
 * 6.3; decision D82): the corrected address of an account that has not accepted its invitation. Any other difference -
 * an accepted account's address, an account added or removed, anything else in the configuration - refuses all of it:
 * the rest is done through the administration as usual. Pure.
 */
public final class EmailRevision {

    private EmailRevision() {}

    /** An account of the run, with its address now and whether its invitation was accepted. */
    public record Recorded(String key, UUID userId, String email, boolean accepted) {
        public Recorded {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(userId, "userId must not be null");
        }
    }

    /** The address to give an account, and to invite it at. */
    public record Revision(String key, UUID userId, String email) {}

    /**
     * @param revisions the addresses to correct (empty when refused)
     * @param refusals  why nothing is done, for the log (empty when accepted)
     */
    public record Outcome(List<Revision> revisions, List<String> refusals) {
        public Outcome {
            revisions = List.copyOf(revisions);
            refusals = List.copyOf(refusals);
        }

        public boolean unchanged() {
            return revisions.isEmpty() && refusals.isEmpty();
        }
    }

    /**
     * Compares the run's accounts with the configuration's addresses ({@code key -> email}).
     *
     * @param otherDifferences whether anything but the accounts' addresses differs from the configuration the run
     *                         recorded
     */
    public static Outcome compare(List<Recorded> recorded, Map<String, String> configured, boolean otherDifferences) {
        List<Revision> revisions = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        if (otherDifferences) {
            refusals.add("the configuration differs in more than the accounts' addresses");
        }
        Set<String> keys = new LinkedHashSet<>(configured.keySet());
        for (Recorded account : recorded) {
            if (!keys.remove(account.key())) {
                refusals.add("the account " + account.key() + " is no longer in the configuration");
                continue;
            }
            String wanted = normalized(configured.get(account.key()));
            if (wanted == null || wanted.equals(normalized(account.email()))) {
                continue;
            }
            if (account.accepted()) {
                refusals.add("the account " + account.key() + " has accepted its invitation: change its address in"
                    + " the administration");
            } else {
                revisions.add(new Revision(account.key(), account.userId(), configured.get(account.key()).strip()));
            }
        }
        keys.forEach(key -> refusals.add("the account " + key + " was not in the configuration the run started with"));
        return refusals.isEmpty() ? new Outcome(revisions, List.of()) : new Outcome(List.of(), refusals);
    }

    private static String normalized(String email) {
        return email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
