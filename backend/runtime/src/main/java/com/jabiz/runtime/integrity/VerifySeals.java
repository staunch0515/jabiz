package com.jabiz.runtime.integrity;

import com.jabiz.integrity.IntegrityKey;
import com.jabiz.integrity.IntegrityProblem;
import com.jabiz.integrity.MerkleRoot;
import com.jabiz.integrity.SealChain;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The step of {@code INTEGRITY_VERIFY} (docs/design/21-audit-retention.md section 2.3): the chain (numbers, links,
 * signatures), each block's rows against its Merkle root and count, each sealed row against its table - changed or
 * gone - and whether each table with sealed rows still refuses changes. Keeps the first problems in full and counts
 * all, in {@code sys_integrity_check}.
 */
@Component
public class VerifySeals implements StepHandler<NoMetadata, ProcessContext> {

    private final IntegrityStore store;
    private final IntegrityKey key;
    private final IntegritySettings settings;
    private final JsonMapper json;

    public VerifySeals(IntegrityStore store, IntegrityKey key, IntegritySettings settings, JsonMapper json) {
        this.store = store;
        this.key = key;
        this.settings = settings;
        this.json = json;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        IntegrityProcesses.VerifyInput input = ctx.get(IntegrityProcesses.INPUT, IntegrityProcesses.VerifyInput.class);
        long from = input == null || input.fromSeal() == null ? 1 : Math.max(1, input.fromSeal());
        StorageEngine engine = store.engine();
        Problems problems = new Problems(settings.maxProblems());
        Mono<SealChain.Stored> previous = from == 1 ? Mono.empty() : store.block(engine, from - 1);
        return store.pin(engine)
            .then(previous.map(Optional::of).defaultIfEmpty(Optional.empty()))
            .flatMap(before -> store.blocks(engine, from).collectList().flatMap(blocks -> {
                SealChain.check(blocks, before.orElse(null), key).forEach(problems::add);
                long to = blocks.isEmpty() ? from - 1 : blocks.getLast().block().sealNo();
                long rows = blocks.stream().mapToLong(stored -> stored.block().rowCount()).sum();
                Mono<Void> checks = blocks.isEmpty() ? Mono.empty()
                    : sealedRows(engine, blocks, from, to, problems).then(tables(engine, from, to, problems));
                return checks
                    .then(unsealed(engine))
                    .flatMap(unsealed -> record(engine, ctx, from, to, blocks.size(), rows, unsealed, problems));
            }));
    }

    /** Each block's rows as stored add up to its root and count. */
    private Mono<Void> sealedRows(StorageEngine engine, List<SealChain.Stored> blocks, long from, long to,
        Problems problems) {
        Map<Long, SealChain.Stored> byNo = new LinkedHashMap<>();
        blocks.forEach(stored -> byNo.put(stored.block().sealNo(), stored));
        Map<Long, Boolean> seen = new LinkedHashMap<>();
        return store.rowsBySeal(engine, from, to)
            .doOnNext(block -> {
                seen.put(block.sealNo(), true);
                SealChain.Stored stored = byNo.get(block.sealNo());
                if (stored != null && (block.rows().size() != stored.block().rowCount()
                    || !MerkleRoot.of(block.rows()).equals(stored.block().merkleRoot()))) {
                    problems.add(altered(block.sealNo()));
                }
            })
            .then(Mono.fromRunnable(() -> byNo.forEach((sealNo, stored) -> {
                if (!seen.containsKey(sealNo) && stored.block().rowCount() > 0) {
                    problems.add(altered(sealNo));
                }
            })));
    }

    private static IntegrityProblem altered(long sealNo) {
        return new IntegrityProblem(IntegrityProblem.Kind.SEAL_ALTERED, sealNo, null, null,
            "The rows of block " + sealNo + " no longer add up to its root");
    }

    /** Each table with sealed rows: still guarded, and its rows as sealed. */
    private Mono<Void> tables(StorageEngine engine, long from, long to, Problems problems) {
        return store.sealedTables(engine, from, to)
            .concatMap(name -> store.table(engine, name).map(Optional::of).defaultIfEmpty(Optional.empty())
                .flatMapMany(found -> {
                    if (found.isEmpty()) {
                        return store.rowsOfMissingTable(engine, name, from, to);
                    }
                    IntegrityStore.Table table = found.get();
                    Flux<IntegrityProblem> guard = table.guarded() ? Flux.empty()
                        : Flux.just(new IntegrityProblem(IntegrityProblem.Kind.UNPROTECTED, null, name, null,
                            "Table " + name + " no longer refuses updates and deletions"));
                    // Without its primary key the sealed keys cannot be matched: every row counts as gone.
                    Flux<IntegrityProblem> rows = table.keyColumns().isEmpty()
                        ? store.rowsOfMissingTable(engine, name, from, to)
                        : store.changedRows(engine, table, from, to);
                    return guard.concatWith(rows);
                }))
            .doOnNext(problems::add)
            .then();
    }

    private Mono<Long> unsealed(StorageEngine engine) {
        return store.appendOnlyTables(engine)
            .filter(table -> !table.keyColumns().isEmpty())
            .concatMap(table -> store.unsealedCount(engine, table))
            .reduce(0L, Long::sum);
    }

    private Mono<Void> record(StorageEngine engine, ProcessContext ctx, long from, long to, int seals, long rows,
        long unsealed, Problems problems) {
        Map<String, BoundValue> check = new LinkedHashMap<>();
        check.put("seq", BoundValue.of(ctx.processSeqId()));
        check.put("actor", BoundValue.of(ctx.request().actorId()));
        check.put("time", BoundValue.of(ctx.opTime()));
        check.put("from", seals == 0 ? BoundValue.nullOf(Long.class) : BoundValue.of(from));
        check.put("to", seals == 0 ? BoundValue.nullOf(Long.class) : BoundValue.of(to));
        check.put("seals", BoundValue.of(seals));
        check.put("rows", BoundValue.of(rows));
        check.put("unsealed", BoundValue.of(unsealed));
        check.put("count", BoundValue.of(problems.count()));
        check.put("problems", BoundValue.of(json.writeValueAsString(problems.kept())));
        check.put("key", BoundValue.of(key.id()));
        return store.insertCheck(engine, check)
            .doOnNext(checkNo -> ctx.put(IntegrityProcesses.OUTPUT, new IntegrityProcesses.VerifyOutput(checkNo,
                problems.count() == 0, problems.count(), seals, rows, unsealed)))
            .then();
    }

    /** The first problems in full, all of them counted. */
    private static final class Problems {
        private final int max;
        private final List<IntegrityProblem> kept = new ArrayList<>();
        private final AtomicInteger count = new AtomicInteger();

        Problems(int max) {
            this.max = max;
        }

        synchronized void add(IntegrityProblem problem) {
            if (count.getAndIncrement() < max) {
                kept.add(problem);
            }
        }

        int count() {
            return count.get();
        }

        synchronized List<IntegrityProblem> kept() {
            return List.copyOf(kept);
        }
    }
}
