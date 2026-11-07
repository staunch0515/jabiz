package com.jabiz.runtime.mail;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
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
 * Refuses every write of a {@code MailLayout} but those the platform's mail layout processes make, which carry a
 * {@link MailLayoutWriteGrant} for that language and operation (decision D72 item 3): the dataset API, the generic
 * entity processes, reverts and any other process end here (422 {@code MAIL_LAYOUT_NEEDS_APPROVAL}). A layout of its
 * own passes only with the grant of an approved controlled change, as the layout is on every mail, verification and
 * password reset included; going back to the default does not need one.
 */
@Component
class MailLayoutGuard implements TemporalWriteGuard {

    @Override
    public List<Violation> check(EntityDefinition def, Timeline timeline, List<PlannedVersion> versions,
        Operation operation, Object grant) {
        if (!MailLayoutEntities.ENTITY.equals(def.name)) {
            return List.of();
        }
        Object locale = timeline.versions().stream().map(EntityVersion::state)
            .map(state -> state.get(MailLayoutEntities.LOCALE)).filter(Objects::nonNull).findFirst()
            .orElseGet(() -> versions.isEmpty() ? null : versions.getFirst().state().get(MailLayoutEntities.LOCALE));
        if (!(grant instanceof MailLayoutWriteGrant granted) || !granted.allows(locale, operation.processSeqId())) {
            return List.of(refused(locale, "The mail layout changes only through MAIL_LAYOUT_RESET or a controlled "
                + "change"));
        }
        boolean custom = !versions.isEmpty()
            && MailTextEntities.CUSTOM.equals(versions.getFirst().state().get(MailLayoutEntities.KIND));
        if (custom && !granted.approved()) {
            return List.of(refused(locale, "The mail layout changes only through a controlled change another "
                + "administrator publishes"));
        }
        return List.of();
    }

    private static Violation refused(Object locale, String message) {
        return new Violation("locale", PlatformErrorCodes.MAIL_LAYOUT_NEEDS_APPROVAL, message,
            Map.of("locale", String.valueOf(locale)));
    }
}
