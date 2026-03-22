package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InventoryValuationService {

    private static final long WAC_CACHE_MILLIS = 5 * 60 * 1000;

    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final Map<Long, CachedWac> wacCache = new ConcurrentHashMap<>();

    public InventoryValuationService(FinishedGoodBatchRepository finishedGoodBatchRepository) {
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
    }

    public BigDecimal safeQuantity(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    public BigDecimal safePercent(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }

    public BigDecimal currency(BigDecimal value) {
        return safeQuantity(value).setScale(2, RoundingMode.HALF_UP);
    }

    public String normalizeStateCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    public BigDecimal resolveLowStockThreshold(FinishedGood finishedGood, Integer overrideThreshold) {
        if (overrideThreshold != null) {
            return BigDecimal.valueOf(Math.max(0, overrideThreshold));
        }
        return safeQuantity(finishedGood.getLowStockThreshold());
    }

    public BigDecimal currentWeightedAverageCost(FinishedGood finishedGood) {
        if (finishedGood == null || finishedGood.getId() == null) {
            return BigDecimal.ZERO;
        }
        CachedWac cached = wacCache.get(finishedGood.getId());
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.cachedAtMillis()) < WAC_CACHE_MILLIS) {
            return cached.cost();
        }
        BigDecimal cost = finishedGoodBatchRepository.calculateWeightedAverageCost(finishedGood);
        if (cost == null) {
            cost = BigDecimal.ZERO;
        }
        wacCache.put(finishedGood.getId(), new CachedWac(cost, now));
        return cost;
    }

    public BigDecimal stockSummaryUnitCost(FinishedGood finishedGood) {
        if (finishedGood == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal onHand = safeQuantity(finishedGood.getCurrentStock());
        if (onHand.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        CostingMethodUtils.FinishedGoodBatchSelectionMethod selectionMethod =
                CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(finishedGood.getCostingMethod());
        if (selectionMethod == CostingMethodUtils.FinishedGoodBatchSelectionMethod.WAC) {
            return currentWeightedAverageCost(finishedGood);
        }

        List<FinishedGoodBatch> batches = new ArrayList<>(
                finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood));
        batches.sort(Comparator
                .comparing(FinishedGoodBatch::getManufacturedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FinishedGoodBatch::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        if (selectionMethod == CostingMethodUtils.FinishedGoodBatchSelectionMethod.LIFO) {
            Collections.reverse(batches);
        }

        BigDecimal valuedStock = BigDecimal.ZERO;
        BigDecimal remaining = onHand;
        for (FinishedGoodBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal availableQuantity = batch.getQuantityAvailable() != null
                    ? batch.getQuantityAvailable()
                    : batch.getQuantityTotal();
            BigDecimal batchQuantity = safeQuantity(availableQuantity);
            if (batchQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal unitCost = batch.getUnitCost() != null ? batch.getUnitCost() : BigDecimal.ZERO;
            BigDecimal used = remaining.min(batchQuantity);
            valuedStock = valuedStock.add(used.multiply(unitCost));
            remaining = remaining.subtract(used);
        }

        if (valuedStock.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return valuedStock.divide(onHand, 6, RoundingMode.HALF_UP);
    }

    public BigDecimal resolveDispatchUnitCost(FinishedGood finishedGood,
                                              FinishedGoodBatch batch) {
        if (finishedGood == null) {
            return BigDecimal.ZERO;
        }
        CostingMethodUtils.FinishedGoodBatchSelectionMethod selectionMethod =
                CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(finishedGood.getCostingMethod());
        if (selectionMethod == CostingMethodUtils.FinishedGoodBatchSelectionMethod.WAC) {
            return currentWeightedAverageCost(finishedGood);
        }
        if (batch != null && batch.getUnitCost() != null) {
            return batch.getUnitCost();
        }
        return currentWeightedAverageCost(finishedGood);
    }

    public void requireNonZeroDispatchCost(FinishedGood finishedGood, BigDecimal unitCost, BigDecimal shippedQuantity) {
        if (finishedGood == null || shippedQuantity == null) {
            return;
        }
        if (shippedQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal resolved = unitCost != null ? unitCost : BigDecimal.ZERO;
        if (resolved.compareTo(BigDecimal.ZERO) != 0) {
            return;
        }
        BigDecimal current = safeQuantity(finishedGood.getCurrentStock());
        BigDecimal reserved = safeQuantity(finishedGood.getReservedStock());
        BigDecimal onHand = current.max(reserved);
        if (onHand.compareTo(BigDecimal.ZERO) > 0) {
            String code = finishedGood.getProductCode() != null ? finishedGood.getProductCode() : finishedGood.getName();
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils
                    .invalidState("Dispatch cost is zero for FG " + code + " with on-hand stock");
        }
    }

    public String normalizeCostingMethod(String method) {
        return CostingMethodUtils.normalizeFinishedGoodMethodOrDefault(method);
    }

    public void invalidateWeightedAverageCost(Long finishedGoodId) {
        if (finishedGoodId != null) {
            wacCache.remove(finishedGoodId);
        }
    }

    private record CachedWac(BigDecimal cost, long cachedAtMillis) {
    }
}
