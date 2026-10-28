package com.jabiz.runtime.erasure;

import com.jabiz.security.erasure.ErasedEntity;
import com.jabiz.security.erasure.PersonalDataEraser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The application's {@link PersonalDataEraser} beans (decision D62 item 7), in bean order; their entities in the order
 * declared, which is the order the account erasure removes and clears them.
 */
@Component
public class PersonalDataErasers {

    private final List<PersonalDataEraser> erasers;

    public PersonalDataErasers(ObjectProvider<PersonalDataEraser> beans) {
        this.erasers = beans.orderedStream().toList();
    }

    public List<PersonalDataEraser> all() {
        return erasers;
    }

    /** Every declared entity, eraser by eraser. */
    public List<ErasedEntity> entities() {
        return erasers.stream().flatMap(eraser -> eraser.entities().stream()).toList();
    }
}
