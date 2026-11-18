package com.jabiz.quizbuks.firstlaunch;

import com.jabiz.firstlaunch.FirstLaunchConfig;
import com.jabiz.quizbuks.content.transfer.QuizFileChecks;
import com.jabiz.quizbuks.content.transfer.QuizFileFormat;
import com.jabiz.quizbuks.page.ProfilePackage;
import com.jabiz.quizbuks.publishing.PublishingLimits;
import com.jabiz.quizbuks.setup.FirstLaunchParams;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.sponsorpackage.PackageJson;
import com.jabiz.quizbuks.sponsorpackage.PackagePlan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The checks of the QuizBuks sections (F-LAUNCH plan section 6.2): references, the money rules of section 4, the
 * packages and the parameters. Every problem at once, each with a code whose words are in the messages
 * ({@code qb.firstLaunch.problem.<code>} and {@code qb.firstLaunch.fix.<code>}).
 */
final class QbFirstLaunchChecks {

    /** The one reward method the first launch allows (plan section 4). */
    static final String OFFLINE = "OFFLINE_REDEEM";

    /**
     * @param location where in the configuration
     * @param code     the problem's code
     * @param params   the values its words name
     */
    record Problem(String location, String code, Map<String, Object> params) {
        Problem {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    private QbFirstLaunchChecks() {}

    /**
     * Every problem of the sections.
     *
     * @param packages reads the package at a path (null when the file does not exist)
     */
    static List<Problem> check(FirstLaunchConfig config, QbFirstLaunchConfig sections,
        Function<String, QbPackages.Package> packages, Instant launch) {
        List<Problem> problems = new ArrayList<>();
        Set<String> accounts = new HashSet<>();
        config.accounts().forEach(account -> accounts.add(account.key()));
        operators(sections, accounts, problems);
        sponsors(sections, packages, launch, problems);
        params(sections, problems);
        return problems;
    }

    private static void operators(QbFirstLaunchConfig sections, Set<String> accounts, List<Problem> problems) {
        if (sections.operators().isEmpty()) {
            problems.add(new Problem("operators", "operators", null));
            return;
        }
        Set<String> keys = new HashSet<>();
        Set<String> leads = new HashSet<>();
        int defaults = 0;
        for (QbFirstLaunchConfig.Operator operator : sections.operators()) {
            String at = "operators[" + operator.index() + "]";
            if (operator.key() == null || operator.key().isEmpty() || !keys.add(operator.key())) {
                problems.add(new Problem(at + ".key", "operatorKey", Map.of("key", String.valueOf(operator.key()))));
            }
            if (operator.name() == null || operator.name().isEmpty()) {
                problems.add(new Problem(at + ".name", "operatorName", null));
            }
            if (operator.isDefault()) {
                defaults++;
            }
            if (operator.lead() == null || !accounts.contains(operator.lead())) {
                problems.add(new Problem(at + ".lead", "account", Map.of("key", String.valueOf(operator.lead()))));
            } else if (!leads.add(operator.lead())) {
                problems.add(new Problem(at + ".lead", "leadTwice", Map.of("key", operator.lead())));
            }
            for (int i = 0; i < operator.members().size(); i++) {
                String member = operator.members().get(i);
                if (!accounts.contains(member)) {
                    problems.add(new Problem(at + ".members[" + i + "]", "account", Map.of("key", member)));
                } else if (member.equals(operator.lead())) {
                    problems.add(new Problem(at + ".members[" + i + "]", "memberIsLead", Map.of("key", member)));
                }
            }
            long sponsors = sections.sponsors().stream().filter(s -> Objects.equals(s.operator(), operator.key()))
                .count();
            if (sponsors != 1) {
                problems.add(new Problem(at, "operatorSponsor", Map.of("count", sponsors)));
            }
        }
        if (defaults != 1) {
            problems.add(new Problem("operators", "operatorDefault", Map.of("count", defaults)));
        }
    }

    private static void sponsors(QbFirstLaunchConfig sections, Function<String, QbPackages.Package> packages,
        Instant launch, List<Problem> problems) {
        for (QbFirstLaunchConfig.Sponsor sponsor : sections.sponsors()) {
            String at = "sponsors[" + sponsor.index() + "]";
            if (sections.operator(sponsor.operator()).isEmpty()) {
                problems.add(new Problem(at + ".operator", "sponsorOperator",
                    Map.of("key", String.valueOf(sponsor.operator()))));
            }
            if (!List.of(OFFLINE).equals(sponsor.rewardMethods())) {
                problems.add(new Problem(at + ".rewardMethods", "rewardMethods", null));
            }
            if (sponsor.packagePath() == null) {
                problems.add(new Problem(at + ".package", "packageMissing", Map.of("path", "")));
                continue;
            }
            QbPackages.Package pkg = packages.apply(sponsor.packagePath());
            if (pkg == null) {
                problems.add(new Problem(at + ".package", "packageMissing", Map.of("path", sponsor.packagePath())));
                continue;
            }
            if (pkg.read().document() == null) {
                problems.add(new Problem(sponsor.packagePath(), "packageRead", Map.of("problem",
                    String.valueOf(pkg.read().problem()), "places", String.join(", ", pkg.read().places()))));
                continue;
            }
            packageContent(sponsor, pkg, problems);
            publications(at, sponsor, pkg.document(), launch, problems);
        }
    }

    private static void packageContent(QbFirstLaunchConfig.Sponsor sponsor, QbPackages.Package pkg,
        List<Problem> problems) {
        String at = sponsor.packagePath();
        PackagePlan.Document document = pkg.document();
        if (document.profile() != null) {
            ProfilePackage.Read profile = ProfilePackage.read(document.profile());
            profile.problems().forEach(v -> problems.add(new Problem(at + " profile" + field(v.field()),
                "profile", Map.of("code", v.ruleCode()))));
            if (profile.profile() != null && profile.profile().mailServer() != null) {
                problems.add(new Problem(at + " profile.mailServer", "profileMail", null));
            }
            for (String path : QbPackages.profileLinks(document.profile())) {
                problems.add(new Problem(at + " profile." + path, "link", null));
            }
        }
        for (PackagePlan.Quiz quiz : document.quizzes()) {
            List<Map<String, Object>> files = new ArrayList<>();
            quiz.versions().forEach(version -> files.add(version.file()));
            if (quiz.current() != null) {
                files.add(quiz.current());
            }
            for (Map<String, Object> file : files) {
                QuizFileFormat.Reading reading = QuizFileFormat.read(file);
                if (!reading.supported()) {
                    problems.add(new Problem(at + " quizzes." + quiz.key(), "quizFile",
                        Map.of("code", String.valueOf(reading.problem()))));
                    continue;
                }
                QuizFileChecks.check(reading.file(), QbPackages.declared(QbParams.CONTENT_MAX_QUESTIONS))
                    .forEach(v -> problems.add(new Problem(at + " quizzes." + quiz.key() + field(v.field()),
                        "quizFile", Map.of("code", v.ruleCode()))));
            }
        }
        PackagePlan.Planned planned = pkg.planned();
        List<String> left = new ArrayList<>();
        planned.tooLarge().forEach(title -> left.add(title));
        planned.oversized().forEach(o -> left.add(o.title()));
        planned.orphans().forEach(left::add);
        planned.unbound().forEach(left::add);
        planned.treesSkipped().forEach(tree -> left.add(String.valueOf(tree)));
        if (!left.isEmpty()) {
            problems.add(new Problem(at, "packagePlan", Map.of("items", String.join(", ", left))));
        }
        pkg.uploads().forEach((step, uploads) -> uploads.forEach(upload -> {
            if (pkg.bytes(upload) == null) {
                problems.add(new Problem(at + " " + upload.source() + ".files." + upload.fileKey(), "fileData",
                    null));
            }
        }));
    }

    private static void publications(String at, QbFirstLaunchConfig.Sponsor sponsor, PackagePlan.Document document,
        Instant launch, List<Problem> problems) {
        Set<String> names = new HashSet<>();
        for (PackagePlan.Publication publication : document.publications()) {
            String name = publication.name();
            String place = at + ".publications." + name;
            if (!names.add(name)) {
                problems.add(new Problem(sponsor.packagePath(), "publicationTwice", Map.of("name", name)));
                continue;
            }
            QbFirstLaunchConfig.PublicationExtra extra = sponsor.publications().get(name);
            if (extra == null) {
                problems.add(new Problem(at + ".publications", "publicationMissing", Map.of("name", name)));
                continue;
            }
            if (!OFFLINE.equals(extra.rewardMethod())) {
                problems.add(new Problem(place + ".rewardMethod", "rewardMethod", null));
            }
            if (extra.redeemDescription() == null || extra.redeemDescription().isEmpty()
                || extra.redeemDescription().length() > PublishingLimits.REDEEM_DESCRIPTION_LENGTH) {
                problems.add(new Problem(place + ".redeemDescription", "redeemDescription",
                    Map.of("max", PublishingLimits.REDEEM_DESCRIPTION_LENGTH)));
            }
            Instant start = extra.start(launch);
            Instant end = extra.end();
            if (start == null) {
                problems.add(new Problem(place + ".startAt", "startAt", null));
            }
            if (end == null || start != null && !end.isAfter(start)) {
                problems.add(new Problem(place + ".endAt", "endAt", null));
            } else if (start != null && java.time.Duration.between(start, end)
                .compareTo(PublishingLimits.MAX_RUNNING) > 0) {
                problems.add(new Problem(place + ".endAt", "running", Map.of("days",
                    PublishingLimits.MAX_RUNNING.toDays())));
            }
            // With no reward (section 4) a publication is open only bound to a node of its graph (TierCoverage).
            if (publication.node() == null) {
                problems.add(new Problem(place, "unbound", null));
            }
            money(place, publication, problems);
        }
        for (String name : sponsor.publications().keySet()) {
            if (!names.contains(name)) {
                problems.add(new Problem(at + ".publications." + name, "publicationUnknown", Map.of("name", name)));
            }
        }
    }

    /** Section 4: nothing of the publication moves money or reserves any. */
    private static void money(String place, PackagePlan.Publication publication, List<Problem> problems) {
        Map<String, Object> settings = publication.settings();
        boolean paid = nonZero(settings.get("budgetCap")) || nonZero(settings.get("entryFee"))
            || settings.get("referral") != null && PackageJson.isObject(settings.get("referral"))
            || "DIGITAL_PRIZE".equals(settings.get("rewardMethod"));
        for (Object tier : PackageJson.items(settings.get("tiers"))) {
            if (PackageJson.isObject(tier) && nonZero(PackageJson.object(tier).get("reward"))) {
                paid = true;
            }
        }
        if (paid) {
            problems.add(new Problem(place, "money", null));
        }
        if (!PackageJson.items(settings.get("conditions")).isEmpty() && !"INVITED".equals(settings.get("audience"))
            || publication.sharedVideo() != null && !publication.sharedVideo().isEmpty()) {
            problems.add(new Problem(place, "audience", null));
        }
    }

    private static void params(QbFirstLaunchConfig sections, List<Problem> problems) {
        sections.params().forEach((key, value) -> {
            String at = "params." + key;
            if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
                problems.add(new Problem(at, "paramValue", null));
                return;
            }
            FirstLaunchParams.Verdict verdict = FirstLaunchParams.verdict(key, String.valueOf(value));
            if (verdict != FirstLaunchParams.Verdict.SETTABLE) {
                problems.add(new Problem(at, "param" + switch (verdict) {
                    case VALUE_REFUSED -> "Refused";
                    case FIXED -> "Fixed";
                    case AMOUNT -> "Amount";
                    default -> "NotSettable";
                }, Map.of("key", key)));
                return;
            }
            Object kind = param(key).valueKind().get("type");
            if ("bool".equals(kind) != (value instanceof Boolean)) {
                problems.add(new Problem(at, "paramValue", null));
            }
        });
    }

    static QbParams.Param param(String key) {
        return QbParams.ALL.stream().filter(param -> param.key().equals(key)).findFirst()
            .orElseThrow(() -> new IllegalStateException("No parameter " + key));
    }

    private static boolean nonZero(Object value) {
        return value instanceof Number number && number.doubleValue() != 0
            || value instanceof String text && !text.isBlank() && !"0".equals(text.strip());
    }

    private static String field(String field) {
        return field == null || field.isEmpty() ? "" : "." + field;
    }

    /** The parameters' values as the proposals take them: a boolean stays one, anything else is text. */
    static Map<String, Object> values(QbFirstLaunchConfig sections) {
        Map<String, Object> values = new LinkedHashMap<>();
        sections.params().forEach((key, value) -> values.put(key, value instanceof Boolean ? value
            : String.valueOf(value)));
        return values;
    }
}
