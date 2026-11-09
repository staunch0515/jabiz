package com.jabiz.quizbuks.help;

import com.jabiz.external.ExternalJobType;

import java.time.Duration;
import java.time.Instant;

/**
 * The job {@value #TYPE} (F-C10b) that stores one entry's and language's materials snapshot as a file: the platform
 * stores files only from uploads and from external jobs' results (decision D50 item 3), so the build hands the text to
 * this job, whose handler returns it as the file of the policy {@value QbHelpMaterials#POLICY}; the completion
 * ({@code QB_HELP_MATERIALS_STORED}) puts it in use. Started by the system ({@code QB_HELP_MATERIALS_BUILD}), so the
 * file is the system's upload. It calls no service and reads no database.
 */
public final class HelpMaterialsJob {

    public static final String TYPE = "qb.help.materials";
    /** The key of the file in the result. */
    public static final String FILE = "materials";
    /** Builds of every entry and language at once, and a few more. */
    static final int MAX_OPEN = 20;

    /**
     * @param builtAt when the build read the sources: a later snapshot is never replaced by an earlier one
     * @param text    the snapshot (at most {@link QbHelpMaterials#MAX_FILE_BYTES}); it is stored with the job, so the
     *                platform keeps it as it keeps every job's input
     */
    public record Input(String entry, String language, String contentHash, Instant builtAt, String text) {

        @Override
        public String toString() {
            return "Input[entry=" + entry + ", language=" + language + ", contentHash=" + contentHash + ", text="
                + (text == null ? 0 : text.length()) + " chars]";
        }
    }

    public record Result(String entry, String language, String contentHash, Instant builtAt, long bytes) {}

    public static ExternalJobType<Input, Result> type() {
        return ExternalJobType.define(TYPE, Input.class, Result.class, t -> t
            .handler(HelpMaterialsHandler.class)
            .timeout(Duration.ofMinutes(1)).retry(3, Duration.ofSeconds(10), Duration.ofMinutes(1))
            .maxConcurrency(2).maxOpenPerRequester(MAX_OPEN)
            .maxInputSize(ExternalJobType.MAX_INPUT_BYTES)
            .outputFiles(QbHelpMaterials.POLICY, 1, QbHelpMaterials.MAX_FILE_BYTES)
            .onCompleted(HelpMaterialsProcesses.STORED).onFailed(HelpMaterialsProcesses.NOT_STORED));
    }

    private HelpMaterialsJob() {}
}
