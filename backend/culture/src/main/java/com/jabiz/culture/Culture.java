package com.jabiz.culture;

import java.util.List;

/**
 * Names shared by the declarations of Culture, Unfiltered (docs/culture/00-design.md): entities, datasets,
 * permissions, states and business parameters.
 */
public final class Culture {

    // Entities (section 3).
    public static final String LOCATION = "Location";
    public static final String THEME = "Theme";
    public static final String PARTICIPANT = "Participant";
    public static final String STORY = "Story";
    public static final String STORY_THEME = "StoryTheme";
    public static final String CONTRIBUTION = "Contribution";
    public static final String MEDIA_ITEM = "MediaItem";
    public static final String RESOURCE = "Resource";
    public static final String RESOURCE_STORY = "ResourceStory";
    public static final String SITE_BLOCK = "SiteBlock";
    public static final String CONSENT = "Consent";

    public static final List<String> ENTITIES = List.of(LOCATION, THEME, PARTICIPANT, STORY, STORY_THEME, CONTRIBUTION,
        MEDIA_ITEM, RESOURCE, RESOURCE_STORY, SITE_BLOCK, CONSENT);

    /** The editors' dataset of an entity, its default one (section 5). */
    public static String dataset(String entity) {
        return "urn:jabiz:dataset:culture:" + entity;
    }

    /** A correspondent's writable dataset: their own rows that are still editable. */
    public static String own(String entity) {
        return "urn:jabiz:dataset:own:" + entity;
    }

    /** A correspondent's read-only dataset: all their own rows. */
    public static String ownView(String entity) {
        return "urn:jabiz:dataset:own-view:" + entity;
    }

    /**
     * The public dataset of an entity (section 7.1): the rows anonymous visitors may read, projected to a whitelist.
     * Public templates read only these.
     */
    public static String publicDataset(String entity) {
        return "urn:jabiz:dataset:public:" + entity;
    }

    // Permissions (section 4).
    public static final String CONTENT_READ = "culture.content.read";
    public static final String CONTENT_WRITE = "culture.content.write";
    public static final String STORY_SUBMIT = "culture.story.submit";
    public static final String STORY_REVIEW = "culture.story.review";
    public static final String STORY_PUBLISH = "culture.story.publish";
    public static final String STORY_UNPUBLISH = "culture.story.unpublish";
    public static final String PARTICIPANT_MANAGE = "culture.participant.manage";
    public static final String CONSENT_READ = "culture.consent.read";
    public static final String CONSENT_WRITE = "culture.consent.write";
    public static final String CONSENT_WITHDRAW = "culture.consent.withdraw";
    public static final String RESOURCE_WRITE = "culture.resource.write";
    public static final String RESOURCE_PUBLISH = "culture.resource.publish";
    public static final String MEDIA_UPLOAD = "culture.media.upload";
    public static final String MEDIA_READ = "culture.media.read";
    public static final String OWN_READ = "culture.own.read";
    public static final String OWN_WRITE = "culture.own.write";
    public static final String PUBLIC_READ = "culture.public.read";
    public static final String ERASE = "culture.erase";

    // States (section 6.1).
    public static final String DRAFT = "DRAFT";
    public static final String IN_REVIEW = "IN_REVIEW";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String UNPUBLISHED = "UNPUBLISHED";
    public static final String ACTIVE = "ACTIVE";
    public static final String HIDDEN = "HIDDEN";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String PRIVATE = "PRIVATE";
    public static final String PUBLIC = "PUBLIC";

    // Consent (section 3.10).
    public static final String PARTY_PARTICIPANT = "PARTICIPANT";
    public static final String PARTY_GUARDIAN = "GUARDIAN";

    // Media.
    public static final String PHOTO = "PHOTO";
    public static final String AUDIO = "AUDIO";

    // Switches (section 6.5), business parameters.
    public static final String REVIEW_REQUIRED = "culture.review.required";
    public static final String GUARDIAN_REQUIRED = "culture.consent.guardian.required";

    private Culture() {}
}
