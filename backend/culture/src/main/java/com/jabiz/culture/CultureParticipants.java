package com.jabiz.culture;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.culture.Culture.*;
import static com.jabiz.culture.Workflow.PARAMS;
import static com.jabiz.culture.Workflow.rows;
import static com.jabiz.culture.Workflow.setAll;
import static com.jabiz.culture.Workflow.values;
import static com.jabiz.culture.Workflow.where;

/**
 * Participants and their consent (docs/culture/00-design.md sections 6.1, 6.2 and 6.4): activation checks the
 * consent; withdrawing a consent the participant's public content relies on takes that content offline at once;
 * erasure deletes the participant's rows and files for real. Inputs are keys only, so the operation records hold no
 * personal data.
 */
@Configuration
public class CultureParticipants {

    public static final String ACTIVATE = "CULTURE_PARTICIPANT_ACTIVATE";
    public static final String HIDE = "CULTURE_PARTICIPANT_HIDE";
    public static final String REOPEN = "CULTURE_PARTICIPANT_REOPEN";
    public static final String WITHDRAW = "CULTURE_CONSENT_WITHDRAW";
    public static final String ERASE = "CULTURE_PARTICIPANT_ERASE";

    public record ParticipantInput(@NotBlank String participantId) {}

    public record ParticipantOutput(String participantId, String status) {}

    public record ConsentInput(@NotBlank String consentId) {}

    /** @param unpublishedStories the stories taken offline because they relied on the withdrawn consent */
    public record WithdrawOutput(String consentId, String participantStatus, List<String> unpublishedStories) {}

    /** @param deletedFiles how many files were deleted with the rows */
    public record EraseOutput(String participantId, int deletedRows, int deletedFiles) {}

    static final String PARTICIPANT_ID = "participantId";
    static final String CONSENT_ID = "consentId";
    private static final String PARTICIPANT_ROW = "participant";
    private static final String CONSENT_ROW = "consent";
    private static final String CONSENTS = "consents";
    private static final String CONTRIBUTIONS = "contributions";
    private static final String MEDIA = "media";
    private static final String STORIES = "stories";
    private static final String STORY_CONTRIBUTIONS = "storyContributions";
    private static final String STORY_MEDIA = "storyMedia";
    private static final String STORY_THEMES = "storyThemes";
    private static final String STORY_RESOURCES = "storyResources";
    private static final String OWN_MEDIA = "ownMedia";
    private static final String FILES = "files";
    private static final String OUTPUT = "output";
    private static final String STATUS = "status";

    private static ProcessContext start(ProcessStart start, String participantId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(PARTICIPANT_ID, participantId);
        return ctx;
    }

    private static EntityInstance participant(ProcessContext ctx) {
        return ctx.get(PARTICIPANT_ROW, EntityInstance.class);
    }

    private static ParticipantOutput participantOutput(ProcessContext ctx) {
        EntityInstance participant = participant(ctx);
        return new ParticipantOutput(String.valueOf(participant.id()),
            ctx.contains(STATUS) ? ctx.get(STATUS, String.class) : String.valueOf((Object) participant.get(STATUS)));
    }

    private static void move(ProcessContext ctx, String status) {
        EntityInstance participant = participant(ctx);
        ctx.changes().update(PARTICIPANT, participant.id(), participant.version(), Workflow.mapOf(STATUS, status));
        ctx.put(STATUS, status);
    }

    /** What the participant's own profile needs consent for: the portrait is a photo. */
    private static Set<ConsentRule.Media> profileMedia(EntityInstance participant) {
        return participant.get("portraitFileId") != null ? EnumSet.of(ConsentRule.Media.PHOTO)
            : EnumSet.noneOf(ConsentRule.Media.class);
    }

    private static List<Map<String, Object>> attributes(List<EntityInstance> rows) {
        return rows.stream().map(EntityInstance::attributes).toList();
    }

    /** A draft or hidden participant goes public, provided their consent covers their profile. */
    public static final ProcessDefinition<ParticipantInput, ParticipantOutput, ProcessContext> ACTIVATE_PROCESS =
        ProcessDefinition.define(ACTIVATE, 1, ParticipantInput.class, ParticipantOutput.class, ProcessContext.class,
            pb -> pb
                .description("Makes a participant public after checking their consent.")
                .permissions(PARTICIPANT_MANAGE)
                .actsOn(PARTICIPANT, PARTICIPANT_ID, a -> a.whenField(STATUS, DRAFT, HIDDEN))
                .contextFactory((start, in) -> start(start, in.participantId()))
                .outputMapper(CultureParticipants::participantOutput)
                .step("Load the participant", LoadEntity.by(dataset(PARTICIPANT), PARTICIPANT_ID, PARTICIPANT_ROW))
                .step("Load the switches", LoadParams.of(ProcessContext::opTime, PARAMS, GUARDIAN_REQUIRED))
                .step("Load the consents", QueryEntities.of(dataset(CONSENT),
                    ctx -> where(PARTICIPANT_ID, ctx.get(PARTICIPANT_ID)), CONSENTS))
                .compute("Activate", (metadata, ctx) -> {
                    EntityInstance participant = participant(ctx);
                    if (!Workflow.inState(ctx, participant, STATUS, DRAFT, HIDDEN)) {
                        return;
                    }
                    List<String> missing = ConsentRule.missing(Boolean.TRUE.equals(participant.get("adult")),
                        Workflow.switchOn(ctx, GUARDIAN_REQUIRED), attributes(rows(ctx, CONSENTS)),
                        profileMedia(participant));
                    if (!missing.isEmpty()) {
                        ctx.reject(new Violation("Participant.status", "CONSENT_MISSING", "Consent is missing",
                            Map.of("participant", String.valueOf(participant.id()),
                                "missing", String.join(", ", missing))));
                        return;
                    }
                    move(ctx, ACTIVE);
                }));

    /** Hides an active participant's profile; their published stories are not affected. */
    public static final ProcessDefinition<ParticipantInput, ParticipantOutput, ProcessContext> HIDE_PROCESS =
        ProcessDefinition.define(HIDE, 1, ParticipantInput.class, ParticipantOutput.class, ProcessContext.class,
            pb -> pb
                .description("Hides an active participant's profile.")
                .permissions(PARTICIPANT_MANAGE)
                .actsOn(PARTICIPANT, PARTICIPANT_ID, a -> a.whenField(STATUS, ACTIVE))
                .contextFactory((start, in) -> start(start, in.participantId()))
                .outputMapper(CultureParticipants::participantOutput)
                .step("Load the participant", LoadEntity.by(dataset(PARTICIPANT), PARTICIPANT_ID, PARTICIPANT_ROW))
                .compute("Hide", (metadata, ctx) -> {
                    if (Workflow.inState(ctx, participant(ctx), STATUS, ACTIVE)) {
                        move(ctx, HIDDEN);
                    }
                }));

    /** A participant whose consent was withdrawn becomes a draft again, for example after a new consent. */
    public static final ProcessDefinition<ParticipantInput, ParticipantOutput, ProcessContext> REOPEN_PROCESS =
        ProcessDefinition.define(REOPEN, 1, ParticipantInput.class, ParticipantOutput.class, ProcessContext.class,
            pb -> pb
                .description("Makes a participant whose consent was withdrawn a draft again.")
                .permissions(PARTICIPANT_MANAGE)
                .actsOn(PARTICIPANT, PARTICIPANT_ID, a -> a.whenField(STATUS, WITHDRAWN))
                .contextFactory((start, in) -> start(start, in.participantId()))
                .outputMapper(CultureParticipants::participantOutput)
                .step("Load the participant", LoadEntity.by(dataset(PARTICIPANT), PARTICIPANT_ID, PARTICIPANT_ROW))
                .compute("Reopen", (metadata, ctx) -> {
                    if (Workflow.inState(ctx, participant(ctx), STATUS, WITHDRAWN)) {
                        move(ctx, DRAFT);
                    }
                }));

    /**
     * Withdraws a consent. When the participant's remaining consents no longer cover what of theirs is public (their
     * profile, and their perspectives with their media in published stories), the participant is withdrawn and every
     * published story with a perspective of theirs goes offline, in the same transaction.
     */
    public static final ProcessDefinition<ConsentInput, WithdrawOutput, ProcessContext> WITHDRAW_PROCESS =
        ProcessDefinition.define(WITHDRAW, 1, ConsentInput.class, WithdrawOutput.class, ProcessContext.class, pb -> pb
            .description("Withdraws a consent and takes offline what no longer has consent.")
            .permissions(CONSENT_WITHDRAW)
            .actsOn(CONSENT, CONSENT_ID)
            .contextFactory((start, in) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(CONSENT_ID, in.consentId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, WithdrawOutput.class))
            .step("Load the consent", LoadEntity.by(dataset(CONSENT), CONSENT_ID, CONSENT_ROW))
            .compute("Find the participant", (metadata, ctx) ->
                ctx.put(PARTICIPANT_ID, ctx.get(CONSENT_ROW, EntityInstance.class).get(PARTICIPANT_ID)))
            .step("Load the participant", LoadEntity.by(dataset(PARTICIPANT), PARTICIPANT_ID, PARTICIPANT_ROW))
            .step("Load the switches", LoadParams.of(ProcessContext::opTime, PARAMS, GUARDIAN_REQUIRED))
            .step("Load the consents", QueryEntities.of(dataset(CONSENT),
                ctx -> where(PARTICIPANT_ID, ctx.get(PARTICIPANT_ID)), CONSENTS))
            .step("Load the perspectives", QueryEntities.of(dataset(CONTRIBUTION),
                ctx -> where(PARTICIPANT_ID, ctx.get(PARTICIPANT_ID)), CONTRIBUTIONS))
            .step("Load their media", QueryEntities.of(dataset(MEDIA_ITEM),
                ctx -> where("contributionId", values(rows(ctx, CONTRIBUTIONS), EntityInstance::id)), MEDIA))
            .step("Load their stories", QueryEntities.of(dataset(STORY),
                ctx -> where("storyId", values(rows(ctx, CONTRIBUTIONS), c -> c.get("storyId"))), STORIES))
            .step("Load the perspectives of the stories", QueryEntities.of(dataset(CONTRIBUTION),
                ctx -> where("storyId", publishedIds(ctx)), STORY_CONTRIBUTIONS))
            .step("Load the media of the stories", QueryEntities.of(dataset(MEDIA_ITEM),
                ctx -> where("storyId", publishedIds(ctx)), STORY_MEDIA))
            .step("Load the themes of the stories", QueryEntities.of(dataset(STORY_THEME),
                ctx -> where("storyId", publishedIds(ctx)), STORY_THEMES))
            .compute("Withdraw", (metadata, ctx) -> withdraw(ctx)));

    private static Set<Object> publishedIds(ProcessContext ctx) {
        return values(rows(ctx, STORIES).stream().filter(s -> PUBLISHED.equals(s.get(STATUS))).toList(),
            EntityInstance::id);
    }

    private static void withdraw(ProcessContext ctx) {
        EntityInstance consent = ctx.get(CONSENT_ROW, EntityInstance.class);
        if (consent.get("withdrawnTime") != null) {
            ctx.reject(new Violation("withdrawnTime", "CONSENT_ALREADY_WITHDRAWN", "The consent is already withdrawn"));
            return;
        }
        ctx.changes().update(CONSENT, consent.id(), consent.version(), Workflow.mapOf("withdrawnTime", ctx.opTime()));

        EntityInstance participant = participant(ctx);
        List<Map<String, Object>> remaining = new ArrayList<>();
        for (EntityInstance other : rows(ctx, CONSENTS)) {
            if (!Objects.equals(other.id(), consent.id())) {
                remaining.add(other.attributes());
            }
        }
        // What of the participant's is public now, and so needs consent.
        Set<Object> published = publishedIds(ctx);
        Set<ConsentRule.Media> needs = profileMedia(participant);
        Set<Object> publicContributions = new LinkedHashSet<>();
        for (EntityInstance c : rows(ctx, CONTRIBUTIONS)) {
            if (published.contains(c.get("storyId"))) {
                publicContributions.add(c.id());
                if (c.get("videoId") != null) {
                    needs.add(ConsentRule.Media.VIDEO);
                }
                if (c.get("audioFileId") != null) {
                    needs.add(ConsentRule.Media.VOICE);
                }
            }
        }
        for (EntityInstance m : rows(ctx, MEDIA)) {
            if (publicContributions.contains(m.get("contributionId"))) {
                needs.add(PHOTO.equals(m.get("kind")) ? ConsentRule.Media.PHOTO : ConsentRule.Media.VOICE);
            }
        }
        List<String> missing = ConsentRule.missing(Boolean.TRUE.equals(participant.get("adult")),
            Workflow.switchOn(ctx, GUARDIAN_REQUIRED), remaining, needs);

        List<String> unpublished = new ArrayList<>();
        String status = String.valueOf((Object) participant.get(STATUS));
        if (!missing.isEmpty()) {
            if (!WITHDRAWN.equals(status)) {
                move(ctx, WITHDRAWN);
                status = WITHDRAWN;
            }
            for (EntityInstance story : rows(ctx, STORIES)) {
                if (published.contains(story.id())) {
                    ctx.changes().update(STORY, story.id(), story.version(), Workflow.mapOf(STATUS, UNPUBLISHED));
                    unpublished.add(String.valueOf(story.id()));
                }
            }
            setAll(ctx, rows(ctx, STORY_CONTRIBUTIONS), "visibility", PRIVATE);
            setAll(ctx, rows(ctx, STORY_MEDIA), "visibility", PRIVATE);
            setAll(ctx, rows(ctx, STORY_THEMES), "visibility", PRIVATE);
        }
        ctx.put(OUTPUT, new WithdrawOutput(String.valueOf(consent.id()), status, List.copyOf(unpublished)));
    }

    /**
     * Erases a withdrawn participant. Deleted: their perspectives with the media of those; the media they added
     * themselves anywhere; the stories they drafted in which nobody else has a perspective, with everything of those
     * stories; their consent records and the participant row; then every file these rows referred to. Stories with
     * other participants' perspectives stay (without an owner): a story left without perspectives fails its next
     * publish check.
     */
    public static final ProcessDefinition<ParticipantInput, EraseOutput, ProcessContext> ERASE_PROCESS =
        ProcessDefinition.define(ERASE, 1, ParticipantInput.class, EraseOutput.class, ProcessContext.class, pb -> pb
            .description("Deletes a withdrawn participant with their perspectives, media, stories, consents and files.")
            .permissions(Culture.ERASE)
            .actsOn(PARTICIPANT, PARTICIPANT_ID, a -> a.whenField(STATUS, WITHDRAWN))
            .contextFactory((start, in) -> start(start, in.participantId()))
            .outputMapper(ctx -> ctx.get(OUTPUT, EraseOutput.class))
            .step("Load the participant", LoadEntity.by(dataset(PARTICIPANT), PARTICIPANT_ID, PARTICIPANT_ROW))
            .step("Load the consents", QueryEntities.of(dataset(CONSENT),
                ctx -> where(PARTICIPANT_ID, ctx.get(PARTICIPANT_ID)), CONSENTS))
            .step("Load the perspectives", QueryEntities.of(dataset(CONTRIBUTION),
                ctx -> where(PARTICIPANT_ID, ctx.get(PARTICIPANT_ID)), CONTRIBUTIONS))
            .step("Load their media", QueryEntities.of(dataset(MEDIA_ITEM),
                ctx -> where("contributionId", values(rows(ctx, CONTRIBUTIONS), EntityInstance::id)), MEDIA))
            .step("Load the media they added", QueryEntities.of(dataset(MEDIA_ITEM),
                ctx -> where("ownerActorId", account(ctx)), OWN_MEDIA))
            .step("Load the stories they drafted", QueryEntities.of(dataset(STORY),
                ctx -> where("ownerActorId", account(ctx)), STORIES))
            .step("Load the perspectives of those stories", QueryEntities.of(dataset(CONTRIBUTION),
                ctx -> where("storyId", values(rows(ctx, STORIES), EntityInstance::id)), STORY_CONTRIBUTIONS))
            .step("Load the media of those stories", QueryEntities.of(dataset(MEDIA_ITEM),
                ctx -> where("storyId", values(rows(ctx, STORIES), EntityInstance::id)), STORY_MEDIA))
            .step("Load the themes of those stories", QueryEntities.of(dataset(STORY_THEME),
                ctx -> where("storyId", values(rows(ctx, STORIES), EntityInstance::id)), STORY_THEMES))
            .step("Load the resource links of those stories", QueryEntities.of(dataset(RESOURCE_STORY),
                ctx -> where("storyId", values(rows(ctx, STORIES), EntityInstance::id)), STORY_RESOURCES))
            .compute("Delete the rows", (metadata, ctx) -> erase(ctx))
            // FILE_DELETE refuses files that current data still refers to: the deletions are saved first.
            .step("Save", SaveChanges.now())
            .step("Delete the files", CallProcess.forEach(FileProcesses.DELETE, 1,
                ctx -> files(ctx).stream().map(FileProcesses.DeleteInput::new).toList(), null)));

    /** The participant's login account, as a set of at most one value (none: nothing of theirs by account). */
    private static Set<Object> account(ProcessContext ctx) {
        Object account = participant(ctx).get("accountActorId");
        return account == null ? Set.of() : Set.of(account);
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> files(ProcessContext ctx) {
        return ctx.contains(FILES) ? (List<UUID>) ctx.get(FILES) : List.of();
    }

    private static void erase(ProcessContext ctx) {
        EntityInstance participant = participant(ctx);
        if (!Workflow.inState(ctx, participant, STATUS, WITHDRAWN)) {
            return;
        }
        Set<UUID> files = new LinkedHashSet<>();
        addFile(files, participant.get("portraitFileId"));
        Set<Object> contributions = values(rows(ctx, CONTRIBUTIONS), EntityInstance::id);
        // Their stories that nobody else contributed to go as a whole; the others lose their owner only.
        Set<Object> ownStories = new LinkedHashSet<>();
        for (EntityInstance story : rows(ctx, STORIES)) {
            boolean shared = rows(ctx, STORY_CONTRIBUTIONS).stream()
                .anyMatch(c -> Objects.equals(c.get("storyId"), story.id()) && !contributions.contains(c.id()));
            if (shared) {
                ctx.changes().update(STORY, story.id(), story.version(), Workflow.mapOf("ownerActorId", null));
            } else {
                ownStories.add(story.id());
            }
        }
        Deletions deletions = new Deletions(ctx, files);
        // Children first: the database keeps its foreign keys.
        for (String key : List.of(MEDIA, OWN_MEDIA, STORY_MEDIA)) {
            for (EntityInstance m : rows(ctx, key)) {
                if (!STORY_MEDIA.equals(key) || ownStories.contains(m.get("storyId"))) {
                    deletions.delete(m, "imageFileId", "audioFileId");
                }
            }
        }
        rows(ctx, CONTRIBUTIONS).forEach(c -> deletions.delete(c, "audioFileId"));
        for (String key : List.of(STORY_CONTRIBUTIONS, STORY_THEMES, STORY_RESOURCES)) {
            rows(ctx, key).stream().filter(row -> ownStories.contains(row.get("storyId")))
                .forEach(row -> deletions.delete(row, "audioFileId"));
        }
        rows(ctx, STORIES).stream().filter(story -> ownStories.contains(story.id()))
            .forEach(story -> deletions.delete(story, "thumbnailFileId"));
        rows(ctx, CONSENTS).forEach(consent -> deletions.delete(consent, "documentFileId"));
        deletions.delete(participant);
        ctx.put(FILES, List.copyOf(files));
        ctx.put(OUTPUT, new EraseOutput(String.valueOf(participant.id()), deletions.count(), files.size()));
    }

    /** Deletes each row once (the same row can be loaded by two steps) and collects the files it refers to. */
    private static final class Deletions {
        private final ProcessContext ctx;
        private final Set<UUID> files;
        private final Set<String> done = new LinkedHashSet<>();

        Deletions(ProcessContext ctx, Set<UUID> files) {
            this.ctx = ctx;
            this.files = files;
        }

        void delete(EntityInstance row, String... fileFields) {
            if (!done.add(row.entityType() + " " + row.id())) {
                return;
            }
            for (String field : fileFields) {
                addFile(files, row.attributes().get(field));
            }
            ctx.changes().delete(row.entityType(), row.id(), row.version());
        }

        int count() {
            return done.size();
        }
    }

    private static void addFile(Set<UUID> files, Object id) {
        if (id instanceof UUID uuid) {
            files.add(uuid);
        } else if (id != null) {
            files.add(UUID.fromString(id.toString()));
        }
    }

    @Bean
    ProcessDefinition<ParticipantInput, ParticipantOutput, ProcessContext> cultureParticipantActivateProcess() {
        return ACTIVATE_PROCESS;
    }

    @Bean
    ProcessDefinition<ParticipantInput, ParticipantOutput, ProcessContext> cultureParticipantHideProcess() {
        return HIDE_PROCESS;
    }

    @Bean
    ProcessDefinition<ParticipantInput, ParticipantOutput, ProcessContext> cultureParticipantReopenProcess() {
        return REOPEN_PROCESS;
    }

    @Bean
    ProcessDefinition<ConsentInput, WithdrawOutput, ProcessContext> cultureConsentWithdrawProcess() {
        return WITHDRAW_PROCESS;
    }

    @Bean
    ProcessDefinition<ParticipantInput, EraseOutput, ProcessContext> cultureParticipantEraseProcess() {
        return ERASE_PROCESS;
    }
}
