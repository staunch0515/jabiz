package com.jabiz.culture;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.jabiz.culture.Culture.*;
import static com.jabiz.culture.Workflow.PARAMS;
import static com.jabiz.culture.Workflow.rows;
import static com.jabiz.culture.Workflow.setAll;
import static com.jabiz.culture.Workflow.where;

/**
 * The workflow of a story (docs/culture/00-design.md sections 6.1 and 6.3): a correspondent submits their draft, an
 * editor returns it or publishes it (after the publish check), unpublishes it and reopens it. The story's status and
 * the flags of its parts ({@code editable}, {@code visibility}) change only here.
 */
@Configuration
public class CultureStories {

    public static final String SUBMIT = "CULTURE_STORY_SUBMIT";
    public static final String RETURN = "CULTURE_STORY_RETURN";
    public static final String PUBLISH = "CULTURE_STORY_PUBLISH";
    public static final String UNPUBLISH = "CULTURE_STORY_UNPUBLISH";
    public static final String REOPEN = "CULTURE_STORY_REOPEN";

    public record StoryInput(@NotBlank String storyId) {}

    /** @param note what the correspondent should change; free text, so masked in the operation record */
    public record ReturnInput(@NotBlank String storyId, @Sensitive @Size(max = 2000) String note) {
        @Override
        public String toString() {
            return "ReturnInput[storyId=" + storyId + ", note=***]";
        }
    }

    public record StoryOutput(String storyId, String status) {}

    static final String STORY_ID = "storyId";
    static final String STORY_ROW = "story";
    static final String THEMES = "themes";
    static final String CONTRIBUTIONS = "contributions";
    static final String MEDIA = "media";
    static final String PARTICIPANTS = "participants";
    static final String CONSENTS = "consents";
    private static final String STATUS = "status";
    private static final String NOTE = "note";
    private static final String PUBLISHED_BY_CHILD = "publishedByChild";

    private static ProcessContext start(ProcessStart start, String storyId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(STORY_ID, storyId);
        return ctx;
    }

    private static EntityInstance story(ProcessContext ctx) {
        return ctx.get(STORY_ROW, EntityInstance.class);
    }

    private static StoryOutput output(ProcessContext ctx) {
        if (ctx.contains(PUBLISHED_BY_CHILD)) {
            return ctx.get(PUBLISHED_BY_CHILD, StoryOutput.class);
        }
        EntityInstance story = story(ctx);
        return new StoryOutput(String.valueOf(story.id()), ctx.contains(STATUS)
            ? ctx.get(STATUS, String.class) : String.valueOf((Object) story.get(STATUS)));
    }

    /** Loads the story's perspectives and media, the parts whose flags follow the story. */
    private static <I> Consumer<ProcessDefinitionBuilder<I, StoryOutput, ProcessContext>> parts() {
        return pb -> pb
            .step("Load the perspectives", QueryEntities.of(dataset(CONTRIBUTION),
                ctx -> where(STORY_ID, ctx.get(STORY_ID)), CONTRIBUTIONS))
            .step("Load the media", QueryEntities.of(dataset(MEDIA_ITEM),
                ctx -> where(STORY_ID, ctx.get(STORY_ID)), MEDIA));
    }

    /** Moves the story to {@code status}; the lifecycle still judges the transition. */
    private static void move(ProcessContext ctx, String status, Map<String, Object> more) {
        EntityInstance story = story(ctx);
        Map<String, Object> changes = new HashMap<>(more);
        changes.put(STATUS, status);
        ctx.changes().update(STORY, story.id(), story.version(), changes);
        ctx.put(STATUS, status);
    }

    private static void setParts(ProcessContext ctx, String field, Object value) {
        setParts(ctx, Workflow.mapOf(field, value));
    }

    private static void setParts(ProcessContext ctx, Map<String, Object> values) {
        setAll(ctx, rows(ctx, CONTRIBUTIONS), values);
        setAll(ctx, rows(ctx, MEDIA), values);
    }

    /**
     * A correspondent submits their own draft: it goes to review and its parts can no longer be edited through their
     * datasets. When the switch {@value Culture#REVIEW_REQUIRED} is off, it is published right away, the publish
     * check included (a failed check refuses the submission as a whole).
     */
    public static final ProcessDefinition<StoryInput, StoryOutput, ProcessContext> SUBMIT_PROCESS =
        ProcessDefinition.define(SUBMIT, 1, StoryInput.class, StoryOutput.class, ProcessContext.class, pb -> {
            pb.description("Sends a correspondent's draft story to review, or publishes it when no review is required.")
                .permissions(STORY_SUBMIT)
                .actsOn(STORY, STORY_ID, a -> a.whenField(STATUS, DRAFT))
                .contextFactory((start, in) -> start(start, in.storyId()))
                .outputMapper(CultureStories::output)
                // Through the correspondent's own dataset: somebody else's story is not found.
                .step("Load the story", LoadEntity.by(ownView(STORY), STORY_ID, STORY_ROW))
                .step("Load the switches", LoadParams.of(ProcessContext::opTime, PARAMS, REVIEW_REQUIRED));
            CultureStories.<StoryInput>parts().accept(pb);
            pb.compute("Submit", (metadata, ctx) -> {
                    if (Workflow.inState(ctx, story(ctx), STATUS, DRAFT)) {
                        move(ctx, IN_REVIEW, Map.of());
                        setParts(ctx, "editable", false);
                    }
                })
                // The publication reads the story as submitted.
                .step("Save", SaveChanges.now())
                .step("Publish without review", CallProcess.when(
                    ctx -> !Workflow.switchOn(ctx, REVIEW_REQUIRED), PUBLISH, 1,
                    ctx -> new StoryInput(ctx.get(STORY_ID, String.class)), PUBLISHED_BY_CHILD));
        });

    /** An editor returns a story in review to its correspondent, with a note; its parts can be edited again. */
    public static final ProcessDefinition<ReturnInput, StoryOutput, ProcessContext> RETURN_PROCESS =
        ProcessDefinition.define(RETURN, 1, ReturnInput.class, StoryOutput.class, ProcessContext.class, pb -> {
            pb.description("Returns a story in review to its correspondent with a note.")
                .permissions(STORY_REVIEW)
                .actsOn(STORY, STORY_ID, a -> a.whenField(STATUS, IN_REVIEW))
                .contextFactory((start, in) -> {
                    ProcessContext ctx = start(start, in.storyId());
                    ctx.put(NOTE, in.note() == null || in.note().isBlank() ? null : in.note().strip());
                    return ctx;
                })
                .outputMapper(CultureStories::output)
                .step("Load the story", LoadEntity.by(dataset(STORY), STORY_ID, STORY_ROW));
            CultureStories.<ReturnInput>parts().accept(pb);
            pb.compute("Return", (metadata, ctx) -> {
                if (Workflow.inState(ctx, story(ctx), STATUS, IN_REVIEW)) {
                    Map<String, Object> note = new HashMap<>();
                    note.put("reviewNote", ctx.contains(NOTE) ? ctx.get(NOTE) : null);
                    move(ctx, DRAFT, note);
                    setParts(ctx, "editable", true);
                }
            });
        });

    /**
     * Publishes a story after the publish check: the story and all its parts go public. Running it again on a
     * published story checks it again and publishes the parts added since.
     */
    public static final ProcessDefinition<StoryInput, StoryOutput, ProcessContext> PUBLISH_PROCESS =
        ProcessDefinition.define(PUBLISH, 1, StoryInput.class, StoryOutput.class, ProcessContext.class, pb -> {
            pb.description("Checks a story and publishes it with all its perspectives, media and themes.")
                .permissions(STORY_PUBLISH)
                .actsOn(STORY, STORY_ID, a -> a.whenField(STATUS, DRAFT, IN_REVIEW, PUBLISHED, UNPUBLISHED))
                .contextFactory((start, in) -> start(start, in.storyId()))
                .outputMapper(CultureStories::output)
                .step("Load the story", LoadEntity.by(dataset(STORY), STORY_ID, STORY_ROW))
                .step("Load the switches", LoadParams.of(ProcessContext::opTime, PARAMS, GUARDIAN_REQUIRED))
                .step("Load the themes", QueryEntities.of(dataset(STORY_THEME),
                    ctx -> where(STORY_ID, ctx.get(STORY_ID)), THEMES));
            CultureStories.<StoryInput>parts().accept(pb);
            pb.step("Load the participants", QueryEntities.of(dataset(PARTICIPANT),
                    ctx -> where("participantId", Workflow.values(rows(ctx, CONTRIBUTIONS), c -> c.get("participantId"))),
                    PARTICIPANTS))
                .step("Load their consents", QueryEntities.of(dataset(CONSENT),
                    ctx -> where("participantId", Workflow.values(rows(ctx, PARTICIPANTS), EntityInstance::id)),
                    CONSENTS))
                .compute("Check and publish", (metadata, ctx) -> publish(ctx));
        });

    private static void publish(ProcessContext ctx) {
        EntityInstance story = story(ctx);
        if (!Workflow.inState(ctx, story, STATUS, DRAFT, IN_REVIEW, PUBLISHED, UNPUBLISHED)) {
            return;
        }
        Map<Object, EntityInstance> participants = new HashMap<>();
        rows(ctx, PARTICIPANTS).forEach(p -> participants.put(p.id(), p));
        List<com.jabiz.entity.Violation> violations = PublishCheck.check(new PublishCheck.Input(story,
            rows(ctx, THEMES), rows(ctx, CONTRIBUTIONS), rows(ctx, MEDIA), participants, rows(ctx, CONSENTS),
            Workflow.switchOn(ctx, GUARDIAN_REQUIRED)));
        if (!violations.isEmpty()) {
            violations.forEach(ctx::reject);
            return;
        }
        Map<String, Object> changes = new HashMap<>();
        if (story.get("publishedTime") == null) {
            changes.put("publishedTime", ctx.opTime());
        }
        if (PUBLISHED.equals(story.get(STATUS))) {
            if (!changes.isEmpty()) {
                ctx.changes().update(STORY, story.id(), story.version(), changes);
            }
            ctx.put(STATUS, PUBLISHED);
        } else {
            move(ctx, PUBLISHED, changes);
        }
        setParts(ctx, Map.of("visibility", PUBLIC, "editable", false));
        setAll(ctx, rows(ctx, THEMES), "visibility", PUBLIC);
    }

    /** Takes a published story offline with all its parts. */
    public static final ProcessDefinition<StoryInput, StoryOutput, ProcessContext> UNPUBLISH_PROCESS =
        ProcessDefinition.define(UNPUBLISH, 1, StoryInput.class, StoryOutput.class, ProcessContext.class, pb -> {
            pb.description("Takes a published story offline with all its perspectives, media and themes.")
                .permissions(STORY_UNPUBLISH)
                .actsOn(STORY, STORY_ID, a -> a.whenField(STATUS, PUBLISHED))
                .contextFactory((start, in) -> start(start, in.storyId()))
                .outputMapper(CultureStories::output)
                .step("Load the story", LoadEntity.by(dataset(STORY), STORY_ID, STORY_ROW))
                .step("Load the themes", QueryEntities.of(dataset(STORY_THEME),
                    ctx -> where(STORY_ID, ctx.get(STORY_ID)), THEMES));
            CultureStories.<StoryInput>parts().accept(pb);
            pb.compute("Unpublish", (metadata, ctx) -> {
                if (Workflow.inState(ctx, story(ctx), STATUS, PUBLISHED)) {
                    move(ctx, UNPUBLISHED, Map.of());
                    setParts(ctx, "visibility", PRIVATE);
                    setAll(ctx, rows(ctx, THEMES), "visibility", PRIVATE);
                }
            });
        });

    /** Makes an unpublished story a draft again, editable by its correspondents. */
    public static final ProcessDefinition<StoryInput, StoryOutput, ProcessContext> REOPEN_PROCESS =
        ProcessDefinition.define(REOPEN, 1, StoryInput.class, StoryOutput.class, ProcessContext.class, pb -> {
            pb.description("Makes an unpublished story a draft again.")
                .permissions(STORY_REVIEW)
                .actsOn(STORY, STORY_ID, a -> a.whenField(STATUS, UNPUBLISHED))
                .contextFactory((start, in) -> start(start, in.storyId()))
                .outputMapper(CultureStories::output)
                .step("Load the story", LoadEntity.by(dataset(STORY), STORY_ID, STORY_ROW));
            CultureStories.<StoryInput>parts().accept(pb);
            pb.compute("Reopen", (metadata, ctx) -> {
                if (Workflow.inState(ctx, story(ctx), STATUS, UNPUBLISHED)) {
                    move(ctx, DRAFT, Map.of());
                    setParts(ctx, "editable", true);
                }
            });
        });

    @Bean
    ProcessDefinition<StoryInput, StoryOutput, ProcessContext> cultureStorySubmitProcess() {
        return SUBMIT_PROCESS;
    }

    @Bean
    ProcessDefinition<ReturnInput, StoryOutput, ProcessContext> cultureStoryReturnProcess() {
        return RETURN_PROCESS;
    }

    @Bean
    ProcessDefinition<StoryInput, StoryOutput, ProcessContext> cultureStoryPublishProcess() {
        return PUBLISH_PROCESS;
    }

    @Bean
    ProcessDefinition<StoryInput, StoryOutput, ProcessContext> cultureStoryUnpublishProcess() {
        return UNPUBLISH_PROCESS;
    }

    @Bean
    ProcessDefinition<StoryInput, StoryOutput, ProcessContext> cultureStoryReopenProcess() {
        return REOPEN_PROCESS;
    }
}
