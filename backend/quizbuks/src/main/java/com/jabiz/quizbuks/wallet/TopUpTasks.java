package com.jabiz.quizbuks.wallet;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.inbound.InboundEntities;
import com.jabiz.runtime.task.TaskSpec;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tasks of top-ups for the finance administrators ({@code qb.reconcile}): what Stripe reported that QuizBuks did
 * not credit, take back or record, and why. The title is one message per reason ({@code task.qb.topup-check.<reason>},
 * {@code task.qb.refund-check.<reason>}), so each reader sees the reason in their language. The source key names what
 * caused the task (the Stripe event, the job), stable when the same thing is processed again.
 */
final class TopUpTasks {

    static final String TOPUP_CHECK = "qb.topup-check";
    static final String REFUND_CHECK = "qb.refund-check";
    private static final int MAX_SHOWN = 40;
    private static final int MAX_SOURCE = 200;

    /** A top-up's task ({@link TopUpChecks#REASONS}). */
    static TaskSpec topUp(String reason, String topUpId, BigDecimal amount, String currency, String source,
        String inboundEventId) {
        return task(TOPUP_CHECK, "task.qb.topup-check." + reason, topUpId, amount, currency, source, inboundEventId);
    }

    /** A refund's or a dispute's task ({@link RefundMath#REASONS}). */
    static TaskSpec refund(String reason, String topUpId, BigDecimal amount, String currency, String source,
        String inboundEventId) {
        return task(REFUND_CHECK, "task.qb.refund-check." + reason, topUpId, amount, currency, source, inboundEventId);
    }

    private static TaskSpec task(String type, String titleKey, String topUpId, BigDecimal amount, String currency,
        String source, String inboundEventId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("topUp", shown(topUpId));
        params.put("amount", amount == null ? "-" : shown(amount.toPlainString()));
        params.put("currency", shown(currency));
        TaskSpec task = TaskSpec.forPermission(type, titleKey, params, QbPermissions.RECONCILE).source(source);
        if (topUpId != null) {
            return task.about(QbTopUps.TOPUP, topUpId);
        }
        return inboundEventId == null ? task : task.about(InboundEntities.EVENT, inboundEventId);
    }

    /** {@code stripe:<event id>}: the same task key when the same event is processed again; a long id by its hash. */
    static String eventSource(String externalEventId) {
        return source("stripe:", externalEventId);
    }

    /** {@code topup:<top-up>:<what>}. */
    static String jobSource(String topUpId, String what) {
        return source("topup:", topUpId + ":" + what);
    }

    private static String source(String prefix, String key) {
        String text = prefix + key;
        if (text.length() <= MAX_SOURCE) {
            return text;
        }
        try {
            return prefix + "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A value of the sender as a title may show it: printable, at most 40 characters; "-" for none. */
    static String shown(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        StringBuilder kept = new StringBuilder();
        value.codePoints().filter(c -> !Character.isISOControl(c)).forEach(kept::appendCodePoint);
        String text = kept.toString().strip();
        if (text.isEmpty()) {
            return "-";
        }
        return text.length() <= MAX_SHOWN ? text : text.substring(0, MAX_SHOWN) + "…";
    }

    private TopUpTasks() {}
}
