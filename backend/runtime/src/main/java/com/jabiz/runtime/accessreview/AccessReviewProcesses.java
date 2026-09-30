package com.jabiz.runtime.accessreview;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.report.ReportRun;
import com.jabiz.runtime.report.ReportRuns;
import com.jabiz.runtime.security.SecurityPermissions;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@code ACCESS_REVIEW_SIGN_OFF} (docs/design/10-security.md section 13.3, decision D28 item 9): a reviewer signs the
 * access of a period. The access report is issued first with {@code REPORT_ISSUE} as of the period's end; the
 * sign-off keeps a reference to it and its content hash, the count and hash of the period's security changes from
 * the audit trail, the segregation-of-duties conflicts at signing with their hash, the reviewer and the comment.
 * Needs {@value SecurityPermissions#ACCESS_REVIEW_SIGN} and a recent second factor.
 */
@Configuration
public class AccessReviewProcesses {

    public static final String SIGN_OFF = "ACCESS_REVIEW_SIGN_OFF";

    static final String INPUT = "input";
    static final String OUTPUT = "output";

    /** Longest comment kept. */
    public static final int MAX_COMMENT = 2000;

    /**
     * @param periodFrom    start of the reviewed period (inclusive)
     * @param periodTo      end of the reviewed period (exclusive); it must have passed
     * @param reportRunId   the issued access report ({@value AccessReviews#ACCESS_TEMPLATE}) read as of
     *                      {@code periodTo}
     * @param reviewComment the reviewer's findings and conclusion
     */
    public record SignOffInput(@NotNull Instant periodFrom, @NotNull Instant periodTo, @NotBlank String reportRunId,
        @NotBlank @Size(max = MAX_COMMENT) String reviewComment) {}

    public record SignOffOutput(String reviewId, String reportHash, int changesCount, String changesHash,
        int conflictsCount, String conflictsHash) {}

    @Bean
    ProcessDefinition<SignOffInput, SignOffOutput, ProcessContext> accessReviewSignOffProcess() {
        return ProcessDefinition.define(SIGN_OFF, 1, SignOffInput.class, SignOffOutput.class, ProcessContext.class,
            pb -> pb
                .description("Signs the access review of a period: the issued access report, the period's security "
                    + "changes and the segregation-of-duties conflicts, with the reviewer's comment.")
                .permissions(SecurityPermissions.ACCESS_REVIEW_SIGN)
                .requiresMfa(MfaRequirement.ALWAYS)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, SignOffOutput.class))
                .step("Sign the review", SignOff.class, NoMetadata.INSTANCE));
    }

    /** The step of {@code ACCESS_REVIEW_SIGN_OFF}. */
    @Component
    public static class SignOff implements StepHandler<NoMetadata, ProcessContext> {

        private final AccessReviews reviews;
        private final ReportRuns runs;
        private final EntityIdGenerator ids;

        public SignOff(AccessReviews reviews, ReportRuns runs, EntityIdGenerator ids) {
            this.reviews = reviews;
            this.runs = runs;
            this.ids = ids;
        }

        @Override
        public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
            return Mono.defer(() -> {
                SignOffInput input = ctx.get(INPUT, SignOffInput.class);
                if (!input.periodFrom().isBefore(input.periodTo()) || input.periodTo().isAfter(ctx.opTime())) {
                    return Mono.error(refused(PlatformErrorCodes.ACCESS_REVIEW_PERIOD, "periodTo",
                        "The period must end after it starts, and have ended"));
                }
                UUID runId;
                try {
                    runId = UUID.fromString(input.reportRunId());
                } catch (IllegalArgumentException e) {
                    return Mono.error(refused(PlatformErrorCodes.ACCESS_REVIEW_REPORT, "reportRunId",
                        "Not a report run"));
                }
                return runs.find(runId)
                    .filter(run -> isAccessReport(run, input.periodTo()))
                    .switchIfEmpty(Mono.error(() -> refused(PlatformErrorCodes.ACCESS_REVIEW_REPORT, "reportRunId",
                        "Run " + input.reportRunId() + " is not an intact access report as of " + input.periodTo())))
                    .flatMap(run -> reviews.changes(input.periodFrom(), input.periodTo())
                        .zipWith(reviews.conflicts())
                        .flatMap(found -> {
                            UUID reviewId = UUID.fromString(String.valueOf(ids.next(null)));
                            AccessReviews.Review review = new AccessReviews.Review(reviewId, input.periodFrom(),
                                input.periodTo(), run.runId(), run.contentHash(), found.getT1().items().size(),
                                found.getT1().hash(), found.getT2().items(), found.getT2().hash(),
                                ctx.request().actorId(), input.reviewComment().strip(), ctx.opTime(),
                                ctx.processSeqId());
                            return reviews.insert(review).then(Mono.fromRunnable(() -> ctx.put(OUTPUT,
                                new SignOffOutput(reviewId.toString(), run.contentHash(),
                                    review.changesCount(), review.changesHash(), review.conflicts().size(),
                                    review.conflictsHash()))));
                        }));
            });
        }

        /** The access template, read as of the end of the period, its rows as issued. */
        private static boolean isAccessReport(ReportRun run, Instant periodTo) {
            return AccessReviews.ACCESS_TEMPLATE.equals(run.templateId()) && periodTo.equals(run.readAt())
                && run.intact();
        }

        private static BusinessRuleViolationException refused(String code, String field, String message) {
            return new BusinessRuleViolationException(new Violation(field, code, message, Map.of()));
        }
    }
}
