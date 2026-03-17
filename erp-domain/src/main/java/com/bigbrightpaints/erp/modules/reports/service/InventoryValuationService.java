package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service("reportsInventoryValuationService")
public class InventoryValuationService {

    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;
    private final ProductionProductRepository productionProductRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;

    public InventoryValuationService(RawMaterialRepository rawMaterialRepository,
                                     RawMaterialBatchRepository rawMaterialBatchRepository,
                                     FinishedGoodRepository finishedGoodRepository,
                                     FinishedGoodBatchRepository finishedGoodBatchRepository,
                                     InventoryMovementRepository inventoryMovementRepository,
                                     RawMaterialMovementRepository rawMaterialMovementRepository,
                                     ProductionProductRepository productionProductRepository,
                                     AccountingPeriodRepository accountingPeriodRepository) {
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
        this.productionProductRepository = productionProductRepository;
        this.accountingPeriodRepository = accountingPeriodRepository;
    }

    public InventorySnapshot currentSnapshot(Company company) {
        return computeCurrentSnapshot(company, null);
    }

    public InventorySnapshot snapshotAsOf(Company company, LocalDate asOfDate) {
        if (company == null) {
            return new InventorySnapshot(BigDecimal.ZERO, 0L, null, List.of());
        }
        return computeCurrentSnapshot(company, asOfDate);
    }

    private InventorySnapshot computeCurrentSnapshot(Company company, LocalDate asOfDate) {
        if (company == null) {
            return new InventorySnapshot(BigDecimal.ZERO, 0L, null, List.of());
        }
        LocalDate effectiveDate = asOfDate;
        InventoryAdjustments adjustments = asOfDate != null ? computeAdjustmentsAfter(company, asOfDate) : null;
        CostingMethodContext costingMethodContext = resolveCostingMethodContext(company, effectiveDate);

        BigDecimal totalValue = BigDecimal.ZERO;
        long lowStock = 0;
        List<InventoryItemSnapshot> items = new ArrayList<>();
        Map<String, ProductionProduct> productBySku = productionProductBySku(company);

        List<RawMaterial> materials = rawMaterialRepository.findByCompanyOrderByNameAsc(company);
        for (RawMaterial material : materials) {
            BigDecimal adjustedStock = applyAsOfStock(material.getCurrentStock(), material.getId(),
                    adjustments != null ? adjustments.rawMaterialQtyDelta() : null);
            RawMaterialValuation valuation = valueFromRawMaterial(material, adjustedStock, costingMethodContext.method());
            totalValue = totalValue.add(valuation.totalValue());
            if (adjustedStock.compareTo(safe(material.getReorderLevel())) < 0) {
                lowStock++;
            }
            ProductionProduct linkedProduct = productBySku.get(normalizeSku(material.getSku()));
            String brand = linkedProduct != null && linkedProduct.getBrand() != null
                    ? linkedProduct.getBrand().getName()
                    : "Raw Materials";
            String category = linkedProduct != null && linkedProduct.getCategory() != null
                    ? linkedProduct.getCategory()
                    : "RAW_MATERIAL";
            items.add(new InventoryItemSnapshot(
                    material.getId(),
                    InventoryTypeBucket.RAW_MATERIAL,
                    material.getSku(),
                    material.getName(),
                    category,
                    brand,
                    adjustedStock,
                    BigDecimal.ZERO,
                    adjustedStock,
                    valuation.unitCost(),
                    valuation.totalValue(),
                    adjustedStock.compareTo(safe(material.getReorderLevel())) < 0
            ));
        }

        List<FinishedGood> finishedGoods = finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company);
        for (FinishedGood finishedGood : finishedGoods) {
            BigDecimal adjustedStock = applyAsOfStock(finishedGood.getCurrentStock(), finishedGood.getId(),
                    adjustments != null ? adjustments.finishedGoodQtyDelta() : null);
            BigDecimal adjustedReserved = safe(finishedGood.getReservedStock());
            BigDecimal available = adjustedStock.subtract(adjustedReserved).max(BigDecimal.ZERO);
            FinishedGoodValuation valuation = valueFromFinishedGood(finishedGood, adjustedStock, costingMethodContext.method());
            totalValue = totalValue.add(valuation.totalValue());
            boolean isLowStock = adjustedReserved.compareTo(BigDecimal.ZERO) > 0
                    && adjustedStock.compareTo(adjustedReserved) < 0;
            if (isLowStock) {
                lowStock++;
            }
            ProductionProduct product = productBySku.get(normalizeSku(finishedGood.getProductCode()));
            String brand = product != null && product.getBrand() != null ? product.getBrand().getName() : "Unbranded";
            String category = product != null && product.getCategory() != null ? product.getCategory() : "FINISHED_GOOD";
            items.add(new InventoryItemSnapshot(
                    finishedGood.getId(),
                    InventoryTypeBucket.FINISHED_GOOD,
                    finishedGood.getProductCode(),
                    finishedGood.getName(),
                    category,
                    brand,
                    adjustedStock,
                    adjustedReserved,
                    available,
                    valuation.unitCost(),
                    valuation.totalValue(),
                    isLowStock
            ));
        }

        items.sort(Comparator.comparing(InventoryItemSnapshot::inventoryType)
                .thenComparing(InventoryItemSnapshot::category, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(InventoryItemSnapshot::brand, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(InventoryItemSnapshot::code, Comparator.nullsLast(String::compareToIgnoreCase)));

        return new InventorySnapshot(
                totalValue.setScale(2, RoundingMode.HALF_UP),
                lowStock,
                costingMethodContext.canonicalMethod(),
                List.copyOf(items)
        );
    }

    private InventoryAdjustments computeAdjustmentsAfter(Company company, LocalDate asOfDate) {
        ZoneId zone = resolveZone(company);
        Instant cutoff = asOfDate.plusDays(1).atStartOfDay(zone).toInstant();
        Map<Long, BigDecimal> rawMaterialQtyDelta = new HashMap<>();
        Map<Long, BigDecimal> finishedGoodQtyDelta = new HashMap<>();
        BigDecimal netValueDelta = BigDecimal.ZERO;

        List<RawMaterialMovement> rawMovements = rawMaterialMovementRepository
                .findByCompanyCreatedAtOnOrAfter(company, cutoff);
        for (RawMaterialMovement movement : rawMovements) {
            MovementImpact impact = rawMaterialImpact(movement.getMovementType());
            if (impact == MovementImpact.NONE) {
                continue;
            }
            BigDecimal qty = safe(movement.getQuantity());
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal deltaQty = impact == MovementImpact.INCREASE ? qty : qty.negate();
            RawMaterial material = movement.getRawMaterial();
            if (material != null && material.getId() != null) {
                rawMaterialQtyDelta.merge(material.getId(), deltaQty, BigDecimal::add);
            }
            BigDecimal deltaValue = safe(movement.getUnitCost()).multiply(qty);
            netValueDelta = netValueDelta.add(impact == MovementImpact.INCREASE ? deltaValue : deltaValue.negate());
        }

        List<InventoryMovement> fgMovements = inventoryMovementRepository
                .findByCompanyCreatedAtOnOrAfter(company, cutoff);
        for (InventoryMovement movement : fgMovements) {
            MovementImpact impact = finishedGoodImpact(movement.getMovementType());
            if (impact == MovementImpact.NONE) {
                continue;
            }
            BigDecimal qty = safe(movement.getQuantity());
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal deltaQty = impact == MovementImpact.INCREASE ? qty : qty.negate();
            FinishedGood finishedGood = movement.getFinishedGood();
            if (finishedGood != null && finishedGood.getId() != null) {
                finishedGoodQtyDelta.merge(finishedGood.getId(), deltaQty, BigDecimal::add);
            }
            BigDecimal deltaValue = safe(movement.getUnitCost()).multiply(qty);
            netValueDelta = netValueDelta.add(impact == MovementImpact.INCREASE ? deltaValue : deltaValue.negate());
        }

        return new InventoryAdjustments(rawMaterialQtyDelta, finishedGoodQtyDelta, netValueDelta);
    }

    private MovementImpact rawMaterialImpact(String type) {
        if (type == null) {
            return MovementImpact.NONE;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "RECEIPT", "ADJUSTMENT_IN" -> MovementImpact.INCREASE;
            case "ISSUE", "RETURN", "ADJUSTMENT_OUT", "SCRAP", "RETURN_TO_VENDOR" -> MovementImpact.DECREASE;
            default -> MovementImpact.NONE;
        };
    }

    private MovementImpact finishedGoodImpact(String type) {
        if (type == null) {
            return MovementImpact.NONE;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "RECEIPT", "RETURN", "ADJUSTMENT_IN", "RETURN_FROM_CUSTOMER" -> MovementImpact.INCREASE;
            case "DISPATCH", "ISSUE", "ADJUSTMENT_OUT", "SCRAP", "WASTAGE", "RETURN_TO_VENDOR" -> MovementImpact.DECREASE;
            case "RESERVE" -> MovementImpact.NONE;
            default -> MovementImpact.NONE;
        };
    }

    private ZoneId resolveZone(Company company) {
        String timezone = company.getTimezone();
        return ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
    }

    private RawMaterialValuation valueFromRawMaterial(RawMaterial material,
                                                      BigDecimal quantityOnHand,
                                                      CostingMethod method) {
        BigDecimal remaining = safe(quantityOnHand);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return new RawMaterialValuation(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        String resolvedMethod = canonicalMethod(method, material != null ? material.getCostingMethod() : null);
        if (CostingMethodUtils.isWeightedAverage(resolvedMethod)) {
            BigDecimal avgCost = CostingMethodUtils.selectWeightedAverageValue(
                    resolvedMethod,
                    () -> rawMaterialBatchRepository.calculateWeightedAverageCost(material),
                    () -> null
            );
            if (avgCost == null || avgCost.compareTo(BigDecimal.ZERO) <= 0) {
                return valueFromRawMaterialBatches(material, remaining, true);
            }
            BigDecimal unitCost = avgCost.setScale(6, RoundingMode.HALF_UP);
            return new RawMaterialValuation(
                    remaining.multiply(unitCost).setScale(2, RoundingMode.HALF_UP),
                    unitCost
            );
        }

        boolean fifo = CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(resolvedMethod)
                != CostingMethodUtils.FinishedGoodBatchSelectionMethod.LIFO;
        return valueFromRawMaterialBatches(material, remaining, fifo);
    }

    private RawMaterialValuation valueFromRawMaterialBatches(RawMaterial material,
                                                             BigDecimal quantityOnHand,
                                                             boolean fifo) {
        BigDecimal required = safe(quantityOnHand);
        if (required.compareTo(BigDecimal.ZERO) <= 0) {
            return new RawMaterialValuation(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        List<RawMaterialBatch> orderedBatches = new ArrayList<>(rawMaterialBatchRepository.findByRawMaterial(material));
        orderedBatches.sort(Comparator
                .comparing(RawMaterialBatch::getReceivedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RawMaterialBatch::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        if (!fifo) {
            Collections.reverse(orderedBatches);
        }

        BigDecimal weightedQuantity = BigDecimal.ZERO;
        BigDecimal weightedValue = BigDecimal.ZERO;
        BigDecimal remaining = required;

        for (RawMaterialBatch batch : orderedBatches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal qty = safe(batch.getQuantity());
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal used = qty.min(remaining);
            BigDecimal unitCost = safe(batch.getCostPerUnit());
            weightedQuantity = weightedQuantity.add(used);
            weightedValue = weightedValue.add(used.multiply(unitCost));
            remaining = remaining.subtract(used);
        }

        BigDecimal unitCost = required.compareTo(BigDecimal.ZERO) > 0
                ? weightedValue.divide(required, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new RawMaterialValuation(
                weightedValue.setScale(2, RoundingMode.HALF_UP),
                unitCost
        );
    }

    private FinishedGoodValuation valueFromFinishedGood(FinishedGood finishedGood,
                                                        BigDecimal quantityOnHand,
                                                        CostingMethod method) {
        BigDecimal remaining = safe(quantityOnHand);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return new FinishedGoodValuation(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        String resolvedMethod = canonicalMethod(method, finishedGood != null ? finishedGood.getCostingMethod() : null);
        CostingMethodUtils.FinishedGoodBatchSelectionMethod selectionMethod =
                CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(resolvedMethod);
        if (selectionMethod == CostingMethodUtils.FinishedGoodBatchSelectionMethod.WAC) {
            BigDecimal avgCost = CostingMethodUtils.selectWeightedAverageValue(
                    resolvedMethod,
                    () -> finishedGoodBatchRepository.calculateWeightedAverageCost(finishedGood),
                    () -> null
            );
            if (avgCost == null || avgCost.compareTo(BigDecimal.ZERO) <= 0) {
                return valueFromFinishedGoodBatches(finishedGood, remaining, true);
            }
            BigDecimal unitCost = avgCost.setScale(6, RoundingMode.HALF_UP);
            return new FinishedGoodValuation(
                    remaining.multiply(unitCost).setScale(2, RoundingMode.HALF_UP),
                    unitCost
            );
        }

        boolean fifo = selectionMethod != CostingMethodUtils.FinishedGoodBatchSelectionMethod.LIFO;
        return valueFromFinishedGoodBatches(finishedGood, remaining, fifo);
    }

    private FinishedGoodValuation valueFromFinishedGoodBatches(FinishedGood finishedGood,
                                                               BigDecimal quantityOnHand,
                                                               boolean fifo) {
        BigDecimal required = safe(quantityOnHand);
        if (required.compareTo(BigDecimal.ZERO) <= 0) {
            return new FinishedGoodValuation(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        List<FinishedGoodBatch> orderedBatches = new ArrayList<>(
                finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood));
        orderedBatches.sort(Comparator
                .comparing(FinishedGoodBatch::getManufacturedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FinishedGoodBatch::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        if (!fifo) {
            Collections.reverse(orderedBatches);
        }

        BigDecimal weightedQuantity = BigDecimal.ZERO;
        BigDecimal weightedValue = BigDecimal.ZERO;
        BigDecimal remaining = required;

        for (FinishedGoodBatch batch : orderedBatches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal qty = safe(batch.getQuantityAvailable() != null ? batch.getQuantityAvailable() : batch.getQuantityTotal());
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal used = qty.min(remaining);
            BigDecimal unitCost = safe(batch.getUnitCost());
            weightedQuantity = weightedQuantity.add(used);
            weightedValue = weightedValue.add(used.multiply(unitCost));
            remaining = remaining.subtract(used);
        }

        BigDecimal unitCost = required.compareTo(BigDecimal.ZERO) > 0
                ? weightedValue.divide(required, 6, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new FinishedGoodValuation(
                weightedValue.setScale(2, RoundingMode.HALF_UP),
                unitCost
        );
    }

    private BigDecimal applyAsOfStock(BigDecimal currentStock, Long itemId, Map<Long, BigDecimal> deltaMap) {
        BigDecimal stock = safe(currentStock);
        if (itemId == null || deltaMap == null || deltaMap.isEmpty()) {
            return stock;
        }
        BigDecimal adjusted = stock.subtract(deltaMap.getOrDefault(itemId, BigDecimal.ZERO));
        return adjusted.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : adjusted;
    }

    private CostingMethodContext resolveCostingMethodContext(Company company, LocalDate asOfDate) {
        if (company == null) {
            return new CostingMethodContext(null, "WEIGHTED_AVERAGE");
        }
        LocalDate referenceDate = asOfDate != null ? asOfDate : CompanyTime.today(company);
        AccountingPeriod period = accountingPeriodRepository
                .findByCompanyAndYearAndMonth(company, referenceDate.getYear(), referenceDate.getMonthValue())
                .orElse(null);
        if (period == null || period.getCostingMethod() == null) {
            return new CostingMethodContext(null, "WEIGHTED_AVERAGE");
        }
        return new CostingMethodContext(period.getCostingMethod(), canonicalMethodLabel(period.getCostingMethod()));
    }

    private String canonicalMethod(CostingMethod periodMethod, String itemMethod) {
        if (periodMethod != null) {
            return canonicalMethodLabel(periodMethod);
        }
        if (itemMethod == null || itemMethod.isBlank()) {
            return "FIFO";
        }
        String normalized = itemMethod.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FIFO" -> "FIFO";
            case "LIFO" -> "LIFO";
            case "WAC", "WEIGHTED_AVERAGE", "WEIGHTED-AVERAGE" -> "WEIGHTED_AVERAGE";
            default -> "FIFO";
        };
    }

    private String canonicalMethodLabel(CostingMethod method) {
        if (method == null) {
            return "WEIGHTED_AVERAGE";
        }
        return switch (method) {
            case FIFO -> "FIFO";
            case LIFO -> "LIFO";
            case WEIGHTED_AVERAGE -> "WEIGHTED_AVERAGE";
        };
    }

    private Map<String, ProductionProduct> productionProductBySku(Company company) {
        Map<String, ProductionProduct> result = new HashMap<>();
        List<ProductionProduct> products = productionProductRepository.findByCompanyOrderByProductNameAsc(company);
        for (ProductionProduct product : products) {
            String key = normalizeSku(product.getSkuCode());
            if (key == null || result.containsKey(key)) {
                continue;
            }
            result.put(key, product);
        }
        return result;
    }

    private String normalizeSku(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record InventorySnapshot(BigDecimal totalValue,
                                    long lowStockItems,
                                    String costingMethod,
                                    List<InventoryItemSnapshot> items) {
    }

    public record InventoryItemSnapshot(Long inventoryItemId,
                                        InventoryTypeBucket inventoryType,
                                        String code,
                                        String name,
                                        String category,
                                        String brand,
                                        BigDecimal quantityOnHand,
                                        BigDecimal reservedQuantity,
                                        BigDecimal availableQuantity,
                                        BigDecimal unitCost,
                                        BigDecimal totalValue,
                                        boolean lowStock) {
    }

    public enum InventoryTypeBucket {
        RAW_MATERIAL,
        FINISHED_GOOD
    }

    private enum MovementImpact {
        INCREASE, DECREASE, NONE
    }

    private record InventoryAdjustments(Map<Long, BigDecimal> rawMaterialQtyDelta,
                                        Map<Long, BigDecimal> finishedGoodQtyDelta,
                                        BigDecimal netValueDelta) {
    }

    private record RawMaterialValuation(BigDecimal totalValue, BigDecimal unitCost) {
    }

    private record FinishedGoodValuation(BigDecimal totalValue, BigDecimal unitCost) {
    }

    private record CostingMethodContext(CostingMethod method, String canonicalMethod) {
    }
}
