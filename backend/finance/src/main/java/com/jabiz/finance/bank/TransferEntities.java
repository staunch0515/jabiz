package com.jabiz.finance.bank;

import com.jabiz.entity.EntityDefinition;

import java.util.List;

/**
 * Money moved between the company's own bank accounts (FIN-BK-002): one transfer, one number, the source credited and
 * the target debited. Received the day it is sent, it is one entry; received later, the money is in transit in
 * between: an entry when it leaves (to the in-transit account) and one when it arrives. Its entries are found by its
 * number among the postings ({@code FinPosting}), as every subledger document's.
 */
public final class TransferEntities {

    public static final String TRANSFER = "FinBankTransfer";
    public static final String TRANSFER_DATASET = "urn:jabiz:dataset:default:FinBankTransfer";
    public static final String STATUSES = "urn:jabiz:dict:finance:bank-transfer-status";

    /** Sent and not yet received; received; taken back. */
    public static final String IN_TRANSIT = "IN_TRANSIT";
    public static final String COMPLETED = "COMPLETED";
    public static final String VOID = "VOID";
    public static final List<String> STATUS_VALUES = List.of(IN_TRANSIT, COMPLETED, VOID);

    public static final EntityDefinition TRANSFER_ENTITY = EntityDefinition.define(TRANSFER, eb -> {
        eb.physicalTable("fi_bank_transfer_version");
        eb.primaryKey("transferId");
        eb.field("transferId", f -> f.physicalColumn("transfer_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:finance:bank-transfer"));
        eb.field("transferNo", f -> f.physicalColumn("transfer_no").immutable(true).required(true).asText(20));
        eb.field("fromBank", f -> f.physicalColumn("from_bank").immutable(true).required(true).asText(20));
        eb.field("toBank", f -> f.physicalColumn("to_bank").immutable(true).required(true).asText(20));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asNumeric(15, 2));
        eb.field("currency", f -> f.physicalColumn("currency").immutable(true).required(true).asText(3));
        eb.field("sentDate", f -> f.physicalColumn("sent_date").immutable(true).required(true).asDate());
        eb.field("receivedDate", f -> f.physicalColumn("received_date").processOnly().asDate());
        eb.field("description", f -> f.physicalColumn("description").immutable(true).asText(500));
        // The in-transit account the money left to: where it is taken from on arrival, whatever the settings say then.
        eb.field("inTransitAccount", f -> f.physicalColumn("in_transit_account").immutable(true).asText(20));
        eb.field("status", f -> f.physicalColumn("status").processOnly().required(true)
            .asCode(STATUSES, IN_TRANSIT, COMPLETED, VOID));
        eb.field("preparedBy", f -> f.physicalColumn("prepared_by").immutable(true).asText(100));
        eb.field("voidDate", f -> f.physicalColumn("void_date").processOnly().asDate());
        eb.field("voidReason", f -> f.physicalColumn("void_reason").processOnly().asText(500));
        eb.unique("uk_fi_bank_transfer_no", "transferNo");
        eb.display("transferNo");
        eb.temporal(t -> t.allowScheduled(false));
        eb.listView("default", lv -> lv
            .columns("transferNo", "fromBank", "toBank", "amount", "sentDate", "receivedDate", "status",
                "description")
            .filters("transferNo", "fromBank", "toBank", "status", "sentDate")
            .sorts("sentDate", "transferNo")
            .defaultSort("sentDate", false));
    });

    private TransferEntities() {}
}
