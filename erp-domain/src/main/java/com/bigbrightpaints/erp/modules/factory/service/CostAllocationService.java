package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.dto.CostAllocationRequest;
import com.bigbrightpaints.erp.modules.factory.dto.CostAllocationResponse;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;

@Service
public class CostAllocationService {

  private static final Logger log = LoggerFactory.getLogger(CostAllocationService.class);

  private final CompanyContextService companyContextService;
  private final ProductionLogRepository productionLogRepository;
  private final FinishedGoodBatchRepository finishedGoodBatchRepository;
  private final AccountingFacade accountingFacade;
  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final CompanyClock companyClock;

  @Autowired
  public CostAllocationService(
      CompanyContextService companyContextService,
      ProductionLogRepository productionLogRepository,
      FinishedGoodBatchRepository finishedGoodBatchRepository,
      AccountingFacade accountingFacade,
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyClock companyClock) {
    this.companyContextService = companyContextService;
    this.productionLogRepository = productionLogRepository;
    this.finishedGoodBatchRepository = finishedGoodBatchRepository;
    this.accountingFacade = accountingFacade;
    this.accountingLookupService = accountingLookupService;
    this.companyClock = companyClock;
  }

  @Transactional
  public CostAllocationResponse allocateCosts(CostAllocationRequest request) {
    Company company = companyContextService.requireCurrentCompany();

    // Calculate month start and end dates
    YearMonth yearMonth = YearMonth.of(request.year(), request.month());
    LocalDate startDate = yearMonth.atDay(1);
    LocalDate endDate = yearMonth.atEndOfMonth().plusDays(1);

    ZoneId zone = companyClock.zoneId(company);
    Instant startInstant = startDate.atStartOfDay(zone).toInstant();
    Instant endInstant = endDate.atStartOfDay(zone).toInstant();

    // Find all fully packed batches in this month
    List<ProductionLog> batches =
        productionLogRepository.findFullyPackedBatchesByMonth(company, startInstant, endInstant);

    BigDecimal laborCost = request.laborCost() == null ? BigDecimal.ZERO : request.laborCost();
    BigDecimal overheadCost =
        request.overheadCost() == null ? BigDecimal.ZERO : request.overheadCost();

    if (batches.isEmpty()) {
      return new CostAllocationResponse(
          request.year(),
          request.month(),
          0,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          List.of(),
          "No fully packed batches found for this period");
    }

    // Calculate total liters produced
    BigDecimal totalLitersProduced =
        batches.stream()
            .map(ProductionLog::getMixedQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    if (totalLitersProduced.compareTo(BigDecimal.ZERO) <= 0) {
      return new CostAllocationResponse(
          request.year(),
          request.month(),
          batches.size(),
          BigDecimal.ZERO,
          laborCost,
          overheadCost,
          BigDecimal.ZERO,
          List.of(),
          "Total liters produced is zero or negative");
    }

    String periodKey = String.format("%04d%02d", request.year(), request.month());
    List<ProductionLog> allocatable = new ArrayList<>();
    int skipped = 0;
    for (ProductionLog batch : batches) {
      if (batch.getProductionCode() == null) {
        continue;
      }
      Optional<String> existingRef =
          accountingFacade.findExistingCostVarianceReference(batch.getProductionCode(), periodKey);
      if (existingRef.isPresent()) {
        skipped++;
        continue;
      }
      allocatable.add(batch);
    }
    if (allocatable.isEmpty()) {
      return new CostAllocationResponse(
          request.year(),
          request.month(),
          0,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          List.of(),
          "skipped: all batches already have cost variance journals for period " + periodKey);
    }

    BigDecimal appliedLabor =
        batches.stream()
            .map(ProductionLog::getLaborCostTotal)
            .map(value -> value == null ? BigDecimal.ZERO : value)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal appliedOverhead =
        batches.stream()
            .map(ProductionLog::getOverheadCostTotal)
            .map(value -> value == null ? BigDecimal.ZERO : value)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal laborVariance = laborCost.subtract(appliedLabor);
    BigDecimal overheadVariance = overheadCost.subtract(appliedOverhead);

    if (laborVariance.compareTo(BigDecimal.ZERO) == 0
        && overheadVariance.compareTo(BigDecimal.ZERO) == 0) {
      return new CostAllocationResponse(
          request.year(),
          request.month(),
          batches.size(),
          totalLitersProduced,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          List.of(),
          "No labor/overhead variance to allocate for this period");
    }

    // Find accounting accounts
    Account finishedGoodsAccount =
        requireAccount(company, request.finishedGoodsAccountId(), AccountType.ASSET);
    Account payrollExpenseAccount =
        requireAccount(company, request.laborExpenseAccountId(), AccountType.EXPENSE);
    Account overheadExpenseAccount =
        requireAccount(company, request.overheadExpenseAccountId(), AccountType.EXPENSE);

    List<Long> journalEntryIds = new ArrayList<>();
    BigDecimal allocatedLabor = BigDecimal.ZERO;
    BigDecimal allocatedOverhead = BigDecimal.ZERO;

    allocatable =
        allocatable.stream()
            .filter(
                batch ->
                    batch.getMixedQuantity() != null
                        && batch.getMixedQuantity().compareTo(BigDecimal.ZERO) > 0)
            .toList();
    BigDecimal allocatableLiters =
        allocatable.stream()
            .map(ProductionLog::getMixedQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (allocatableLiters.compareTo(BigDecimal.ZERO) <= 0) {
      return new CostAllocationResponse(
          request.year(),
          request.month(),
          0,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          List.of(),
          "No allocatable liters found for cost variance allocation");
    }
    BigDecimal laborVarianceToAllocate = laborVariance;
    BigDecimal overheadVarianceToAllocate = overheadVariance;
    BigDecimal totalVarianceToAllocate = laborVarianceToAllocate.add(overheadVarianceToAllocate);
    BigDecimal variancePerLiter =
        totalVarianceToAllocate.divide(allocatableLiters, 4, RoundingMode.HALF_UP);

    // Allocate variance to each batch
    for (int i = 0; i < allocatable.size(); i++) {
      ProductionLog batch = allocatable.get(i);
      boolean isLast = i == allocatable.size() - 1;
      BigDecimal batchLiters = batch.getMixedQuantity();
      BigDecimal batchLaborVariance =
          isLast
              ? laborVarianceToAllocate.subtract(allocatedLabor)
              : batchLiters
                  .multiply(laborVarianceToAllocate)
                  .divide(allocatableLiters, 4, RoundingMode.HALF_UP);
      BigDecimal batchOverheadVariance =
          isLast
              ? overheadVarianceToAllocate.subtract(allocatedOverhead)
              : batchLiters
                  .multiply(overheadVarianceToAllocate)
                  .divide(allocatableLiters, 4, RoundingMode.HALF_UP);
      allocatedLabor = allocatedLabor.add(batchLaborVariance);
      allocatedOverhead = allocatedOverhead.add(batchOverheadVariance);

      // Update production log
      BigDecimal updatedLabor =
          (batch.getLaborCostTotal() == null ? BigDecimal.ZERO : batch.getLaborCostTotal())
              .add(batchLaborVariance);
      BigDecimal updatedOverhead =
          (batch.getOverheadCostTotal() == null ? BigDecimal.ZERO : batch.getOverheadCostTotal())
              .add(batchOverheadVariance);
      batch.setLaborCostTotal(updatedLabor);
      batch.setOverheadCostTotal(updatedOverhead);

      // Recalculate unit cost (material + labor + overhead) / packed quantity
      BigDecimal totalBatchCost =
          (batch.getMaterialCostTotal() == null ? BigDecimal.ZERO : batch.getMaterialCostTotal())
              .add(updatedLabor)
              .add(updatedOverhead);

      BigDecimal packedQty =
          batch.getTotalPackedQuantity() == null ? BigDecimal.ZERO : batch.getTotalPackedQuantity();
      BigDecimal newUnitCost = null;
      if (packedQty.compareTo(BigDecimal.ZERO) > 0) {
        newUnitCost = totalBatchCost.divide(packedQty, 4, RoundingMode.HALF_UP);
        batch.setUnitCost(newUnitCost);
      }

      productionLogRepository.save(batch);
      if (newUnitCost != null) {
        updateFinishedGoodBatchCosts(batch, newUnitCost);
      }

      BigDecimal journalLabor = batchLaborVariance.setScale(2, RoundingMode.HALF_UP);
      BigDecimal journalOverhead = batchOverheadVariance.setScale(2, RoundingMode.HALF_UP);
      if (journalLabor.compareTo(BigDecimal.ZERO) != 0
          || journalOverhead.compareTo(BigDecimal.ZERO) != 0) {
        JournalEntryDto journalEntry =
            accountingFacade.postCostVarianceAllocation(
                batch.getProductionCode(),
                periodKey,
                yearMonth.atEndOfMonth(),
                finishedGoodsAccount.getId(),
                payrollExpenseAccount.getId(),
                overheadExpenseAccount.getId(),
                journalLabor,
                journalOverhead,
                request.notes() != null ? request.notes() : "Cost variance allocation");

        if (journalEntry != null) {
          journalEntryIds.add(journalEntry.id());
        }
      }
    }

    String summary =
        String.format(
            "Allocated variance labor=%s overhead=%s across %d batches (%.2f liters); skipped %d",
            laborVarianceToAllocate,
            overheadVarianceToAllocate,
            allocatable.size(),
            allocatableLiters,
            skipped);

    return new CostAllocationResponse(
        request.year(),
        request.month(),
        allocatable.size(),
        allocatableLiters,
        laborVarianceToAllocate,
        overheadVarianceToAllocate,
        variancePerLiter,
        journalEntryIds,
        summary);
  }

  private Account requireAccount(Company company, Long accountId, AccountType expectedType) {
    Account account = accountingLookupService.requireAccount(company, accountId);
    if (account.getType() != expectedType) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Account " + account.getCode() + " is not of type " + expectedType);
    }
    return account;
  }

  private void updateFinishedGoodBatchCosts(ProductionLog log, BigDecimal baseUnitCost) {
    if (log.getPackingRecords() == null || log.getPackingRecords().isEmpty()) {
      return;
    }
    Set<Long> updatedBatchIds = new HashSet<>();
    log.getPackingRecords()
        .forEach(
            record -> {
              FinishedGoodBatch fgBatch = record.getFinishedGoodBatch();
              if (fgBatch == null || fgBatch.getId() == null) {
                return;
              }
              if (!updatedBatchIds.add(fgBatch.getId())) {
                return;
              }
              BigDecimal qtyPacked =
                  record.getQuantityPacked() == null ? BigDecimal.ZERO : record.getQuantityPacked();
              BigDecimal packagingCost =
                  record.getPackagingCost() == null ? BigDecimal.ZERO : record.getPackagingCost();
              BigDecimal packagingCostPerUnit = BigDecimal.ZERO;
              if (qtyPacked.compareTo(BigDecimal.ZERO) > 0
                  && packagingCost.compareTo(BigDecimal.ZERO) > 0) {
                packagingCostPerUnit = packagingCost.divide(qtyPacked, 4, RoundingMode.HALF_UP);
              }
              BigDecimal updatedUnitCost =
                  baseUnitCost.add(packagingCostPerUnit).setScale(4, RoundingMode.HALF_UP);
              fgBatch.setUnitCost(updatedUnitCost);
              finishedGoodBatchRepository.save(fgBatch);
            });
  }
}
