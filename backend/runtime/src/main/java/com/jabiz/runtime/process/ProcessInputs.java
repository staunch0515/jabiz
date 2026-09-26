package com.jabiz.runtime.process;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a JSON-shaped input into a process's input record and checks it with Bean Validation
 * (docs/design/06-process.md section 8): a body that does not convert is 400 {@code INVALID_VALUE}; broken
 * constraints are 400, {@code NotNull} / {@code NotBlank} / {@code NotEmpty} as {@code REQUIRED} and the others as
 * {@code INVALID_VALUE}, all at once. Shared by the process API and scenario replay, so both accept the same inputs.
 */
@Component
public class ProcessInputs {

    private final Validator validator;
    private final JsonMapper json;

    public ProcessInputs(Validator validator, JsonMapper json) {
        this.validator = validator;
        this.json = json;
        // The first validation loads message bundles from the classpath; do it here rather than on an event loop.
        validator.validate(new Warmup(null));
    }

    private record Warmup(@jakarta.validation.constraints.NotNull String value) {}

    /** @throws ValidationException when the body is not a valid input of the type */
    public <I> I convert(Class<I> type, Map<String, Object> body) {
        I input;
        try {
            input = json.convertValue(body == null ? Map.of() : body, type);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new ValidationException(List.of(new Violation(null, PlatformErrorCodes.INVALID_VALUE,
                "The request body is not a valid input of this process")));
        }
        if (input == null) {
            throw new ValidationException(List.of(new Violation(null, PlatformErrorCodes.REQUIRED,
                "The process needs an input")));
        }
        Set<ConstraintViolation<I>> broken = validator.validate(input);
        if (!broken.isEmpty()) {
            throw new ValidationException(broken.stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(ProcessInputs::toViolation)
                .toList());
        }
        return input;
    }

    private static Violation toViolation(ConstraintViolation<?> violation) {
        String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        String code = switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> PlatformErrorCodes.REQUIRED;
            default -> PlatformErrorCodes.INVALID_VALUE;
        };
        String field = violation.getPropertyPath().toString();
        return new Violation(field.isEmpty() ? null : field, code, field + " " + violation.getMessage(),
            Map.of("constraint", constraint));
    }
}
