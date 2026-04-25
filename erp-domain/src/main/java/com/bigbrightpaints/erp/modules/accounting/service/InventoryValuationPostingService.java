package com.bigbrightpaints.erp.modules.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.InventoryRevaluationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.LandedCostRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.WipAdjustmentRequest;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.service.CompanyScopedFactoryLookupService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.service.CompanyScopedPurchasingLookupService;

@Service
class InventoryValuationPostingService {

  private final CompanyContextService companyContextService;
  private final CompanyScopedPurchasingLookupService purchasingLookupService;
  private final CompanyScopedFactoryLookupService factoryLookupService;
  private final AccountResolutionService accountResolutionService;
  private final JournalReferenceService journalReferenceService;
  private final JournalEntryService journalEntryService;
  private final JournalEntryRepository journalEntryRepository;
  private final FinishedGoodBatchRepository finishedGoodBatchRepository;
  private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  private final RawMaterialBatchRepository rawMaterialBatchRepository;
  private final RawMaterialMovementRepository rawMaterialMovementRepository;

  InventoryValuationPostingService(
      CompanyContextService companyContextService,
      CompanyScopedPurchasingLookupService purchasingLookupService,
      CompanyScopedFactoryLookupService factoryLookupService,
      AccountResolutionService accountResolutionService,
      JournalReferenceService journalReferenceService,
      JournalEntryService journalEntryService,
      JournalEntryRepository journalEntryRepository,
      FinishedGoodBatchRepository finishedGoodBatchRepository,
      RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      RawMaterialMovementRepository rawMaterialMovementRepository) {
    this.companyContextService = companyContextService;
    this.purchasingLookupService = purchasingLookupService;
    this.factoryLookupService = factoryLookupService;
    this.accountResolutionService = accountResolutionService;
    this.journalReferenceService = journalReferenceService;
    this.journalEntryService = journalEntryService;
    this.journalEntryRepository = journalEntryRepository;
    this.finishedGoodBatchRepository = finishedGoodBatchRepository;
    this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
    this.rawMaterialBatchRepository = rawMaterialBatchRepository;
    this.rawMaterialMovementRepository = rawMaterialMovementRepository;
  }

  @Transactional
  JournalEntryDto recordLandedCost(LandedCostRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    RawMaterialPurchase purchase =
        purchasingLookupService.requireRawMaterialPurchase(
            company, request.rawMaterialPurchaseId());
    String reference =
        journalReferenceService.resolveJournalReference(
            company,
            StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey()
                : request.referenceNumber());
    boolean replay = journalAlreadyExists(company, reference);
    LocalDate entryDate =
        request.entryDate() != null
            ? request.entryDate()
            : accountResolutionService.currentDate(company);
    String memo =
        StringUtils.hasText(request.memo())
            ? request.memo().trim()
            : "Landed cost for purchase " + purchase.getInvoiceNumber();
    JournalEntryDto journalEntry =
        journalEntryService.createJournalEntry(
            new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                null,
                request.adminOverride(),
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        request.inventoryAccountId(), memo, request.amount(), BigDecimal.ZERO),
                    new JournalEntryRequest.JournalLineRequest(
                        request.offsetAccountId(), memo, BigDecimal.ZERO, request.amount()))));
    if (!replay) {
      adjustLandedCostValuation(purchase, request.amount());
    }
    return journalEntry;
  }

  @Transactional
  JournalEntryDto revalueInventory(InventoryRevaluationRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    String reference =
        journalReferenceService.resolveJournalReference(
            company,
            StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey()
                : request.referenceNumber());
    boolean replay = journalAlreadyExists(company, reference);
    LocalDate entryDate =
        request.entryDate() != null
            ? request.entryDate()
            : accountResolutionService.currentDate(company);
    String memo =
        StringUtils.hasText(request.memo()) ? request.memo().trim() : "Inventory revaluation";
    BigDecimal delta = request.deltaAmount();
    BigDecimal debit = delta.compareTo(BigDecimal.ZERO) >= 0 ? delta : BigDecimal.ZERO;
    BigDecimal credit = delta.compareTo(BigDecimal.ZERO) < 0 ? delta.abs() : BigDecimal.ZERO;
    JournalEntryDto journalEntry =
        journalEntryService.createJournalEntry(
            new JournalEntryRequest(
                reference,
                entryDate,
                memo,
                null,
                null,
                request.adminOverride(),
                List.of(
                    new JournalEntryRequest.JournalLineRequest(
                        request.inventoryAccountId(), memo, debit, credit),
                    new JournalEntryRequest.JournalLineRequest(
                        request.revaluationAccountId(), memo, credit, debit))));
    if (!replay) {
      revalueFinishedBatches(
          finishedGoodBatchRepository.findByCompanyAndValuationAccountId(
              company, request.inventoryAccountId()),
          delta);
    }
    return journalEntry;
  }

  @Transactional
  JournalEntryDto adjustWip(WipAdjustmentRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    if (request.productionLogId() == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD, "productionLogId is required");
    }
    factoryLookupService.requireProductionLog(company, request.productionLogId());
    String reference =
        journalReferenceService.resolveJournalReference(
            company,
            StringUtils.hasText(request.idempotencyKey())
                ? request.idempotencyKey()
                : request.referenceNumber());
    LocalDate entryDate =
        request.entryDate() != null
            ? request.entryDate()
            : accountResolutionService.currentDate(company);
    String memo = StringUtils.hasText(request.memo()) ? request.memo().trim() : "WIP adjustment";
    JournalEntryRequest.JournalLineRequest line1;
    JournalEntryRequest.JournalLineRequest line2;
    if (request.direction() == WipAdjustmentRequest.Direction.ISSUE) {
      line1 =
          new JournalEntryRequest.JournalLineRequest(
              request.wipAccountId(), memo, request.amount(), BigDecimal.ZERO);
      line2 =
          new JournalEntryRequest.JournalLineRequest(
              request.inventoryAccountId(), memo, BigDecimal.ZERO, request.amount());
    } else {
      line1 =
          new JournalEntryRequest.JournalLineRequest(
              request.inventoryAccountId(), memo, request.amount(), BigDecimal.ZERO);
      line2 =
          new JournalEntryRequest.JournalLineRequest(
              request.wipAccountId(), memo, BigDecimal.ZERO, request.amount());
    }
    return journalEntryService.createJournalEntry(
        new JournalEntryRequest(
            reference,
            entryDate,
            memo,
            null,
            null,
            request.adminOverride(),
            List.of(line1, line2)));
  }

  private void adjustLandedCostValuation(RawMaterialPurchase purchase, BigDecimal landedAmount) {
    if (landedAmount == null || landedAmount.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    List<RawMaterialPurchaseLine> lines = purchase.getLines();
    BigDecimal totalValue =
        lines.stream()
            .map(RawMaterialPurchaseLine::getLineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (totalValue.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    for (RawMaterialPurchaseLine line : lines) {
      BigDecimal qty = line.getQuantity() == null ? BigDecimal.ZERO : line.getQuantity();
      if (qty.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      BigDecimal allocation =
          landedAmount
              .multiply(line.getLineTotal().divide(totalValue, 8, RoundingMode.HALF_UP))
              .setScale(4, RoundingMode.HALF_UP);
      BigDecimal deltaPerUnit = allocation.divide(qty, 6, RoundingMode.HALF_UP);
      line.setCostPerUnit(line.getCostPerUnit().add(deltaPerUnit));
      line.setLineTotal(line.getLineTotal().add(allocation));
      if (line.getRawMaterialBatch() != null) {
        line.getRawMaterialBatch()
            .setCostPerUnit(line.getRawMaterialBatch().getCostPerUnit().add(deltaPerUnit));
        rawMaterialBatchRepository.save(line.getRawMaterialBatch());
        rawMaterialMovementRepository.findByRawMaterialBatch(line.getRawMaterialBatch()).stream()
            .filter(movement -> "RECEIPT".equalsIgnoreCase(movement.getMovementType()))
            .forEach(
                movement -> {
                  movement.setUnitCost(movement.getUnitCost().add(deltaPerUnit));
                  rawMaterialMovementRepository.save(movement);
                });
      }
    }
    rawMaterialPurchaseRepository.save(purchase);
  }

  private void revalueFinishedBatches(List<FinishedGoodBatch> batches, BigDecimal delta) {
    BigDecimal totalQty =
        batches.stream()
            .map(
                batch ->
                    batch.getQuantityTotal() == null ? BigDecimal.ZERO : batch.getQuantityTotal())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    BigDecimal deltaPerUnit = delta.divide(totalQty, 6, RoundingMode.HALF_UP);
    for (FinishedGoodBatch batch : batches) {
      BigDecimal qty =
          batch.getQuantityTotal() == null ? BigDecimal.ZERO : batch.getQuantityTotal();
      if (qty.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      batch.setUnitCost(batch.getUnitCost().add(deltaPerUnit));
      finishedGoodBatchRepository.save(batch);
    }
  }

  private boolean journalAlreadyExists(Company company, String reference) {
    if (company == null || !StringUtils.hasText(reference)) {
      return false;
    }
    return journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).isPresent();
  }
}
