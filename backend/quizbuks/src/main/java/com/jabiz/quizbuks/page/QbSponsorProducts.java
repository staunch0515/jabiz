package com.jabiz.quizbuks.page;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FileKind;
import com.jabiz.quizbuks.identity.AccountLimits;
import com.jabiz.quizbuks.identity.QbAccounts;

import java.util.List;

/**
 * A sponsor's products (docs/quizbuks/plans/Q60-sponsor-page.md, Q60b): shown on their published page and with their
 * quizzes, each linking to the sponsor's own shop. Not reviewed on their own (the page's first publication was);
 * headquarters or the sponsor's operator takes one down, and only they put it back. The price is the shop's, shown as
 * it is: no money moves on the platform for it, so it is a decimal with its currency, not a monetary amount.
 * Ordinary tables written by {@link SponsorProductProcesses} only.
 */
public final class QbSponsorProducts {

    public static final String PRODUCT = "QbSponsorProduct";
    /** A product shown with a quiz (question number 0) or with one of its questions. */
    public static final String QUIZ_LINK = "QbQuizProduct";

    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + PRODUCT;
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + PRODUCT;
    public static final String PUBLIC_DATASET = "urn:jabiz:dataset:public:" + PRODUCT;
    public static final String LINK_DATASET = "urn:jabiz:dataset:default:" + QUIZ_LINK;
    public static final String MY_LINK_DATASET = "urn:jabiz:dataset:me:" + QUIZ_LINK;
    public static final String PUBLIC_LINK_DATASET = "urn:jabiz:dataset:public:" + QUIZ_LINK;

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:sponsor-product-status";
    /** Shown (on a published page). */
    public static final String LISTED = "LISTED";
    /** Hidden by the sponsor. */
    public static final String UNLISTED = "UNLISTED";
    /** Hidden by headquarters or the operator; back only when they put it back. */
    public static final String TAKEN_DOWN = "TAKEN_DOWN";
    public static final List<String> STATUSES = List.of(LISTED, UNLISTED, TAKEN_DOWN);

    public static final int MAX_PRODUCTS = 50;
    /** Products shown with a whole quiz; a question shows one. */
    public static final int MAX_QUIZ_PRODUCTS = 3;
    public static final int NAME_LENGTH = 100;
    public static final int DESCRIPTION_LENGTH = 500;
    public static final int SHOP_URL_LENGTH = 300;
    /** The question number of a product shown with the whole quiz. */
    public static final int WHOLE_QUIZ = 0;

    /** The decimals a price has in a currency: none for the yen, two for the others. */
    public static int scaleOf(String currency) {
        return "JPY".equals(currency) ? 0 : 2;
    }

    public static final EntityDefinition PRODUCT_ENTITY = EntityDefinition.define(PRODUCT, eb -> {
        eb.physicalTable("qb_sponsor_product");
        eb.primaryKey("productId");
        eb.field("productId", f -> f.physicalColumn("product_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:sponsor-product"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("name", f -> f.physicalColumn("name").required(true).asText(NAME_LENGTH)
            .apply(Rules.notBlank(SponsorPageCodes.PRODUCT_NAME_BLANK)));
        eb.field("description", f -> f.physicalColumn("description").asText(DESCRIPTION_LENGTH, true));
        eb.field("image", f -> f.physicalColumn("image").kind(FileKind.of(QbSponsorPages.IMAGE_POLICY)));
        eb.field("price", f -> f.physicalColumn("price").required(true).asNumeric(12, 2));
        eb.field("currency", f -> f.physicalColumn("currency").required(true)
            .asCode(QbAccounts.CURRENCY_DICTIONARY, QbAccounts.CURRENCIES.toArray(String[]::new)));
        eb.field("shopUrl", f -> f.physicalColumn("shop_url").required(true).asText(SHOP_URL_LENGTH)
            .apply(Rules.pattern(SponsorPageCodes.WEBSITE_FORMAT, QbSponsorPages.WEBSITE_PATTERN)));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("seq", f -> f.physicalColumn("seq").required(true).asNumeric(4, 0));
        eb.field("takenDownAt", f -> f.physicalColumn("taken_down_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("takedownReason", f -> f.physicalColumn("takedown_reason").processOnly()
            .asText(QbSponsorPages.REASON_LENGTH, true));
        // The operator whose member took it down (null: headquarters): an operator puts back only its own takedowns.
        eb.field("takedownOperatorId", f -> f.physicalColumn("takedown_operator_id").processOnly().asText(36));
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.display("name");
        eb.listView("default", lv -> lv
            .columns("name", "userId", "price", "currency", "status", "seq", "editedAt")
            .filters("name", "userId", "status", "currency")
            .sorts("seq", "name", "editedAt")
            .defaultSort("seq", true));
    });

    public static final EntityDefinition LINK_ENTITY = EntityDefinition.define(QUIZ_LINK, eb -> {
        eb.physicalTable("qb_quiz_product");
        eb.primaryKey("linkId");
        eb.field("linkId", f -> f.physicalColumn("link_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:quiz-product"));
        eb.field("quizId", f -> f.physicalColumn("quiz_id").immutable(true).required(true).asText(36));
        // 0: the whole quiz; otherwise the question of that number in whichever version is played.
        eb.field("questionNo", f -> f.physicalColumn("question_no").immutable(true).required(true).asNumeric(3, 0));
        eb.field("productId", f -> f.physicalColumn("product_id").immutable(true).required(true).asText(36));
        eb.field("ownerId", f -> f.physicalColumn("owner_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("seq", f -> f.physicalColumn("seq").required(true).asNumeric(2, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv.columns("quizId", "questionNo", "productId", "seq")
            .filters("quizId", "productId").sorts("questionNo", "seq").defaultSort("questionNo", true));
    });

    /** The lock of a sponsor's products and their links: the account's (every writer of them holds it). */
    static String lockName(Object userId) {
        return QbAccounts.lockName(userId);
    }

    private QbSponsorProducts() {
    }
}
