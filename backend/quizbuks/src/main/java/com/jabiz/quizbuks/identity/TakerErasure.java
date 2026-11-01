package com.jabiz.quizbuks.identity;

import com.jabiz.quizbuks.audience.QbAudience;
import com.jabiz.quizbuks.invitation.QbInvitations;
import com.jabiz.quizbuks.reward.CashRewardStatus;
import com.jabiz.quizbuks.reward.QbRewards;
import com.jabiz.quizbuks.reward.RedemptionStatus;
import com.jabiz.quizbuks.wallet.QbPayouts;
import com.jabiz.quizbuks.wallet.TransferStatus;
import com.jabiz.security.erasure.ErasedEntity;
import com.jabiz.security.erasure.ErasureLoad;
import com.jabiz.security.erasure.ErasureNotice;
import com.jabiz.security.erasure.ErasureSubject;
import com.jabiz.security.erasure.PersonalDataEraser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A taker's account deleted (Q71; docs/quizbuks/plans/Q71-taker-erasure.md; platform decision D62): what QuizBuks
 * erases of a taker, beyond what every account shares with a sponsor ({@link SponsorErasure}: profile, settings,
 * inbox), and what the taker is told before asking.
 *
 * <p>Their "about me", bank account, Stripe onboarding link, the invitations they claimed (their address) and their
 * place on a sponsor's staff go. Withdrawals stay as the records the accounts need (kept ten years) with the holder's
 * name and the account number emptied (migration V52); so do the rewards owed to them, with the notes people wrote
 * about them. Their attempts, views, vouchers, consents, certifications, bans, wallet, ledger and Stripe payout account
 * hold only the user's id and stay (decision D62 item 4): the sponsors' statistics and the accounts rest on them. A
 * balance left in the wallet stays there, unpaid (the requester's choice, 2026-11-01).
 */
@Configuration
public class TakerErasure {

    static final String TRANSFERS = "takerTransfers";
    static final String CASH = "takerCashRewards";
    static final String REDEMPTIONS = "takerRedemptions";

    /** The notices' codes (messages {@code erasure.notice.<code>}). */
    static final String NOTICE_WITHDRAWAL = "qb.withdrawal-in-progress";
    static final String NOTICE_REWARDS = "qb.rewards-unclaimed";

    /** A withdrawal not yet settled either way. */
    static final Set<String> OPEN_TRANSFERS = Set.of(TransferStatus.REVIEW, TransferStatus.SENDING,
        TransferStatus.CHECK);
    /** A cash reward a sponsor still owes the taker. */
    static final Set<String> OPEN_CASH = Set.of(CashRewardStatus.DUE, CashRewardStatus.MARKED_PAID,
        CashRewardStatus.DISPUTED, CashRewardStatus.OVERDUE);
    /** A voucher the taker has not used yet. */
    static final Set<String> OPEN_REDEMPTIONS = Set.of(RedemptionStatus.DUE, RedemptionStatus.CLAIMED);

    @Bean
    PersonalDataEraser qbTakerPersonalData() {
        return new PersonalDataEraser() {
            @Override
            public List<ErasedEntity> entities() {
                return List.of(
                    // What only the taker uses: gone, with its personal values masked in the audit records.
                    ErasedEntity.delete(QbAudience.ATTRIBUTE, "userId"),
                    ErasedEntity.delete(QbPayouts.BANK_ACCOUNT, "userId", "accountNumber", "accountHolder"),
                    ErasedEntity.delete(QbPayouts.ONBOARDING, "userId", "url"),
                    // Found by who claimed them, not by who sent them as the sponsor's are (decision D62 item 8).
                    ErasedEntity.delete(QbInvitations.INVITATION, "userId", "email"),
                    ErasedEntity.delete(QbRewards.STAFF, "userId"),
                    // Records the accounts need (ten years): who was paid stays a number, not a name.
                    ErasedEntity.keep(QbPayouts.TRANSFER, "userId", "accountNumber", "accountHolder"),
                    ErasedEntity.keep(QbRewards.CASH_REWARD, "userId", "markedPaidNote", "disputeNote",
                        "resolveNote"),
                    ErasedEntity.keep(QbRewards.REDEMPTION, "userId", "voidNote"));
            }

            @Override
            public List<ErasureLoad> loads() {
                return List.of(
                    ErasureLoad.of(TRANSFERS, QbPayouts.defaultDataset(QbPayouts.TRANSFER), "userId"),
                    ErasureLoad.of(CASH, QbRewards.defaultDataset(QbRewards.CASH_REWARD), "userId"),
                    ErasureLoad.of(REDEMPTIONS, QbRewards.defaultDataset(QbRewards.REDEMPTION), "userId"));
            }

            @Override
            public List<ErasureNotice> notices(ErasureSubject subject) {
                return TakerErasure.notices(subject);
            }
        };
    }

    /**
     * What a taker should know before asking (they inform, they do not refuse): a withdrawal under way should arrive
     * first (a bank transfer not yet paid cannot be paid once the purge empties its account details), and rewards not
     * yet received are given up. A balance left in the wallet is {@link SponsorErasure}'s
     * notice, for every account.
     */
    static List<ErasureNotice> notices(ErasureSubject subject) {
        List<ErasureNotice> notices = new ArrayList<>();
        long withdrawals = open(subject, TRANSFERS, OPEN_TRANSFERS);
        if (withdrawals > 0) {
            notices.add(ErasureNotice.of(NOTICE_WITHDRAWAL, Map.of("count", withdrawals)));
        }
        long rewards = open(subject, CASH, OPEN_CASH) + open(subject, REDEMPTIONS, OPEN_REDEMPTIONS);
        if (rewards > 0) {
            notices.add(ErasureNotice.of(NOTICE_REWARDS, Map.of("count", rewards)));
        }
        return List.copyOf(notices);
    }

    private static long open(ErasureSubject subject, String load, Set<String> statuses) {
        return subject.rows(load).stream().filter(row -> statuses.contains(String.valueOf(row.get("status")))).count();
    }
}
