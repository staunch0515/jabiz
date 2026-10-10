package com.jabiz.runtime.mail;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.steps.CheckedStep;
import com.jabiz.runtime.security.secret.SingleUseSecrets;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Uses a one-time token of a mail in a process (docs/design/18-numbering-approvals-tasks.md section 5.6; decision
 * D35 item 3), such as the link that verifies an address or resets a password:
 * <pre>{@code
 * .step("Use the link", MailTokens.consume("verify", ctx -> input(ctx).token(), "used"))
 * }</pre>
 * The token is found by its SHA-256; it must be of the purpose, unexpired at the operation time, the latest token of
 * that purpose sent to its recipient (a later mail supersedes it), and unused. Its use is recorded in the process's
 * transaction, whose primary key allows it once. On success the context holds a {@link Consumed} under the target key;
 * otherwise the process is rejected with {@code TOKEN_INVALID} (422), whatever the reason, and the key stays empty.
 */
@Component
public class MailTokens<C extends ProcessContext> implements StepHandler<MailTokens.Metadata<C>, C>,
    CheckedStep<MailTokens.Metadata<C>> {

    /** Longest token looked up: tokens are 43 characters. */
    static final int MAX_TOKEN_LENGTH = 128;

    /** A used token: to whom its mail went. */
    public record Consumed(String messageId, String userId, String address) {}

    public record Metadata<C>(String purpose, Function<C, String> token, String targetKey) {
        public Metadata {
            Objects.requireNonNull(purpose, "purpose must not be null");
            Objects.requireNonNull(token, "token must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> consume(String purpose,
        Function<C, String> token, String targetKey) {
        return StepSpec.of(MailTokens.class, new Metadata<>(purpose, token, targetKey));
    }

    private static final String FIND = """
        SELECT t.message_id, t.purpose, t.user_id, t.address, t.expires_time,
               NOT EXISTS (SELECT 1 FROM sys_mail_token n
                            WHERE n.purpose = t.purpose AND n.issue_seq > t.issue_seq
                              AND (n.user_id = t.user_id
                                   OR (t.user_id IS NULL AND n.user_id IS NULL AND n.address = t.address))) AS latest
        FROM sys_mail_token t WHERE t.token_hash = :hash""";

    /** Not a plain insert: a key violation would abort the process's transaction. */
    private static final String USE = """
        INSERT INTO sys_mail_token_use (token_hash, used_time, process_seq_id) VALUES (:hash, :time, :seq)
        ON CONFLICT (token_hash) DO NOTHING RETURNING token_hash""";

    private final MailTemplates templates;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public MailTokens(MailTemplates templates, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.templates = templates;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            String token = metadata.token().apply(ctx);
            if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
                return invalid(ctx, "missing or malformed");
            }
            String hash = SingleUseSecrets.sha256Hex(token);
            StorageEngine engine = storages.getEngine(poolRef);
            return engine.select(FIND, Map.of("hash", BoundValue.of(hash)))
                .next()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(found -> {
                    if (found.isEmpty()) {
                        return invalid(ctx, "unknown");
                    }
                    Map<String, Object> row = found.get();
                    if (!metadata.purpose().equals(Rows.string(row.get("purpose")))) {
                        return invalid(ctx, "of another purpose");
                    }
                    if (!ctx.opTime().isBefore(Rows.instant(row.get("expires_time")))) {
                        return invalid(ctx, "expired");
                    }
                    if (!Boolean.TRUE.equals(row.get("latest"))) {
                        return invalid(ctx, "superseded");
                    }
                    Map<String, BoundValue> use = new LinkedHashMap<>();
                    use.put("hash", BoundValue.of(hash));
                    use.put("time", BoundValue.of(ctx.opTime()));
                    use.put("seq", BoundValue.of(ctx.processSeqId()));
                    return engine.select(USE, use)
                        .hasElements()
                        .flatMap(inserted -> {
                            if (!inserted) {
                                return invalid(ctx, "used");
                            }
                            UUID user = Rows.uuid(row.get("user_id"));
                            ctx.put(metadata.targetKey(), new Consumed(Rows.string(row.get("message_id")),
                                user == null ? null : user.toString(), Rows.string(row.get("address"))));
                            return Mono.<Void>empty();
                        });
                });
        });
    }

    private static Mono<Void> invalid(ProcessContext ctx, String why) {
        // Why it is invalid stays in the operation record: the caller learns no more than that it is.
        ctx.reject(new Violation(null, PlatformErrorCodes.TOKEN_INVALID, "Invalid token (" + why + ")"));
        return Mono.empty();
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        List<String> problems = new ArrayList<>();
        boolean declared = templates.all().stream()
            .anyMatch(template -> template.tokens().containsKey(metadata.purpose()));
        if (!declared) {
            problems.add("uses tokens of purpose " + metadata.purpose() + ", which no mail template declares");
        }
        return problems;
    }
}
