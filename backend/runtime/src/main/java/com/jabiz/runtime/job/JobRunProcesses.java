package com.jabiz.runtime.job;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * {@code JOB_RUN_REQUEST} (decision D77; docs/design/11-ledger-events-jobs.md section 4.2): an administrator asks for a
 * past scheduled time of a job to run again, with the reason. The process checks the time and records the request (its
 * operation carries the reason); {@link JobRunRequests} holds the job's lock around it and starts the run once it has
 * committed. Only {@code POST /api/jobs/{job}/runs} runs it ({@code entryOnly()}).
 */
@Configuration
public class JobRunProcesses {

    public static final String REQUEST = "JOB_RUN_REQUEST";

    /** How many of a job's latest scheduled times the jobs API offers to run again, besides those of its runs. */
    static final int OFFERED_TIMES = 20;
    /** The first stretch searched back for a job's latest scheduled times; widened fourfold up to the max age. */
    private static final Duration FIRST_STRETCH = Duration.ofMinutes(10);

    static final String INPUT = "input";

    /**
     * @param job           the job's name
     * @param scheduledTime a past time of its schedule
     * @param reason        why it runs again
     */
    public record RunRequestInput(@NotBlank @Size(max = 80) String job, @NotNull Instant scheduledTime,
        @NotBlank @Size(max = 2000) String reason) {}

    public record RunRequestOutput(String job, Instant scheduledTime) {}

    /**
     * Why {@code scheduledTime} cannot be run again by hand at {@code now}, or empty when it can: it must be a time of
     * the job's schedule, not to come, and no older than {@code maxAge}.
     */
    public static Optional<Violation> problem(JobDefinition<?> job, Instant scheduledTime, Instant now,
        Duration maxAge) {
        Map<String, Object> params = Map.of("job", job.name(), "scheduledTime", scheduledTime.toString());
        if (!isOccurrence(job, scheduledTime)) {
            return Optional.of(new Violation("scheduledTime", PlatformErrorCodes.JOB_RUN_NOT_SCHEDULED,
                "Job " + job.name() + " is not scheduled at " + scheduledTime, params));
        }
        if (scheduledTime.isAfter(now)) {
            return Optional.of(new Violation("scheduledTime", PlatformErrorCodes.JOB_RUN_NOT_DUE,
                "Scheduled time " + scheduledTime + " of job " + job.name() + " has not come yet", params));
        }
        if (scheduledTime.isBefore(now.minus(maxAge))) {
            return Optional.of(new Violation("scheduledTime", PlatformErrorCodes.JOB_RUN_TOO_OLD,
                "Scheduled time " + scheduledTime + " of job " + job.name() + " is older than " + maxAge,
                Map.of("job", job.name(), "scheduledTime", scheduledTime.toString(), "maxAgeDays", maxAge.toDays())));
        }
        return Optional.empty();
    }

    /** Whether the job's schedule names {@code time} (as it is now: its cron and zone may have changed since a run). */
    static boolean isOccurrence(JobDefinition<?> job, Instant time) {
        return time.equals(JobScheduler.nextTime(job, time.minusMillis(1)));
    }

    /**
     * The scheduled times the jobs API offers to run again, newest first: the latest {@value #OFFERED_TIMES} of the
     * schedule and those of {@code runTimes} it still names, all at or before {@code now} and no older than
     * {@code maxAge}.
     */
    static List<Instant> offeredTimes(JobDefinition<?> job, Instant now, Duration maxAge,
        Collection<Instant> runTimes) {
        Instant oldest = now.minus(maxAge);
        Deque<Instant> latest = new ArrayDeque<>();
        Duration stretch = FIRST_STRETCH.compareTo(maxAge) < 0 ? FIRST_STRETCH : maxAge;
        while (true) {
            latest.clear();
            Instant from = now.minus(stretch);
            // Inclusive of the start: the next time after a millisecond before it.
            for (Instant time = JobScheduler.nextTime(job, from.minusMillis(1)); time != null && !time.isAfter(now);
                time = JobScheduler.nextTime(job, time)) {
                latest.addLast(time);
                if (latest.size() > OFFERED_TIMES) {
                    latest.removeFirst();
                }
            }
            if (latest.size() >= OFFERED_TIMES || stretch.compareTo(maxAge) >= 0) {
                break;
            }
            Duration wider = stretch.multipliedBy(4);
            stretch = wider.compareTo(maxAge) < 0 ? wider : maxAge;
        }
        TreeSet<Instant> times = new TreeSet<>(latest);
        runTimes.stream().filter(time -> !time.isAfter(now) && !time.isBefore(oldest) && isOccurrence(job, time))
            .forEach(times::add);
        return List.copyOf(times.descendingSet());
    }

    @Bean
    ProcessDefinition<RunRequestInput, RunRequestOutput, ProcessContext> jobRunRequestProcess(JobRegistry jobs,
        Clock clock, @Value("${jabiz.jobs.manual-run.max-age:P90D}") Duration maxAge) {
        return ProcessDefinition.define(REQUEST, 1, RunRequestInput.class, RunRequestOutput.class,
            ProcessContext.class, pb -> pb
                .description("Asks for a past scheduled time of a job to run again, with the reason (decision D77).")
                .permissions(JobPermissions.RUN)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .entryOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> {
                    RunRequestInput input = ctx.get(INPUT, RunRequestInput.class);
                    return new RunRequestOutput(input.job(), input.scheduledTime());
                })
                .compute("Check the scheduled time", (metadata, ctx) -> {
                    RunRequestInput input = ctx.get(INPUT, RunRequestInput.class);
                    JobDefinition<?> job = jobs.find(input.job()).orElseThrow(() ->
                        new EntityNotFoundException("Job " + input.job() + " not found"));
                    Optional<Violation> problem = problem(job, input.scheduledTime(), clock.instant(), maxAge);
                    if (problem.isPresent()) {
                        throw new BusinessRuleViolationException(problem.get());
                    }
                }));
    }
}
