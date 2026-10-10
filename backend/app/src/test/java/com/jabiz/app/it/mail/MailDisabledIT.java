package com.jabiz.app.it.mail;

import com.jabiz.app.it.fixture.ItMailFixtures;
import com.jabiz.app.it.security.SecurityItSupport;
import com.jabiz.runtime.event.OutboxDeliverer;
import com.jabiz.runtime.task.MailMessage;
import com.jabiz.runtime.task.NotificationSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With mail off (the default, decision D35 item 6) messages are recorded as usual and their attempt is
 * {@code SKIPPED}: nothing is handed to the sender, and the message is not tried again.
 */
@SpringBootTest
class MailDisabledIT extends SecurityItSupport {

    @TestConfiguration
    static class RecordingSender {

        static final List<String> SENT = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        NotificationSender recordingMailSender() {
            return new NotificationSender() {
                @Override
                public void send(String to, String subject, String body) {
                    SENT.add(to);
                }

                @Override
                public void send(MailMessage message) {
                    SENT.add(message.to());
                }
            };
        }
    }

    @Autowired
    OutboxDeliverer deliverer;

    @Test
    @SuppressWarnings("unchecked")
    void messagesAreRecordedAndSkipped() {
        String address = unique("off").toLowerCase() + "@example.com";
        Map<String, Object> user = new HashMap<>(Map.of("userName", unique("off"), "email", address));
        Map<String, Object> created = post("/api/processes/SEC_USER_CREATE/latest", admin(), user)
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        String userId = (String) ((Map<String, Object>) created.get("output")).get("userId");
        Map<String, Object> input = new HashMap<>(Map.of("template", "verify", "userId", userId, "value", "Ida"));
        post("/api/processes/IT_MAIL_SEND/latest", bearer(ItMailFixtures.PERMISSION), input).expectStatus().isOk();

        deliverer.deliverPending().block();
        deliverer.deliverPending().block();

        assertThat(query("SELECT a.outcome, a.detail FROM sys_mail_attempt a JOIN sys_mail_message m"
            + " ON m.message_id = a.message_id WHERE m.address = ?", address)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("outcome", "SKIPPED")
                .containsEntry("detail", "MAIL_DISABLED"));
        assertThat(query("SELECT * FROM sys_mail_token WHERE address = ?", address)).isEmpty();
        assertThat(RecordingSender.SENT).doesNotContain(address);
    }
}
