package com.jabiz.runtime.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Settings of file storage ({@code jabiz.files.*}, docs/design/14-files.md sections 5 and 6).
 *
 * @param maxRequestBytes         largest upload request; a declared {@code Content-Length} above it is refused at once
 * @param uploadRatePerMinute     uploads one actor may start per minute (in-process token bucket)
 * @param orphanAfter             how old an unreferenced file must be before the sweep deletes it
 * @param orphanObjectGrace       how old an object without a row must be before the sweep deletes it
 * @param sweepBatchSize          files one sweep run deletes at most
 * @param sweepCron               when the sweep job runs (six-field Spring cron, UTC)
 * @param imageConcurrency        images processed at once (each holds several full-size bitmaps); default half the
 *                                processors, at least one
 * @param local                   the local directory store
 */
@ConfigurationProperties("jabiz.files")
public record FileProperties(DataSize maxRequestBytes, Integer uploadRatePerMinute, Duration orphanAfter,
    Duration orphanObjectGrace, Integer sweepBatchSize, String sweepCron, Integer imageConcurrency, Local local) {

    public FileProperties {
        maxRequestBytes = maxRequestBytes == null ? DataSize.ofMegabytes(100) : maxRequestBytes;
        uploadRatePerMinute = uploadRatePerMinute == null ? 30 : uploadRatePerMinute;
        orphanAfter = orphanAfter == null ? Duration.ofHours(24) : orphanAfter;
        orphanObjectGrace = orphanObjectGrace == null ? Duration.ofHours(1) : orphanObjectGrace;
        sweepBatchSize = sweepBatchSize == null ? 500 : sweepBatchSize;
        sweepCron = sweepCron == null || sweepCron.isBlank() ? "0 17 3 * * *" : sweepCron;
        imageConcurrency = imageConcurrency == null || imageConcurrency < 1
            ? Math.max(1, Runtime.getRuntime().availableProcessors() / 2) : imageConcurrency;
        local = local == null ? new Local(null) : local;
    }

    /**
     * @param root the directory holding the objects; no default: an application with file policies must set it
     *             (startup check), since a default would put personal data somewhere nobody chose
     */
    public record Local(String root) {

        public boolean configured() {
            return root != null && !root.isBlank();
        }

        public Path rootPath() {
            return Path.of(root).toAbsolutePath().normalize();
        }
    }
}
