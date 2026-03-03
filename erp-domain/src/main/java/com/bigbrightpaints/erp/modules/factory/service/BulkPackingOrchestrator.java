package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex;

@Service
public class BulkPackingOrchestrator {

    private static final RoundingMode COST_ROUNDING = RoundingMode.HALF_UP;

    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRegistrar finishedGoodBatchRegistrar;
    private final PackingJournalBuilder packingJournalBuilder;

    public BulkPackingOrchestrator(FinishedGoodRepository finishedGoodRepository,
                                   FinishedGoodBatchRegistrar finishedGoodBatchRegistrar,
                                   PackingJournalBuilder packingJournalBuilder) {
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRegistrar = finishedGoodBatchRegistrar;
        this.packingJournalBuilder = packingJournalBuilder;
    }

    public BigDecimal calculateTotalVolume(List<BulkPackRequest.PackLine> packs) {
        BigDecimal total = BigDecimal.ZERO;
        for (BulkPackRequest.PackLine pack : packs) {
            BigDecimal sizeInLiters = extractSizeInLiters(pack.sizeLabel(), pack.unit());
            total = total.add(pack.quantity().multiply(sizeInLiters));
        }
        return total;
    }

    public int resolveTotalPacks(List<BulkPackRequest.PackLine> packs) {
        int totalPacks = 0;
        for (BulkPackRequest.PackLine line : packs) {
            int lineCount = requireWholePackCount(line.quantity(), line.childSkuId());
            try {
                totalPacks = Math.addExact(totalPacks, lineCount);
            } catch (ArithmeticException ex) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Pack quantity is too large for child SKU " + line.childSkuId());
            }
        }
        return totalPacks;
    }

    public void validatePackLines(BulkPackRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "Bulk pack request is required");
        }
        if (request.packs() == null || request.packs().isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT, "At least one pack line is required");
        }

        Set<Long> seenChildSkus = new HashSet<>();
        for (BulkPackRequest.PackLine line : request.packs()) {
            if (line == null || line.childSkuId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Child SKU is required for each pack line");
            }
            requireWholePackCount(line.quantity(), line.childSkuId());
            if (!seenChildSkus.add(line.childSkuId())) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Duplicate child SKU line is not allowed: " + line.childSkuId());
            }
        }
    }

    public String buildPackReference(FinishedGoodBatch bulkBatch, BulkPackRequest request) {
        String batchCode = StringUtils.hasText(bulkBatch.getBatchCode())
                ? bulkBatch.getBatchCode().trim().toUpperCase()
                : String.valueOf(bulkBatch.getId());

        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append("bulkBatchId=").append(bulkBatch.getId() != null ? bulkBatch.getId() : "null")
                .append("|skipPackaging=").append(Boolean.TRUE.equals(request.skipPackagingConsumption()));

        List<BulkPackRequest.PackLine> lines = request.packs().stream()
                .sorted(Comparator.comparing(BulkPackRequest.PackLine::childSkuId))
                .toList();
        for (BulkPackRequest.PackLine line : lines) {
            fingerprint.append("|")
                    .append(line.childSkuId() != null ? line.childSkuId() : "null")
                    .append("=")
                    .append(line.quantity() != null ? line.quantity().stripTrailingZeros().toPlainString() : "0");
        }

        String idempotencyKey = StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey().trim().toUpperCase()
                : "";
        String hash = sha256Hex(fingerprint + "|" + idempotencyKey, 12);
        return trimReference("PACK-", batchCode, hash, 64);
    }

    public FinishedGoodBatch createChildBatch(Company company,
                                              FinishedGoodBatch parentBatch,
                                              BulkPackRequest.PackLine line,
                                              BigDecimal bulkUnitCost,
                                              BigDecimal packagingCostPerUnit,
                                              LocalDate packDate,
                                              String packReference) {
        FinishedGood childFg = finishedGoodRepository.lockByCompanyAndId(company, line.childSkuId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Child SKU not found: " + line.childSkuId()));

        BigDecimal sizeInLiters = extractSizeInLiters(line.sizeLabel(), line.unit());
        BigDecimal childUnitCost = bulkUnitCost.multiply(sizeInLiters)
                .add(packagingCostPerUnit)
                .setScale(4, COST_ROUNDING);

        FinishedGoodBatchRegistrar.ReceiptRegistrationResult result = finishedGoodBatchRegistrar.registerReceipt(
                new FinishedGoodBatchRegistrar.ReceiptRegistrationRequest(
                        childFg,
                        parentBatch,
                        null,
                        line.quantity(),
                        childUnitCost,
                        parentBatch.getManufacturedAt(),
                        packDate,
                        false,
                        InventoryBatchSource.PRODUCTION,
                        line.sizeLabel(),
                        InventoryReference.PACKING_RECORD,
                        packReference,
                        "RECEIPT"));
        return result.batch();
    }

    public List<JournalEntryRequest.JournalLineRequest> buildBulkToSizeJournalLines(FinishedGoodBatch bulkBatch,
                                                                                     List<FinishedGoodBatch> childBatches,
                                                                                     BigDecimal volumeDeducted,
                                                                                     BulkPackCostSummary packagingCostSummary) {
        FinishedGood bulkFg = bulkBatch.getFinishedGood();
        Long bulkAccountId = bulkFg.getValuationAccountId();
        if (bulkAccountId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Bulk FG " + bulkFg.getProductCode() + " missing valuation account");
        }

        BigDecimal bulkValue = bulkBatch.getUnitCost().multiply(volumeDeducted).setScale(2, COST_ROUNDING);
        List<JournalEntryRequest.JournalLineRequest> childDebits = childBatches.stream()
                .map(this::toChildDebitLine)
                .toList();

        return packingJournalBuilder.buildBulkToSizePackingLines(
                bulkAccountId,
                bulkValue,
                packagingCostSummary.accountTotals(),
                childDebits);
    }

    private JournalEntryRequest.JournalLineRequest toChildDebitLine(FinishedGoodBatch childBatch) {
        Long childAccountId = childBatch.getFinishedGood().getValuationAccountId();
        if (childAccountId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Child FG " + childBatch.getFinishedGood().getProductCode()
                            + " missing valuation account");
        }
        BigDecimal childValue = childBatch.getUnitCost()
                .multiply(childBatch.getQuantityTotal())
                .setScale(2, COST_ROUNDING);
        return new JournalEntryRequest.JournalLineRequest(
                childAccountId,
                "Packed from bulk: " + childBatch.getSizeLabel(),
                childValue,
                BigDecimal.ZERO);
    }

    public BigDecimal extractSizeInLiters(String sizeLabel, String unit) {
        BigDecimal fromLabel = parseSizeInLiters(sizeLabel);
        if (fromLabel != null) {
            return fromLabel;
        }
        BigDecimal fromUnit = parseSizeInLiters(unit);
        if (fromUnit != null) {
            return fromUnit;
        }
        throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                "Unable to parse size label for bulk pack: " + sizeLabel);
    }

    public static BigDecimal parseSizeInLiters(String label) {
        return PackagingSizeParser.parseSizeInLiters(label);
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

    private String trimReference(String prefix, String batchCode, String hash, int maxLength) {
        String safePrefix = StringUtils.hasText(prefix) ? prefix : "";
        String safeBatch = StringUtils.hasText(batchCode) ? batchCode : "";
        String safeHash = StringUtils.hasText(hash) ? hash : "";
        String base = safePrefix + safeBatch + "-" + safeHash;
        if (base.length() <= maxLength) {
            return base;
        }
        int maxBatchLen = maxLength - safePrefix.length() - 1 - safeHash.length();
        if (maxBatchLen <= 0) {
            return safePrefix + safeHash;
        }
        String trimmedBatch = safeBatch.length() > maxBatchLen ? safeBatch.substring(0, maxBatchLen) : safeBatch;
        return safePrefix + trimmedBatch + "-" + safeHash;
    }
}
