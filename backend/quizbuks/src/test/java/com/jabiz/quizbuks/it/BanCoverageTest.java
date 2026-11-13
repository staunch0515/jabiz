package com.jabiz.quizbuks.it;

import com.jabiz.process.ProcessAccess;
import com.jabiz.process.ProcessDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M15 a0 (docs/quizbuks/plans/impl/F-M15.md §5.4-6): every process of the {@code operator}, {@code fee} and
 * {@code verification} packages that a person starts reads the caller's ban first ({@code CallerChecks.refuseBanned},
 * its step "Load the caller's ban"), or is exempt here with its reason. A process a person starts is one neither
 * {@code internal()} nor {@code subProcessOnly()}. A new one that forgets the ban fails this test.
 *
 * <p>A definition's package is that of the class it is written in: its output mapper is a lambda or a method
 * reference written there (the operators' acts on publications and bans the operators' configuration registers are
 * written in {@code publishing} and {@code identity}, outside this inventory).
 */
class BanCoverageTest extends PlayItSupport {

    static final String BAN_STEP = "Load the caller's ban";
    static final Set<String> PACKAGES = Set.of("com.jabiz.quizbuks.operator", "com.jabiz.quizbuks.fee",
        "com.jabiz.quizbuks.verification");

    /**
     * Processes outside those packages that are started by a person and spend their money (F-M8: the exposures' top-up,
     * written in {@code publishing}, whose other processes check the ban in their own way).
     */
    static final Set<String> ALSO = Set.of("QB_EXPOSURE_TOP_UP");

    /** Headquarters' holders are disabled, not banned (BanProcesses), and a banned person does not sign in. */
    static final String HQ = "总部专属";
    static final String READ_ONLY = "只读";
    static final Map<String, String> EXEMPT = Map.ofEntries(
        Map.entry("QB_OPERATOR_SAVE", HQ),
        Map.entry("QB_OPERATOR_MEMBER_ADD", HQ),
        Map.entry("QB_OPERATOR_MEMBER_REMOVE", HQ),
        Map.entry("QB_SPONSOR_OPERATOR_ASSIGN", HQ),
        Map.entry("QB_REVIEW_SPOT_CHECK", HQ),
        Map.entry("QB_OPERATOR_PLAN_SET", HQ),
        Map.entry("QB_OPERATOR_SETTLEMENT_STATEMENT", HQ),
        Map.entry("QB_OPERATOR_SETTLEMENT_PAY", HQ),
        Map.entry("QB_OPERATOR_WITHDRAWAL_REJECT", HQ),
        Map.entry("QB_OPERATOR_WITHDRAWAL_PAY", HQ),
        Map.entry("QB_FEE_REQUEST", HQ),
        Map.entry("QB_AUDIENCE_REFUND_CLOSE", HQ),
        Map.entry("QB_OPERATOR_TEAM", READ_ONLY));

    /** The gaps §5.4 closed: found here, so that the package rule above cannot miss them. */
    static final Set<String> CLOSED = Set.of("QB_FEE_CREDIT_ONBOARDING", "QB_OPERATOR_BILLING_SET",
        "QB_OPERATOR_BANK_ACCOUNT_SAVE", "QB_OPERATOR_WITHDRAWAL_REQUEST", "QB_OPERATOR_WITHDRAWAL_CANCEL",
        "QB_OPERATOR_MEMBER_DROP", "QB_SPONSOR_VERIFICATION_REVOKE");

    /** How many processes the inventory found when it was written (F-M15 a0); a new one only raises it. */
    static final int MIN_INVENTORY = 29;

    @Test
    void everyProcessAPersonStartsReadsTheCallersBanOrIsExempt() {
        Map<String, ProcessDefinition<?, ?, ?>> started = new TreeMap<>();
        Set<String> nowhere = new TreeSet<>();
        for (ProcessDefinition<?, ?, ?> definition : processes.all()) {
            if (packageOf(definition).isEmpty()) {
                nowhere.add(definition.name());
            }
            if ((PACKAGES.contains(packageOf(definition)) || ALSO.contains(definition.name()))
                && definition.access() != ProcessAccess.INTERNAL
                && definition.access() != ProcessAccess.SUB_PROCESS_ONLY) {
                started.put(definition.name(), definition);
            }
        }
        // No definition slips out of the inventory unseen: each has a package, and the inventory does not shrink.
        assertThat(nowhere).as("definitions whose package is not known").isEmpty();
        assertThat(started).as("the inventory").hasSizeGreaterThanOrEqualTo(MIN_INVENTORY);
        assertThat(started.keySet()).as("the inventory").containsAll(CLOSED).containsAll(ALSO);

        Set<String> missing = new TreeSet<>();
        Set<String> checkedAndExempt = new TreeSet<>();
        Set<String> notFirst = new TreeSet<>();
        started.forEach((name, definition) -> {
            boolean checked = readsTheBan(definition);
            // Before anything else is read or written (CallerChecks).
            if (checked && !BAN_STEP.equals(definition.steps().getFirst().stepName())) {
                notFirst.add(name);
            }
            if (!checked && !EXEMPT.containsKey(name)) {
                missing.add(name);
            }
            if (checked && EXEMPT.containsKey(name)) {
                checkedAndExempt.add(name);
            }
        });
        assertThat(missing).as("started by a person without the caller's ban").isEmpty();
        assertThat(notFirst).as("the caller's ban read after another step").isEmpty();
        // An exemption that no longer applies is removed from the table.
        assertThat(checkedAndExempt).as("exempt but checked").isEmpty();
        assertThat(started.keySet()).as("the exemptions name processes of these packages").containsAll(EXEMPT.keySet());
    }

    static boolean readsTheBan(ProcessDefinition<?, ?, ?> definition) {
        return definition.steps().stream().anyMatch(step -> BAN_STEP.equals(step.stepName()));
    }

    static String packageOf(ProcessDefinition<?, ?, ?> definition) {
        List<Object> written = new java.util.ArrayList<>();
        written.add(definition.outputMapper());
        written.add(definition.contextFactory());
        return written.stream().filter(java.util.Objects::nonNull).map(o -> o.getClass().getPackageName())
            .findFirst().orElse("");
    }
}
