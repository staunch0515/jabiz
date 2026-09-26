package com.jabiz.app.it.temporal;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.ConcurrentUpdateException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.RebaseConflictException;
import com.jabiz.app.it.fixture.ItTemporalFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scheduled changes, rebase (decision D1), corrections and cancellations (docs/design/04 sections 3.1, 4, 4.1).
 * Time only moves when the test advances the clock; no job takes part.
 */
class TemporalScheduleIT extends TemporalItSupport {

    private static final Duration DAY = Duration.ofDays(1);

    private static BigDecimal amount(EntityInstance instance) {
        return instance.get("amount");
    }

    /** Acceptance: the old value before the scheduled time, the new one after it, without any job. */
    @Test
    void aScheduledChangeTakesEffectWhenItsTimeComes() {
        EntityInstance price = newPrice(sku(), 100);
        Instant tomorrow = now().plus(DAY);
        EntityInstance scheduled = commit(update(price.id(), 1, attrs("amount", 200), tomorrow)).getFirst();
        assertThat(scheduled.attributes()).containsEntry("effectStartTime", tomorrow);

        assertThat(amount(read(price.id()))).isEqualByComparingTo("100");
        assertThat(queryAll(ItTemporalFixtures.PRICE_DATASET)).filteredOn(p -> p.id().equals(price.id()))
            .singleElement().satisfies(p -> assertThat(amount(p)).isEqualByComparingTo("100"));
        assertThat(amount(read(price.id(), tomorrow))).isEqualByComparingTo("200");

        advance(DAY.minusSeconds(1));
        assertThat(amount(read(price.id()))).isEqualByComparingTo("100");
        advance(Duration.ofSeconds(1));
        assertThat(amount(read(price.id()))).isEqualByComparingTo("200");
        assertThat(read(price.id()).version()).isEqualTo(2);
    }

    /** Acceptance: a change of another field before the scheduled one; both hold afterwards. */
    @Test
    void aScheduledChangeIsRebasedOverAnEarlierChangeOfAnotherField() {
        EntityInstance price = newPrice(sku(), 100);
        Instant tomorrow = now().plus(DAY);
        commit(update(price.id(), 1, attrs("amount", 200), tomorrow));

        advance(Duration.ofHours(1));
        commit(update(price.id(), 1, attrs("note", "promo"), null));

        assertThat(read(price.id()).attributes()).containsEntry("note", "promo");
        assertThat(amount(read(price.id()))).isEqualByComparingTo("100");
        advance(DAY);
        EntityInstance later = read(price.id());
        assertThat(amount(later)).isEqualByComparingTo("200");
        assertThat(later.attributes()).containsEntry("note", "promo");
        assertThat(query("SELECT action, base_version_no, changed_fields::text AS fields FROM op_process_item "
            + "WHERE entity_id = ? AND version_no = 4", price.id()).getFirst())
            .containsEntry("action", "REBASE").containsEntry("base_version_no", 2).containsEntry("fields", "{amount}");
    }

    /** Acceptance: changing the same field before the scheduled change is refused, listing the schedule. */
    @Test
    void anEarlierChangeOfTheSameFieldIsRefused() {
        EntityInstance price = newPrice(sku(), 100);
        commit(update(price.id(), 1, attrs("amount", 200), now().plus(DAY)));
        long scheduleOperation = lastOperation(price.id());

        assertThatThrownBy(() -> commit(update(price.id(), 1, attrs("amount", 150), null)))
            .isInstanceOfSatisfying(RebaseConflictException.class, e -> assertThat(e.conflicts()).singleElement()
                .satisfies(c -> {
                    assertThat(c.versionNo()).isEqualTo(2);
                    assertThat(c.processSeqId()).isEqualTo(scheduleOperation);
                    assertThat(c.effectiveFrom()).isEqualTo(now().plus(DAY));
                    assertThat(c.fields()).containsExactly("amount");
                }));
        assertThat(versions(price.id())).hasSize(2);
    }

    /** Acceptance: several scheduled versions are rebased one after the other. */
    @Test
    void severalScheduledVersionsAreRebasedInOrder() {
        EntityInstance price = newPrice(sku(), 100);
        Instant t1 = now().plus(DAY);
        Instant t2 = now().plus(DAY.multipliedBy(2));
        commit(update(price.id(), 1, attrs("amount", 200), t1));
        commit(update(price.id(), 2, attrs("status", "ACTIVE"), t2));

        commit(update(price.id(), 1, attrs("note", "n"), null));

        assertThat(read(price.id(), t1).attributes()).containsEntry("note", "n").containsEntry("status", "DRAFT");
        assertThat(amount(read(price.id(), t1))).isEqualByComparingTo("200");
        assertThat(read(price.id(), t2).attributes()).containsEntry("note", "n").containsEntry("status", "ACTIVE");
        assertThat(amount(read(price.id(), t2))).isEqualByComparingTo("200");
        assertThat(versions(price.id())).hasSize(6);
    }

    /** Acceptance: a scheduled deletion stays a deletion after an earlier change. */
    @Test
    void aScheduledDeletionStaysADeletion() {
        EntityInstance price = newPrice(sku(), 100);
        Instant tomorrow = now().plus(DAY);
        commit(delete(price.id(), 1, tomorrow));

        commit(update(price.id(), 1, attrs("note", "until tomorrow"), null));

        assertThat(read(price.id()).attributes()).containsEntry("note", "until tomorrow");
        assertThat(read(price.id(), tomorrow)).isNull();
        advance(DAY);
        assertThat(read(price.id())).isNull();
        // A deletion cannot slip under a scheduled change either.
        EntityInstance other = newPrice(sku(), 100);
        commit(update(other.id(), 1, attrs("amount", 1), now().plus(DAY)));
        assertThatThrownBy(() -> commit(delete(other.id(), 1, null))).isInstanceOf(RebaseConflictException.class);
    }

    /** Acceptance: a correction of the past rebases the versions that took effect after it. */
    @Test
    void aCorrectionRebasesVersionsAlreadyInEffect() {
        EntityInstance price = newPrice(sku(), 100);
        Instant created = now();
        advance(Duration.ofHours(2));
        commit(update(price.id(), 1, attrs("amount", 120), null));
        advance(Duration.ofHours(2));

        commit(ItTemporalFixtures.PRICE_DATASET, ADMIN, "typo in the note",
            update(price.id(), 1, attrs("note", "corrected"), created.plus(Duration.ofHours(1))));

        EntityInstance current = read(price.id());
        assertThat(current.attributes()).containsEntry("note", "corrected");
        assertThat(amount(current)).isEqualByComparingTo("120");
        assertThat(read(price.id(), created).attributes()).containsEntry("note", null);
        assertThat(query("SELECT reason FROM op_process WHERE process_seq_id = ?", lastOperation(price.id())))
            .containsExactly(Map.of("reason", "typo in the note"));
    }

    /** Acceptance: at the same effective time the later recording wins; knownAt shows what was known then. */
    @Test
    void aLaterRecordingAtTheSameEffectiveTimeWinsAndKnownAtShowsTheOldValue() {
        EntityInstance price = newPrice(sku(), 100);
        Instant created = now();
        advance(Duration.ofHours(3));
        Instant beforeCorrection = now();
        advance(Duration.ofHours(1));

        commit(ItTemporalFixtures.PRICE_DATASET, ADMIN, "wrong amount entered",
            update(price.id(), 1, attrs("amount", 90), created));

        assertThat(amount(read(price.id()))).isEqualByComparingTo("90");
        assertThat(amount(read(price.id(), created))).isEqualByComparingTo("90");
        assertThat(amount(read(ItTemporalFixtures.PRICE_DATASET, price.id(), created, beforeCorrection)))
            .isEqualByComparingTo("100");
        assertThat(amount(read(ItTemporalFixtures.PRICE_DATASET, price.id(), null, beforeCorrection)))
            .isEqualByComparingTo("100");
    }

    @Test
    void correctionsNeedThePermissionAndAReason() {
        EntityInstance price = newPrice(sku(), 100);
        Instant created = now();
        advance(Duration.ofHours(1));

        assertThatThrownBy(() -> commit(ItTemporalFixtures.PRICE_DATASET, TEST_REQUEST, "reason",
            update(price.id(), 1, attrs("amount", 90), created)))
            .isInstanceOfSatisfying(PermissionDeniedException.class,
                e -> assertThat(e.permission()).isEqualTo("temporal.backdate"));
        assertThatThrownBy(() -> commit(update(price.id(), 1, attrs("amount", 90), created)))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("REASON_REQUIRED"));
        assertThat(versions(price.id())).hasSize(1);
    }

    @Test
    void theCallerMustHaveReadTheVersionInEffectAtTheEffectiveTime() {
        EntityInstance price = newPrice(sku(), 100);
        Instant tomorrow = now().plus(DAY);
        commit(update(price.id(), 1, attrs("amount", 200), tomorrow));

        // After tomorrow version 2 is in effect; basing a change on version 1 is stale.
        assertThatThrownBy(() -> commit(update(price.id(), 1, attrs("note", "x"), tomorrow.plus(DAY))))
            .isInstanceOf(ConcurrentUpdateException.class);
        assertThat(commit(update(price.id(), 2, attrs("note", "x"), tomorrow.plus(DAY))).getFirst().version())
            .isEqualTo(3);
    }

    /** docs/design/04 section 4.1: cancelling returns to the previous state and is itself recorded. */
    @Test
    void aScheduledChangeCanBeCancelled() {
        EntityInstance price = newPrice(sku(), 100);
        Instant t1 = now().plus(DAY);
        Instant t2 = now().plus(DAY.multipliedBy(2));
        commit(update(price.id(), 1, attrs("amount", 200), t1));
        commit(update(price.id(), 2, attrs("status", "ACTIVE"), t2));

        EntityInstance cancelled = commit(cancel(price.id(), 2, t1)).getFirst();
        assertThat(amount(cancelled)).isEqualByComparingTo("100");

        assertThat(amount(read(price.id(), t1))).isEqualByComparingTo("100");
        assertThat(amount(read(price.id(), t2))).isEqualByComparingTo("100");
        assertThat(read(price.id(), t2).attributes()).containsEntry("status", "ACTIVE");
        assertThat(query("SELECT action FROM op_process_item WHERE entity_id = ? ORDER BY version_no", price.id()))
            .extracting(r -> r.get("action")).containsExactly("INSERT", "UPDATE", "UPDATE", "CANCEL", "REBASE");

        // Review finding: a cancelled schedule cannot be cancelled again.
        assertThatThrownBy(() -> commit(cancel(price.id(), 4, t1)))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("NOT_SCHEDULED"));

        // The cancelled schedule no longer blocks changes of the amount.
        assertThat(commit(update(price.id(), 1, attrs("amount", 110), null))).hasSize(1);
        assertThat(amount(read(price.id(), t2))).isEqualByComparingTo("110");
    }

    @Test
    void onlyScheduledVersionsCanBeCancelled() {
        EntityInstance price = newPrice(sku(), 100);
        assertThatThrownBy(() -> commit(cancel(price.id(), 1, now())))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("NOT_SCHEDULED"));
        assertThatThrownBy(() -> commit(cancel(price.id(), 1, now().plus(DAY))))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("NOT_SCHEDULED"));

        UUID later = UUID.randomUUID();
        commit(insert(later, attrs("sku", sku(), "region", "JP"), now().plus(DAY)));
        assertThat(read(later)).isNull();
        // Cancelling a scheduled insertion leaves nothing.
        assertThat(commit(cancel(later, 1, now().plus(DAY)))).isEmpty();
        assertThatThrownBy(() -> commit(cancel(later, 2, now().plus(DAY))))
            .isInstanceOf(BusinessRuleViolationException.class);
        advance(DAY.multipliedBy(2));
        assertThat(read(later)).isNull();
    }

    @Test
    void entityChecksApplyToRebasedCopies() {
        EntityInstance price = newPrice(sku(), 500);
        Instant later = now().plus(Duration.ofDays(1));
        commit(update(price.id(), 1, attrs("amount", 2000), later));
        long versions = query("SELECT 1 FROM it_price WHERE price_id = ?", price.id()).size();

        // Valid now (500 is cheap), but the rebased copy of the schedule would say "cheap" at 2000.
        assertThatThrownBy(() -> commit(update(price.id(), 1, attrs("note", "cheap"), null)))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(Violation::ruleCode).containsExactly(ItTemporalFixtures.CHEAP_NOTE));
        assertThat(query("SELECT 1 FROM it_price WHERE price_id = ?", price.id())).hasSize((int) versions);
        assertThat((Object) read(price.id()).get("note")).isNull();
    }
}
