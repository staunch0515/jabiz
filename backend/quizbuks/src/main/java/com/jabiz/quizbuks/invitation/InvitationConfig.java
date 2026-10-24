package com.jabiz.quizbuks.invitation;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.mail.MailTemplate;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.messaging.MailValues;
import com.jabiz.context.RequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

/** The beans of the invitation lists (Q25): entities, datasets, processes, the mail template and the mail job. */
@Configuration
public class InvitationConfig {

    private final String poolRef;
    private final InvitationProcesses.Limits limits;

    /**
     * @param maxPerPublication how many addresses one publication's list holds ({@code quizbuks.invitations.max-per-publication})
     * @param sendsPerDay       how many invitations one sponsor queues a day ({@code quizbuks.invitations.sends-per-day})
     * @param zone              the zone the quota's days are counted in ({@code jabiz.fiscal-year-zone}, the platform's)
     */
    InvitationConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${quizbuks.invitations.max-per-publication:5000}") int maxPerPublication,
        @Value("${quizbuks.invitations.sends-per-day:2000}") int sendsPerDay,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.poolRef = poolRef;
        // An invalid zone is the platform's startup error (RETENTION): UTC here so that it is reported, not this.
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (RuntimeException e) {
            zoneId = ZoneOffset.UTC;
        }
        this.limits = new InvitationProcesses.Limits(maxPerPublication, sendsPerDay, zoneId);
    }

    @Bean
    EntityDefinition qbInvitationEntity() {
        return QbInvitations.INVITATION_ENTITY;
    }

    @Bean
    EntityDefinition qbInvitationQuotaEntity() {
        return QbInvitations.QUOTA_ENTITY;
    }

    /** Written by the processes only; the administration reads every list. */
    @Bean
    DatasetDefinition qbInvitationDataset() {
        return DatasetDefinition.define(QbInvitations.DEFAULT_DATASET, d -> d
            .targetEntityType(QbInvitations.INVITATION)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(QbInvitations.MAX_PER_CALL))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The sponsor's own lists, read only (its write permission is no role's). */
    @Bean
    DatasetDefinition qbSponsorInvitationDataset() {
        return DatasetDefinition.define(QbInvitations.SPONSOR_DATASET, d -> d
            .targetEntityType(QbInvitations.INVITATION)
            .permissions(QbPermissions.PUBLICATION_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    /** The invitations a taker's account claimed, read only: the publication page shows an invited publication by them. */
    @Bean
    DatasetDefinition qbMyInvitationDataset() {
        return DatasetDefinition.define(QbInvitations.MY_DATASET, d -> d
            .targetEntityType(QbInvitations.INVITATION)
            .permissions(QbPermissions.PLAY, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", RequestContext::actorId)));
    }

    @Bean
    DatasetDefinition qbInvitationQuotaDataset() {
        return DatasetDefinition.define(QbInvitations.QUOTA_DATASET, d -> d
            .targetEntityType(QbInvitations.QUOTA)
            .asDefault()
            .permissions(QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(10))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbInvitationStatusDictionary() {
        return StaticDictionary.define(QbInvitations.STATUS_DICTIONARY, d -> d
            .item(InvitationStatus.ADDED, "en", "Added", "zh", "已添加", "ja", "追加済み")
            .item(InvitationStatus.QUEUED, "en", "Sending", "zh", "发送中", "ja", "送信中")
            .item(InvitationStatus.SENT, "en", "Sent", "zh", "已发送", "ja", "送信済み")
            .item(InvitationStatus.STARTED, "en", "Started", "zh", "已开始答题", "ja", "回答開始")
            .item(InvitationStatus.ANSWERED, "en", "Answered", "zh", "已答题", "ja", "回答済み"));
    }

    @Bean
    MailTemplate qbInvitationMail() {
        return InvitationMails.INVITATION;
    }

    @Bean
    ProcessDefinition<InvitationProcesses.AddInput, InvitationProcesses.AddOutput, ProcessContext> qbInvitationAdd() {
        return InvitationProcesses.add(limits);
    }

    @Bean
    ProcessDefinition<InvitationProcesses.InvitationRef, InvitationProcesses.RemoveOutput, ProcessContext>
        qbInvitationRemove() {
        return InvitationProcesses.remove();
    }

    @Bean
    ProcessDefinition<InvitationProcesses.PublicationRef, InvitationProcesses.SendOutput, ProcessContext>
        qbInvitationSend() {
        return InvitationProcesses.send(limits);
    }

    @Bean
    ProcessDefinition<InvitationProcesses.InvitationRef, InvitationProcesses.MailOutput, ProcessContext>
        qbInvitationMailProcess(MailValues values) {
        return InvitationProcesses.mail(values);
    }

    /** Every minute: the queued invitations, each mail in a transaction of its own (platform decision D42). */
    @Bean
    JobDefinition<InvitationProcesses.InvitationRef> qbInvitationMailJob(
        ProcessDefinition<InvitationProcesses.InvitationRef, InvitationProcesses.MailOutput, ProcessContext>
            qbInvitationMailProcess) {
        return JobDefinition.forEach(InvitationProcesses.MAIL_JOB, "0 * * * * *", ZoneOffset.UTC,
                JobItems.template(InvitationProcesses.QUEUED_TEMPLATE, scheduledTime -> Map.of(), "invitationId"),
                qbInvitationMailProcess,
                (scheduledTime, row) -> new InvitationProcesses.InvitationRef(
                    String.valueOf(row.get("invitationId"))))
            .maxItems(500);
    }
}
