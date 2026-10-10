package com.jabiz.runtime.param;

import com.jabiz.param.ControlledParams;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The declared {@link ControlledParams} beans (decision D40): which business parameters change only with four eyes.
 * Read once at startup; every temporal write of a parameter asks it. Malformed declarations are reported by
 * {@link ParamChecks}.
 */
@Component
public class ControlledParamRegistry {

    private final List<ControlledParams> declarations;
    private final Set<String> keys;

    public ControlledParamRegistry(ObjectProvider<ControlledParams> declarations) {
        this.declarations = declarations.orderedStream().toList();
        Set<String> all = new LinkedHashSet<>();
        this.declarations.forEach(declaration -> all.addAll(declaration.keys()));
        this.keys = Set.copyOf(all);
    }

    public List<ControlledParams> declarations() {
        return declarations;
    }

    public boolean controls(Object key) {
        return key instanceof String text && keys.contains(text);
    }
}
