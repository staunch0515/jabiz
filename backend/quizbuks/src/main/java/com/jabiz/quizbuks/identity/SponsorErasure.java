package com.jabiz.quizbuks.identity;

import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.fee.QbPrepayments;
import com.jabiz.quizbuks.invitation.QbInvitations;
import com.jabiz.quizbuks.mailserver.QbMailServers;
import com.jabiz.quizbuks.messaging.BroadcastProcesses;
import com.jabiz.quizbuks.messaging.QbInbox;
import com.jabiz.quizbuks.messaging.QbMessaging;
import com.jabiz.quizbuks.page.QbSponsorPages;
import com.jabiz.quizbuks.page.QbSponsorProducts;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.reward.CashRewardStatus;
import com.jabiz.quizbuks.reward.QbRewards;
import com.jabiz.quizbuks.settings.QbSettings;
import com.jabiz.quizbuks.wallet.QbTopUps;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.TopUpStatus;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.quizbuks.benefit.QbBenefits;
import com.jabiz.security.erasure.ErasedEntity;
import com.jabiz.security.erasure.ErasureLoad;
import com.jabiz.security.erasure.ErasureNotice;
import com.jabiz.security.erasure.ErasureSubject;
import com.jabiz.security.erasure.PersonalDataEraser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A sponsor's account deleted (Q72; docs/quizbuks/plans/Q72-sponsor-erasure.md; platform decision D62): what QuizBuks
 * erases of the user when the platform erases the account ({@code ACCOUNT_ERASE}, the rows below in this order), what
 * the user is told before asking, and what happens after: their running publications end and their notices leave
 * every inbox ({@value #ERASED}, on the platform's {@code jabiz.account.erased}).
 *
 * <p>Their page, products, mail server (with its password), tokens and staff, AI jobs, invitations (addresses others
 * gave them), profile and inbox go; their onboarding is tombstoned and its history overwritten by the purge. Their
 * quizzes, publications and notices stay as the records that takers' attempts and rewards point to (the requester's
 * choice, 2026-10-31: offline and kept). Money stays as it is (wallet, ledger, top-ups and their receipts, kept ten
 * years; rewards): only a top-up's payer name is overwritten by the purge. The counts of their page and products go
 * with the account (the platform's counters, {@code ownerIsUser}).
 */
@Configuration
public class SponsorErasure {

    /** The internal process run after a user's account was erased (on {@code jabiz.account.erased}). */
    public static final String ERASED = "QB_SPONSOR_ERASED";
    /** The platform's event that an account was erased (its consumers run after the erasure committed). */
    static final String ACCOUNT_ERASED_EVENT = "jabiz.account.erased";

    static final String PUBLICATIONS = "publications";
    static final String WALLETS = "wallets";
    static final String CASH = "cashRewards";
    /** F-M15 a: the platform fee invoices still owed: how many, and their totals, credit applied and amounts paid. */
    static final String UNPAID = "feeUnpaid";
    static final String UNPAID_TOTAL = "feeUnpaidTotal";
    static final String UNPAID_CREDITED = "feeUnpaidCredited";
    static final String UNPAID_PAID = "feeUnpaidPaid";
    /**
     * Whether the sponsor may have postpaid fees not invoiced yet (which have no figure: nothing marks a fee "not
     * invoiced"): their own billing mode written on the onboarding row is POSTPAID ({@value #POSTPAID}), or an open
     * postpaid prepayment counts against their credit limit ({@value #POSTPAID_OPEN}), or a postpaid fee was ever charged
     * to them ({@value #POSTPAID_CHARGES}: one who switched to prepaid this month still has last fees to be invoiced).
     * A load cannot read the parameter {@code qb.fee.default-billing-mode}: a sponsor postpaid by that default is caught
     * by the other two once charged. That default is PREPAID (pinned by SponsorErasureTest); before it is changed to
     * POSTPAID, revisit these loads (operations.md §7.51).
     */
    static final String POSTPAID = "feePostpaid";
    static final String POSTPAID_OPEN = "feePostpaidOpen";
    static final String POSTPAID_CHARGES = "feePostpaidCharges";
    /** The open prepayments of their publications that hold money: how many, and what is left of them. */
    static final String DEPOSITS = "feeDeposits";
    static final String DEPOSITS_LEFT = "feeDepositsLeft";
    /** F-M15c: what the AI credit holds. */
    static final String AI_CREDIT_LEFT = "aiCreditLeft";
    /** Every open prepayment that holds money, an AI credit's too: granted credit may be frozen in them. */
    static final String FROZEN = "feeFrozen";
    /** The top-ups whose money has not arrived yet. */
    static final String TOPUPS = "topUpsPending";

    /** The notices' codes (messages {@code erasure.notice.<code>}). */
    static final String NOTICE_PUBLICATIONS = "qb.publications-end";
    static final String NOTICE_BALANCE = "qb.wallet-balance";
    static final String NOTICE_CASH = "qb.cash-rewards-open";
    static final String NOTICE_FEE_UNPAID = "qb.fee-unpaid";
    /** A postpaid sponsor without unpaid invoices: fees not invoiced yet, without a figure. */
    static final String NOTICE_FEE_UNBILLED = "qb.fee-unbilled";
    static final String NOTICE_FEE_SHORTFALL = "qb.fee-shortfall";
    static final String NOTICE_DEPOSITS = "qb.deposits-held";
    /** F-M15c: the AI credit is given back and closed at the wind-up; its jobs still running are cancelled. */
    static final String NOTICE_AI_CREDIT = "qb.ai-credit";
    static final String NOTICE_GIFT_VOID = "qb.gift-void";
    /** No granted credit in the account, but some may be frozen in prepayments: without a figure. */
    static final String NOTICE_GIFT_FROZEN = "qb.gift-frozen";
    static final String NOTICE_CASH_LEFT = "qb.cash-left";
    static final String NOTICE_TOPUPS = "qb.topup-pending";

    /** The wallets whose balance is Kudos (a sponsor's, a taker's); the cash and the granted credit are told apart. */
    static final Set<String> KUDOS_WALLETS = Set.of(QbWallets.SPONSOR, QbWallets.TAKER);
    /** The purposes of a publication's prepayments (an AI credit's is F-M15 c's notice). */
    static final Set<String> PUBLICATION_PURPOSES = Set.of(QbPrepayments.DISTRIBUTION, QbPrepayments.EXPOSURE,
        QbPrepayments.AUDIENCE);
    /** A top-up whose money may still arrive: a card's checkout under way, a bank transfer not yet booked. */
    static final Set<String> PENDING_TOPUPS = Set.of(TopUpStatus.OPENING, TopUpStatus.OPEN, TopUpStatus.AWAITING,
        TopUpStatus.REPORTED, TopUpStatus.REVIEW);

    static final Set<String> OPEN_CASH = Set.of(CashRewardStatus.DUE, CashRewardStatus.MARKED_PAID,
        CashRewardStatus.DISPUTED, CashRewardStatus.OVERDUE);

    /** @param userId the erased user */
    public record ErasedInput(@NotBlank String userId) {}

    /** @param endedPublications the publications ended; @param withdrawnNotices the notices withdrawn */
    public record ErasedOutput(int endedPublications, int withdrawnNotices) {}

    @Bean
    PersonalDataEraser qbSponsorPersonalData() {
        return new PersonalDataEraser() {
            @Override
            public List<ErasedEntity> entities() {
                return List.of(
                    // What only the sponsor uses: gone, with its personal values masked in the audit records.
                    ErasedEntity.delete(QbMailServers.ENTITY, "sponsorId", "host", "username", "fromAddress",
                        "fromName", "lastError"),
                    ErasedEntity.delete(QbRewards.REDEEM_TOKEN, "sponsorId"),
                    ErasedEntity.delete(QbRewards.STAFF, "sponsorId"),
                    // Q89c: the passes for the sponsor's counter.
                    ErasedEntity.delete(QbRewards.PASS, "handlerId"),
                    // F-C1: the asset library and where its assets were copied to (what was copied goes or stays
                    // with the quizzes and publications it is in).
                    ErasedEntity.delete(com.jabiz.quizbuks.asset.QbAssets.USE, "sponsorId"),
                    ErasedEntity.delete(com.jabiz.quizbuks.asset.QbAssets.ASSET, "sponsorId", "name", "description",
                        "translations", "tags", "image", "pdf", "audio", "url", "body", "prizeDescription"),
                    // F-C2: the daily usage snapshots (numbers only).
                    ErasedEntity.delete(com.jabiz.quizbuks.usage.QbUsage.DAY, "sponsorId"),
                    // F-C4: the records of their sponsor packages' imports.
                    ErasedEntity.delete(com.jabiz.quizbuks.sponsorpackage.QbPackageImports.IMPORT, "sponsorId"),
                    // F-M3: their company verifications (the address the link went to masked in the audit records);
                    // the domain is free again for the company's next account. The platform fee Kudos follow the
                    // wallet's own erasure.
                    ErasedEntity.delete(com.jabiz.quizbuks.verification.QbVerifications.VERIFICATION, "sponsorId",
                        "confirmAddress"),
                    ErasedEntity.delete(QbSponsorProducts.QUIZ_LINK, "ownerId"),
                    ErasedEntity.delete(QbSponsorProducts.PRODUCT, "userId", "name", "description", "image",
                        "shopUrl", "translations"),
                    ErasedEntity.delete(QbSponsorPages.PAGE, "userId", "handle", "headline", "cover", "website",
                        "translations"),
                    ErasedEntity.delete(QbAi.JOB, "sponsorId", "candidates", "failureMessage"),
                    // Addresses of people the sponsor invited: theirs, not the sponsor's to keep.
                    ErasedEntity.delete(QbInvitations.INVITATION, "publisherId", "email"),
                    // The account's own: profile, settings and inbox.
                    ErasedEntity.delete(QbAccounts.PROFILE, "userId", "displayName", "city"),
                    // F-S9: with the topics they liked (masked in the audit records too).
                    ErasedEntity.delete(QbSettings.SETTINGS, "userId", "publicHandle", "likedTopics"),

                    ErasedEntity.delete(QbInbox.STATE, "userId"),
                    ErasedEntity.delete(QbInbox.MESSAGE, "recipientId", "title", "body"),
                    ErasedEntity.delete(QbMessaging.NOTICE, "recipientId"),
                    // Q82: what the user wrote in conversations, as a taker or as a sponsor: the rows stay (the other
                    // side sees a message was there), the text goes.
                    ErasedEntity.keep(com.jabiz.quizbuks.conversation.QbConversations.MESSAGE, "senderUserId",
                        "body"),
                    // Q89a: what they wrote to other sponsors stays as a row, the text goes (as Q82); a benefit they
                    // listed keeps its business description but loses its images (the catalog no longer shows it: its
                    // provider's onboarding is gone); purchases are business records between two sponsors and stay,
                    // only the free text each side wrote is overwritten.
                    ErasedEntity.keep(QbBenefits.MESSAGE, "senderUserId", "body"),
                    ErasedEntity.clear(QbBenefits.OFFER, "providerId", "image1", "image2", "image3", "image4"),
                    ErasedEntity.keep(QbBenefits.PURCHASE, "sponsorId", "note"),
                    ErasedEntity.keep(QbBenefits.PURCHASE, "providerId", "declineReason"),
                    // Q89d-1: a suspension's reason, and the incidents' texts, as written by the erased sponsor.
                    ErasedEntity.keep(QbBenefits.PURCHASE, "suspendedBy", "suspendReason"),
                    ErasedEntity.keep(QbBenefits.INCIDENT, "recordedBy", "note"),
                    ErasedEntity.keep(QbBenefits.INCIDENT, "resolvedBy", "resolution"),
                    // F-S14: a shared video's use is a record between two sponsors and stays (as a purchase); only the
                    // free text each side wrote goes: the user's note, the provider's reason for sending it back.
                    ErasedEntity.keep(com.jabiz.quizbuks.videouse.QbVideoUses.USE, "sponsorId", "note"),
                    ErasedEntity.keep(com.jabiz.quizbuks.videouse.QbVideoUses.USE, "providerId", "rejectReason"),
                    // The onboarding: tombstoned; the purge overwrites who they are in every version.
                    ErasedEntity.delete(QbAccounts.SPONSOR, "userId", "contactName", "company", "intro", "avatar",
                        "introTranslations"),
                    // Money stays (ten years): only who paid a bank transfer and why one was entered by hand
                    // (F-M15 a0) are overwritten. The fees' texts: FeeErasure.
                    ErasedEntity.keep(QbTopUps.TOPUP, "sponsorId", "payerName", "manualReason"),
                    // F-M7: credit notes stay as the invoices they credit do; the purge overwrites finance's
                    // explanation (it may name the company), and their documents, which name it, unless retained with
                    // the receipts' (RetentionPolicy of DocumentRun).
                    ErasedEntity.keep(com.jabiz.quizbuks.fee.QbCreditNotes.CREDIT_NOTE, "sponsorId", "note")
                        .documents(com.jabiz.quizbuks.fee.QbCreditNotes.LAYOUT_ID));
            }

            @Override
            public List<ErasureLoad> loads() {
                return List.of(
                    // F-M15 a: counts with fixed conditions, however many there are (a row load reads at most 100,
                    // more is an error); the cards, ordinary rows kept in step with the publications' states.
                    ErasureLoad.count(PUBLICATIONS, QbPublications.defaultDataset(QbPublications.CARD), "publisherId")
                        .where("publisherKind", PublisherKind.SPONSOR)
                        .where("status", PublicationAdmin.ENDABLE.stream().sorted().toArray()),
                    ErasureLoad.of(WALLETS, QbWallets.defaultDataset(QbWallets.WALLET), "ownerId"),
                    ErasureLoad.count(CASH, QbRewards.defaultDataset(QbRewards.CASH_REWARD), "publisherId")
                        .where("status", OPEN_CASH.stream().sorted().toArray()),
                    // F-M15 a: the platform fee account, counted and added up in the database.
                    unpaid(ErasureLoad.count(UNPAID, INVOICES, "sponsorId")),
                    unpaid(ErasureLoad.sum(UNPAID_TOTAL, INVOICES, "sponsorId", "total")),
                    unpaid(ErasureLoad.sum(UNPAID_CREDITED, INVOICES, "sponsorId", "creditApplied")),
                    unpaid(ErasureLoad.sum(UNPAID_PAID, INVOICES, "sponsorId", "paidAmount")),
                    ErasureLoad.count(POSTPAID, QbAccounts.defaultDataset(QbAccounts.SPONSOR), "userId")
                        .where("billingMode", FeeRules.POSTPAID),
                    ErasureLoad.count(POSTPAID_OPEN, PREPAYMENTS, "sponsorId").where("status", QbPrepayments.OPEN)
                        .where("mode", QbPrepayments.POSTPAID),
                    ErasureLoad.count(POSTPAID_CHARGES, QbFees.defaultDataset(QbFees.CHARGE), "sponsorId")
                        .where("billingMode", FeeRules.POSTPAID),
                    held(ErasureLoad.count(DEPOSITS, PREPAYMENTS, "sponsorId"))
                        .where("purpose", PUBLICATION_PURPOSES.stream().sorted().toArray()),
                    held(ErasureLoad.sum(DEPOSITS_LEFT, PREPAYMENTS, "sponsorId", "remaining"))
                        .where("purpose", PUBLICATION_PURPOSES.stream().sorted().toArray()),
                    held(ErasureLoad.count(FROZEN, PREPAYMENTS, "sponsorId")),
                    // F-M15c: the AI credit (one a sponsor), added up in the database.
                    ErasureLoad.sum(AI_CREDIT_LEFT, PREPAYMENTS, "sponsorId", "remaining")
                        .where("purpose", QbPrepayments.AI).where("status", QbPrepayments.OPEN),
                    ErasureLoad.count(TOPUPS, QbTopUps.defaultDataset(QbTopUps.TOPUP), "sponsorId")
                        .where("status", PENDING_TOPUPS.stream().sorted().toArray()));
            }

            @Override
            public List<ErasureNotice> notices(ErasureSubject subject) {
                return SponsorErasure.notices(subject);
            }
        };
    }

    static final String INVOICES = QbFees.defaultDataset(QbFees.INVOICE);
    static final String PREPAYMENTS = QbFees.defaultDataset(QbPrepayments.PREPAYMENT);

    private static ErasureLoad unpaid(ErasureLoad load) {
        return load.where("status", QbFees.UNPAID.toArray());
    }

    /** Open and holding money (a postpaid sponsor's only counts against their credit limit). */
    private static ErasureLoad held(ErasureLoad load) {
        return load.where("status", QbPrepayments.OPEN).where("mode", QbPrepayments.PREPAID);
    }

    /**
     * What a sponsor should know before asking (they inform, they do not refuse): running publications end, a balance
     * left in the Kudos wallet is not paid out, and rewards owed to takers are still owed; F-M15 a: what happens to the
     * platform fee account when the account is erased (docs/quizbuks/plans/impl/F-M15.md §2, §3): unpaid invoices are
     * paid from it (and what it may not cover stays owed), prepayments are settled, the granted credit left is voided,
     * the cash stays in the closed account, and money still arriving goes there too. The figures are those of the
     * moment of asking; the account is erased after the cooling-off period.
     */
    static List<ErasureNotice> notices(ErasureSubject subject) {
        List<ErasureNotice> notices = new ArrayList<>();
        long running = subject.count(PUBLICATIONS);
        if (running > 0) {
            notices.add(ErasureNotice.of(NOTICE_PUBLICATIONS, Map.of("count", running)));
        }
        List<Map<String, Object>> wallets = subject.rows(WALLETS);
        BigDecimal balance = wallets.stream().filter(row -> KUDOS_WALLETS.contains(String.valueOf(row.get("kind"))))
            .map(row -> amount(row.get("balance"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (balance.signum() > 0) {
            notices.add(ErasureNotice.of(NOTICE_BALANCE, Map.of("balance", balance.toPlainString())));
        }
        long open = subject.count(CASH);
        if (open > 0) {
            notices.add(ErasureNotice.of(NOTICE_CASH, Map.of("count", open)));
        }
        BigDecimal gift = wallets.stream().filter(row -> QbWallets.FEE_CREDIT.equals(row.get("kind")))
            .map(row -> amount(row.get("balance"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cash = wallets.stream().filter(row -> QbWallets.CASH.equals(row.get("kind")))
            .map(row -> amount(row.get("balance"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashHeld = wallets.stream().filter(row -> QbWallets.CASH.equals(row.get("kind")))
            .map(row -> amount(row.get("held"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        long invoices = subject.count(UNPAID);
        // What the invoices still owe: QbFees.owed (total less credit applied less paid) added up, but subtracted on the
        // three totals. QbFees.owed floors each invoice at 0; the table's check (paid + credit applied <= total) keeps
        // every invoice's difference at 0 or more, so the sum of the differences is the sum of what each owes. The
        // floor below only guards the total.
        BigDecimal owed = subject.sum(UNPAID_TOTAL).subtract(subject.sum(UNPAID_CREDITED))
            .subtract(subject.sum(UNPAID_PAID)).max(BigDecimal.ZERO);
        if (invoices > 0) {
            notices.add(ErasureNotice.of(NOTICE_FEE_UNPAID, Map.of("count", invoices, "amount", owed.toPlainString())));
        } else if (subject.count(POSTPAID) > 0 || subject.count(POSTPAID_OPEN) > 0
            || subject.count(POSTPAID_CHARGES) > 0) {
            notices.add(ErasureNotice.of(NOTICE_FEE_UNBILLED, Map.of()));
        }
        // What pays them now: the granted credit, then the cash not held by a dispute (FeeAllocation's order).
        BigDecimal available = gift.max(BigDecimal.ZERO).add(cash.subtract(cashHeld).max(BigDecimal.ZERO));
        if (owed.compareTo(available) > 0) {
            notices.add(ErasureNotice.of(NOTICE_FEE_SHORTFALL, Map.of("available", available.toPlainString(),
                "short", owed.subtract(available).toPlainString())));
        }
        long deposits = subject.count(DEPOSITS);
        if (deposits > 0) {
            notices.add(ErasureNotice.of(NOTICE_DEPOSITS, Map.of("count", deposits,
                "amount", subject.sum(DEPOSITS_LEFT).toPlainString())));
        }
        BigDecimal aiCredit = subject.sum(AI_CREDIT_LEFT);
        if (aiCredit.signum() > 0) {
            notices.add(ErasureNotice.of(NOTICE_AI_CREDIT, Map.of("amount", aiCredit.toPlainString())));
        }
        if (gift.signum() > 0) {
            notices.add(ErasureNotice.of(NOTICE_GIFT_VOID, Map.of("amount", gift.toPlainString())));
        } else if (subject.count(FROZEN) > 0) {
            notices.add(ErasureNotice.of(NOTICE_GIFT_FROZEN, Map.of()));
        }
        if (cash.signum() > 0) {
            notices.add(ErasureNotice.of(NOTICE_CASH_LEFT, Map.of("amount", cash.toPlainString())));
        }
        long topUps = subject.count(TOPUPS);
        if (topUps > 0) {
            notices.add(ErasureNotice.of(NOTICE_TOPUPS, Map.of("count", topUps)));
        }
        return List.copyOf(notices);
    }

    private static BigDecimal amount(Object value) {
        return value instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO;
    }

    @Bean
    ProcessDefinition<ErasedInput, ErasedOutput, ProcessContext> qbSponsorErased() {
        return ProcessDefinition.define(ERASED, 1, ErasedInput.class, ErasedOutput.class, ProcessContext.class,
            pb -> {
                pb.description("After a user's account was erased: ends their running publications, withdraws "
                        + "their notices and shared videos, and cancels their AI jobs.")
                    .permissions(QbPermissions.PUBLICATION_SYSTEM)
                    .internal()
                    .contextFactory((start, input) -> AccountEditing.start(start, input,
                        AccountEditing.canonical(input.userId())))
                    .outputMapper(ctx -> new ErasedOutput(
                        ctx.contains("ended") ? ((List<?>) ctx.get("ended")).size() : 0,
                        ctx.contains("withdrawn") ? ((BroadcastProcesses.BanOutput) ctx.get("withdrawn")).withdrawn()
                            : 0));
                // The account's lock first, as a ban: the publications' pairs after it, in the order of their ids.
                AccountEditing.lock(pb);
                pb.step("Load the sponsor's publications", QueryEntities.<ProcessContext>of(
                    QbPublications.defaultDataset(QbPublications.PUBLICATION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("publisherKind", PublisherKind.SPONSOR),
                            new QueryPredicate.Eq("publisherId", AccountEditing.userId(ctx)))))
                        .limit(Integer.MAX_VALUE).build(), PUBLICATIONS));
                pb.step("End the running ones", CallProcess.forEach(PublicationAdmin.ERASED_END, 1,
                    SponsorErasure::ends, "ended"));
                pb.step("Withdraw their notices", CallProcess.<ProcessContext>of(BroadcastProcesses.BAN_WITHDRAW, 1,
                    ctx -> new BroadcastProcesses.SponsorRef(AccountEditing.userId(ctx)), "withdrawn"));
                // F-S12 (总控 2026-11-08): the videos they share are withdrawn (no new picks); the publications
                // submitted with them keep them until they end.
                pb.step("Load the videos they share", QueryEntities.<ProcessContext>of(
                    com.jabiz.quizbuks.sharedvideo.QbSharedVideos.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("providerId", AccountEditing.userId(ctx)),
                            new QueryPredicate.In("status", new ArrayList<Object>(
                                com.jabiz.quizbuks.sharedvideo.QbSharedVideos.STANDING)))))
                        .limit(Integer.MAX_VALUE).build(), SHARED_VIDEOS));
                pb.step("Withdraw the videos they share", CallProcess.forEach(
                    com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses.ERASED_WITHDRAW, 1,
                    SponsorErasure::sharedVideos, "sharedVideosWithdrawn"));
                // F-M15c: their AI jobs still open are cancelled (their own rows were deleted with the account; the
                // platform's are found by who asked for them). The wind-up of the fee account does it again while more
                // are left than a run reads.
                pb.step("Cancel their AI jobs", CallProcess.<ProcessContext>of(
                    com.jabiz.quizbuks.content.ai.ErasedAiJobs.CANCEL_ALL, 1,
                    ctx -> new com.jabiz.quizbuks.content.ai.ErasedAiJobs.SponsorRef(AccountEditing.userId(ctx)),
                    "aiJobsCancelled"));
            });
    }

    static final String SHARED_VIDEOS = "erasedSharedVideos";

    /** F-S12: the erased provider's shares still standing, in the order of their ids. */
    static List<com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses.ErasedWithdrawInput> sharedVideos(
        ProcessContext ctx) {
        return AccountEditing.list(ctx, SHARED_VIDEOS).stream().map(video -> String.valueOf(video.id())).sorted()
            .map(id -> new com.jabiz.quizbuks.sharedvideo.SharedVideoProcesses.ErasedWithdrawInput(id,
                AccountEditing.userId(ctx))).toList();
    }

    /** The erased sponsor's publications that still run, in the order of their ids. */
    static List<PublicationAdmin.BanPauseInput> ends(ProcessContext ctx) {
        return AccountEditing.list(ctx, PUBLICATIONS).stream()
            .filter(publication -> PublicationAdmin.ENDABLE.contains(String.valueOf((Object) publication.get("status"))))
            .map(publication -> String.valueOf(publication.id()))
            .sorted()
            .map(id -> new PublicationAdmin.BanPauseInput(UUID.fromString(id)))
            .toList();
    }

    @Bean
    EventSubscription<ErasedInput> qbSponsorErasedSubscription(
        ProcessDefinition<ErasedInput, ErasedOutput, ProcessContext> qbSponsorErased) {
        return EventSubscription.of("qb.account-erased", ACCOUNT_ERASED_EVENT, qbSponsorErased,
            SponsorErasure::erasedInput);
    }

    static ErasedInput erasedInput(DomainEvent event) {
        return new ErasedInput(String.valueOf(event.payload().get("userId")));
    }
}
