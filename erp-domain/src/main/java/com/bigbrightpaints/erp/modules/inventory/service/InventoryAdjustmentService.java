package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustment;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentLine;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryAdjustmentType;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentDto;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentLineDto;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentRequest;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.annotation.Transactional;
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

    public InventoryAdjustmentService(CompanyContextService companyContextService,
                                      FinishedGoodRepository finishedGoodRepository,
                                      InventoryAdjustmentRepository adjustmentRepository,
                                      InventoryMovementRepository inventoryMovementRepository,
                                      FinishedGoodBatchRepository finishedGoodBatchRepository,
                                      AccountingFacade accountingFacade,
                                      ReferenceNumberService referenceNumberService,
                                      CompanyClock companyClock) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.accountingFacade = accountingFacade;
        this.referenceNumberService = referenceNumberService;
        this.companyClock = companyClock;
    }

    public List<InventoryAdjustmentDto> listAdjustments() {
        Company company = companyContextService.requireCurrentCompany();
        return adjustmentRepository.findByCompanyOrderByAdjustmentDateDesc(company).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    @Retryable(value = OptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public InventoryAdjustmentDto createAdjustment(InventoryAdjustmentRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("Adjustment lines are required");
        }
        List<InventoryAdjustmentRequest.LineRequest> sortedLines = request.lines().stream()
                .sorted(Comparator.comparing(InventoryAdjustmentRequest.LineRequest::finishedGoodId))
                .toList();
        Company company = companyContextService.requireCurrentCompany();
        InventoryAdjustmentType type = request.type() == null ? InventoryAdjustmentType.DAMAGED : request.type();
        InventoryAdjustment adjustment = new InventoryAdjustment();
        adjustment.setCompany(company);
        adjustment.setReferenceNumber(resolveReference(company, type));
        adjustment.setAdjustmentDate(request.adjustmentDate() != null ? request.adjustmentDate() : resolveCurrentDate(company));
        adjustment.setType(type);
        adjustment.setReason(request.reason());
        adjustment.setCreatedBy(resolveCurrentUser());
        Map<Long, BigDecimal> inventoryCredits = new HashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (InventoryAdjustmentRequest.LineRequest lineRequest : sortedLines) {
            InventoryAdjustmentLine line = buildLine(company, adjustment, lineRequest);
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
                memo);
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

    private InventoryAdjustmentLine buildLine(Company company,
                                              InventoryAdjustment adjustment,
                                              InventoryAdjustmentRequest.LineRequest lineRequest) {
        FinishedGood finishedGood = finishedGoodRepository.lockByCompanyAndId(company, lineRequest.finishedGoodId())
                .orElseThrow(() -> new IllegalArgumentException("Finished good not found"));
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
            finishedGoodRepository.save(finishedGood);
            adjustBatchQuantities(finishedGood, line.getQuantity());

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
            finishedGoodBatchRepository.save(batch);
            remaining = remaining.subtract(delta);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new IllegalStateException("Insufficient batch availability for " + finishedGood.getProductCode());
        }
    }

    private List<FinishedGoodBatch> selectBatchesByCostingMethod(FinishedGood finishedGood) {
        String method = finishedGood.getCostingMethod() == null ? "FIFO" : finishedGood.getCostingMethod().trim().toUpperCase();
        return switch (method) {
            case "LIFO" -> finishedGoodBatchRepository.findAllocatableBatchesLIFO(finishedGood);
            case "WAC", "WEIGHTED_AVERAGE", "WEIGHTED-AVERAGE" -> finishedGoodBatchRepository.findAllocatableBatchesFIFO(finishedGood);
            default -> finishedGoodBatchRepository.findAllocatableBatchesFIFO(finishedGood);
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
