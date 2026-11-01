package com.jabiz.quizbuks.referral;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.RefereeRules;
import com.jabiz.quizbuks.publishing.ReferralPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneOffset;
import java.util.Map;

/**
 * Referral advertising (Q70, docs/quizbuks/plans/Q70-referrals-and-rounds.md): its two tables, dictionaries,
 * processes and jobs. The default datasets are read by headquarters ({@code qb.admin.referrals.read}); a referrer and
 * a sponsor read theirs through templates; nobody writes through a dataset.
 */
@Configuration
public class ReferralConfig {

    private final String poolRef;

    ReferralConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    private DatasetDefinition defaultDataset(String entity) {
        return DatasetDefinition.define(QbReferrals.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.REFERRAL_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(ReferralPolicy.MAX_QUOTA))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** A template scope: the rows whose {@code field} is the caller (a sponsor's: and published as a sponsor). */
    private DatasetDefinition scoped(String id, String entity, String field) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.REFERRAL_SYSTEM, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> {
                s.fromContext(field, "actorId", RequestContext::actorId);
                if (PublisherKind.ID_FIELD.equals(field)) {
                    s.fixed(PublisherKind.KIND_FIELD, PublisherKind.SPONSOR);
                }
            }));
    }

    @Bean
    DatasetDefinition qbRefereeReferralDataset() {
        return scoped(QbReferrals.REFEREE_DATASET, QbReferrals.REFERRAL, "refereeId");
    }

    @Bean
    DatasetDefinition qbReferrerReferralDataset() {
        return scoped(QbReferrals.REFERRER_DATASET, QbReferrals.REFERRAL, "referrerId");
    }

    @Bean
    DatasetDefinition qbReferrerLinkDataset() {
        return scoped(QbReferrals.REFERRER_LINK_DATASET, QbReferrals.LINK, "referrerId");
    }

    @Bean
    DatasetDefinition qbSponsorReferralDataset() {
        return scoped(QbReferrals.SPONSOR_DATASET, QbReferrals.REFERRAL, PublisherKind.ID_FIELD);
    }

    @Bean
    EntityDefinition qbReferralLinkEntity() {
        return QbReferrals.LINK_ENTITY;
    }

    @Bean
    DatasetDefinition qbReferralLinkDataset() {
        return defaultDataset(QbReferrals.LINK);
    }

    @Bean
    EntityDefinition qbReferralEntity() {
        return QbReferrals.REFERRAL_ENTITY;
    }

    @Bean
    DatasetDefinition qbReferralDataset() {
        return defaultDataset(QbReferrals.REFERRAL);
    }

    @Bean
    StaticDictionary qbReferralStatusDictionary() {
        return StaticDictionary.define(QbReferrals.STATUS_DICTIONARY, d -> d
            .item(QbReferrals.REFERRED, "en", "Referred", "zh", "已推荐", "ja", "紹介済み")
            .item(QbReferrals.REWARDED, "en", "Rewarded", "zh", "已发放", "ja", "報酬済み")
            .item(QbReferrals.EXPIRED, "en", "Expired", "zh", "已过期", "ja", "期限切れ")
            .item(QbReferrals.VOID, "en", "Void", "zh", "作废", "ja", "無効"));
    }

    @Bean
    StaticDictionary qbRefereeRuleDictionary() {
        return StaticDictionary.define(RefereeRules.DICTIONARY, d -> d
            .item(RefereeRules.NOT_REGULAR, "en", "Not a regular (never passed one of my quizzes)", "zh",
                "不是熟客（没有在本商户答题合格过）", "ja", "常連でない（当店のクイズに合格したことがない）")
            .item(RefereeRules.NEW_TO_PLATFORM, "en", "New to the platform (joined within 7 days)", "zh",
                "本平台新注册（7 天内注册）", "ja", "新規登録（7日以内に登録）")
            .item(RefereeRules.MATCHES_AUDIENCE, "en", "Matches the quiz's customer conditions", "zh",
                "符合发布的客户匹配条件", "ja", "クイズの顧客条件に合う")
            .item(RefereeRules.SAME_REGION_AS_REFERRER, "en", "Same country as the referrer", "zh",
                "与推荐人同一国家", "ja", "紹介者と同じ国"));
    }

    @Bean
    StaticDictionary qbReferrerRequirementDictionary() {
        return StaticDictionary.define(ReferralPolicy.REQUIREMENT_DICTIONARY, d -> d
            .item(ReferralPolicy.NONE, "en", "Nothing", "zh", "无要求", "ja", "条件なし")
            .item(ReferralPolicy.ANSWERED, "en", "Answered this quiz", "zh", "答过这份答卷", "ja", "このクイズに回答済み")
            .item(ReferralPolicy.PASSED, "en", "Passed this quiz", "zh", "这份答卷合格", "ja", "このクイズに合格"));
    }

    @Bean
    ProcessDefinition<ReferralProcesses.LinkInput, ReferralProcesses.LinkOutput, ProcessContext> qbReferralLink() {
        return ReferralProcesses.link();
    }

    @Bean
    ProcessDefinition<ReferralProcesses.ClaimInput, ReferralProcesses.ClaimOutput, ProcessContext> qbReferralClaim() {
        return ReferralProcesses.claim();
    }

    @Bean
    ProcessDefinition<ReferralProcesses.ViewInput, ReferralProcesses.ReferralView, ProcessContext> qbReferralView() {
        return ReferralProcesses.view();
    }

    @Bean
    ProcessDefinition<ReferralProcesses.ReferralRef, ReferralProcesses.ReferralOutput, ProcessContext>
        qbReferralReward() {
        return ReferralProcesses.reward();
    }

    @Bean
    ProcessDefinition<ReferralProcesses.ReferralRef, ReferralProcesses.ReferralOutput, ProcessContext>
        qbReferralExpire() {
        return ReferralProcesses.expire();
    }

    /**
     * Every five minutes (D-Q70-16): each referral whose referee passed in time is rewarded, one a transaction under
     * the publication's referral lock (platform decision D42); one the sponsor's wallet cannot pay yet waits for the
     * next run.
     */
    @Bean
    JobDefinition<ReferralProcesses.ReferralRef> qbReferralRewardJob(
        ProcessDefinition<ReferralProcesses.ReferralRef, ReferralProcesses.ReferralOutput, ProcessContext>
            qbReferralReward) {
        return JobDefinition.forEach(ReferralProcesses.REWARD_JOB, "0 */5 * * * *", ZoneOffset.UTC,
                JobItems.template(ReferralProcesses.DUE_TEMPLATE, scheduledTime -> Map.of(), "referralId"),
                qbReferralReward,
                (scheduledTime, row) -> new ReferralProcesses.ReferralRef(String.valueOf(row.get("referralId"))))
            .maxItems(1_000);
    }

    /**
     * Every hour at minute 37 (D-Q70-17): each referral whose time is up, or whose referee completed without passing,
     * is ended, so that its place of the quota is free again.
     */
    @Bean
    JobDefinition<ReferralProcesses.ReferralRef> qbReferralExpireJob(
        ProcessDefinition<ReferralProcesses.ReferralRef, ReferralProcesses.ReferralOutput, ProcessContext>
            qbReferralExpire) {
        return JobDefinition.forEach(ReferralProcesses.EXPIRE_JOB, "0 37 * * * *", ZoneOffset.UTC,
                JobItems.template(ReferralProcesses.OVER_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "referralId"),
                qbReferralExpire,
                (scheduledTime, row) -> new ReferralProcesses.ReferralRef(String.valueOf(row.get("referralId"))))
            .maxItems(1_000);
    }
}
