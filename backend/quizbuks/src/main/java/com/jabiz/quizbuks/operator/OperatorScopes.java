package com.jabiz.quizbuks.operator;

import com.jabiz.runtime.publicread.PublicCounterEntities;
import com.jabiz.dataset.DatasetScope;
import com.jabiz.dataset.EntryScopeVia;
import com.jabiz.quizbuks.audience.QbAudience;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.invitation.QbInvitations;
import com.jabiz.quizbuks.page.QbSponsorPages;
import com.jabiz.quizbuks.page.QbSponsorProducts;
import com.jabiz.quizbuks.play.QbPlay;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.reward.QbDigitalPrizes;
import com.jabiz.quizbuks.reward.QbRewards;
import com.jabiz.quizbuks.stats.QbAnalytics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What of the data an operator's session sees (Q59c-2, platform decision D64): the rows of the sponsors the operator
 * looks after now ({@code QbSponsorOperator}), through the field of each entity that holds the sponsor; the rows only a
 * publication's id ties to a sponsor, through the operator its card froze when it went on. Headquarters and the other
 * entries are not limited. Every default dataset the operators' permissions read declares its entry here
 * ({@code OperatorScopesTest} checks it).
 */
public final class OperatorScopes {

    /** By entity, the field holding the sponsor's user id, or {@link #BY_CARD} for a publication's id. */
    static final Map<String, String> SPONSOR_FIELDS;

    /** The field of the entities that only a publication's id ties to a sponsor. */
    static final String BY_CARD = "publicationId";

    static {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String entity : new String[] {QbContent.QUIZ, QbContent.MATERIAL, QbContent.MATERIAL_IMAGE,
            QbContent.QUESTION, QbContent.OPTION, QbContent.VERSION, QbContent.VERSION_FILE}) {
            fields.put(entity, "ownerId");
        }
        for (String entity : new String[] {QbPublications.PUBLICATION, QbPublications.TIER, QbPublications.CARD,
            QbRewards.CASH_REWARD, QbRewards.REDEMPTION, QbDigitalPrizes.DIGITAL_PRIZE, QbDigitalPrizes.DIGITAL_CODE,
            QbInvitations.INVITATION, QbAudience.CRITERION, QbAnalytics.HOUR, QbAnalytics.CURSOR,
            QbAnalytics.QUESTION_STAT}) {
            fields.put(entity, "publisherId");
        }
        fields.put(QbRewards.REDEEM_TOKEN, "sponsorId");
        fields.put(QbRewards.STAFF, "sponsorId");
        fields.put(QbInvitations.QUOTA, "sponsorId");
        fields.put(QbPublications.SPONSOR_METHODS, "userId");
        fields.put(QbAccounts.SPONSOR, "userId");
        fields.put(QbSponsorPages.PAGE, "userId");
        fields.put(QbSponsorProducts.PRODUCT, "userId");
        fields.put(QbSponsorProducts.QUIZ_LINK, "ownerId");
        // Q60c-2: the public counters' counts of sponsors' pages and products, by their owner (the sponsor).
        fields.put(PublicCounterEntities.ENTITY, "ownerKey");
        for (String entity : new String[] {QbPublications.COUNTER, QbPublications.START_COUNTER,
            QbPublications.SCHEDULE, QbPublications.PAST_VERSION, QbPlay.VIEW, QbPlay.STATS}) {
            fields.put(entity, BY_CARD);
        }
        SPONSOR_FIELDS = Map.copyOf(fields);
    }

    private OperatorScopes() {}

    /** The scope of {@code entity}'s default dataset; fails for an entity this class does not know. */
    public static Consumer<DatasetScope.Builder> of(String entity) {
        String field = SPONSOR_FIELDS.get(entity);
        if (field == null) {
            throw new IllegalArgumentException("No operator scope is declared for " + entity);
        }
        return BY_CARD.equals(field)
            ? s -> s.withinEntryScope(QbOperators.SCOPE, field,
                EntryScopeVia.of(QbPublications.CARD, "publicationId", "operatorId"))
            : s -> s.withinEntryScope(QbOperators.SCOPE, field,
                EntryScopeVia.of(QbOperators.ASSIGNMENT, "userId", "operatorId"));
    }
}
