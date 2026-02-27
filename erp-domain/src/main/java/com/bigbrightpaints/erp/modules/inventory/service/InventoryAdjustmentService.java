package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustment;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentLine;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentType;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentDto;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentLineDto;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentReversalRequest;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryAdjustmentService {

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final InventoryAdjustmentRepository adjustmentRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final AccountingFacade accountingFacade;
    private final AccountingService accountingService;
    private final ReferenceNumberService referenceNumberService;
    private final CompanyClock companyClock;
    private final FinishedGoodsService finishedGoodsService;
    private final TransactionTemplate transactionTemplate;

    public InventoryAdjustmentService(CompanyContextService companyContextService,
                                      FinishedGoodRepository finishedGoodRepository,
                                      InventoryAdjustmentRepository adjustmentRepository,
                                      InventoryMovementRepository inventoryMovementRepository,
                                      FinishedGoodBatchRepository finishedGoodBatchRepository,
                                      AccountingFacade accountingFacade,
                                      AccountingService accountingService,
                                      ReferenceNumberService referenceNumberService,
                                      CompanyClock companyClock,
                                      FinishedGoodsService finishedGoodsService,
                                      PlatformTransactionManager transactionManager) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.accountingFacade = accountingFacade;
        this.accountingService = accountingService;
        this.referenceNumberService = referenceNumberService;
        this.companyClock = companyClock;
        this.finishedGoodsService = finishedGoodsService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public List<InventoryAdjustmentDto> listAdjustments() {
        Company company = companyContextService.requireCurrentCompany();
        return adjustmentRepository.findByCompanyOrderByAdjustmentDateDesc(company).stream()
                .map(this::toDto)
                .toList();
    }

    @Retryable(value = {OptimisticLockingFailureException.class, DataIntegrityViolationException.class},
            maxAttempts = 3, backoff = @Backoff(delay = 100))
    public InventoryAdjustmentDto createAdjustment(InventoryAdjustmentRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("Adjustment lines are required");
        }
        List<InventoryAdjustmentRequest.LineRequest> sortedLines = request.lines().stream()
                .sorted(Comparator.comparing(InventoryAdjustmentRequest.LineRequest::finishedGoodId))
                .toList();
        Company company = companyContextService.requireCurrentCompany();
        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Idempotency key is required for inventory adjustments");
        }
        LocalDate resolvedAdjustmentDate = request.adjustmentDate() != null
                ? request.adjustmentDate()
                : resolveCurrentDate(company);
        String requestSignature = buildAdjustmentSignature(request, sortedLines, resolvedAdjustmentDate);
        InventoryAdjustment existing = adjustmentRepository.findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
                .orElse(null);
        if (existing != null) {
            assertIdempotencyMatch(existing, requestSignature, idempotencyKey);
            return toDto(existing);
        }
        try {
            return transactionTemplate.execute(status ->
                    createAdjustmentInternal(request, sortedLines, company, resolvedAdjustmentDate, idempotencyKey, requestSignature));
        } catch (RuntimeException ex) {
            if (!isDataIntegrityViolation(ex)) {
                throw ex;
            }
            InventoryAdjustment concurrent = adjustmentRepository.findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
                    .orElseThrow(() -> ex);
            assertIdempotencyMatch(concurrent, requestSignature, idempotencyKey);
            return toDto(concurrent);
        }
    }

    @Retryable(value = {OptimisticLockingFailureException.class, DataIntegrityViolationException.class},
            maxAttempts = 3, backoff = @Backoff(delay = 100))
    public InventoryAdjustmentDto reverseAdjustment(Long adjustmentId, InventoryAdjustmentReversalRequest request) {
        if (adjustmentId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Adjustment id is required");
        }
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Inventory adjustment reversal request is required");
        }
        Company company = companyContextService.requireCurrentCompany();
        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Idempotency key is required for inventory adjustment reversals");
        }
        LocalDate resolvedReversalDate = request.reversalDate() != null
                ? request.reversalDate()
                : resolveCurrentDate(company);
        String requestSignature = buildReversalSignature(adjustmentId, request, resolvedReversalDate);

        InventoryAdjustment existingByKey = adjustmentRepository
                .findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
                .orElse(null);
        if (existingByKey != null) {
            assertReversalIdempotencyMatch(existingByKey, adjustmentId, requestSignature, idempotencyKey);
            return toDto(existingByKey);
        }

        try {
            return transactionTemplate.execute(status -> reverseAdjustmentInternal(
                    company, adjustmentId, request, resolvedReversalDate, idempotencyKey, requestSignature));
        } catch (RuntimeException ex) {
            if (!isDataIntegrityViolation(ex)) {
                throw ex;
            }
            InventoryAdjustment concurrent = adjustmentRepository
                    .findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
                    .orElseThrow(() -> ex);
            assertReversalIdempotencyMatch(concurrent, adjustmentId, requestSignature, idempotencyKey);
            return toDto(concurrent);
        }
    }

    private InventoryAdjustmentDto reverseAdjustmentInternal(Company company,
                                                             Long adjustmentId,
                                                             InventoryAdjustmentReversalRequest request,
                                                             LocalDate resolvedReversalDate,
                                                             String idempotencyKey,
                                                             String requestSignature) {
        InventoryAdjustment original = adjustmentRepository.lockByCompanyAndId(company, adjustmentId)
                .flatMap(locked -> adjustmentRepository.findWithLinesByCompanyAndId(company, locked.getId()))
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Inventory adjustment not found"));
        if (!"POSTED".equalsIgnoreCase(original.getStatus())) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Only posted inventory adjustments can be reversed");
        }
        if (original.getReversalOf() != null) {
            throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                    "Reversal adjustments cannot be reversed again");
        }
        InventoryAdjustment existingReversal = adjustmentRepository.findByCompanyAndReversalOf(company, original)
                .orElse(null);
        if (existingReversal != null) {
            if (!idempotencyKey.equals(existingReversal.getIdempotencyKey())) {
                throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                        "Inventory adjustment has already been reversed")
                        .withDetail("adjustmentId", adjustmentId)
                        .withDetail("existingReversalId", existingReversal.getId())
                        .withDetail("existingIdempotencyKey", existingReversal.getIdempotencyKey());
            }
            assertReversalIdempotencyMatch(existingReversal, adjustmentId, requestSignature, idempotencyKey);
            return toDto(existingReversal);
        }

        List<Long> finishedGoodIds = original.getLines().stream()
                .map(line -> line.getFinishedGood().getId())
                .distinct()
                .toList();
        List<FinishedGood> lockedFinishedGoods = finishedGoodRepository.lockByCompanyAndIdInOrderById(company, finishedGoodIds);
        Map<Long, FinishedGood> finishedGoodsById = new HashMap<>();
        for (FinishedGood finishedGood : lockedFinishedGoods) {
            finishedGoodsById.put(finishedGood.getId(), finishedGood);
        }
        if (finishedGoodsById.size() != finishedGoodIds.size()) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Finished good not found for reversal");
        }

        InventoryAdjustment reversal = new InventoryAdjustment();
        reversal.setCompany(company);
        reversal.setReferenceNumber(resolveReference(company, original.getType()));
        reversal.setAdjustmentDate(resolvedReversalDate);
        reversal.setType(original.getType());
        reversal.setReason(resolveReversalReason(original, request));
        reversal.setCreatedBy(resolveCurrentUser());
        reversal.setIdempotencyKey(idempotencyKey);
        reversal.setIdempotencyHash(requestSignature);
        reversal.setReversalOf(original);
        reversal.setStatus("POSTED");
        reversal.setTotalAmount(original.getTotalAmount());

        for (InventoryAdjustmentLine line : original.getLines()) {
            FinishedGood finishedGood = finishedGoodsById.get(line.getFinishedGood().getId());
            InventoryAdjustmentLine reversalLine = new InventoryAdjustmentLine();
            reversalLine.setAdjustment(reversal);
            reversalLine.setFinishedGood(finishedGood);
            reversalLine.setQuantity(line.getQuantity());
            reversalLine.setUnitCost(line.getUnitCost());
            reversalLine.setAmount(line.getAmount());
            reversalLine.setNote(resolveReversalLineNote(line));
            reversal.getLines().add(reversalLine);
        }

        InventoryAdjustment savedReversal = adjustmentRepository.save(reversal);
        Long journalEntryId = reverseLinkedJournal(original, request, resolvedReversalDate);
        savedReversal.setJournalEntryId(journalEntryId);

        List<InventoryMovement> reversalMovements = applyReverseMovements(savedReversal, journalEntryId);
        inventoryMovementRepository.saveAll(reversalMovements);

        original.setStatus("REVERSED");
        original.setReversalEntry(savedReversal);
        adjustmentRepository.save(original);
        InventoryAdjustment storedReversal = adjustmentRepository.save(savedReversal);
        return toDto(storedReversal);
    }

    private InventoryAdjustmentDto createAdjustmentInternal(InventoryAdjustmentRequest request,
                                                            List<InventoryAdjustmentRequest.LineRequest> sortedLines,
                                                            Company company,
                                                            LocalDate resolvedAdjustmentDate,
                                                            String idempotencyKey,
                                                            String requestSignature) {
        List<Long> finishedGoodIds = sortedLines.stream()
                .map(InventoryAdjustmentRequest.LineRequest::finishedGoodId)
                .toList();
        if (finishedGoodIds.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("Finished good not found");
        }
        List<Long> uniqueFinishedGoodIds = finishedGoodIds.stream().distinct().toList();
        List<FinishedGood> lockedFinishedGoods = finishedGoodRepository.lockByCompanyAndIdInOrderById(company, uniqueFinishedGoodIds);
        Map<Long, FinishedGood> finishedGoodsById = new HashMap<>();
        for (FinishedGood finishedGood : lockedFinishedGoods) {
            finishedGoodsById.put(finishedGood.getId(), finishedGood);
        }
        if (finishedGoodsById.size() != uniqueFinishedGoodIds.size()) {
            throw new IllegalArgumentException("Finished good not found");
        }
        InventoryAdjustmentType type = request.type() == null ? InventoryAdjustmentType.DAMAGED : request.type();
        InventoryAdjustment adjustment = new InventoryAdjustment();
        adjustment.setCompany(company);
        adjustment.setReferenceNumber(resolveReference(company, type));
        adjustment.setAdjustmentDate(resolvedAdjustmentDate);
        adjustment.setType(type);
        adjustment.setReason(request.reason());
        adjustment.setCreatedBy(resolveCurrentUser());
        adjustment.setIdempotencyKey(idempotencyKey);
        adjustment.setIdempotencyHash(requestSignature);
        Map<Long, BigDecimal> inventoryCredits = new HashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (InventoryAdjustmentRequest.LineRequest lineRequest : sortedLines) {
            FinishedGood finishedGood = finishedGoodsById.get(lineRequest.finishedGoodId());
            if (finishedGood == null) {
                throw new IllegalArgumentException("Finished good not found");
            }
            InventoryAdjustmentLine line = buildLine(adjustment, finishedGood, lineRequest);
            Long valuationAccountId = line.getFinishedGood().getValuationAccountId();
            if (valuationAccountId == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Finished good " + line.getFinishedGood().getProductCode() + " is missing a valuation account");
            }
            inventoryCredits.merge(valuationAccountId, line.getAmount(), BigDecimal::add);
            totalAmount = totalAmount.add(line.getAmount());
        }
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Adjustment amount must be greater than zero");
        }
        adjustment.setTotalAmount(totalAmount);
        InventoryAdjustment savedDraft = adjustmentRepository.save(adjustment);
        List<InventoryMovement> movements = applyMovements(savedDraft, null);
        boolean adminOverride = Boolean.TRUE.equals(request.adminOverride());
        String memo = memoFor(savedDraft, request.reason());
        JournalEntryDto journalEntry = accountingFacade.postInventoryAdjustment(
                savedDraft.getType().name(),
                savedDraft.getReferenceNumber(),
                request.adjustmentAccountId(),
                Map.copyOf(inventoryCredits),
                false,
                adminOverride,
                memo,
                savedDraft.getAdjustmentDate());
        if (journalEntry == null) {
            throw new IllegalStateException("Inventory adjustment journal was not created");
        }
        savedDraft.setJournalEntryId(journalEntry.id());
        savedDraft.setStatus("POSTED");
        movements.forEach(movement -> movement.setJournalEntryId(journalEntry.id()));
        inventoryMovementRepository.saveAll(movements);
        InventoryAdjustment posted = adjustmentRepository.save(savedDraft);
        return toDto(posted);
    }

    private InventoryAdjustmentLine buildLine(InventoryAdjustment adjustment,
                                              FinishedGood finishedGood,
                                              InventoryAdjustmentRequest.LineRequest lineRequest) {
        BigDecimal quantity = requirePositive(lineRequest.quantity(), "quantity");
        BigDecimal unitCost = requirePositive(lineRequest.unitCost(), "unitCost");
        BigDecimal currentStock = finishedGood.getCurrentStock() == null ? BigDecimal.ZERO : finishedGood.getCurrentStock();
        BigDecimal reservedStock = safeQuantity(finishedGood.getReservedStock());
        BigDecimal available = currentStock.subtract(reservedStock);
        if (available.compareTo(quantity) < 0) {
            throw new IllegalArgumentException("Insufficient available stock for " + finishedGood.getProductCode());
        }
        InventoryAdjustmentLine line = new InventoryAdjustmentLine();
        line.setAdjustment(adjustment);
        line.setFinishedGood(finishedGood);
        line.setQuantity(quantity);
        line.setUnitCost(unitCost);
        line.setAmount(quantity.multiply(unitCost));
        line.setNote(lineRequest.note());
        adjustment.getLines().add(line);
        return line;
    }

    private List<InventoryMovement> applyMovements(InventoryAdjustment adjustment, Long journalEntryId) {
        List<InventoryMovement> movements = adjustment.getLines().stream().map(line -> {
            FinishedGood finishedGood = line.getFinishedGood();
            BigDecimal currentStock = finishedGood.getCurrentStock() == null ? BigDecimal.ZERO : finishedGood.getCurrentStock();
            finishedGood.setCurrentStock(currentStock.subtract(line.getQuantity()));
            adjustBatchQuantities(finishedGood, line.getQuantity());
            finishedGoodsService.invalidateWeightedAverageCost(finishedGood.getId());

            InventoryMovement movement = new InventoryMovement();
            movement.setFinishedGood(finishedGood);
            movement.setReferenceType("ADJUSTMENT");
            movement.setReferenceId(adjustment.getReferenceNumber());
            movement.setMovementType("ADJUSTMENT_OUT");
            movement.setQuantity(line.getQuantity());
            movement.setUnitCost(line.getUnitCost());
            movement.setJournalEntryId(journalEntryId);
            return movement;
        }).toList();
        return movements;
    }

    private List<InventoryMovement> applyReverseMovements(InventoryAdjustment reversal, Long journalEntryId) {
        List<InventoryMovement> movements = reversal.getLines().stream().map(line -> {
            FinishedGood finishedGood = line.getFinishedGood();
            BigDecimal currentStock = safeQuantity(finishedGood.getCurrentStock());
            finishedGood.setCurrentStock(currentStock.add(line.getQuantity()));
            FinishedGoodBatch batch = restoreBatchQuantities(reversal, line);
            finishedGoodsService.invalidateWeightedAverageCost(finishedGood.getId());

            InventoryMovement movement = new InventoryMovement();
            movement.setFinishedGood(finishedGood);
            movement.setFinishedGoodBatch(batch);
            movement.setReferenceType("ADJUSTMENT_REVERSAL");
            movement.setReferenceId(reversal.getReferenceNumber());
            movement.setMovementType("ADJUSTMENT_IN");
            movement.setQuantity(line.getQuantity());
            movement.setUnitCost(line.getUnitCost());
            movement.setJournalEntryId(journalEntryId);
            return movement;
        }).toList();
        return movements;
    }

    private FinishedGoodBatch restoreBatchQuantities(InventoryAdjustment reversal, InventoryAdjustmentLine line) {
        FinishedGood finishedGood = line.getFinishedGood();
        String batchCode = "REV-" + reversal.getReferenceNumber();
        FinishedGoodBatch batch = finishedGoodBatchRepository
                .lockByFinishedGoodAndBatchCode(finishedGood, batchCode)
                .orElseGet(() -> {
                    FinishedGoodBatch created = new FinishedGoodBatch();
                    created.setFinishedGood(finishedGood);
                    created.setBatchCode(batchCode);
                    created.setQuantityTotal(BigDecimal.ZERO);
                    created.setQuantityAvailable(BigDecimal.ZERO);
                    created.setUnitCost(line.getUnitCost());
                    return finishedGoodBatchRepository.save(created);
                });
        batch.setUnitCost(line.getUnitCost());
        batch.setQuantityTotal(safeQuantity(batch.getQuantityTotal()).add(line.getQuantity()));
        batch.setQuantityAvailable(safeQuantity(batch.getQuantityAvailable()).add(line.getQuantity()));
        return finishedGoodBatchRepository.save(batch);
    }

    private Long reverseLinkedJournal(InventoryAdjustment original,
                                      InventoryAdjustmentReversalRequest request,
                                      LocalDate resolvedReversalDate) {
        Long originalJournalEntryId = original.getJournalEntryId();
        if (originalJournalEntryId == null) {
            return null;
        }
        JournalEntryReversalRequest reversalRequest = new JournalEntryReversalRequest(
                resolvedReversalDate,
                false,
                resolveReversalReason(original, request),
                "Inventory adjustment reversal for " + original.getReferenceNumber(),
                request.adminOverride()
        );
        JournalEntryDto journal = accountingService.reverseJournalEntry(originalJournalEntryId, reversalRequest);
        if (journal == null) {
            throw new IllegalStateException("Inventory adjustment reversal journal was not created");
        }
        return journal.id();
    }

    private String resolveReversalReason(InventoryAdjustment original, InventoryAdjustmentReversalRequest request) {
        if (request != null && StringUtils.hasText(request.reason())) {
            return request.reason().trim();
        }
        return "Reversal of " + original.getReferenceNumber();
    }

    private String resolveReversalLineNote(InventoryAdjustmentLine originalLine) {
        if (StringUtils.hasText(originalLine.getNote())) {
            return "Reversal: " + originalLine.getNote().trim();
        }
        return "Reversal";
    }

    private void adjustBatchQuantities(FinishedGood finishedGood, BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal remaining = quantity;
        for (FinishedGoodBatch batch : selectBatchesByCostingMethod(finishedGood)) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal available = safeQuantity(batch.getQuantityAvailable());
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal delta = available.min(remaining);
            BigDecimal total = safeQuantity(batch.getQuantityTotal());
            batch.setQuantityAvailable(available.subtract(delta));
            batch.setQuantityTotal(total.subtract(delta));
            remaining = remaining.subtract(delta);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Insufficient batch availability for " + finishedGood.getProductCode());
        }
    }

    private List<FinishedGoodBatch> selectBatchesByCostingMethod(FinishedGood finishedGood) {
        return switch (CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(finishedGood.getCostingMethod())) {
            case WAC -> finishedGoodBatchRepository.findAllocatableBatches(finishedGood);
            case LIFO -> finishedGoodBatchRepository.findAllocatableBatchesLIFO(finishedGood);
            case FIFO -> finishedGoodBatchRepository.findAllocatableBatchesFIFO(finishedGood);
        };
    }

    private InventoryAdjustmentDto toDto(InventoryAdjustment adjustment) {
        List<InventoryAdjustmentLineDto> lines = adjustment.getLines().stream()
                .map(line -> new InventoryAdjustmentLineDto(
                        line.getFinishedGood().getId(),
                        line.getFinishedGood().getName(),
                        line.getQuantity(),
                        line.getUnitCost(),
                        line.getAmount(),
                        line.getNote()))
                .toList();
        return new InventoryAdjustmentDto(
                adjustment.getId(),
                adjustment.getPublicId(),
                adjustment.getReferenceNumber(),
                adjustment.getAdjustmentDate(),
                adjustment.getType().name(),
                adjustment.getStatus(),
                adjustment.getReason(),
                adjustment.getTotalAmount(),
                adjustment.getJournalEntryId(),
                lines
        );
    }

    private BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero");
        }
        return value;
    }

    private BigDecimal safeQuantity(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private String normalizeIdempotencyKey(String raw) {
        return StringUtils.hasText(raw) ? raw.trim() : null;
    }

    private void assertIdempotencyMatch(InventoryAdjustment adjustment,
                                        String expectedSignature,
                                        String idempotencyKey) {
        String storedSignature = adjustment.getIdempotencyHash();
        if (StringUtils.hasText(storedSignature)) {
            if (!storedSignature.equals(expectedSignature)) {
                throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                        "Idempotency key already used with different payload")
                        .withDetail("idempotencyKey", idempotencyKey);
            }
            return;
        }
        adjustment.setIdempotencyHash(expectedSignature);
        adjustmentRepository.save(adjustment);
    }

    private void assertReversalIdempotencyMatch(InventoryAdjustment adjustment,
                                                Long expectedAdjustmentId,
                                                String expectedSignature,
                                                String idempotencyKey) {
        InventoryAdjustment reversalOf = adjustment.getReversalOf();
        Long linkedAdjustmentId = reversalOf != null ? reversalOf.getId() : null;
        if (!expectedAdjustmentId.equals(linkedAdjustmentId)) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used for a different inventory reversal")
                    .withDetail("idempotencyKey", idempotencyKey)
                    .withDetail("adjustmentId", expectedAdjustmentId)
                    .withDetail("existingReversalOfAdjustmentId", linkedAdjustmentId);
        }
        assertIdempotencyMatch(adjustment, expectedSignature, idempotencyKey);
    }

    private String buildReversalSignature(Long adjustmentId,
                                          InventoryAdjustmentReversalRequest request,
                                          LocalDate resolvedDate) {
        StringBuilder signature = new StringBuilder();
        signature.append(adjustmentId != null ? adjustmentId : "")
                .append('|').append(resolvedDate != null ? resolvedDate : "")
                .append('|').append(normalizeToken(request.reason()))
                .append('|').append(Boolean.TRUE.equals(request.adminOverride()));
        return DigestUtils.sha256Hex(signature.toString());
    }

    private String buildAdjustmentSignature(InventoryAdjustmentRequest request,
                                            List<InventoryAdjustmentRequest.LineRequest> sortedLines,
                                            LocalDate resolvedDate) {
        StringBuilder signature = new StringBuilder();
        signature.append(resolvedDate != null ? resolvedDate : "")
                .append('|').append(request.type() != null ? request.type().name() : "")
                .append('|').append(request.adjustmentAccountId() != null ? request.adjustmentAccountId() : "")
                .append('|').append(normalizeToken(request.reason()))
                .append('|').append(Boolean.TRUE.equals(request.adminOverride()));
        for (InventoryAdjustmentRequest.LineRequest line : sortedLines) {
            signature.append('|').append(line.finishedGoodId() != null ? line.finishedGoodId() : "")
                    .append(':').append(normalizeAmount(line.quantity()))
                    .append(':').append(normalizeAmount(line.unitCost()))
                    .append(':').append(normalizeToken(line.note()));
        }
        return DigestUtils.sha256Hex(signature.toString());
    }

    private String normalizeToken(String value) {
        return value != null ? value.trim() : "";
    }

    private String normalizeAmount(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private boolean isDataIntegrityViolation(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof DataIntegrityViolationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private String memoFor(InventoryAdjustment adjustment, String reason) {
        String suffix = StringUtils.hasText(reason) ? reason.trim() : adjustment.getType().name();
        return "Inventory adjustment - " + suffix;
    }

    private String resolveReference(Company company, InventoryAdjustmentType type) {
        return referenceNumberService.inventoryAdjustmentReference(company, type != null ? type.name() : "GEN");
    }

    private LocalDate resolveCurrentDate(Company company) {
        return companyClock.today(company);
    }

    private String resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "system";
        }
        return authentication.getName();
    }
}
