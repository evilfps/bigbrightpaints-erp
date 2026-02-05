package com.bigbrightpaints.erp.modules.reports.service;

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
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class InventoryValuationService {

    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;

    public InventoryValuationService(RawMaterialRepository rawMaterialRepository,
                                     RawMaterialBatchRepository rawMaterialBatchRepository,
                                     FinishedGoodRepository finishedGoodRepository,
                                     FinishedGoodBatchRepository finishedGoodBatchRepository,
                                     InventoryMovementRepository inventoryMovementRepository,
                                     RawMaterialMovementRepository rawMaterialMovementRepository) {
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
    }

    public InventorySnapshot currentSnapshot(Company company) {
        return computeCurrentSnapshot(company);
    }

    public InventorySnapshot snapshotAsOf(Company company, LocalDate asOfDate) {
        if (company == null) {
            return new InventorySnapshot(BigDecimal.ZERO, 0L);
        }
        if (asOfDate == null) {
            return computeCurrentSnapshot(company);
        }
        InventorySnapshot current = computeCurrentSnapshot(company);
        InventoryAdjustments adjustments = computeAdjustmentsAfter(company, asOfDate);
        BigDecimal adjustedValue = current.totalValue().subtract(adjustments.netValueDelta());
        if (adjustedValue.compareTo(BigDecimal.ZERO) < 0) {
            adjustedValue = BigDecimal.ZERO;
        }
        long lowStock = computeLowStockAsOf(company, adjustments);
        return new InventorySnapshot(adjustedValue.setScale(2, RoundingMode.HALF_UP), lowStock);
    }

    private InventorySnapshot computeCurrentSnapshot(Company company) {
        BigDecimal totalValue = BigDecimal.ZERO;
        long lowStock = 0;
        List<RawMaterial> materials = rawMaterialRepository.findByCompanyOrderByNameAsc(company);
        for (RawMaterial material : materials) {
            totalValue = totalValue.add(valueFromRawMaterial(material));
            BigDecimal stock = safe(material.getCurrentStock());
            if (stock.compareTo(safe(material.getReorderLevel())) < 0) {
                lowStock++;
            }
        }
        List<FinishedGood> finishedGoods = finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company);
        for (FinishedGood finishedGood : finishedGoods) {
            totalValue = totalValue.add(valueFromFinishedGood(finishedGood));
            BigDecimal reserved = finishedGood.getReservedStock();
            if (reserved != null && safe(finishedGood.getCurrentStock()).compareTo(reserved) < 0) {
                lowStock++;
            }
        }
        return new InventorySnapshot(totalValue.setScale(2, RoundingMode.HALF_UP), lowStock);
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

    private long computeLowStockAsOf(Company company, InventoryAdjustments adjustments) {
        long lowStock = 0;
        Map<Long, BigDecimal> rawDelta = adjustments.rawMaterialQtyDelta();
        Map<Long, BigDecimal> fgDelta = adjustments.finishedGoodQtyDelta();
        for (RawMaterial material : rawMaterialRepository.findByCompanyOrderByNameAsc(company)) {
            BigDecimal current = safe(material.getCurrentStock());
            BigDecimal delta = rawDelta.getOrDefault(material.getId(), BigDecimal.ZERO);
            BigDecimal asOfStock = current.subtract(delta);
            if (asOfStock.compareTo(BigDecimal.ZERO) < 0) {
                asOfStock = BigDecimal.ZERO;
            }
            if (asOfStock.compareTo(safe(material.getReorderLevel())) < 0) {
                lowStock++;
            }
        }
        for (FinishedGood finishedGood : finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company)) {
            BigDecimal current = safe(finishedGood.getCurrentStock());
            BigDecimal delta = fgDelta.getOrDefault(finishedGood.getId(), BigDecimal.ZERO);
            BigDecimal asOfStock = current.subtract(delta);
            if (asOfStock.compareTo(BigDecimal.ZERO) < 0) {
                asOfStock = BigDecimal.ZERO;
            }
            BigDecimal reserved = finishedGood.getReservedStock();
            if (reserved != null && asOfStock.compareTo(reserved) < 0) {
                lowStock++;
            }
        }
        return lowStock;
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

    private BigDecimal valueFromRawMaterial(RawMaterial material) {
        BigDecimal remaining = Optional.ofNullable(material.getCurrentStock()).orElse(BigDecimal.ZERO);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (isWeightedAverage(material.getCostingMethod())) {
            BigDecimal avgCost = rawMaterialBatchRepository.calculateWeightedAverageCost(material);
            if (avgCost == null) {
                return BigDecimal.ZERO;
            }
            return remaining.multiply(avgCost);
        }
        List<RawMaterialBatch> batches = rawMaterialBatchRepository.findByRawMaterial(material).stream()
                .sorted((a, b) -> a.getReceivedAt().compareTo(b.getReceivedAt()))
                .toList();
        return consumeValuation(remaining, batches.stream()
                .map(batch -> new CostSlice(batch.getQuantity(), batch.getCostPerUnit()))
                .toList());
    }

    private BigDecimal valueFromFinishedGood(FinishedGood finishedGood) {
        BigDecimal remaining = Optional.ofNullable(finishedGood.getCurrentStock()).orElse(BigDecimal.ZERO);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (isWeightedAverage(finishedGood.getCostingMethod())) {
            BigDecimal avgCost = finishedGoodBatchRepository.calculateWeightedAverageCost(finishedGood);
            if (avgCost == null) {
                return BigDecimal.ZERO;
            }
            return remaining.multiply(avgCost);
        }
        List<FinishedGoodBatch> batches = finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(finishedGood);
        return consumeValuation(remaining, batches.stream()
                .map(batch -> new CostSlice(batch.getQuantityTotal(), batch.getUnitCost()))
                .toList());
    }

    private BigDecimal consumeValuation(BigDecimal required, List<CostSlice> slices) {
        BigDecimal remaining = required;
        BigDecimal total = BigDecimal.ZERO;
        for (CostSlice slice : slices) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal qty = slice.quantity() != null ? slice.quantity() : BigDecimal.ZERO;
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal unitCost = slice.unitCost() != null ? slice.unitCost() : BigDecimal.ZERO;
            BigDecimal used = remaining.min(qty);
            total = total.add(used.multiply(unitCost));
            remaining = remaining.subtract(used);
        }
        return total;
    }

    private boolean isWeightedAverage(String method) {
        if (method == null) {
            return false;
        }
        String normalized = method.trim().toUpperCase(Locale.ROOT);
        return "WAC".equals(normalized) || "WEIGHTED_AVERAGE".equals(normalized) || "WEIGHTED-AVERAGE".equals(normalized);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record InventorySnapshot(BigDecimal totalValue, long lowStockItems) {}

    private enum MovementImpact {
        INCREASE, DECREASE, NONE
    }

    private record InventoryAdjustments(Map<Long, BigDecimal> rawMaterialQtyDelta,
                                        Map<Long, BigDecimal> finishedGoodQtyDelta,
                                        BigDecimal netValueDelta) {}

    private record CostSlice(BigDecimal quantity, BigDecimal unitCost) {}
}
