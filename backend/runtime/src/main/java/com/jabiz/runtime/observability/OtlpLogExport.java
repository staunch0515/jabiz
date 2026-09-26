package com.jabiz.runtime.observability;

import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sends log records to the OTLP log endpoint as well as to the console (docs/design/13-observability-ops.md). Spring
 * Boot exports OpenTelemetry log records but does not feed Logback into them; this adds OpenTelemetry's Logback
 * appender to the root logger. Active only when {@code management.opentelemetry.logging.export.otlp.endpoint} is
 * set. The MDC (request id, trace and span ids) travels with every record; log messages never contain request
 * bodies (docs/design/10-security.md section 6).
 */
@Component
@ConditionalOnProperty("management.opentelemetry.logging.export.otlp.endpoint")
public class OtlpLogExport implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(OtlpLogExport.class);
    static final String APPENDER = "OTEL";

    private final ObjectProvider<OpenTelemetry> openTelemetry;

    public OtlpLogExport(ObjectProvider<OpenTelemetry> openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        OpenTelemetry sdk = openTelemetry.getIfAvailable();
        if (sdk == null || !(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            log.warn("OTLP log export is configured but OpenTelemetry or Logback is not available; logs stay local");
            return;
        }
        ch.qos.logback.classic.Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER) == null) {
            OpenTelemetryAppender appender = new OpenTelemetryAppender();
            appender.setContext(context);
            appender.setName(APPENDER);
            appender.setCaptureMdcAttributes("*");
            appender.start();
            root.addAppender(appender);
        }
        OpenTelemetryAppender.install(sdk);
    }
}
