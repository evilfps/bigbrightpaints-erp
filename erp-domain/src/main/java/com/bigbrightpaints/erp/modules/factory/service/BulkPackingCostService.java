package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingConsumptionResult;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BulkPackingCostService {

    private static final RoundingMode COST_ROUNDING = RoundingMode.HALF_UP;

    private final PackagingMaterialService packagingMaterialService;
    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;

    public BulkPackingCostService(PackagingMaterialService packagingMaterialService,
                                  RawMaterialRepository rawMaterialRepository,
                                  RawMaterialBatchRepository rawMaterialBatchRepository,
                                  RawMaterialMovementRepository rawMaterialMovementRepository) {
        this.packagingMaterialService = packagingMaterialService;
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
    }

    public BulkPackCostSummary consumePackagingIfRequired(Company company,
                                                          BulkPackRequest request,
                                                          String packReference) {
        if (request == null || !request.shouldConsumePackaging()) {
            return new BulkPackCostSummary(BigDecimal.ZERO, Map.of(), Map.of());
        }
        return consumePackagingFromMappings(company, request.packs(), packReference);
    }

    public BulkPackCostingContext createCostingContext(BigDecimal bulkUnitCost,
                                                       BulkPackCostSummary packagingCostSummary,
                                                       int totalPacks) {
        BigDecimal fallbackPackagingCostPerUnit = totalPacks > 0
                ? packagingCostSummary.totalCost().divide(BigDecimal.valueOf(totalPacks), 6, COST_ROUNDING)
                : BigDecimal.ZERO;
        Map<Integer, BigDecimal> lineCosts = packagingCostSummary.lineCosts();
        boolean hasLineCosts = lineCosts != null && !lineCosts.isEmpty();
        return new BulkPackCostingContext(
                bulkUnitCost,
                fallbackPackagingCostPerUnit,
                lineCosts,
                hasLineCosts);
    }

    public BigDecimal resolveLinePackagingCostPerUnit(BulkPackCostingContext context,
                                                      BulkPackRequest.PackLine line,
                                                      int lineIndex) {
        if (!context.hasLineCosts()) {
            return context.fallbackPackagingCostPerUnit();
        }
        BigDecimal linePackagingTotal = context.lineCosts().get(lineIndex);
        if (linePackagingTotal == null || line.quantity().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return linePackagingTotal.divide(line.quantity(), 6, COST_ROUNDING);
    }

    private BulkPackCostSummary consumePackagingFromMappings(Company company,
                                                             List<BulkPackRequest.PackLine> packs,
                                                             String reference) {
        BigDecimal totalCost = BigDecimal.ZERO;
        Map<Long, BigDecimal> accountTotals = new HashMap<>();
        Map<Integer, BigDecimal> lineCosts = new HashMap<>();

        int lineIndex = 0;
        for (BulkPackRequest.PackLine line : packs) {
            int currentIndex = lineIndex++;
            int piecesCount = requireWholePackCount(line.quantity(), line.childSkuId());
            if (piecesCount <= 0) {
                continue;
            }

            String sizeLabel = resolvePackagingSizeLabel(line);
            PackagingConsumptionResult result = packagingMaterialService.consumePackagingMaterial(
                    sizeLabel,
                    piecesCount,
                    reference);
            if (!result.isConsumed()) {
                continue;
            }

            totalCost = totalCost.add(result.totalCost());
            lineCosts.put(currentIndex, result.totalCost());
            result.accountTotalsOrEmpty().forEach(
                    (accountId, amount) -> accountTotals.merge(accountId, amount, BigDecimal::add));
        }

        return new BulkPackCostSummary(totalCost, accountTotals, lineCosts);
    }

    private int requireWholePackCount(BigDecimal quantity, Long childSkuId) {
        if (quantity == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Pack quantity is required for child SKU " + childSkuId);
        }
        int packCount;
        try {
            packCount = quantity.intValueExact();
        } catch (ArithmeticException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Pack quantity must be a whole number for child SKU " + childSkuId);
        }
        if (packCount <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Pack quantity must be greater than zero for child SKU " + childSkuId);
        }
        return packCount;
    }

    private String resolvePackagingSizeLabel(BulkPackRequest.PackLine line) {
        if (StringUtils.hasText(line.sizeLabel())) {
            return line.sizeLabel().trim();
        }
        BigDecimal sizeInLiters = PackagingSizeParser.parseSizeInLiters(line.unit());
        if (sizeInLiters == null || sizeInLiters.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Unable to parse size label for bulk pack: " + line.sizeLabel());
        }
        return sizeInLiters.stripTrailingZeros().toPlainString() + "L";
    }
}
