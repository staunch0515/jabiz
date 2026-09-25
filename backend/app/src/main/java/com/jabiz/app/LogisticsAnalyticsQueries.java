package com.jabiz.query.custom;

import com.jabiz.entity.DimensionType;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;

import java.util.List;

public final class LogisticsAnalyticsQueries {

    public static final AdvancedQueryDefinition TOKYO_PORT_WAYBILL_CUSTOMS_AUDIT =
        AdvancedQueryDefinition.define("query:logistics:tokyo_port_audit", qb -> {
            qb.description("Gross weight, declared duty and live spatial cell of waybills cleared at Tokyo port");

            // 1. Entities involved in the query
            qb.fromEntities("WaybillTracking", "CustomsDeclaration");

            // 2. Input parameters and their semantic constraints
            qb.parameter("minFreight",
                new SemanticKind.Monetary("JPY", 0), true);

            qb.parameter("allowedStatuses",
                new SemanticKind.Code("urn:ubos:dict:waybill_status", List.of("CUSTOMS_CLEARED", "DELIVERED")), true);

            qb.parameter("shippedAfter",
                new SemanticKind.Temporal(TemporalRole.EVENT_TIME), false);

            // 3. Result columns and their semantics
            qb.returns("waybillSn",
                new SemanticKind.SemanticIdentity("urn:ubos:entity:logistics:waybill"),
                "WaybillTracking", "waybillId");

            qb.returns("finalFreight",
                new SemanticKind.Monetary("JPY", 0),
                "WaybillTracking", "freightCharge");

            qb.returns("clearedWeightKg",
                new SemanticKind.PhysicalQuantity(DimensionType.MASS, "urn:unit:si:kilogram"),
                "WaybillTracking", "totalWeight");

            qb.returns("customsPortCell",
                new SemanticKind.SpatialH3(8),
                "WaybillTracking", "currentLocation");

            qb.returns("declarationNo",
                new SemanticKind.SemanticIdentity("urn:ubos:entity:customs:declaration"),
                "CustomsDeclaration", "declarationId");

            qb.returns("dutyPaid",
                new SemanticKind.Monetary("JPY", 0),
                "CustomsDeclaration", "dutyAmount");

            // 4. SQL template. Tables and columns are resolved through the metamodel, so the
            //    query follows physical renames and dataset routing.
            qb.sqlTemplate("""
                SELECT
                    w.{{WaybillTracking.waybillId}}        AS waybillSn,
                    w.{{WaybillTracking.freightCharge}}    AS finalFreight,
                    w.{{WaybillTracking.totalWeight}}      AS clearedWeightKg,
                    w.{{WaybillTracking.currentLocation}}  AS customsPortCell,
                    c.{{CustomsDeclaration.declarationId}} AS declarationNo,
                    c.{{CustomsDeclaration.dutyAmount}}    AS dutyPaid
                FROM {{WaybillTracking}} w
                INNER JOIN {{CustomsDeclaration}} c
                    ON w.{{WaybillTracking.waybillId}} = c.{{CustomsDeclaration.waybillRef}}
                WHERE w.{{WaybillTracking.freightCharge}} >= :minFreight
                  AND w.{{WaybillTracking.status}} IN (:allowedStatuses)
                  AND (CAST(:shippedAfter AS timestamptz) IS NULL
                       OR w.{{WaybillTracking.shippedTime}} >= :shippedAfter)
                ORDER BY w.{{WaybillTracking.shippedTime}} DESC
                """);
        });

    private LogisticsAnalyticsQueries() {}
}
