package com.jabiz.quizbuks.settings;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.quizbuks.topic.TopicCodes;
import com.jabiz.quizbuks.topic.TopicRules;
import com.jabiz.quizbuks.topic.TopicSteps;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Saving one's own settings ({@value #SAVE}, Q57, Q59a): takers and sponsors alike ({@code qb.account.read}, which
 * both hold), for themselves only. No verified address is needed: nothing here reaches anyone else.
 */
public final class SettingsProcesses {

    public static final String SAVE = "QB_SETTINGS_SAVE";

    /**
     * The settings to change; a setting left out (null) stays as it is.
     *
     * @param soundOn          the sound effects (Q57)
     * @param contentLanguages the languages of the quizzes shown (Q59a), each once; empty: the interface language
     *                         only, whichever it is
     * @param publicHandle     Q77: the public certification page's name ({@link PublicHandles}); blank clears it
     * @param publicPage       Q77: whether that page is shown; on needs a name
     * @param likedTopics      F-S9: the topics liked, codes of the word list, all of them (empty: none); a word liked
     *                         anew must be offered, one liked before stays though deactivated since
     * @param strictTopics     F-S9: strict mode (only publications with a liked topic on the home, the shelf of shorts
     *                         and push); on needs a liked topic, off always works
     */
    public record SaveInput(Boolean soundOn,
        @Size(max = 3) List<@Pattern(regexp = "ja|zh|en") String> contentLanguages,
        @Size(max = 60) String publicHandle, Boolean publicPage,
        @Size(max = TopicRules.MAX_LIKED + 1) List<@NotNull @Size(max = TopicRules.CODE_LENGTH) String> likedTopics,
        Boolean strictTopics) {

        public SaveInput(Boolean soundOn, List<String> contentLanguages, String publicHandle, Boolean publicPage) {
            this(soundOn, contentLanguages, publicHandle, publicPage, null, null);
        }

        public SaveInput(Boolean soundOn) {
            this(soundOn, null);
        }

        public SaveInput(Boolean soundOn, List<String> contentLanguages) {
            this(soundOn, contentLanguages, null, null);
        }
    }

    /** The settings after the save; no content languages: the interface language. */
    public record SettingsOutput(boolean soundOn, List<String> contentLanguages, String publicHandle,
        boolean publicPage, List<String> likedTopics, boolean strictTopics) {
    }

    private static final String INPUT = "input";
    private static final String CURRENT = "current";
    private static final String HOLDER = "handleHolder";
    private static final String OUTPUT = "output";

    public static ProcessDefinition<SaveInput, SettingsOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, SettingsOutput.class, ProcessContext.class, pb -> {
            pb.description("Saves one's own settings of the app or the sponsor console (the sound effects, the "
                    + "languages of the quizzes shown).")
                .permissions(QbPermissions.ACCOUNT_READ)
                .contextFactory(SettingsProcesses::start)
                .outputMapper(ctx -> ctx.get(OUTPUT, SettingsOutput.class));
            // F-ban-coverage: the settings hold the unique public name and the public certification page anyone can
            // read, so a banned caller's still valid token is refused before anything else.
            CallerChecks.refuseBanned(pb);
            // F-S9: the words liked, to check those liked anew (none given: no statement).
            TopicSteps.loadWords(pb, ctx -> {
                List<String> liked = ctx.get(INPUT, SaveInput.class).likedTopics();
                return liked == null ? List.of() : liked.stream().map(String::strip).toList();
            });
            pb.step("Lock the settings", HoldLock.<ProcessContext>exclusive(
                ctx -> QbSettings.lockName(ctx.request().actorId())));
            pb.step("Load the settings", QueryEntities.<ProcessContext>of(QbSettings.DEFAULT_DATASET,
                ctx -> mine(ctx.request().actorId()), CURRENT));
            // Q77: whoever has the public name asked for (the unique index stands behind it).
            pb.step("Load who has the public name", QueryEntities.<ProcessContext>of(QbSettings.DEFAULT_DATASET,
                ctx -> {
                    String handle = PublicHandles.normalized(ctx.get(INPUT, SaveInput.class).publicHandle());
                    List<Object> handles = handle == null ? List.of() : List.of(handle);
                    return EntityQuery.builder().where(new QueryPredicate.In("publicHandle", handles)).limit(1)
                        .build();
                }, HOLDER));
            pb.compute("Save", (metadata, ctx) -> write(ctx, ctx.get(INPUT, SaveInput.class)));
        });
    }

    private static void write(ProcessContext ctx, SaveInput input) {
        EntityInstance current = first(ctx);
        boolean soundOff = input.soundOn() != null ? !input.soundOn()
            : current != null && Boolean.TRUE.equals(current.get("soundOff"));
        String languages = input.contentLanguages() != null ? languages(input.contentLanguages())
            : current == null ? null : (String) current.get("contentLanguages");
        String handle = input.publicHandle() != null ? PublicHandles.normalized(input.publicHandle())
            : current == null ? null : (String) current.get("publicHandle");
        boolean publicPage = input.publicPage() != null ? input.publicPage()
            : current != null && Boolean.TRUE.equals(current.get("publicPage"));
        List<String> likedBefore = TopicRules.codes(current == null ? null : current.get("likedTopics"));
        List<String> liked = input.likedTopics() != null
            ? TopicRules.codes(String.join(",", input.likedTopics().stream().map(String::strip).toList()))
            : likedBefore;
        boolean strict = input.strictTopics() != null ? input.strictTopics()
            : current != null && Boolean.TRUE.equals(current.get("strictTopics"));
        if (input.likedTopics() != null) {
            TopicRules.choiceProblems("likedTopics", liked, likedBefore, TopicSteps.words(ctx)).forEach(ctx::reject);
            if (liked.size() > TopicRules.MAX_LIKED) {
                ctx.reject(new Violation("likedTopics", TopicCodes.TOO_MANY_LIKED, "More than " + TopicRules.MAX_LIKED
                    + " liked topics", Map.of("max", TopicRules.MAX_LIKED)));
            }
        }
        // Strict mode without a liked topic would show nothing: turning it on, or leaving none while on, is refused.
        if (strict && liked.isEmpty() && (input.strictTopics() != null || input.likedTopics() != null)) {
            ctx.reject(new Violation("strictTopics", TopicCodes.STRICT_NEEDS_TOPICS, "Strict mode needs a liked topic",
                Map.of()));
        }
        String likedColumn = TopicRules.joinCodes(liked);
        if (input.publicHandle() != null && handle != null) {
            String problem = PublicHandles.problem(handle);
            if (problem != null) {
                ctx.reject(new Violation("publicHandle", problem, "Not a public name to use: " + handle));
            } else if (holderOtherThan(ctx, ctx.request().actorId())) {
                ctx.reject(new Violation("publicHandle", PublicHandles.TAKEN, "The public name is taken"));
            }
        }
        if (publicPage && handle == null) {
            ctx.reject(new Violation("publicPage", PublicHandles.NO_HANDLE, "A public page needs a public name"));
        }
        if (ctx.hasViolations()) {
            return;
        }
        if (current == null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", ctx.request().actorId());
            row.put("soundOff", soundOff);
            row.put("contentLanguages", languages);
            row.put("publicHandle", handle);
            row.put("publicPage", publicPage);
            row.put("likedTopics", likedColumn);
            row.put("strictTopics", strict);
            ctx.changes().insert(QbSettings.SETTINGS, row);
        } else if (!Boolean.valueOf(soundOff).equals(current.get("soundOff"))
            || !Objects.equals(languages, current.get("contentLanguages"))
            || !Objects.equals(handle, current.get("publicHandle"))
            || !Boolean.valueOf(publicPage).equals(current.get("publicPage"))
            || !Objects.equals(likedColumn, current.get("likedTopics"))
            || !Boolean.valueOf(strict).equals(current.get("strictTopics"))) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("soundOff", soundOff);
            values.put("contentLanguages", languages);
            values.put("publicHandle", handle);
            values.put("publicPage", publicPage);
            values.put("likedTopics", likedColumn);
            values.put("strictTopics", strict);
            ctx.changes().update(QbSettings.SETTINGS, current.id(), current.version(), values);
        }
        ctx.put(OUTPUT, new SettingsOutput(!soundOff, languages == null ? List.of()
            : Arrays.asList(languages.split(",")), handle, publicPage, liked, strict));
    }

    /** The languages as stored: in the platform's order, each once; none as null. */
    static String languages(List<String> chosen) {
        List<String> ordered = QbContent.LANGUAGES.stream().filter(chosen::contains).toList();
        return ordered.isEmpty() ? null : String.join(",", ordered);
    }

    private static EntityQuery mine(Object userId) {
        List<Object> ids = new ArrayList<>();
        if (userId != null) {
            ids.add(userId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("userId", ids)).limit(1).build();
    }

    @SuppressWarnings("unchecked")
    private static boolean holderOtherThan(ProcessContext ctx, Object userId) {
        List<EntityInstance> rows = ctx.contains(HOLDER) ? (List<EntityInstance>) ctx.get(HOLDER) : List.of();
        return rows.stream().anyMatch(row -> !Objects.equals(String.valueOf(row.id()), String.valueOf(userId)));
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance first(ProcessContext ctx) {
        List<EntityInstance> rows = ctx.contains(CURRENT) ? (List<EntityInstance>) ctx.get(CURRENT) : null;
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private static ProcessContext start(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private SettingsProcesses() {
    }
}
