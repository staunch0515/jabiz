package com.jabiz.quizbuks.shopify;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;

import java.util.List;

/**
 * The entities of QuizBuks for Shopify (docs/quizbuks/shopify/S1-install.md sections 3 to 5). All are ordinary tables
 * written by its processes only.
 *
 * <ul>
 *   <li>{@value #SHOP}: one row per shop that installed the app, by its address; kept when it is uninstalled, removed
 *       when Shopify asks to erase the shop's data ({@code shop/redact}).</li>
 *   <li>{@value #MEMBER}: a staff member of a shop who opened the app, with exactly one {@code ADMIN} per shop. A
 *       platform user belongs to one shop only (the identity is the person within the shop, platform decision D59).
 *       The shop's status is kept on the row too, for the sign-in guard, which reads one dataset by user.</li>
 *   <li>{@value #TOKEN}: a shop's sealed Shopify access and refresh tokens, one row per shop.</li>
 *   <li>{@value #COMPLIANCE}: what was done about each of Shopify's privacy requests, written once.</li>
 * </ul>
 */
public final class QbsShops {

    private QbsShops() {}

    public static final String SHOP = "QbsShop";
    public static final String MEMBER = "QbsShopMember";
    public static final String TOKEN = "QbsShopToken";
    public static final String COMPLIANCE = "QbsComplianceRequest";

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:shopify-shop-status";
    public static final String CONNECTION_DICTIONARY = "urn:jabiz:dict:quizbuks:shopify-connection";
    public static final String ROLE_DICTIONARY = "urn:jabiz:dict:quizbuks:shopify-member-role";
    public static final String TOPIC_DICTIONARY = "urn:jabiz:dict:quizbuks:shopify-compliance-topic";
    public static final String OUTCOME_DICTIONARY = "urn:jabiz:dict:quizbuks:shopify-compliance-outcome";

    public static final String ACTIVE = "ACTIVE";
    public static final String UNINSTALLED = "UNINSTALLED";
    public static final List<String> STATUSES = List.of(ACTIVE, UNINSTALLED);

    /** No access token yet; tokens held; the refresh token was refused, so a member must connect again. */
    public static final String NONE = "NONE";
    public static final String CONNECTED = "CONNECTED";
    public static final String RECONNECT = "RECONNECT";
    public static final List<String> CONNECTIONS = List.of(NONE, CONNECTED, RECONNECT);

    public static final String ADMIN = "ADMIN";
    public static final String MEMBER_ROLE = "MEMBER";
    public static final List<String> ROLES = List.of(ADMIN, MEMBER_ROLE);

    public static final String DATA_REQUEST = "customers/data_request";
    public static final String CUSTOMERS_REDACT = "customers/redact";
    public static final String SHOP_REDACT = "shop/redact";
    public static final String UNINSTALL = "app/uninstalled";
    public static final List<String> TOPICS = List.of(DATA_REQUEST, CUSTOMERS_REDACT, SHOP_REDACT);

    /**
     * No data of the customer is held (S1 keeps none); the shop's data was deleted; the shop was installed again
     * before the request came, so nothing was deleted; no data of the shop was held; the body named another shop than
     * the header, so nothing was done.
     */
    public static final String NO_DATA = "NO_DATA";
    public static final String DELETED = "DELETED";
    public static final String KEPT_REINSTALLED = "KEPT_REINSTALLED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String MISMATCH = "MISMATCH";
    public static final List<String> OUTCOMES = List.of(NO_DATA, DELETED, KEPT_REINSTALLED, NOT_FOUND, MISMATCH);

    public static final int ID_LENGTH = 36;
    public static final int USER_ID_LENGTH = 128;
    public static final int STAFF_ID_LENGTH = 255;
    public static final int NAME_LENGTH = 255;
    public static final int SEALED_LENGTH = 4000;
    public static final int SCOPE_LENGTH = 1000;

    public static final EntityDefinition SHOP_ENTITY = EntityDefinition.define(SHOP, eb -> {
        eb.physicalTable("qbs_shop");
        eb.primaryKey("shopId");
        eb.field("shopId", f -> f.physicalColumn("shop_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:shopify-shop"));
        eb.field("shopDomain", f -> f.physicalColumn("shop_domain").immutable(true).required(true)
            .asText(Shopify.SHOP_DOMAIN_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("installedAt", f -> f.physicalColumn("installed_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("uninstalledAt", f -> f.physicalColumn("uninstalled_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("shopName", f -> f.physicalColumn("shop_name").processOnly().asText(NAME_LENGTH));
        eb.field("connection", f -> f.physicalColumn("connection").required(true).processOnly()
            .asCode(CONNECTION_DICTIONARY, CONNECTIONS.toArray(String[]::new)));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qbs_shop_domain", "shopDomain");
        eb.display("shopDomain");
    });

    public static final EntityDefinition MEMBER_ENTITY = EntityDefinition.define(MEMBER, eb -> {
        eb.physicalTable("qbs_shop_member");
        eb.primaryKey("memberId");
        eb.field("memberId", f -> f.physicalColumn("member_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:shopify-member"));
        eb.field("shopId", f -> f.physicalColumn("shop_id").immutable(true).required(true).asText(ID_LENGTH));
        eb.field("shopDomain", f -> f.physicalColumn("shop_domain").immutable(true).required(true)
            .asText(Shopify.SHOP_DOMAIN_LENGTH));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).asText(USER_ID_LENGTH));
        eb.field("staffId", f -> f.physicalColumn("staff_id").immutable(true).required(true)
            .asText(STAFF_ID_LENGTH));
        eb.field("role", f -> f.physicalColumn("role").required(true).processOnly()
            .asCode(ROLE_DICTIONARY, ROLES.toArray(String[]::new)));
        eb.field("shopStatus", f -> f.physicalColumn("shop_status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("joinedAt", f -> f.physicalColumn("joined_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qbs_shop_member_user", "userId");
        eb.display("staffId");
    });

    public static final EntityDefinition TOKEN_ENTITY = EntityDefinition.define(TOKEN, eb -> {
        eb.physicalTable("qbs_shop_token");
        eb.primaryKey("shopId");
        eb.field("shopId", f -> f.physicalColumn("shop_id").immutable(true).required(true).asText(ID_LENGTH));
        eb.field("sealedAccessToken", f -> f.physicalColumn("sealed_access_token").required(true).sensitive()
            .asText(SEALED_LENGTH));
        eb.field("accessExpiresAt", f -> f.physicalColumn("access_expires_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("sealedRefreshToken", f -> f.physicalColumn("sealed_refresh_token").required(true).sensitive()
            .asText(SEALED_LENGTH));
        eb.field("refreshExpiresAt", f -> f.physicalColumn("refresh_expires_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("scope", f -> f.physicalColumn("scope").processOnly().asText(SCOPE_LENGTH));
        eb.field("obtainedAt", f -> f.physicalColumn("obtained_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
    });

    public static final EntityDefinition COMPLIANCE_ENTITY = EntityDefinition.define(COMPLIANCE, eb -> {
        eb.physicalTable("qbs_compliance_request");
        eb.primaryKey("requestId");
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:shopify-compliance-request"));
        eb.field("inboundEventId", f -> f.physicalColumn("inbound_event_id").immutable(true).required(true)
            .asText(ID_LENGTH));
        eb.field("topic", f -> f.physicalColumn("topic").immutable(true).required(true)
            .asCode(TOPIC_DICTIONARY, TOPICS.toArray(String[]::new)));
        eb.field("shopDomain", f -> f.physicalColumn("shop_domain").immutable(true)
            .asText(Shopify.SHOP_DOMAIN_LENGTH));
        eb.field("outcome", f -> f.physicalColumn("outcome").immutable(true).required(true)
            .asCode(OUTCOME_DICTIONARY, OUTCOMES.toArray(String[]::new)));
        eb.field("receivedAt", f -> f.physicalColumn("received_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("handledAt", f -> f.physicalColumn("handled_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qbs_compliance_event", "inboundEventId");
    });
}
