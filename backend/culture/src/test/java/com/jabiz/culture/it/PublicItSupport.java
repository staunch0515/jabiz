package com.jabiz.culture.it;

import com.jabiz.culture.Culture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.culture.Culture.*;

/**
 * Content for the tests of the public interface (docs/culture/00-design.md section 7), made through the editors'
 * datasets and the workflow as editors make it, then read anonymously.
 */
abstract class PublicItSupport extends CultureItSupport {

    static final String PUBLISH = "CULTURE_STORY_PUBLISH";
    static final String UNPUBLISH = "CULTURE_STORY_UNPUBLISH";

    String slug(String entity, String id) {
        return (String) attributes(Culture.dataset(entity), id).get("slug");
    }

    String location(String slug, Map<String, Object> name, int sortOrder) {
        return create(Culture.dataset(LOCATION), curator(), Map.of("slug", slug, "name", name, "countryCode", "JP",
            "sortOrder", sortOrder, "visible", true));
    }

    String theme(String slug, Map<String, Object> title, Map<String, Object> question) {
        return create(Culture.dataset(THEME), curator(), Map.of("slug", slug, "icon", "🏠", "title", title,
            "question", question, "visible", true));
    }

    /** An active adult participant with full consent, a portrait and a short bio. */
    String person(String location, String displayName, Map<String, Object> shortBio) {
        Map<String, Object> attributes = new HashMap<>(Map.of("slug", unique("person"), "displayName", displayName,
            "locationId", location, "adult", true, "portraitFileId", photo(curator()),
            "portraitAlt", en("A portrait"), "shortBio", shortBio));
        String participant = create(Culture.dataset(PARTICIPANT), curator(), attributes);
        consent(participant, "PARTICIPANT", true, true, true);
        ok("CULTURE_PARTICIPANT_ACTIVATE", curator(), Map.of("participantId", participant));
        return participant;
    }

    /** A story with the given texts, themes and one perspective per participant, not yet published. */
    String story(Map<String, Object> texts, List<String> themes, List<String> participants) {
        Map<String, Object> attributes = storyAttributes(curator());
        attributes.putAll(texts);
        String story = create(Culture.dataset(STORY), curator(), attributes);
        themes.forEach(theme -> link(story, theme));
        participants.forEach(p -> create(Culture.dataset(CONTRIBUTION), curator(), contribution(story, p)));
        return story;
    }

    String published(Map<String, Object> texts, List<String> themes, List<String> participants) {
        String story = story(texts, themes, participants);
        ok(PUBLISH, curator(), Map.of("storyId", story));
        return story;
    }

    /** The values of {@code column} in a public template's rows. */
    List<Object> column(String column, String query, String... params) {
        return publicItems(query, params).stream().map(row -> row.get(column)).toList();
    }
}
