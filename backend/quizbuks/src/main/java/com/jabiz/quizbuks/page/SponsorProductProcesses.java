package com.jabiz.quizbuks.page;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.TextTranslations;
import com.jabiz.quizbuks.publishing.PublicationLanguages;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.operator.OperatorChecks;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.file.FileEntities;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.publicread.FileAccess;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A sponsor's products and the quizzes that show them (docs/quizbuks/plans/Q60-sponsor-page.md, Q60b):
 * <ul>
 *   <li>{@value #SAVE} / {@value #DELETE}: the sponsor writes their products (at most
 *       {@value QbSponsorProducts#MAX_PRODUCTS}); a change shows at once, no review (the page's first publication was
 *       reviewed); a product taken down stays hidden whatever the sponsor saves;</li>
 *   <li>{@value #QUIZ_PRODUCTS_SET}: the products a quiz shows (at most {@value QbSponsorProducts#MAX_QUIZ_PRODUCTS})
 *       and one a question shows, by its number; not part of the quiz's versions, so it applies at once;</li>
 *   <li>{@value #TAKEDOWN} / {@value #RESTORE}, and the operator's: headquarters, or the sponsor's operator, hides a
 *       product with a reason the sponsor reads, and puts it back (an operator only its own takedowns).</li>
 * </ul>
 * Every write holds the sponsor's account lock (an operator's membership before it).
 */
public final class SponsorProductProcesses {

    public static final String SAVE = "QB_PRODUCT_SAVE";
    public static final String DELETE = "QB_PRODUCT_DELETE";
    public static final String QUIZ_PRODUCTS_SET = "QB_QUIZ_PRODUCTS_SET";
    public static final String TAKEDOWN = "QB_PRODUCT_TAKEDOWN";
    public static final String RESTORE = "QB_PRODUCT_RESTORE";
    public static final String OPERATOR_TAKEDOWN = "QB_OPERATOR_PRODUCT_TAKEDOWN";
    public static final String OPERATOR_RESTORE = "QB_OPERATOR_PRODUCT_RESTORE";

    /**
     * @param productId    the product to change; none for a new one
     * @param image        a file of the policy {@code qb.sponsor-page-image} the caller uploaded; none to have none
     * @param price        the shop's price, in {@code currency} (no decimals for the yen, two for the others)
     * @param listed       whether it is shown (a product taken down is not, whatever this says)
     * @param seq          its place among the sponsor's products; none: last
     * @param baseRevision the revision the caller edited; when given and no longer current, 422
     */
    public record SaveInput(String productId, @NotBlank String name, String description, UUID image,
        @NotNull BigDecimal price, @NotBlank String currency, @NotBlank String shopUrl, boolean listed, Integer seq,
        Long baseRevision, Map<String, Map<String, String>> translations, List<String> confirmed) {

        /** A product in its page's own language only (before Q73c). */
        public SaveInput(String productId, String name, String description, UUID image, BigDecimal price,
            String currency, String shopUrl, boolean listed, Integer seq, Long baseRevision) {
            this(productId, name, description, image, price, currency, shopUrl, listed, seq, baseRevision, null, null);
        }
    }

    public record ProductRef(@NotBlank String productId) {}

    /** @param reason why, as the sponsor will read it */
    public record TakedownInput(@NotBlank String productId,
        @NotBlank @Size(max = QbSponsorPages.REASON_LENGTH) String reason) {}

    public record ProductOutput(String productId, String status, long revision) {}

    /** A product shown with a question of the quiz, by the question's number in whichever version is played. */
    public record QuestionProduct(int questionNo, @NotNull UUID productId) {}

    /**
     * @param quizProducts     the products shown with the whole quiz, in their order
     * @param questionProducts one product each for some questions
     */
    public record QuizProductsInput(@NotNull UUID quizId, List<UUID> quizProducts,
        List<@Valid QuestionProduct> questionProducts) {}

    public record QuizProductsOutput(String quizId, int links) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String USER_ID = "productUserId";
    static final String FIRST = "first";
    static final String PRODUCT = "product";
    static final String PRODUCTS = "products";
    static final String FILES = "files";
    static final String QUIZ = "quiz";
    static final String LINKS = "links";
    static final String RELEASED = "released";
    static final String OFFLINE = "offlineImage";

    // ---- save and delete -------------------------------------------------------------------------------------------

    public static ProcessDefinition<SaveInput, ProductOutput, ProcessContext> save() {
        return ProcessDefinition.define(SAVE, 1, SaveInput.class, ProductOutput.class, ProcessContext.class, pb -> {
            pb.description("Saves one of the sponsor's products: its name, description, image, price and shop link; "
                    + "shown on the published page at once.")
                .permissions(QbPermissions.SPONSOR_ME)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, start.request().actorId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, ProductOutput.class));
            lockAccount(pb);
            CallerChecks.refuseBanned(pb);
            pb.step("Load the products and the image", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbSponsorProducts.DEFAULT_DATASET, ctx -> ofUser(userId(ctx)), PRODUCTS),
                QueryEntities.query(FileEntities.DATASET, ctx -> {
                    UUID image = ctx.get(INPUT, SaveInput.class).image();
                    return EntityQuery.builder().where(new QueryPredicate.In(FileEntities.FILE_ID,
                        image == null ? List.of() : List.of(image))).limit(1).build();
                }, FILES)));
            pb.compute("Write the product", (metadata, ctx) -> saved(ctx));
            pb.step("Save", SaveChanges.now());
            pb.step("Release the replaced image", CallProcess.<ProcessContext>when(
                ctx -> !ctx.hasViolations() && ctx.contains(RELEASED), FileProcesses.DELETE, 1,
                ctx -> new FileProcesses.DeleteInput(ctx.get(RELEASED, UUID.class)), null));
            stopServingImage(pb);
        });
    }

    static void saved(ProcessContext ctx) {
        SaveInput input = ctx.get(INPUT, SaveInput.class);
        List<EntityInstance> products = list(ctx, PRODUCTS);
        EntityInstance product = null;
        if (input.productId() != null) {
            product = products.stream().filter(row -> same(row.id(), input.productId())).findFirst()
                .orElseThrow(() -> new EntityNotFoundException(QbSponsorProducts.PRODUCT + " " + input.productId()));
            AccountEditing.current(ctx, product, input.baseRevision(), SponsorPageCodes.PRODUCT_CHANGED);
        } else if (products.size() >= QbSponsorProducts.MAX_PRODUCTS) {
            ctx.reject(new Violation(null, SponsorPageCodes.PRODUCT_LIMIT, "A sponsor has at most "
                + QbSponsorProducts.MAX_PRODUCTS + " products", Map.of("max", QbSponsorProducts.MAX_PRODUCTS)));
        }
        BigDecimal price = input.price();
        if (price.signum() < 0 || price.stripTrailingZeros().scale() > QbSponsorProducts.scaleOf(input.currency())
            || price.precision() - price.scale() > 10) {
            ctx.reject(new Violation("price", SponsorPageCodes.PRODUCT_PRICE, "The price " + price.toPlainString()
                + " does not fit " + input.currency(), Map.of("decimals",
                    QbSponsorProducts.scaleOf(input.currency()))));
        }
        Object stored = product == null ? null : product.get("image");
        if (input.image() != null && !same(stored, input.image())) {
            EntityInstance file = first(ctx, FILES);
            if (file != null && !userId(ctx).equals(file.get(FileEntities.UPLOADED_BY))) {
                ctx.reject(new Violation("image", AccountCodes.FILE_NOT_OWN, "File " + input.image()
                    + " was not uploaded by you", Map.of("fileId", input.image().toString())));
            }
        }
        // Q73c: the name and description in other languages, optional; a translation out of date is refused.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("name", input.name().strip());
        sources.put("description", blankToNull(input.description()));
        Map<String, Map<String, PublicationLanguages.Text>> storedTexts = PublicationLanguages.parse(
            product == null ? null : product.get("translations"));
        List<String> languages = new java.util.ArrayList<>(input.translations() == null ? storedTexts.keySet()
            : input.translations().keySet());
        Map<String, Map<String, PublicationLanguages.Text>> translations = TextTranslations.merge(
            input.translations(), storedTexts, sources, languages, input.confirmed());
        TextTranslations.problems(languages, languages, input.translations(), translations, sources, Map.of("name",
            QbSponsorProducts.NAME_LENGTH, "description", QbSponsorProducts.DESCRIPTION_LENGTH), false)
            .forEach(ctx::reject);
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", input.name().strip());
        values.put("description", blankToNull(input.description()));
        values.put("translations", PublicationLanguages.toJson(translations));
        values.put("image", input.image());
        values.put("price", price.setScale(QbSponsorProducts.scaleOf(input.currency())));
        values.put("currency", input.currency());
        values.put("shopUrl", input.shopUrl().strip());
        boolean takenDown = product != null && QbSponsorProducts.TAKEN_DOWN.equals(product.get("status"));
        values.put("status", takenDown ? QbSponsorProducts.TAKEN_DOWN
            : input.listed() ? QbSponsorProducts.LISTED : QbSponsorProducts.UNLISTED);
        values.put("seq", BigDecimal.valueOf(input.seq() != null ? input.seq()
            : product != null ? AccountEditing.number(product.get("seq")) : products.size()));
        if (product == null) {
            values.put("userId", userId(ctx));
        } else if (stored != null && !same(stored, input.image())) {
            ctx.put(RELEASED, UUID.fromString(String.valueOf(stored)));
        } else if (stored != null && QbSponsorProducts.LISTED.equals(product.get("status"))
            && !QbSponsorProducts.LISTED.equals(values.get("status"))) {
            // Hidden with its image kept: the image stops being public at once.
            ctx.put(OFFLINE, UUID.fromString(String.valueOf(stored)));
        }
        write(ctx, product, values);
    }

    public static ProcessDefinition<ProductRef, ProductOutput, ProcessContext> delete() {
        return ProcessDefinition.define(DELETE, 1, ProductRef.class, ProductOutput.class, ProcessContext.class, pb -> {
            pb.description("Deletes one of the sponsor's products, and its place on their quizzes.")
                .permissions(QbPermissions.SPONSOR_ME)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> start(start, input, start.request().actorId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, ProductOutput.class));
            lockAccount(pb);
            CallerChecks.refuseBanned(pb);
            pb.step("Load the product and where it is shown", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbSponsorProducts.DEFAULT_DATASET,
                    ctx -> byId(ctx.get(INPUT, ProductRef.class).productId(), userId(ctx)), PRODUCT),
                QueryEntities.query(QbSponsorProducts.LINK_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("productId",
                        List.of(ctx.get(INPUT, ProductRef.class).productId())))
                    .limit(Integer.MAX_VALUE).build(), LINKS)));
            pb.compute("Delete", (metadata, ctx) -> {
                EntityInstance product = requireProduct(ctx);
                for (EntityInstance link : list(ctx, LINKS)) {
                    ctx.changes().delete(QbSponsorProducts.QUIZ_LINK, link.id(), link.version());
                }
                ctx.changes().delete(QbSponsorProducts.PRODUCT, product.id(), product.version());
                Object image = product.get("image");
                if (image != null) {
                    ctx.put(RELEASED, UUID.fromString(String.valueOf(image)));
                    if (QbSponsorProducts.LISTED.equals(product.get("status"))) {
                        ctx.put(OFFLINE, UUID.fromString(String.valueOf(image)));
                    }
                }
                ctx.put(OUTPUT, new ProductOutput(String.valueOf(product.id()), null,
                    AccountEditing.number(product.get("revision"))));
            });
            pb.step("Save", SaveChanges.now());
            pb.step("Release the image", CallProcess.<ProcessContext>when(
                ctx -> !ctx.hasViolations() && ctx.contains(RELEASED), FileProcesses.DELETE, 1,
                ctx -> new FileProcesses.DeleteInput(ctx.get(RELEASED, UUID.class)), null));
            stopServingImage(pb);
        });
    }

    // ---- the quizzes' products -------------------------------------------------------------------------------------

    public static ProcessDefinition<QuizProductsInput, QuizProductsOutput, ProcessContext> quizProducts() {
        return ProcessDefinition.define(QUIZ_PRODUCTS_SET, 1, QuizProductsInput.class, QuizProductsOutput.class,
            ProcessContext.class, pb -> {
                pb.description("Sets the products one of the sponsor's quizzes shows, and the one some of its "
                        + "questions show; applies at once to its publications.")
                    .permissions(QbPermissions.SPONSOR_ME)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> start(start, input, start.request().actorId()))
                    .outputMapper(ctx -> ctx.get(OUTPUT, QuizProductsOutput.class));
                lockAccount(pb);
                CallerChecks.refuseBanned(pb);
                pb.step("Load the quiz, the products and the links", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbContent.defaultDataset(QbContent.QUIZ), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("quizId", List.of(quizId(ctx)))).limit(1).build(), QUIZ),
                    QueryEntities.query(QbSponsorProducts.DEFAULT_DATASET, ctx -> ofUser(userId(ctx)), PRODUCTS),
                    QueryEntities.query(QbSponsorProducts.LINK_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("quizId", List.of(quizId(ctx).toString())))
                        .limit(Integer.MAX_VALUE).build(), LINKS)));
                pb.compute("Write the links", (metadata, ctx) -> linked(ctx));
            });
    }

    private static UUID quizId(ProcessContext ctx) {
        return ctx.get(INPUT, QuizProductsInput.class).quizId();
    }

    /** The link wanted, keyed as stored: question number and product. */
    private record Wanted(int questionNo, String productId, int seq) {}

    static void linked(ProcessContext ctx) {
        QuizProductsInput input = ctx.get(INPUT, QuizProductsInput.class);
        EntityInstance quiz = first(ctx, QUIZ);
        if (quiz == null || !userId(ctx).equals(quiz.get("ownerId")) || Boolean.TRUE.equals(quiz.get("removed"))) {
            // Another's quiz is not found, as it is not in their lists.
            throw new EntityNotFoundException(QbContent.QUIZ + " " + input.quizId());
        }
        Set<String> own = new HashSet<>();
        list(ctx, PRODUCTS).forEach(row -> own.add(String.valueOf(row.id())));
        List<UUID> forQuiz = input.quizProducts() == null ? List.of() : input.quizProducts();
        List<QuestionProduct> forQuestions = input.questionProducts() == null ? List.of() : input.questionProducts();
        List<Wanted> wanted = new ArrayList<>();
        Set<String> seenQuiz = new HashSet<>();
        for (UUID id : forQuiz) {
            if (id != null && seenQuiz.add(id.toString())) {
                wanted.add(new Wanted(QbSponsorProducts.WHOLE_QUIZ, id.toString(), wanted.size()));
            }
        }
        Set<Integer> seenQuestions = new HashSet<>();
        boolean badNumber = false;
        for (QuestionProduct link : forQuestions) {
            if (link == null || link.productId() == null) {
                badNumber = true;
                continue;
            }
            if (link.questionNo() < 1 || link.questionNo() > ContentLimits.MAX_QUESTIONS
                || !seenQuestions.add(link.questionNo())) {
                badNumber = true;
            }
            wanted.add(new Wanted(link.questionNo(), link.productId().toString(), 0));
        }
        boolean foreign = wanted.stream().anyMatch(link -> !own.contains(link.productId()));
        if (seenQuiz.size() > QbSponsorProducts.MAX_QUIZ_PRODUCTS || badNumber || foreign) {
            ctx.reject(new Violation(null, SponsorPageCodes.QUIZ_PRODUCTS, "A quiz shows at most "
                + QbSponsorProducts.MAX_QUIZ_PRODUCTS + " of the sponsor's own products, and a question one",
                Map.of("max", QbSponsorProducts.MAX_QUIZ_PRODUCTS)));
            return;
        }
        // The links as stored, by their key; those no longer wanted go, new ones come, a changed place is updated.
        Map<String, EntityInstance> stored = new HashMap<>();
        for (EntityInstance link : list(ctx, LINKS)) {
            stored.put(key(AccountEditing.number(link.get("questionNo")), link.get("productId")), link);
        }
        for (Wanted link : wanted) {
            EntityInstance existing = stored.remove(key(link.questionNo(), link.productId()));
            if (existing == null) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("quizId", input.quizId().toString());
                values.put("questionNo", BigDecimal.valueOf(link.questionNo()));
                values.put("productId", link.productId());
                values.put("ownerId", userId(ctx));
                values.put("seq", BigDecimal.valueOf(link.seq()));
                ctx.changes().insert(QbSponsorProducts.QUIZ_LINK, values);
            } else if (AccountEditing.number(existing.get("seq")) != link.seq()) {
                ctx.changes().update(QbSponsorProducts.QUIZ_LINK, existing.id(), existing.version(),
                    Map.of("seq", BigDecimal.valueOf(link.seq())));
            }
        }
        for (EntityInstance gone : stored.values()) {
            ctx.changes().delete(QbSponsorProducts.QUIZ_LINK, gone.id(), gone.version());
        }
        ctx.put(OUTPUT, new QuizProductsOutput(input.quizId().toString(), wanted.size()));
    }

    private static String key(Object questionNo, Object productId) {
        return questionNo + ":" + productId;
    }

    // ---- takedown and restore --------------------------------------------------------------------------------------

    public static ProcessDefinition<TakedownInput, ProductOutput, ProcessContext> takedown() {
        return takedown(TAKEDOWN, false);
    }

    public static ProcessDefinition<TakedownInput, ProductOutput, ProcessContext> operatorTakedown() {
        return takedown(OPERATOR_TAKEDOWN, true);
    }

    public static ProcessDefinition<ProductRef, ProductOutput, ProcessContext> restore() {
        return restore(RESTORE, false);
    }

    public static ProcessDefinition<ProductRef, ProductOutput, ProcessContext> operatorRestore() {
        return restore(OPERATOR_RESTORE, true);
    }

    private static ProcessDefinition<TakedownInput, ProductOutput, ProcessContext> takedown(String name,
        boolean operator) {
        return ProcessDefinition.define(name, 1, TakedownInput.class, ProductOutput.class, ProcessContext.class,
            pb -> {
                pb.description(operator ? "Hides a product of a sponsor the operator looks after."
                        : "Hides a sponsor's product; only an administrator puts it back.")
                    .permissions(operator ? QbPermissions.OPERATOR_USE : QbPermissions.ADMIN_CONTENT_WRITE)
                    .requiresMfa(MfaRequirement.ALWAYS)
                    .requiresVerifiedEmail()
                    .actsOn(QbSponsorProducts.PRODUCT, "productId", a -> a.whenField("status",
                        QbSponsorProducts.LISTED, QbSponsorProducts.UNLISTED))
                    .contextFactory((start, input) -> start(start, input, null))
                    .outputMapper(ctx -> ctx.get(OUTPUT, ProductOutput.class));
                lockAndLoad(pb, operator, ctx -> ctx.get(INPUT, TakedownInput.class).productId());
                pb.compute("Take down", (metadata, ctx) -> {
                    EntityInstance product = requireProduct(ctx);
                    if (!in(ctx, product, QbSponsorProducts.LISTED, QbSponsorProducts.UNLISTED)) {
                        return;
                    }
                    if (QbSponsorProducts.LISTED.equals(product.get("status")) && product.get("image") != null) {
                        ctx.put(OFFLINE, UUID.fromString(String.valueOf((Object) product.get("image"))));
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("status", QbSponsorProducts.TAKEN_DOWN);
                    values.put("takenDownAt", ctx.opTime());
                    values.put("takedownReason", ctx.get(INPUT, TakedownInput.class).reason());
                    values.put("takedownOperatorId", operator ? OperatorChecks.operatorId(ctx) : null);
                    write(ctx, product, values);
                });
                stopServingImage(pb);
            });
    }

    private static ProcessDefinition<ProductRef, ProductOutput, ProcessContext> restore(String name,
        boolean operator) {
        return ProcessDefinition.define(name, 1, ProductRef.class, ProductOutput.class, ProcessContext.class, pb -> {
            pb.description(operator ? "Puts back a product the operator took down; the sponsor shows it again."
                    : "Puts back a product taken down; the sponsor shows it again.")
                .permissions(operator ? QbPermissions.OPERATOR_USE : QbPermissions.ADMIN_CONTENT_WRITE)
                .requiresMfa(MfaRequirement.ALWAYS)
                .requiresVerifiedEmail()
                .actsOn(QbSponsorProducts.PRODUCT, "productId", a -> a.whenField("status",
                    QbSponsorProducts.TAKEN_DOWN))
                .contextFactory((start, input) -> start(start, input, null))
                .outputMapper(ctx -> ctx.get(OUTPUT, ProductOutput.class));
            lockAndLoad(pb, operator, ctx -> ctx.get(INPUT, ProductRef.class).productId());
            pb.compute("Put back", (metadata, ctx) -> {
                EntityInstance product = requireProduct(ctx);
                if (!in(ctx, product, QbSponsorProducts.TAKEN_DOWN)) {
                    return;
                }
                // An operator does not undo headquarters' takedown, nor another operator's (D-Q48-5).
                if (operator && !Objects.equals(OperatorChecks.operatorId(ctx), product.get("takedownOperatorId"))) {
                    ctx.reject(new Violation(null, SponsorPageCodes.PRODUCT_STATE, "Headquarters took this product "
                        + "down", Map.of("status", QbSponsorProducts.TAKEN_DOWN)));
                    return;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                // Back to the sponsor, hidden: they show it again when they choose.
                values.put("status", QbSponsorProducts.UNLISTED);
                values.put("takenDownAt", null);
                values.put("takedownReason", null);
                values.put("takedownOperatorId", null);
                write(ctx, product, values);
            });
        });
    }

    /**
     * Finds the product, takes its sponsor's account lock (an operator's membership first), reads it again under the
     * lock and, for an operator, refuses another's sponsor as not found.
     */
    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb, boolean operator,
        java.util.function.Function<ProcessContext, String> productId) {
        if (operator) {
            OperatorChecks.member(pb);
        }
        pb.step("Find the product", QueryEntities.<ProcessContext>of(QbSponsorProducts.DEFAULT_DATASET,
            ctx -> byId(productId.apply(ctx), null), FIRST));
        pb.step("Lock the account", HoldLock.<ProcessContext>exclusive(ctx -> {
            EntityInstance found = first(ctx, FIRST);
            return found == null ? null : QbSponsorProducts.lockName(found.get("userId"));
        }));
        pb.step("Load the product", QueryEntities.<ProcessContext>of(QbSponsorProducts.DEFAULT_DATASET,
            ctx -> byId(productId.apply(ctx), null), PRODUCT));
        if (operator) {
            OperatorChecks.ownSponsor(pb, ctx -> {
                EntityInstance product = first(ctx, PRODUCT);
                return product == null ? null : product.get("userId");
            });
        }
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static ProcessContext start(ProcessStart start, Object input, String userId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        if (userId != null) {
            ctx.put(USER_ID, userId);
        }
        return ctx;
    }

    static String userId(ProcessContext ctx) {
        return ctx.contains(USER_ID) ? ctx.get(USER_ID, String.class) : null;
    }

    private static <I, O> void lockAccount(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the account", HoldLock.<ProcessContext>exclusive(
            ctx -> QbSponsorProducts.lockName(userId(ctx))));
    }

    /** Ends the public decision of a listed product's image going offline, after the commit. */
    private static <I, O> void stopServingImage(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.afterCommit("Stop serving the product's image publicly", FileAccess.<ProcessContext>invalidate(
            ctx -> ctx.contains(OFFLINE) ? List.of(ctx.get(OFFLINE, UUID.class)) : List.of()));
    }

    private static EntityQuery ofUser(String userId) {
        return EntityQuery.builder().where(new QueryPredicate.In("userId", userId == null ? List.of()
            : List.of(userId))).limit(QbSponsorProducts.MAX_PRODUCTS + 1).build();
    }

    /** The product of the id, the caller's own only when {@code userId} is given. */
    private static EntityQuery byId(String productId, String userId) {
        List<QueryPredicate> where = new ArrayList<>();
        where.add(new QueryPredicate.In("productId", productId == null ? List.of() : List.of(productId)));
        if (userId != null) {
            where.add(new QueryPredicate.In("userId", List.of(userId)));
        }
        return EntityQuery.builder().where(new QueryPredicate.And(where)).limit(1).build();
    }

    private static EntityInstance requireProduct(ProcessContext ctx) {
        EntityInstance product = first(ctx, PRODUCT);
        if (product == null) {
            throw new EntityNotFoundException(QbSponsorProducts.PRODUCT + " " + ctx.get(INPUT));
        }
        return product;
    }

    private static boolean in(ProcessContext ctx, EntityInstance product, String... states) {
        String status = product.get("status");
        if (List.of(states).contains(status)) {
            return true;
        }
        ctx.reject(new Violation(null, SponsorPageCodes.PRODUCT_STATE, "The product is " + status,
            Map.of("status", status)));
        return false;
    }

    private static void write(ProcessContext ctx, EntityInstance product, Map<String, Object> values) {
        long revision = product == null ? 1 : AccountEditing.number(product.get("revision")) + 1;
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put("revision", BigDecimal.valueOf(revision));
        next.put("editedAt", ctx.opTime());
        Object id;
        if (product == null) {
            id = ctx.changes().insert(QbSponsorProducts.PRODUCT, next);
        } else {
            id = product.id();
            ctx.changes().update(QbSponsorProducts.PRODUCT, product.id(), product.version(), next);
        }
        String status = next.containsKey("status") ? (String) next.get("status") : product.get("status");
        ctx.put(OUTPUT, new ProductOutput(String.valueOf(id), status, revision));
    }

    private static boolean same(Object stored, Object given) {
        return stored != null && given != null && String.valueOf(stored).equals(String.valueOf(given));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
        return found == null ? List.of() : found;
    }

    static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    private SponsorProductProcesses() {
    }
}
