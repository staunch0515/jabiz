package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The cost limits of AI generation (docs/quizbuks/plans/Q8-ai.md, A-7), in plain Java: checked by
 * {@code QB_AI_QUESTIONS_REQUEST} under the sponsor's lock {@code qb.ai:<sponsor>}, so that two requests at once cannot
 * both pass a limit only one fits in. Days and months are the deployment's zone's ({@code jabiz.fiscal-year-zone}).
 * <ul>
 *   <li>at most {@value AiQuestionsJob#MAX_OPEN} unfinished jobs per sponsor (cancelled ones count until the provider
 *       is done with them), one per quiz;</li>
 *   <li>at most {@code qb.ai.sponsor.daily-jobs} requests a day;</li>
 *   <li>the month's tokens, plus what the unfinished jobs and this one may use ({@link #estimate}), within
 *       {@code qb.ai.sponsor.monthly-tokens};</li>
 *   <li>everybody's tokens of the month, plus what every sponsor's unfinished jobs and this one may use, within
 *       {@code qb.ai.global.monthly-tokens}: read under {@code qb.ai:*} as well, after the sponsor's lock.</li>
 * </ul>
 * A job's estimate covers all its {@value AiQuestionsJob#ATTEMPTS} attempts, each sending its whole input and writing
 * all the output it asks for ({@link ClaudeApi#maxTokens}), so the unfinished jobs together can never use more than
 * they reserved. The usage is the provider's own count, recorded per attempt (failed and refused attempts too, since
 * they are billed); an attempt that got no answer is charged its estimate ({@link ClaudeApi#unanswered}).
 * Every refusal names when it passes ({@code resetsAt}), for the page.
 */
public final class AiLimits {

    /** What the fixed system prompt and the request's framing add to the materials' text, in tokens. */
    static final long FRAMING_TOKENS = 1_500;

    /** The limits in force. */
    public record Settings(int dailyJobs, long monthlyTokens, long globalMonthlyTokens) {}

    /**
     * Where the sponsor stands.
     *
     * @param openJobs      the sponsor's unfinished jobs
     * @param quizBusy      whether the quiz has one
     * @param jobsToday     requests made since the start of the day
     * @param monthTokens   the tokens recorded this month (input and output)
     * @param openEstimate  what the unfinished jobs may still use
     * @param globalTokens  everybody's tokens recorded this month
     * @param globalOpenEstimate what every sponsor's unfinished jobs may still use (this sponsor's included)
     */
    public record Standing(int openJobs, boolean quizBusy, int jobsToday, long monthTokens, long openEstimate,
        long globalTokens, long globalOpenEstimate) {}

    /**
     * The input of {@code inputBytes} bytes in tokens: one per three bytes (Japanese and Chinese characters are three
     * bytes and about a token each; English is about four bytes a token, so this errs high), and the framing.
     */
    public static long inputEstimate(long inputBytes) {
        return (inputBytes + 2) / 3 + FRAMING_TOKENS;
    }

    /** The most one attempt of a job may use: its input, and every output token it asks for, thinking included. */
    public static long attemptEstimate(long inputBytes, int count) {
        return inputEstimate(inputBytes) + ClaudeApi.maxTokens(count);
    }

    /** The most a job may use: every one of its attempts in full. */
    public static long estimate(long inputBytes, int count) {
        return estimate(inputBytes, ClaudeApi.maxTokens(count), 0);
    }

    /**
     * What one attempt's input may come to, as a request reserves it: the text's estimate and {@code pdfPages} pages of
     * PDF documents at {@value ClaudeApi#TOKENS_PER_PDF_PAGE} tokens a page. The provider's count of a request with
     * documents may not be above it (the job fails before sending), so no attempt uses more than was reserved.
     */
    public static long reservedInput(long inputBytes, int pdfPages) {
        return inputEstimate(inputBytes) + (long) ClaudeApi.TOKENS_PER_PDF_PAGE * pdfPages;
    }

    /**
     * The most a job may use: every one of its attempts with its whole input ({@code inputBytes} of text and
     * {@code pdfPages} pages of PDF documents, at {@value ClaudeApi#TOKENS_PER_PDF_PAGE} tokens a page) and all the
     * {@code maxTokens} output it asks for.
     */
    public static long estimate(long inputBytes, int maxTokens, int pdfPages) {
        return AiQuestionsJob.ATTEMPTS * (reservedInput(inputBytes, pdfPages) + maxTokens);
    }

    /** Every limit the request would pass, with when each passes. */
    public static List<Violation> problems(Settings settings, Standing standing, long estimate, Instant now,
        ZoneId zone) {
        List<Violation> problems = new ArrayList<>();
        if (standing.quizBusy()) {
            problems.add(new Violation(null, AiCodes.QUIZ_BUSY, "The quiz has an AI job that has not finished",
                Map.of()));
        } else if (standing.openJobs() >= AiQuestionsJob.MAX_OPEN) {
            problems.add(new Violation(null, AiCodes.TOO_MANY_OPEN, "At most " + AiQuestionsJob.MAX_OPEN
                + " AI jobs may run at a time", Map.of("max", AiQuestionsJob.MAX_OPEN)));
        }
        if (standing.jobsToday() >= settings.dailyJobs()) {
            problems.add(new Violation(null, AiCodes.DAILY_LIMIT, "At most " + settings.dailyJobs()
                + " AI requests a day", Map.of("max", settings.dailyJobs(), "resetsAt",
                nextDay(now, zone).toString())));
        }
        if (standing.monthTokens() + standing.openEstimate() + estimate > settings.monthlyTokens()) {
            problems.add(new Violation(null, AiCodes.MONTHLY_LIMIT, "The month's AI tokens are used up",
                Map.of("max", settings.monthlyTokens(), "used", standing.monthTokens(), "resetsAt",
                    nextMonth(now, zone).toString())));
        }
        if (standing.globalTokens() + standing.globalOpenEstimate() + estimate > settings.globalMonthlyTokens()) {
            problems.add(new Violation(null, AiCodes.GLOBAL_LIMIT, "AI generation is used up for this month",
                Map.of("resetsAt", nextMonth(now, zone).toString())));
        }
        return List.copyOf(problems);
    }

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM");

    /** The month of {@code at} in the zone, as {@code QbAiUsage.month} holds it ({@code 2026-10}). */
    public static String month(Instant at, ZoneId zone) {
        return MONTH.format(at.atZone(zone));
    }

    /** The start of the day of {@code at} in the zone. */
    public static Instant dayStart(Instant at, ZoneId zone) {
        return at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }

    public static Instant nextDay(Instant at, ZoneId zone) {
        return at.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
    }

    public static Instant nextMonth(Instant at, ZoneId zone) {
        LocalDate day = at.atZone(zone).toLocalDate();
        return day.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant();
    }

    private AiLimits() {}
}
