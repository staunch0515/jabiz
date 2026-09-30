package com.jabiz.runtime.integrity;

import com.jabiz.integrity.IntegrityKey;
import com.jabiz.integrity.MerkleRoot;
import com.jabiz.integrity.SealBlock;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * The step of {@code INTEGRITY_SEAL} (docs/design/21-audit-retention.md section 2.2). Rows the transaction cannot
 * see yet - their own transaction has not committed - are simply not there: the next run takes them, so no grace
 * period is needed and no row is ever skipped. Runs are serialized by a transaction lock.
 */
@Component
public class SealRows implements StepHandler<NoMetadata, ProcessContext> {

    private final IntegrityStore store;
    private final IntegrityKey key;
    private final IntegritySettings settings;

    public SealRows(IntegrityStore store, IntegrityKey key, IntegritySettings settings) {
        this.store = store;
        this.key = key;
        this.settings = settings;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        StorageEngine engine = store.engine();
        return store.pin(engine)
            .then(store.lock(engine))
            .then(store.head(engine).map(Optional::of).defaultIfEmpty(Optional.empty()))
            .flatMap(head -> store.appendOnlyTables(engine)
                .filter(table -> !table.keyColumns().isEmpty())
                .concatMap(table -> store.unsealed(engine, table, settings.maxRows()))
                .take(settings.maxRows())
                .collectList()
                .flatMap(rows -> {
                    if (rows.isEmpty()) {
                        ctx.put(IntegrityProcesses.OUTPUT, new IntegrityProcesses.SealOutput(null, 0, null));
                        return Mono.empty();
                    }
                    SealBlock block = new SealBlock(head.map(h -> h.block().sealNo() + 1).orElse(1L), ctx.opTime(),
                        rows.size(), MerkleRoot.of(rows), head.map(h -> h.storedHash()).orElse(SealBlock.GENESIS),
                        key.id());
                    String hash = block.hash(key);
                    return store.insertSeal(engine, block, hash, ctx.processSeqId(), rows)
                        .then(Mono.fromRunnable(() -> ctx.put(IntegrityProcesses.OUTPUT,
                            new IntegrityProcesses.SealOutput(block.sealNo(), rows.size(), hash))));
                }));
    }
}
