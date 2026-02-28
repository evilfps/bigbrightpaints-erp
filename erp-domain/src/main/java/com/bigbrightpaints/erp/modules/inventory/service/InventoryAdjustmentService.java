package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencySignatureBuilder;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
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
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
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
    private final ReferenceNumberService referenceNumberService;
    private final CompanyClock companyClock;
    private final FinishedGoodsService finishedGoodsService;
    private final IdempotencyReservationService idempotencyReservationService = new IdempotencyReservationService();
    private final TransactionTemplate transactionTemplate;

    public InventoryAdjustmentService(CompanyContextService companyContextService,
                                      FinishedGoodRepository finishedGoodRepository,
                                      InventoryAdjustmentRepository adjustmentRepository,
                                      InventoryMovementRepository inventoryMovementRepository,
                                      FinishedGoodBatchRepository finishedGoodBatchRepository,
                                      AccountingFacade accountingFacade,
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
            if (!idempotencyReservationService.isDataIntegrityViolation(ex)) {
                throw ex;
            }
            InventoryAdjustment concurrent = adjustmentRepository.findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
                    .orElseThrow(() -> ex);
            assertIdempotencyMatch(concurrent, requestSignature, idempotencyKey);
            return toDto(concurrent);
        }
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
        return idempotencyReservationService.normalizeKey(raw);
    }

    private void assertIdempotencyMatch(InventoryAdjustment adjustment,
                                        String expectedSignature,
                                        String idempotencyKey) {
        idempotencyReservationService.assertAndRepairSignature(
                adjustment,
                idempotencyKey,
                expectedSignature,
                InventoryAdjustment::getIdempotencyHash,
                InventoryAdjustment::setIdempotencyHash,
                adjustmentRepository::save
        );
    }

    private String buildAdjustmentSignature(InventoryAdjustmentRequest request,
                                            List<InventoryAdjustmentRequest.LineRequest> sortedLines,
                                            LocalDate resolvedDate) {
        IdempotencySignatureBuilder signature = IdempotencySignatureBuilder.create()
                .add(resolvedDate != null ? resolvedDate : "")
                .add(request.type() != null ? request.type().name() : "")
                .add(request.adjustmentAccountId() != null ? request.adjustmentAccountId() : "")
                .addToken(request.reason())
                .add(Boolean.TRUE.equals(request.adminOverride()));
        for (InventoryAdjustmentRequest.LineRequest line : sortedLines) {
            signature.add(
                    (line.finishedGoodId() != null ? line.finishedGoodId() : "")
                            + ":" + IdempotencyUtils.normalizeAmount(line.quantity())
                            + ":" + IdempotencyUtils.normalizeAmount(line.unitCost())
                            + ":" + IdempotencyUtils.normalizeToken(line.note())
            );
        }
        return signature.buildHash();
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
