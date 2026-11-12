package com.jabiz.quizbuks.page;

import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorProcesses;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.mailserver.MailServerProcesses;
import com.jabiz.quizbuks.mailserver.QbMailServers;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Importing a profile package (docs/quizbuks/plans/Q100-profile-package.md, Q100a). The export is the console's own
 * (it reads what the hub's pages read); the server never reads a file's bytes.
 * <ul>
 *   <li>{@value #CHECK}: the package without its files' data: its shape, and what the import would do (the plan), from
 *       the sponsor's present onboarding, page, products and mail server. Writes nothing.</li>
 *   <li>{@value #IMPORT}: the same package with the files uploaded for it (each by the caller, through the image
 *       policies), carried out by the processes the hub's pages run, as sub-processes in one transaction: all of it or
 *       nothing, by the same rules as typing it in.</li>
 * </ul>
 * <b>An import never changes a review</b> (D-Q100-3): it skips the basics while the onboarding is not approved (they
 * fill the application instead, which the sponsor submits), and the page while it waits for review (a save would
 * withdraw it) or before the onboarding is approved. Products are replaced as a whole (D-Q100-4). An image given as a
 * link is listed in the plan with the policy to download it under (F-C6): the page has the server download it once
 * ({@code POST /api/files/fetch}, decision D75: never in this transaction) and passes the file like an uploaded one;
 * a link without a file leaves the image there is as it is.
 */
public final class ProfileTransferProcesses {

    public static final String CHECK = "QB_PROFILE_IMPORT_CHECK";
    public static final String IMPORT = "QB_PROFILE_IMPORT";

    public static final String FILE_MISSING = "QB_PROFILE_IMPORT_FILE_MISSING";
    public static final String MAIL_PASSWORD = "QB_PROFILE_IMPORT_MAIL_PASSWORD";
    public static final String PRODUCT_TWICE = "QB_PROFILE_IMPORT_PRODUCT_TWICE";
    public static final List<String> CODES = List.of(FILE_MISSING, MAIL_PASSWORD, PRODUCT_TWICE);

    /** What the import does with a part. */
    public static final String SAVE = "SAVE";
    public static final String NONE = "NONE";
    /** The basics fill the application, which the sponsor submits (no onboarding yet, or a rejected one). */
    public static final String APPLY_FORM = "APPLY_FORM";
    /** The basics or the page wait for a review: left alone. */
    public static final String PENDING = "PENDING";
    /** The page before the onboarding is approved: left alone. */
    public static final String NOT_APPROVED = "NOT_APPROVED";
    /** The mail server needs its password typed in (no stored one for these settings). */
    public static final String PASSWORD = "PASSWORD";

    private static final String INPUT = "input";
    private static final String PACKAGE = "package";
    private static final String SPONSORS = "sponsors";
    private static final String PAGES = "pages";
    private static final String PRODUCTS = "products";
    private static final String SERVERS = "servers";
    private static final String PLAN = "plan";
    private static final String LINKS = "links";
    private static final String QUIZZES = "quizzes";
    private static final String PARAMS = "params";

    /** @param document the package's JSON; its files' {@code data} may be left out (they are never read) */
    public record CheckInput(@NotNull Map<String, Object> document) {}

    /** A file the import needs uploaded: where it is used (its key in {@code files} of the import) and the policy. */
    public record FileNeed(String path, String key, String policy, String type, long size) {}

    /**
     * An image given as a link (F-C6): the page downloads it under {@code policy} and passes the file by this path; a
     * link given no file leaves the image there is.
     */
    public record Link(String path, String url, String policy) {}

    /**
     * What the import does: each part's action ({@link #SAVE}, {@link #NONE}, {@link #APPLY_FORM},
     * {@link #PENDING}, {@link #NOT_APPROVED}, {@link #PASSWORD}), the products added, changed and deleted (by
     * name), and the files to upload.
     */
    public record Plan(String basics, String page, List<String> added, List<String> changed, List<String> deleted,
        String mailServer, List<FileNeed> files, List<Link> links, List<LinkedProduct> linked) {}

    /** A product the import deletes that quizzes show (D-Q100-13): it leaves them; the sponsor confirms it first. */
    public record LinkedProduct(String product, List<String> quizzes) {}

    public record CheckOutput(Plan plan) {}

    /**
     * @param files        the id of each file uploaded for the package (or downloaded for a link of it, F-C6), by its path
     * @param mailPassword the mail server's password; none keeps the stored one when the server is the same
     * @param siteDraftId  F-C7c: the website draft the package came from; its request to review is closed once the
     *                     package is imported (only the caller's own draft counts)
     */
    public record ImportInput(@NotNull Map<String, Object> document, Map<String, UUID> files,
        @Sensitive String mailPassword, UUID siteDraftId) {

        /** As before F-C7c: a package that did not come from a website draft. */
        public ImportInput(Map<String, Object> document, Map<String, UUID> files, String mailPassword) {
            this(document, files, mailPassword, null);
        }

        @Override
        public String toString() {
            return "ImportInput[files=" + files + "]";
        }
    }

    public record ImportOutput(Plan plan) {}

    private ProfileTransferProcesses() {}

    public static ProcessDefinition<CheckInput, CheckOutput, ProcessContext> check() {
        return ProcessDefinition.define(CHECK, 1, CheckInput.class, CheckOutput.class, ProcessContext.class, pb -> {
            pb.description("Checks a profile package before importing it: its content, and what the import would "
                    + "change.")
                .permissions(QbPermissions.SPONSOR_ME)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new CheckOutput(ctx.get(PLAN, Plan.class)));
            CallerChecks.refuseBanned(pb);
            plan(pb, ctx -> ctx.get(INPUT, CheckInput.class).document(), null);
        });
    }

    public static ProcessDefinition<ImportInput, ImportOutput, ProcessContext> importPackage() {
        return ProcessDefinition.define(IMPORT, 1, ImportInput.class, ImportOutput.class, ProcessContext.class,
            pb -> {
                pb.description("Imports a profile package: the basics, the public page, the products (replacing "
                        + "them) and the mail server, as the hub's pages save them; never changes a review.")
                    .permissions(QbPermissions.SPONSOR_ME)
                    .requiresVerifiedEmail()
                    .contextFactory((start, input) -> {
                        ProcessContext ctx = new ProcessContext(start);
                        ctx.put(INPUT, input);
                        return ctx;
                    })
                    .outputMapper(ctx -> new ImportOutput(ctx.get(PLAN, Plan.class)));
                CallerChecks.refuseBanned(pb);
                plan(pb, ctx -> ctx.get(INPUT, ImportInput.class).document(),
                    ctx -> ctx.get(INPUT, ImportInput.class).mailPassword());
                pb.compute("Check the uploads", (metadata, ctx) -> checkUploads(ctx));
                // The page first: its save takes the handle's lock before the account's, as typed in.
                pb.step("Save the page", CallProcess.<ProcessContext>when(ctx -> doing(ctx).page().equals(SAVE),
                    SponsorPageProcesses.SAVE, 1, ProfileTransferProcesses::pageInput, null));
                pb.step("Save the basics", CallProcess.<ProcessContext>when(ctx -> doing(ctx).basics().equals(SAVE),
                    SponsorProcesses.PROFILE_SAVE, 1, ProfileTransferProcesses::basicsInput, null));
                pb.step("Delete the products not in the package", CallProcess.<ProcessContext>forEach(
                    SponsorProductProcesses.DELETE, 1, ProfileTransferProcesses::deletions, null));
                pb.step("Save the products", CallProcess.<ProcessContext>forEach(SponsorProductProcesses.SAVE, 1,
                    ProfileTransferProcesses::productInputs, null));
                pb.step("Save the mail server", CallProcess.<ProcessContext>when(
                    ctx -> doing(ctx).mailServer().equals(SAVE), MailServerProcesses.SET, 1,
                    ProfileTransferProcesses::mailInput, null));
                pb.step("Close the request to review the draft", CallProcess.<ProcessContext>when(
                    ctx -> ctx.get(INPUT, ImportInput.class).siteDraftId() != null,
                    com.jabiz.quizbuks.content.ai.SiteProfileProcesses.REVIEW_DONE, 1,
                    ctx -> new com.jabiz.quizbuks.content.ai.SiteProfileProcesses.ReviewRef(
                        String.valueOf(ctx.get(INPUT, ImportInput.class).siteDraftId())), null));
            });
    }

    // ---- reading and planning --------------------------------------------------------------------------------------

    private static <I, O> void plan(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        java.util.function.Function<ProcessContext, Map<String, Object>> document,
        java.util.function.Function<ProcessContext, String> password) {
        // F-C11: the products' limit, as the products page and QB_PRODUCT_SAVE read it.
        pb.step("Load the products' limit", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
            QbParams.PRODUCTS_MAX));
        pb.compute("Read the package", (metadata, ctx) -> {
            ProfilePackage.Read read = ProfilePackage.read(document.apply(ctx),
                SponsorProductProcesses.maxProducts(ctx.get(PARAMS, ParamValues.class)));
            read.problems().forEach(ctx::reject);
            if (read.profile() != null) {
                ctx.put(PACKAGE, read.profile());
            }
        });
        pb.step("Load the sponsor's profile", QueryEntities.<ProcessContext>all(
            QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> ofUser(ctx, 1), SPONSORS),
            QueryEntities.query(QbSponsorPages.DEFAULT_DATASET, ctx -> ofUser(ctx, 1), PAGES),
            // All of them, whatever the limit (a package replaces every one).
            QueryEntities.query(QbSponsorProducts.DEFAULT_DATASET, ctx -> ofUser(ctx, Integer.MAX_VALUE), PRODUCTS),
            QueryEntities.query(QbMailServers.DEFAULT_DATASET,
                ctx -> QbMailServers.bySponsor(ctx.request().actorId()), SERVERS)));
        // D-Q100-13: where the products the import would delete are shown, and those quizzes' titles.
        pb.step("Load where the products are shown", QueryEntities.<ProcessContext>of(QbSponsorProducts.LINK_DATASET,
            ctx -> EntityQuery.builder().where(new QueryPredicate.In("productId", list(ctx, PRODUCTS).stream()
                .map(row -> (Object) String.valueOf(row.id())).toList())).limit(Integer.MAX_VALUE).build(), LINKS));
        pb.step("Load those quizzes", QueryEntities.<ProcessContext>of(QbContent.defaultDataset(QbContent.QUIZ),
            ctx -> EntityQuery.builder().where(new QueryPredicate.In("quizId", list(ctx, LINKS).stream()
                .map(row -> (Object) UUID.fromString(String.valueOf((Object) row.get("quizId")))).distinct().toList()))
                .limit(Integer.MAX_VALUE).build(), QUIZZES));
        pb.compute("Plan the import", (metadata, ctx) -> {
            if (!ctx.contains(PACKAGE)) {
                return;
            }
            ctx.put(PLAN, planOf(ctx, password == null ? null : password.apply(ctx)));
        });
    }

    private static EntityQuery ofUser(ProcessContext ctx, int limit) {
        return EntityQuery.builder().where(new QueryPredicate.In("userId", List.of(ctx.request().actorId())))
            .limit(limit).build();
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
        return found == null ? List.of() : found;
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static ProfilePackage pkg(ProcessContext ctx) {
        return ctx.get(PACKAGE, ProfilePackage.class);
    }

    private static Plan doing(ProcessContext ctx) {
        Plan plan = ctx.contains(PLAN) ? ctx.get(PLAN, Plan.class) : null;
        return plan == null || ctx.hasViolations() ? new Plan(NONE, NONE, List.of(), List.of(), List.of(), NONE,
            List.of(), List.of(), List.of()) : plan;
    }

    private static Plan planOf(ProcessContext ctx, String password) {
        ProfilePackage profile = pkg(ctx);
        EntityInstance sponsor = first(ctx, SPONSORS);
        Object review = sponsor == null ? null : sponsor.get("reviewStatus");
        String basics = profile.basics() == null ? NONE
            : SponsorStatus.APPROVED.equals(review) ? SAVE
            : SponsorStatus.PENDING.equals(review) ? PENDING : APPLY_FORM;
        EntityInstance page = first(ctx, PAGES);
        String pageAction = profile.page() == null ? NONE
            : !SponsorStatus.APPROVED.equals(review) ? NOT_APPROVED
            : page != null && QbSponsorPages.PENDING.equals(page.get("status")) ? PENDING : SAVE;

        // Products (D-Q100-13): one of the sponsor's own found by its code, else by its id, is changed; the rest are
        // new; the sponsor's others go.
        List<EntityInstance> matches = matches(ctx);
        Set<String> kept = new HashSet<>();
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (int i = 0; i < profile.products().size(); i++) {
            String name = profile.products().get(i).name().get(profile.base());
            EntityInstance match = matches.get(i);
            if (match != null) {
                if (!kept.add(String.valueOf(match.id()))) {
                    ctx.reject(new Violation("products[" + i + "]", PRODUCT_TWICE, "The product "
                        + match.get("name") + " is in the package twice", Map.of("index", i)));
                }
                changed.add(name);
            } else {
                added.add(name);
            }
        }
        List<EntityInstance> gone = list(ctx, PRODUCTS).stream().filter(row -> !kept.contains(String.valueOf(row.id())))
            .toList();
        List<String> deleted = gone.stream().map(row -> String.valueOf((Object) row.get("name"))).toList();
        Map<String, String> titles = new LinkedHashMap<>();
        list(ctx, QUIZZES).stream().filter(quiz -> !Boolean.TRUE.equals(quiz.get("removed")))
            .forEach(quiz -> titles.put(String.valueOf(quiz.id()), String.valueOf((Object) quiz.get("title"))));
        List<LinkedProduct> linked = new ArrayList<>();
        for (EntityInstance row : gone) {
            List<String> quizzes = list(ctx, LINKS).stream()
                .filter(link -> String.valueOf(row.id()).equals(String.valueOf((Object) link.get("productId"))))
                .map(link -> titles.get(String.valueOf((Object) link.get("quizId")))).filter(Objects::nonNull).distinct()
                .toList();
            if (!quizzes.isEmpty()) {
                linked.add(new LinkedProduct(String.valueOf((Object) row.get("name")), quizzes));
            }
        }

        String mail = NONE;
        if (profile.mailServer() != null) {
            EntityInstance stored = first(ctx, SERVERS);
            boolean same = stored != null && Objects.equals(stored.get("host"), profile.mailServer().host())
                && Objects.equals(stored.get("username"), profile.mailServer().username());
            mail = same || password != null && !password.isBlank() ? SAVE : PASSWORD;
        }

        List<FileNeed> files = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        profile.images().forEach((path, image) -> {
            if (image.isLink()) {
                if (wanted(path, basics, pageAction)) {
                    links.add(new Link(path, image.url(), policyOf(path)));
                }
            } else if (image.isFile() && wanted(path, basics, pageAction)) {
                ProfilePackage.FileEntry entry = profile.files().get(image.key());
                files.add(new FileNeed(path, image.key(), policyOf(path), entry.type(), entry.size()));
            }
        });
        return new Plan(basics, pageAction, List.copyOf(added), List.copyOf(changed), deleted, mail,
            List.copyOf(files), List.copyOf(links), List.copyOf(linked));
    }

    /** The sponsor's own product each of the package's stands for: by its code, else by its id; null for a new one. */
    private static List<EntityInstance> matches(ProcessContext ctx) {
        Map<String, EntityInstance> byId = new LinkedHashMap<>();
        Map<String, EntityInstance> byCode = new LinkedHashMap<>();
        for (EntityInstance row : list(ctx, PRODUCTS)) {
            byId.put(String.valueOf(row.id()), row);
            if (row.get("code") != null) {
                byCode.put(String.valueOf((Object) row.get("code")), row);
            }
        }
        List<EntityInstance> matches = new ArrayList<>();
        for (ProfilePackage.Product product : pkg(ctx).products()) {
            matches.add(product.code() != null ? byCode.get(product.code())
                : product.productId() == null ? null : byId.get(product.productId()));
        }
        return matches;
    }

    /** The file policy an image of the package is stored under: the avatar's, else the page images'. */
    private static String policyOf(String path) {
        return path.equals("basics.avatar") ? QbAccounts.AVATAR_POLICY : QbSponsorPages.IMAGE_POLICY;
    }

    /** Whether the image of {@code path} is saved by this import (a part left alone uploads nothing). */
    private static boolean wanted(String path, String basics, String page) {
        if (path.startsWith("basics.")) {
            return basics.equals(SAVE);
        }
        if (path.startsWith("page.")) {
            return page.equals(SAVE);
        }
        return true;
    }

    // ---- the import's sub-processes --------------------------------------------------------------------------------

    private static void checkUploads(ProcessContext ctx) {
        if (ctx.hasViolations() || !ctx.contains(PLAN)) {
            return;
        }
        Plan plan = ctx.get(PLAN, Plan.class);
        Map<String, UUID> uploaded = uploaded(ctx);
        for (FileNeed need : plan.files()) {
            if (uploaded.get(need.path()) == null) {
                ctx.reject(new Violation(need.path(), FILE_MISSING, "The file for " + need.path() + " was not "
                    + "uploaded", Map.of("path", need.path())));
            }
        }
        if (plan.mailServer().equals(PASSWORD)) {
            ctx.reject(new Violation("mailPassword", MAIL_PASSWORD, "Type in the mail server's password",
                Map.of()));
        }
    }

    private static Map<String, UUID> uploaded(ProcessContext ctx) {
        Map<String, UUID> files = ctx.get(INPUT, ImportInput.class).files();
        return files == null ? Map.of() : files;
    }

    /**
     * The image to save at {@code path}: the uploaded one, none, or the stored one (left out, or a link the page
     * downloaded no file for: F-C6). A file passed for a link is checked like an uploaded one, by the save.
     */
    private static UUID imageAt(ProcessContext ctx, String path, ProfilePackage.Image image, Object stored) {
        if (image == null) {
            return null;
        }
        if (image.isFile()) {
            return uploaded(ctx).get(path);
        }
        if (image.isLink() && uploaded(ctx).get(path) != null) {
            return uploaded(ctx).get(path);
        }
        return stored == null ? null : UUID.fromString(String.valueOf(stored));
    }

    /** The texts of the languages other than the base, as the saves take them ({@code {language: {field: text}}}). */
    private static void translate(Map<String, Map<String, String>> into, Map<String, String> texts, String field,
        String base, List<String> confirmed) {
        texts.forEach((language, text) -> {
            if (!language.equals(base)) {
                into.computeIfAbsent(language, l -> new LinkedHashMap<>()).put(field, text);
                // Written against the package's own base text: up to date as imported.
                confirmed.add(language + "." + field);
            }
        });
    }

    private static Object pageInput(ProcessContext ctx) {
        ProfilePackage profile = pkg(ctx);
        ProfilePackage.Page page = profile.page();
        EntityInstance stored = first(ctx, PAGES);
        Map<String, Map<String, String>> translations = new LinkedHashMap<>();
        List<String> confirmed = new ArrayList<>();
        translate(translations, page.headline(), "headline", profile.base(), confirmed);
        return new SponsorPageProcesses.SaveInput(page.handle(), profile.base(), page.headline().get(profile.base()),
            imageAt(ctx, "page.cover", page.cover(), stored == null ? null : stored.get("cover")), page.website(),
            stored == null ? null : number(stored.get("revision")),
            profile.languages().size() > 1 ? profile.languages() : List.of(), translations, confirmed);
    }

    private static Object basicsInput(ProcessContext ctx) {
        ProfilePackage profile = pkg(ctx);
        ProfilePackage.Basics basics = profile.basics();
        EntityInstance stored = first(ctx, SPONSORS);
        Map<String, Map<String, String>> translations = new LinkedHashMap<>();
        List<String> confirmed = new ArrayList<>();
        translate(translations, basics.intro(), SponsorProcesses.INTRO, profile.base(), confirmed);
        return new SponsorProcesses.ProfileInput(basics.contactName(), basics.company(),
            basics.intro().get(profile.base()), imageAt(ctx, "basics.avatar", basics.avatar(),
            stored.get("avatar")), number(stored.get("revision")), translations, confirmed);
    }

    private static List<?> deletions(ProcessContext ctx) {
        if (ctx.hasViolations() || !ctx.contains(PACKAGE)) {
            return List.of();
        }
        Set<String> kept = new HashSet<>();
        matches(ctx).forEach(match -> {
            if (match != null) {
                kept.add(String.valueOf(match.id()));
            }
        });
        return list(ctx, PRODUCTS).stream().map(row -> String.valueOf(row.id())).filter(id -> !kept.contains(id))
            .map(SponsorProductProcesses.ProductRef::new).toList();
    }

    private static List<?> productInputs(ProcessContext ctx) {
        if (ctx.hasViolations() || !ctx.contains(PACKAGE)) {
            return List.of();
        }
        ProfilePackage profile = pkg(ctx);
        List<EntityInstance> matches = matches(ctx);
        List<Object> inputs = new ArrayList<>();
        for (int i = 0; i < profile.products().size(); i++) {
            ProfilePackage.Product product = profile.products().get(i);
            EntityInstance stored = matches.get(i);
            Map<String, Map<String, String>> translations = new LinkedHashMap<>();
            List<String> confirmed = new ArrayList<>();
            translate(translations, product.name(), "name", profile.base(), confirmed);
            translate(translations, product.description(), "description", profile.base(), confirmed);
            inputs.add(new SponsorProductProcesses.SaveInput(stored == null ? null : String.valueOf(stored.id()),
                product.name().get(profile.base()), product.description().get(profile.base()),
                imageAt(ctx, "products[" + i + "].image", product.image(), stored == null ? null : stored.get("image")),
                product.price(), product.currency(), product.shopUrl(), product.listed(), i + 1,
                stored == null ? null : number(stored.get("revision")), translations, confirmed, product.code()));
        }
        return inputs;
    }

    private static Object mailInput(ProcessContext ctx) {
        ProfilePackage.MailServer server = pkg(ctx).mailServer();
        String password = ctx.get(INPUT, ImportInput.class).mailPassword();
        return new MailServerProcesses.SetInput(server.host(), server.port(), server.tls(), server.username(),
            password == null || password.isBlank() ? null : password, server.fromAddress(), server.fromName());
    }

    private static Long number(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value)).longValue();
    }
}
