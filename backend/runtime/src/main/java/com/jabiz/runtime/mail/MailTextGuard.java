package com.jabiz.runtime.mail;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.mail.MailTemplate;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.temporal.TemporalWriteGuard;
import com.jabiz.temporal.EntityVersion;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Refuses every write of a {@code MailText} but those the platform's mail text processes make, which carry a
 * {@link MailTextWriteGrant} for that template, language and operation (decision D72): the dataset API, the generic
 * entity processes, reverts and any other process end here (422 {@code MAIL_TEXT_NEEDS_APPROVAL}). A text of its own
 * for a template whose changes need approval ({@link MailTemplate#textChangeNeedsApproval()}) passes only with a
 * grant of an approved controlled change; going back to the default does not need one.
 */
@Component
class MailTextGuard implements TemporalWriteGuard {

    private final MailTemplates templates;

    MailTextGuard(MailTemplates templates) {
        this.templates = templates;
    }

    @Override
    public List<Violation> check(EntityDefinition def, Timeline timeline, List<PlannedVersion> versions,
        Operation operation, Object grant) {
        if (!MailTextEntities.ENTITY.equals(def.name)) {
            return List.of();
        }
        Object template = field(timeline, versions, MailTextEntities.TEMPLATE);
        Object locale = field(timeline, versions, MailTextEntities.LOCALE);
        if (!(grant instanceof MailTextWriteGrant granted) || !granted.allows(template, locale,
            operation.processSeqId())) {
            return List.of(refused(template, "Mail texts change only through MAIL_TEXT_SAVE, MAIL_TEXT_RESET or a "
                + "controlled change"));
        }
        boolean custom = !versions.isEmpty()
            && MailTextEntities.CUSTOM.equals(versions.getFirst().state().get(MailTextEntities.KIND));
        boolean needsApproval = templates.find(String.valueOf(template))
            .map(MailTemplate::textChangeNeedsApproval).orElse(true);
        if (custom && needsApproval && !granted.approved()) {
            return List.of(refused(template, "The texts of mail " + template + " change only through a controlled "
                + "change another administrator publishes"));
        }
        return List.of();
    }

    /** An immutable field, as stored or as written now. */
    private static Object field(Timeline timeline, List<PlannedVersion> versions, String name) {
        return timeline.versions().stream().map(EntityVersion::state).map(state -> state.get(name))
            .filter(Objects::nonNull).findFirst()
            .orElseGet(() -> versions.isEmpty() ? null : versions.getFirst().state().get(name));
    }

    private static Violation refused(Object template, String message) {
        return new Violation("template", PlatformErrorCodes.MAIL_TEXT_NEEDS_APPROVAL, message,
            Map.of("template", String.valueOf(template)));
    }
}
