package com.jabiz.runtime.job;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A run that ends while the application is stopping must not try to schedule its successor on the executor that
 * is shutting down: that raised {@code TaskRejectedException}, logged as ERROR by Spring's task error handler.
 */
class JobSchedulerShutdownTest {

    record In(Instant at) {}

    static final ProcessDefinition<In, In, ProcessContext> TICK = ProcessDefinition.single("TICK", 1,
        In.class, In.class, (in, ctx) -> in).asInternal();

    @SuppressWarnings("unchecked")
    @Test
    void run_ending_during_shutdown_does_not_reschedule_or_log_errors() throws Exception {
        JobDefinition<In> job = JobDefinition.cron("tick", "* * * * * *", ZoneOffset.UTC, TICK, In::new);
        ObjectProvider<JobDefinition<?>> beans = mock(ObjectProvider.class);
        when(beans.orderedStream()).thenReturn(Stream.of(job));
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobRunner runner = mock(JobRunner.class);
        when(runner.run(any(), any())).thenAnswer(call -> {
            running.countDown();
            release.await(10, TimeUnit.SECONDS);
            return JobRunner.Outcome.SUCCEEDED;
        });
        JobScheduler scheduler = new JobScheduler(new JobRegistry(beans), runner, Clock.systemUTC(),
            true, 2, Duration.ofSeconds(10));

        Logger springLog = (Logger) LoggerFactory.getLogger("org.springframework.scheduling.support.TaskUtils$LoggingErrorHandler");
        Logger ownLog = (Logger) LoggerFactory.getLogger(JobScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        springLog.addAppender(appender);
        ownLog.addAppender(appender);
        try {
            scheduler.start();
            assertThat(running.await(5, TimeUnit.SECONDS)).as("job fired").isTrue();
            Thread stopper = new Thread(scheduler::stop);
            stopper.start();
            // stop() waits for the running task; wait until the executor is shut down, then let the run end
            ThreadPoolTaskScheduler tasks = executor(scheduler);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!tasks.getScheduledThreadPoolExecutor().isShutdown() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(tasks.getScheduledThreadPoolExecutor().isShutdown()).isTrue();
            release.countDown();
            stopper.join(10_000);
            assertThat(tasks.getScheduledThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            springLog.detachAppender(appender);
            ownLog.detachAppender(appender);
        }
        List<ILoggingEvent> errors = appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.ERROR)).toList();
        assertThat(errors).as("errors logged during shutdown").isEmpty();
    }

    private static ThreadPoolTaskScheduler executor(JobScheduler scheduler) throws Exception {
        Field field = JobScheduler.class.getDeclaredField("scheduler");
        field.setAccessible(true);
        return (ThreadPoolTaskScheduler) field.get(scheduler);
    }
}
