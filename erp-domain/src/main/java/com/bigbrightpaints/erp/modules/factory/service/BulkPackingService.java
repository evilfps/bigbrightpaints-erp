package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest.JournalLineRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.inventory.domain.*;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    private static final String PACKAGING_REFERENCE_TYPE = "PACKAGING";
    private static final String PACK_REFERENCE_PREFIX = "PACK-";
    private static final int PACK_REFERENCE_HASH_LENGTH = 16;
    private static final int PACK_REFERENCE_MAX_LENGTH = 64;

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final AccountingService accountingService;
    private final JournalEntryRepository journalEntryRepository;
    private final BatchNumberService batchNumberService;

    public BulkPackingService(CompanyContextService companyContextService,
                              FinishedGoodRepository finishedGoodRepository,
                              FinishedGoodBatchRepository finishedGoodBatchRepository,
                              RawMaterialRepository rawMaterialRepository,
                              RawMaterialBatchRepository rawMaterialBatchRepository,
                              InventoryMovementRepository inventoryMovementRepository,
                              AccountingService accountingService,
                              JournalEntryRepository journalEntryRepository,
                              BatchNumberService batchNumberService) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.accountingService = accountingService;
        this.journalEntryRepository = journalEntryRepository;
        this.batchNumberService = batchNumberService;
    }

    private record PackagingCostSummary(BigDecimal totalCost, Map<Long, BigDecimal> accountTotals) {}

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

        LocalDate packDate = request.packDate() != null ? request.packDate() : LocalDate.now();
        String packReference = buildPackReference(bulkBatch, request, packDate);
        validateBulkBatchCompany(bulkBatch, company);
        List<InventoryMovement> existingMovements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(PACKAGING_REFERENCE_TYPE, packReference);
        if (!existingMovements.isEmpty()) {
            BigDecimal totalVolume = calculateTotalVolume(request.packs());
            ensureBulkIssueMovement(bulkBatch, packReference, existingMovements, totalVolume);
            Long journalEntryId = resolveJournalEntryId(company, packReference, existingMovements);
            linkMovementsToJournal(existingMovements, journalEntryId);
            return buildIdempotentResponse(bulkBatch, existingMovements, journalEntryId, totalVolume);
        }

        validateBulkBatchAvailability(bulkBatch);

        // 2. Calculate total volume needed from bulk
        BigDecimal totalVolume = calculateTotalVolume(request.packs());
        if (totalVolume.compareTo(bulkBatch.getQuantityAvailable()) > 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    String.format("Insufficient bulk stock. Available: %s, Requested: %s",
                            bulkBatch.getQuantityAvailable(), totalVolume));
        }

        // 3. Consume packaging materials (optional)
        PackagingCostSummary packagingCostSummary = new PackagingCostSummary(BigDecimal.ZERO, Map.of());
        if (request.packagingMaterials() != null && !request.packagingMaterials().isEmpty()) {
            packagingCostSummary = consumePackagingMaterials(company, request.packagingMaterials(),
                    "BULK-" + packReference);
        }
        BigDecimal packagingCost = packagingCostSummary.totalCost();

        // 4. Calculate cost per unit for child batches
        BigDecimal bulkUnitCost = bulkBatch.getUnitCost();
        int totalPacks = request.packs().stream()
                .mapToInt(p -> p.quantity().intValue())
                .sum();
        BigDecimal packagingCostPerUnit = totalPacks > 0 
                ? packagingCost.divide(BigDecimal.valueOf(totalPacks), 6, COST_ROUNDING)
                : BigDecimal.ZERO;

        // 5. Create child batches for each size SKU
        List<FinishedGoodBatch> childBatches = new ArrayList<>();
        BigDecimal totalChildValue = BigDecimal.ZERO;

        for (BulkPackRequest.PackLine line : request.packs()) {
            FinishedGoodBatch childBatch = createChildBatch(company, bulkBatch, line, 
                    bulkUnitCost, packagingCostPerUnit, packDate, packReference);
            childBatches.add(childBatch);
            totalChildValue = totalChildValue.add(childBatch.getUnitCost()
                    .multiply(childBatch.getQuantityTotal()));
        }

        // 6. Deduct from bulk batch
        bulkBatch.setQuantityAvailable(bulkBatch.getQuantityAvailable().subtract(totalVolume));
        finishedGoodBatchRepository.save(bulkBatch);

        // Also update the bulk FG stock
        FinishedGood bulkFg = bulkBatch.getFinishedGood();
        bulkFg.setCurrentStock(bulkFg.getCurrentStock().subtract(totalVolume));
        finishedGoodRepository.save(bulkFg);

        // 7. Record bulk ISSUE movement for audit linkage
        recordBulkIssueMovement(bulkBatch, totalVolume, packReference);

        // 8. Post packaging journal
        Long journalEntryId = postPackagingJournal(company, bulkBatch, childBatches,
                totalVolume, packagingCostSummary, packDate, request.notes(), packReference);
        linkMovementsToJournal(packReference, journalEntryId);

        // 9. Build response
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

    private void validateBulkBatchCompany(FinishedGoodBatch batch, Company company) {
        if (!batch.getFinishedGood().getCompany().getId().equals(company.getId())) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Batch does not belong to this company");
        }
    }

    private void validateBulkBatchAvailability(FinishedGoodBatch batch) {
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

    private BigDecimal extractSizeInLiters(String sizeLabel, String unit) {
        // Try to extract numeric size from label like "1L", "4L", "20L"
        String label = sizeLabel != null ? sizeLabel.trim().toUpperCase() : "";
        if (label.endsWith("L")) {
            try {
                return new BigDecimal(label.substring(0, label.length() - 1));
            } catch (NumberFormatException ignored) {}
        }
        // Try from unit
        if (unit != null) {
            String u = unit.trim().toUpperCase();
            if (u.endsWith("L")) {
                try {
                    return new BigDecimal(u.substring(0, u.length() - 1));
                } catch (NumberFormatException ignored) {}
            }
        }
        // Default to 1 if can't parse
        return BigDecimal.ONE;
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
            
            List<RawMaterialBatch> batches = rawMaterialBatchRepository
                    .findAvailableBatchesFIFO(rm);
            
            for (RawMaterialBatch batch : batches) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                
                BigDecimal available = batch.getQuantity();
                BigDecimal toConsume = remaining.min(available);
                batch.setQuantity(available.subtract(toConsume));
                rawMaterialBatchRepository.save(batch);
                
                consumedCost = consumedCost.add(toConsume.multiply(batch.getCostPerUnit()));
                remaining = remaining.subtract(toConsume);
            }
            
            // Update RM stock
            rm.setCurrentStock(rm.getCurrentStock().subtract(material.quantity()));
            rawMaterialRepository.save(rm);
            
            totalCost = totalCost.add(consumedCost);
            accountTotals.merge(rm.getInventoryAccountId(), consumedCost, BigDecimal::add);
        }

        return new PackagingCostSummary(totalCost, accountTotals);
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
        movement.setReferenceType(PACKAGING_REFERENCE_TYPE);
        movement.setReferenceId(packReference);
        movement.setMovementType("RECEIPT");
        movement.setQuantity(line.quantity());
        movement.setUnitCost(childUnitCost);
        inventoryMovementRepository.save(movement);

        return savedBatch;
    }

    private Long postPackagingJournal(Company company,
                                      FinishedGoodBatch bulkBatch,
                                      List<FinishedGoodBatch> childBatches,
                                      BigDecimal volumeDeducted,
                                      PackagingCostSummary packagingCostSummary,
                                      LocalDate entryDate,
                                      String notes,
                                      String reference) {
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

    private InventoryMovement recordBulkIssueMovement(FinishedGoodBatch bulkBatch, BigDecimal totalVolume, String packReference) {
        InventoryMovement issue = new InventoryMovement();
        issue.setFinishedGood(bulkBatch.getFinishedGood());
        issue.setFinishedGoodBatch(bulkBatch);
        issue.setReferenceType(PACKAGING_REFERENCE_TYPE);
        issue.setReferenceId(packReference);
        issue.setMovementType("ISSUE");
        issue.setQuantity(totalVolume);
        issue.setUnitCost(bulkBatch.getUnitCost());
        return inventoryMovementRepository.save(issue);
    }

    private void ensureBulkIssueMovement(FinishedGoodBatch bulkBatch,
                                         String packReference,
                                         List<InventoryMovement> movements,
                                         BigDecimal totalVolume) {
        boolean hasIssue = movements.stream()
                .anyMatch(movement -> "ISSUE".equalsIgnoreCase(movement.getMovementType()));
        if (!hasIssue) {
            InventoryMovement issue = recordBulkIssueMovement(bulkBatch, totalVolume, packReference);
            movements.add(issue);
        }
    }

    private Long resolveJournalEntryId(Company company,
                                       String packReference,
                                       List<InventoryMovement> movements) {
        Optional<Long> fromMovements = movements.stream()
                .map(InventoryMovement::getJournalEntryId)
                .filter(Objects::nonNull)
                .findFirst();
        if (fromMovements.isPresent()) {
            return fromMovements.get();
        }
        return journalEntryRepository.findByCompanyAndReferenceNumber(company, packReference)
                .map(JournalEntry::getId)
                .orElse(null);
    }

    private void linkMovementsToJournal(String packReference, Long journalEntryId) {
        if (journalEntryId == null) {
            return;
        }
        List<InventoryMovement> movements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(PACKAGING_REFERENCE_TYPE, packReference);
        if (movements.isEmpty()) {
            return;
        }
        linkMovementsToJournal(movements, journalEntryId);
    }

    private void linkMovementsToJournal(List<InventoryMovement> movements, Long journalEntryId) {
        if (journalEntryId == null || movements.isEmpty()) {
            return;
        }
        boolean updated = false;
        for (InventoryMovement movement : movements) {
            if (movement.getJournalEntryId() == null || !movement.getJournalEntryId().equals(journalEntryId)) {
                movement.setJournalEntryId(journalEntryId);
                updated = true;
            }
        }
        if (updated) {
            inventoryMovementRepository.saveAll(movements);
        }
    }

    private BulkPackResponse buildIdempotentResponse(FinishedGoodBatch bulkBatch,
                                                     List<InventoryMovement> movements,
                                                     Long journalEntryId,
                                                     BigDecimal totalVolume) {
        BigDecimal volumeDeducted = movements.stream()
                .filter(movement -> "ISSUE".equalsIgnoreCase(movement.getMovementType()))
                .map(InventoryMovement::getQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (volumeDeducted.compareTo(BigDecimal.ZERO) == 0) {
            volumeDeducted = totalVolume;
        }
        Map<Long, FinishedGoodBatch> childBatchMap = new LinkedHashMap<>();
        for (InventoryMovement movement : movements) {
            if (!"RECEIPT".equalsIgnoreCase(movement.getMovementType())) {
                continue;
            }
            FinishedGoodBatch batch = movement.getFinishedGoodBatch();
            if (batch != null) {
                childBatchMap.putIfAbsent(batch.getId(), batch);
            }
        }
        List<BulkPackResponse.ChildBatchDto> childDtos = childBatchMap.values().stream()
                .map(this::toChildBatchDto)
                .toList();
        BigDecimal packagingCost = resolvePackagingCost(journalEntryId);
        return new BulkPackResponse(
                bulkBatch.getId(),
                bulkBatch.getBatchCode(),
                volumeDeducted,
                bulkBatch.getQuantityAvailable(),
                packagingCost,
                childDtos,
                journalEntryId,
                Instant.now()
        );
    }

    private BigDecimal resolvePackagingCost(Long journalEntryId) {
        if (journalEntryId == null) {
            return BigDecimal.ZERO;
        }
        Optional<JournalEntry> entry = journalEntryRepository.findById(journalEntryId);
        if (entry.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return entry.get().getLines().stream()
                .filter(line -> line.getDescription() != null
                        && line.getDescription().startsWith("Packaging material consumption"))
                .map(line -> line.getCredit() == null ? BigDecimal.ZERO : line.getCredit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String buildPackReference(FinishedGoodBatch bulkBatch,
                                      BulkPackRequest request,
                                      LocalDate packDate) {
        String fingerprint = buildPackFingerprint(bulkBatch, request, packDate);
        String hash = sha256Hex(fingerprint, PACK_REFERENCE_HASH_LENGTH);
        String batchCode = bulkBatch.getBatchCode() != null ? bulkBatch.getBatchCode() : bulkBatch.getId().toString();
        String suffix = "-" + hash;
        int maxBatchLength = Math.max(1, PACK_REFERENCE_MAX_LENGTH - PACK_REFERENCE_PREFIX.length() - suffix.length());
        if (batchCode.length() > maxBatchLength) {
            batchCode = batchCode.substring(0, maxBatchLength);
        }
        return PACK_REFERENCE_PREFIX + batchCode + suffix;
    }

    private String buildPackFingerprint(FinishedGoodBatch bulkBatch,
                                        BulkPackRequest request,
                                        LocalDate packDate) {
        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append("bulkBatchId=").append(bulkBatch.getId())
                .append("|bulkBatchCode=").append(bulkBatch.getBatchCode())
                .append("|packDate=").append(packDate);
        List<BulkPackRequest.PackLine> packs = new ArrayList<>(request.packs());
        packs.sort(Comparator
                .comparing(BulkPackRequest.PackLine::childSkuId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(line -> safeQuantity(line.quantity()))
                .thenComparing(line -> normalizeToken(line.sizeLabel()))
                .thenComparing(line -> normalizeToken(line.unit())));
        fingerprint.append("|packs=");
        for (BulkPackRequest.PackLine line : packs) {
            fingerprint.append(line.childSkuId()).append(':')
                    .append(formatQuantity(line.quantity())).append(':')
                    .append(normalizeToken(line.sizeLabel())).append(':')
                    .append(normalizeToken(line.unit())).append(';');
        }
        List<BulkPackRequest.MaterialConsumption> materials = request.packagingMaterials() == null
                ? List.of()
                : new ArrayList<>(request.packagingMaterials());
        materials.sort(Comparator
                .comparing(BulkPackRequest.MaterialConsumption::materialId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(material -> safeQuantity(material.quantity()))
                .thenComparing(material -> normalizeToken(material.unit())));
        fingerprint.append("|materials=");
        for (BulkPackRequest.MaterialConsumption material : materials) {
            fingerprint.append(material.materialId()).append(':')
                    .append(formatQuantity(material.quantity())).append(':')
                    .append(normalizeToken(material.unit())).append(';');
        }
        fingerprint.append("|packedBy=").append(normalizeFreeText(request.packedBy()))
                .append("|notes=").append(normalizeFreeText(request.notes()));
        return fingerprint.toString();
    }

    private BigDecimal safeQuantity(BigDecimal quantity) {
        return quantity == null ? BigDecimal.ZERO : quantity;
    }

    private String formatQuantity(BigDecimal quantity) {
        if (quantity == null) {
            return "0";
        }
        return quantity.stripTrailingZeros().toPlainString();
    }

    private String normalizeToken(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private String normalizeFreeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256Hex(String input, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            String fullHex = HexFormat.of().formatHex(hash);
            return fullHex.substring(0, Math.min(length, fullHex.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
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
