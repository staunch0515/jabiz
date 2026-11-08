package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.topic.TopicCodes;
import com.jabiz.quizbuks.topic.TopicRules;
import com.jabiz.quizbuks.topic.TopicSteps;
import com.jabiz.runtime.EntityInstance;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.quizbuks.publishing.PublicationEditing.INPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.OUTPUT;
import static com.jabiz.quizbuks.publishing.PublicationEditing.PUBLICATION;
import static com.jabiz.quizbuks.publishing.PublicationEditing.VERSIONS;

/**
 * A publication's topics (F-S9, docs/quizbuks/plans/impl/F-S9.md): it inherits its version's and adds or removes some
 * ({@code TopicRules.Changes}). {@value #SET} replaces a draft's changes (the wizard's first step), as the matching
 * conditions are replaced: under the publication's lock, at the revision the page edited, only while it is a draft.
 * The changes are kept against the version; the card gets the topics they make ({@link #writeTo}) when the publication
 * goes on and at a switch of version, which applies the same changes to the new version's topics.
 */
public final class PublicationTopics {

    public static final String SET = "QB_PUBLICATION_TOPICS_SET";

    /**
     * The whole of the draft's changes (those left out are dropped).
     *
     * @param add          codes of words the version lacks
     * @param remove       codes of the version's words left out
     * @param customAdd    custom words of its own (for search only)
     * @param customRemove the version's custom words left out
     * @param baseRevision the revision the page edited; when given and no longer current, 422
     */
    public record TopicsInput(@NotNull UUID publicationId,
        @Size(max = TopicRules.HARD_MAX) List<@NotNull @Size(max = TopicRules.CODE_LENGTH) String> add,
        @Size(max = TopicRules.HARD_MAX) List<@NotNull @Size(max = TopicRules.CODE_LENGTH) String> remove,
        @Size(max = TopicRules.HARD_MAX)
        List<@NotNull @Size(max = TopicRules.CUSTOM_HARD_MAX_LENGTH * 2) String> customAdd,
        @Size(max = TopicRules.HARD_MAX)
        List<@NotNull @Size(max = TopicRules.CUSTOM_HARD_MAX_LENGTH * 2) String> customRemove, Long baseRevision) {}

    public static ProcessDefinition<TopicsInput, PublicationOutput, ProcessContext> set() {
        return ProcessDefinition.define(SET, 1, TopicsInput.class, PublicationOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Adds topics to a draft publication, or leaves out some of its quiz version's.")
                    .permissions(QbPermissions.PUBLICATION_WRITE)
                    .requiresVerifiedEmail()
                    .steps(CallerChecks::refuseBanned)
                    .actsOn(QbPublications.PUBLICATION, "publicationId", a -> a.whenField("status",
                        PublicationStatus.DRAFT))
                    .contextFactory((start, input) -> PublicationEditing.start(start, input, input.publicationId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, PublicationOutput.class));
                // The word list and the limits change nothing of the publication: read before its lock.
                TopicSteps.loadParams(pb);
                TopicSteps.loadWords(pb, ctx -> {
                    List<String> add = ctx.get(INPUT, TopicsInput.class).add();
                    return add == null ? List.of() : add.stream().map(String::strip).toList();
                });
                PublicationEditing.lockAndLoad(pb);
                PublicationEditing.loadVersion(pb, ctx -> {
                    EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
                    return publication == null ? null : publication.get("versionId");
                }, false);
                pb.compute("Set the topics", (metadata, ctx) -> set(ctx));
            });
    }

    static void set(ProcessContext ctx) {
        TopicsInput input = ctx.get(INPUT, TopicsInput.class);
        EntityInstance publication = ctx.get(PUBLICATION, EntityInstance.class);
        if (!PublicationEditing.draft(ctx, publication)
            || !PublicationEditing.current(ctx, publication, input.baseRevision())) {
            return;
        }
        EntityInstance version = PublicationEditing.first(ctx, VERSIONS);
        if (version == null) {
            ctx.reject(new Violation("versionId", TopicCodes.NO_VERSION, "The publication's topics change its quiz "
                + "version's: choose the version first", Map.of()));
            return;
        }
        List<String> customAdd = input.customAdd() == null ? List.of() : input.customAdd();
        TopicRules.customProblems("customAdd", customAdd, TopicSteps.customMaxLength(ctx)).forEach(ctx::reject);
        TopicRules.Topics inherited = versionTopics(version);
        TopicRules.Changes before = TopicRules.Changes.of(publication.get("topicChanges"));
        TopicRules.Changes changes = new TopicRules.Changes(stripped(input.add()), stripped(input.remove()),
            customAdd, input.customRemove()).against(inherited);
        // A word added before stays though deactivated since; one added anew must be offered.
        TopicRules.choiceProblems("add", changes.add(), before.add(), TopicSteps.words(ctx)).forEach(ctx::reject);
        TopicRules.countProblems("add", TopicRules.effective(inherited, changes), TopicSteps.maxPerQuiz(ctx))
            .forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return;
        }
        String json = changes.json();
        if (Objects.equals(json, publication.get("topicChanges"))) {
            PublicationEditing.unchanged(ctx, publication);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("topicChanges", json);
        PublicationEditing.write(ctx, publication, values);
    }

    /** A version's topics. */
    static TopicRules.Topics versionTopics(EntityInstance version) {
        return TopicRules.Topics.of(version.get("topics"), version.get("customTopics"));
    }

    /**
     * The publication's topics on its card: its version's with its changes. When it goes on, and at a switch of
     * version (the new version's topics, the same changes).
     */
    static void writeTo(Map<String, Object> card, EntityInstance publication, EntityInstance version) {
        TopicRules.Topics topics = TopicRules.effective(versionTopics(version),
            TopicRules.Changes.of(publication.get("topicChanges")));
        card.put("topics", topics.codesColumn());
        card.put("customTopics", topics.customColumn());
    }

    private static List<String> stripped(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String code : codes) {
            String text = code.strip();
            if (!text.isEmpty() && seen.add(text)) {
                out.add(text);
            }
        }
        return out;
    }

    private PublicationTopics() {
    }
}
