package com.jabiz.quizbuks.identity;

import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ai.QbAi;
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
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
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

    /** The notices' codes (messages {@code erasure.notice.<code>}). */
    static final String NOTICE_PUBLICATIONS = "qb.publications-end";
    static final String NOTICE_BALANCE = "qb.wallet-balance";
    static final String NOTICE_CASH = "qb.cash-rewards-open";

    private static final Set<String> OPEN_CASH = Set.of(CashRewardStatus.DUE, CashRewardStatus.MARKED_PAID,
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
                    ErasedEntity.delete(QbSponsorProducts.QUIZ_LINK, "ownerId"),
                    ErasedEntity.delete(QbSponsorProducts.PRODUCT, "userId", "name", "description", "image",
                        "shopUrl"),
                    ErasedEntity.delete(QbSponsorPages.PAGE, "userId", "handle", "headline", "cover", "website"),
                    ErasedEntity.delete(QbAi.JOB, "sponsorId", "candidates", "failureMessage"),
                    // Addresses of people the sponsor invited: theirs, not the sponsor's to keep.
                    ErasedEntity.delete(QbInvitations.INVITATION, "publisherId", "email"),
                    // The account's own: profile, settings and inbox.
                    ErasedEntity.delete(QbAccounts.PROFILE, "userId", "displayName", "city"),
                    ErasedEntity.delete(QbSettings.SETTINGS, "userId"),
                    ErasedEntity.delete(QbInbox.STATE, "userId"),
                    ErasedEntity.delete(QbInbox.MESSAGE, "recipientId", "title", "body"),
                    ErasedEntity.delete(QbMessaging.NOTICE, "recipientId"),
                    // The onboarding: tombstoned; the purge overwrites who they are in every version.
                    ErasedEntity.delete(QbAccounts.SPONSOR, "userId", "contactName", "company", "intro", "avatar"),
                    // Money stays (ten years): only who paid a bank transfer is overwritten.
                    ErasedEntity.keep(QbTopUps.TOPUP, "sponsorId", "payerName"));
            }

            @Override
            public List<ErasureLoad> loads() {
                return List.of(
                    ErasureLoad.of(PUBLICATIONS, QbPublications.defaultDataset(QbPublications.PUBLICATION),
                        "publisherId"),
                    ErasureLoad.of(WALLETS, QbWallets.defaultDataset(QbWallets.WALLET), "ownerId"),
                    ErasureLoad.of(CASH, QbRewards.defaultDataset(QbRewards.CASH_REWARD), "publisherId"));
            }

            @Override
            public List<ErasureNotice> notices(ErasureSubject subject) {
                return SponsorErasure.notices(subject);
            }
        };
    }

    /**
     * What a sponsor should know before asking (they inform, they do not refuse): running publications end, a balance
     * left in the wallet is not paid out, and rewards owed to takers are still owed.
     */
    static List<ErasureNotice> notices(ErasureSubject subject) {
        List<ErasureNotice> notices = new ArrayList<>();
        long running = subject.rows(PUBLICATIONS).stream()
            .filter(row -> PublisherKind.SPONSOR.equals(row.get("publisherKind"))
                && PublicationAdmin.ENDABLE.contains(String.valueOf(row.get("status"))))
            .count();
        if (running > 0) {
            notices.add(ErasureNotice.of(NOTICE_PUBLICATIONS, Map.of("count", running)));
        }
        BigDecimal balance = subject.rows(WALLETS).stream()
            .map(row -> row.get("balance") instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (balance.signum() > 0) {
            notices.add(ErasureNotice.of(NOTICE_BALANCE, Map.of("balance", balance.toPlainString())));
        }
        long open = subject.rows(CASH).stream()
            .filter(row -> OPEN_CASH.contains(String.valueOf(row.get("status")))).count();
        if (open > 0) {
            notices.add(ErasureNotice.of(NOTICE_CASH, Map.of("count", open)));
        }
        return List.copyOf(notices);
    }

    @Bean
    ProcessDefinition<ErasedInput, ErasedOutput, ProcessContext> qbSponsorErased() {
        return ProcessDefinition.define(ERASED, 1, ErasedInput.class, ErasedOutput.class, ProcessContext.class,
            pb -> {
                pb.description("After a user's account was erased: ends their running publications and withdraws "
                        + "their notices.")
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
            });
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
