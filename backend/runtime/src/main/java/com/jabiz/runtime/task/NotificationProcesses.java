package com.jabiz.runtime.task;

import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.MessageTemplate;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.RetryPolicy;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * E-mail about new tasks (docs/design/18-numbering-approvals-tasks.md section 5.4). With {@code jabiz.mail.enabled}
 * (off by default) the consumer {@value #CONSUMER} runs {@code TASK_NOTIFY} once for every {@code jabiz.task.created}
 * event: it records one {@code Notification} per recipient with an address, in its transaction, and sends them after
 * the commit ({@link SendNotifications}), retried by {@link #RETRY} with every attempt recorded. The text is the task's
 * title in the platform's default language and a link to the task.
 */
@Configuration
public class NotificationProcesses {

    public static final String NOTIFY = "TASK_NOTIFY";
    public static final String CONSUMER = "jabiz.task-notifications";

    /** Five attempts, the first retry after a second, then doubling. */
    static final RetryPolicy RETRY = new RetryPolicy(5, Duration.ofSeconds(1));

    public record NotifyInput(@NotNull UUID taskId) {}

    public record NotifyOutput(int notifications) {}

    private static final String TASK_ID = "taskId";
    private static final String TASK = "task";
    private static final String RECIPIENTS = "recipients";
    private static final String IDS = "ids";
    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};
    private static final StepSpec<SendNotifications.Metadata, ProcessContext> SEND = SendNotifications.of(IDS);

    static ProcessDefinition<NotifyInput, NotifyOutput, ProcessContext> notify(MessageCatalog messages, JsonMapper json,
        String baseUrl) {
        return ProcessDefinition.define(NOTIFY, 1, NotifyInput.class, NotifyOutput.class, ProcessContext.class, pb -> pb
            .description("Notifies the assignees of a new task by e-mail.")
            .permissions(TaskPermissions.NOTIFY)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(TASK_ID, input.taskId());
                return ctx;
            })
            .outputMapper(ctx -> new NotifyOutput(ctx.contains(IDS) ? ((List<?>) ctx.get(IDS)).size() : 0))
            .step("Load the task", LoadEntity.by(TaskEntities.TASK_DATASET, TASK_ID, TASK))
            .step("Find the recipients", FindRecipients.of(TASK, RECIPIENTS))
            .compute("Record the notifications", (metadata, ctx) -> record(ctx, messages, json, baseUrl))
            .afterCommit("Send them", SEND.handlerClass(), SEND.metadata(), RETRY));
    }

    @SuppressWarnings("unchecked")
    private static void record(ProcessContext ctx, MessageCatalog messages, JsonMapper json, String baseUrl) {
        EntityInstance task = ctx.get(TASK, EntityInstance.class);
        if (!TaskEntities.OPEN.equals(task.get("status"))) {
            return;
        }
        String key = task.get("titleKey");
        Map<String, String> params = json.readValue(task.<String>get("titleParams"), PARAMS);
        String title = messages.find(key, messages.defaultLocale())
            .map(text -> MessageTemplate.format(text, params)).orElse(key);
        String subject = title.length() > 300 ? title.substring(0, 300) : title;
        String link = task.get("link");
        String body = link == null ? title : title + "\n\n" + baseUrl + link;
        List<String> ids = new ArrayList<>();
        for (FindRecipients.Recipient recipient : (List<FindRecipients.Recipient>) ctx.get(RECIPIENTS)) {
            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("taskId", task.id());
            notification.put("channel", "EMAIL");
            notification.put("recipientId", recipient.userId());
            notification.put("address", recipient.address());
            notification.put("subject", subject);
            notification.put("body", body.length() > 4000 ? body.substring(0, 4000) : body);
            notification.put("createdTime", ctx.opTime());
            notification.put("processSeqId", BigDecimal.valueOf(ctx.processSeqId()));
            ids.add(String.valueOf(ctx.changes().insert(TaskEntities.NOTIFICATION, notification)));
        }
        ctx.put(IDS, List.copyOf(ids));
    }

    @Bean
    ProcessDefinition<NotifyInput, NotifyOutput, ProcessContext> taskNotifyProcess(MessageCatalog messages,
        JsonMapper json, @Value("${jabiz.mail.base-url:}") String baseUrl) {
        return notify(messages, json, baseUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "jabiz.mail.enabled", havingValue = "true")
    EventSubscription<NotifyInput> taskNotificationSubscription(
        ProcessDefinition<NotifyInput, NotifyOutput, ProcessContext> taskNotifyProcess) {
        return EventSubscription.of(CONSUMER, TaskWriter.CREATED, taskNotifyProcess, NotificationProcesses::input);
    }

    private static NotifyInput input(DomainEvent event) {
        return new NotifyInput(UUID.fromString(String.valueOf(event.payload().get("taskId"))));
    }
}
