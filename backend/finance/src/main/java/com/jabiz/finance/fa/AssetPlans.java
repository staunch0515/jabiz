package com.jabiz.finance.fa;

import com.jabiz.finance.calc.Depreciation;
import com.jabiz.finance.calc.Money;
import com.jabiz.runtime.EntityInstance;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

/**
 * An asset's depreciation plan as the runs, a change in estimate and a disposal read it: its terms, where its plan
 * starts (the last change in estimate, the register brought over, or its first month) and what the plan has it
 * accumulate by a month. A run takes the difference to what the asset has accumulated, so a month an asset was not run
 * for (placed in service with an earlier date) is made good by the next run.
 */
final class AssetPlans {

    private AssetPlans() {}

    /** The asset's terms; null when it has none (unclassified) or depreciates by use. */
    static Depreciation.Terms terms(EntityInstance asset) {
        if (asset.get("classCode") == null || asset.get("method") == null || isByUse(asset)) {
            return null;
        }
        return new Depreciation.Terms(asset.get("cost"), asset.get("salvage"), asset.get("inServiceDate"),
            asset.<BigDecimal>get("lifeMonths").intValueExact(), Depreciation.Method.valueOf(asset.get("method")),
            Depreciation.Convention.valueOf(asset.get("convention")));
    }

    static boolean isByUse(EntityInstance asset) {
        return AssetEntities.UOP.equals(asset.get("method"));
    }

    /** Cost less salvage. */
    static BigDecimal depreciable(EntityInstance asset) {
        BigDecimal salvage = asset.get("salvage");
        return asset.<BigDecimal>get("cost").subtract(salvage == null ? BigDecimal.ZERO : salvage);
    }

    static BigDecimal accumulated(EntityInstance asset) {
        BigDecimal accumulated = asset.get("accumulated");
        return accumulated == null ? BigDecimal.ZERO.setScale(2) : accumulated;
    }

    /** The asset's changes in estimate in force by {@code period}: the latest of them, or null. */
    static EntityInstance change(List<EntityInstance> changes, Object assetId, YearMonth period) {
        return changes.stream()
            .filter(c -> assetId.equals(c.get("assetId"))
                && YearMonth.parse(c.get("fromPeriod")).compareTo(period) <= 0)
            .max(Comparator.comparing(c -> (String) c.get("fromPeriod")))
            .orElse(null);
    }

    /** Where the plan starts: after a change in estimate afresh from it, else as brought over, else from nothing. */
    static Depreciation.Start start(EntityInstance asset, Depreciation.Terms terms, EntityInstance change) {
        if (change != null) {
            return Depreciation.restart(YearMonth.parse(change.get("fromPeriod")), change.get("accumulatedAt"));
        }
        if (asset.get("openingPeriod") != null) {
            BigDecimal broughtOver = asset.get("openingAccumulated");
            return Depreciation.opening(terms, YearMonth.parse(asset.get("openingPeriod")),
                broughtOver == null ? BigDecimal.ZERO : broughtOver);
        }
        return new Depreciation.Start(terms.firstMonth(), BigDecimal.ZERO, null, BigDecimal.ZERO);
    }

    /** What the plan has the asset accumulate by the end of {@code period}. */
    static BigDecimal plannedThrough(Depreciation.Terms terms, Depreciation.Start start, YearMonth period) {
        BigDecimal planned = start.accumulated();
        if (period.isBefore(start.period())) {
            return planned;
        }
        for (Depreciation.Month month : Depreciation.scheduleFrom(terms, start)) {
            if (month.period().isAfter(period)) {
                break;
            }
            planned = month.accumulated();
        }
        return planned;
    }

    /** What a run of {@code period} takes for the asset: what the plan has by then less what it has; never negative. */
    static BigDecimal due(EntityInstance asset, List<EntityInstance> changes, YearMonth period) {
        Depreciation.Terms terms = terms(asset);
        Depreciation.Start start = start(asset, terms, change(changes, asset.id(), period));
        BigDecimal due = plannedThrough(terms, start, period).subtract(accumulated(asset));
        return Money.usd(due.max(BigDecimal.ZERO).min(depreciable(asset).subtract(accumulated(asset))
            .max(BigDecimal.ZERO)));
    }

    /**
     * What a disposal in {@code period}, not yet run, takes: any month before it not taken, and the share of the
     * month the convention gives (FIN-FA-007).
     */
    static BigDecimal disposalMonth(EntityInstance asset, List<EntityInstance> changes, YearMonth period) {
        Depreciation.Terms terms = terms(asset);
        Depreciation.Start start = start(asset, terms, change(changes, asset.id(), period));
        BigDecimal before = plannedThrough(terms, start, period.minusMonths(1));
        BigDecimal month = plannedThrough(terms, start, period).subtract(before);
        BigDecimal due = before.subtract(accumulated(asset)).max(BigDecimal.ZERO)
            .add(month.multiply(Depreciation.disposalShare(terms.convention())));
        return Money.usd(due.min(depreciable(asset).subtract(accumulated(asset)).max(BigDecimal.ZERO)));
    }

    /** The next month the asset has not been depreciated for: where a change in estimate takes effect. */
    static YearMonth nextPeriod(EntityInstance asset) {
        if (asset.get("depreciatedThrough") != null) {
            return YearMonth.parse(asset.<String>get("depreciatedThrough")).plusMonths(1);
        }
        if (asset.get("openingPeriod") != null) {
            return YearMonth.parse(asset.get("openingPeriod"));
        }
        YearMonth placed = YearMonth.from(asset.<java.time.LocalDate>get("inServiceDate"));
        return AssetEntities.NEXT_MONTH.equals(asset.get("convention")) ? placed.plusMonths(1) : placed;
    }
}
