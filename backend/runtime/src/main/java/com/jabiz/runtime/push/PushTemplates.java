package com.jabiz.runtime.push;

import com.jabiz.push.PushTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** The push templates the application declares as beans (decision D69). Two of one name are a startup error. */
@Component
public class PushTemplates {

    private final List<PushTemplate> all;

    @org.springframework.beans.factory.annotation.Autowired
    public PushTemplates(ObjectProvider<PushTemplate> templates) {
        this.all = templates.orderedStream().toList();
    }

    PushTemplates(List<PushTemplate> templates) {
        this.all = List.copyOf(templates);
    }

    public List<PushTemplate> all() {
        return all;
    }

    public Optional<PushTemplate> find(String name) {
        return all.stream().filter(template -> template.name().equals(name)).findFirst();
    }
}
