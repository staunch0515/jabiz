package com.jabiz.quizbuks.invitation;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.publishing.QbPublications;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The invitation list of an invited publication (docs/quizbuks/plans/Q25-invitations.md): one row per address, in
 * lower case, written by the invitation processes only ({@code QB_INVITATION_ADD}, {@code _REMOVE}, {@code _SEND},
 * {@code _MAIL}, {@code _CLAIM}) and by the attempt that starts and finishes on it. The sponsor reads their own
 * ({@code sponsor:}), a taker the ones claimed by their account ({@code me:}, for the publication page), the
 * administration all. An ordinary table: a row not yet started may be removed. The daily quota of sends is a row
 * per sponsor and day (in {@code jabiz.fiscal-year-zone}).
 */
public final class QbInvitations {

    public static final String INVITATION = "QbInvitation";
    public static final String QUOTA = "QbInvitationQuota";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:invitation-status";

    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + INVITATION;
    public static final String SPONSOR_DATASET = "urn:jabiz:dataset:sponsor:" + INVITATION;
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + INVITATION;
    public static final String QUOTA_DATASET = "urn:jabiz:dataset:default:" + QUOTA;

    public static final int EMAIL_LENGTH = 320;
    /** How many addresses one call of {@code QB_INVITATION_ADD} takes. */
    public static final int MAX_PER_CALL = 500;
    /** The platform's rule of an address ({@code SecUser.email}), applied to each invited address. */
    public static final Pattern EMAIL = Pattern.compile("[^@ ]+@[^@ ]+[.][^@ ]+");

    /** The lock of a publication's list: additions, removals, queuing and the mail of each row take it. */
    public static String listLockName(Object publicationId) {
        return "qb.invitation-list:" + publicationId;
    }

    /** The lock of a sponsor's daily quota. */
    public static String quotaLockName(Object sponsorId) {
        return "qb.invitation-quota:" + sponsorId;
    }

    /** An address as the list keeps it: trimmed, in lower case; null when it is not an address. */
    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String address = email.strip().toLowerCase(Locale.ROOT);
        return address.length() <= EMAIL_LENGTH && EMAIL.matcher(address).matches() ? address : null;
    }

    public static final EntityDefinition INVITATION_ENTITY = EntityDefinition.define(INVITATION, eb -> {
        eb.physicalTable("qb_invitation");
        eb.primaryKey("invitationId");
        eb.field("invitationId", f -> f.physicalColumn("invitation_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:invitation"));
        eb.field("publicationId", f -> f.physicalColumn("publication_id").immutable(true).required(true)
            .asReference(QbPublications.PUBLICATION));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("email", f -> f.physicalColumn("email").immutable(true).required(true).asText(EMAIL_LENGTH));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, InvitationStatus.ALL.toArray(String[]::new)));
        eb.field("addedAt", f -> f.physicalColumn("added_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("sentCount", f -> f.physicalColumn("sent_count").required(true).processOnly().asNumeric(5, 0));
        eb.field("lastSentAt", f -> f.physicalColumn("last_sent_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // The account that claimed the invitation: the one signed in with the address.
        eb.field("userId", f -> f.physicalColumn("user_id").processOnly().asText(128));
        eb.field("attemptId", f -> f.physicalColumn("attempt_id").processOnly().asText(36));
        eb.field("startedAt", f -> f.physicalColumn("started_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("answeredAt", f -> f.physicalColumn("answered_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("score", f -> f.physicalColumn("score").processOnly().asNumeric(7, 0));
        eb.field("reward", f -> f.physicalColumn("reward").processOnly().asMonetary("JPY", 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_invitation", "publicationId", "email");
        eb.display("email");
        eb.listView("default", lv -> lv
            .columns("publicationId", "email", "status", "addedAt", "sentCount", "lastSentAt", "userId", "startedAt",
                "answeredAt", "score", "reward")
            .filters("publicationId", "sponsorId", "email", "status", "userId", "attemptId", "addedAt")
            .sorts("addedAt", "answeredAt", "email")
            .defaultSort("addedAt", false));
    });

    public static final EntityDefinition QUOTA_ENTITY = EntityDefinition.define(QUOTA, eb -> {
        eb.physicalTable("qb_invitation_quota");
        eb.primaryKey("quotaId");
        eb.field("quotaId", f -> f.physicalColumn("quota_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:invitation-quota"));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("day", f -> f.physicalColumn("day").immutable(true).required(true).asText(10));
        eb.field("sent", f -> f.physicalColumn("sent").required(true).processOnly().asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_invitation_quota", "sponsorId", "day");
        eb.listView("default", lv -> lv
            .columns("sponsorId", "day", "sent")
            .filters("sponsorId", "day")
            .sorts("day")
            .defaultSort("day", false));
    });

    private QbInvitations() {}
}
