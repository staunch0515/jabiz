package com.jabiz.runtime.param;

import com.jabiz.param.ControlledParams;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The declared {@link ControlledParams} beans (decision D40): which business parameters change only with four eyes.
 * Malformed declarations are reported by {@link ParamChecks}.
 */
@Component
public class ControlledParamRegistry {

    private final List<ControlledParams> declarations;

    public ControlledParamRegistry(ObjectProvider<ControlledParams> declarations) {
        this.declarations = declarations.orderedStream().toList();
    }

    public List<ControlledParams> declarations() {
        return declarations;
    }

    public boolean controls(Object key) {
        return key instanceof String text && declarations.stream().anyMatch(d -> d.controls(text));
    }
}
