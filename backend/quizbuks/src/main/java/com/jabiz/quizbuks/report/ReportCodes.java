package com.jabiz.quizbuks.report;

/** The refusal codes of reports (Q49a), each with a message in the three languages; the processes refuse with 422. */
public final class ReportCodes {

    /** A reason that is none of {@link QbReports#REASONS}. */
    public static final String REASON = "QB_REPORT_REASON";
    /** The caller's report of the publication is still open: one at a time. */
    public static final String ALREADY_REPORTED = "QB_REPORT_ALREADY_REPORTED";
    /** The caller made {@value QbReports#DAILY_LIMIT} reports in a day. */
    public static final String DAILY_LIMIT = "QB_REPORT_DAILY_LIMIT";
    /** An outcome that is neither dismissed nor acted on. */
    public static final String OUTCOME = "QB_REPORT_OUTCOME";

    private ReportCodes() {}
}
