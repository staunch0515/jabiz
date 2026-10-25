package com.jabiz.quizbuks.mailserver;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.mail.MailSenderSettings;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A sponsor's own mail server (docs/quizbuks/plans/Q29-merchant-mail-server.md; platform decision D56): the SMTP
 * settings their invitations leave through, one row per sponsor ({@code qb_sponsor_mail_server}, an ordinary table),
 * written by {@code QB_MAIL_SERVER_SET} / {@code QB_MAIL_SERVER_REMOVE} only. The password is stored sealed
 * ({@code SealSecret}); the platform's delivery opens it. The sponsor reads their own row through the template
 * {@code qb.sponsor.mail-server} (never the password), the administration every row.
 *
 * <p>The platform's {@code MailSenderSource} ({@link MailServerConfig}) hands out the settings of a <em>verified</em>
 * server only: saving one sends a test mail to the sponsor's own address, and the row records the outcome.
 */
public final class QbMailServers {

    public static final String ENTITY = "QbSponsorMailServer";
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + ENTITY;
    /** The sponsor's own row, read only. */
    public static final String SPONSOR_DATASET = "urn:jabiz:dataset:sponsor:" + ENTITY;

    public static final int HOST_LENGTH = 253;
    public static final int USERNAME_LENGTH = 200;
    public static final int PASSWORD_LENGTH = 200;
    public static final int ADDRESS_LENGTH = 320;
    public static final int ERROR_LENGTH = 2000;

    /** The lock a sponsor's server is changed under (saving, removing). */
    public static String lockName(Object sponsorId) {
        return "qb.mail-server:" + sponsorId;
    }

    /** The server of one sponsor (at most one row). */
    public static EntityQuery bySponsor(Object sponsorId) {
        List<Object> ids = new ArrayList<>();
        if (sponsorId != null) {
            ids.add(String.valueOf(sponsorId));
        }
        return EntityQuery.builder().where(new QueryPredicate.In("sponsorId", ids)).limit(1).build();
    }

    /** Whether the row is a server invitations may leave through: present and verified. */
    public static boolean usable(EntityInstance server) {
        return server != null && server.get("verifiedAt") != null;
    }

    /** The TLS mode a text names, or null when it names none. */
    public static MailSenderSettings.Tls tls(String value) {
        if (value == null) {
            return null;
        }
        try {
            return MailSenderSettings.Tls.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The settings of a row's attributes, as the platform's delivery asks for them (a verified row only). */
    public static Optional<MailSenderSettings> settingsOf(Map<String, Object> row) {
        if (row.get("verifiedAt") == null) {
            return Optional.empty();
        }
        return Optional.of(new MailSenderSettings((String) row.get("host"), ((Number) row.get("port")).intValue(),
            tls((String) row.get("tls")), (String) row.get("username"), (String) row.get("sealedPassword"),
            (String) row.get("fromAddress"), (String) row.get("fromName")));
    }

    /**
     * The field of the settings a problem of {@link MailSenderSettings#problems} is about: the problems start with the
     * component's name.
     */
    static String fieldOf(String problem) {
        for (String field : List.of("host", "port", "tls", "username", "password", "fromAddress", "fromName")) {
            if (problem.startsWith(field)) {
                return field;
            }
        }
        return null;
    }

    /** The server's or the connection's answer as the row keeps it: the message, truncated, never a stack. */
    static String reason(Throwable error) {
        String message = error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName()
            : error.getMessage();
        message = message.strip().replaceAll("\\s+", " ");
        return message.length() > ERROR_LENGTH ? message.substring(0, ERROR_LENGTH) : message;
    }

    public static final EntityDefinition ENTITY_DEFINITION = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable("qb_sponsor_mail_server");
        eb.primaryKey("sponsorId");
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("host", f -> f.physicalColumn("host").required(true).asText(HOST_LENGTH));
        eb.field("port", f -> f.physicalColumn("port").required(true).asNumeric(5, 0));
        eb.field("tls", f -> f.physicalColumn("tls").required(true).asText(10));
        eb.field("username", f -> f.physicalColumn("username").required(true).asText(USERNAME_LENGTH));
        // Sealed (platform SealedSecrets): no read interface returns it; only the delivery opens it.
        eb.field("sealedPassword", f -> f.physicalColumn("sealed_password").sensitive().asText(2000));
        eb.field("fromAddress", f -> f.physicalColumn("from_address").required(true).asText(ADDRESS_LENGTH));
        eb.field("fromName", f -> f.physicalColumn("from_name").asText(MailSenderSettings.MAX_NAME));
        eb.field("verifiedAt", f -> f.physicalColumn("verified_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("testedAt", f -> f.physicalColumn("tested_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("lastError", f -> f.physicalColumn("last_error").processOnly().asText(ERROR_LENGTH, true));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.display("host");
        eb.listView("default", lv -> lv
            .columns("sponsorId", "host", "port", "tls", "username", "fromAddress", "fromName", "verifiedAt",
                "testedAt", "lastError")
            .filters("sponsorId", "host", "verifiedAt")
            .sorts("host", "verifiedAt", "testedAt")
            .defaultSort("testedAt", false));
    });

    private QbMailServers() {}
}
