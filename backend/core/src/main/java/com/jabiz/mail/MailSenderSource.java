package com.jabiz.mail;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where the platform finds the SMTP settings a mail names by its sender key (docs/design/18-numbering-approvals-tasks.md
 * section 5.6, decision D56): an application declares one bean naming the entity that holds its senders and the field
 * that is the key ({@code SendMail.via(ctx -> key)}); the delivery reads the row (the latest version of a temporal
 * entity, deleted ones count as missing) and asks this for the settings. Synchronous and pure: the row's attributes
 * are given by field name, as the entity declares them, with the password still sealed.
 *
 * <pre>{@code
 * @Bean
 * MailSenderSource merchantMailServers() {
 *     return MailSenderSource.of("MerchantMailServer", "sponsorId", row -> row.get("verifiedAt") == null
 *         ? Optional.empty()
 *         : Optional.of(new MailSenderSettings((String) row.get("host"), ((Number) row.get("port")).intValue(), ...)));
 * }
 * }</pre>
 */
public interface MailSenderSource {

    /** The entity holding the senders. */
    String entity();

    /** The field of {@link #entity()} a sender key is looked up by; its values are unique. */
    String keyField();

    /**
     * The settings of a row of the entity, or none when the row is no usable sender (not verified yet, say): the
     * mail then fails with that reason and is not sent through the platform's server either.
     */
    Optional<MailSenderSettings> settings(Map<String, Object> attributes);

    static MailSenderSource of(String entity, String keyField,
        Function<Map<String, Object>, Optional<MailSenderSettings>> settings) {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(keyField, "keyField must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        return new MailSenderSource() {
            @Override
            public String entity() {
                return entity;
            }

            @Override
            public String keyField() {
                return keyField;
            }

            @Override
            public Optional<MailSenderSettings> settings(Map<String, Object> attributes) {
                return settings.apply(attributes);
            }

            @Override
            public String toString() {
                return "MailSenderSource[" + entity + "." + keyField + "]";
            }
        };
    }
}
