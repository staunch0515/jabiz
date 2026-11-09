package com.jabiz.quizbuks.sponsorpackage;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Registers the sponsor packages' imports (F-C4, docs/quizbuks/plans/impl/F-C4.md): the record, its default dataset
 * (written by the package's processes only; read by the administrators who read publications, an operator's session
 * its own Sponsors' only), the Sponsor's own records (read only), the three processes the console runs, and the daily
 * job deleting the records past their retention (one transaction each, platform decision D42).
 */
@Configuration
public class PackageImportConfig {

    /** Every day at 18:53 UTC. */
    static final String EXPIRE_CRON = "0 53 18 * * *";

    private final String poolRef;

    PackageImportConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbPackageImportEntity() {
        return QbPackageImports.ENTITY;
    }

    @Bean
    DatasetDefinition qbPackageImportDataset() {
        return DatasetDefinition.define(QbPackageImports.DEFAULT_DATASET, d -> d
            .targetEntityType(QbPackageImports.IMPORT)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .scope(OperatorScopes.of(QbPackageImports.IMPORT))
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The Sponsor's own records, read only: an import not finished is gone on with or given up. */
    @Bean
    DatasetDefinition qbMyPackageImportDataset() {
        return DatasetDefinition.define(QbPackageImports.MY_DATASET, d -> d
            .targetEntityType(QbPackageImports.IMPORT)
            .permissions(QbPermissions.SPONSOR_ME, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    @Bean
    StaticDictionary qbPackageImportModeDictionary() {
        return StaticDictionary.define(QbPackageImports.MODE_DICTIONARY, d -> d
            .item(QbPackageImports.FULL, "en", "Whole package", "zh", "整个包", "ja", "パッケージ全体")
            .item(QbPackageImports.PROFILE, "en", "Profile part only", "zh", "只导入资料部分", "ja", "プロフィール部分のみ"));
    }

    @Bean
    StaticDictionary qbPackageImportStatusDictionary() {
        return StaticDictionary.define(QbPackageImports.STATUS_DICTIONARY, d -> d
            .item(QbPackageImports.RUNNING, "en", "Not finished", "zh", "未完成", "ja", "未完了")
            .item(QbPackageImports.DONE, "en", "Done", "zh", "已完成", "ja", "完了")
            .item(QbPackageImports.ABANDONED, "en", "Given up", "zh", "已放弃", "ja", "中止"));
    }

    @Bean
    ProcessDefinition<PackageImportProcesses.StartInput, PackageImportProcesses.ImportView, ProcessContext>
        qbPackageImportStart() {
        return PackageImportProcesses.start();
    }

    @Bean
    ProcessDefinition<PackageImportProcesses.StepInput, PackageImportProcesses.StepOutput, ProcessContext>
        qbPackageImportStep() {
        return PackageImportProcesses.step();
    }

    @Bean
    ProcessDefinition<PackageImportProcesses.ImportRef, PackageImportProcesses.AbandonOutput, ProcessContext>
        qbPackageImportAbandon() {
        return PackageImportProcesses.abandon();
    }

    @Bean
    ProcessDefinition<PackageImportProcesses.ExpireInput, PackageImportProcesses.ExpireOutput, ProcessContext>
        qbPackageImportExpire() {
        return PackageImportProcesses.expire();
    }

    /** Every day, each record past its retention is deleted in a transaction of its own (platform decision D42). */
    @Bean
    JobDefinition<PackageImportProcesses.ExpireInput> qbPackageImportExpireJob(
        ProcessDefinition<PackageImportProcesses.ExpireInput, PackageImportProcesses.ExpireOutput, ProcessContext>
            qbPackageImportExpire) {
        return JobDefinition.forEach(PackageImportProcesses.EXPIRE_JOB, EXPIRE_CRON, ZoneOffset.UTC,
            JobItems.template(PackageImportProcesses.EXPIRED_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                "importId"),
            qbPackageImportExpire, (scheduledTime, row) -> new PackageImportProcesses.ExpireInput(
                UUID.fromString(String.valueOf(row.get("importId"))), scheduledTime));
    }
}
