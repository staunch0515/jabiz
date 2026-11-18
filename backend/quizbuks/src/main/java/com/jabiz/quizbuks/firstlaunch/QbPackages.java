package com.jabiz.quizbuks.firstlaunch;

import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.transfer.QuizFileChecks;
import com.jabiz.quizbuks.content.transfer.QuizFileFormat;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.page.ProfilePackage;
import com.jabiz.quizbuks.page.QbSponsorPages;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.sponsorpackage.PackageJson;
import com.jabiz.quizbuks.sponsorpackage.PackagePlan;
import com.jabiz.quizbuks.sponsorpackage.QuizFileCut;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A sponsor package of the first launch (F-LAUNCH plan section 1.2), read from the configuration's bytes and planned
 * as the sponsor page plans it for a new account ({@link PackagePlan#plan}, with the declared limits): the steps of
 * {@code QB_PACKAGE_IMPORT_STEP} and the files each one uploads. No I/O.
 */
final class QbPackages {

    /** The file policies a package's files are stored under: the quizzes' and the profile's. */
    static final Set<String> POLICIES = Set.of(QbContent.IMAGE_POLICY, QbContent.PDF_POLICY, QbContent.AUDIO_POLICY,
        QbAccounts.AVATAR_POLICY, QbSponsorPages.IMAGE_POLICY);

    /**
     * A file a step uploads.
     *
     * @param key    its key in the step's {@code files} (a quiz file's key, a profile image's path)
     * @param policy the policy it is stored under
     * @param source where its bytes are in the package: the quiz content's check id, or {@code profile}
     * @param fileKey its key in that document's {@code files}
     */
    record Upload(String key, String policy, String source, String fileKey) {}

    /** The profile's files are found under this source. */
    static final String PROFILE = "profile";

    /**
     * @param hash    the SHA-256 of the package's bytes (hex), as {@code QB_PACKAGE_IMPORT_START} takes it
     * @param read    the package, or why it is not one
     * @param planned the steps (null when not read)
     */
    record Package(String hash, PackagePlan.Read read, PackagePlan.Planned planned) {

        PackagePlan.Document document() {
            return read.document();
        }

        /** The files each step uploads, by the step's key (steps without files are left out). */
        Map<String, List<Upload>> uploads() {
            Map<String, List<Upload>> uploads = new LinkedHashMap<>();
            for (PackagePlan.Step step : planned.steps()) {
                List<Upload> files = switch (step) {
                    case PackagePlan.Step.Profile p -> profileUploads(document().profile());
                    case PackagePlan.Step.Content c -> contentUploads(c.check(), c.document(), quizFile(c.check()));
                    case PackagePlan.Step.Batch b -> contentUploads(b.check(), b.document(), quizFile(b.check()));
                    default -> List.of();
                };
                if (!files.isEmpty()) {
                    uploads.put(step.key(), files);
                }
            }
            return uploads;
        }

        /** The whole quiz file a content's check id ({@code <quiz key>:<version key|current>}) names, or null. */
        Map<String, Object> quizFile(String check) {
            int colon = check.lastIndexOf(':');
            String quizKey = check.substring(0, colon);
            String versionKey = check.substring(colon + 1);
            for (PackagePlan.Quiz quiz : document().quizzes()) {
                if (!quiz.key().equals(quizKey)) {
                    continue;
                }
                if ("current".equals(versionKey) && quiz.current() != null) {
                    return quiz.current();
                }
                for (PackagePlan.QuizVersion version : quiz.versions()) {
                    if (version.key().equals(versionKey)) {
                        return version.file();
                    }
                }
                return quiz.versions().isEmpty() ? null : quiz.versions().getLast().file();
            }
            return null;
        }

        /** The bytes of an upload, decoded from the package (null when it has none). */
        byte[] bytes(Upload upload) {
            Map<String, Object> document = PROFILE.equals(upload.source()) ? document().profile()
                : quizFile(upload.source());
            if (document == null || !PackageJson.isObject(document.get("files"))) {
                return null;
            }
            Object entry = PackageJson.object(document.get("files")).get(upload.fileKey());
            if (!PackageJson.isObject(entry) || !(PackageJson.object(entry).get("data") instanceof String data)) {
                return null;
            }
            try {
                return Base64.getMimeDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private QbPackages() {}

    /** Reads and plans the package's bytes. */
    static Package read(byte[] bytes, JsonMapper json) {
        Object value;
        try {
            value = json.readValue(bytes, Object.class);
        } catch (RuntimeException e) {
            return new Package(sha256(bytes), new PackagePlan.Read(null, PackagePlan.Problem.NOT_PACKAGE,
                List.of()), null);
        }
        PackagePlan.Read read = PackagePlan.read(value);
        PackagePlan.Planned planned = read.document() == null ? null : PackagePlan.plan(read.document(), context());
        return new Package(sha256(bytes), read, planned);
    }

    /** What a new account's import knows: the declared limits, nothing made yet, nothing shared. */
    static PackagePlan.Context context() {
        return new PackagePlan.Context(PackagePlan.Mode.FULL, declared(QbParams.CONTENT_IMPORT_BATCH),
            declared(QbParams.CONTENT_IMPORT_BATCH_BYTES), declared(QbParams.CONTENT_MAX_QUESTIONS),
            PackagePlan.AccountState.fresh(declared(QbParams.TREE_SPONSOR_MAX_TREES)), List.of(), List.of(), Set.of());
    }

    /** A declared whole-number parameter's value. */
    static int declared(String key) {
        Object value = QbParams.ALL.stream().filter(param -> param.key().equals(key)).findFirst()
            .orElseThrow(() -> new IllegalStateException("No parameter " + key)).value();
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value).strip());
    }

    /** The profile's images given as files, by path, under the policy the import stores each one with. */
    static List<Upload> profileUploads(Map<String, Object> profile) {
        if (profile == null) {
            return List.of();
        }
        ProfilePackage.Read read = ProfilePackage.read(profile);
        if (read.profile() == null) {
            return List.of();
        }
        List<Upload> uploads = new ArrayList<>();
        read.profile().images().forEach((path, image) -> {
            if (image.isFile()) {
                uploads.add(new Upload(path, path.equals("basics.avatar") ? QbAccounts.AVATAR_POLICY
                    : QbSponsorPages.IMAGE_POLICY, PROFILE, image.key()));
            }
        });
        return uploads;
    }

    /** The profile's images given as links (the first launch fetches nothing from the web: refused). */
    static List<String> profileLinks(Map<String, Object> profile) {
        if (profile == null) {
            return List.of();
        }
        ProfilePackage.Read read = ProfilePackage.read(profile);
        if (read.profile() == null) {
            return List.of();
        }
        return read.profile().images().entrySet().stream().filter(entry -> entry.getValue().isLink())
            .map(Map.Entry::getKey).toList();
    }

    /** The files a content's request uploads, each under the policy of its first use in the whole quiz file. */
    static List<Upload> contentUploads(String check, Map<String, Object> document, Map<String, Object> whole) {
        if (whole == null) {
            return List.of();
        }
        QuizFileFormat.Reading reading = QuizFileFormat.read(whole);
        if (!reading.supported()) {
            return List.of();
        }
        Map<String, QuizFileChecks.FileField> needed = QuizFileChecks.needed(reading.file());
        List<Upload> uploads = new ArrayList<>();
        for (String key : QuizFileCut.usedFiles(document)) {
            QuizFileChecks.FileField field = needed.get(key);
            if (field != null) {
                uploads.add(new Upload(key, field.policy(), check, key));
            }
        }
        return uploads;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** For tests: the package of a JSON text. */
    static Package read(String text, JsonMapper json) {
        return read(text.getBytes(StandardCharsets.UTF_8), json);
    }
}
