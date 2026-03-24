package com.bigbrightpaints.erp.modules.reports.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshot;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodSnapshotRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodTrialBalanceLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.reports.dto.*;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.dto.CostBreakdownDto;
import com.bigbrightpaints.erp.modules.factory.dto.CostComponentTraceDto;
import com.bigbrightpaints.erp.modules.factory.dto.MonthlyProductionCostDto;
import com.bigbrightpaints.erp.modules.factory.dto.PackedBatchTraceDto;
import com.bigbrightpaints.erp.modules.factory.dto.RawMaterialTraceDto;
import com.bigbrightpaints.erp.modules.factory.dto.WastageReportDto;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class ReportService {

    private final CompanyContextService companyContextService;
    private final AccountRepository accountRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final AccountingPeriodSnapshotRepository snapshotRepository;
    private final AccountingPeriodTrialBalanceLineRepository snapshotLineRepository;
    private final DealerRepository dealerRepository;
    private final DealerLedgerService dealerLedgerService;
    private final DealerLedgerRepository dealerLedgerRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;
    private final ProductionLogRepository productionLogRepository;
    private final PackingRecordRepository packingRecordRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyClock companyClock;
    private final InventoryValuationService inventoryValuationService;
    private final TrialBalanceReportQueryService trialBalanceReportQueryService;
    private final ProfitLossReportQueryService profitLossReportQueryService;
    private final BalanceSheetReportQueryService balanceSheetReportQueryService;
    private final AgedDebtorsReportQueryService agedDebtorsReportQueryService;
    private final InvoiceRepository invoiceRepository;
    private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
    private final GstService gstService;
    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");

    public ReportService(CompanyContextService companyContextService,
                         AccountRepository accountRepository,
                         AccountingPeriodRepository accountingPeriodRepository,
                         AccountingPeriodSnapshotRepository snapshotRepository,
                         AccountingPeriodTrialBalanceLineRepository snapshotLineRepository,
                         DealerRepository dealerRepository,
                         DealerLedgerService dealerLedgerService,
                         DealerLedgerRepository dealerLedgerRepository,
                         JournalEntryRepository journalEntryRepository,
                         JournalLineRepository journalLineRepository,
                         ProductionLogRepository productionLogRepository,
                         PackingRecordRepository packingRecordRepository,
                         InventoryMovementRepository inventoryMovementRepository,
                         RawMaterialMovementRepository rawMaterialMovementRepository,
                         CompanyEntityLookup companyEntityLookup,
                         CompanyClock companyClock,
                         InventoryValuationService inventoryValuationService,
                         TrialBalanceReportQueryService trialBalanceReportQueryService,
                         ProfitLossReportQueryService profitLossReportQueryService,
                         BalanceSheetReportQueryService balanceSheetReportQueryService,
                         AgedDebtorsReportQueryService agedDebtorsReportQueryService,
                         InvoiceRepository invoiceRepository,
                         RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
                         GstService gstService) {
        this.companyContextService = companyContextService;
        this.accountRepository = accountRepository;
        this.accountingPeriodRepository = accountingPeriodRepository;
        this.snapshotRepository = snapshotRepository;
        this.snapshotLineRepository = snapshotLineRepository;
        this.dealerRepository = dealerRepository;
        this.dealerLedgerService = dealerLedgerService;
        this.dealerLedgerRepository = dealerLedgerRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.journalLineRepository = journalLineRepository;
        this.productionLogRepository = productionLogRepository;
        this.packingRecordRepository = packingRecordRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
        this.companyEntityLookup = companyEntityLookup;
        this.companyClock = companyClock;
        this.inventoryValuationService = inventoryValuationService;
        this.trialBalanceReportQueryService = trialBalanceReportQueryService;
        this.profitLossReportQueryService = profitLossReportQueryService;
        this.balanceSheetReportQueryService = balanceSheetReportQueryService;
        this.agedDebtorsReportQueryService = agedDebtorsReportQueryService;
        this.invoiceRepository = invoiceRepository;
        this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
        this.gstService = gstService;
    }

    @Transactional(readOnly = true)
    public BalanceSheetDto balanceSheet(LocalDate asOfDate) {
        return balanceSheet(ReportQueryRequestBuilder.fromAsOfDate(asOfDate));
    }

    @Transactional(readOnly = true)
    public BalanceSheetDto balanceSheet(FinancialReportQueryRequest request) {
        return balanceSheetReportQueryService.generate(requireFinancialReportRequest(request));
    }

    @Transactional(readOnly = true)
    public ProfitLossDto profitLoss(LocalDate asOfDate) {
        return profitLoss(ReportQueryRequestBuilder.fromAsOfDate(asOfDate));
    }

    @Transactional(readOnly = true)
    public ProfitLossDto profitLoss(FinancialReportQueryRequest request) {
        return profitLossReportQueryService.generate(requireFinancialReportRequest(request));
    }

    @Transactional(readOnly = true)
    public CashFlowDto cashFlow() {
        ReportContext context = resolveReportContext(null);
        Company company = context.company();
        List<JournalEntry> entries = journalEntryRepository.findByCompanyOrderByEntryDateDesc(company);
        BigDecimal operating = BigDecimal.ZERO;
        BigDecimal investing = BigDecimal.ZERO;
        BigDecimal financing = BigDecimal.ZERO;
        for (JournalEntry entry : entries) {
            if (!"POSTED".equalsIgnoreCase(entry.getStatus())) {
                continue;
            }
            List<JournalLine> lines = entry.getLines();
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            for (JournalLine line : entry.getLines()) {
                if (!isCashAccount(line.getAccount())) {
                    continue;
                }
                BigDecimal delta = safe(line.getDebit()).subtract(safe(line.getCredit()));
                if (delta.compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }
                Map<CashFlowSection, BigDecimal> allocations = resolveCashFlowAllocations(line, lines, delta);
                operating = operating.add(safe(allocations.get(CashFlowSection.OPERATING)));
                investing = investing.add(safe(allocations.get(CashFlowSection.INVESTING)));
                financing = financing.add(safe(allocations.get(CashFlowSection.FINANCING)));
            }
        }
        BigDecimal net = operating.add(investing).add(financing);
        return new CashFlowDto(operating, investing, financing, net, context.metadata());
    }

    @Transactional(readOnly = true)
    public InventoryValuationDto inventoryValuationAsOf(LocalDate asOfDate) {
        return inventoryValuation(asOfDate);
    }

    @Transactional(readOnly = true)
    public InventoryValuationDto inventoryValuation() {
        return inventoryValuation(null);
    }

    @Transactional(readOnly = true)
    public GstReturnReportDto gstReturn(Long periodId) {
        Company company = companyContextService.requireCurrentCompany();
        AccountingPeriod period = resolveRequestedPeriod(company, periodId);
        LocalDate startDate = period.getStartDate();
        LocalDate endDate = period.getEndDate();

        List<Invoice> invoices = invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(company, startDate, endDate);
        List<RawMaterialPurchase> purchases = rawMaterialPurchaseRepository
                .findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(company, startDate, endDate);

        Map<BigDecimal, GstRateAccumulator> accumulatorsByRate = new LinkedHashMap<>();
        List<GstReturnReportDto.GstTransactionDetail> transactionDetails = new ArrayList<>();

        for (Invoice invoice : invoices) {
            if (!isIncludedInvoiceStatus(invoice.getStatus())) {
                continue;
            }
            for (InvoiceLine line : invoice.getLines()) {
                LineTaxBreakdown breakdown = resolveInvoiceTaxBreakdown(company, invoice, line);
                if (!breakdown.hasTax()) {
                    continue;
                }
                BigDecimal taxRate = normalizeRate(line != null ? line.getTaxRate() : null);
                GstRateAccumulator accumulator = accumulatorsByRate.computeIfAbsent(taxRate, ignored -> new GstRateAccumulator(taxRate));
                accumulator.addOutput(breakdown.taxableAmount(), breakdown.cgst(), breakdown.sgst(), breakdown.igst());

                transactionDetails.add(new GstReturnReportDto.GstTransactionDetail(
                        "SALES_INVOICE",
                        invoice.getId(),
                        invoice.getInvoiceNumber(),
                        invoice.getIssueDate(),
                        invoice.getDealer() != null ? invoice.getDealer().getName() : null,
                        taxRate,
                        breakdown.taxableAmount(),
                        breakdown.cgst(),
                        breakdown.sgst(),
                        breakdown.igst(),
                        breakdown.totalTax(),
                        "OUTPUT"
                ));
            }
        }

        for (RawMaterialPurchase purchase : purchases) {
            if (!isIncludedPurchaseStatus(purchase.getStatus())) {
                continue;
            }
            for (RawMaterialPurchaseLine line : purchase.getLines()) {
                LineTaxBreakdown breakdown = resolvePurchaseTaxBreakdown(company, purchase, line);
                if (!breakdown.hasTax()) {
                    continue;
                }
                BigDecimal taxRate = normalizeRate(line != null ? line.getTaxRate() : null);
                GstRateAccumulator accumulator = accumulatorsByRate.computeIfAbsent(taxRate, ignored -> new GstRateAccumulator(taxRate));
                accumulator.addInput(breakdown.taxableAmount(), breakdown.cgst(), breakdown.sgst(), breakdown.igst());

                transactionDetails.add(new GstReturnReportDto.GstTransactionDetail(
                        "PURCHASE_INVOICE",
                        purchase.getId(),
                        purchase.getInvoiceNumber(),
                        purchase.getInvoiceDate(),
                        purchase.getSupplier() != null ? purchase.getSupplier().getName() : null,
                        taxRate,
                        breakdown.taxableAmount(),
                        breakdown.cgst(),
                        breakdown.sgst(),
                        breakdown.igst(),
                        breakdown.totalTax(),
                        "INPUT"
                ));
            }
        }

        List<GstReturnReportDto.GstRateSummary> rateSummaries = accumulatorsByRate.values().stream()
                .sorted(Comparator.comparing(GstRateAccumulator::taxRate))
                .map(GstRateAccumulator::toSummary)
                .toList();

        BigDecimal outputCgst = rateSummaries.stream().map(GstReturnReportDto.GstRateSummary::outputCgst).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outputSgst = rateSummaries.stream().map(GstReturnReportDto.GstRateSummary::outputSgst).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outputIgst = rateSummaries.stream().map(GstReturnReportDto.GstRateSummary::outputIgst).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal inputCgst = rateSummaries.stream().map(GstReturnReportDto.GstRateSummary::inputCgst).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal inputSgst = rateSummaries.stream().map(GstReturnReportDto.GstRateSummary::inputSgst).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal inputIgst = rateSummaries.stream().map(GstReturnReportDto.GstRateSummary::inputIgst).reduce(BigDecimal.ZERO, BigDecimal::add);

        GstReturnReportDto.GstComponentSummary outputTax = componentSummary(outputCgst, outputSgst, outputIgst);
        GstReturnReportDto.GstComponentSummary inputTaxCredit = componentSummary(inputCgst, inputSgst, inputIgst);
        GstReturnReportDto.GstComponentSummary netLiability = componentSummary(
                outputTax.cgst().subtract(inputTaxCredit.cgst()),
                outputTax.sgst().subtract(inputTaxCredit.sgst()),
                outputTax.igst().subtract(inputTaxCredit.igst())
        );

        ReportMetadata metadata = new ReportMetadata(
                endDate,
                startDate,
                endDate,
                period.getStatus() == AccountingPeriodStatus.CLOSED ? ReportSource.SNAPSHOT : ReportSource.LIVE,
                period.getId(),
                period.getStatus() != null ? period.getStatus().name() : null,
                null,
                true,
                true,
                null
        );

        List<GstReturnReportDto.GstTransactionDetail> orderedDetails = transactionDetails.stream()
                .sorted(Comparator
                        .comparing(GstReturnReportDto.GstTransactionDetail::transactionDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(GstReturnReportDto.GstTransactionDetail::sourceType, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(GstReturnReportDto.GstTransactionDetail::referenceNumber, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(GstReturnReportDto.GstTransactionDetail::sourceId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return new GstReturnReportDto(
                period.getId(),
                period.getLabel(),
                startDate,
                endDate,
                outputTax,
                inputTaxCredit,
                netLiability,
                rateSummaries,
                orderedDetails,
                metadata
        );
    }

    private InventoryValuationDto inventoryValuation(LocalDate asOfDate) {
        ReportContext context = resolveReportContext(asOfDate);
        if (context.source() == ReportSource.SNAPSHOT && context.snapshot() != null) {
            AccountingPeriodSnapshot snapshot = context.snapshot();
            InventoryValuationService.InventorySnapshot inventorySnapshot = inventoryValuationService.snapshotAsOf(
                    context.company(),
                    context.asOfDate());
            return mapInventorySnapshot(inventorySnapshot, snapshot.getInventoryTotalValue(), snapshot.getInventoryLowStock(), context.metadata());
        }
        Company company = context.company();
        if (context.source() == ReportSource.AS_OF) {
            InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.snapshotAsOf(company, context.asOfDate());
            return mapInventorySnapshot(snapshot, snapshot.totalValue(), snapshot.lowStockItems(), context.metadata());
        }
        InventoryValuationService.InventorySnapshot snapshot = inventoryValuationService.currentSnapshot(company);
        return mapInventorySnapshot(snapshot, snapshot.totalValue(), snapshot.lowStockItems(), context.metadata());
    }

    private InventoryValuationDto mapInventorySnapshot(InventoryValuationService.InventorySnapshot snapshot,
                                                       BigDecimal totalValueOverride,
                                                       long lowStockOverride,
                                                       ReportMetadata metadata) {
        InventoryValuationService.InventorySnapshot effective = snapshot != null
                ? snapshot
                : new InventoryValuationService.InventorySnapshot(BigDecimal.ZERO, 0L, "FIFO", List.of());
        List<InventoryValuationItemDto> items = effective.items() == null
                ? List.of()
                : effective.items().stream()
                .filter(Objects::nonNull)
                .map(item -> new InventoryValuationItemDto(
                        item.inventoryItemId(),
                        item.inventoryType() != null ? item.inventoryType().name() : null,
                        item.code(),
                        item.name(),
                        item.category(),
                        item.brand(),
                        roundCurrency(safe(item.quantityOnHand())),
                        roundCurrency(safe(item.reservedQuantity())),
                        roundCurrency(safe(item.availableQuantity())),
                        roundCurrency(safe(item.unitCost())),
                        roundCurrency(safe(item.totalValue())),
                        item.lowStock()
                ))
                .toList();

        List<InventoryValuationGroupDto> groupByCategory = summarizeInventory(items, "CATEGORY", true);
        List<InventoryValuationGroupDto> groupByBrand = summarizeInventory(items, "BRAND", false);

        BigDecimal totalValue = roundCurrency(totalValueOverride != null ? totalValueOverride : effective.totalValue());
        String costingMethod = effective.costingMethod() != null ? effective.costingMethod() : "FIFO";

        return new InventoryValuationDto(
                totalValue,
                lowStockOverride,
                costingMethod,
                items,
                groupByCategory,
                groupByBrand,
                metadata
        );
    }

    private List<InventoryValuationGroupDto> summarizeInventory(List<InventoryValuationItemDto> items,
                                                                 String groupType,
                                                                 boolean byCategory) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, InventoryGroupingAccumulator> grouped = new LinkedHashMap<>();
        for (InventoryValuationItemDto item : items) {
            if (item == null) {
                continue;
            }
            String key = byCategory ? item.category() : item.brand();
            String normalizedKey = key == null || key.isBlank()
                    ? (byCategory ? "UNCATEGORIZED" : "UNBRANDED")
                    : key.trim();
            InventoryGroupingAccumulator accumulator = grouped.computeIfAbsent(
                    normalizedKey,
                    ignored -> new InventoryGroupingAccumulator(groupType, normalizedKey)
            );
            accumulator.add(safe(item.totalValue()), item.lowStock());
        }
        return grouped.values().stream()
                .map(InventoryGroupingAccumulator::toDto)
                .sorted(Comparator.comparing(InventoryValuationGroupDto::groupKey, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private AccountingPeriod resolveRequestedPeriod(Company company, Long periodId) {
        if (periodId != null) {
            return accountingPeriodRepository.findByCompanyAndId(company, periodId)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                            "Accounting period not found: " + periodId));
        }
        LocalDate today = companyClock.today(company);
        return accountingPeriodRepository.findByCompanyAndYearAndMonth(company, today.getYear(), today.getMonthValue())
                .or(() -> accountingPeriodRepository.findFirstByCompanyOrderByStartDateDesc(company))
                .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_ENTITY_NOT_FOUND,
                        "Accounting period not found for company"));
    }

    private LineTaxBreakdown resolveInvoiceTaxBreakdown(Company company, Invoice invoice, InvoiceLine line) {
        if (line == null) {
            return LineTaxBreakdown.zero();
        }
        BigDecimal taxAmount = safe(line.getTaxAmount());
        if (taxAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return LineTaxBreakdown.zero();
        }

        BigDecimal taxable = requireInvoiceTaxableAmount(invoice, line);
        BigDecimal cgst = safe(line.getCgstAmount());
        BigDecimal sgst = safe(line.getSgstAmount());
        BigDecimal igst = safe(line.getIgstAmount());
        if (cgst.add(sgst).add(igst).compareTo(BigDecimal.ZERO) > 0) {
            return new LineTaxBreakdown(
                    roundCurrency(taxable),
                    roundCurrency(cgst),
                    roundCurrency(sgst),
                    roundCurrency(igst)
            );
        }

        GstService.GstBreakdown split = gstService.splitTaxAmount(
                taxable,
                taxAmount,
                company.getStateCode(),
                invoice != null && invoice.getDealer() != null ? invoice.getDealer().getStateCode() : null
        );
        return new LineTaxBreakdown(
                roundCurrency(split.taxableAmount()),
                roundCurrency(split.cgst()),
                roundCurrency(split.sgst()),
                roundCurrency(split.igst())
        );
    }

    private LineTaxBreakdown resolvePurchaseTaxBreakdown(Company company,
                                                         RawMaterialPurchase purchase,
                                                         RawMaterialPurchaseLine line) {
        if (line == null) {
            return LineTaxBreakdown.zero();
        }
        BigDecimal taxAmount = safe(line.getTaxAmount());
        if (taxAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return LineTaxBreakdown.zero();
        }

        BigDecimal retainedRatio = resolveRetainedQuantityRatio(line.getQuantity(), line.getReturnedQuantity());
        if (retainedRatio.compareTo(BigDecimal.ZERO) <= 0) {
            return LineTaxBreakdown.zero();
        }

        BigDecimal taxable = roundCurrency(safe(line.getCostPerUnit()).multiply(safe(line.getQuantity())));
        BigDecimal netTax = roundCurrency(taxAmount.multiply(retainedRatio));
        BigDecimal netTaxable = roundCurrency(taxable.multiply(retainedRatio));

        BigDecimal cgst = safe(line.getCgstAmount());
        BigDecimal sgst = safe(line.getSgstAmount());
        BigDecimal igst = safe(line.getIgstAmount());
        if (cgst.add(sgst).add(igst).compareTo(BigDecimal.ZERO) > 0) {
            return new LineTaxBreakdown(
                    netTaxable,
                    roundCurrency(cgst.multiply(retainedRatio)),
                    roundCurrency(sgst.multiply(retainedRatio)),
                    roundCurrency(igst.multiply(retainedRatio))
            );
        }

        GstService.GstBreakdown split = gstService.splitTaxAmount(
                netTaxable,
                netTax,
                company.getStateCode(),
                purchase != null && purchase.getSupplier() != null ? purchase.getSupplier().getStateCode() : null
        );
        return new LineTaxBreakdown(
                roundCurrency(split.taxableAmount()),
                roundCurrency(split.cgst()),
                roundCurrency(split.sgst()),
                roundCurrency(split.igst())
        );
    }

    private BigDecimal resolveRetainedQuantityRatio(BigDecimal quantity, BigDecimal returnedQuantity) {
        BigDecimal totalQuantity = safe(quantity);
        if (totalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal returned = safe(returnedQuantity);
        if (returned.compareTo(BigDecimal.ZERO) < 0) {
            returned = BigDecimal.ZERO;
        }
        BigDecimal retained = totalQuantity.subtract(returned);
        if (retained.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        if (retained.compareTo(totalQuantity) >= 0) {
            return BigDecimal.ONE;
        }
        return retained.divide(totalQuantity, 12, RoundingMode.HALF_UP);
    }

    private BigDecimal requireInvoiceTaxableAmount(Invoice invoice, InvoiceLine line) {
        BigDecimal explicitTaxable = line.getTaxableAmount();
        if (explicitTaxable != null && explicitTaxable.compareTo(BigDecimal.ZERO) >= 0) {
            return roundCurrency(explicitTaxable);
        }
        String invoiceReference = invoice != null && invoice.getInvoiceNumber() != null
                ? invoice.getInvoiceNumber()
                : "unknown";
        throw new ApplicationException(
                ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                "Invoice line taxable amount is required and must be non-negative for GST reporting. Invoice: "
                        + invoiceReference);
    }

    private boolean isIncludedInvoiceStatus(String status) {
        if (status == null) {
            return true;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return !normalized.equals("DRAFT")
                && !normalized.equals("VOID")
                && !normalized.equals("REVERSED")
                && !normalized.equals("CANCELLED");
    }

    private boolean isIncludedPurchaseStatus(String status) {
        if (status == null) {
            return true;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return !normalized.equals("VOID")
                && !normalized.equals("REVERSED")
                && !normalized.equals("CANCELLED");
    }

    private GstReturnReportDto.GstComponentSummary componentSummary(BigDecimal cgst,
                                                                    BigDecimal sgst,
                                                                    BigDecimal igst) {
        BigDecimal roundedCgst = roundCurrency(safe(cgst));
        BigDecimal roundedSgst = roundCurrency(safe(sgst));
        BigDecimal roundedIgst = roundCurrency(safe(igst));
        BigDecimal total = roundCurrency(roundedCgst.add(roundedSgst).add(roundedIgst));
        return new GstReturnReportDto.GstComponentSummary(roundedCgst, roundedSgst, roundedIgst, total);
    }

    private BigDecimal normalizeRate(BigDecimal rate) {
        return roundCurrency(safe(rate));
    }

    @Transactional(readOnly = true)
    public List<AccountStatementEntryDto> accountStatement() {
        Company company = companyContextService.requireCurrentCompany();
        var dealers = dealerRepository.findByCompanyOrderByNameAsc(company);
        var balances = dealerLedgerService.currentBalances(dealers.stream().map(Dealer::getId).toList());
        if (balances == null) {
            throw new ApplicationException(
                    ErrorCode.SYSTEM_INTERNAL_ERROR,
                    "Dealer balance snapshot unavailable for account statement");
        }
        return dealers.stream()
                .map(dealer -> {
                    BigDecimal outstanding = balances.getOrDefault(dealer.getId(), BigDecimal.ZERO);
                    DealerLedgerEntry latest = dealerLedgerRepository
                            .findFirstByCompanyAndDealerOrderByEntryDateDescIdDesc(company, dealer)
                            .orElse(null);
                    LocalDate entryDate = latest != null ? latest.getEntryDate() : companyClock.today(company);
                    String reference = latest != null && latest.getReferenceNumber() != null
                            ? latest.getReferenceNumber()
                            : "BALANCE";
                    BigDecimal debit = latest != null ? safe(latest.getDebit()) : outstanding;
                    BigDecimal credit = latest != null ? safe(latest.getCredit()) : BigDecimal.ZERO;
                    Long journalEntryId = latest != null && latest.getJournalEntry() != null
                            ? latest.getJournalEntry().getId()
                            : null;
                    return new AccountStatementEntryDto(
                            dealer.getName(),
                            entryDate,
                            reference,
                            debit,
                            credit,
                            outstanding,
                            journalEntryId);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AgedDebtorDto> agedDebtors(FinancialReportQueryRequest request) {
        return agedDebtorsReportQueryService.generate(requireFinancialReportRequest(request));
    }

    @Transactional(readOnly = true)
    public ReconciliationSummaryDto inventoryReconciliation() {
        Company company = companyContextService.requireCurrentCompany();
        InventoryValuationService.InventorySnapshot totals = inventoryValuationService.currentSnapshot(company);
        BigDecimal ledgerBalance = resolveInventoryLedgerBalance(company);
        BigDecimal variance = totals.totalValue().subtract(ledgerBalance);
        return new ReconciliationSummaryDto(totals.totalValue(), ledgerBalance, variance);
    }

    @Transactional(readOnly = true)
    public List<BalanceWarningDto> balanceWarnings() {
        Company company = companyContextService.requireCurrentCompany();
        List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
        List<BalanceWarningDto> warnings = new ArrayList<>();
        for (Account account : accounts) {
            BigDecimal balance = safe(account.getBalance());
            AccountType type = account.getType();
            String reason = null;
            String severity = "INFO";
            if (type == AccountType.ASSET && balance.compareTo(BigDecimal.ZERO) < 0) {
                reason = "Asset account has a credit balance";
                severity = "HIGH";
            } else if (type == AccountType.LIABILITY && balance.compareTo(BigDecimal.ZERO) > 0) {
                reason = "Liability account has a debit balance";
                severity = "HIGH";
            } else if (type == AccountType.REVENUE && balance.compareTo(BigDecimal.ZERO) > 0) {
                reason = "Revenue account shows a debit balance";
                severity = "MEDIUM";
            } else if ((type == AccountType.EXPENSE || type == AccountType.COGS) && balance.compareTo(BigDecimal.ZERO) < 0) {
                reason = "Expense account shows a credit balance";
                severity = "MEDIUM";
            }
            if (reason != null) {
                warnings.add(new BalanceWarningDto(account.getId(), account.getCode(), account.getName(), balance, severity, reason));
            }
        }
        return warnings;
    }

    @Transactional(readOnly = true)
    public ReconciliationDashboardDto reconciliationDashboard(Long bankAccountId, BigDecimal statementBalance) {
        Company company = companyContextService.requireCurrentCompany();
        Account bankAccount = companyEntityLookup.requireAccount(company, bankAccountId);
        InventoryValuationService.InventorySnapshot totals = inventoryValuationService.currentSnapshot(company);
        BigDecimal ledgerInventoryBalance = resolveInventoryLedgerBalance(company);
        BigDecimal physicalInventoryValue = totals.totalValue();
        BigDecimal inventoryVariance = physicalInventoryValue.subtract(ledgerInventoryBalance);
        BigDecimal bankLedgerBalance = safe(bankAccount.getBalance());
        BigDecimal bankStatementBalance = statementBalance != null ? statementBalance : bankLedgerBalance;
        BigDecimal bankVariance = bankLedgerBalance.subtract(bankStatementBalance);
        boolean inventoryBalanced = inventoryVariance.abs().compareTo(BALANCE_TOLERANCE) <= 0;
        boolean bankBalanced = bankVariance.abs().compareTo(BALANCE_TOLERANCE) <= 0;
        return new ReconciliationDashboardDto(
                ledgerInventoryBalance,
                physicalInventoryValue,
                inventoryVariance,
                bankLedgerBalance,
                bankStatementBalance,
                bankVariance,
                inventoryBalanced,
                bankBalanced,
                balanceWarnings());
    }

    @Transactional(readOnly = true)
    public TrialBalanceDto trialBalance(LocalDate asOfDate) {
        return trialBalance(ReportQueryRequestBuilder.fromAsOfDate(asOfDate));
    }

    @Transactional(readOnly = true)
    public TrialBalanceDto trialBalance(FinancialReportQueryRequest request) {
        return trialBalanceReportQueryService.generate(requireFinancialReportRequest(request));
    }

    private FinancialReportQueryRequest requireFinancialReportRequest(FinancialReportQueryRequest request) {
        if (request == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Financial report query request is required");
        }
        return request;
    }

    private ReportContext resolveReportContext(LocalDate asOfDate) {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate effectiveDate = asOfDate != null ? asOfDate : companyClock.today(company);
        AccountingPeriod period = accountingPeriodRepository
                .findByCompanyAndYearAndMonth(company, effectiveDate.getYear(), effectiveDate.getMonthValue())
                .orElse(null);
        AccountingPeriodSnapshot snapshot = null;
        ReportSource source;
        if (period != null && period.getStatus() == AccountingPeriodStatus.CLOSED) {
            snapshot = snapshotRepository.findByCompanyAndPeriod(company, period)
                    .orElseThrow(() -> new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                            "Closed period snapshot is required for reports")
                            .withDetail("companyId", company.getId())
                            .withDetail("periodId", period.getId())
                            .withDetail("asOfDate", effectiveDate));
            source = ReportSource.SNAPSHOT;
        } else if (asOfDate != null) {
            source = ReportSource.AS_OF;
        } else {
            source = ReportSource.LIVE;
        }
        return new ReportContext(company, effectiveDate, period, snapshot, source);
    }

    private List<TrialBalanceLine> resolveTrialBalanceLines(ReportContext context) {
        if (context.source() == ReportSource.SNAPSHOT && context.snapshot() != null) {
            return snapshotLineRepository.findBySnapshotOrderByAccountCodeAsc(context.snapshot()).stream()
                    .map(line -> new TrialBalanceLine(
                            line.getAccountId(),
                            line.getAccountCode(),
                            line.getAccountName(),
                            line.getAccountType(),
                            safe(line.getDebit()),
                            safe(line.getCredit())
                    ))
                    .toList();
        }
        Company company = context.company();
        List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);
        if (context.source() == ReportSource.AS_OF) {
            Map<Long, BigDecimal> balances = summarizeBalances(company, context.asOfDate());
            return accounts.stream()
                    .map(account -> toTrialBalanceLine(account, balances.getOrDefault(account.getId(), BigDecimal.ZERO)))
                    .toList();
        }
        return accounts.stream()
                .map(account -> toTrialBalanceLine(account, safe(account.getBalance())))
                .toList();
    }

    private Map<Long, BigDecimal> summarizeBalances(Company company, LocalDate asOfDate) {
        Map<Long, BigDecimal> balances = new HashMap<>();
        List<Object[]> rows = journalLineRepository.summarizeByAccountUpTo(company, asOfDate);
        for (Object[] row : rows) {
            if (row == null || row.length < 3 || row[0] == null) {
                continue;
            }
            Long accountId = (Long) row[0];
            BigDecimal debit = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
            BigDecimal credit = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
            balances.put(accountId, debit.subtract(credit));
        }
        return balances;
    }

    private TrialBalanceLine toTrialBalanceLine(Account account, BigDecimal balance) {
        BigDecimal safeBalance = safe(balance);
        AccountType type = account != null ? account.getType() : null;
        boolean debitNormal = type == null || type.isDebitNormalBalance();
        BigDecimal normalized = debitNormal ? safeBalance : safeBalance.negate();
        BigDecimal debit;
        BigDecimal credit;
        if (normalized.compareTo(BigDecimal.ZERO) >= 0) {
            debit = debitNormal ? normalized : BigDecimal.ZERO;
            credit = debitNormal ? BigDecimal.ZERO : normalized;
        } else {
            BigDecimal amount = normalized.abs();
            debit = debitNormal ? BigDecimal.ZERO : amount;
            credit = debitNormal ? amount : BigDecimal.ZERO;
        }
        return new TrialBalanceLine(
                account != null ? account.getId() : null,
                account != null ? account.getCode() : null,
                account != null ? account.getName() : null,
                type,
                debit,
                credit);
    }

    private record TrialBalanceLine(
            Long accountId,
            String code,
            String name,
            AccountType type,
            BigDecimal debit,
            BigDecimal credit
    ) {
    }

    private record ReportContext(
            Company company,
            LocalDate asOfDate,
            AccountingPeriod period,
            AccountingPeriodSnapshot snapshot,
            ReportSource source
    ) {
        ReportMetadata metadata() {
            Long periodId = period != null ? period.getId() : null;
            String status = period != null && period.getStatus() != null ? period.getStatus().name() : null;
            Long snapshotId = snapshot != null ? snapshot.getId() : null;
            return new ReportMetadata(asOfDate, source, periodId, status, snapshotId);
        }
    }

    private boolean isInventoryAccount(Account account) {
        if (account == null || account.getName() == null) {
            return false;
        }
        return account.getName().toLowerCase(Locale.ROOT).contains("inventory");
    }

    private boolean isCashAccount(Account account) {
        if (account == null || account.getType() != AccountType.ASSET) {
            return false;
        }
        String label = (account.getCode() + " " + account.getName()).toLowerCase(Locale.ROOT);
        return label.contains("cash")
                || label.contains("bank")
                || label.contains("wallet")
                || label.contains("upi");
    }

    private Map<CashFlowSection, BigDecimal> resolveCashFlowAllocations(JournalLine cashLine,
                                                                         List<JournalLine> entryLines,
                                                                         BigDecimal cashDelta) {
        Map<CashFlowSection, BigDecimal> allocations = new EnumMap<>(CashFlowSection.class);
        if (cashDelta == null || cashDelta.compareTo(BigDecimal.ZERO) == 0) {
            return allocations;
        }
        Map<CashFlowSection, BigDecimal> weights = resolveCashFlowWeights(cashLine, entryLines, cashDelta);
        if (weights.isEmpty()) {
            allocations.put(CashFlowSection.OPERATING, cashDelta);
            return allocations;
        }
        BigDecimal totalWeight = weights.values().stream()
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
            allocations.put(CashFlowSection.OPERATING, cashDelta);
            return allocations;
        }

        CashFlowSection dominantSection = dominantCashFlowSection(weights);
        BigDecimal allocated = BigDecimal.ZERO;
        for (Map.Entry<CashFlowSection, BigDecimal> entry : weights.entrySet()) {
            CashFlowSection section = entry.getKey();
            if (section == dominantSection) {
                continue;
            }
            BigDecimal weight = safe(entry.getValue());
            if (weight.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal sectionAmount = roundCurrency(cashDelta.multiply(weight)
                    .divide(totalWeight, 8, java.math.RoundingMode.HALF_UP));
            if (sectionAmount.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            allocations.merge(section, sectionAmount, BigDecimal::add);
            allocated = allocated.add(sectionAmount);
        }

        BigDecimal dominantAmount = roundCurrency(cashDelta.subtract(allocated));
        if (dominantAmount.compareTo(BigDecimal.ZERO) != 0) {
            allocations.merge(dominantSection, dominantAmount, BigDecimal::add);
        }
        return allocations;
    }

    private Map<CashFlowSection, BigDecimal> resolveCashFlowWeights(JournalLine cashLine,
                                                                     List<JournalLine> entryLines,
                                                                     BigDecimal cashDelta) {
        Map<CashFlowSection, BigDecimal> weights = new EnumMap<>(CashFlowSection.class);
        if (cashLine == null || entryLines == null || entryLines.isEmpty() || cashDelta == null) {
            return weights;
        }
        boolean inflow = cashDelta.compareTo(BigDecimal.ZERO) > 0;
        List<JournalLine> candidates = entryLines.stream()
                .filter(line -> line != null && line != cashLine)
                .filter(line -> !isCashAccount(line.getAccount()))
                .filter(line -> inflow
                        ? safe(line.getCredit()).compareTo(BigDecimal.ZERO) > 0
                        : safe(line.getDebit()).compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (candidates.isEmpty()) {
            candidates = entryLines.stream()
                    .filter(line -> line != null && line != cashLine)
                    .filter(line -> !isCashAccount(line.getAccount()))
                    .toList();
        }
        for (JournalLine candidate : candidates) {
            BigDecimal weight = inflow ? safe(candidate.getCredit()) : safe(candidate.getDebit());
            if (weight.compareTo(BigDecimal.ZERO) <= 0) {
                weight = safe(candidate.getDebit()).add(safe(candidate.getCredit()));
            }
            if (weight.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            CashFlowSection section = classifyCashFlowCounterparty(candidate.getAccount());
            weights.merge(section, weight.abs(), BigDecimal::add);
        }
        return weights;
    }

    private CashFlowSection dominantCashFlowSection(Map<CashFlowSection, BigDecimal> weights) {
        if (weights == null || weights.isEmpty()) {
            return CashFlowSection.OPERATING;
        }
        CashFlowSection resolved = CashFlowSection.OPERATING;
        BigDecimal maxWeight = BigDecimal.ZERO;
        for (Map.Entry<CashFlowSection, BigDecimal> entry : weights.entrySet()) {
            BigDecimal weight = safe(entry.getValue());
            if (weight.compareTo(maxWeight) > 0) {
                maxWeight = weight;
                resolved = entry.getKey();
            }
        }
        return resolved;
    }

    private BigDecimal roundCurrency(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private enum CashFlowSection {
        OPERATING,
        INVESTING,
        FINANCING
    }

    private final class InventoryGroupingAccumulator {
        private final String groupType;
        private final String groupKey;
        private BigDecimal totalValue = BigDecimal.ZERO;
        private long itemCount = 0;
        private long lowStockItems = 0;

        private InventoryGroupingAccumulator(String groupType, String groupKey) {
            this.groupType = groupType;
            this.groupKey = groupKey;
        }

        private void add(BigDecimal value, boolean lowStock) {
            this.totalValue = this.totalValue.add(value == null ? BigDecimal.ZERO : value);
            this.itemCount++;
            if (lowStock) {
                this.lowStockItems++;
            }
        }

        private InventoryValuationGroupDto toDto() {
            return new InventoryValuationGroupDto(
                    groupType,
                    groupKey,
                    roundCurrency(totalValue),
                    itemCount,
                    lowStockItems
            );
        }
    }

    private static final class GstRateAccumulator {
        private final BigDecimal taxRate;
        private BigDecimal taxableAmount = BigDecimal.ZERO;
        private BigDecimal outputCgst = BigDecimal.ZERO;
        private BigDecimal outputSgst = BigDecimal.ZERO;
        private BigDecimal outputIgst = BigDecimal.ZERO;
        private BigDecimal inputCgst = BigDecimal.ZERO;
        private BigDecimal inputSgst = BigDecimal.ZERO;
        private BigDecimal inputIgst = BigDecimal.ZERO;

        private GstRateAccumulator(BigDecimal taxRate) {
            this.taxRate = taxRate == null ? BigDecimal.ZERO : taxRate;
        }

        private BigDecimal taxRate() {
            return taxRate;
        }

        private void addOutput(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
            taxableAmount = taxableAmount.add(safeAmount(taxable));
            outputCgst = outputCgst.add(safeAmount(cgst));
            outputSgst = outputSgst.add(safeAmount(sgst));
            outputIgst = outputIgst.add(safeAmount(igst));
        }

        private void addInput(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
            taxableAmount = taxableAmount.add(safeAmount(taxable));
            inputCgst = inputCgst.add(safeAmount(cgst));
            inputSgst = inputSgst.add(safeAmount(sgst));
            inputIgst = inputIgst.add(safeAmount(igst));
        }

        private GstReturnReportDto.GstRateSummary toSummary() {
            BigDecimal outputTax = outputCgst.add(outputSgst).add(outputIgst);
            BigDecimal inputTaxCredit = inputCgst.add(inputSgst).add(inputIgst);
            BigDecimal netTax = outputTax.subtract(inputTaxCredit);
            return new GstReturnReportDto.GstRateSummary(
                    roundAmount(taxRate),
                    roundAmount(taxableAmount),
                    roundAmount(outputTax),
                    roundAmount(inputTaxCredit),
                    roundAmount(netTax),
                    roundAmount(outputCgst),
                    roundAmount(outputSgst),
                    roundAmount(outputIgst),
                    roundAmount(inputCgst),
                    roundAmount(inputSgst),
                    roundAmount(inputIgst)
            );
        }

        private static BigDecimal safeAmount(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }

        private static BigDecimal roundAmount(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
        }
    }

    private record LineTaxBreakdown(BigDecimal taxableAmount,
                                    BigDecimal cgst,
                                    BigDecimal sgst,
                                    BigDecimal igst) {
        private boolean hasTax() {
            return totalTax().compareTo(BigDecimal.ZERO) > 0;
        }

        private BigDecimal totalTax() {
            return safeTax(cgst).add(safeTax(sgst)).add(safeTax(igst)).setScale(2, RoundingMode.HALF_UP);
        }

        private static LineTaxBreakdown zero() {
            return new LineTaxBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        private static BigDecimal safeTax(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }

    private CashFlowSection classifyCashFlowCounterparty(Account account) {
        if (account == null) {
            return CashFlowSection.OPERATING;
        }
        AccountType type = account.getType();
        String label = ((account.getCode() != null ? account.getCode() : "")
                + " "
                + (account.getName() != null ? account.getName() : ""))
                .toLowerCase(Locale.ROOT);
        if (type == AccountType.EQUITY) {
            return CashFlowSection.FINANCING;
        }
        if (type == AccountType.LIABILITY) {
            if (containsAny(label, "loan", "borrow", "debt", "note payable", "capital lease", "long-term")) {
                return CashFlowSection.FINANCING;
            }
            return CashFlowSection.OPERATING;
        }
        if (type == AccountType.ASSET) {
            if (containsAny(label, "fixed asset", "equipment", "machinery", "vehicle", "building", "plant", "investment")) {
                return CashFlowSection.INVESTING;
            }
            return CashFlowSection.OPERATING;
        }
        return CashFlowSection.OPERATING;
    }

    private boolean containsAny(String value, String... tokens) {
        if (value == null || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal resolveInventoryLedgerBalance(Company company) {
        Long defaultInventoryAccountId = company.getDefaultInventoryAccountId();
        if (defaultInventoryAccountId != null) {
            Account account = companyEntityLookup.requireAccount(company, defaultInventoryAccountId);
            return safe(account.getBalance());
        }
        return accountRepository.findByCompanyOrderByCodeAsc(company).stream()
                .filter(this::isInventoryAccount)
                .map(Account::getBalance)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }


    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional(readOnly = true)
    public List<WastageReportDto> wastageReport() {
        Company company = companyContextService.requireCurrentCompany();
        List<ProductionLog> logs = productionLogRepository.findTop25ByCompanyOrderByProducedAtDesc(company);

        return logs.stream()
                .filter(log -> log.getWastageQuantity() != null &&
                               log.getWastageQuantity().compareTo(BigDecimal.ZERO) > 0)
                .map(log -> {
                    BigDecimal mixedQty = safe(log.getMixedQuantity());
                    BigDecimal wastageQty = safe(log.getWastageQuantity());
                    BigDecimal wastagePercentage = mixedQty.compareTo(BigDecimal.ZERO) > 0
                            ? wastageQty.divide(mixedQty, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                            : BigDecimal.ZERO;

                    BigDecimal wastageValue = wastageQty.multiply(safe(log.getUnitCost()));

                    return new WastageReportDto(
                            log.getId(),
                            log.getProductionCode(),
                            log.getProduct() != null ? log.getProduct().getProductName() : "Unknown",
                            log.getBatchColour(),
                            log.getMixedQuantity(),
                            log.getTotalPackedQuantity(),
                            log.getWastageQuantity(),
                            wastagePercentage,
                            wastageValue,
                            log.getProducedAt()
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public CostBreakdownDto costBreakdown(Long productionLogId) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionLog log = companyEntityLookup.requireProductionLog(company, productionLogId);

        BigDecimal materialCost = safe(log.getMaterialCostTotal());
        BigDecimal laborCost = safe(log.getLaborCostTotal());
        BigDecimal overheadCost = safe(log.getOverheadCostTotal());

        List<PackingRecord> packingRecords = packingRecordRepository
                .findByCompanyAndProductionLogOrderByPackedDateAscIdAsc(company, log);

        List<PackedBatchTraceDto> packedBatches = new ArrayList<>();
        List<RawMaterialTraceDto> rawMaterialTrace = new ArrayList<>();

        BigDecimal packedQuantity = BigDecimal.ZERO;
        BigDecimal packagingCost = BigDecimal.ZERO;

        for (PackingRecord record : packingRecords) {
            String referencePrefix = log.getProductionCode() + "-PACK-" + record.getId();

            BigDecimal quantity = safe(record.getQuantityPacked());
            BigDecimal unitCost = Optional.ofNullable(record.getFinishedGoodBatch())
                    .map(batch -> safe(batch.getUnitCost()))
                    .orElse(BigDecimal.ZERO);
            BigDecimal totalValue = quantity.multiply(unitCost);

            List<InventoryMovement> movements = inventoryMovementRepository
                    .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                            company,
                            InventoryReference.PACKING_RECORD,
                            referencePrefix);
            Long journalEntryId = movements.stream()
                    .map(InventoryMovement::getJournalEntryId)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);

            packedBatches.add(new PackedBatchTraceDto(
                    record.getId(),
                    record.getFinishedGoodBatch() != null ? record.getFinishedGoodBatch().getId() : null,
                    record.getFinishedGoodBatch() != null ? record.getFinishedGoodBatch().getPublicId() : null,
                    record.getFinishedGoodBatch() != null ? record.getFinishedGoodBatch().getBatchCode() : null,
                    record.getFinishedGood() != null ? record.getFinishedGood().getProductCode() : null,
                    record.getFinishedGood() != null ? record.getFinishedGood().getName() : null,
                    record.getSizeVariant() != null ? record.getSizeVariant().getSizeLabel() : record.getPackagingSize(),
                    quantity,
                    unitCost,
                    totalValue,
                    referencePrefix,
                    journalEntryId
            ));

            packedQuantity = packedQuantity.add(quantity);
            packagingCost = packagingCost.add(safe(record.getPackagingCost()));

            List<RawMaterialMovement> packagingMovements = rawMaterialMovementRepository
                    .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                            company,
                            InventoryReference.PACKING_RECORD,
                            referencePrefix);
            for (RawMaterialMovement movement : packagingMovements) {
                BigDecimal movementQuantity = safe(movement.getQuantity());
                BigDecimal movementUnitCost = safe(movement.getUnitCost());
                rawMaterialTrace.add(new RawMaterialTraceDto(
                        movement.getId(),
                        movement.getRawMaterial() != null ? movement.getRawMaterial().getId() : null,
                        movement.getRawMaterial() != null ? movement.getRawMaterial().getSku() : null,
                        movement.getRawMaterial() != null ? movement.getRawMaterial().getName() : null,
                        movement.getRawMaterialBatch() != null ? movement.getRawMaterialBatch().getId() : null,
                        movement.getRawMaterialBatch() != null ? movement.getRawMaterialBatch().getBatchCode() : null,
                        movementQuantity,
                        movementUnitCost,
                        movementQuantity.multiply(movementUnitCost),
                        movement.getMovementType(),
                        movement.getReferenceType(),
                        movement.getReferenceId(),
                        movement.getCreatedAt(),
                        movement.getJournalEntryId()
                ));
            }
        }

        log.getMaterials().stream()
                .sorted(Comparator.comparing(material -> Optional.ofNullable(material.getRawMaterialMovementId()).orElse(Long.MAX_VALUE)))
                .forEach(material -> {
                    BigDecimal qty = safe(material.getQuantity());
                    BigDecimal unit = safe(material.getCostPerUnit());
                    rawMaterialTrace.add(new RawMaterialTraceDto(
                            material.getRawMaterialMovementId(),
                            material.getRawMaterial() != null ? material.getRawMaterial().getId() : null,
                            material.getRawMaterial() != null ? material.getRawMaterial().getSku() : null,
                            material.getMaterialName(),
                            material.getRawMaterialBatch() != null ? material.getRawMaterialBatch().getId() : null,
                            material.getRawMaterialBatch() != null ? material.getRawMaterialBatch().getBatchCode() : null,
                            qty,
                            unit,
                            safe(material.getTotalCost()),
                            "ISSUE",
                            InventoryReference.PRODUCTION_LOG,
                            log.getProductionCode(),
                            log.getProducedAt(),
                            null
                    ));
                });

        BigDecimal totalCost = materialCost
                .add(laborCost)
                .add(overheadCost)
                .add(packagingCost);

        CostComponentTraceDto costComponents = new CostComponentTraceDto(
                materialCost,
                laborCost,
                overheadCost,
                packagingCost,
                totalCost,
                safe(log.getMixedQuantity()),
                packedQuantity,
                packedQuantity.compareTo(BigDecimal.ZERO) > 0
                        ? totalCost.divide(packedQuantity, 4, RoundingMode.HALF_UP)
                        : safe(log.getUnitCost())
        );

        return new CostBreakdownDto(
                log.getId(),
                log.getProductionCode(),
                log.getProduct() != null ? log.getProduct().getProductName() : "Unknown",
                log.getBatchColour(),
                log.getMixedQuantity(),
                log.getMaterialCostTotal(),
                log.getLaborCostTotal(),
                log.getOverheadCostTotal(),
                totalCost,
                log.getUnitCost(),
                log.getProducedAt(),
                costComponents,
                packedBatches,
                rawMaterialTrace
        );
    }

    @Transactional(readOnly = true)
    public MonthlyProductionCostDto monthlyProductionCosts(Integer year, Integer month) {
        Company company = companyContextService.requireCurrentCompany();

        java.time.YearMonth yearMonth = java.time.YearMonth.of(year, month);
        java.time.LocalDate startDate = yearMonth.atDay(1);
        java.time.LocalDate endDate = yearMonth.atEndOfMonth().plusDays(1);

        ZoneId zone = companyClock.zoneId(company);
        java.time.Instant startInstant = startDate.atStartOfDay(zone).toInstant();
        java.time.Instant endInstant = endDate.atStartOfDay(zone).toInstant();

        List<ProductionLog> logs = productionLogRepository.findFullyPackedBatchesByMonth(
                company, startInstant, endInstant);

        if (logs.isEmpty()) {
            return new MonthlyProductionCostDto(
                    year, month, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
            );
        }

        BigDecimal totalLiters = BigDecimal.ZERO;
        BigDecimal totalMaterialCost = BigDecimal.ZERO;
        BigDecimal totalLaborCost = BigDecimal.ZERO;
        BigDecimal totalOverheadCost = BigDecimal.ZERO;
        BigDecimal totalWastage = BigDecimal.ZERO;

        for (ProductionLog log : logs) {
            totalLiters = totalLiters.add(safe(log.getMixedQuantity()));
            totalMaterialCost = totalMaterialCost.add(safe(log.getMaterialCostTotal()));
            totalLaborCost = totalLaborCost.add(safe(log.getLaborCostTotal()));
            totalOverheadCost = totalOverheadCost.add(safe(log.getOverheadCostTotal()));
            totalWastage = totalWastage.add(safe(log.getWastageQuantity()));
        }

        BigDecimal totalCost = totalMaterialCost.add(totalLaborCost).add(totalOverheadCost);
        BigDecimal avgCostPerLiter = totalLiters.compareTo(BigDecimal.ZERO) > 0
                ? totalCost.divide(totalLiters, 4, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal wastagePercentage = totalLiters.compareTo(BigDecimal.ZERO) > 0
                ? totalWastage.divide(totalLiters, 4, java.math.RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        return new MonthlyProductionCostDto(
                year,
                month,
                logs.size(),
                totalLiters,
                totalMaterialCost,
                totalLaborCost,
                totalOverheadCost,
                totalCost,
                avgCostPerLiter,
                totalWastage,
                wastagePercentage
        );
    }
}
