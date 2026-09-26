package com.jabiz.runtime.ledger;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.ledger.Direction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.Currency;

/**
 * The double-entry ledger as temporal platform entities (docs/design/11-ledger-events-jobs.md section 1; decision
 * D14): {@code LedgerAccount}, {@code LedgerTransaction} and its {@code LedgerEntry}s. Every field of a transaction
 * and of an entry is immutable and both are written by the ledger processes only (their datasets are
 * {@code processOnlyWrites}): a transaction is corrected by a reversing transaction, never changed or reverted.
 *
 * <p>The ledger keeps one currency, {@code jabiz.ledger.currency} (default JPY) with {@code jabiz.ledger.scale}
 * decimal places (default 0, at most 4), since a monetary field declares its currency.
 */
@Configuration
public class LedgerEntities {

    public static final String ACCOUNT = "LedgerAccount";
    public static final String TRANSACTION = "LedgerTransaction";
    public static final String ENTRY = "LedgerEntry";
    public static final String ACCOUNT_DATASET = "urn:jabiz:dataset:platform:LedgerAccount";
    public static final String TRANSACTION_DATASET = "urn:jabiz:dataset:platform:LedgerTransaction";
    public static final String ENTRY_DATASET = "urn:jabiz:dataset:platform:LedgerEntry";

    public static final String ACCOUNT_TYPES = "urn:jabiz:dict:ledger:account-type";
    public static final String DIRECTIONS = "urn:jabiz:dict:ledger:direction";

    /** Largest scale of the ledger: the columns are {@code numeric(19,4)}. */
    public static final int MAX_SCALE = 4;

    /** The currency and scale of the ledger's amounts. */
    public record Settings(String currency, int scale) {
        public Settings {
            Currency.getInstance(currency);
            if (scale < 0 || scale > MAX_SCALE) {
                throw new IllegalArgumentException("jabiz.ledger.scale must be between 0 and " + MAX_SCALE);
            }
        }
    }

    public static EntityDefinition account() {
        return EntityDefinition.define(ACCOUNT, eb -> {
            eb.physicalTable("ledger_account_version");
            eb.primaryKey("accountId");
            eb.field("accountId", f -> f.physicalColumn("account_id").immutable(true).required(true).generated(true)
                .asSemanticIdentity("urn:jabiz:entity:ledger:account"));
            eb.field("accountCode", f -> f.physicalColumn("account_code").immutable(true).required(true).asText(50));
            eb.field("accountName", f -> f.physicalColumn("account_name").required(true).asText(200));
            eb.field("accountType", f -> f.physicalColumn("account_type").immutable(true).required(true)
                .asCode(ACCOUNT_TYPES, "ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE"));
            eb.field("enabled", f -> f.physicalColumn("enabled").required(true).asBool());
            eb.unique("uk_ledger_account_code", "accountCode");
            eb.temporal(t -> t.allowScheduled(true));
            eb.listView("default", lv -> lv
                .columns("accountCode", "accountName", "accountType", "enabled")
                .filters("accountCode", "accountType", "enabled")
                .sorts("accountCode")
                .defaultSort("accountCode", true));
        });
    }

    public static EntityDefinition transaction() {
        return EntityDefinition.define(TRANSACTION, eb -> {
            eb.physicalTable("ledger_transaction_version");
            eb.primaryKey("transactionId");
            eb.field("transactionId", f -> f.physicalColumn("transaction_id").immutable(true).required(true)
                .generated(true).asSemanticIdentity("urn:jabiz:entity:ledger:transaction"));
            eb.field("bookingTime", f -> f.physicalColumn("booking_time").immutable(true).required(true)
                .asTemporal(com.jabiz.entity.TemporalRole.EVENT_TIME));
            eb.field("description", f -> f.physicalColumn("description").immutable(true).required(true)
                .asText(500));
            eb.field("reference", f -> f.physicalColumn("reference").immutable(true).asText(100));
            eb.field("reversesTransactionId", f -> f.physicalColumn("reverses_transaction_id").immutable(true)
                .asReference(TRANSACTION));
            // A transaction is reversed at most once (decision D6: advisory lock and check).
            eb.unique("uk_ledger_transaction_reverses", "reversesTransactionId");
            eb.temporal(t -> t.allowScheduled(false));
            eb.publishChanges();
            eb.listView("default", lv -> lv
                .columns("bookingTime", "description", "reference", "reversesTransactionId")
                .filters("bookingTime", "reference", "reversesTransactionId")
                .sorts("bookingTime")
                .defaultSort("bookingTime", false));
        });
    }

    public static EntityDefinition entry(Settings settings) {
        return EntityDefinition.define(ENTRY, eb -> {
            eb.physicalTable("ledger_entry_version");
            eb.primaryKey("entryId");
            eb.field("entryId", f -> f.physicalColumn("entry_id").immutable(true).required(true).generated(true)
                .asSemanticIdentity("urn:jabiz:entity:ledger:entry"));
            eb.field("transactionId", f -> f.physicalColumn("transaction_id").immutable(true).required(true)
                .asReference(TRANSACTION));
            eb.field("accountId", f -> f.physicalColumn("account_id").immutable(true).required(true)
                .asReference(ACCOUNT));
            eb.field("lineNo", f -> f.physicalColumn("line_no").immutable(true).required(true).asNumeric(9, 0));
            eb.field("direction", f -> f.physicalColumn("direction").immutable(true).required(true)
                .asCode(DIRECTIONS, Arrays.stream(Direction.values()).map(Enum::name).toArray(String[]::new)));
            eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true)
                .asMonetary(settings.currency(), settings.scale()));
            eb.temporal(t -> t.allowScheduled(false));
            eb.listView("default", lv -> lv
                .columns("transactionId", "lineNo", "accountId", "direction", "amount")
                .filters("transactionId", "accountId", "direction")
                .sorts("transactionId", "lineNo")
                .defaultSort("lineNo", true));
        });
    }

    @Bean
    Settings ledgerSettings(@Value("${jabiz.ledger.currency:JPY}") String currency,
        @Value("${jabiz.ledger.scale:0}") int scale) {
        return new Settings(currency, scale);
    }

    @Bean
    EntityDefinition ledgerAccountEntity() {
        return account();
    }

    @Bean
    EntityDefinition ledgerTransactionEntity() {
        return transaction();
    }

    @Bean
    EntityDefinition ledgerEntryEntity(Settings settings) {
        return entry(settings);
    }

    @Bean
    DatasetDefinition ledgerAccountDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(ACCOUNT_DATASET, d -> d
            .targetEntityType(ACCOUNT)
            .asDefault()
            .permissions(LedgerPermissions.ACCOUNT_READ, LedgerPermissions.ACCOUNT_WRITE)
            .policy(p -> p.maxQueryBatchSize(500))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition ledgerTransactionDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(TRANSACTION_DATASET, d -> d
            .targetEntityType(TRANSACTION)
            .asDefault()
            .permissions(LedgerPermissions.READ, LedgerPermissions.POST)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition ledgerEntryDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(ENTRY_DATASET, d -> d
            .targetEntityType(ENTRY)
            .asDefault()
            .permissions(LedgerPermissions.READ, LedgerPermissions.POST)
            // A transaction has at most LedgerPosting.MAX_LINES entries; the processes read them all at once.
            .policy(p -> p.processOnlyWrites().maxQueryBatchSize(500).maxWriteBatchSize(500))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}
