package com.jabiz.quizbuks.play;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.content.QuizSnapshots;
import com.jabiz.quizbuks.country.QbCountries;
import com.jabiz.quizbuks.country.Regions;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What the answering processes share: their context keys (prefixed, apart from the publication's steps they reuse),
 * the steps that read a taker's regions, a card and a version, and value conversions.
 */
final class PlaySteps {

    static final String INPUT = "play.input";
    static final String OUTPUT = "play.output";
    static final String PROFILE = "play.profile";
    static final String COUNTRY = "play.country";
    static final String VERSION = "play.version";

    private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Z]{2}");

    /**
     * Loads the profile of {@code userId} and its country: the regions the user sees publications of. By the
     * immutable user and code, so each query reads only those instances' versions.
     */
    static <I, O> void loadRegions(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> userId) {
        pb.step("Load the taker's profile", QueryEntities.<ProcessContext>of(
                QbAccounts.defaultDataset(QbAccounts.PROFILE), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("userId", ids(userId.apply(ctx)))).limit(1).build(), PROFILE))
            .step("Load the taker's country", QueryEntities.<ProcessContext>of(QbCountries.DATASET, ctx -> {
                EntityInstance profile = first(ctx, PROFILE);
                Object code = profile == null ? null : profile.get("countryCode");
                return EntityQuery.builder().where(new QueryPredicate.In("code",
                    code != null && COUNTRY_CODE.matcher(code.toString()).matches() ? ids(code) : ids(null)))
                    .limit(1).build();
            }, COUNTRY));
    }

    /**
     * The regions of the taker's country; {@code GLOBAL} alone without a profile or a country (a publication aimed
     * at everyone reaches them).
     */
    static String userRegions(ProcessContext ctx) {
        EntityInstance country = first(ctx, COUNTRY);
        Object regions = country == null ? null : country.get("regions");
        return regions == null ? Regions.GLOBAL : regions.toString();
    }

    /** Loads the version {@code versionId} names (none when it names none). */
    static <I, O> void loadVersion(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> versionId) {
        pb.step("Load the version", QueryEntities.<ProcessContext>of(QbContent.defaultDataset(QbContent.VERSION),
            ctx -> EntityQuery.builder().where(new QueryPredicate.In("versionId", ids(versionId.apply(ctx))))
                .limit(1).build(), VERSION));
    }

    /** The snapshot of the loaded version; a version is written once, so an attempt's is always there. */
    static QuizSnapshot snapshot(ProcessContext ctx) {
        EntityInstance version = first(ctx, VERSION);
        if (version == null) {
            throw new IllegalStateException("The version of the attempt is missing");
        }
        return QuizSnapshots.fromJson(version.get("content"));
    }

    /** Loads the card of the publication {@code publicationId} names into {@code key}. */
    static <I, O> void loadCard(ProcessDefinitionBuilder<I, O, ProcessContext> pb, String stepName,
        Function<ProcessContext, Object> publicationId, String key) {
        pb.step(stepName, QueryEntities.<ProcessContext>of(QbPublications.defaultDataset(QbPublications.CARD),
            ctx -> EntityQuery.builder().where(new QueryPredicate.In("publicationId", ids(publicationId.apply(ctx))))
                .limit(1).build(), key));
    }

    /** Loads the counters of the publication {@code publicationId} names into {@code key}. */
    static <I, O> void loadCounter(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> publicationId, String key) {
        pb.step("Load the counters", QueryEntities.<ProcessContext>of(
            QbPublications.defaultDataset(QbPublications.COUNTER), ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("publicationId", ids(publicationId.apply(ctx)))).limit(1).build(), key));
    }

    static AttemptRules.Counts counts(EntityInstance counter) {
        return new AttemptRules.Counts(number(counter.get("starts")), number(counter.get("completions")),
            number(counter.get("winners")), money(counter.get("reserved")), money(counter.get("spent")));
    }

    static AttemptRules.Limits limits(EntityInstance card) {
        return new AttemptRules.Limits(moneyOrNull(card.get("budgetCap")), integerOrNull(card.get("maxWinners")),
            money(card.get("maxReward")));
    }

    static AttemptRules.Card cardOf(EntityInstance card) {
        return new AttemptRules.Card(card.get("sponsorId"), card.get("status"), card.get("regions"),
            instant(card.get("startAt")), instant(card.get("endAt")), limits(card));
    }

    static List<Object> ids(Object id) {
        List<Object> ids = new ArrayList<>();
        if (id != null) {
            ids.add(id);
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    static long number(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).longValueExact();
    }

    static int integer(Object value) {
        return value == null ? 0 : new BigDecimal(value.toString()).intValueExact();
    }

    static Integer integerOrNull(Object value) {
        return value == null ? null : new BigDecimal(value.toString()).intValueExact();
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    static BigDecimal moneyOrNull(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    static Instant instant(Object value) {
        return value == null ? null : value instanceof OffsetDateTime time ? time.toInstant()
            : value instanceof Instant time ? time : Instant.parse(value.toString());
    }

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /** A value of a template row by its result name (the keys may come back folded to lower case). */
    static Object column(Map<String, Object> row, String name) {
        return row.containsKey(name) ? row.get(name) : row.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    private PlaySteps() {}
}
