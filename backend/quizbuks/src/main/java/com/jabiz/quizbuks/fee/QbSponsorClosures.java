package com.jabiz.quizbuks.fee;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * F-M15 b (docs/quizbuks/plans/impl/F-M15.md §4.1): the wind-up of an erased sponsor's platform fee account, one row a
 * sponsor who had money in it (an ordinary table, written only by {@code fee.MoneyClose}'s processes).
 *
 * <p>It holds no amounts (what was paid, voided and is still owed is summed from the ledger and the payment parts) and no
 * personal text (ids, states and reason codes only), so the erasure need not, and cannot, declare it.
 */
public final class QbSponsorClosures {

    public static final String CLOSURE = "QbSponsorClosure";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:sponsor-closure-status";
    public static final String REASON_DICTIONARY = "urn:jabiz:dict:quizbuks:sponsor-closure-reason";

    /** Registered, not wound up yet. */
    public static final String WAITING = "WAITING";
    /** Wound up: nothing held, the invoices paid as far as the account went, the granted credit voided. */
    public static final String CLOSED = "CLOSED";
    public static final List<String> STATUSES = List.of(WAITING, CLOSED);

    /** Why the last run did not finish, or finished with something owed. */
    public static final String SWITCH_OFF = "SWITCH_OFF";
    public static final String DEPOSIT_OPEN = "DEPOSIT_OPEN";
    public static final String FEE_OWED = "FEE_OWED";
    /** F-M15c: more of the erased sponsor's AI jobs may be open than a run cancels; the next run goes on. */
    public static final String AI_JOBS_OPEN = "AI_JOBS_OPEN";
    public static final List<String> REASONS = List.of(SWITCH_OFF, DEPOSIT_OPEN, FEE_OWED, AI_JOBS_OPEN);

    /** Why a manual credit or a fee request under review ended without a decision: its sponsor was erased. */
    public static final String ENDED_ERASED = "ERASED";

    private QbSponsorClosures() {}

    public static final EntityDefinition CLOSURE_ENTITY = EntityDefinition.define(CLOSURE, eb -> {
        eb.physicalTable("qb_sponsor_closure");
        eb.primaryKey("closureId");
        eb.field("closureId", f -> f.physicalColumn("closure_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:sponsor-closure"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("reasonCode", f -> f.physicalColumn("reason_code").processOnly()
            .asCode(REASON_DICTIONARY, REASONS.toArray(String[]::new)));
        eb.field("erasedAt", f -> f.physicalColumn("erased_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("lastRunAt", f -> f.physicalColumn("last_run_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("closedAt", f -> f.physicalColumn("closed_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        // When finance was given the task that it takes long (once).
        eb.field("alertedAt", f -> f.physicalColumn("alerted_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("sponsorId", "status", "reasonCode", "erasedAt", "lastRunAt", "closedAt")
            .filters("sponsorId", "status", "reasonCode", "erasedAt", "closedAt")
            .sorts("erasedAt", "closedAt")
            .defaultSort("erasedAt", false));
    });
}
