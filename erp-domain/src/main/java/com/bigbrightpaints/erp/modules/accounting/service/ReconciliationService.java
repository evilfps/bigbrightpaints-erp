package com.bigbrightpaints.erp.modules.accounting.service;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.BankReconciliationSummaryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.InventoryCountRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.InventoryCountResponse;
import com.bigbrightpaints.erp.modules.accounting.dto.InventoryCountTarget;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ReconciliationService {

    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal VARIANCE_PERCENT_THRESHOLD = new BigDecimal("0.20");
    private static final BigDecimal VARIANCE_VALUE_THRESHOLD = new BigDecimal("1000.00");

    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final JournalLineRepository journalLineRepository;
    private final AccountingPeriodService accountingPeriodService;
    private final AccountingService accountingService;
    private final RawMaterialRepository rawMaterialRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;
    private final InventoryMovementRepository inventoryMovementRepository;

    public ReconciliationService(CompanyContextService companyContextService,
                                 AccountRepository accountRepository,
                                 JournalLineRepository journalLineRepository,
                                 AccountingPeriodService accountingPeriodService,
                                 AccountingService accountingService,
                                 RawMaterialRepository rawMaterialRepository,
                                 FinishedGoodRepository finishedGoodRepository,
                                 RawMaterialMovementRepository rawMaterialMovementRepository,
                                 InventoryMovementRepository inventoryMovementRepository) {
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.journalLineRepository = journalLineRepository;
        this.accountingPeriodService = accountingPeriodService;
        this.accountingService = accountingService;
        this.rawMaterialRepository = rawMaterialRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
    }

    @Transactional
    public BankReconciliationSummaryDto reconcileBank(BankReconciliationRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        Account account = accountRepository.findByCompanyAndId(company, request.bankAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Bank account not found"));
        LocalDate statementDate = request.statementDate();
        LocalDate startDate = request.startDate() != null ? request.startDate() : statementDate.minusMonths(1);
        LocalDate endDate = request.endDate() != null ? request.endDate() : statementDate;
        List<JournalLine> lines = journalLineRepository.findLinesForAccountBetween(company, account.getId(), startDate, endDate);
        Set<String> clearedRefs = normalizeReferences(request.clearedReferences());
        Set<Long> clearedEntryIds = extractEntryIds(request.clearedReferences());
        List<BankReconciliationSummaryDto.BankReconciliationItemDto> deposits = new ArrayList<>();
        List<BankReconciliationSummaryDto.BankReconciliationItemDto> checks = new ArrayList<>();
        BigDecimal outstandingDeposits = BigDecimal.ZERO;
        BigDecimal outstandingChecks = BigDecimal.ZERO;
        for (JournalLine line : lines) {
            JournalEntry entry = line.getJournalEntry();
            BigDecimal debit = safe(line.getDebit());
            BigDecimal credit = safe(line.getCredit());
            BigDecimal net = debit.subtract(credit);
            if (net.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            if (isCleared(entry, clearedRefs, clearedEntryIds)) {
                continue;
            }
            BankReconciliationSummaryDto.BankReconciliationItemDto item =
                    new BankReconciliationSummaryDto.BankReconciliationItemDto(
                            entry.getId(),
                            entry.getReferenceNumber(),
                            entry.getEntryDate(),
                            entry.getMemo(),
                            debit,
                            credit,
                            net.abs());
            if (net.compareTo(BigDecimal.ZERO) > 0) {
                deposits.add(item);
                outstandingDeposits = outstandingDeposits.add(net);
            } else {
                checks.add(item);
                outstandingChecks = outstandingChecks.add(net.abs());
            }
        }
        BigDecimal ledgerBalance = safe(account.getBalance());
        BigDecimal statementBalance = request.statementEndingBalance();
        BigDecimal difference = ledgerBalance.subtract(statementBalance.add(outstandingDeposits).subtract(outstandingChecks));
        boolean balanced = difference.abs().compareTo(BALANCE_TOLERANCE) <= 0;
        if (Boolean.TRUE.equals(request.markAsComplete()) && balanced) {
            accountingPeriodService.confirmBankReconciliation(request.accountingPeriodId(), statementDate, request.note());
        }
        return new BankReconciliationSummaryDto(
                account.getId(),
                account.getCode(),
                account.getName(),
                statementDate,
                ledgerBalance,
                statementBalance,
                outstandingDeposits,
                outstandingChecks,
                difference,
                balanced,
                List.copyOf(deposits),
                List.copyOf(checks));
    }

    @Transactional
    public InventoryCountResponse recordInventoryCount(InventoryCountRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        InventoryContext context = resolveInventoryContext(company, request);
        BigDecimal physical = requireNonNegative(request.physicalQuantity(), "physicalQuantity");
        BigDecimal unitCost = requirePositive(request.unitCost(), "unitCost");
        BigDecimal varianceQuantity = physical.subtract(context.systemQuantity());
        BigDecimal varianceValue = varianceQuantity.multiply(unitCost);
        boolean adjustmentPosted = false;
        Long journalEntryId = null;
        String referenceNumber = buildInventoryReference(context);
        if (varianceQuantity.compareTo(BigDecimal.ZERO) != 0) {
            LocalDate countDate = resolveCountDate(company, request);
            JournalEntryDto entry = accountingService.createJournalEntry(
                    buildAdjustmentRequest(context, varianceQuantity, varianceValue, request.adjustmentAccountId(), countDate, referenceNumber));
            journalEntryId = entry.id();
            adjustmentPosted = true;
            recordMovement(context, varianceQuantity, unitCost, referenceNumber, journalEntryId);
        }
        applyPhysicalCount(context, physical);
        boolean alertRaised = isLargeVariance(varianceQuantity, context.systemQuantity(), varianceValue);
        String alertReason = alertRaised ? buildAlertReason(varianceQuantity, context.systemQuantity(), varianceValue) : null;
        if (Boolean.TRUE.equals(request.markAsComplete())) {
            accountingPeriodService.confirmInventoryCount(request.accountingPeriodId(), resolveCountDate(company, request), request.note());
        }
        return new InventoryCountResponse(
                context.itemId(),
                context.itemName(),
                context.target(),
                context.systemQuantity(),
                physical,
                varianceQuantity,
                varianceValue,
                adjustmentPosted,
                journalEntryId,
                alertRaised,
                alertReason);
    }

    private InventoryContext resolveInventoryContext(Company company, InventoryCountRequest request) {
        if (request.target() == InventoryCountTarget.RAW_MATERIAL) {
            RawMaterial material = rawMaterialRepository.lockByCompanyAndId(company, request.itemId())
                    .orElseThrow(() -> new IllegalArgumentException("Raw material not found"));
            if (material.getInventoryAccountId() == null) {
                throw new IllegalStateException("Raw material is missing inventory account mapping");
            }
            return InventoryContext.forRawMaterial(material, material.getCurrentStock(), material.getInventoryAccountId());
        }
        FinishedGood good = finishedGoodRepository.lockByCompanyAndId(company, request.itemId())
                .orElseThrow(() -> new IllegalArgumentException("Finished good not found"));
        if (good.getValuationAccountId() == null) {
            throw new IllegalStateException("Finished good is missing valuation account mapping");
        }
        return InventoryContext.forFinishedGood(good, good.getCurrentStock(), good.getValuationAccountId());
    }

    private void applyPhysicalCount(InventoryContext context, BigDecimal physicalQuantity) {
        if (context.rawMaterial() != null) {
            context.rawMaterial().setCurrentStock(physicalQuantity);
            rawMaterialRepository.save(context.rawMaterial());
        } else if (context.finishedGood() != null) {
            context.finishedGood().setCurrentStock(physicalQuantity);
            finishedGoodRepository.save(context.finishedGood());
        }
    }

    private JournalEntryRequest buildAdjustmentRequest(InventoryContext context,
                                                       BigDecimal varianceQuantity,
                                                       BigDecimal varianceValue,
                                                       Long adjustmentAccountId,
                                                       LocalDate countDate,
                                                       String referenceNumber) {
        BigDecimal amount = varianceValue.abs();
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Variance amount must be greater than zero");
        }
        String memo = "Inventory count adjustment for " + context.itemName();
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        if (varianceQuantity.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(context.inventoryAccountId(), memo, amount, BigDecimal.ZERO));
            lines.add(new JournalEntryRequest.JournalLineRequest(adjustmentAccountId, memo, BigDecimal.ZERO, amount));
        } else {
            lines.add(new JournalEntryRequest.JournalLineRequest(adjustmentAccountId, memo, amount, BigDecimal.ZERO));
            lines.add(new JournalEntryRequest.JournalLineRequest(context.inventoryAccountId(), memo, BigDecimal.ZERO, amount));
        }
        return new JournalEntryRequest(
                referenceNumber,
                countDate,
                memo,
                null,
                null,
                Boolean.FALSE,
                lines);
    }

    private void recordMovement(InventoryContext context,
                                BigDecimal varianceQuantity,
                                BigDecimal unitCost,
                                String referenceNumber,
                                Long journalEntryId) {
        if (context.rawMaterial() != null) {
            RawMaterialMovement movement = new RawMaterialMovement();
            movement.setRawMaterial(context.rawMaterial());
            movement.setReferenceType("INVENTORY_COUNT");
            movement.setReferenceId(referenceNumber);
            movement.setMovementType(varianceQuantity.compareTo(BigDecimal.ZERO) > 0 ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT");
            movement.setQuantity(varianceQuantity.abs());
            movement.setUnitCost(unitCost);
            movement.setJournalEntryId(journalEntryId);
            rawMaterialMovementRepository.save(movement);
        } else if (context.finishedGood() != null) {
            InventoryMovement movement = new InventoryMovement();
            movement.setFinishedGood(context.finishedGood());
            movement.setReferenceType("INVENTORY_COUNT");
            movement.setReferenceId(referenceNumber);
            movement.setMovementType(varianceQuantity.compareTo(BigDecimal.ZERO) > 0 ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT");
            movement.setQuantity(varianceQuantity.abs());
            movement.setUnitCost(unitCost);
            movement.setJournalEntryId(journalEntryId);
            inventoryMovementRepository.save(movement);
        }
    }

    private boolean isCleared(JournalEntry entry, Set<String> clearedRefs, Set<Long> clearedIds) {
        if (entry == null) {
            return false;
        }
        String reference = entry.getReferenceNumber();
        if (StringUtils.hasText(reference) && clearedRefs.contains(reference.trim().toUpperCase(Locale.ROOT))) {
            return true;
        }
        return entry.getId() != null && clearedIds.contains(entry.getId());
    }

    private Set<String> normalizeReferences(List<String> references) {
        if (references == null || references.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String reference : references) {
            if (!StringUtils.hasText(reference)) {
                continue;
            }
            normalized.add(reference.trim().toUpperCase(Locale.ROOT));
        }
        return normalized;
    }

    private Set<Long> extractEntryIds(List<String> references) {
        if (references == null || references.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (String reference : references) {
            if (!StringUtils.hasText(reference)) {
                continue;
            }
            try {
                ids.add(Long.parseLong(reference.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    private boolean isLargeVariance(BigDecimal varianceQuantity, BigDecimal systemQuantity, BigDecimal varianceValue) {
        BigDecimal base = systemQuantity.abs().max(BigDecimal.ONE);
        BigDecimal percent = varianceQuantity.abs().divide(base, 4, RoundingMode.HALF_UP);
        return percent.compareTo(VARIANCE_PERCENT_THRESHOLD) > 0
                || varianceValue.abs().compareTo(VARIANCE_VALUE_THRESHOLD) > 0;
    }

    private String buildAlertReason(BigDecimal varianceQuantity, BigDecimal systemQuantity, BigDecimal varianceValue) {
        BigDecimal base = systemQuantity.abs().max(BigDecimal.ONE);
        BigDecimal percent = varianceQuantity.abs().divide(base, 4, RoundingMode.HALF_UP);
        if (percent.compareTo(VARIANCE_PERCENT_THRESHOLD) > 0) {
            BigDecimal percentValue = percent.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal threshold = VARIANCE_PERCENT_THRESHOLD.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            return "Quantity variance of " + percentValue + "% exceeds " + threshold + "% threshold";
        }
        if (varianceValue.abs().compareTo(VARIANCE_VALUE_THRESHOLD) > 0) {
            return "Value variance of " + varianceValue.abs() + " exceeds " + VARIANCE_VALUE_THRESHOLD;
        }
        return null;
    }

    private String buildInventoryReference(InventoryContext context) {
        return "INV-CNT-" + context.target().name() + "-" + context.itemId() + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero");
        }
        return value;
    }

    private BigDecimal requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
        return value;
    }

    private LocalDate resolveCountDate(Company company, InventoryCountRequest request) {
        if (request.countDate() != null) {
            return request.countDate();
        }
        String timezone = company.getTimezone() == null ? "UTC" : company.getTimezone();
        return LocalDate.now(ZoneId.of(timezone));
    }

    private record InventoryContext(InventoryCountTarget target,
                                    Long itemId,
                                    String itemName,
                                    BigDecimal systemQuantity,
                                    Long inventoryAccountId,
                                    RawMaterial rawMaterial,
                                    FinishedGood finishedGood) {

        static InventoryContext forRawMaterial(RawMaterial material, BigDecimal systemQuantity, Long accountId) {
            return new InventoryContext(InventoryCountTarget.RAW_MATERIAL,
                    material.getId(),
                    material.getName(),
                    systemQuantity == null ? BigDecimal.ZERO : systemQuantity,
                    accountId,
                    material,
                    null);
        }

        static InventoryContext forFinishedGood(FinishedGood good, BigDecimal systemQuantity, Long accountId) {
            return new InventoryContext(InventoryCountTarget.FINISHED_GOOD,
                    good.getId(),
                    good.getName(),
                    systemQuantity == null ? BigDecimal.ZERO : systemQuantity,
                    accountId,
                    null,
                    good);
        }
    }
}
