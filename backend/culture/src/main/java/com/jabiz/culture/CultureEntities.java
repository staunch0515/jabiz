package com.jabiz.culture;

import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldBuilder;
import com.jabiz.entity.Rules;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.file.FileKind;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.jabiz.culture.Culture.*;

/**
 * The content model (docs/culture/00-design.md section 3). All entities are ordinary, not temporal: they hold personal
 * data of minors that must be deletable for real. Workflow fields ({@code status}, {@code visibility},
 * {@code editable}, ...) are process-only; their lifecycles return to their first state, which is therefore declared.
 */
@Configuration
public class CultureEntities extends BaseEntityDefinitions {

    public static final String STORY_STATUS = "urn:jabiz:dict:culture:story-status";
    public static final String PARTICIPANT_STATUS = "urn:jabiz:dict:culture:participant-status";
    public static final String RESOURCE_STATUS = "urn:jabiz:dict:culture:resource-status";
    public static final String VISIBILITY = "urn:jabiz:dict:culture:visibility";
    public static final String VIDEO_PROVIDER = "urn:jabiz:dict:culture:video-provider";
    public static final String MEDIA_KIND = "urn:jabiz:dict:culture:media-kind";
    public static final String CONSENT_PARTY = "urn:jabiz:dict:culture:consent-party";
    /** Database dictionaries: editors add values without a release (brief section 14). */
    public static final String MEDIA_TYPE = "urn:jabiz:dict:culture:media-type";
    public static final String ACTIVITY_TYPE = "urn:jabiz:dict:culture:activity-type";
    public static final String AGE_GROUP = "urn:jabiz:dict:culture:age-group";

    public static final String IMAGE_POLICY = "culture.image";
    public static final String AUDIO_POLICY = "culture.audio";
    public static final String PDF_POLICY = "culture.pdf";
    public static final String CONSENT_DOC_POLICY = "culture.consent-doc";

    static final String SLUG_PATTERN = "[a-z0-9-]+";
    static final String VIDEO_ID_PATTERN = "[A-Za-z0-9_-]{6,32}";

    private static Consumer<FieldBuilder> id(String column, String urn) {
        return f -> f.physicalColumn(column).immutable(true).required(true).generated(true).asSemanticIdentity(urn);
    }

    private static Consumer<FieldBuilder> slug(String code, int length) {
        return f -> f.physicalColumn("slug").required(true).asText(length).apply(Rules.pattern(code, SLUG_PATTERN));
    }

    private static Consumer<FieldBuilder> sortOrder() {
        return f -> f.physicalColumn("sort_order").asNumeric(5, 0);
    }

    private static Consumer<FieldBuilder> visibility() {
        return f -> f.physicalColumn("visibility").required(true).processOnly().asCode(VISIBILITY, PRIVATE, PUBLIC);
    }

    private static Consumer<FieldBuilder> editable() {
        return f -> f.physicalColumn("editable").processOnly().asBool();
    }

    private static Consumer<FieldBuilder> ownerActorId() {
        return f -> f.physicalColumn("owner_actor_id").asText(64);
    }

    private static Consumer<FieldBuilder> videoId(String code) {
        return f -> f.physicalColumn("video_id").asText(32).apply(Rules.pattern(code, VIDEO_ID_PATTERN));
    }

    /** Published and taken offline by the story's processes; private again when a story is unpublished. */
    private static void visibilityLifecycle(com.jabiz.entity.EntityBuilder eb) {
        eb.stateTransitions("visibility", st -> st.initial(PRIVATE).from(PRIVATE).to(PUBLIC).from(PUBLIC).to(PRIVATE));
    }

    /** A video is a provider and an id, or neither. */
    private static List<Violation> videoComplete(Map<String, Object> state) {
        boolean provider = state.get("videoProvider") != null;
        boolean video = state.get("videoId") != null;
        return provider == video ? List.of()
            : List.of(new Violation(provider ? "videoId" : "videoProvider", "VIDEO_INCOMPLETE",
                "A video needs both its provider and its id"));
    }

    static boolean hasEnglish(Object text) {
        return text instanceof Map<?, ?> map && map.get("en") instanceof String en && !en.isBlank();
    }

    public static final EntityDefinition LOCATION_ENTITY = EntityDefinition.define(LOCATION, eb -> {
        eb.physicalTable("cu_location");
        eb.primaryKey("locationId");
        eb.field("locationId", id("location_id", "urn:jabiz:entity:culture:location"));
        eb.field("slug", slug("LOCATION_SLUG_FORMAT", 40));
        eb.field("name", f -> f.physicalColumn("name").required(true).apply(I18nText.of(80).required("en")));
        eb.field("countryCode", f -> f.physicalColumn("country_code").asText(2)
            .apply(Rules.pattern("COUNTRY_CODE_FORMAT", "[A-Z]{2}")));
        eb.field("placeLabel", f -> f.physicalColumn("place_label").apply(I18nText.of(80)));
        eb.field("latitude", f -> f.physicalColumn("latitude").asNumeric(8, 5)
            .apply(Rules.range("LATITUDE_RANGE", new BigDecimal("-90"), new BigDecimal("90"))));
        eb.field("longitude", f -> f.physicalColumn("longitude").asNumeric(9, 5)
            .apply(Rules.range("LONGITUDE_RANGE", new BigDecimal("-180"), new BigDecimal("180"))));
        eb.field("sortOrder", sortOrder());
        eb.field("visible", f -> f.physicalColumn("visible").required(true).asBool());
        eb.field("rowVersion", rowVersion("version"));
        eb.unique("uk_cu_location_slug", "slug");
        eb.display("name");
        eb.listView("default", lv -> lv
            .columns("name", "slug", "countryCode", "placeLabel", "sortOrder", "visible")
            .filters("slug", "countryCode", "visible")
            .sorts("sortOrder", "slug")
            .defaultSort("sortOrder", true));
    });

    public static final EntityDefinition THEME_ENTITY = EntityDefinition.define(THEME, eb -> {
        eb.physicalTable("cu_theme");
        eb.primaryKey("themeId");
        eb.field("themeId", id("theme_id", "urn:jabiz:entity:culture:theme"));
        eb.field("slug", slug("THEME_SLUG_FORMAT", 40));
        eb.field("icon", f -> f.physicalColumn("icon").asText(8));
        eb.field("title", f -> f.physicalColumn("title").required(true).apply(I18nText.of(60).required("en")));
        eb.field("question", f -> f.physicalColumn("question").required(true).apply(I18nText.of(200).required("en")));
        eb.field("intro", f -> f.physicalColumn("intro").apply(I18nText.markdown(4000)));
        eb.field("sortOrder", sortOrder());
        eb.field("visible", f -> f.physicalColumn("visible").required(true).asBool());
        eb.field("rowVersion", rowVersion("version"));
        eb.unique("uk_cu_theme_slug", "slug");
        eb.display("title");
        eb.listView("default", lv -> lv
            .columns("icon", "title", "slug", "question", "sortOrder", "visible")
            .filters("slug", "visible")
            .sorts("sortOrder", "slug")
            .defaultSort("sortOrder", true));
    });

    public static final EntityDefinition PARTICIPANT_ENTITY = EntityDefinition.define(PARTICIPANT, eb -> {
        eb.physicalTable("cu_participant");
        eb.primaryKey("participantId");
        eb.field("participantId", id("participant_id", "urn:jabiz:entity:culture:participant"));
        eb.field("slug", slug("PARTICIPANT_SLUG_FORMAT", 40));
        // A first name or a chosen name, never a surname (editor guide).
        eb.field("displayName", f -> f.physicalColumn("display_name").required(true).asText(40)
            .apply(Rules.notBlank("DISPLAY_NAME_BLANK")));
        eb.field("locationId", f -> f.physicalColumn("location_id").required(true).asReference(LOCATION));
        eb.field("portraitFileId", f -> f.physicalColumn("portrait_file_id").kind(FileKind.of(IMAGE_POLICY)));
        eb.field("portraitAlt", f -> f.physicalColumn("portrait_alt").apply(I18nText.of(200)));
        eb.field("shortBio", f -> f.physicalColumn("short_bio").apply(I18nText.of(300)));
        eb.field("bio", f -> f.physicalColumn("bio").apply(I18nText.markdown(3000)));
        eb.field("perspective", f -> f.physicalColumn("perspective").apply(I18nText.of(600).multiline()));
        eb.field("reflection", f -> f.physicalColumn("reflection").apply(I18nText.markdown(5000)));
        eb.field("interests", f -> f.physicalColumn("interests").apply(I18nText.of(200)));
        // Left empty unless the participant wishes to disclose them (brief section 6).
        eb.field("languages", f -> f.physicalColumn("languages").asText(200));
        eb.field("sortOrder", sortOrder());
        eb.field("accountActorId", f -> f.physicalColumn("account_actor_id").asText(64));
        eb.field("adult", f -> f.physicalColumn("adult").required(true).asBool());
        eb.field("curatorNote", f -> f.physicalColumn("curator_note").asText(2000, true));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(PARTICIPANT_STATUS, DRAFT, ACTIVE, HIDDEN, WITHDRAWN));
        eb.field("rowVersion", rowVersion("version"));
        eb.stateTransitions("status", st -> st.initial(DRAFT)
            .from(DRAFT).to(ACTIVE, WITHDRAWN)
            .from(ACTIVE).to(HIDDEN, WITHDRAWN)
            .from(HIDDEN).to(ACTIVE, WITHDRAWN)
            .from(WITHDRAWN).to(DRAFT));
        eb.check("PORTRAIT_ALT_REQUIRED", (state, ctx) ->
            state.get("portraitFileId") != null && !hasEnglish(state.get("portraitAlt"))
                ? List.of(new Violation("portraitAlt", "PORTRAIT_ALT_REQUIRED",
                    "A portrait needs an English text alternative"))
                : List.of());
        eb.unique("uk_cu_participant_slug", "slug");
        eb.unique("uk_cu_participant_account", "accountActorId");
        eb.display("displayName");
        eb.listView("default", lv -> lv
            .columns("displayName", "slug", "locationId", "status", "adult", "accountActorId", "sortOrder")
            .filters("slug", "displayName", "locationId", "status", "adult", "accountActorId")
            .sorts("sortOrder", "displayName", "status")
            .defaultSort("sortOrder", true));
    });

    public static final EntityDefinition STORY_ENTITY = EntityDefinition.define(STORY, eb -> {
        eb.physicalTable("cu_story");
        eb.primaryKey("storyId");
        eb.field("storyId", id("story_id", "urn:jabiz:entity:culture:story"));
        eb.field("slug", slug("STORY_SLUG_FORMAT", 80));
        eb.field("title", f -> f.physicalColumn("title").required(true).apply(I18nText.of(120)));
        eb.field("summary", f -> f.physicalColumn("summary").apply(I18nText.of(300).multiline()));
        eb.field("about", f -> f.physicalColumn("about").apply(I18nText.markdown(3000)));
        eb.field("body", f -> f.physicalColumn("body").apply(I18nText.markdown(40_000)));
        eb.field("mediaType", f -> f.physicalColumn("media_type").required(true).asCode(MEDIA_TYPE));
        eb.field("storyDate", f -> f.physicalColumn("story_date").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("thumbnailFileId", f -> f.physicalColumn("thumbnail_file_id").kind(FileKind.of(IMAGE_POLICY)));
        eb.field("thumbnailAlt", f -> f.physicalColumn("thumbnail_alt").apply(I18nText.of(200)));
        eb.field("videoProvider", f -> f.physicalColumn("video_provider").asCode(VIDEO_PROVIDER, "YOUTUBE", "VIMEO"));
        eb.field("videoId", videoId("VIDEO_ID_FORMAT"));
        eb.field("captionsConfirmed", f -> f.physicalColumn("captions_confirmed").asBool());
        eb.field("transcript", f -> f.physicalColumn("transcript").apply(I18nText.markdown(40_000)));
        eb.field("reflectionSurprised", f -> f.physicalColumn("reflection_surprised").apply(I18nText.markdown(3000)));
        eb.field("reflectionAssumed", f -> f.physicalColumn("reflection_assumed").apply(I18nText.markdown(3000)));
        eb.field("reflectionLearned", f -> f.physicalColumn("reflection_learned").apply(I18nText.markdown(3000)));
        eb.field("featured", f -> f.physicalColumn("featured").asBool());
        eb.field("ownerActorId", ownerActorId());
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STORY_STATUS, DRAFT, IN_REVIEW, PUBLISHED, UNPUBLISHED));
        eb.field("publishedTime", f -> f.physicalColumn("published_time").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("reviewNote", f -> f.physicalColumn("review_note").processOnly().asText(2000, true));
        eb.field("rowVersion", rowVersion("version"));
        eb.stateTransitions("status", st -> st.initial(DRAFT)
            .from(DRAFT).to(IN_REVIEW, PUBLISHED)
            .from(IN_REVIEW).to(DRAFT, PUBLISHED)
            .from(PUBLISHED).to(UNPUBLISHED)
            .from(UNPUBLISHED).to(PUBLISHED, DRAFT));
        eb.check("VIDEO_INCOMPLETE", (state, ctx) -> videoComplete(state));
        eb.unique("uk_cu_story_slug", "slug");
        eb.display("title");
        eb.listView("default", lv -> lv
            .columns("title", "slug", "mediaType", "status", "storyDate", "featured", "ownerActorId", "publishedTime")
            .filters("slug", "mediaType", "status", "featured", "ownerActorId")
            .sorts("storyDate", "publishedTime", "slug", "status")
            .defaultSort("storyDate", false));
    });

    public static final EntityDefinition STORY_THEME_ENTITY = EntityDefinition.define(STORY_THEME, eb -> {
        eb.physicalTable("cu_story_theme");
        eb.primaryKey("storyThemeId");
        eb.field("storyThemeId", id("story_theme_id", "urn:jabiz:entity:culture:story-theme"));
        eb.field("storyId", f -> f.physicalColumn("story_id").immutable(true).required(true).asReference(STORY));
        eb.field("themeId", f -> f.physicalColumn("theme_id").required(true).asReference(THEME));
        eb.field("visibility", visibility());
        eb.field("rowVersion", rowVersion("version"));
        visibilityLifecycle(eb);
        eb.unique("uk_cu_story_theme", "storyId", "themeId");
        eb.listView("default", lv -> lv
            .columns("storyId", "themeId", "visibility")
            .filters("storyId", "themeId", "visibility"));
    });

    public static final EntityDefinition CONTRIBUTION_ENTITY = EntityDefinition.define(CONTRIBUTION, eb -> {
        eb.physicalTable("cu_contribution");
        eb.primaryKey("contributionId");
        eb.field("contributionId", id("contribution_id", "urn:jabiz:entity:culture:contribution"));
        eb.field("storyId", f -> f.physicalColumn("story_id").immutable(true).required(true).asReference(STORY));
        eb.field("participantId", f -> f.physicalColumn("participant_id").immutable(true).required(true)
            .asReference(PARTICIPANT));
        eb.field("heading", f -> f.physicalColumn("heading").apply(I18nText.of(120)));
        eb.field("text", f -> f.physicalColumn("text").apply(I18nText.markdown(10_000)));
        eb.field("videoProvider", f -> f.physicalColumn("video_provider").asCode(VIDEO_PROVIDER, "YOUTUBE", "VIMEO"));
        eb.field("videoId", videoId("VIDEO_ID_FORMAT"));
        eb.field("captionsConfirmed", f -> f.physicalColumn("captions_confirmed").asBool());
        eb.field("transcript", f -> f.physicalColumn("transcript").apply(I18nText.markdown(40_000)));
        eb.field("audioFileId", f -> f.physicalColumn("audio_file_id").kind(FileKind.of(AUDIO_POLICY)));
        eb.field("sortOrder", sortOrder());
        eb.field("ownerActorId", ownerActorId());
        eb.field("editable", editable());
        eb.field("visibility", visibility());
        eb.field("rowVersion", rowVersion("version"));
        visibilityLifecycle(eb);
        eb.check("VIDEO_INCOMPLETE", (state, ctx) -> videoComplete(state));
        eb.unique("uk_cu_contribution", "storyId", "participantId");
        eb.display("heading");
        eb.listView("default", lv -> lv
            .columns("storyId", "participantId", "heading", "sortOrder", "visibility", "ownerActorId")
            .filters("storyId", "participantId", "visibility", "ownerActorId", "editable")
            .sorts("sortOrder")
            .defaultSort("sortOrder", true));
    });

    public static final EntityDefinition MEDIA_ITEM_ENTITY = EntityDefinition.define(MEDIA_ITEM, eb -> {
        eb.physicalTable("cu_media_item");
        eb.primaryKey("mediaItemId");
        eb.field("mediaItemId", id("media_item_id", "urn:jabiz:entity:culture:media-item"));
        eb.field("storyId", f -> f.physicalColumn("story_id").immutable(true).required(true).asReference(STORY));
        eb.field("contributionId", f -> f.physicalColumn("contribution_id").immutable(true)
            .asReference(CONTRIBUTION));
        eb.field("kind", f -> f.physicalColumn("kind").required(true).immutable(true)
            .asCode(MEDIA_KIND, PHOTO, AUDIO));
        eb.field("imageFileId", f -> f.physicalColumn("image_file_id").kind(FileKind.of(IMAGE_POLICY)));
        eb.field("audioFileId", f -> f.physicalColumn("audio_file_id").kind(FileKind.of(AUDIO_POLICY)));
        eb.field("alt", f -> f.physicalColumn("alt").apply(I18nText.of(300)));
        eb.field("caption", f -> f.physicalColumn("caption").apply(I18nText.of(500).multiline()));
        eb.field("credit", f -> f.physicalColumn("credit").asText(80));
        eb.field("showsIdentifiablePeople", f -> f.physicalColumn("shows_identifiable_people").asBool());
        eb.field("peopleConsentConfirmed", f -> f.physicalColumn("people_consent_confirmed").asBool());
        eb.field("sortOrder", sortOrder());
        eb.field("ownerActorId", ownerActorId());
        eb.field("editable", editable());
        eb.field("visibility", visibility());
        eb.field("rowVersion", rowVersion("version"));
        visibilityLifecycle(eb);
        // One file field per policy (a field has one policy), so the kind says which one is used.
        eb.check("MEDIA_FILE_KIND", (state, ctx) -> {
            boolean photo = PHOTO.equals(state.get("kind"));
            Object wanted = state.get(photo ? "imageFileId" : "audioFileId");
            Object other = state.get(photo ? "audioFileId" : "imageFileId");
            return wanted != null && other == null ? List.of()
                : List.of(new Violation(photo ? "imageFileId" : "audioFileId", "MEDIA_FILE_KIND",
                    "A photo has an image file only, an audio item an audio file only", Map.of("kind", photo ? PHOTO : AUDIO)));
        });
        eb.listView("default", lv -> lv
            .columns("storyId", "contributionId", "kind", "imageFileId", "alt", "sortOrder", "visibility")
            .filters("storyId", "contributionId", "kind", "visibility", "ownerActorId", "editable")
            .sorts("sortOrder")
            .defaultSort("sortOrder", true));
    });

    public static final EntityDefinition RESOURCE_ENTITY = EntityDefinition.define(RESOURCE, eb -> {
        eb.physicalTable("cu_resource");
        eb.primaryKey("resourceId");
        eb.field("resourceId", id("resource_id", "urn:jabiz:entity:culture:resource"));
        eb.field("slug", slug("RESOURCE_SLUG_FORMAT", 80));
        eb.field("title", f -> f.physicalColumn("title").required(true).apply(I18nText.of(120).required("en")));
        eb.field("description", f -> f.physicalColumn("description").apply(I18nText.of(1000).multiline()));
        eb.field("activityType", f -> f.physicalColumn("activity_type").required(true).asCode(ACTIVITY_TYPE));
        eb.field("ageGroup", f -> f.physicalColumn("age_group").asCode(AGE_GROUP));
        eb.field("durationMinutes", f -> f.physicalColumn("duration_minutes").asNumeric(4, 0)
            .apply(Rules.range("DURATION_RANGE", new BigDecimal("5"), new BigDecimal("600"))));
        eb.field("pdfFileId", f -> f.physicalColumn("pdf_file_id").kind(FileKind.of(PDF_POLICY)));
        eb.field("body", f -> f.physicalColumn("body").apply(I18nText.markdown(20_000)));
        eb.field("sortOrder", sortOrder());
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(RESOURCE_STATUS, DRAFT, PUBLISHED));
        eb.field("rowVersion", rowVersion("version"));
        eb.stateTransitions("status", st -> st.initial(DRAFT).from(DRAFT).to(PUBLISHED).from(PUBLISHED).to(DRAFT));
        eb.unique("uk_cu_resource_slug", "slug");
        eb.display("title");
        eb.listView("default", lv -> lv
            .columns("title", "slug", "activityType", "ageGroup", "durationMinutes", "status", "sortOrder")
            .filters("slug", "activityType", "ageGroup", "status")
            .sorts("sortOrder", "slug")
            .defaultSort("sortOrder", true));
    });

    public static final EntityDefinition RESOURCE_STORY_ENTITY = EntityDefinition.define(RESOURCE_STORY, eb -> {
        eb.physicalTable("cu_resource_story");
        eb.primaryKey("resourceStoryId");
        eb.field("resourceStoryId", id("resource_story_id", "urn:jabiz:entity:culture:resource-story"));
        eb.field("resourceId", f -> f.physicalColumn("resource_id").immutable(true).required(true)
            .asReference(RESOURCE));
        eb.field("storyId", f -> f.physicalColumn("story_id").required(true).asReference(STORY));
        eb.field("rowVersion", rowVersion("version"));
        eb.unique("uk_cu_resource_story", "resourceId", "storyId");
        eb.listView("default", lv -> lv.columns("resourceId", "storyId").filters("resourceId", "storyId"));
    });

    public static final EntityDefinition SITE_BLOCK_ENTITY = EntityDefinition.define(SITE_BLOCK, eb -> {
        eb.physicalTable("cu_site_block");
        eb.primaryKey("siteBlockId");
        eb.field("siteBlockId", id("site_block_id", "urn:jabiz:entity:culture:site-block"));
        eb.field("blockKey", f -> f.physicalColumn("block_key").required(true).immutable(true).asText(80)
            .apply(Rules.pattern("BLOCK_KEY_FORMAT", "[a-z0-9.-]+")));
        eb.field("body", f -> f.physicalColumn("body").required(true).apply(I18nText.markdown(10_000).required("en")));
        eb.field("note", f -> f.physicalColumn("note").asText(500, true));
        eb.field("rowVersion", rowVersion("version"));
        eb.unique("uk_cu_site_block_key", "blockKey");
        eb.display("blockKey");
        eb.listView("default", lv -> lv
            .columns("blockKey", "body", "note")
            .filters("blockKey")
            .sorts("blockKey")
            .defaultSort("blockKey", true));
    });

    /** Never public; corrected by withdrawing and recording anew, so only the withdrawal changes a record. */
    public static final EntityDefinition CONSENT_ENTITY = EntityDefinition.define(CONSENT, eb -> {
        eb.physicalTable("cu_consent");
        eb.primaryKey("consentId");
        eb.field("consentId", id("consent_id", "urn:jabiz:entity:culture:consent"));
        eb.field("participantId", f -> f.physicalColumn("participant_id").immutable(true).required(true)
            .asReference(PARTICIPANT));
        eb.field("party", f -> f.physicalColumn("party").immutable(true).required(true)
            .asCode(CONSENT_PARTY, PARTY_PARTICIPANT, PARTY_GUARDIAN));
        eb.field("coversPhoto", f -> f.physicalColumn("covers_photo").immutable(true).required(true).asBool());
        eb.field("coversVideo", f -> f.physicalColumn("covers_video").immutable(true).required(true).asBool());
        eb.field("coversVoice", f -> f.physicalColumn("covers_voice").immutable(true).required(true).asBool());
        eb.field("signedOn", f -> f.physicalColumn("signed_on").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME).apply(Rules.notFuture("CONSENT_SIGNED_IN_FUTURE", 86_400)));
        eb.field("documentFileId", f -> f.physicalColumn("document_file_id").immutable(true)
            .kind(FileKind.of(CONSENT_DOC_POLICY)));
        eb.field("withdrawnTime", f -> f.physicalColumn("withdrawn_time").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("rowVersion", rowVersion("version"));
        eb.listView("default", lv -> lv
            .columns("participantId", "party", "coversPhoto", "coversVideo", "coversVoice", "signedOn",
                "withdrawnTime")
            .filters("participantId", "party")
            .sorts("signedOn")
            .defaultSort("signedOn", false));
    });

    public static final List<EntityDefinition> ALL = List.of(LOCATION_ENTITY, THEME_ENTITY, PARTICIPANT_ENTITY,
        STORY_ENTITY, STORY_THEME_ENTITY, CONTRIBUTION_ENTITY, MEDIA_ITEM_ENTITY, RESOURCE_ENTITY,
        RESOURCE_STORY_ENTITY, SITE_BLOCK_ENTITY, CONSENT_ENTITY);

    @Bean EntityDefinition cultureLocationEntity() { return LOCATION_ENTITY; }
    @Bean EntityDefinition cultureThemeEntity() { return THEME_ENTITY; }
    @Bean EntityDefinition cultureParticipantEntity() { return PARTICIPANT_ENTITY; }
    @Bean EntityDefinition cultureStoryEntity() { return STORY_ENTITY; }
    @Bean EntityDefinition cultureStoryThemeEntity() { return STORY_THEME_ENTITY; }
    @Bean EntityDefinition cultureContributionEntity() { return CONTRIBUTION_ENTITY; }
    @Bean EntityDefinition cultureMediaItemEntity() { return MEDIA_ITEM_ENTITY; }
    @Bean EntityDefinition cultureResourceEntity() { return RESOURCE_ENTITY; }
    @Bean EntityDefinition cultureResourceStoryEntity() { return RESOURCE_STORY_ENTITY; }
    @Bean EntityDefinition cultureSiteBlockEntity() { return SITE_BLOCK_ENTITY; }
    @Bean EntityDefinition cultureConsentEntity() { return CONSENT_ENTITY; }
}
