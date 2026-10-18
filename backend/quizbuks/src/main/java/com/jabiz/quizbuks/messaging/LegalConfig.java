package com.jabiz.quizbuks.messaging;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers terms, privacy and help (docs/quizbuks/plans/Q7-messages-legal.md, Q7b): the four entities and their
 * datasets, the dictionaries and the processes.
 *
 * <p>The drafts and the questions are edited directly by the content administrators in the administration (their
 * default datasets, written with {@code qb.admin.content.write}; the platform audits every change). Versions and
 * consents are written by the processes only and read by the administrators of users. Anybody reads the published
 * versions and the published questions, through public datasets (decision D17) and the public templates
 * {@code qb.public.*}: the drafts have no public dataset, and the questions' is fixed to the published ones.
 */
@Configuration
public class LegalConfig {

    private final String poolRef;

    LegalConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.poolRef = poolRef;
    }

    @Bean
    EntityDefinition qbLegalDocEntity() {
        return QbLegal.DOC_ENTITY;
    }

    @Bean
    EntityDefinition qbLegalVersionEntity() {
        return QbLegal.VERSION_ENTITY;
    }

    @Bean
    EntityDefinition qbLegalWithdrawalEntity() {
        return QbLegal.WITHDRAWAL_ENTITY;
    }

    @Bean
    EntityDefinition qbConsentEntity() {
        return QbLegal.CONSENT_ENTITY;
    }

    @Bean
    EntityDefinition qbFaqEntryEntity() {
        return QbLegal.FAQ_ENTITY;
    }

    /** The drafts: the content administrators read and edit them; publishing is {@code QB_LEGAL_PUBLISH}. */
    @Bean
    DatasetDefinition qbLegalDocDataset() {
        return DatasetDefinition.define(QbLegal.defaultDataset(QbLegal.DOC), d -> d
            .targetEntityType(QbLegal.DOC)
            .asDefault()
            .permissions(QbPermissions.ADMIN_CONTENT_WRITE, QbPermissions.ADMIN_CONTENT_WRITE)
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The questions and answers: the content administrators read and edit them. */
    @Bean
    DatasetDefinition qbFaqEntryDataset() {
        return DatasetDefinition.define(QbLegal.defaultDataset(QbLegal.FAQ), d -> d
            .targetEntityType(QbLegal.FAQ)
            .asDefault()
            .permissions(QbPermissions.ADMIN_CONTENT_WRITE, QbPermissions.ADMIN_CONTENT_WRITE)
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** Versions and consents: read by the administrators of users, written by the processes only. */
    private DatasetDefinition processWritten(String entity) {
        return DatasetDefinition.define(QbLegal.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_USERS_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites())
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbLegalVersionDataset() {
        return processWritten(QbLegal.VERSION);
    }

    @Bean
    DatasetDefinition qbLegalWithdrawalDataset() {
        return processWritten(QbLegal.WITHDRAWAL);
    }

    @Bean
    DatasetDefinition qbConsentDataset() {
        return processWritten(QbLegal.CONSENT);
    }

    /** One's own consents, read only (the scope is the caller), for {@code qb.me.consents}. */
    @Bean
    DatasetDefinition qbMyConsentsDataset() {
        return DatasetDefinition.define(QbLegal.MY_CONSENTS, d -> d
            .targetEntityType(QbLegal.CONSENT)
            .permissions(QbPermissions.ACCOUNT_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("userId", "actorId", request -> request.actorId())));
    }

    /**
     * Every published version is public, an announced one included (announcing a change is publishing it with the
     * time it takes effect): not who published it nor its hash. The drafts are another entity, never public.
     */
    @Bean
    DatasetDefinition qbPublicLegalVersionDataset() {
        return DatasetDefinition.define(QbLegal.PUBLIC_VERSIONS, d -> d
            .targetEntityType(QbLegal.VERSION)
            .publicRead(p -> p.allRows().fields("kind", "versionNumber", "title", "body", "changeSummary",
                "requiresConsent", "effectiveAt"))
            .permissions(QbPermissions.ADMIN_USERS_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /**
     * Which versions were withdrawn, so that the public pages leave them out and the history marks them: not who
     * withdrew them nor why.
     */
    @Bean
    DatasetDefinition qbPublicLegalWithdrawalDataset() {
        return DatasetDefinition.define(QbLegal.PUBLIC_WITHDRAWALS, d -> d
            .targetEntityType(QbLegal.WITHDRAWAL)
            .publicRead(p -> p.allRows().fields("kind", "versionNumber", "withdrawnAt"))
            .permissions(QbPermissions.ADMIN_USERS_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    /** The published questions only: an unpublished one is outside the dataset's fixed scope. */
    @Bean
    DatasetDefinition qbPublicFaqDataset() {
        return DatasetDefinition.define(QbLegal.PUBLIC_FAQ, d -> d
            .targetEntityType(QbLegal.FAQ)
            .scope(s -> s.fixed("published", true))
            .publicRead(p -> p.fields("faqId", "audience", "category", "question", "answer", "seq"))
            .permissions(QbPermissions.ADMIN_CONTENT_WRITE, QbPermissions.SPONSOR_VIEW_WRITE)
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary qbLegalKindDictionary() {
        return StaticDictionary.define(QbLegal.KIND_DICTIONARY, d -> d
            .item(QbLegal.TERMS_APP, "en", "Terms of use (app)", "zh", "用户条款（App）", "ja", "利用規約（アプリ）")
            .item(QbLegal.TERMS_SPONSOR, "en", "Sponsor terms", "zh", "商家条款", "ja", "スポンサー利用規約")
            .item(QbLegal.PRIVACY, "en", "Privacy policy", "zh", "隐私政策", "ja", "プライバシーポリシー")
            .item(QbLegal.COMMERCE_NOTICE, "en", "Specified Commercial Transactions Act notice",
                "zh", "特定商取引法标示", "ja", "特定商取引法に基づく表記")
            .item(QbLegal.AI_NOTICE, "en", "AI notice", "zh", "AI 使用说明", "ja", "AI 利用に関する説明"));
    }

    @Bean
    StaticDictionary qbFaqCategoryDictionary() {
        return StaticDictionary.define(QbLegal.FAQ_CATEGORY_DICTIONARY, d -> d
            .item("ACCOUNT", "en", "Account", "zh", "账号", "ja", "アカウント")
            .item("QUIZ_RULES", "en", "Quiz rules", "zh", "答题规则", "ja", "クイズのルール")
            .item("REWARDS", "en", "Rewards and transfers", "zh", "奖励与转账", "ja", "報酬と振込")
            .item("OTHER", "en", "Other", "zh", "其他", "ja", "その他")
            .item("ONBOARDING", "en", "Account and onboarding", "zh", "账号与入驻", "ja", "アカウントと登録審査")
            .item("PUBLISHING", "en", "Content and publishing", "zh", "内容与发布", "ja", "コンテンツと公開")
            .item("BILLING", "en", "Top-ups and fees", "zh", "充值与费用", "ja", "チャージと手数料"));
    }

    @Bean
    StaticDictionary qbFaqAudienceDictionary() {
        return StaticDictionary.define(QbLegal.FAQ_AUDIENCE_DICTIONARY, d -> d
            .item(QbLegal.AUDIENCE_APP, "en", "App", "zh", "App", "ja", "アプリ")
            .item(QbLegal.AUDIENCE_SPONSOR, "en", "Sponsor console", "zh", "商家后台", "ja", "スポンサー管理画面")
            .item(QbLegal.AUDIENCE_ALL, "en", "Both", "zh", "两者", "ja", "両方"));
    }

    @Bean
    StaticDictionary qbConsentSourceDictionary() {
        return StaticDictionary.define(QbLegal.CONSENT_SOURCE_DICTIONARY, d -> d
            .item(QbLegal.SOURCE_WELCOME, "en", "Registration", "zh", "注册", "ja", "登録時")
            .item(QbLegal.SOURCE_ONBOARDING, "en", "Sponsor application", "zh", "商家入驻", "ja", "スポンサー申請")
            .item(QbLegal.SOURCE_RECONSENT, "en", "After a change", "zh", "变更后重新同意", "ja", "改定後の再同意")
            .item(QbLegal.SOURCE_AI, "en", "AI request", "zh", "使用 AI 时", "ja", "AI の利用時"));
    }

    @Bean
    ProcessDefinition<LegalProcesses.PublishInput, LegalProcesses.PublishOutput, ProcessContext> qbLegalPublish(
        MessageTexts qbMessageTexts, MailValues qbMailValues) {
        return LegalProcesses.publish(qbMessageTexts, qbMailValues);
    }

    @Bean
    ProcessDefinition<LegalProcesses.WithdrawInput, LegalProcesses.WithdrawOutput, ProcessContext>
        qbLegalWithdraw() {
        return LegalProcesses.withdraw();
    }

    @Bean
    ProcessDefinition<LegalProcesses.AcceptInput, LegalProcesses.AcceptOutput, ProcessContext> qbConsentAccept() {
        return LegalProcesses.accept();
    }
}
