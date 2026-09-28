package com.jabiz.culture.it;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.culture.Culture.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public templates (docs/culture/00-design.md section 7.2) as the public site calls them, anonymously: what each
 * page reads, the filters by place, theme and media type, and search in any language with its wildcards taken
 * literally. Every test class shares one schema, so each test looks only at the rows it made (unique slugs and words).
 */
class PublicTemplatesIT extends PublicItSupport {

    private static final Map<String, Object> JAPAN = Map.of("en", "Japan", "zh", "日本", "ja", "日本");

    @Test
    void thePagesOfTheSiteReadPublishedContentOnly() {
        String japan = location(unique("japan"), JAPAN, 1);
        String eswatini = location(unique("eswatini"), en("Eswatini"), 2);
        String home = theme(unique("home"), en("Home"), en("What makes somewhere feel like home?"));
        String food = theme(unique("food"), en("Food"), en("What do we eat together?"));
        String aiko = person(japan, "Aiko", en("Interested in trains."));
        String sipho = person(eswatini, "Sipho", en("Interested in football."));
        String draftPerson = participant(japan, null, true);

        String shared = story(Map.of("featured", true), List.of(home), List.of(aiko, sipho));
        create(dataset(MEDIA_ITEM), curator(), Map.of("storyId", shared, "kind", PHOTO,
            "imageFileId", photo(curator()), "alt", en("A kitchen")));
        ok(PUBLISH, curator(), Map.of("storyId", shared));
        String photos = published(Map.of("mediaType", "PHOTO"), List.of(food), List.of(sipho));
        String draft = story(Map.of(), List.of(home), List.of(aiko));
        String resource = create(dataset(RESOURCE), curator(), Map.of("slug", unique("resource"),
            "title", en("Mapping home"), "description", en("Draw what home means."), "activityType", "CLASSROOM",
            "ageGroup", "14-16", "durationMinutes", 45));
        create(dataset(RESOURCE_STORY), curator(), Map.of("resourceId", resource, "storyId", shared));
        create(dataset(RESOURCE_STORY), curator(), Map.of("resourceId", resource, "storyId", draft));
        ok("CULTURE_RESOURCE_PUBLISH", curator(), Map.of("resourceId", resource));

        String japanSlug = slug(LOCATION, japan);
        String eswatiniSlug = slug(LOCATION, eswatini);
        String homeSlug = slug(THEME, home);
        String foodSlug = slug(THEME, food);
        String aikoSlug = slug(PARTICIPANT, aiko);
        String siphoSlug = slug(PARTICIPANT, sipho);
        String sharedSlug = slug(STORY, shared);
        String photosSlug = slug(STORY, photos);

        // Places, with their people and the stories those people are in.
        Map<String, Object> japanRow = only("culture.public.locations", "filter", "slug:eq:" + japanSlug);
        assertThat(japanRow).containsEntry("name", JAPAN).containsEntry("participantCount", 1)
            .containsEntry("storyCount", 1);
        assertThat(only("culture.public.locations", "filter", "slug:eq:" + eswatiniSlug))
            .containsEntry("participantCount", 1).containsEntry("storyCount", 2);

        // People.
        assertThat(column("slug", "culture.public.people", "p.location", japanSlug)).containsExactly(aikoSlug);
        assertThat(only("culture.public.person", "p.slug", aikoSlug)).containsEntry("displayName", "Aiko")
            .containsEntry("locationSlug", japanSlug).containsEntry("shortBio", en("Interested in trains."));
        assertThat(publicItems("culture.public.person", "p.slug", slug(PARTICIPANT, draftPerson))).isEmpty();
        assertThat(column("slug", "culture.public.person_stories", "p.slug", aikoSlug)).containsExactly(sharedSlug);
        assertThat(column("slug", "culture.public.person_themes", "p.slug", siphoSlug))
            .containsExactlyInAnyOrder(homeSlug, foodSlug);

        // Themes: the question, and the perspectives of all places side by side, in the places' order.
        Map<String, Object> homeRow = normalized(publicItems("culture.public.themes").stream()
            .filter(row -> homeSlug.equals(row.get("slug"))).findFirst().orElseThrow());
        assertThat(homeRow).containsEntry("storyCount", 1).containsEntry("locationCount", 2);
        assertThat(only("culture.public.theme", "p.slug", homeSlug))
            .containsEntry("question", en("What makes somewhere feel like home?"));
        assertThat(column("locationSlug", "culture.public.theme_perspectives", "p.slug", homeSlug))
            .containsExactly(japanSlug, eswatiniSlug);

        // Stories: filters, the places of the people in them, and the story page.
        assertThat(storySlugs("p.location", japanSlug)).contains(sharedSlug).doesNotContain(photosSlug);
        assertThat(storySlugs("p.location", eswatiniSlug)).contains(sharedSlug, photosSlug);
        assertThat(storySlugs("p.theme", foodSlug)).containsExactly(photosSlug);
        assertThat(storySlugs("p.theme", foodSlug, "p.mediaType", "ARTICLE")).isEmpty();
        assertThat(storySlugs("p.theme", homeSlug, "p.theme", foodSlug)).containsExactlyInAnyOrder(sharedSlug,
            photosSlug);
        assertThat(storySlugs("p.location", japanSlug, "p.featured", "true")).containsExactly(sharedSlug);
        assertThat(storySlugs("p.location", japanSlug, "p.mediaType", "PHOTO")).isEmpty();
        assertThat(only("culture.public.stories", "p.theme", homeSlug, "p.location", japanSlug))
            .containsEntry("locationSlugs", japanSlug + "," + eswatiniSlug);
        assertThat(only("culture.public.story", "p.slug", sharedSlug)).containsEntry("mediaType", "ARTICLE");
        assertThat(publicItems("culture.public.story", "p.slug", slug(STORY, draft))).isEmpty();
        assertThat(column("slug", "culture.public.story_themes", "p.slug", sharedSlug)).containsExactly(homeSlug);
        assertThat(column("participantSlug", "culture.public.story_perspectives", "p.slug", sharedSlug))
            .containsExactlyInAnyOrder(aikoSlug, siphoSlug);
        assertThat(column("alt", "culture.public.story_media", "p.slug", sharedSlug)).containsExactly(en("A kitchen"));

        // Resources and the published stories they work with.
        String resourceSlug = slug(RESOURCE, resource);
        assertThat(column("slug", "culture.public.resources", "p.ageGroup", "14-16")).contains(resourceSlug);
        assertThat(column("slug", "culture.public.resources", "p.ageGroup", "ADULT")).doesNotContain(resourceSlug);
        assertThat(only("culture.public.resource", "p.slug", resourceSlug)).containsEntry("durationMinutes", 45);
        assertThat(column("slug", "culture.public.resource_stories", "p.slug", resourceSlug))
            .containsExactly(sharedSlug);

        // Texts of the fixed pages, preset by the migrations.
        assertThat(column("blockKey", "culture.public.site_blocks", "p.keys", "home.hero.title",
            "p.keys", "home.hero.subtitle")).containsExactly("home.hero.subtitle", "home.hero.title");
    }

    @Test
    void hiddenPlacesThemesAndPeopleLeaveThePages() {
        String place = location(unique("hidden-place"), en("Somewhere"), 3);
        String theme = theme(unique("hidden-theme"), en("Hidden"), en("Is it there?"));
        String shown = theme(unique("shown-theme"), en("Shown"), en("Is it here?"));
        String person = person(place, "Mia", en("Interested in maps."));
        String other = person(location("Tokyo area"), "Ren", en("Interested in trains."));
        String story = published(Map.of(), List.of(theme, shown), List.of(person, other));
        String storySlug = slug(STORY, story);
        String placeSlug = slug(LOCATION, place);

        commit(dataset(LOCATION), curator(), update(place, version(dataset(LOCATION), place),
            Map.of("visible", false))).expectStatus().isOk();
        commit(dataset(THEME), curator(), update(theme, version(dataset(THEME), theme),
            Map.of("visible", false))).expectStatus().isOk();
        ok("CULTURE_PARTICIPANT_HIDE", curator(), Map.of("participantId", other));

        // A hidden place: not listed, no filter, no label; its people stay.
        assertThat(publicItems("culture.public.locations", "filter", "slug:eq:" + placeSlug)).isEmpty();
        assertThat(storySlugs("p.location", placeSlug)).isEmpty();
        assertThat(only("culture.public.person", "p.slug", slug(PARTICIPANT, person)))
            .containsEntry("locationSlug", null);
        // A hidden theme: not on the story, not a filter.
        assertThat(column("slug", "culture.public.story_themes", "p.slug", storySlug))
            .containsExactly(slug(THEME, shown));
        assertThat(storySlugs("p.theme", slug(THEME, theme))).isEmpty();
        // A hidden participant: no page, and their perspective leaves the still published story.
        assertThat(publicItems("culture.public.person", "p.slug", slug(PARTICIPANT, other))).isEmpty();
        assertThat(column("participantSlug", "culture.public.story_perspectives", "p.slug", storySlug))
            .containsExactly(slug(PARTICIPANT, person));
    }

    @Test
    void theMapShowsTheThemesPhotographsAndVideosOfThePeopleOfAPlace() {
        String place = location(unique("sweden"), en("Sweden"), 4);
        String elsewhere = location(unique("poland"), en("Poland"), 5);
        String food = theme(unique("food"), en("Food"), en("What do we eat together?"));
        String hidden = theme(unique("hidden-theme"), en("Hidden"), en("Is it there?"));
        String school = theme(unique("school"), en("School"), en("What do we learn?"));
        String elsa = person(place, "Elsa", en("Interested in bread."));
        String ola = person(elsewhere, "Ola", en("Interested in soup."));

        String story = story(Map.of("mediaType", "VIDEO", "videoProvider", "VIMEO", "videoId", "76979871",
            "captionsConfirmed", true), List.of(food, hidden), List.of());
        Map<String, Object> mine = contribution(story, elsa);
        mine.putAll(Map.of("videoProvider", "YOUTUBE", "videoId", "dQw4w9WgXcQ", "captionsConfirmed", true));
        String elsasPerspective = create(dataset(CONTRIBUTION), curator(), mine);
        String olasPerspective = create(dataset(CONTRIBUTION), curator(), contribution(story, ola));
        String elsasPhoto = create(dataset(MEDIA_ITEM), curator(), Map.of("storyId", story, "kind", PHOTO,
            "contributionId", elsasPerspective, "imageFileId", photo(curator()), "alt", en("Bread on a board")));
        create(dataset(MEDIA_ITEM), curator(), Map.of("storyId", story, "kind", PHOTO,
            "contributionId", olasPerspective, "imageFileId", photo(curator()), "alt", en("Soup")));
        create(dataset(MEDIA_ITEM), curator(), Map.of("storyId", story, "kind", PHOTO,
            "imageFileId", photo(curator()), "alt", en("The table")));
        ok(PUBLISH, curator(), Map.of("storyId", story));
        story(Map.of(), List.of(school), List.of(elsa)); // a draft: none of it is on the map
        commit(dataset(THEME), curator(), update(hidden, version(dataset(THEME), hidden),
            Map.of("visible", false))).expectStatus().isOk();

        String placeSlug = slug(LOCATION, place);
        String storySlug = slug(STORY, story);
        assertThat(column("slug", "culture.public.location_themes", "p.location", placeSlug))
            .containsExactly(slug(THEME, food));
        assertThat(only("culture.public.location_themes", "p.location", placeSlug)).containsEntry("storyCount", 1);

        // Her photograph and video, and the story's own video (someone from the place is in it); not the story's
        // own photograph nor the photograph of someone from elsewhere.
        List<Map<String, Object>> media = publicItems("culture.public.location_media", "p.location", placeSlug);
        assertThat(media).extracting(row -> row.get("itemId"))
            .containsExactlyInAnyOrder(elsasPhoto, elsasPerspective, story);
        Map<String, Object> photo = media.stream().filter(row -> elsasPhoto.equals(row.get("itemId"))).findFirst()
            .orElseThrow();
        assertThat(photo).containsEntry("kind", "PHOTO").containsEntry("alt", en("Bread on a board"))
            .containsEntry("storySlug", storySlug).containsEntry("participantSlug", slug(PARTICIPANT, elsa))
            .containsEntry("videoId", null);
        Map<String, Object> video = media.stream().filter(row -> elsasPerspective.equals(row.get("itemId")))
            .findFirst().orElseThrow();
        assertThat(video).containsEntry("kind", "VIDEO").containsEntry("videoProvider", "YOUTUBE")
            .containsEntry("videoId", "dQw4w9WgXcQ").containsEntry("alt", en("A kitchen table set for dinner"))
            .containsEntry("displayName", "Elsa");
        assertThat(column("itemId", "culture.public.location_media", "p.location", placeSlug, "filter",
            "kind:eq:VIDEO")).containsExactlyInAnyOrder(elsasPerspective, story);

        // Once she is hidden, what she made leaves the map; the story's own video goes with her perspective.
        ok("CULTURE_PARTICIPANT_HIDE", curator(), Map.of("participantId", elsa));
        assertThat(publicItems("culture.public.location_media", "p.location", placeSlug)).isEmpty();
        assertThat(publicItems("culture.public.location_themes", "p.location", placeSlug)).isEmpty();
        // A hidden place has no panel.
        String olaSlug = slug(LOCATION, elsewhere);
        assertThat(publicItems("culture.public.location_media", "p.location", olaSlug)).hasSize(2);
        commit(dataset(LOCATION), curator(), update(elsewhere, version(dataset(LOCATION), elsewhere),
            Map.of("visible", false))).expectStatus().isOk();
        assertThat(publicItems("culture.public.location_media", "p.location", olaSlug)).isEmpty();
        assertThat(publicItems("culture.public.location_themes", "p.location", olaSlug)).isEmpty();
    }

    @Test
    void searchFindsWordsOfAnyLanguageAndTakesWildcardsLiterally() {
        String token = unique("w").replace("-", "");
        String theme = theme(unique("kitchen"), en("Kitchens " + token), en("Who cooks at home?"));
        String person = person(location("Tokyo area"), "Haru" + token, en("Interested in cooking."));
        String kitchen = published(Map.of(
            "title", Map.of("en", "Grandmother's kitchen", "zh", "奶奶的厨房" + token, "ja", "おばあちゃんの台所" + token),
            "summary", en(token + "-100% homemade, a_b")), List.of(theme), List.of(person));
        String dumplings = published(Map.of("summary", en(token + "-1000 dumplings, axb")), List.of(theme),
            List.of(person));
        story(Map.of("summary", en(token + " still a draft")), List.of(theme), List.of(person));
        String kitchenSlug = slug(STORY, kitchen);
        String dumplingsSlug = slug(STORY, dumplings);

        // Chinese, Japanese, and English regardless of case.
        assertThat(storySlugs("p.q", "厨房" + token)).containsExactly(kitchenSlug);
        assertThat(storySlugs("p.q", "台所" + token)).containsExactly(kitchenSlug);
        assertThat(storySlugs("p.q", token.toUpperCase())).containsExactlyInAnyOrder(kitchenSlug, dumplingsSlug);
        // % and _ are the visitor's characters, not wildcards.
        assertThat(storySlugs("p.q", token + "-100%")).containsExactly(kitchenSlug);
        assertThat(storySlugs("p.q", token + "-100")).containsExactlyInAnyOrder(kitchenSlug, dumplingsSlug);
        assertThat(storySlugs("p.q", "a_b")).contains(kitchenSlug).doesNotContain(dumplingsSlug);
        // Filters and words together; too short or too long words find nothing, no words search nothing.
        assertThat(storySlugs("p.q", token, "p.theme", slug(THEME, theme))).hasSize(2);
        assertThat(storySlugs("p.q", "k")).isEmpty();
        assertThat(storySlugs("p.q", token + "x".repeat(100 - token.length() + 1))).isEmpty();
        assertThat(storySlugs("p.q", "", "p.theme", slug(THEME, theme))).hasSize(2);

        // The search page: stories, people, themes, resources; never drafts.
        List<Map<String, Object>> found = publicItems("culture.public.search", "p.q", token);
        assertThat(found).extracting(row -> row.get("kind") + ":" + row.get("slug")).containsExactlyInAnyOrder(
            "PERSON:" + slug(PARTICIPANT, person), "STORY:" + kitchenSlug, "STORY:" + dumplingsSlug,
            "THEME:" + slug(THEME, theme));
        assertThat(publicItems("culture.public.search", "p.q", token, "filter", "kind:eq:PERSON"))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("name", "Haru" + token)
                .containsEntry("title", null));
        assertThat(publicItems("culture.public.search", "p.q", "x")).isEmpty();
        assertThat(publicItems("culture.public.search", "p.q", token + "%")).isEmpty();
    }

    @Test
    void theInterfaceRefusesWhatItDoesNotOffer() {
        // Unknown templates are not found; unknown parameters, missing ones and outer filters that are not offered
        // are refused. A media type that is not in the dictionary (editors add values) simply matches nothing.
        publicQuery("culture.public.nothing").expectStatus().isNotFound();
        assertThat(storySlugs("p.mediaType", "NOVEL")).isEmpty();
        publicQuery("culture.public.stories", "p.unknown", "x").expectStatus().isBadRequest();
        publicQuery("culture.public.story").expectStatus().isBadRequest();
        publicQuery("culture.public.stories", "filter", "slug:eq:x").expectStatus().isBadRequest();
        client.post().uri("/api/public/queries/culture.public.stories").exchange()
            .expectStatus().isEqualTo(405);
        // Cached by browsers and proxies for a minute.
        publicQuery("culture.public.themes").expectStatus().isOk().expectHeader()
            .valueEquals("Cache-Control", "public, max-age=60");
    }

    private Map<String, Object> only(String query, String... params) {
        List<Map<String, Object>> items = publicItems(query, params);
        assertThat(items).as(query).hasSize(1);
        return normalized(items.getFirst());
    }

    /** Numbers as ints, so rows compare with literals. */
    private static Map<String, Object> normalized(Map<String, Object> row) {
        Map<String, Object> copy = new HashMap<>();
        row.forEach((k, v) -> copy.put(k, v instanceof Number n && n.doubleValue() == n.intValue() ? n.intValue() : v));
        return copy;
    }

    private List<Object> storySlugs(String... params) {
        return column("slug", "culture.public.stories", params);
    }
}
