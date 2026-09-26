package com.jabiz.runtime.job;

import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import io.r2dbc.spi.ConnectionFactory;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.r2dbc.R2dbcLockProvider;
import com.jabiz.runtime.observability.PlatformObservations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;

/** Wiring of scheduled jobs (docs/design/11-ledger-events-jobs.md section 4). */
@Configuration
public class JobConfig {

    /** Table of the cluster locks (platform migration V10). */
    public static final String LOCK_TABLE = "jabiz_shedlock";

    /** ShedLock over R2DBC, on the platform's own lock table. */
    @Bean
    LockProvider jobLockProvider(ConnectionFactory connections) {
        return new R2dbcLockProvider(connections, LOCK_TABLE);
    }

    /**
     * @param instanceId     names this instance in the run records; defaults to the JVM's {@code pid@host}
     * @param lockAtLeastFor how long a finished run keeps the lock, so that an instance whose clock is slightly behind
     *                       does not start the same scheduled time again (it would only replay it)
     */
    @Bean
    JobRunner jobRunner(LockProvider locks, ProcessExecutor executor, StorageAdapterRegistry storages,
        EntityIdGenerator ids, Clock clock,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${jabiz.jobs.instance-id:}") String instanceId,
        @Value("${jabiz.jobs.lock-at-least-for:30s}") Duration lockAtLeastFor, PlatformObservations observations) {
        String instance = instanceId.isBlank() ? ManagementFactory.getRuntimeMXBean().getName() : instanceId;
        return new JobRunner(locks, executor, storages, ids, clock, poolRef, instance, lockAtLeastFor, observations);
    }
}
