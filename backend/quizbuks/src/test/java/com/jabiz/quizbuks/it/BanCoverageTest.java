package com.jabiz.quizbuks.it;

import com.jabiz.process.ProcessAccess;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.process.steps.HoldLock;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-ban-coverage (docs/quizbuks/plans/impl/F-ban-coverage.md §2; first made for the operators, fees and verification
 * by F-M15 a0): every process a person starts that a role which can be banned may run reads the caller's ban first
 * ({@code CallerChecks.refuseBanned}, its step "Load the caller's ban"), or is listed here with why not.
 *
 * <p>The scope comes from the definitions as registered and from {@link QbRoles}, not from a folder: a process is in
 * it unless it is {@code internal()} or {@code subProcessOnly()}, and a taker, a sponsor or an operator's member holds
 * what it asks for. A process only headquarters holds is out of it: those holders are disabled, not banned
 * ({@code BanProcesses}), and a banned person does not sign in. A definition's package is that of the class its
 * output mapper is written in (the operators' acts on publications and bans are written in {@code publishing} and
 * {@code identity}), so failures are reported by it.
 *
 * <p>A process that passes needs the read as its first step (nothing is read or written before it). Two tables hold
 * the rest, each entry with its reason: {@link #BLOCKED_BY_HELPER} (a helper refuses the banned together with
 * something else and tells everything wrong at once, so it is not the standard step) and {@link #EXEMPT}.
 */
class BanCoverageTest extends PlayItSupport {

    static final String BAN_STEP = "Load the caller's ban";
    /** The step every helper in {@link #BLOCKED_BY_HELPER} has: SponsorChecks' "onboarded and not banned". */
    static final String SPONSOR_STEP = "Load the sponsor's onboarding";

    static final String READ_ONLY = "只读";
    static final String OWN_WITHDRAW = "撤回自己的、尚无后果的东西";
    static final String OWN_PREFERENCE = "本人偏好，不影响他人";

    /** A process whose caller is refused for a ban by a shared helper, and the IT that shows it. */
    record Helper(String helper, String where, String it) {}

    /**
     * Reasons are checked by review: each names why a banned person doing this harms no one. Never a way to quiet
     * the test for a process that writes something new for the caller or about others.
     */
    static final Map<String, String> EXEMPT = new TreeMap<>(Map.ofEntries(
        Map.entry("QB_OPERATOR_TEAM", READ_ONLY),
        Map.entry("QB_AUDIENCE_FEE_QUOTE", READ_ONLY),
        // F-M9b: the caller's own AI credit at a glance (buying is refused while banned; the return is too).
        Map.entry("QB_AI_CREDIT_STANDING", READ_ONLY + "：自己的 AI 额度一览，购买与退回在封禁期间照常被拒"),
        Map.entry("QB_PUBLICATION_FUNDS_CHECK", READ_ONLY + "：向导最后一步的试算，不写任何东西"),
        Map.entry("QB_MY_CERTIFICATIONS", READ_ONLY),
        Map.entry("QB_SPONSOR_MY_CERTIFICATES", READ_ONLY),
        Map.entry("QB_REFERRAL_VIEW", READ_ONLY),
        Map.entry("QB_ATTEMPT_RESULT", READ_ONLY),
        Map.entry("QB_SPONSOR_ATTEMPT_RESULT", READ_ONLY),
        Map.entry("QB_BLOCK_ADD", "本人的屏蔽清单，不影响他人"),
        Map.entry("QB_BLOCK_REMOVE", "本人的屏蔽清单，不影响他人"),
        Map.entry("QB_MESSAGE_MARK", "本人收件箱的已读，不影响他人"),
        Map.entry("QB_INBOX_READ_ALL", "本人收件箱的已读，不影响他人"),
        Map.entry("QB_INBOX_SETTINGS_SAVE", OWN_PREFERENCE),
        Map.entry("QB_BROADCAST_CANCEL", OWN_WITHDRAW + "：封禁本身就撤回通知（QB_BROADCAST_BAN_WITHDRAW）"),
        Map.entry("QB_BROADCAST_WITHDRAW", OWN_WITHDRAW + "：封禁本身就撤回通知（QB_BROADCAST_BAN_WITHDRAW）"),
        Map.entry("QB_SPONSOR_PAGE_UNPUBLISH", OWN_WITHDRAW + "：收回自己的页面，封禁本身会下线"),
        Map.entry("QB_SHARED_VIDEO_WITHDRAW", OWN_WITHDRAW + "：收回自己的共享，封禁本身会撤回"),
        Map.entry("QB_TREE_WITHDRAW", OWN_WITHDRAW + "：撤回自己的评审申请，只收回、不产生新效果"),
        Map.entry("QB_TOPUP_BANK_CANCEL", OWN_WITHDRAW + "：只把自己尚无资金的银行充值置为已取消，不过账（计划 §5b）"),
        Map.entry("APPROVAL_DECIDE", "平台流程，应用层不能加检查；残留风险与缓解见计划 §6 和 operations.md")));

    /** The sponsors' processes that also need an approved onboarding, told together with the ban (CLAUDE.md). */
    static final Map<String, Helper> BLOCKED_BY_HELPER = new TreeMap<>(Map.ofEntries(
        Map.entry("QB_BROADCAST_SAVE", new Helper("BroadcastProcesses.sponsorReads",
            "messaging/BroadcastProcesses.java:199", "BroadcastIT")),
        Map.entry("QB_BROADCAST_SEND", new Helper("BroadcastProcesses.sponsorReads",
            "messaging/BroadcastProcesses.java:199", "BroadcastIT")),
        Map.entry("QB_PUBLICATION_SUBMIT", new Helper("PublicationEditing.loadSponsor",
            "publishing/PublicationLifecycle.java:126", "BanIT"))));

    static final String LOCK_FIRST = "先取账户锁、再在锁内读封禁：与封禁流程（同取该锁）串行，不能提到锁之前";

    /**
     * Processes that hold a lock before the ban is read, on purpose: a ban takes the same account lock, so a write
     * that holds it first sees every committed ban and a ban never overlaps the write. The ban step must follow
     * the locks directly (nothing else is read or written before it). A new process takes the standard first step.
     */
    static final Map<String, String> BAN_AFTER_LOCK = new TreeMap<>(Map.ofEntries(
        Map.entry("QB_CONSENT_ACCEPT", LOCK_FIRST),
        Map.entry("QB_PRODUCT_DELETE", LOCK_FIRST),
        Map.entry("QB_PRODUCT_SAVE", LOCK_FIRST),
        Map.entry("QB_PROFILE_ATTRIBUTES_SAVE", LOCK_FIRST),
        Map.entry("QB_PROFILE_SAVE", LOCK_FIRST),
        Map.entry("QB_QUIZ_PRODUCTS_SET", LOCK_FIRST),
        Map.entry("QB_SHARED_VIDEO_SHARE", LOCK_FIRST),
        Map.entry("QB_SPONSOR_APPLY", LOCK_FIRST + "（账户锁，其后是归属锁）"),
        Map.entry("QB_SPONSOR_PAGE_SAVE", LOCK_FIRST),
        Map.entry("QB_SPONSOR_PAGE_SUBMIT", LOCK_FIRST),
        Map.entry("QB_SPONSOR_PROFILE_SAVE", LOCK_FIRST)));

    /** The gaps closed by F-M15 a0 and by this work: found here, so that nothing can drop them from the scope. */
    static final Set<String> CLOSED = Set.of(
        // F-M15 a0
        "QB_FEE_CREDIT_ONBOARDING", "QB_OPERATOR_BILLING_SET", "QB_OPERATOR_BANK_ACCOUNT_SAVE",
        "QB_OPERATOR_WITHDRAWAL_REQUEST", "QB_OPERATOR_WITHDRAWAL_CANCEL", "QB_OPERATOR_MEMBER_DROP",
        "QB_SPONSOR_VERIFICATION_REVOKE",
        // F-ban-coverage (plan §4; BanOperatorActsIT)
        "QB_REDEMPTION_VOID", "QB_DIGITAL_PRIZE_RESOLVE", "QB_SPONSOR_REWARD_METHODS_SET",
        "QB_PROSPECT_SETTINGS_SAVE", "QB_PROSPECT_DIRECTION_SAVE", "QB_PROSPECT_APPROVE", "QB_PROSPECT_DISCARD",
        "QB_PROSPECT_BLOCK_ADD", "QB_PROSPECT_MAIL_SERVER_SET", "QB_PROSPECT_MAIL_SERVER_REMOVE",
        "QB_REGION_EXCEPTION_ADD", "QB_REGION_EXCEPTION_EXPIRY_SET", "QB_REGION_EXCEPTION_REVOKE",
        "QB_SHARED_VIDEO_TAKEDOWN", "QB_HELP_REPLY", "QB_AI_TRANSLATION_CHECK_REQUEST",
        "QB_OPERATOR_PUBLICATION_PAUSE", "QB_OPERATOR_PUBLICATION_RESUME", "QB_OPERATOR_PUBLICATION_END",
        "QB_OPERATOR_SPONSOR_PAGE_TAKEDOWN", "QB_OPERATOR_PRODUCT_TAKEDOWN", "QB_OPERATOR_PRODUCT_RESTORE",
        "QB_OPERATOR_REPORT_RESOLVE", "QB_OPERATOR_SPONSOR_BAN", "QB_OPERATOR_SPONSOR_UNBAN",
        "QB_TREE_COPY", "QB_TREE_ADOPT", "QB_TREE_RETIRE", "QB_EXPOSURE_REPORT",
        "QB_SETTINGS_SAVE", "QB_CERTIFICATE_SHARE_SET", "QB_SPONSOR_CERTIFICATE_SHARE_SET");

    /** How many processes the scope held when it was written; a new one only raises it. */
    static final int MIN_INVENTORY = 219;

    @Test
    void everyProcessAPersonStartsReadsTheCallersBanOrIsListed() {
        List<Set<String>> roles = List.of(permissionsOf(QbRoles.TAKER), permissionsOf(QbRoles.SPONSOR),
            permissionsOf(QbRoles.OPERATOR));
        Map<String, ProcessDefinition<?, ?, ?>> started = new TreeMap<>();
        Set<String> nowhere = new TreeSet<>();
        for (ProcessDefinition<?, ?, ?> definition : processes.all()) {
            if (definition.access() == ProcessAccess.INTERNAL || definition.access() == ProcessAccess.SUB_PROCESS_ONLY
                || !heldByABannableRole(definition, roles)) {
                continue;
            }
            if (packageOf(definition).isEmpty()) {
                nowhere.add(definition.name());
            }
            started.put(definition.name(), definition);
        }
        // No definition slips out of the scope unseen: each has a package, and the scope does not shrink.
        assertThat(nowhere).as("definitions whose package is not known").isEmpty();
        assertThat(started).as("the scope").hasSizeGreaterThanOrEqualTo(MIN_INVENTORY);
        assertThat(started.keySet()).as("the scope").containsAll(CLOSED);

        Map<String, Set<String>> missing = new TreeMap<>();
        Set<String> checkedAndListed = new TreeSet<>();
        Set<String> notFirst = new TreeSet<>();
        Set<String> lockedButFirst = new TreeSet<>();
        started.forEach((name, definition) -> {
            boolean checked = readsTheBan(definition);
            // Before anything else is read or written (CallerChecks).
            if (checked && !BAN_STEP.equals(definition.steps().getFirst().stepName())
                && !(BAN_AFTER_LOCK.containsKey(name) && onlyLocksBeforeTheBan(definition))) {
                notFirst.add(name);
            }
            if (checked && BAN_STEP.equals(definition.steps().getFirst().stepName())
                && BAN_AFTER_LOCK.containsKey(name)) {
                lockedButFirst.add(name);
            }
            boolean listed = EXEMPT.containsKey(name) || BLOCKED_BY_HELPER.containsKey(name);
            if (!checked && !listed) {
                missing.computeIfAbsent(packageOf(definition), p -> new TreeSet<>()).add(name);
            }
            if (checked && listed) {
                checkedAndListed.add(name);
            }
        });
        // Everything wrong is told at once (soft), so a run lists all the processes to fix.
        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(missing).as("started by a person without the caller's ban, by package").isEmpty();
            soft.assertThat(notFirst).as("the caller's ban read after another step").isEmpty();
            soft.assertThat(lockedButFirst).as("in BAN_AFTER_LOCK but the ban is first").isEmpty();
            soft.assertThat(started.keySet()).as("BAN_AFTER_LOCK names processes in the scope")
                .containsAll(BAN_AFTER_LOCK.keySet());
            soft.assertThat(BAN_AFTER_LOCK.keySet()).as("locked first and listed elsewhere")
                .doesNotContainAnyElementsOf(EXEMPT.keySet());
            // A listing that no longer applies is removed from its table.
            soft.assertThat(checkedAndListed).as("listed but checked").isEmpty();
            soft.assertThat(started.keySet()).as("the exemptions name processes in the scope")
                .containsAll(EXEMPT.keySet());
            soft.assertThat(started.keySet()).as("the helper-blocked name processes in the scope")
                .containsAll(BLOCKED_BY_HELPER.keySet());
            soft.assertThat(EXEMPT.keySet()).as("exempt and blocked by a helper")
                .doesNotContainAnyElementsOf(BLOCKED_BY_HELPER.keySet());
            EXEMPT.forEach((name, reason) -> soft.assertThat(reason).as("the reason of " + name).isNotBlank());
        });
    }

    /** Each process a helper blocks has the helper's step, and an IT that shows the ban refusing it. */
    @Test
    void theProcessesAHelperBlocksAreBlockedAndTested() throws Exception {
        Path tests = Path.of("src/test/java/com/jabiz/quizbuks/it");
        for (Map.Entry<String, Helper> entry : BLOCKED_BY_HELPER.entrySet()) {
            ProcessDefinition<?, ?, ?> definition = processes.findLatest(entry.getKey()).orElseThrow(
                () -> new AssertionError(entry.getKey() + " is no process"));
            assertThat(definition.steps().stream().map(step -> step.stepName()))
                .as(entry.getKey() + " by " + entry.getValue().helper() + " (" + entry.getValue().where() + ")")
                .contains(SPONSOR_STEP);
            Path it = tests.resolve(entry.getValue().it() + ".java");
            assertThat(it).as("the IT of " + entry.getKey()).exists();
            // Some assert it by its constant (AccountCodes.USER_BANNED), some by its code (QB_USER_BANNED); the
            // process is named there too (by its code), so the IT is about this process and not another's ban.
            String source = Files.readString(it);
            assertThat(source).as(entry.getValue().it() + " asserts the ban for " + entry.getKey())
                .contains("USER_BANNED").contains("\"" + entry.getKey() + "\"");
        }
    }

    /** Whether every step before the ban's is a lock: its handler is the platform's {@link HoldLock}. */
    static boolean onlyLocksBeforeTheBan(ProcessDefinition<?, ?, ?> definition) {
        for (var step : definition.steps()) {
            if (BAN_STEP.equals(step.stepName())) {
                return true;
            }
            if (!HoldLock.class.equals(step.handlerClass())) {
                return false;
            }
        }
        return false;
    }

    static boolean readsTheBan(ProcessDefinition<?, ?, ?> definition) {
        return definition.steps().stream().anyMatch(step -> BAN_STEP.equals(step.stepName()));
    }

    static Set<String> permissionsOf(String role) {
        return Set.copyOf(QbRoles.of(role).permissions());
    }

    /** Whether a taker, a sponsor or an operator's member holds what starting the process takes (either set). */
    static boolean heldByABannableRole(ProcessDefinition<?, ?, ?> definition, List<Set<String>> roles) {
        return roles.stream().anyMatch(held -> held.containsAll(definition.permissions())
            || (!definition.alternativePermissions().isEmpty() && held.containsAll(definition.alternativePermissions())));
    }

    static String packageOf(ProcessDefinition<?, ?, ?> definition) {
        List<Object> written = new java.util.ArrayList<>();
        written.add(definition.outputMapper());
        written.add(definition.contextFactory());
        return written.stream().filter(java.util.Objects::nonNull).map(o -> o.getClass().getPackageName())
            .findFirst().orElse("");
    }
}
