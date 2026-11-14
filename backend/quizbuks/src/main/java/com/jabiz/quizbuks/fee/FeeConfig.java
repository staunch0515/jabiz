package com.jabiz.quizbuks.fee;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.document.DocumentLayout;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.event.EventSubscription;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.wallet.TransferProcesses;
import com.jabiz.retention.RetentionPolicy;
import com.jabiz.runtime.approval.ApprovalProcesses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

/** The beans of the platform's fees (Q87): entities, datasets, dictionaries, processes, jobs, the document, retention. */
@Configuration
public class FeeConfig {

    private final String poolRef;
    private final ZoneId zone;

    FeeConfig(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.fiscal-year-zone:UTC}") String zone) {
        this.poolRef = poolRef;
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (RuntimeException e) {
            zoneId = ZoneOffset.UTC;
        }
        this.zone = zoneId;
    }

    // ---- entities and datasets ---------------------------------------------------------------------------------------

    @Bean
    EntityDefinition qbFeeChargeEntity() {
        return QbFees.CHARGE_ENTITY;
    }

    @Bean
    EntityDefinition qbFeeInvoiceEntity() {
        return QbFees.INVOICE_ENTITY;
    }

    @Bean
    EntityDefinition qbFeeRequestEntity() {
        return QbFees.REQUEST_ENTITY;
    }

    /** The account that pays the platform's fees (F-M1): the fee credit's lots, the cash lots, the payments. */
    @Bean
    EntityDefinition qbFeeCreditGrantEntity() {
        return QbFeeAccount.GRANT_ENTITY;
    }

    @Bean
    EntityDefinition qbCashLotEntity() {
        return QbFeeAccount.CASH_LOT_ENTITY;
    }

    @Bean
    EntityDefinition qbFeePaymentEntity() {
        return QbFeeAccount.PAYMENT_ENTITY;
    }

    /** F-M5: the prepayments publications' submissions set aside; finance reads them, the processes write them. */
    @Bean
    EntityDefinition qbPrepaymentEntity() {
        return QbPrepayments.PREPAYMENT_ENTITY;
    }

    @Bean
    DatasetDefinition qbPrepaymentDataset() {
        return processOnly(QbPrepayments.PREPAYMENT);
    }

    @Bean
    DatasetDefinition qbSponsorPrepaymentDataset() {
        return own(QbPrepayments.SPONSOR_PREPAYMENTS, QbPrepayments.PREPAYMENT);
    }

    @Bean
    StaticDictionary qbPrepaymentPurposeDictionary() {
        return StaticDictionary.define(QbPrepayments.PURPOSE_DICTIONARY, d -> d
            .item(QbPrepayments.DISTRIBUTION, "en", "Distribution fee", "zh", "配送费", "ja", "配信料")
            .item(QbPrepayments.EXPOSURE, "en", "Exposure fee", "zh", "曝光费", "ja", "露出料")
            .item(QbPrepayments.AUDIENCE, "en", "Audience rules", "zh", "受众规则费", "ja", "対象者ルール料")
            .item(QbPrepayments.AI, "en", "AI credit", "zh", "AI 额度", "ja", "AI クレジット"));
    }

    @Bean
    StaticDictionary qbPrepaymentModeDictionary() {
        return StaticDictionary.define(QbPrepayments.MODE_DICTIONARY, d -> d
            .item(QbPrepayments.PREPAID, "en", "Money set aside", "zh", "已冻结资金", "ja", "資金を確保")
            .item(QbPrepayments.POSTPAID, "en", "Counted against the credit limit", "zh", "占用信用额",
                "ja", "与信枠を使用"));
    }

    @Bean
    StaticDictionary qbPrepaymentStatusDictionary() {
        return StaticDictionary.define(QbPrepayments.STATUS_DICTIONARY, d -> d
            .item(QbPrepayments.OPEN, "en", "Held", "zh", "冻结中", "ja", "確保中")
            .item(QbPrepayments.RELEASED, "en", "Given back", "zh", "已退回", "ja", "返却済み"));
    }

    /** F-M7: issuing a credit note (under the wallet lock its callers hold), and the reminders of what it paid. */
    @Bean
    ProcessDefinition<CreditNotes.IssueInput, CreditNotes.IssueOutput, ProcessContext> qbFeeCreditNoteIssue() {
        return CreditNotes.issue(zone);
    }

    @Bean
    ProcessDefinition<CreditNotes.TaskCloseInput, CreditNotes.TaskCloseOutput, ProcessContext>
        qbFeeCreditNoteTaskClose() {
        return CreditNotes.taskClose();
    }

    @Bean
    ProcessDefinition<Prepayments.FreezeInput, Prepayments.FreezeOutput, ProcessContext> qbPrepaymentFreeze() {
        return Prepayments.freeze();
    }

    @Bean
    ProcessDefinition<Prepayments.TopUpInput, Prepayments.FreezeOutput, ProcessContext> qbPrepaymentTopUp() {
        return Prepayments.topUp();
    }

    @Bean
    ProcessDefinition<Prepayments.UseInput, FeeSettlement.SettleOutput, ProcessContext> qbPrepaymentUse() {
        return Prepayments.use();
    }

    @Bean
    ProcessDefinition<Prepayments.ChargeInput, Prepayments.ChargeOutput, ProcessContext> qbPrepaymentCharge() {
        return Prepayments.charge(zone);
    }

    @Bean
    ProcessDefinition<Prepayments.ReleaseInput, Prepayments.ReleaseOutput, ProcessContext> qbPrepaymentRelease() {
        return Prepayments.release();
    }

    @Bean
    ProcessDefinition<Prepayments.ReleaseRef, FeeProcesses.ItemOutput, ProcessContext> qbPrepaymentReleaseItem() {
        return Prepayments.releaseItem();
    }

    /**
     * Every day ({@code quizbuks.prepayment.release-cron}, {@code QB_PREPAYMENT_RELEASE_CRON}; 03:23 in the fiscal
     * year's zone): what ended publications' prepayments still hold goes back (F-M5: "within a day of the end"; F-M6:
     * one item a publication and purpose, the distribution fee's deposit only after its final settlement), at most
     * {@code quizbuks.prepayment.release-max-items} a run.
     */
    @Bean
    JobDefinition<Prepayments.ReleaseRef> qbPrepaymentReleaseJob(
        ProcessDefinition<Prepayments.ReleaseRef, FeeProcesses.ItemOutput, ProcessContext> qbPrepaymentReleaseItem,
        @org.springframework.beans.factory.annotation.Value("${quizbuks.prepayment.release-cron:0 23 3 * * *}")
        String cron,
        @org.springframework.beans.factory.annotation.Value("${quizbuks.prepayment.release-max-items:10000}")
        int maxItems) {
        return JobDefinition.forEach(Prepayments.RELEASE_JOB, cron, zone,
                JobItems.template(Prepayments.RELEASE_DUE_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "prepaymentId"),
                qbPrepaymentReleaseItem,
                (scheduledTime, row) -> new Prepayments.ReleaseRef(String.valueOf(row.get("publicationId")),
                    String.valueOf(row.get("sponsorId")), String.valueOf(row.get("purpose"))))
            .maxItems(maxItems);
    }

    /** F-M7: the item that decides an audience fee's unused days once. */
    @Bean
    ProcessDefinition<AudienceRefundJob.Ref, FeeProcesses.ItemOutput, ProcessContext> qbAudienceRefundItem() {
        return AudienceRefundJob.item();
    }

    @Bean
    ProcessDefinition<AudienceRefundJob.CloseInput, AudienceRefundJob.CloseOutput, ProcessContext>
        qbAudienceRefundClose() {
        return AudienceRefundJob.close();
    }

    /**
     * F-M7 (§10.3-6): every day ({@code quizbuks.audience-refund.cron}, {@code QB_AUDIENCE_REFUND_CRON}; 03:37 in the
     * fiscal year's zone, after the distribution fee's final settlements at 03:07 and the prepayments' release at
     * 03:23), the audience fees of publications that ended and are not decided yet, at most
     * {@code quizbuks.audience-refund.max-items} a run.
     */
    @Bean
    JobDefinition<AudienceRefundJob.Ref> qbAudienceRefundJob(
        ProcessDefinition<AudienceRefundJob.Ref, FeeProcesses.ItemOutput, ProcessContext> qbAudienceRefundItem,
        @org.springframework.beans.factory.annotation.Value("${quizbuks.audience-refund.cron:0 37 3 * * *}")
        String cron,
        @org.springframework.beans.factory.annotation.Value("${quizbuks.audience-refund.max-items:10000}")
        int maxItems) {
        return JobDefinition.forEach(AudienceRefundJob.JOB, cron, zone,
                JobItems.template(AudienceRefundJob.DUE_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "prepaymentId"),
                qbAudienceRefundItem,
                (scheduledTime, row) -> new AudienceRefundJob.Ref(String.valueOf(row.get("prepaymentId")),
                    String.valueOf(row.get("publicationId")), String.valueOf(row.get("sponsorId")),
                    AudienceRefundJob.instant(row.get("endedAt"))))
            .maxItems(maxItems);
    }

    /** F-M6: the operators' distribution prices and the publications' settlements. */
    @Bean
    EntityDefinition qbDistributionPriceEntity() {
        return QbDistributions.PRICE_ENTITY;
    }

    @Bean
    DatasetDefinition qbDistributionPriceDataset() {
        return processOnly(QbDistributions.PRICE);
    }

    @Bean
    EntityDefinition qbDistributionSettlementEntity() {
        return QbDistributions.SETTLEMENT_ENTITY;
    }

    @Bean
    DatasetDefinition qbDistributionSettlementDataset() {
        return processOnly(QbDistributions.SETTLEMENT);
    }

    @Bean
    DatasetDefinition qbSponsorDistributionSettlementDataset() {
        return own(QbDistributions.SPONSOR_SETTLEMENTS, QbDistributions.SETTLEMENT);
    }

    @Bean
    ProcessDefinition<DistributionPrices.PriceInput, DistributionPrices.PriceOutput, ProcessContext>
        qbDistributionPriceSet() {
        return DistributionPrices.set();
    }

    @Bean
    ProcessDefinition<DistributionPrices.PriceInput, DistributionPrices.PriceOutput, ProcessContext>
        qbOperatorDistributionPriceSet() {
        return DistributionPrices.operatorSet();
    }

    /** F-M9: the AI credit's movements (written only by the AI credit's processes) and its purchase. */
    @Bean
    EntityDefinition qbAiCreditMovementEntity() {
        return AiCredits.MOVEMENT_ENTITY;
    }

    @Bean
    DatasetDefinition qbAiCreditMovementDataset() {
        return processOnly(AiCredits.MOVEMENT);
    }

    @Bean
    ProcessDefinition<AiCredits.BuyInput, AiCredits.BuyOutput, ProcessContext> qbAiCreditBuy() {
        return AiCredits.buy();
    }

    /** F-M8: the operators' exposure prices, the publications' settlements and the reminders. */
    @Bean
    EntityDefinition qbExposurePriceEntity() {
        return QbExposureFees.PRICE_ENTITY;
    }

    @Bean
    DatasetDefinition qbExposurePriceDataset() {
        return processOnly(QbExposureFees.PRICE);
    }

    @Bean
    EntityDefinition qbExposureSettlementEntity() {
        return QbExposureFees.SETTLEMENT_ENTITY;
    }

    @Bean
    DatasetDefinition qbExposureSettlementDataset() {
        return processOnly(QbExposureFees.SETTLEMENT);
    }

    /**
     * The operator reads only the settlements of the publications whose fee is its own (D64): by the operator frozen
     * at the submission, so a Sponsor moved to another operator since stays with the operator whose fee it was.
     */
    @Bean
    DatasetDefinition qbOperatorExposureSettlementDataset() {
        return DatasetDefinition.define(QbExposureFees.OPERATOR_SETTLEMENTS, d -> d
            .targetEntityType(QbExposureFees.SETTLEMENT)
            .permissions(QbPermissions.OPERATOR_USE, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .scope(s -> s.withinEntryScope(QbOperators.SCOPE, "operatorId"))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    DatasetDefinition qbSponsorExposureSettlementDataset() {
        return own(QbExposureFees.SPONSOR_SETTLEMENTS, QbExposureFees.SETTLEMENT);
    }

    @Bean
    EntityDefinition qbExposureNoticeEntity() {
        return QbExposureFees.NOTICE_ENTITY;
    }

    @Bean
    DatasetDefinition qbExposureNoticeDataset() {
        return processOnly(QbExposureFees.NOTICE);
    }

    @Bean
    ProcessDefinition<ExposurePrices.PriceInput, ExposurePrices.PriceOutput, ProcessContext> qbExposurePriceSet() {
        return ExposurePrices.set();
    }

    @Bean
    ProcessDefinition<ExposurePrices.PriceInput, ExposurePrices.PriceOutput, ProcessContext>
        qbOperatorExposurePriceSet() {
        return ExposurePrices.operatorSet();
    }

    @Bean
    RetentionPolicy qbExposureSettlementRetention() {
        return RetentionPolicy.of(QbExposureFees.SETTLEMENT).keep(RETENTION).from("settledAt").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy qbDistributionSettlementRetention() {
        return RetentionPolicy.of(QbDistributions.SETTLEMENT).keep(RETENTION).from("settledAt").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy qbPrepaymentRetention() {
        return RetentionPolicy.of(QbPrepayments.PREPAYMENT).keep(RETENTION).from("frozenAt").afterFiscalYearEnd();
    }

    @Bean
    DatasetDefinition qbFeeCreditGrantDataset() {
        return processOnly(QbFeeAccount.GRANT);
    }

    @Bean
    DatasetDefinition qbCashLotDataset() {
        return processOnly(QbFeeAccount.CASH_LOT);
    }

    @Bean
    DatasetDefinition qbFeePaymentDataset() {
        return processOnly(QbFeeAccount.PAYMENT);
    }

    /** A sponsor's own lots and payments, read only (the wallet page's account block reads them by templates). */
    @Bean
    DatasetDefinition qbSponsorFeeCreditGrantDataset() {
        return own(QbFeeAccount.SPONSOR_GRANTS, QbFeeAccount.GRANT);
    }

    @Bean
    DatasetDefinition qbSponsorCashLotDataset() {
        return own(QbFeeAccount.SPONSOR_CASH_LOTS, QbFeeAccount.CASH_LOT);
    }

    @Bean
    DatasetDefinition qbSponsorFeePaymentDataset() {
        return own(QbFeeAccount.SPONSOR_PAYMENTS, QbFeeAccount.PAYMENT);
    }

    @Bean
    StaticDictionary qbFeeCreditSourceDictionary() {
        return StaticDictionary.define(QbFeeAccount.GRANT_SOURCE_DICTIONARY, d -> d
            .item(QbFeeAccount.ONBOARDING, "en", "Onboarding", "zh", "入驻赠送", "ja", "登録特典")
            .item(QbFeeAccount.LIGHT, "en", "Node lit", "zh", "点亮赠送", "ja", "ノード達成特典")
            .item(QbFeeAccount.MANUAL, "en", "Granted by hand", "zh", "手工赠送", "ja", "手動付与")
            .item(QbFeeAccount.CERTIFIED_TOP_UP, "en", "Verified company", "zh", "企业认证赠送", "ja", "企業認証特典"));
    }

    @Bean
    StaticDictionary qbCashSourceDictionary() {
        return StaticDictionary.define(QbFeeAccount.CASH_SOURCE_DICTIONARY, d -> d
            .item(QbFeeAccount.CARD, "en", "Card top-up", "zh", "银行卡充值", "ja", "カードでのチャージ")
            .item(QbFeeAccount.BANK, "en", "Bank transfer", "zh", "银行转账", "ja", "銀行振込")
            .item(QbFeeAccount.OPERATOR, "en", "From the operator", "zh", "运营商转入", "ja", "運営パートナーから")
            .item(QbFeeAccount.OTHER, "en", "Other", "zh", "其他", "ja", "その他"));
    }

    @Bean
    StaticDictionary qbFeeSubAccountDictionary() {
        return StaticDictionary.define(QbFeeAccount.SUB_ACCOUNT_DICTIONARY, d -> d
            .item(QbFeeAccount.FEE_CREDIT, "en", "Granted credit", "zh", "赠送额度", "ja", "付与クレジット")
            .item(QbFeeAccount.CASH, "en", "Cash", "zh", "现金", "ja", "現金"));
    }

    /** Finance reads every charge, invoice and request; only the processes write them. */
    @Bean
    DatasetDefinition qbFeeChargeDataset() {
        return processOnly(QbFees.CHARGE);
    }

    @Bean
    DatasetDefinition qbFeeInvoiceDataset() {
        return processOnly(QbFees.INVOICE);
    }

    @Bean
    DatasetDefinition qbFeeRequestDataset() {
        return processOnly(QbFees.REQUEST);
    }

    /** A sponsor's own charges and invoices, read only. */
    @Bean
    DatasetDefinition qbSponsorFeeChargeDataset() {
        return own(QbFees.SPONSOR_CHARGES, QbFees.CHARGE);
    }

    @Bean
    DatasetDefinition qbSponsorFeeInvoiceDataset() {
        return own(QbFees.SPONSOR_INVOICES, QbFees.INVOICE);
    }

    /** F-M7: credit notes and what their credit paid; finance reads them, the processes write them. */
    @Bean
    EntityDefinition qbFeeCreditNoteEntity() {
        return QbCreditNotes.CREDIT_NOTE_ENTITY;
    }

    @Bean
    EntityDefinition qbFeeCreditNoteUseEntity() {
        return QbCreditNotes.USE_ENTITY;
    }

    @Bean
    DatasetDefinition qbFeeCreditNoteDataset() {
        return processOnly(QbCreditNotes.CREDIT_NOTE);
    }

    @Bean
    DatasetDefinition qbFeeCreditNoteUseDataset() {
        return processOnly(QbCreditNotes.USE);
    }

    @Bean
    DatasetDefinition qbSponsorFeeCreditNoteDataset() {
        return own(QbCreditNotes.SPONSOR_CREDIT_NOTES, QbCreditNotes.CREDIT_NOTE);
    }

    @Bean
    DatasetDefinition qbSponsorFeeCreditNoteUseDataset() {
        return own(QbCreditNotes.SPONSOR_USES, QbCreditNotes.USE);
    }

    @Bean
    EntityDefinition qbFeeCreditNoteDocumentEntity() {
        return QbCreditNotes.DOCUMENT_ENTITY;
    }

    @Bean
    DatasetDefinition qbFeeCreditNoteDocumentDataset() {
        return processOnly(QbCreditNotes.DOCUMENT);
    }

    /** The sponsor's own: their document process reads (and writes, as the process) through it. */
    @Bean
    DatasetDefinition qbSponsorFeeCreditNoteDocumentDataset() {
        return own(QbCreditNotes.SPONSOR_DOCUMENTS, QbCreditNotes.DOCUMENT);
    }

    @Bean
    DocumentLayout qbFeeCreditNoteLayout() {
        return FeeDocuments.CREDIT_NOTE_LAYOUT;
    }

    @Bean
    ProcessDefinition<CreditNotes.DocumentInput, CreditNotes.DocumentOutput, ProcessContext> qbFeeCreditNoteDocument() {
        return CreditNotes.document();
    }

    private DatasetDefinition processOnly(String entity) {
        return DatasetDefinition.define(QbFees.defaultDataset(entity), d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(QbPermissions.ADMIN_FINANCE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.processOnlyWrites().maxWriteBatchSize(10))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    private DatasetDefinition own(String id, String entity) {
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .permissions(QbPermissions.SPONSOR_FINANCE_READ, QbPermissions.SPONSOR_VIEW_WRITE)
            .policy(p -> p.readOnly(true).allowTimeTravel(false))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef))
            .scope(s -> s.fromContext("sponsorId", "actorId", RequestContext::actorId)));
    }

    @Bean
    StaticDictionary qbFeeKindDictionary() {
        return StaticDictionary.define(QbFees.KIND_DICTIONARY, d -> d
            .item(FeeRules.MONTHLY, "en", "Monthly fee", "zh", "月费", "ja", "月額料金")
            .item(FeeRules.MANUAL, "en", "Fee charged by hand", "zh", "手工记费", "ja", "手動の料金")
            .item(FeeRules.AUDIENCE, "en", "Audience rules", "zh", "受众规则费", "ja", "対象者ルール料")
            .item(FeeRules.DISTRIBUTION, "en", "Distribution fee", "zh", "配送费", "ja", "配信料")
            .item(FeeRules.EXPOSURE, "en", "Exposure fee", "zh", "曝光费", "ja", "露出料"));
    }

    @Bean
    StaticDictionary qbBillingModeDictionary() {
        return StaticDictionary.define(QbFees.BILLING_MODE_DICTIONARY, d -> d
            .item(FeeRules.PREPAID, "en", "Prepaid", "zh", "预付", "ja", "前払い")
            .item(FeeRules.POSTPAID, "en", "Postpaid", "zh", "后付", "ja", "後払い"));
    }

    @Bean
    StaticDictionary qbFeeInvoiceStatusDictionary() {
        return StaticDictionary.define(QbFees.INVOICE_STATUS_DICTIONARY, d -> d
            .item(QbFees.OPEN, "en", "Open", "zh", "待付", "ja", "未払い")
            .item(QbFees.PARTIAL, "en", "Partly paid", "zh", "部分已付", "ja", "一部支払済み")
            .item(QbFees.OVERDUE, "en", "Overdue", "zh", "逾期", "ja", "期限超過")
            .item(QbFees.PAID, "en", "Paid", "zh", "已付", "ja", "支払済み")
            .item(QbFees.VOID, "en", "Voided", "zh", "已作废", "ja", "取消済み"));
    }

    @Bean
    StaticDictionary qbFeeRequestKindDictionary() {
        return StaticDictionary.define(QbFees.REQUEST_KIND_DICTIONARY, d -> d
            .item(QbFees.REQUEST_MANUAL, "en", "Fee charged by hand", "zh", "手工记费", "ja", "手動の料金")
            .item(QbFees.REQUEST_VOID, "en", "Invoice voided", "zh", "作废账单", "ja", "請求書の取消")
            .item(QbFees.REQUEST_CREDIT, "en", "Platform fee Kudos granted by hand", "zh", "追加赠送平台费 Kudos",
                "ja", "利用料 Kudos の追加付与")
            .item(QbFees.REQUEST_CREDIT_NOTE, "en", "Credit note by hand", "zh", "手工开贷项凭证", "ja",
                "手動の返還インボイス"));
    }

    @Bean
    StaticDictionary qbFeeCreditNoteReasonDictionary() {
        return StaticDictionary.define(QbCreditNotes.REASON_DICTIONARY, d -> d
            .item(QbCreditNotes.AUDIENCE_UNUSED, "en", "Unused days after an early end", "zh", "提前结束后未用的天数",
                "ja", "早期終了後の未使用日数")
            .item(QbCreditNotes.MANUAL, "en", "By hand", "zh", "手工", "ja", "手動"));
    }

    @Bean
    StaticDictionary qbAudienceRefundOutcomeDictionary() {
        return StaticDictionary.define(QbPrepayments.REFUND_OUTCOME_DICTIONARY, d -> d
            .item(AudienceRefunds.REFUNDED, "en", "Given back", "zh", "已退回", "ja", "返金済み")
            .item(AudienceRefunds.CREDITED, "en", "Credited", "zh", "已作贷项", "ja", "クレジット済み")
            .item(AudienceRefunds.NONE_SWITCH_OFF, "en", "None: refunds were off", "zh", "不退：开关关闭", "ja",
                "返金なし：設定がオフ")
            .item(AudienceRefunds.NONE_REASON, "en", "None: not refundable", "zh", "不退：原因不可退", "ja",
                "返金なし：対象外の理由")
            .item(AudienceRefunds.NONE_ZERO, "en", "None: no unused days", "zh", "不退：没有未用天数", "ja",
                "返金なし：未使用日数なし")
            .item(AudienceRefunds.NONE_NO_PERIOD, "en", "None: no period", "zh", "不退：没有计费期间", "ja",
                "返金なし：期間なし")
            .item(AudienceRefunds.NONE_FINANCE, "en", "None: decided by finance", "zh", "不退：财务决定不开凭证", "ja",
                "返金なし：経理が判断"));
    }

    @Bean
    StaticDictionary qbFeeRequestStatusDictionary() {
        return StaticDictionary.define(QbFees.REQUEST_STATUS_DICTIONARY, d -> d
            .item(QbFees.REVIEW, "en", "Awaiting approval", "zh", "待批准", "ja", "承認待ち")
            .item(QbFees.DONE, "en", "Done", "zh", "已执行", "ja", "実行済み")
            .item(QbFees.REJECTED, "en", "Rejected", "zh", "已拒绝", "ja", "却下")
            .item(QbFees.FAILED, "en", "Not done", "zh", "未能执行", "ja", "実行できず"));
    }

    /** {@code QBF-2026-00001}: one count per year, without gaps. */
    @Bean
    NumberSequence qbFeeInvoiceNumbers() {
        return NumberSequence.define(QbFees.INVOICE_SEQUENCE, s -> s.format("QBF-{scope}-{n:5}").scoped());
    }

    /** {@code QBC-2026-00001}: credit notes, one count per year, without gaps (F-M7). */
    @Bean
    NumberSequence qbFeeCreditNoteNumbers() {
        return NumberSequence.define(QbCreditNotes.SEQUENCE, s -> s.format("QBC-{scope}-{n:5}").scoped());
    }

    @Bean
    ApprovalSubject qbFeeRequestApprovalSubject() {
        return ApprovalSubject.define(QbFees.APPROVAL_SUBJECT, s -> s.entity(QbFees.REQUEST).bool("feeRequest")
            .text("kind").text("sponsorId").number("amount"));
    }

    @Bean
    DocumentLayout qbFeeInvoiceLayout() {
        return FeeDocuments.LAYOUT;
    }

    // ---- processes ----------------------------------------------------------------------------------------------------

    @Bean
    ProcessDefinition<FeeProcesses.ChargeInput, FeeProcesses.ChargeOutput, ProcessContext> qbFeeCharge() {
        return FeeProcesses.charge(zone);
    }

    @Bean
    ProcessDefinition<FeeSettlement.SettleInput, FeeSettlement.SettleOutput, ProcessContext> qbFeeSettle() {
        return FeeSettlement.process();
    }

    @Bean
    ProcessDefinition<FeeSettlement.RefundInput, FeeSettlement.RefundOutput, ProcessContext> qbFeeRefund() {
        return FeeSettlement.refund();
    }

    @Bean
    ProcessDefinition<FeeProcesses.MonthlyInput, FeeProcesses.ItemOutput, ProcessContext> qbFeeMonthlyItem() {
        return FeeProcesses.monthlyItem();
    }

    @Bean
    ProcessDefinition<FeeProcesses.InvoiceInput, FeeProcesses.ItemOutput, ProcessContext> qbFeeInvoiceItem() {
        return FeeProcesses.invoiceItem(zone);
    }

    @Bean
    ProcessDefinition<FeeProcesses.InvoiceRef, FeeProcesses.InvoiceOutput, ProcessContext> qbFeeInvoicePay() {
        return FeeProcesses.pay();
    }

    @Bean
    ProcessDefinition<FeeProcesses.EarlyInput, FeeProcesses.EarlyOutput, ProcessContext> qbFeePayEarly() {
        return FeeProcesses.payEarly();
    }

    @Bean
    ProcessDefinition<FeeProcesses.SettleInput, FeeProcesses.SettleOutput, ProcessContext> qbFeeSettleItem() {
        return FeeProcesses.settleItem(zone);
    }

    @Bean
    ProcessDefinition<FeeProcesses.DocumentInput, FeeProcesses.InvoiceOutput, ProcessContext> qbFeeInvoiceDocument(
        com.jabiz.runtime.document.Documents documents, com.jabiz.i18n.MessageCatalog messages) {
        return FeeProcesses.document(zone, (language, request) -> messages.find(
            "document.qb.fee_invoice.sourcesUnavailable", documents.language(language, request)).orElse(""));
    }

    @Bean
    ProcessDefinition<FeeProcesses.InvoiceRef, FeeProcesses.ItemOutput, ProcessContext> qbFeeOverdueItem() {
        return FeeProcesses.overdueItem(zone);
    }

    @Bean
    ProcessDefinition<FeeProcesses.BillingInput, FeeProcesses.BillingOutput, ProcessContext> qbOperatorBillingSet() {
        return FeeProcesses.billingSet();
    }

    @Bean
    ProcessDefinition<FeeRequests.RequestInput, FeeRequests.RequestOutput, ProcessContext> qbFeeRequest() {
        return FeeRequests.record();
    }

    @Bean
    ProcessDefinition<TransferProcesses.ApprovalResultInput, FeeRequests.RequestOutput, ProcessContext>
        qbFeeRequestApprovalResult() {
        return FeeRequests.approvalResult(zone);
    }

    @Bean
    EventSubscription<TransferProcesses.ApprovalResultInput> qbFeeRequestApprovedSubscription(
        ProcessDefinition<TransferProcesses.ApprovalResultInput, FeeRequests.RequestOutput, ProcessContext>
            qbFeeRequestApprovalResult) {
        return EventSubscription.of("qb.fee-request-approved", ApprovalProcesses.APPROVED_EVENT,
            qbFeeRequestApprovalResult, TransferProcesses::resultInput);
    }

    @Bean
    EventSubscription<TransferProcesses.ApprovalResultInput> qbFeeRequestRejectedSubscription(
        ProcessDefinition<TransferProcesses.ApprovalResultInput, FeeRequests.RequestOutput, ProcessContext>
            qbFeeRequestApprovalResult) {
        return EventSubscription.of("qb.fee-request-rejected", ApprovalProcesses.REJECTED_EVENT,
            qbFeeRequestApprovalResult, TransferProcesses::resultInput);
    }

    // ---- F-S4b: the platform fee Kudos lighting a node grants ----------------------------------------------------------

    /** How many lots one settlement grants at most (the rest by the next one or the daily job; not business). */
    @Value("${quizbuks.tree-grants.max-per-run:50}")
    int treeGrantsMaxPerRun;

    @Bean
    ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> qbTreeLitGrant() {
        return LightGrants.settle(treeGrantsMaxPerRun);
    }

    @Bean
    com.jabiz.event.EventSubscription<FeeCredits.SponsorRef> qbTreeGrantDueSubscription(
        ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> qbTreeLitGrant) {
        return com.jabiz.event.EventSubscription.of(LightGrants.DUE_CONSUMER, LightGrants.DUE_EVENT, qbTreeLitGrant,
            event -> new FeeCredits.SponsorRef(String.valueOf(event.payload().get("sponsorId"))));
    }

    /** A revoked lighting: the subject is settled (a Taker's has no lots: nothing is done). */
    @Bean
    com.jabiz.event.EventSubscription<FeeCredits.SponsorRef> qbTreeGrantRevokedSubscription(
        ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> qbTreeLitGrant) {
        return com.jabiz.event.EventSubscription.of(LightGrants.REVOKED_CONSUMER,
            com.jabiz.quizbuks.tree.TreeRevocations.REVOKED_EVENT, qbTreeLitGrant,
            event -> new FeeCredits.SponsorRef(String.valueOf(event.payload().get("userId"))));
    }

    /** Whatever the events missed (a failure, the switch off, a voided invoice's refund), each Sponsor in a transaction. */
    @Bean
    JobDefinition<FeeCredits.SponsorRef> qbTreeGrantRetryJob(
        ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> qbTreeLitGrant) {
        return JobDefinition.forEach(LightGrants.RETRY_JOB, LightGrants.RETRY_CRON, zone,
                JobItems.template(LightGrants.DUE_TEMPLATE, "sponsorId"),
                qbTreeLitGrant,
                (scheduledTime, row) -> new FeeCredits.SponsorRef(String.valueOf(row.get("sponsorId"))))
            .maxItems(10_000);
    }

    // ---- F-M3: the top-up of a verified company ---------------------------------------------------------------------

    @Bean
    ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> qbFeeCreditCertifiedTopUp() {
        return CertifiedTopUps.item();
    }

    /**
     * A verification decided (or revoked: the item then finds nothing due): the top-up, in a transaction of its own
     * after the verification's (the review of F-M3, L2: a failure here never rolls the verification back).
     */
    @Bean
    com.jabiz.event.EventSubscription<FeeCredits.SponsorRef> qbFeeCreditCertifiedSubscription(
        ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> qbFeeCreditCertifiedTopUp) {
        return com.jabiz.event.EventSubscription.of(CertifiedTopUps.CONSUMER,
            com.jabiz.quizbuks.verification.VerificationProcesses.DECIDED_EVENT, qbFeeCreditCertifiedTopUp,
            event -> new FeeCredits.SponsorRef(String.valueOf(event.payload().get("sponsorId"))));
    }

    /** The top-ups the event could not make (a failure, the switch off, a ban), each Sponsor in a transaction. */
    @Bean
    JobDefinition<FeeCredits.SponsorRef> qbFeeCreditCertifiedDueJob(
        ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> qbFeeCreditCertifiedTopUp) {
        return JobDefinition.forEach(CertifiedTopUps.DUE_JOB, CertifiedTopUps.DUE_CRON, zone,
                JobItems.template(CertifiedTopUps.DUE_TEMPLATE, "sponsorId"),
                qbFeeCreditCertifiedTopUp,
                (scheduledTime, row) -> new FeeCredits.SponsorRef(String.valueOf(row.get("sponsorId"))))
            .maxItems(10_000);
    }

    // ---- F-M2: platform fee Kudos granted -------------------------------------------------------------------------

    @Bean
    ProcessDefinition<FeeCredits.GrantInput, FeeCredits.GrantOutput, ProcessContext> qbFeeCreditGrant() {
        return FeeCredits.grant();
    }

    @Bean
    ProcessDefinition<FeeCredits.OnboardingInput, FeeCredits.GrantOutput, ProcessContext> qbFeeCreditOnboarding() {
        return FeeCredits.onboarding();
    }

    @Bean
    ProcessDefinition<FeeCredits.GrantRef, FeeProcesses.ItemOutput, ProcessContext> qbFeeCreditExpireItem() {
        return FeeCredits.expireItem();
    }

    @Bean
    ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> qbFeeCreditRemindItem() {
        return FeeCredits.remindItem();
    }

    /** Every day at 02:47 in the fiscal year's zone (Japan's): the lots expired with something left, voided. */
    @Bean
    JobDefinition<FeeCredits.GrantRef> qbFeeCreditExpireJob(
        ProcessDefinition<FeeCredits.GrantRef, FeeProcesses.ItemOutput, ProcessContext> qbFeeCreditExpireItem) {
        return JobDefinition.forEach(FeeCredits.EXPIRE_JOB, "0 47 2 * * *", zone,
                JobItems.template(FeeCredits.EXPIRE_DUE_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "grantId"),
                qbFeeCreditExpireItem,
                (scheduledTime, row) -> new FeeCredits.GrantRef(String.valueOf(row.get("grantId"))))
            .maxItems(10_000);
    }

    /** Every day at 10:13 in the fiscal year's zone: the sponsors whose lots expire within the notice, told. */
    @Bean
    JobDefinition<FeeCredits.SponsorRef> qbFeeCreditRemindJob(
        ProcessDefinition<FeeCredits.SponsorRef, FeeProcesses.ItemOutput, ProcessContext> qbFeeCreditRemindItem) {
        return JobDefinition.forEach(FeeCredits.REMIND_JOB, "0 13 10 * * *", zone,
                JobItems.template(FeeCredits.REMIND_DUE_TEMPLATE, scheduledTime -> Map.of("at", scheduledTime),
                    "sponsorId"),
                qbFeeCreditRemindItem,
                (scheduledTime, row) -> new FeeCredits.SponsorRef(String.valueOf(row.get("sponsorId"))))
            .maxItems(10_000);
    }

    // ---- jobs (the second of each month, Japan's: the fee of the month that begins, the invoices of the month that ended)

    /** The monthly fee, charged on the 2nd at 01:07 (Japan) for the month that began. */
    @Bean
    JobDefinition<FeeProcesses.MonthlyInput> qbFeeMonthlyJob(
        ProcessDefinition<FeeProcesses.MonthlyInput, FeeProcesses.ItemOutput, ProcessContext> qbFeeMonthlyItem) {
        return JobDefinition.forEach(FeeProcesses.MONTHLY_JOB, "0 7 16 1 * *", ZoneOffset.UTC,
                JobItems.template(FeeProcesses.MONTHLY_DUE_TEMPLATE,
                    scheduledTime -> Map.of("period", FeeRules.period(scheduledTime, zone)), "sponsorId"),
                qbFeeMonthlyItem,
                (scheduledTime, row) -> new FeeProcesses.MonthlyInput(String.valueOf(row.get("sponsorId")),
                    FeeRules.period(scheduledTime, zone)))
            .maxItems(10_000);
    }

    /**
     * The postpaid invoices (F-M6, F-M.md §9.6): every day at 01:17 in the fiscal year's zone except the 1st (the monthly
     * distribution settlement on the 2nd at 00:37 comes first), each sponsor's earliest month that ended with postpaid
     * fees not invoiced and every distribution fee due for it settled.
     */
    @Bean
    JobDefinition<FeeProcesses.InvoiceInput> qbFeeInvoiceJob(
        ProcessDefinition<FeeProcesses.InvoiceInput, FeeProcesses.ItemOutput, ProcessContext> qbFeeInvoiceItem) {
        return JobDefinition.forEach(FeeProcesses.INVOICE_JOB, "0 17 1 2-31 * *", zone,
                JobItems.template(FeeProcesses.INVOICES_DUE_TEMPLATE,
                    scheduledTime -> Map.of("current", FeeRules.period(scheduledTime, zone), "zone", zone.getId()),
                    "sponsorId"),
                qbFeeInvoiceItem,
                (scheduledTime, row) -> new FeeProcesses.InvoiceInput(String.valueOf(row.get("sponsorId")),
                    String.valueOf(row.get("period"))))
            .maxItems(10_000);
    }

    /** F-M6: a publication's distribution fee settled for a month (the item of both jobs). */
    @Bean
    ProcessDefinition<DistributionSettlements.SettleRef, FeeProcesses.ItemOutput, ProcessContext>
        qbDistributionSettle() {
        return DistributionSettlements.settle(zone);
    }

    /** F-M6: the distribution fees of the month that ended, on the 2nd at 00:37 in the fiscal year's zone. */
    @Bean
    JobDefinition<DistributionSettlements.SettleRef> qbDistributionMonthlyJob(
        ProcessDefinition<DistributionSettlements.SettleRef, FeeProcesses.ItemOutput, ProcessContext>
            qbDistributionSettle,
        @Value("${quizbuks.distribution.monthly-cron:0 37 0 2 * *}") String cron) {
        return JobDefinition.forEach(DistributionSettlements.MONTHLY_JOB, cron, zone,
                JobItems.template(DistributionSettlements.DUE_TEMPLATE, scheduledTime -> {
                    String period = previousPeriod(scheduledTime);
                    return Map.of("period", period, "periodStart", FeeRules.periodStart(period, zone),
                        "periodEnd", FeeRules.periodStart(FeeRules.period(scheduledTime, zone), zone));
                }, "publicationId"),
                qbDistributionSettle,
                (scheduledTime, row) -> new DistributionSettlements.SettleRef(String.valueOf(row.get("publicationId")),
                    String.valueOf(row.get("sponsorId")), previousPeriod(scheduledTime), false))
            .maxItems(10_000);
    }

    /**
     * F-M6: the final settlement of the publications that ended, every day at 03:07 in the fiscal year's zone (before
     * the prepayments' release at 03:23).
     */
    @Bean
    JobDefinition<DistributionSettlements.SettleRef> qbDistributionFinalJob(
        ProcessDefinition<DistributionSettlements.SettleRef, FeeProcesses.ItemOutput, ProcessContext>
            qbDistributionSettle,
        @Value("${quizbuks.distribution.final-cron:0 7 3 * * *}") String cron) {
        return JobDefinition.forEach(DistributionSettlements.FINAL_JOB, cron, zone,
                JobItems.template(DistributionSettlements.FINAL_DUE_TEMPLATE,
                    scheduledTime -> Map.of("at", scheduledTime), "publicationId"),
                qbDistributionSettle,
                (scheduledTime, row) -> new DistributionSettlements.SettleRef(String.valueOf(row.get("publicationId")),
                    String.valueOf(row.get("sponsorId")), null, true))
            .maxItems(10_000);
    }

    /** F-M8: a publication's exposure fee settled for a month (the item of both jobs). */
    @Bean
    ProcessDefinition<ExposureSettlements.SettleRef, FeeProcesses.ItemOutput, ProcessContext> qbExposureSettle() {
        return ExposureSettlements.settle(zone);
    }

    /** F-M8: the exposure fees of the month that ended, on the 2nd at 00:47 in the fiscal year's zone. */
    @Bean
    JobDefinition<ExposureSettlements.SettleRef> qbExposureMonthlyJob(
        ProcessDefinition<ExposureSettlements.SettleRef, FeeProcesses.ItemOutput, ProcessContext> qbExposureSettle,
        @Value("${quizbuks.exposure.monthly-cron:0 47 0 2 * *}") String cron) {
        return JobDefinition.forEach(ExposureSettlements.MONTHLY_JOB, cron, zone,
                JobItems.template(ExposureSettlements.DUE_TEMPLATE, scheduledTime -> {
                    String period = previousPeriod(scheduledTime);
                    return Map.of("period", period, "periodStart", FeeRules.periodStart(period, zone),
                        "periodEnd", FeeRules.periodStart(FeeRules.period(scheduledTime, zone), zone));
                }, "publicationId"),
                qbExposureSettle,
                (scheduledTime, row) -> new ExposureSettlements.SettleRef(String.valueOf(row.get("publicationId")),
                    String.valueOf(row.get("sponsorId")), previousPeriod(scheduledTime), false))
            .maxItems(10_000);
    }

    /**
     * F-M8: the final settlement of the publications that ended, every day at 03:12 in the fiscal year's zone (after the
     * distribution fee's at 03:07, before the prepayments' release at 03:23).
     */
    @Bean
    JobDefinition<ExposureSettlements.SettleRef> qbExposureFinalJob(
        ProcessDefinition<ExposureSettlements.SettleRef, FeeProcesses.ItemOutput, ProcessContext> qbExposureSettle,
        @Value("${quizbuks.exposure.final-cron:0 12 3 * * *}") String cron) {
        return JobDefinition.forEach(ExposureSettlements.FINAL_JOB, cron, zone,
                JobItems.template(ExposureSettlements.FINAL_DUE_TEMPLATE,
                    scheduledTime -> Map.of("at", scheduledTime), "publicationId"),
                qbExposureSettle,
                (scheduledTime, row) -> new ExposureSettlements.SettleRef(String.valueOf(row.get("publicationId")),
                    String.valueOf(row.get("sponsorId")), null, true))
            .maxItems(10_000);
    }

    /** Every day at 02:37 (Japan): the invoices past their due day whose reminder is due. */
    @Bean
    JobDefinition<FeeProcesses.InvoiceRef> qbFeeOverdueJob(
        ProcessDefinition<FeeProcesses.InvoiceRef, FeeProcesses.ItemOutput, ProcessContext> qbFeeOverdueItem) {
        return JobDefinition.forEach(FeeProcesses.OVERDUE_JOB, "0 37 17 * * *", ZoneOffset.UTC,
                JobItems.template(FeeProcesses.OVERDUE_DUE_TEMPLATE, scheduledTime -> Map.of(
                    "today", scheduledTime.atZone(zone).toLocalDate(),
                    "remindedBefore", scheduledTime.minus(java.time.Duration.ofDays(QbFees.REMINDER_DAYS))),
                    "invoiceId"),
                qbFeeOverdueItem,
                (scheduledTime, row) -> new FeeProcesses.InvoiceRef(String.valueOf(row.get("invoiceId"))))
            .maxItems(10_000);
    }

    private String previousPeriod(java.time.Instant scheduledTime) {
        return FeeRules.period(FeeRules.periodStart(FeeRules.period(scheduledTime, zone), zone).minusSeconds(1), zone);
    }

    // ---- retention (platform 16v-1): ten years from the fiscal year's end, as the receipts ----------------------------

    static final Period RETENTION = Period.ofYears(10);

    @Bean
    RetentionPolicy qbFeeChargeRetention() {
        return RetentionPolicy.of(QbFees.CHARGE).keep(RETENTION).from("chargedAt").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy qbFeeInvoiceRetention() {
        return RetentionPolicy.of(QbFees.INVOICE).keep(RETENTION).from("issuedAt").afterFiscalYearEnd();
    }

    /** F-M7: credit notes and what their credit paid are accounting evidence as invoices are. */
    @Bean
    RetentionPolicy qbFeeCreditNoteRetention() {
        return RetentionPolicy.of(QbCreditNotes.CREDIT_NOTE).keep(RETENTION).from("issuedAt").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy qbFeeCreditNoteUseRetention() {
        return RetentionPolicy.of(QbCreditNotes.USE).keep(RETENTION).from("usedAt").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy qbFeeCreditNoteDocumentRetention() {
        return RetentionPolicy.of(QbCreditNotes.DOCUMENT).keep(RETENTION).from("issuedAt").afterFiscalYearEnd();
    }

    @Bean
    RetentionPolicy qbFeePaymentRetention() {
        return RetentionPolicy.of(QbFeeAccount.PAYMENT).keep(RETENTION).from("paidAt").afterFiscalYearEnd();
    }
}
