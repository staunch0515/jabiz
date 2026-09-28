package com.jabiz.culture.it;

import com.jabiz.culture.Culture;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.test.FileSamples;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;
import net.jqwik.api.sessions.JqwikSession;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.jabiz.culture.Culture.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * C2 acceptance (docs/culture/ROADMAP.md): whatever the combination of states, the public templates return published
 * data only, and anonymous visitors get only the files of public rows. jqwik generates worlds: places and themes shown
 * or hidden; participants never activated, active, hidden, withdrawn or reopened, with or without portrait and consent
 * scan; stories never published, published, unpublished or reopened, with perspectives, photos and audio, some with a
 * photo added after publication; resources published or not. The worlds are made through the editors' datasets and
 * the workflow (a publication the check refuses stays refused), so every state is one the application can reach.
 *
 * <p>The oracle is the editors' view of the database, not the generator's plan: a story is published when its status
 * says so, a perspective shows when it is public, its story published and its participant active, and so on. Every row
 * of every public template, called with every slug there is (published or not) and every filter value, must satisfy
 * it; the lists of stories, people and places must equal it. The seeds are fixed so that a failure reproduces.
 */
class PublicVisibilityPropertyIT extends PublicItSupport {

    private static final long[] SEEDS = {7_101L, 7_102L, 7_103L, 7_104L, 7_105L, 7_106L, 7_107L, 7_108L};

    enum PersonFate { NEVER_ACTIVE, ACTIVE, HIDDEN, WITHDRAWN, REOPENED }

    enum StoryFate { DRAFT, PUBLISHED, UNPUBLISHED, REOPENED }

    record PersonPlan(int location, boolean portrait, boolean scan, PersonFate fate) {}

    record StoryPlan(Set<Integer> themes, Set<Integer> people, boolean storyPhoto, boolean perspectiveMedia,
        boolean late, StoryFate fate) {}

    record ResourcePlan(boolean published, boolean pdf, Set<Integer> stories) {}

    record World(List<Boolean> places, List<Boolean> themes, List<PersonPlan> people, List<StoryPlan> stories,
        List<ResourcePlan> resources) {}

    static Arbitrary<World> worlds() {
        Arbitrary<Set<Integer>> someOf = Arbitraries.integers().between(0, 3).set().ofMaxSize(3);
        Arbitrary<PersonPlan> person = Combinators.combine(Arbitraries.integers().between(0, 1),
            Arbitraries.of(true, false), Arbitraries.of(true, false),
            Arbitraries.frequency(
                Tuple.of(1, PersonFate.NEVER_ACTIVE), Tuple.of(6, PersonFate.ACTIVE),
                Tuple.of(3, PersonFate.HIDDEN), Tuple.of(1, PersonFate.WITHDRAWN),
                Tuple.of(1, PersonFate.REOPENED)))
            .as(PersonPlan::new);
        Arbitrary<StoryPlan> story = Combinators.combine(Arbitraries.integers().between(0, 1).set().ofMaxSize(2),
            Arbitraries.integers().between(0, 3).set().ofMinSize(1).ofMaxSize(2), Arbitraries.of(true, false),
            Arbitraries.of(true, false), Arbitraries.of(true, false),
            Arbitraries.frequency(Tuple.of(1, StoryFate.DRAFT),
                Tuple.of(3, StoryFate.PUBLISHED), Tuple.of(1, StoryFate.UNPUBLISHED),
                Tuple.of(1, StoryFate.REOPENED)))
            .as(StoryPlan::new);
        Arbitrary<ResourcePlan> resource = Combinators.combine(Arbitraries.of(true, false),
            Arbitraries.of(true, false), someOf).as(ResourcePlan::new);
        Arbitrary<List<Boolean>> shown = Arbitraries.frequency(Tuple.of(3, true),
            Tuple.of(1, false)).list().ofSize(2);
        return Combinators.combine(shown, shown, person.list().ofSize(4), story.list().ofSize(4),
            resource.list().ofSize(2)).as(World::new);
    }

    private final Set<String> called = new HashSet<>();

    @Override
    List<Map<String, Object>> publicItems(String query, String... params) {
        called.add(query);
        return super.publicItems(query, params);
    }

    @Test
    void publicTemplatesReturnPublishedDataOnly() {
        int publishedStories = 0;
        int hiddenRows = 0;
        int hiddenPerspectives = 0;
        for (long seed : SEEDS) {
            Set<String> filesBefore = Oracle.read(this).files.keySet();
            World world = generate(seed);
            make(world);
            Oracle oracle = Oracle.read(this);
            publishedStories += oracle.stories.size();
            hiddenRows += oracle.allStorySlugs.size() - oracle.stories.size();
            hiddenPerspectives += oracle.hiddenPerspectives;

            oracle.assertWorkflowInvariant(seed);
            checkTemplates(oracle, seed);
            // Files of this world only: they were never asked for before, so no remembered decision is stale.
            oracle.files.forEach((file, allowed) -> {
                if (!filesBefore.contains(file)) {
                    assertThat(publicFile(file)).as("file %s (seed %d)", file, seed).isEqualTo(allowed ? 200 : 404);
                }
            });
        }
        // Every public template was called: a new one must be added to checkTemplates.
        assertThat(called).isEqualTo(context.getBean(SqlTemplateRegistry.class).all().stream()
            .filter(AdvancedQueryDefinition::publicAccess).map(AdvancedQueryDefinition::queryId)
            .collect(Collectors.toSet()));
        // The worlds exercise both sides.
        assertThat(publishedStories).isGreaterThan(5);
        assertThat(hiddenRows).isGreaterThan(5);
        // Public perspectives of participants no longer active, in published stories: the pages must leave them out.
        assertThat(hiddenPerspectives).isGreaterThan(0);
    }

    /** One world from the generators, outside a jqwik property: a jqwik session provides their context. */
    private static World generate(long seed) {
        JqwikSession.start(String.valueOf(seed));
        try {
            return worlds().generator(1000).next(new Random(seed)).value();
        } finally {
            JqwikSession.finish();
        }
    }

    // Making a world.

    private void make(World world) {
        List<String> places = new ArrayList<>();
        for (int i = 0; i < world.places().size(); i++) {
            places.add(location(unique("place"), en("Place " + i), i));
        }
        List<String> themes = new ArrayList<>();
        for (int i = 0; i < world.themes().size(); i++) {
            themes.add(theme(unique("theme"), en("Home " + i), en("What makes somewhere feel like home?")));
        }
        List<String> people = new ArrayList<>();
        List<String> consents = new ArrayList<>();
        for (PersonPlan plan : world.people()) {
            Map<String, Object> attributes = new HashMap<>(Map.of("slug", unique("person"), "displayName", "Aiko",
                "locationId", places.get(plan.location()), "adult", true, "shortBio", en("Interested in home.")));
            if (plan.portrait()) {
                attributes.put("portraitFileId", photo(curator()));
                attributes.put("portraitAlt", en("A portrait"));
            }
            String person = create(dataset(PARTICIPANT), curator(), attributes);
            Map<String, Object> consent = new HashMap<>(Map.of("participantId", person, "party", "PARTICIPANT",
                "coversPhoto", true, "coversVideo", true, "coversVoice", true, "signedOn", "2026-01-10T00:00:00Z"));
            if (plan.scan()) {
                consent.put("documentFileId", consentScan());
            }
            consents.add(create(dataset(CONSENT), curator(), consent));
            if (plan.fate() != PersonFate.NEVER_ACTIVE) {
                ok("CULTURE_PARTICIPANT_ACTIVATE", curator(), Map.of("participantId", person));
            }
            people.add(person);
        }
        List<String> stories = new ArrayList<>();
        for (StoryPlan plan : world.stories()) {
            String story = create(dataset(STORY), curator(), storyAttributes(curator()));
            plan.themes().forEach(t -> link(story, themes.get(t)));
            boolean first = true;
            for (int p : plan.people()) {
                Map<String, Object> contribution = contribution(story, people.get(p));
                if (first && plan.perspectiveMedia()) {
                    contribution.put("audioFileId", audio(curator()));
                    contribution.put("transcript", en("What I said."));
                }
                String perspective = create(dataset(CONTRIBUTION), curator(), contribution);
                if (first && plan.perspectiveMedia()) {
                    create(dataset(MEDIA_ITEM), curator(), Map.of("storyId", story, "contributionId", perspective,
                        "kind", PHOTO, "imageFileId", photo(curator()), "alt", en("My street")));
                }
                first = false;
            }
            if (plan.storyPhoto()) {
                create(dataset(MEDIA_ITEM), curator(), Map.of("storyId", story, "kind", PHOTO,
                    "imageFileId", photo(curator()), "alt", en("A kitchen")));
            }
            if (plan.fate() != StoryFate.DRAFT) {
                // The publish check may refuse (no theme, no perspective, a participant not active): it stays a draft.
                boolean published = run(PUBLISH, curator(), Map.of("storyId", story)).returnResult(byte[].class)
                    .getStatus().is2xxSuccessful();
                if (published && plan.fate() != StoryFate.PUBLISHED) {
                    ok(UNPUBLISH, curator(), Map.of("storyId", story));
                    if (plan.fate() == StoryFate.REOPENED) {
                        ok("CULTURE_STORY_REOPEN", curator(), Map.of("storyId", story));
                    }
                }
            }
            if (plan.late()) {
                create(dataset(MEDIA_ITEM), curator(), Map.of("storyId", story, "kind", PHOTO,
                    "imageFileId", photo(curator()), "alt", en("Added later")));
            }
            stories.add(story);
        }
        for (ResourcePlan plan : world.resources()) {
            Map<String, Object> attributes = new HashMap<>(Map.of("slug", unique("resource"),
                "title", en("Mapping home"), "description", en("Draw what home means."), "activityType", "CLASSROOM",
                "ageGroup", "14-16", "durationMinutes", 45));
            if (plan.pdf()) {
                attributes.put("pdfFileId", upload("culture.pdf", FileSamples.pdf(), "activity.pdf",
                    "application/pdf", curator()));
            }
            String resource = create(dataset(RESOURCE), curator(), attributes);
            plan.stories().forEach(s -> create(dataset(RESOURCE_STORY), curator(),
                Map.of("resourceId", resource, "storyId", stories.get(s))));
            if (plan.published()) {
                ok("CULTURE_RESOURCE_PUBLISH", curator(), Map.of("resourceId", resource));
            }
        }
        for (int i = 0; i < people.size(); i++) {
            String person = people.get(i);
            switch (world.people().get(i).fate()) {
                case HIDDEN -> ok("CULTURE_PARTICIPANT_HIDE", curator(), Map.of("participantId", person));
                case WITHDRAWN -> ok("CULTURE_CONSENT_WITHDRAW", curator(), Map.of("consentId", consents.get(i)));
                case REOPENED -> {
                    ok("CULTURE_CONSENT_WITHDRAW", curator(), Map.of("consentId", consents.get(i)));
                    ok("CULTURE_PARTICIPANT_REOPEN", curator(), Map.of("participantId", person));
                }
                default -> { }
            }
        }
        hide(LOCATION, places, world.places());
        hide(THEME, themes, world.themes());
    }

    private void hide(String entity, List<String> ids, List<Boolean> shown) {
        for (int i = 0; i < ids.size(); i++) {
            if (!shown.get(i)) {
                commit(dataset(entity), curator(), update(ids.get(i), version(dataset(entity), ids.get(i)),
                    Map.of("visible", false))).expectStatus().isOk();
            }
        }
    }

    // Checking the templates.

    private void checkTemplates(Oracle oracle, long seed) {
        Checker check = new Checker(oracle, seed);
        Map<String, String> lists = Map.of("culture.public.locations", LOCATION, "culture.public.people", PARTICIPANT,
            "culture.public.themes", THEME, "culture.public.stories", STORY, "culture.public.resources", RESOURCE);
        lists.forEach((query, entity) -> check.rows(query, entity, publicItems(query, "limit", "100")));
        check.rows("culture.public.site_blocks", null, publicItems("culture.public.site_blocks"));
        assertThat(slugs("culture.public.stories")).as("stories (seed %d)", seed).isEqualTo(oracle.stories);
        assertThat(slugs("culture.public.people")).as("people (seed %d)", seed).isEqualTo(oracle.people);
        assertThat(slugs("culture.public.locations")).as("places (seed %d)", seed).isEqualTo(oracle.places);

        Map<String, Set<String>> bySlug = new LinkedHashMap<>();
        bySlug.put(PARTICIPANT, oracle.allPeopleSlugs);
        bySlug.put(THEME, oracle.allThemeSlugs);
        bySlug.put(STORY, oracle.allStorySlugs);
        bySlug.put(RESOURCE, oracle.allResourceSlugs);
        Map<String, List<String>> templates = Map.of(
            PARTICIPANT, List.of("person", "person_stories", "person_themes"),
            THEME, List.of("theme", "theme_perspectives"),
            STORY, List.of("story", "story_themes", "story_perspectives", "story_media"),
            RESOURCE, List.of("resource", "resource_stories"));
        bySlug.forEach((entity, slugs) -> {
            for (String slug : slugs) {
                boolean shown = oracle.isPublic(entity, slug);
                for (String name : templates.get(entity)) {
                    String query = "culture.public." + name;
                    List<Map<String, Object>> rows = publicItems(query, "p.slug", slug);
                    if (!shown) {
                        assertThat(rows).as("%s of hidden %s %s (seed %d)", query, entity, slug, seed).isEmpty();
                    }
                    // The page's own row is of the entity asked for; the lists under it are of others.
                    String slugEntity = name.contains("_") ? listedEntity(name) : entity;
                    check.rows(query, slugEntity, rows);
                }
            }
        });
        for (String place : oracle.allPlaceSlugs) {
            check.rows("culture.public.stories", STORY, publicItems("culture.public.stories", "p.location", place));
            check.rows("culture.public.people", PARTICIPANT, publicItems("culture.public.people", "p.location", place));
            // The map's panel of a place: nothing of a hidden place.
            for (String query : List.of("culture.public.location_themes", "culture.public.location_media")) {
                List<Map<String, Object>> rows = publicItems(query, "p.location", place);
                if (!oracle.places.contains(place)) {
                    assertThat(rows).as("%s of hidden place %s (seed %d)", query, place, seed).isEmpty();
                }
                check.rows(query, query.endsWith("themes") ? THEME : null, rows);
            }
        }
        for (String theme : oracle.allThemeSlugs) {
            check.rows("culture.public.stories", STORY, publicItems("culture.public.stories", "p.theme", theme));
        }
        for (String words : List.of("home", "Aiko", "somewhere", "Mapping", "kitchen")) {
            check.rows("culture.public.search", null, publicItems("culture.public.search", "p.q", words));
            check.rows("culture.public.stories", STORY, publicItems("culture.public.stories", "p.q", words));
        }
    }

    private static String listedEntity(String template) {
        return switch (template) {
            case "person_stories", "resource_stories" -> STORY;
            case "person_themes", "story_themes" -> THEME;
            default -> null; // perspectives and media: no slug column of their own
        };
    }

    private Set<String> slugs(String query) {
        return publicItems(query, "limit", "100").stream().map(row -> (String) row.get("slug"))
            .collect(Collectors.toSet());
    }

    /** Every value in a row of a public template must belong to published data. */
    private record Checker(Oracle oracle, long seed) {

        private static final Map<String, String> SLUG_COLUMNS = Map.of("participantSlug", PARTICIPANT,
            "storySlug", STORY, "locationSlug", LOCATION);

        void rows(String query, String slugEntity, List<Map<String, Object>> rows) {
            for (Map<String, Object> row : rows) {
                String entity = "culture.public.search".equals(query) ? searchEntity(row) : slugEntity;
                row.forEach((column, value) -> check(query, entity, column, value));
            }
        }

        private static String searchEntity(Map<String, Object> row) {
            return switch ((String) row.get("kind")) {
                case "STORY" -> STORY;
                case "PERSON" -> PARTICIPANT;
                case "THEME" -> THEME;
                default -> RESOURCE;
            };
        }

        private void check(String query, String slugEntity, String column, Object value) {
            if (value == null) {
                return;
            }
            String what = query + " " + column + "=" + value + " (seed " + seed + ")";
            switch (column) {
                case "storyId" -> assertThat(oracle.storyIds).as(what).contains((String) value);
                case "participantId" -> assertThat(oracle.peopleIds).as(what).contains((String) value);
                case "contributionId" -> assertThat(oracle.contributions).as(what).contains((String) value);
                case "mediaItemId" -> assertThat(oracle.media).as(what).contains((String) value);
                case "themeId" -> assertThat(oracle.themeIds).as(what).contains((String) value);
                case "resourceId" -> assertThat(oracle.resourceIds).as(what).contains((String) value);
                case "locationId" -> assertThat(oracle.placeIds).as(what).contains((String) value);
                // location_media: a shown photograph, perspective (its video) or story (its own video).
                case "itemId" -> assertThat(Stream.of(oracle.media, oracle.contributions, oracle.storyIds)
                    .anyMatch(ids -> ids.contains((String) value))).as(what).isTrue();
                case "locationSlugs" -> assertThat(oracle.places).as(what)
                    .containsAll(List.of(((String) value).split(",")));
                case "slug" -> {
                    if (slugEntity != null) {
                        assertThat(oracle.isPublic(slugEntity, (String) value)).as(what).isTrue();
                    }
                }
                default -> {
                    if (SLUG_COLUMNS.containsKey(column)) {
                        assertThat(oracle.isPublic(SLUG_COLUMNS.get(column), (String) value)).as(what).isTrue();
                    } else if (column.endsWith("FileId")) {
                        assertThat(oracle.shownFiles).as(what).contains((String) value);
                    }
                }
            }
        }
    }

    /**
     * What is public, from the editors' view of every row: the definition the templates must follow
     * (docs/culture/00-design.md section 7).
     */
    private static final class Oracle {
        final Set<String> places = new HashSet<>();
        final Set<String> placeIds = new HashSet<>();
        final Set<String> allPlaceSlugs = new HashSet<>();
        final Set<String> themeIds = new HashSet<>();
        final Set<String> themeSlugs = new HashSet<>();
        final Set<String> allThemeSlugs = new HashSet<>();
        final Set<String> people = new HashSet<>();
        final Set<String> peopleIds = new HashSet<>();
        final Set<String> allPeopleSlugs = new HashSet<>();
        final Set<String> stories = new HashSet<>();
        final Set<String> storyIds = new HashSet<>();
        final Set<String> allStorySlugs = new HashSet<>();
        final Set<String> resources = new HashSet<>();
        final Set<String> resourceIds = new HashSet<>();
        final Set<String> allResourceSlugs = new HashSet<>();
        final Set<String> contributions = new HashSet<>();
        final Set<String> media = new HashSet<>();
        /** Files the pages show. */
        final Set<String> shownFiles = new HashSet<>();
        /** Every file, and whether a public row refers to it (what the public file endpoint decides by). */
        final Map<String, Boolean> files = new HashMap<>();
        final List<Map<String, Object>> publicChildren = new ArrayList<>();
        int hiddenPerspectives;

        static Oracle read(CultureItSupport it) {
            Oracle o = new Oracle();
            for (Map<String, Object> l : o.rows(it, LOCATION)) {
                o.allPlaceSlugs.add(slug(l));
                if (Boolean.TRUE.equals(l.get("visible"))) {
                    o.places.add(slug(l));
                    o.placeIds.add(id(l));
                }
            }
            for (Map<String, Object> t : o.rows(it, THEME)) {
                o.allThemeSlugs.add(slug(t));
                if (Boolean.TRUE.equals(t.get("visible"))) {
                    o.themeSlugs.add(slug(t));
                    o.themeIds.add(id(t));
                }
            }
            for (Map<String, Object> p : o.rows(it, PARTICIPANT)) {
                o.allPeopleSlugs.add(slug(p));
                boolean active = ACTIVE.equals(p.get("status"));
                if (active) {
                    o.people.add(slug(p));
                    o.peopleIds.add(id(p));
                }
                o.file(p.get("portraitFileId"), active, active);
            }
            List<Map<String, Object>> storyRows = o.rows(it, STORY);
            for (Map<String, Object> s : storyRows) {
                o.allStorySlugs.add(slug(s));
                boolean published = PUBLISHED.equals(s.get("status"));
                if (published) {
                    o.stories.add(slug(s));
                    o.storyIds.add(id(s));
                }
                o.file(s.get("thumbnailFileId"), published, published);
            }
            for (Map<String, Object> c : o.rows(it, CONTRIBUTION)) {
                boolean isPublic = PUBLIC.equals(c.get("visibility"));
                boolean shown = isPublic && o.storyIds.contains((String) c.get("storyId"))
                    && o.peopleIds.contains((String) c.get("participantId"));
                if (shown) {
                    o.contributions.add(id(c));
                } else if (isPublic && o.storyIds.contains((String) c.get("storyId"))) {
                    o.hiddenPerspectives++;
                }
                if (isPublic) {
                    o.publicChildren.add(c);
                }
                o.file(c.get("audioFileId"), isPublic, shown);
            }
            for (Map<String, Object> m : o.rows(it, MEDIA_ITEM)) {
                boolean isPublic = PUBLIC.equals(m.get("visibility"));
                Object perspective = m.get("contributionId");
                boolean shown = isPublic && o.storyIds.contains((String) m.get("storyId"))
                    && (perspective == null || o.contributions.contains((String) perspective));
                if (shown) {
                    o.media.add(id(m));
                }
                if (isPublic) {
                    o.publicChildren.add(m);
                }
                o.file(m.get("imageFileId"), isPublic, shown);
                o.file(m.get("audioFileId"), isPublic, shown);
            }
            for (Map<String, Object> r : o.rows(it, RESOURCE)) {
                o.allResourceSlugs.add(slug(r));
                boolean published = PUBLISHED.equals(r.get("status"));
                if (published) {
                    o.resources.add(slug(r));
                    o.resourceIds.add(id(r));
                }
                o.file(r.get("pdfFileId"), published, published);
            }
            for (Map<String, Object> st : o.rows(it, STORY_THEME)) {
                if (PUBLIC.equals(st.get("visibility"))) {
                    o.publicChildren.add(st);
                }
            }
            for (Map<String, Object> c : o.rows(it, CONSENT)) {
                o.file(c.get("documentFileId"), false, false);
            }
            return o;
        }

        boolean isPublic(String entity, String slug) {
            return switch (entity) {
                case PARTICIPANT -> people.contains(slug);
                case THEME -> themeSlugs.contains(slug);
                case STORY -> stories.contains(slug);
                case RESOURCE -> resources.contains(slug);
                case LOCATION -> places.contains(slug);
                default -> throw new IllegalArgumentException(entity);
            };
        }

        /** The workflow keeps the parts of a story public only while the story is published. */
        void assertWorkflowInvariant(long seed) {
            for (Map<String, Object> child : publicChildren) {
                assertThat(storyIds).as("story of public part %s (seed %d)", child, seed)
                    .contains((String) child.get("storyId"));
            }
        }

        private void file(Object id, boolean allowed, boolean shown) {
            if (id != null) {
                files.merge(id.toString(), allowed, Boolean::logicalOr);
                if (shown) {
                    shownFiles.add(id.toString());
                }
            }
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> rows(CultureItSupport it, String entity) {
            Map<String, Object> page = it.post("/api/datasets/" + Culture.dataset(entity) + "/query", it.admin(),
                Map.of("limit", 1000)).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> item : (List<Map<String, Object>>) page.get("items")) {
                Map<String, Object> row = new HashMap<>((Map<String, Object>) item.get("attributes"));
                row.put("__id", item.get("id"));
                rows.add(row);
            }
            return rows;
        }

        private static String slug(Map<String, Object> row) {
            return (String) row.get("slug");
        }

        private static String id(Map<String, Object> row) {
            return (String) row.get("__id");
        }
    }
}
