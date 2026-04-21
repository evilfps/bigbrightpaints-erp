package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReconciliationDto;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReportBreakdownDto;
import com.bigbrightpaints.erp.modules.accounting.dto.GstReturnDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;

import jakarta.transaction.Transactional;

@Service
@Transactional(Transactional.TxType.SUPPORTS)
public class TaxService {

  private final CompanyContextService companyContextService;
  private final CompanyAccountingSettingsService companyAccountingSettingsService;
  private final CompanyClock companyClock;
  private final JournalLineRepository journalLineRepository;
  private final GstService gstService;
  private final InvoiceRepository invoiceRepository;
  private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;

  public TaxService(
      CompanyContextService companyContextService,
      CompanyAccountingSettingsService companyAccountingSettingsService,
      CompanyClock companyClock,
      JournalLineRepository journalLineRepository,
      GstService gstService,
      InvoiceRepository invoiceRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository) {
    this.companyContextService = companyContextService;
    this.companyAccountingSettingsService = companyAccountingSettingsService;
    this.companyClock = companyClock;
    this.journalLineRepository = journalLineRepository;
    this.gstService = gstService;
    this.invoiceRepository = invoiceRepository;
    this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
  }

  public GstReturnDto generateGstReturn(YearMonth period) {
    Company company = companyContextService.requireCurrentCompany();
    YearMonth target = resolvePeriod(company, period);
    LocalDate start = target.atDay(1);
    LocalDate end = target.atEndOfMonth();

    if (isNonGstMode(company)) {
      ensureNonGstCompanyDoesNotCarryGstAccounts(company);
      return buildGstReturn(target, start, end, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    GstReportAggregation aggregation = aggregateGstReport(company, start, end);
    if (aggregation.hasTaxableDocuments() || company.getId() != null) {
      GstReconciliationDto.GstComponentSummary collectedSummary =
          componentSummary(
              aggregation.collected().cgst,
              aggregation.collected().sgst,
              aggregation.collected().igst);
      GstReconciliationDto.GstComponentSummary inputTaxCreditSummary =
          componentSummary(
              aggregation.inputTaxCredit().cgst,
              aggregation.inputTaxCredit().sgst,
              aggregation.inputTaxCredit().igst);
      return buildGstReturn(
          target, start, end, collectedSummary.getTotal(), inputTaxCreditSummary.getTotal());
    }

    var taxConfig = companyAccountingSettingsService.requireTaxAccounts();

    BigDecimal outputTaxBalance = sumTax(company, taxConfig.outputTaxAccountId(), start, end, true);
    BigDecimal inputTaxBalance = sumTax(company, taxConfig.inputTaxAccountId(), start, end, false);

    BigDecimal outputTax =
        MoneyUtils.roundCurrency(
            positivePortion(outputTaxBalance).add(positivePortion(inputTaxBalance.negate())));
    BigDecimal inputTax =
        MoneyUtils.roundCurrency(
            positivePortion(inputTaxBalance).add(positivePortion(outputTaxBalance.negate())));

    return buildGstReturn(target, start, end, outputTax, inputTax);
  }

  public GstReportBreakdownDto generateGstReportBreakdown(YearMonth period) {
    Company company = companyContextService.requireCurrentCompany();
    YearMonth target = resolvePeriod(company, period);
    LocalDate start = target.atDay(1);
    LocalDate end = target.atEndOfMonth();

    GstReportBreakdownDto dto = new GstReportBreakdownDto();
    dto.setPeriod(target);
    dto.setPeriodStart(start);
    dto.setPeriodEnd(end);

    if (isNonGstMode(company)) {
      ensureNonGstCompanyDoesNotCarryGstAccounts(company);
      dto.setCollected(componentSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
      dto.setInputTaxCredit(componentSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
      dto.setNetLiability(componentSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
      dto.setRateSummaries(List.of());
      dto.setTransactionDetails(List.of());
      return dto;
    }

    GstReportAggregation aggregation = aggregateGstReport(company, start, end);
    dto.setCollected(
        componentSummary(
            aggregation.collected().cgst,
            aggregation.collected().sgst,
            aggregation.collected().igst));
    dto.setInputTaxCredit(
        componentSummary(
            aggregation.inputTaxCredit().cgst,
            aggregation.inputTaxCredit().sgst,
            aggregation.inputTaxCredit().igst));
    dto.setNetLiability(
        componentSummary(
            aggregation.collected().cgst.subtract(aggregation.inputTaxCredit().cgst),
            aggregation.collected().sgst.subtract(aggregation.inputTaxCredit().sgst),
            aggregation.collected().igst.subtract(aggregation.inputTaxCredit().igst)));
    dto.setRateSummaries(aggregation.rateSummaries());
    dto.setTransactionDetails(aggregation.transactionDetails());
    return dto;
  }

  public GstReconciliationDto generateGstReconciliation(YearMonth period) {
    Company company = companyContextService.requireCurrentCompany();
    YearMonth target = resolvePeriod(company, period);
    LocalDate start = target.atDay(1);
    LocalDate end = target.atEndOfMonth();

    GstReconciliationDto dto = new GstReconciliationDto();
    dto.setPeriod(target);
    dto.setPeriodStart(start);
    dto.setPeriodEnd(end);

    if (isNonGstMode(company)) {
      ensureNonGstCompanyDoesNotCarryGstAccounts(company);
      dto.setCollected(componentSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
      dto.setInputTaxCredit(componentSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
      dto.setNetLiability(componentSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
      return dto;
    }

    GstReportAggregation aggregation = aggregateGstReport(company, start, end);
    ComponentTotals collected = aggregation.collected();
    ComponentTotals inputCredit = aggregation.inputTaxCredit();

    dto.setCollected(componentSummary(collected.cgst, collected.sgst, collected.igst));
    dto.setInputTaxCredit(componentSummary(inputCredit.cgst, inputCredit.sgst, inputCredit.igst));
    dto.setNetLiability(
        componentSummary(
            collected.cgst.subtract(inputCredit.cgst),
            collected.sgst.subtract(inputCredit.sgst),
            collected.igst.subtract(inputCredit.igst)));
    return dto;
  }

  private YearMonth resolvePeriod(Company company, YearMonth period) {
    YearMonth target = period != null ? period : YearMonth.from(companyClock.today(company));
    if (period != null) {
      YearMonth currentPeriod = YearMonth.from(companyClock.today(company));
      if (target.isAfter(currentPeriod)) {
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_DATE, "GST return period cannot be in the future")
            .withDetail("requestedPeriod", target.toString())
            .withDetail("currentPeriod", currentPeriod.toString());
      }
    }
    return target;
  }

  private BigDecimal sumTax(
      Company company, Long accountId, LocalDate start, LocalDate end, boolean outputTax) {
    if (accountId == null) {
      return BigDecimal.ZERO;
    }
    List<JournalLine> lines =
        journalLineRepository.findLinesForAccountBetween(company, accountId, start, end);
    if (lines == null || lines.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal total = BigDecimal.ZERO;
    for (JournalLine line : lines) {
      BigDecimal debit = safe(line.getDebit());
      BigDecimal credit = safe(line.getCredit());
      BigDecimal delta = outputTax ? credit.subtract(debit) : debit.subtract(credit);
      total = total.add(delta);
    }
    return total;
  }

  private BigDecimal safe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal positivePortion(BigDecimal value) {
    return value.compareTo(BigDecimal.ZERO) > 0 ? value : BigDecimal.ZERO;
  }

  private GstReportAggregation aggregateGstReport(Company company, LocalDate start, LocalDate end) {
    List<Invoice> invoices =
        invoiceRepository.findByCompanyAndIssueDateBetweenOrderByIssueDateAsc(company, start, end);
    List<RawMaterialPurchase> purchases =
        rawMaterialPurchaseRepository.findByCompanyAndInvoiceDateBetweenOrderByInvoiceDateAsc(
            company, start, end);
    if (invoices == null) {
      invoices = List.of();
    }
    if (purchases == null) {
      purchases = List.of();
    }

    ComponentTotals collected = new ComponentTotals();
    ComponentTotals inputTaxCredit = new ComponentTotals();
    Map<BigDecimal, GstRateAccumulator> accumulatorsByRate = new LinkedHashMap<>();
    List<GstReportBreakdownDto.GstTransactionDetail> transactionDetails = new ArrayList<>();

    for (Invoice invoice : invoices) {
      if (!isIncludedInvoiceStatus(invoice.getStatus())) {
        continue;
      }
      List<InvoiceLine> lines = invoice.getLines() == null ? List.of() : invoice.getLines();
      for (InvoiceLine line : lines) {
        GstService.GstBreakdown breakdown = resolveInvoiceLineBreakdown(company, invoice, line);
        if (!hasTax(breakdown)) {
          continue;
        }
        collected.add(breakdown);
        BigDecimal taxRate = normalizeRate(line != null ? line.getTaxRate() : null);
        GstRateAccumulator accumulator =
            accumulatorsByRate.computeIfAbsent(taxRate, ignored -> new GstRateAccumulator(taxRate));
        accumulator.addOutput(
            breakdown.taxableAmount(), breakdown.cgst(), breakdown.sgst(), breakdown.igst());

        transactionDetails.add(
            new GstReportBreakdownDto.GstTransactionDetail(
                "SALES_INVOICE",
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getIssueDate(),
                invoice.getDealer() != null ? invoice.getDealer().getName() : null,
                taxRate,
                roundAmount(breakdown.taxableAmount()),
                roundAmount(breakdown.cgst()),
                roundAmount(breakdown.sgst()),
                roundAmount(breakdown.igst()),
                roundAmount(breakdown.totalTax()),
                "OUTPUT"));
      }
    }

    for (RawMaterialPurchase purchase : purchases) {
      if (!isIncludedPurchaseStatus(purchase.getStatus())) {
        continue;
      }
      List<RawMaterialPurchaseLine> lines =
          purchase.getLines() == null ? List.of() : purchase.getLines();
      for (RawMaterialPurchaseLine line : lines) {
        GstService.GstBreakdown breakdown = resolvePurchaseLineBreakdown(company, purchase, line);
        if (!hasTax(breakdown)) {
          continue;
        }
        inputTaxCredit.add(breakdown);
        BigDecimal taxRate = normalizeRate(line != null ? line.getTaxRate() : null);
        GstRateAccumulator accumulator =
            accumulatorsByRate.computeIfAbsent(taxRate, ignored -> new GstRateAccumulator(taxRate));
        accumulator.addInput(
            breakdown.taxableAmount(), breakdown.cgst(), breakdown.sgst(), breakdown.igst());

        transactionDetails.add(
            new GstReportBreakdownDto.GstTransactionDetail(
                "PURCHASE_INVOICE",
                purchase.getId(),
                purchase.getInvoiceNumber(),
                purchase.getInvoiceDate(),
                purchase.getSupplier() != null ? purchase.getSupplier().getName() : null,
                taxRate,
                roundAmount(breakdown.taxableAmount()),
                roundAmount(breakdown.cgst()),
                roundAmount(breakdown.sgst()),
                roundAmount(breakdown.igst()),
                roundAmount(breakdown.totalTax()),
                "INPUT"));
      }
    }

    List<GstReportBreakdownDto.GstRateSummary> rateSummaries =
        accumulatorsByRate.values().stream()
            .sorted(Comparator.comparing(GstRateAccumulator::taxRate))
            .map(GstRateAccumulator::toSummary)
            .toList();
    List<GstReportBreakdownDto.GstTransactionDetail> orderedDetails =
        transactionDetails.stream()
            .sorted(
                Comparator.comparing(
                        GstReportBreakdownDto.GstTransactionDetail::getTransactionDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                        GstReportBreakdownDto.GstTransactionDetail::getSourceType,
                        Comparator.nullsLast(String::compareToIgnoreCase))
                    .thenComparing(
                        GstReportBreakdownDto.GstTransactionDetail::getReferenceNumber,
                        Comparator.nullsLast(String::compareToIgnoreCase))
                    .thenComparing(
                        GstReportBreakdownDto.GstTransactionDetail::getSourceId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

    return new GstReportAggregation(collected, inputTaxCredit, rateSummaries, orderedDetails);
  }

  private GstService.GstBreakdown resolveInvoiceLineBreakdown(
      Company company, Invoice invoice, InvoiceLine line) {
    BigDecimal taxAmount = safe(line.getTaxAmount());
    if (taxAmount.compareTo(BigDecimal.ZERO) <= 0) {
      return new GstService.GstBreakdown(
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          GstService.TaxType.INTER_STATE);
    }
    BigDecimal cgst = safe(line.getCgstAmount());
    BigDecimal sgst = safe(line.getSgstAmount());
    BigDecimal igst = safe(line.getIgstAmount());
    if (cgst.add(sgst).add(igst).compareTo(BigDecimal.ZERO) > 0) {
      return new GstService.GstBreakdown(
          taxableAmount(line.getTaxableAmount(), line.getLineTotal(), taxAmount),
          MoneyUtils.roundCurrency(cgst),
          MoneyUtils.roundCurrency(sgst),
          MoneyUtils.roundCurrency(igst),
          gstService.resolveTaxType(
              company.getStateCode(),
              invoice.getDealer() != null ? invoice.getDealer().getStateCode() : null,
              true));
    }
    return gstService.splitTaxAmount(
        taxableAmount(line.getTaxableAmount(), line.getLineTotal(), taxAmount),
        taxAmount,
        company.getStateCode(),
        invoice.getDealer() != null ? invoice.getDealer().getStateCode() : null);
  }

  private GstService.GstBreakdown resolvePurchaseLineBreakdown(
      Company company, RawMaterialPurchase purchase, RawMaterialPurchaseLine line) {
    BigDecimal taxAmount = safe(line.getTaxAmount());
    if (taxAmount.compareTo(BigDecimal.ZERO) <= 0) {
      return new GstService.GstBreakdown(
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          GstService.TaxType.INTER_STATE);
    }

    BigDecimal retainedRatio =
        resolveRetainedQuantityRatio(line.getQuantity(), line.getReturnedQuantity());
    if (retainedRatio.compareTo(BigDecimal.ZERO) <= 0) {
      return new GstService.GstBreakdown(
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          GstService.TaxType.INTER_STATE);
    }

    BigDecimal netTaxAmount = MoneyUtils.roundCurrency(taxAmount.multiply(retainedRatio));
    BigDecimal netTaxableAmount =
        MoneyUtils.roundCurrency(
            taxableAmount(null, line.getLineTotal(), taxAmount).multiply(retainedRatio));

    BigDecimal cgst = safe(line.getCgstAmount());
    BigDecimal sgst = safe(line.getSgstAmount());
    BigDecimal igst = safe(line.getIgstAmount());
    if (cgst.add(sgst).add(igst).compareTo(BigDecimal.ZERO) > 0) {
      return new GstService.GstBreakdown(
          netTaxableAmount,
          MoneyUtils.roundCurrency(cgst.multiply(retainedRatio)),
          MoneyUtils.roundCurrency(sgst.multiply(retainedRatio)),
          MoneyUtils.roundCurrency(igst.multiply(retainedRatio)),
          gstService.resolveTaxType(
              company.getStateCode(),
              purchase.getSupplier() != null ? purchase.getSupplier().getStateCode() : null,
              true));
    }
    return gstService.splitTaxAmount(
        netTaxableAmount,
        netTaxAmount,
        company.getStateCode(),
        purchase.getSupplier() != null ? purchase.getSupplier().getStateCode() : null);
  }

  private BigDecimal resolveRetainedQuantityRatio(
      BigDecimal quantity, BigDecimal returnedQuantity) {
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
    return retained.divide(totalQuantity, 12, java.math.RoundingMode.HALF_UP);
  }

  private BigDecimal taxableAmount(
      BigDecimal explicitTaxable, BigDecimal lineTotal, BigDecimal taxAmount) {
    if (explicitTaxable != null && explicitTaxable.compareTo(BigDecimal.ZERO) >= 0) {
      return MoneyUtils.roundCurrency(explicitTaxable);
    }
    BigDecimal fallback = safe(lineTotal).subtract(safe(taxAmount));
    if (fallback.compareTo(BigDecimal.ZERO) < 0) {
      return BigDecimal.ZERO;
    }
    return MoneyUtils.roundCurrency(fallback);
  }

  private boolean isIncludedInvoiceStatus(String status) {
    if (status == null) {
      return true;
    }
    String normalized = status.trim().toUpperCase();
    return !normalized.equals("DRAFT")
        && !normalized.equals("VOID")
        && !normalized.equals("REVERSED")
        && !normalized.equals("CANCELLED");
  }

  private boolean isIncludedPurchaseStatus(String status) {
    if (status == null) {
      return true;
    }
    String normalized = status.trim().toUpperCase();
    return !normalized.equals("DRAFT")
        && !normalized.equals("VOID")
        && !normalized.equals("REVERSED")
        && !normalized.equals("CANCELLED");
  }

  private GstReconciliationDto.GstComponentSummary componentSummary(
      BigDecimal cgst, BigDecimal sgst, BigDecimal igst) {
    BigDecimal roundedCgst = MoneyUtils.roundCurrency(cgst == null ? BigDecimal.ZERO : cgst);
    BigDecimal roundedSgst = MoneyUtils.roundCurrency(sgst == null ? BigDecimal.ZERO : sgst);
    BigDecimal roundedIgst = MoneyUtils.roundCurrency(igst == null ? BigDecimal.ZERO : igst);
    BigDecimal total = MoneyUtils.roundCurrency(roundedCgst.add(roundedSgst).add(roundedIgst));
    return new GstReconciliationDto.GstComponentSummary(
        roundedCgst, roundedSgst, roundedIgst, total);
  }

  private boolean hasTax(GstService.GstBreakdown breakdown) {
    return breakdown != null && breakdown.totalTax().compareTo(BigDecimal.ZERO) > 0;
  }

  private BigDecimal normalizeRate(BigDecimal rate) {
    return MoneyUtils.roundCurrency(rate == null ? BigDecimal.ZERO : rate);
  }

  private BigDecimal roundAmount(BigDecimal amount) {
    return MoneyUtils.roundCurrency(amount == null ? BigDecimal.ZERO : amount);
  }

  private boolean isNonGstMode(Company company) {
    BigDecimal defaultGstRate = company.getDefaultGstRate();
    return defaultGstRate != null && defaultGstRate.compareTo(BigDecimal.ZERO) == 0;
  }

  private void ensureNonGstCompanyDoesNotCarryGstAccounts(Company company) {
    List<String> configured = new ArrayList<>();
    if (company.getGstInputTaxAccountId() != null) {
      configured.add("gstInputTaxAccountId");
    }
    if (company.getGstOutputTaxAccountId() != null) {
      configured.add("gstOutputTaxAccountId");
    }
    if (company.getGstPayableAccountId() != null) {
      configured.add("gstPayableAccountId");
    }
    if (configured.isEmpty()) {
      return;
    }
    throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "Non-GST mode company cannot have GST tax accounts configured")
        .withDetail("configured", configured);
  }

  private GstReturnDto buildGstReturn(
      YearMonth period,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal outputTax,
      BigDecimal inputTax) {
    GstReturnDto dto = new GstReturnDto();
    dto.setPeriod(period);
    dto.setPeriodStart(periodStart);
    dto.setPeriodEnd(periodEnd);
    dto.setOutputTax(outputTax);
    dto.setInputTax(inputTax);
    dto.setNetPayable(MoneyUtils.roundCurrency(outputTax.subtract(inputTax)));
    return dto;
  }

  private record GstReportAggregation(
      ComponentTotals collected,
      ComponentTotals inputTaxCredit,
      List<GstReportBreakdownDto.GstRateSummary> rateSummaries,
      List<GstReportBreakdownDto.GstTransactionDetail> transactionDetails) {

    private boolean hasTaxableDocuments() {
      return (collected != null && collected.hasTax())
          || (inputTaxCredit != null && inputTaxCredit.hasTax());
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

    private GstReportBreakdownDto.GstRateSummary toSummary() {
      BigDecimal outputTax = outputCgst.add(outputSgst).add(outputIgst);
      BigDecimal inputTaxCredit = inputCgst.add(inputSgst).add(inputIgst);
      BigDecimal netTax = outputTax.subtract(inputTaxCredit);
      return new GstReportBreakdownDto.GstRateSummary(
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
          roundAmount(inputIgst));
    }

    private static BigDecimal safeAmount(BigDecimal value) {
      return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal roundAmount(BigDecimal value) {
      return MoneyUtils.roundCurrency(value == null ? BigDecimal.ZERO : value);
    }
  }

  private static final class ComponentTotals {
    private BigDecimal cgst = BigDecimal.ZERO;
    private BigDecimal sgst = BigDecimal.ZERO;
    private BigDecimal igst = BigDecimal.ZERO;

    private void add(GstService.GstBreakdown breakdown) {
      if (breakdown == null) {
        return;
      }
      cgst = cgst.add(safeAmount(breakdown.cgst()));
      sgst = sgst.add(safeAmount(breakdown.sgst()));
      igst = igst.add(safeAmount(breakdown.igst()));
    }

    private boolean hasTax() {
      return safeAmount(cgst).compareTo(BigDecimal.ZERO) > 0
          || safeAmount(sgst).compareTo(BigDecimal.ZERO) > 0
          || safeAmount(igst).compareTo(BigDecimal.ZERO) > 0;
    }

    private static BigDecimal safeAmount(BigDecimal value) {
      return value == null ? BigDecimal.ZERO : value;
    }
  }
}
