package com.jabiz.app.commerce;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.UUID;

import static com.jabiz.app.commerce.CommerceEntities.MAX_ORDER_LINES;

/**
 * Ordering in the customer portal (ROADMAP phase 16b-2; docs/design/10-security.md section 15): {@value #NAME} places
 * an order for the customer whose account is the caller, never for a customer the input names, and only with a
 * verified e-mail address ({@code requiresVerifiedEmail()}). The order itself is {@code ORDER_PLACE}, called as a
 * sub-process, so its pricing and stock rules are the same. A caller linked to no customer, or to a blocked one, is
 * refused.
 */
@Configuration
public class PortalOrders {

    public static final String NAME = "PORTAL_ORDER_PLACE";
    /** Granted to the portal's customer role. */
    public static final String PERMISSION = "commerce.portal.order";

    public static final String NO_CUSTOMER = "COMMERCE_PORTAL_NO_CUSTOMER";
    public static final String CUSTOMER_BLOCKED = "COMMERCE_CUSTOMER_BLOCKED";

    /** No customer code: the customer is the caller's. */
    public record PortalPlaceInput(@NotBlank String warehouseCode,
        @NotEmpty @Size(max = MAX_ORDER_LINES) List<CommerceProcesses.@Valid @NotNull LineInput> lines) {}

    private static final String INPUT = "input";
    private static final String CUSTOMERS = "customers";
    private static final String PLACE = "place";
    private static final String PLACED = "placed";

    public static final ProcessDefinition<PortalPlaceInput, CommerceProcesses.PlaceOutput, ProcessContext> PROCESS =
        ProcessDefinition.define(NAME, 1, PortalPlaceInput.class, CommerceProcesses.PlaceOutput.class,
            ProcessContext.class, pb -> pb
                .description("Places a sales order for the caller's own customer.")
                .permissions(PERMISSION)
                .requiresVerifiedEmail()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(PLACED, CommerceProcesses.PlaceOutput.class))
                .step("Find the caller's customer", QueryEntities.of(CommerceEntities.CUSTOMER_DATASET,
                    PortalOrders::callersCustomers, CUSTOMERS))
                .compute("Check the customer", (metadata, ctx) -> check(ctx))
                .step("Place the order", CallProcess.<ProcessContext>when(ctx -> ctx.get(PLACE) != null,
                    CommerceProcesses.ORDER_PLACE, 1, ctx -> ctx.get(PLACE), PLACED)));

    /** The customers whose account is the caller: none for a caller who is no user of the platform. */
    private static EntityQuery callersCustomers(ProcessContext ctx) {
        QueryPredicate where;
        try {
            where = new QueryPredicate.Eq("userId", UUID.fromString(ctx.request().actorId()));
        } catch (IllegalArgumentException | NullPointerException notAUser) {
            where = new QueryPredicate.In("userId", List.of());
        }
        // Two at most: one account should be one customer, and a second is refused rather than guessed between.
        return EntityQuery.builder().where(where).limit(2).build();
    }

    @SuppressWarnings("unchecked")
    private static void check(ProcessContext ctx) {
        List<EntityInstance> customers = (List<EntityInstance>) ctx.get(CUSTOMERS);
        if (customers == null || customers.size() != 1) {
            ctx.reject(new Violation(null, NO_CUSTOMER, "The caller's account is linked to no single customer"));
            return;
        }
        EntityInstance customer = customers.getFirst();
        if (Boolean.TRUE.equals(customer.get("blocked"))) {
            ctx.reject(new Violation(null, CUSTOMER_BLOCKED, "The customer is blocked"));
            return;
        }
        PortalPlaceInput input = ctx.get(INPUT, PortalPlaceInput.class);
        ctx.put(PLACE, new CommerceProcesses.PlaceInput(null, customer.get("customerCode"), input.warehouseCode(),
            input.lines()));
    }

    @Bean
    ProcessDefinition<PortalPlaceInput, CommerceProcesses.PlaceOutput, ProcessContext> portalOrderPlaceProcess() {
        return PROCESS;
    }
}
