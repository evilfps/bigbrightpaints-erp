package com.bigbrightpaints.erp.modules.inventory.service;

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
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentDto;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentLineDto;
import com.bigbrightpaints.erp.modules.inventory.dto.InventoryAdjustmentRequest;
import jakarta.transaction.Transactional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryAdjustmentService {

    private final CompanyContextService companyContextService;
    private final FinishedGoodRepository finishedGoodRepository;
    private final InventoryAdjustmentRepository adjustmentRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final AccountingFacade accountingFacade;

    public InventoryAdjustmentService(CompanyContextService companyContextService,
                                      FinishedGoodRepository finishedGoodRepository,
                                      InventoryAdjustmentRepository adjustmentRepository,
                                      InventoryMovementRepository inventoryMovementRepository,
                                      AccountingFacade accountingFacade) {
        this.companyContextService = companyContextService;
        this.finishedGoodRepository = finishedGoodRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.accountingFacade = accountingFacade;
    }

    public List<InventoryAdjustmentDto> listAdjustments() {
        Company company = companyContextService.requireCurrentCompany();
        return adjustmentRepository.findByCompanyOrderByAdjustmentDateDesc(company).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public InventoryAdjustmentDto createAdjustment(InventoryAdjustmentRequest request) {
        if (request == null || request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("Adjustment lines are required");
        }
        Company company = companyContextService.requireCurrentCompany();
        InventoryAdjustmentType type = request.type() == null ? InventoryAdjustmentType.DAMAGED : request.type();
        InventoryAdjustment adjustment = new InventoryAdjustment();
        adjustment.setCompany(company);
        adjustment.setReferenceNumber(resolveReference(type));
        adjustment.setAdjustmentDate(request.adjustmentDate() != null ? request.adjustmentDate() : resolveCurrentDate(company));
        adjustment.setType(type);
        adjustment.setReason(request.reason());
        adjustment.setCreatedBy(resolveCurrentUser());
        Map<Long, BigDecimal> inventoryCredits = new HashMap<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (InventoryAdjustmentRequest.LineRequest lineRequest : request.lines()) {
            InventoryAdjustmentLine line = buildLine(company, adjustment, lineRequest);
            Long valuationAccountId = line.getFinishedGood().getValuationAccountId();
            if (valuationAccountId == null) {
                throw new IllegalStateException("Finished good " + line.getFinishedGood().getProductCode() + " missing valuation account");
            }
            inventoryCredits.merge(valuationAccountId, line.getAmount(), BigDecimal::add);
            totalAmount = totalAmount.add(line.getAmount());
        }
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Adjustment amount must be greater than zero");
        }
        adjustment.setTotalAmount(totalAmount);
        InventoryAdjustment savedDraft = adjustmentRepository.save(adjustment);
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
        InventoryAdjustment posted = adjustmentRepository.save(savedDraft);
        applyMovements(posted);
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
        if (currentStock.compareTo(quantity) < 0) {
            throw new IllegalArgumentException("Insufficient stock for " + finishedGood.getProductCode());
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

    private void applyMovements(InventoryAdjustment adjustment) {
        for (InventoryAdjustmentLine line : adjustment.getLines()) {
            FinishedGood finishedGood = line.getFinishedGood();
            BigDecimal currentStock = finishedGood.getCurrentStock() == null ? BigDecimal.ZERO : finishedGood.getCurrentStock();
            finishedGood.setCurrentStock(currentStock.subtract(line.getQuantity()));
            finishedGoodRepository.save(finishedGood);
            InventoryMovement movement = new InventoryMovement();
            movement.setFinishedGood(finishedGood);
            movement.setReferenceType("ADJUSTMENT");
            movement.setReferenceId(adjustment.getReferenceNumber());
            movement.setMovementType("ADJUSTMENT_OUT");
            movement.setQuantity(line.getQuantity());
            movement.setUnitCost(line.getUnitCost());
            movement.setJournalEntryId(adjustment.getJournalEntryId());
            inventoryMovementRepository.save(movement);
        }
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

    private String memoFor(InventoryAdjustment adjustment, String reason) {
        String suffix = StringUtils.hasText(reason) ? reason.trim() : adjustment.getType().name();
        return "Inventory adjustment - " + suffix;
    }

    private String resolveReference(InventoryAdjustmentType type) {
        return "ADJ-" + type.name().charAt(0) + UUID.randomUUID().toString().substring(0, 7).toUpperCase();
    }

    private LocalDate resolveCurrentDate(Company company) {
        String timezone = company.getTimezone() == null ? "UTC" : company.getTimezone();
        return LocalDate.now(ZoneId.of(timezone));
    }

    private String resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "system";
        }
        return authentication.getName();
    }
}
