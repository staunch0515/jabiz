package com.jabiz.quizbuks.page;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.file.FileKind;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.identity.AccountLimits;

import java.util.List;
import java.util.Locale;

/**
 * A sponsor's page in the app (docs/quizbuks/plans/Q60-sponsor-page.md, Q60a): one per sponsor, at {@code /s/<handle>}
 * for anybody once published, in an ordinary table (its history is the platform's audit), written only by
 * {@link SponsorPageProcesses}. The company, the logo (the avatar) and the "About us" (the introduction) come from the
 * approved onboarding (Q65b: one page of the console, docs/quizbuks/plans/Q65-start-and-profile.md section 1); the
 * page's own are the handle, language, headline, cover and website. The table's columns {@code logo} and {@code story}
 * of Q60a stay, unused (V50).
 */
public final class QbSponsorPages {

    public static final String PAGE = "QbSponsorPage";
    /** The administrators' and operators' dataset (operators: their own sponsors' pages). */
    public static final String DEFAULT_DATASET = "urn:jabiz:dataset:default:" + PAGE;
    /** The sponsor's own page, read only. */
    public static final String MY_DATASET = "urn:jabiz:dataset:me:" + PAGE;
    /** The published pages, read anonymously through the public templates. */
    public static final String PUBLIC_DATASET = "urn:jabiz:dataset:public:" + PAGE;

    /** The page's cover (and the products' images): public once the page is. */
    public static final String IMAGE_POLICY = "qb.sponsor-page-image";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:sponsor-page-status";

    /** Not yet published, or taken back by the sponsor. */
    public static final String DRAFT = "DRAFT";
    /** Its first publication (or the one after a takedown) is in review. */
    public static final String PENDING = "PENDING";
    /** Shown to anybody; the sponsor's changes apply at once. */
    public static final String PUBLISHED = "PUBLISHED";
    /** The review sent it back; the sponsor changes it and submits again. */
    public static final String REJECTED = "REJECTED";
    /** An administrator or the sponsor's operator took it down (or the sponsor was banned); back only after a review. */
    public static final String TAKEN_DOWN = "TAKEN_DOWN";
    public static final List<String> STATUSES = List.of(DRAFT, PENDING, PUBLISHED, REJECTED, TAKEN_DOWN);

    /** The handle: lowercase letters, digits and inner hyphens. */
    public static final String HANDLE_PATTERN = "[a-z0-9][a-z0-9-]{1,28}[a-z0-9]";
    public static final int HANDLE_LENGTH = 30;
    public static final int HEADLINE_LENGTH = 120;
    public static final int WEBSITE_LENGTH = 300;
    public static final String WEBSITE_PATTERN = "https://[^ \\t\\r\\n]+";
    public static final int REASON_LENGTH = 500;

    public static final EntityDefinition ENTITY = EntityDefinition.define(PAGE, eb -> {
        eb.physicalTable("qb_sponsor_page");
        eb.primaryKey("userId");
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true)
            .asText(AccountLimits.USER_ID_LENGTH));
        eb.field("handle", f -> f.physicalColumn("handle").required(true).asText(HANDLE_LENGTH)
            .apply(Rules.pattern(SponsorPageCodes.HANDLE_FORMAT, HANDLE_PATTERN)));
        eb.field("language", f -> f.physicalColumn("language").required(true)
            .asCode(QbContent.LANGUAGE_DICTIONARY, QbContent.LANGUAGES.toArray(String[]::new)));
        eb.field("headline", f -> f.physicalColumn("headline").required(true).asText(HEADLINE_LENGTH)
            .apply(Rules.notBlank(SponsorPageCodes.HEADLINE_BLANK)));
        eb.field("cover", f -> f.physicalColumn("cover").kind(FileKind.of(IMAGE_POLICY)));
        // Q73c: the languages offered (the first its main one; none: `language` only) and the headline in each other
        // one, with the hash of the headline it translates ({language: {headline: {text, hash}}}).
        eb.field("languages", f -> f.physicalColumn("languages").asText(20));
        eb.field("translations", f -> f.physicalColumn("translations").asText(null, true));
        eb.field("website", f -> f.physicalColumn("website").asText(WEBSITE_LENGTH)
            .apply(Rules.pattern(SponsorPageCodes.WEBSITE_FORMAT, WEBSITE_PATTERN)));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(64));
        eb.field("submittedAt", f -> f.physicalColumn("submitted_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("publishedAt", f -> f.physicalColumn("published_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("rejectReason", f -> f.physicalColumn("reject_reason").processOnly().asText(REASON_LENGTH, true));
        // Part of the content a review approves: a page taken down goes back only through a new review.
        eb.field("takenDownAt", f -> f.physicalColumn("taken_down_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("takedownReason", f -> f.physicalColumn("takedown_reason").processOnly()
            .asText(REASON_LENGTH, true));
        // Set by a takedown, cleared only by a publication: until then every submission goes to a person's review,
        // whatever became of the page meanwhile (submitted, then changed or taken back to its draft).
        eb.field("reviewRequired", f -> f.physicalColumn("review_required").required(true).processOnly().asBool());
        eb.field("editedAt", f -> f.physicalColumn("edited_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).processOnly().asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_sponsor_page_handle", "handle");
        eb.display("handle");
        eb.listView("default", lv -> lv
            .columns("handle", "userId", "language", "headline", "status", "publishedAt", "takenDownAt", "editedAt")
            .filters("handle", "userId", "language", "status", "editedAt")
            .sorts("editedAt", "handle", "publishedAt")
            .defaultSort("editedAt", false));
    });

    /** A handle as stored: lowercase, so that two spellings of one name are the same handle. */
    public static String handle(String given) {
        return given == null ? null : given.strip().toLowerCase(Locale.ROOT);
    }

    /** The lock of a handle: two sponsors claiming the same one take turns (the second finds it taken). */
    static String handleLockName(String handle) {
        return "qb.sponsor-page.handle:" + handle;
    }

    private QbSponsorPages() {
    }
}
