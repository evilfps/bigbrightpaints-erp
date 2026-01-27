package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingConsumptionResult;
import com.bigbrightpaints.erp.modules.inventory.domain.*;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for converting bulk FG batches into sized child batches.
 * 
 * Flow:
 * 1. Production creates a bulk FG batch (parent SKU: Safari-WHITE)
 * 2. This service packs bulk into sized child SKUs (Safari-WHITE-1L, Safari-WHITE-4L)
 * 3. Optionally consumes packaging materials (buckets/cans)
 * 4. Posts accounting journal to move cost from bulk to child FG
 */
@Service
public class BulkPackingService {

    private static final RoundingMode COST_ROUNDING = RoundingMode.HALF_UP;

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;
    private final AccountingService accountingService;
    private final BatchNumberService batchNumberService;
    private final PackagingMaterialService packagingMaterialService;
    private final FinishedGoodsService finishedGoodsService;
    private final CompanyClock companyClock;

    public BulkPackingService(CompanyContextService companyContextService,
                              FinishedGoodRepository finishedGoodRepository,
                              FinishedGoodBatchRepository finishedGoodBatchRepository,
                              RawMaterialRepository rawMaterialRepository,
                              RawMaterialBatchRepository rawMaterialBatchRepository,
                              InventoryMovementRepository inventoryMovementRepository,
                              RawMaterialMovementRepository rawMaterialMovementRepository,
                              AccountingService accountingService,
                              BatchNumberService batchNumberService,
                              PackagingMaterialService packagingMaterialService,
                              FinishedGoodsService finishedGoodsService,
                              CompanyClock companyClock) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
        this.accountingService = accountingService;
        this.batchNumberService = batchNumberService;
        this.packagingMaterialService = packagingMaterialService;
        this.finishedGoodsService = finishedGoodsService;
        this.companyClock = companyClock;
    }

    private record PackagingCostSummary(BigDecimal totalCost,
                                        Map<Long, BigDecimal> accountTotals,
                                        Map<Integer, BigDecimal> lineCosts) {}

    /**
     * Pack a bulk FG batch into sized child batches.
     */
    @Transactional
    public BulkPackResponse pack(BulkPackRequest request) {
        Company company = companyContextService.requireCurrentCompany();

        // 1. Load and lock the bulk batch
        FinishedGoodBatch bulkBatch = finishedGoodBatchRepository.lockById(request.bulkBatchId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Bulk batch not found: " + request.bulkBatchId()));

        validateBulkBatch(bulkBatch, company);
        int totalPacks = resolveTotalPacks(request.packs());

        if (request.packagingMaterials() != null && !request.packagingMaterials().isEmpty()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Manual packaging materials are not supported; configure packaging BOM mappings instead");
        }

        // 2. Calculate total volume needed from bulk
        BigDecimal totalVolume = calculateTotalVolume(request.packs());
        if (totalVolume.compareTo(bulkBatch.getQuantityAvailable()) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    String.format("Insufficient bulk stock. Available: %s, Requested: %s",
                            bulkBatch.getQuantityAvailable(), totalVolume));
        }

        // 3. Consume packaging materials (BOM-based)
        String packReference = "PACK-" + bulkBatch.getBatchCode() + "-" + System.currentTimeMillis();
        PackagingCostSummary packagingCostSummary = new PackagingCostSummary(BigDecimal.ZERO, Map.of(), Map.of());
        if (request.skipPackagingConsumption() == null || !request.skipPackagingConsumption()) {
            packagingCostSummary = consumePackagingFromMappings(company, request.packs(), packReference);
        }
        BigDecimal packagingCost = packagingCostSummary.totalCost();

        // 4. Calculate cost per unit for child batches
        BigDecimal bulkUnitCost = bulkBatch.getUnitCost();
        BigDecimal fallbackPackagingCostPerUnit = totalPacks > 0 
                ? packagingCost.divide(BigDecimal.valueOf(totalPacks), 6, COST_ROUNDING)
                : BigDecimal.ZERO;

        // 5. Create child batches for each size SKU
        LocalDate packDate = request.packDate() != null ? request.packDate() : companyClock.today(company);
        List<FinishedGoodBatch> childBatches = new ArrayList<>();
        BigDecimal totalChildValue = BigDecimal.ZERO;

        Map<Integer, BigDecimal> lineCosts = packagingCostSummary.lineCosts();
        boolean hasLineCosts = lineCosts != null && !lineCosts.isEmpty();
        int lineIndex = 0;
        for (BulkPackRequest.PackLine line : request.packs()) {
            BigDecimal linePackagingCostPerUnit;
            if (hasLineCosts) {
                BigDecimal linePackagingTotal = lineCosts.get(lineIndex);
                if (linePackagingTotal != null && line.quantity().compareTo(BigDecimal.ZERO) > 0) {
                    linePackagingCostPerUnit = linePackagingTotal
                            .divide(line.quantity(), 6, COST_ROUNDING);
                } else {
                    linePackagingCostPerUnit = BigDecimal.ZERO;
                }
            } else {
                linePackagingCostPerUnit = fallbackPackagingCostPerUnit;
            }
            FinishedGoodBatch childBatch = createChildBatch(company, bulkBatch, line,
                    bulkUnitCost, linePackagingCostPerUnit, packDate, packReference);
            childBatches.add(childBatch);
            totalChildValue = totalChildValue.add(childBatch.getUnitCost()
                    .multiply(childBatch.getQuantityTotal()));
            lineIndex++;
        }

        // 6. Deduct from bulk batch
        bulkBatch.setQuantityAvailable(bulkBatch.getQuantityAvailable().subtract(totalVolume));
        bulkBatch.setQuantityTotal(bulkBatch.getQuantityTotal().subtract(totalVolume));
        finishedGoodBatchRepository.save(bulkBatch);

        // Also update the bulk FG stock
        FinishedGood bulkFg = bulkBatch.getFinishedGood();
        bulkFg.setCurrentStock(bulkFg.getCurrentStock().subtract(totalVolume));
        finishedGoodRepository.save(bulkFg);

        // Record issue movement for bulk batch depletion
        InventoryMovement bulkIssue = new InventoryMovement();
        bulkIssue.setFinishedGood(bulkFg);
        bulkIssue.setFinishedGoodBatch(bulkBatch);
        bulkIssue.setReferenceType(InventoryReference.PACKING_RECORD);
        bulkIssue.setReferenceId(packReference);
        bulkIssue.setMovementType("ISSUE");
        bulkIssue.setQuantity(totalVolume);
        bulkIssue.setUnitCost(bulkBatch.getUnitCost());
        inventoryMovementRepository.save(bulkIssue);

        finishedGoodsService.invalidateWeightedAverageCost(bulkFg.getId());

        // 7. Post packaging journal
        Long journalEntryId = postPackagingJournal(company, bulkBatch, childBatches,
                totalVolume, packagingCostSummary, packDate, request.notes(), packReference);
        if (journalEntryId != null) {
            linkPackagingMovementsToJournal(company, packReference, journalEntryId);
        }

        // 8. Build response
        List<BulkPackResponse.ChildBatchDto> childDtos = childBatches.stream()
                .map(this::toChildBatchDto)
                .toList();

        return new BulkPackResponse(
                bulkBatch.getId(),
                bulkBatch.getBatchCode(),
                totalVolume,
                bulkBatch.getQuantityAvailable(),
                packagingCost,
                childDtos,
                journalEntryId,
                Instant.now()
        );
    }

    /**
     * List available bulk batches for a finished good.
     */
    @Transactional
    public List<BulkPackResponse.ChildBatchDto> listBulkBatches(Long finishedGoodId) {
        Company company = companyContextService.requireCurrentCompany();
        FinishedGood fg = finishedGoodRepository.findByCompanyAndId(company, finishedGoodId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Finished good not found"));
        
        return finishedGoodBatchRepository.findAvailableBulkBatches(fg).stream()
                .map(this::toChildBatchDto)
                .toList();
    }

    /**
     * List child batches created from a parent bulk batch.
     */
    @Transactional
    public List<BulkPackResponse.ChildBatchDto> listChildBatches(Long parentBatchId) {
        Company company = companyContextService.requireCurrentCompany();
        FinishedGoodBatch parentBatch = finishedGoodBatchRepository.findById(parentBatchId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Parent batch not found"));
        if (!parentBatch.getFinishedGood().getCompany().getId().equals(company.getId())) {
            throw new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                    "Parent batch not found");
        }
        
        return finishedGoodBatchRepository.findByParentBatch(parentBatch).stream()
                .map(this::toChildBatchDto)
                .toList();
    }

    private void validateBulkBatch(FinishedGoodBatch batch, Company company) {
        if (!batch.getFinishedGood().getCompany().getId().equals(company.getId())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Batch does not belong to this company");
        }
        if (!batch.isBulk()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Batch " + batch.getBatchCode() + " is not marked as bulk");
        }
        if (batch.getQuantityAvailable().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Batch has no available quantity: " + batch.getBatchCode());
        }
    }

    private BigDecimal calculateTotalVolume(List<BulkPackRequest.PackLine> packs) {
        BigDecimal total = BigDecimal.ZERO;
        for (BulkPackRequest.PackLine pack : packs) {
            // quantity is the number of units (e.g., 20 buckets of 1L)
            // We need to extract the size from sizeLabel or unit
            BigDecimal sizeInLiters = extractSizeInLiters(pack.sizeLabel(), pack.unit());
            total = total.add(pack.quantity().multiply(sizeInLiters));
        }
        return total;
    }

    private int resolveTotalPacks(List<BulkPackRequest.PackLine> packs) {
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

    private int requireWholePackCount(BigDecimal quantity, Long childSkuId) {
        if (quantity == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Pack quantity is required for child SKU " + childSkuId);
        }
        try {
            return quantity.intValueExact();
        } catch (ArithmeticException ex) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Pack quantity must be a whole number for child SKU " + childSkuId);
        }
    }

    static final Pattern SIZE_LABEL_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(ML|L|LTR|LITRE|LITER)");

    private BigDecimal extractSizeInLiters(String sizeLabel, String unit) {
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

    static BigDecimal parseSizeInLiters(String label) {
        if (!StringUtils.hasText(label)) {
            return null;
        }
        String normalized = label.trim().toUpperCase();
        Matcher matcher = SIZE_LABEL_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
        String unit = matcher.group(2);
        if ("ML".equals(unit)) {
            return value.divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);
        }
        return value;
    }

    private PackagingCostSummary consumePackagingMaterials(Company company,
                                                           List<BulkPackRequest.MaterialConsumption> materials,
                                                           String reference) {
        BigDecimal totalCost = BigDecimal.ZERO;
        Map<Long, BigDecimal> accountTotals = new HashMap<>();

        for (BulkPackRequest.MaterialConsumption material : materials) {
            RawMaterial rm = rawMaterialRepository.lockByCompanyAndId(company, material.materialId())
                    .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                            "Raw material not found: " + material.materialId()));

            if (rm.getInventoryAccountId() == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Packaging material " + rm.getSku() + " missing inventory account");
            }
            if (rm.getCurrentStock().compareTo(material.quantity()) < 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        String.format("Insufficient %s. Available: %s, Required: %s",
                                rm.getSku(), rm.getCurrentStock(), material.quantity()));
            }

            // Consume using FIFO
            BigDecimal remaining = material.quantity();
            BigDecimal consumedCost = BigDecimal.ZERO;
            BigDecimal weightedAverageCost = isWeightedAverage(rm.getCostingMethod())
                    ? rawMaterialBatchRepository.calculateWeightedAverageCost(rm)
                    : null;

            List<RawMaterialBatch> batches = rawMaterialBatchRepository
                    .findAvailableBatchesFIFO(rm);
            
            for (RawMaterialBatch batch : batches) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                
                BigDecimal available = batch.getQuantity();
                BigDecimal toConsume = remaining.min(available);
                int updated = rawMaterialBatchRepository.deductQuantityIfSufficient(batch.getId(), toConsume);
                if (updated == 0) {
                    throw new ApplicationException(ErrorCode.INTERNAL_CONCURRENCY_FAILURE,
                            "Concurrent modification detected for packaging batch " + batch.getBatchCode());
                }

                RawMaterialMovement movement = new RawMaterialMovement();
                movement.setRawMaterial(rm);
                movement.setRawMaterialBatch(batch);
                movement.setReferenceType(InventoryReference.PACKING_RECORD);
                movement.setReferenceId(reference);
                movement.setMovementType("ISSUE");
                movement.setQuantity(toConsume);
                BigDecimal unitCost = weightedAverageCost != null
                        ? weightedAverageCost
                        : (batch.getCostPerUnit() != null ? batch.getCostPerUnit() : BigDecimal.ZERO);
                movement.setUnitCost(unitCost);
                rawMaterialMovementRepository.save(movement);

                consumedCost = consumedCost.add(toConsume.multiply(unitCost));
                remaining = remaining.subtract(toConsume);
            }

            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                        "Insufficient batch availability for packaging material " + rm.getSku());
            }
            
            // Update RM stock
            rm.setCurrentStock(rm.getCurrentStock().subtract(material.quantity()));
            rawMaterialRepository.save(rm);
            
            consumedCost = consumedCost.setScale(2, COST_ROUNDING);
            totalCost = totalCost.add(consumedCost);
            accountTotals.merge(rm.getInventoryAccountId(), consumedCost, BigDecimal::add);
        }

        return new PackagingCostSummary(totalCost, accountTotals, Map.of());
    }

    private boolean isWeightedAverage(String method) {
        if (method == null) {
            return false;
        }
        String normalized = method.trim().toUpperCase();
        return "WAC".equals(normalized) || "WEIGHTED_AVERAGE".equals(normalized) || "WEIGHTED-AVERAGE".equals(normalized);
    }

    private PackagingCostSummary consumePackagingFromMappings(Company company,
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
                    reference
            );

            if (!result.isConsumed()) {
                continue;
            }

            totalCost = totalCost.add(result.totalCost());
            lineCosts.put(currentIndex, result.totalCost());
            result.accountTotalsOrEmpty().forEach(
                    (accountId, amount) -> accountTotals.merge(accountId, amount, BigDecimal::add));
        }

        return new PackagingCostSummary(totalCost, accountTotals, lineCosts);
    }

    private String resolvePackagingSizeLabel(BulkPackRequest.PackLine line) {
        if (StringUtils.hasText(line.sizeLabel())) {
            return line.sizeLabel().trim();
        }
        BigDecimal sizeInLiters = extractSizeInLiters(line.sizeLabel(), line.unit());
        return sizeInLiters.stripTrailingZeros().toPlainString() + "L";
    }

    private void linkPackagingMovementsToJournal(Company company, String referenceId, Long journalEntryId) {
        if (journalEntryId == null) {
            return;
        }
        List<RawMaterialMovement> movements = rawMaterialMovementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company, InventoryReference.PACKING_RECORD, referenceId);
        if (movements.isEmpty()) {
            return;
        }
        movements.forEach(movement -> movement.setJournalEntryId(journalEntryId));
        rawMaterialMovementRepository.saveAll(movements);
    }

    private FinishedGoodBatch createChildBatch(Company company,
                                               FinishedGoodBatch parentBatch,
                                               BulkPackRequest.PackLine line,
                                               BigDecimal bulkUnitCost,
                                               BigDecimal packagingCostPerUnit,
                                               LocalDate packDate,
                                               String packReference) {
        // Get or create the child SKU (FinishedGood)
        FinishedGood childFg = finishedGoodRepository.lockByCompanyAndId(company, line.childSkuId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Child SKU not found: " + line.childSkuId()));

        // Calculate child unit cost:
        // (bulk cost per liter * liters per unit) + packaging cost per unit
        BigDecimal sizeInLiters = extractSizeInLiters(line.sizeLabel(), line.unit());
        BigDecimal childUnitCost = bulkUnitCost.multiply(sizeInLiters)
                .add(packagingCostPerUnit)
                .setScale(4, COST_ROUNDING);

        // Create child batch
        FinishedGoodBatch childBatch = new FinishedGoodBatch();
        childBatch.setFinishedGood(childFg);
        childBatch.setParentBatch(parentBatch);
        childBatch.setBatchCode(batchNumberService.nextFinishedGoodBatchCode(childFg, packDate));
        childBatch.setQuantityTotal(line.quantity());
        childBatch.setQuantityAvailable(line.quantity());
        childBatch.setUnitCost(childUnitCost);
        childBatch.setManufacturedAt(parentBatch.getManufacturedAt());
        childBatch.setBulk(false);
        childBatch.setSizeLabel(line.sizeLabel());
        
        FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(childBatch);

        // Update child FG stock
        childFg.setCurrentStock(childFg.getCurrentStock().add(line.quantity()));
        finishedGoodRepository.save(childFg);

        // Record inventory movement
        InventoryMovement movement = new InventoryMovement();
        movement.setFinishedGood(childFg);
        movement.setFinishedGoodBatch(savedBatch);
        movement.setReferenceType(InventoryReference.PACKING_RECORD);
        movement.setReferenceId(packReference);
        movement.setMovementType("RECEIPT");
        movement.setQuantity(line.quantity());
        movement.setUnitCost(childUnitCost);
        inventoryMovementRepository.save(movement);
        finishedGoodsService.invalidateWeightedAverageCost(childFg.getId());

        return savedBatch;
    }

    private Long postPackagingJournal(Company company,
                                      FinishedGoodBatch bulkBatch,
                                      List<FinishedGoodBatch> childBatches,
                                      BigDecimal volumeDeducted,
                                      PackagingCostSummary packagingCostSummary,
                                      LocalDate entryDate,
                                      String notes,
                                      String packReference) {
        FinishedGood bulkFg = bulkBatch.getFinishedGood();
        Long bulkAccountId = bulkFg.getValuationAccountId();
        
        if (bulkAccountId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Bulk FG " + bulkFg.getProductCode() + " missing valuation account");
        }

        // Calculate bulk value to credit
        BigDecimal bulkValue = bulkBatch.getUnitCost().multiply(volumeDeducted)
                .setScale(2, COST_ROUNDING);

        // Build journal lines
        List<JournalLineRequest> lines = new ArrayList<>();

        // Credit: Bulk FG Inventory
        lines.add(new JournalLineRequest(bulkAccountId,
                "Bulk consumption for packaging", BigDecimal.ZERO, bulkValue));

        // Credit: Packaging RM Inventory (per account)
        for (Map.Entry<Long, BigDecimal> entry : packagingCostSummary.accountTotals().entrySet()) {
            BigDecimal creditAmount = entry.getValue().setScale(2, COST_ROUNDING);
            if (creditAmount.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(new JournalLineRequest(entry.getKey(),
                        "Packaging material consumption", BigDecimal.ZERO, creditAmount));
            }
        }

        // Debit: Each child FG Inventory
        for (FinishedGoodBatch childBatch : childBatches) {
            Long childAccountId = childBatch.getFinishedGood().getValuationAccountId();
            if (childAccountId == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Child FG " + childBatch.getFinishedGood().getProductCode() + 
                        " missing valuation account");
            }
            BigDecimal childValue = childBatch.getUnitCost()
                    .multiply(childBatch.getQuantityTotal())
                    .setScale(2, COST_ROUNDING);
            lines.add(new JournalLineRequest(childAccountId,
                    "Packed from bulk: " + childBatch.getSizeLabel(), childValue, BigDecimal.ZERO));
        }

        // Adjust for rounding variance so debits = credits (bulk + packaging)
        BigDecimal totalDebit = lines.stream()
                .map(JournalLineRequest::debit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = lines.stream()
                .map(JournalLineRequest::credit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalDebit.compareTo(totalCredit) != 0 && lines.size() > 1) {
            BigDecimal variance = totalCredit.subtract(totalDebit);
            // Adjust the last debit line (child FG) to keep balanced
            for (int i = lines.size() - 1; i >= 0; i--) {
                JournalLineRequest line = lines.get(i);
                if (line.debit().compareTo(BigDecimal.ZERO) > 0) {
                    lines.set(i, new JournalLineRequest(
                            line.accountId(),
                            line.description(),
                            line.debit().add(variance),
                            line.credit()));
                    break;
                }
            }
        }

        String reference = packReference;
        String memo = "Bulk-to-size packaging: " + bulkBatch.getBatchCode();
        if (notes != null) {
            memo += " - " + notes;
        }

        JournalEntryRequest journalRequest = new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null, null, false,
                lines
        );

        JournalEntryDto journal = accountingService.createJournalEntry(journalRequest);
        return journal.id();
    }

    private BulkPackResponse.ChildBatchDto toChildBatchDto(FinishedGoodBatch batch) {
        FinishedGood fg = batch.getFinishedGood();
        return new BulkPackResponse.ChildBatchDto(
                batch.getId(),
                batch.getPublicId(),
                batch.getBatchCode(),
                fg.getId(),
                fg.getProductCode(),
                fg.getName(),
                batch.getSizeLabel(),
                batch.getQuantityAvailable(),
                batch.getUnitCost(),
                batch.getUnitCost().multiply(batch.getQuantityAvailable())
        );
    }
}
