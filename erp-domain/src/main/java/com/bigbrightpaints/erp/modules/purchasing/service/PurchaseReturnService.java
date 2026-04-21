package com.bigbrightpaints.erp.modules.purchasing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.accounting.service.JournalCorrectionMetadataService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.service.CompanyScopedInventoryLookupService;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnPreviewDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;

import jakarta.transaction.Transactional;

@Service
public class PurchaseReturnService {
  private static final int MAX_JOURNAL_REFERENCE_LENGTH = 64;

  private final CompanyContextService companyContextService;
  private final RawMaterialPurchaseRepository purchaseRepository;
  private final RawMaterialRepository rawMaterialRepository;
  private final RawMaterialBatchRepository rawMaterialBatchRepository;
  private final RawMaterialMovementRepository movementRepository;
  private final AccountingFacade accountingFacade;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalCorrectionMetadataService journalCorrectionMetadataService;
  private final CompanyScopedPurchasingLookupService purchasingLookupService;
  private final CompanyScopedInventoryLookupService inventoryLookupService;
  private final ReferenceNumberService referenceNumberService;
  private final CompanyClock companyClock;
  private final GstService gstService;
  private final PurchaseReturnAllocationService allocationService;
  private final IdempotencyReservationService idempotencyReservationService =
      new IdempotencyReservationService();

  public PurchaseReturnService(
      CompanyContextService companyContextService,
      RawMaterialPurchaseRepository purchaseRepository,
      RawMaterialRepository rawMaterialRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      RawMaterialMovementRepository movementRepository,
      AccountingFacade accountingFacade,
      JournalEntryRepository journalEntryRepository,
      JournalCorrectionMetadataService journalCorrectionMetadataService,
      CompanyScopedPurchasingLookupService purchasingLookupService,
      CompanyScopedInventoryLookupService inventoryLookupService,
      ReferenceNumberService referenceNumberService,
      CompanyClock companyClock,
      GstService gstService,
      PurchaseReturnAllocationService allocationService) {
    this.companyContextService = companyContextService;
    this.purchaseRepository = purchaseRepository;
    this.rawMaterialRepository = rawMaterialRepository;
    this.rawMaterialBatchRepository = rawMaterialBatchRepository;
    this.movementRepository = movementRepository;
    this.accountingFacade = accountingFacade;
    this.journalEntryRepository = journalEntryRepository;
    this.journalCorrectionMetadataService = journalCorrectionMetadataService;
    this.purchasingLookupService = purchasingLookupService;
    this.inventoryLookupService = inventoryLookupService;
    this.referenceNumberService = referenceNumberService;
    this.companyClock = companyClock;
    this.gstService = gstService;
    this.allocationService = allocationService;
  }

  @Transactional
  public PurchaseReturnPreviewDto previewPurchaseReturn(PurchaseReturnRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier = purchasingLookupService.requireSupplier(company, request.supplierId());
    supplier.requireTransactionalUsage("preview purchase returns");
    RawMaterialPurchase purchase =
        purchaseRepository
            .lockByCompanyAndId(company, request.purchaseId())
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Raw material purchase not found"));
    ensurePostedPurchase(purchase);
    if (purchase.getSupplier() == null
        || !purchase.getSupplier().getId().equals(supplier.getId())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Purchase does not belong to the supplier");
    }
    RawMaterial material = requireActiveMaterial(company, request.rawMaterialId());
    boolean materialInPurchase =
        purchase.getLines().stream()
            .anyMatch(
                line ->
                    line.getRawMaterial() != null
                        && line.getRawMaterial().getId().equals(material.getId()));
    if (!materialInPurchase) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Purchase does not include raw material " + material.getName());
    }
    BigDecimal quantity = positive(request.quantity(), "quantity");
    BigDecimal unitCost = positive(request.unitCost(), "unitCost");
    BigDecimal remainingReturnableQty =
        allocationService.remainingReturnableQuantity(purchase, material);
    if (quantity.compareTo(remainingReturnableQty) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Purchase return quantity exceeds remaining returnable quantity")
          .withDetail("purchaseId", purchase.getId())
          .withDetail("rawMaterialId", material.getId())
          .withDetail("remainingReturnableQuantity", remainingReturnableQty)
          .withDetail("requestedQuantity", quantity);
    }
    BigDecimal lineNet = currency(MoneyUtils.safeMultiply(quantity, unitCost));
    BigDecimal taxAmount = computeReturnTax(purchase, material, quantity);
    String reference =
        StringUtils.hasText(request.referenceNumber())
            ? request.referenceNumber().trim()
            : referenceNumberService.purchaseReturnReference(company, supplier);
    LocalDate returnDate =
        request.returnDate() != null ? request.returnDate() : companyClock.today(company);
    return new PurchaseReturnPreviewDto(
        purchase.getId(),
        purchase.getInvoiceNumber(),
        material.getId(),
        material.getName(),
        quantity,
        remainingReturnableQty.subtract(quantity).max(BigDecimal.ZERO),
        lineNet,
        taxAmount,
        currency(lineNet.add(taxAmount)),
        returnDate,
        reference);
  }

  @Transactional
  public JournalEntryDto recordPurchaseReturn(PurchaseReturnRequest request) {
    return recordPurchaseReturn(request, null);
  }

  @Transactional
  public JournalEntryDto recordPurchaseReturn(
      PurchaseReturnRequest request, String idempotencyKey) {
    Company company = companyContextService.requireCurrentCompany();
    Supplier supplier = purchasingLookupService.requireSupplier(company, request.supplierId());
    RawMaterialPurchase purchase =
        purchaseRepository
            .lockByCompanyAndId(company, request.purchaseId())
            .orElseThrow(
                () ->
                    com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                        "Raw material purchase not found"));
    if (purchase.getSupplier() == null
        || !purchase.getSupplier().getId().equals(supplier.getId())) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Purchase does not belong to the supplier");
    }
    RawMaterial material = requireActiveMaterial(company, request.rawMaterialId());
    boolean materialInPurchase =
        purchase.getLines().stream()
            .anyMatch(
                line ->
                    line.getRawMaterial() != null
                        && line.getRawMaterial().getId().equals(material.getId()));
    if (!materialInPurchase) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Purchase does not include raw material " + material.getName());
    }
    if (material.getInventoryAccountId() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Raw material " + material.getName() + " is missing an inventory account mapping");
    }
    BigDecimal quantity = positive(request.quantity(), "quantity");
    BigDecimal unitCost = positive(request.unitCost(), "unitCost");
    BigDecimal lineNet = currency(MoneyUtils.safeMultiply(quantity, unitCost));
    BigDecimal taxAmount = computeReturnTax(purchase, material, quantity);
    BigDecimal totalAmount = currency(lineNet.add(taxAmount));
    String memo = returnMemo(material, supplier, request.reason());
    String reference = resolveReturnReference(company, supplier, request, idempotencyKey);
    LocalDate returnDate =
        request.returnDate() != null ? request.returnDate() : companyClock.today(company);

    List<RawMaterialMovement> existingMovements =
        movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, InventoryReference.PURCHASE_RETURN, reference);
    if (!existingMovements.isEmpty()) {
      return returnExistingPurchaseReturn(
          purchase,
          material,
          supplier,
          quantity,
          unitCost,
          reference,
          returnDate,
          memo,
          existingMovements);
    }

    ensurePostedPurchase(purchase);
    supplier.requireTransactionalUsage("post purchase returns");
    if (supplier.getPayableAccount() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Supplier " + supplier.getName() + " is missing a payable account");
    }

    BigDecimal remainingReturnableQty =
        allocationService.remainingReturnableQuantity(purchase, material);
    if (remainingReturnableQty.compareTo(BigDecimal.ZERO) <= 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "All purchased quantity has already been returned for this material")
          .withDetail("purchaseId", purchase.getId())
          .withDetail("rawMaterialId", material.getId());
    }
    if (quantity.compareTo(remainingReturnableQty) > 0) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "Purchase return quantity exceeds remaining returnable quantity")
          .withDetail("purchaseId", purchase.getId())
          .withDetail("rawMaterialId", material.getId())
          .withDetail("remainingReturnableQuantity", remainingReturnableQty)
          .withDetail("requestedQuantity", quantity);
    }

    Map<Long, BigDecimal> taxCredits = null;
    JournalCreationRequest.GstBreakdown gstBreakdown = null;
    if (taxAmount.compareTo(BigDecimal.ZERO) > 0) {
      taxCredits = new HashMap<>();
      taxCredits.put(null, taxAmount);
      String companyStateCode = resolveCompanyStateCode(company);
      String supplierStateCode = resolveSupplierStateCode(supplier);
      if (!StringUtils.hasText(supplier.getStateCode()) && StringUtils.hasText(supplierStateCode)) {
        supplier.setStateCode(supplierStateCode);
      }
      GstService.GstBreakdown split =
          splitTaxAmountSafe(lineNet, taxAmount, companyStateCode, supplierStateCode);
      gstBreakdown =
          new JournalCreationRequest.GstBreakdown(
              lineNet, split.cgst(), split.sgst(), split.igst());
    }
    JournalEntryDto entry =
        accountingFacade.postPurchaseReturn(
            supplier.getId(),
            reference,
            returnDate,
            memo,
            Map.of(material.getInventoryAccountId(), lineNet),
            taxCredits,
            gstBreakdown,
            totalAmount);
    ensureLinkedCorrectionJournal(
        companyContextService.requireCurrentCompany(),
        entry,
        purchase.getJournalEntry(),
        purchase.getInvoiceNumber());

    int updated = rawMaterialRepository.deductStockIfSufficient(material.getId(), quantity);
    if (updated == 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Cannot return more than on-hand inventory for " + material.getName());
    }

    List<RawMaterialMovement> movements =
        issueReturnFromBatches(material, quantity, unitCost, reference, entry.id());
    movementRepository.saveAll(movements);
    allocationService.applyPurchaseReturnQuantity(purchase, material, quantity);
    allocationService.applyPurchaseReturnToOutstanding(purchase, totalAmount);
    return entry;
  }

  private JournalEntryDto returnExistingPurchaseReturn(
      RawMaterialPurchase purchase,
      RawMaterial material,
      Supplier supplier,
      BigDecimal quantity,
      BigDecimal unitCost,
      String reference,
      LocalDate returnDate,
      String memo,
      List<RawMaterialMovement> existingMovements) {
    validateReturnReplay(material, quantity, unitCost, reference, existingMovements);
    validateReplayPurchaseReturnProvenance(purchase, reference, existingMovements);
    BigDecimal lineNet = currency(MoneyUtils.safeMultiply(quantity, unitCost));
    BigDecimal taxAmount = computeReturnTax(purchase, material, quantity);
    BigDecimal totalAmount = currency(lineNet.add(taxAmount));
    Map<Long, BigDecimal> taxCredits = null;
    JournalCreationRequest.GstBreakdown gstBreakdown = null;
    if (taxAmount.compareTo(BigDecimal.ZERO) > 0) {
      taxCredits = new HashMap<>();
      taxCredits.put(null, taxAmount);
      Company company = companyContextService.requireCurrentCompany();
      String companyStateCode = resolveCompanyStateCode(company);
      String supplierStateCode = resolveSupplierStateCode(supplier);
      if (!StringUtils.hasText(supplier.getStateCode()) && StringUtils.hasText(supplierStateCode)) {
        supplier.setStateCode(supplierStateCode);
      }
      GstService.GstBreakdown split =
          splitTaxAmountSafe(lineNet, taxAmount, companyStateCode, supplierStateCode);
      gstBreakdown =
          new JournalCreationRequest.GstBreakdown(
              lineNet, split.cgst(), split.sgst(), split.igst());
    }
    JournalEntryDto entry =
        accountingFacade.postPurchaseReturn(
            supplier.getId(),
            reference,
            returnDate,
            memo,
            Map.of(material.getInventoryAccountId(), lineNet),
            taxCredits,
            gstBreakdown,
            totalAmount);
    ensureLinkedCorrectionJournal(
        companyContextService.requireCurrentCompany(),
        entry,
        purchase.getJournalEntry(),
        purchase.getInvoiceNumber());
    if (entry != null) {
      Long entryId = entry.id();
      boolean needsLink =
          existingMovements.stream()
              .anyMatch(
                  movement ->
                      movement.getJournalEntryId() == null
                          || !movement.getJournalEntryId().equals(entryId));
      if (needsLink) {
        existingMovements.forEach(movement -> movement.setJournalEntryId(entryId));
        movementRepository.saveAll(existingMovements);
      }
    }
    return entry;
  }

  private void ensurePostedPurchase(RawMaterialPurchase purchase) {
    if (purchase == null) {
      return;
    }
    if (purchase.getJournalEntry() == null || purchase.getJournalEntry().getId() == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Only posted purchases can be corrected through purchase return");
    }
    String status = purchase.getStatus();
    if (!StringUtils.hasText(status)
        || "DRAFT".equalsIgnoreCase(status)
        || "VOID".equalsIgnoreCase(status)
        || "REVERSED".equalsIgnoreCase(status)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Only posted purchases can be corrected through purchase return");
    }
  }

  private void ensureLinkedCorrectionJournal(
      Company company,
      JournalEntryDto entryDto,
      JournalEntry sourceEntry,
      String purchaseInvoiceNumber) {
    if (entryDto == null
        || entryDto.id() == null
        || sourceEntry == null
        || sourceEntry.getId() == null) {
      return;
    }
    journalCorrectionMetadataService
        .findByCompanyAndId(company, entryDto.id())
        .ifPresent(
            entry -> {
              boolean bootstrapCandidate =
                  canCanonicalizeFreshPurchaseReturnJournal(
                      entry, entryDto.referenceNumber(), purchaseInvoiceNumber);
              if (!bootstrapCandidate) {
                validateReplayCorrectionJournal(entry, sourceEntry, purchaseInvoiceNumber);
              }
              journalCorrectionMetadataService.syncReversalMetadata(
                  entry, "PURCHASE_RETURN", "PURCHASING_RETURN", purchaseInvoiceNumber);
            });
  }

  private boolean canCanonicalizeFreshPurchaseReturnJournal(
      JournalEntry entry, String returnReference, String purchaseInvoiceNumber) {
    if (entry == null || !StringUtils.hasText(purchaseInvoiceNumber)) {
      return false;
    }
    if (entry.getCorrectionType() != null
        && entry.getCorrectionType() != JournalCorrectionType.REVERSAL) {
      return false;
    }
    if (StringUtils.hasText(entry.getCorrectionReason())
        && !"PURCHASE_RETURN".equalsIgnoreCase(entry.getCorrectionReason())) {
      return false;
    }
    if (StringUtils.hasText(entry.getSourceModule())
        && !"PURCHASING_RETURN".equalsIgnoreCase(entry.getSourceModule())) {
      return false;
    }
    if (!StringUtils.hasText(entry.getSourceReference())) {
      return true;
    }
    if (Objects.equals(entry.getSourceReference(), purchaseInvoiceNumber)) {
      return true;
    }
    return StringUtils.hasText(returnReference)
        && Objects.equals(entry.getSourceReference(), returnReference);
  }

  private void validateReplayPurchaseReturnProvenance(
      RawMaterialPurchase purchase, String reference, List<RawMaterialMovement> existingMovements) {
    if (purchase == null || existingMovements == null || existingMovements.isEmpty()) {
      return;
    }
    String expectedSourceReference = purchase.getInvoiceNumber();
    JournalEntry sourceEntry = purchase.getJournalEntry();
    for (RawMaterialMovement movement : existingMovements) {
      Long journalEntryId = movement.getJournalEntryId();
      if (journalEntryId == null) {
        continue;
      }
      JournalEntry entry =
          journalEntryRepository
              .findByCompanyAndId(companyContextService.requireCurrentCompany(), journalEntryId)
              .orElse(null);
      if (entry == null) {
        continue;
      }
      try {
        validateReplayCorrectionJournal(entry, sourceEntry, expectedSourceReference, reference);
      } catch (ApplicationException ex) {
        throw new ApplicationException(
                ErrorCode.CONCURRENCY_CONFLICT,
                "Purchase return reference already used for another purchase")
            .withDetail("reference", reference)
            .withDetail("journalEntryId", journalEntryId)
            .withDetail("purchaseInvoiceNumber", expectedSourceReference);
      }
    }
  }

  private void validateReplayCorrectionJournal(
      JournalEntry entry, JournalEntry sourceEntry, String purchaseInvoiceNumber) {
    validateReplayCorrectionJournal(entry, sourceEntry, purchaseInvoiceNumber, null);
  }

  private void validateReplayCorrectionJournal(
      JournalEntry entry,
      JournalEntry sourceEntry,
      String purchaseInvoiceNumber,
      String returnReference) {
    if (entry == null) {
      return;
    }
    if (entry.getReversalOf() != null
        && sourceEntry != null
        && sourceEntry.getId() != null
        && !Objects.equals(entry.getReversalOf().getId(), sourceEntry.getId())) {
      throw new ApplicationException(
          ErrorCode.CONCURRENCY_CONFLICT,
          "Purchase return reference already used for another purchase");
    }
    if (StringUtils.hasText(entry.getCorrectionReason())
        && !"PURCHASE_RETURN".equalsIgnoreCase(entry.getCorrectionReason())) {
      throw new ApplicationException(
          ErrorCode.CONCURRENCY_CONFLICT,
          "Purchase return reference already used for another correction flow");
    }
    if (StringUtils.hasText(entry.getSourceModule())
        && !"PURCHASING_RETURN".equalsIgnoreCase(entry.getSourceModule())) {
      throw new ApplicationException(
          ErrorCode.CONCURRENCY_CONFLICT,
          "Purchase return reference already used for another correction flow");
    }
    if (StringUtils.hasText(entry.getSourceReference())
        && !Objects.equals(entry.getSourceReference(), purchaseInvoiceNumber)
        && !(StringUtils.hasText(returnReference)
            && Objects.equals(entry.getSourceReference(), returnReference))) {
      throw new ApplicationException(
          ErrorCode.CONCURRENCY_CONFLICT,
          "Purchase return reference already used for another purchase");
    }
  }

  private void validateReturnReplay(
      RawMaterial material,
      BigDecimal quantity,
      BigDecimal unitCost,
      String reference,
      List<RawMaterialMovement> existingMovements) {
    List<Long> materialIds =
        existingMovements.stream()
            .map(movement -> movement.getRawMaterial().getId())
            .distinct()
            .toList();
    if (materialIds.size() != 1 || !materialIds.get(0).equals(material.getId())) {
      throwIdempotencyConflict(material, quantity, unitCost, reference);
    }
    BigDecimal existingQty =
        existingMovements.stream()
            .map(RawMaterialMovement::getQuantity)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (existingQty.compareTo(quantity) != 0) {
      throwIdempotencyConflict(material, quantity, unitCost, reference);
    }
    BigDecimal expectedCost = unitCost != null ? unitCost : BigDecimal.ZERO;
    boolean unitCostMismatch =
        existingMovements.stream()
            .map(RawMaterialMovement::getUnitCost)
            .filter(Objects::nonNull)
            .anyMatch(cost -> cost.compareTo(expectedCost) != 0);
    if (unitCostMismatch) {
      throwIdempotencyConflict(material, quantity, unitCost, reference);
    }
  }

  private void throwIdempotencyConflict(
      RawMaterial material, BigDecimal quantity, BigDecimal unitCost, String reference) {
    throw new ApplicationException(
            ErrorCode.CONCURRENCY_CONFLICT,
            "Purchase return reference already used with different payload")
        .withDetail("reference", reference)
        .withDetail("rawMaterialId", material.getId())
        .withDetail("quantity", quantity)
        .withDetail("unitCost", unitCost);
  }

  private List<RawMaterialMovement> issueReturnFromBatches(
      RawMaterial material,
      BigDecimal quantity,
      BigDecimal unitCost,
      String reference,
      Long journalEntryId) {
    List<RawMaterialBatch> batches = rawMaterialBatchRepository.findAvailableBatchesFIFO(material);
    BigDecimal remaining = quantity;
    List<RawMaterialMovement> movements = new ArrayList<>();

    for (RawMaterialBatch batch : batches) {
      if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
        break;
      }
      BigDecimal available = batch.getQuantity() != null ? batch.getQuantity() : BigDecimal.ZERO;
      if (available.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      BigDecimal take = available.min(remaining);
      if (take.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }

      int updated = rawMaterialBatchRepository.deductQuantityIfSufficient(batch.getId(), take);
      if (updated == 0) {
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
            "Concurrent modification detected or insufficient quantity for batch "
                + batch.getBatchCode());
      }

      RawMaterialMovement movement = new RawMaterialMovement();
      movement.setRawMaterial(material);
      movement.setRawMaterialBatch(batch);
      movement.setReferenceType(InventoryReference.PURCHASE_RETURN);
      movement.setReferenceId(reference);
      movement.setMovementType("RETURN");
      movement.setQuantity(take);
      movement.setUnitCost(unitCost);
      movement.setJournalEntryId(journalEntryId);
      movements.add(movement);

      remaining = remaining.subtract(take);
    }

    if (remaining.compareTo(BigDecimal.ZERO) > 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Insufficient batch availability for " + material.getName());
    }
    return movements;
  }

  private BigDecimal computeReturnTax(
      RawMaterialPurchase purchase, RawMaterial material, BigDecimal returnQuantity) {
    if (purchase == null || material == null || returnQuantity == null) {
      return currency(BigDecimal.ZERO);
    }
    BigDecimal purchaseTax = MoneyUtils.zeroIfNull(purchase.getTaxAmount());
    if (purchaseTax.compareTo(BigDecimal.ZERO) <= 0) {
      return currency(BigDecimal.ZERO);
    }
    BigDecimal materialLineTotal =
        purchase.getLines().stream()
            .filter(
                line ->
                    line.getRawMaterial() != null
                        && line.getRawMaterial().getId().equals(material.getId()))
            .map(RawMaterialPurchaseLine::getLineTotal)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal materialLineQty =
        purchase.getLines().stream()
            .filter(
                line ->
                    line.getRawMaterial() != null
                        && line.getRawMaterial().getId().equals(material.getId()))
            .map(RawMaterialPurchaseLine::getQuantity)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (materialLineTotal.compareTo(BigDecimal.ZERO) <= 0
        || materialLineQty.compareTo(BigDecimal.ZERO) <= 0) {
      return currency(BigDecimal.ZERO);
    }
    boolean hasCompleteLineTaxData =
        purchase.getLines().stream()
            .map(RawMaterialPurchaseLine::getTaxAmount)
            .allMatch(Objects::nonNull);
    if (hasCompleteLineTaxData) {
      BigDecimal materialLineTax =
          purchase.getLines().stream()
              .filter(
                  line ->
                      line.getRawMaterial() != null
                          && line.getRawMaterial().getId().equals(material.getId()))
              .map(RawMaterialPurchaseLine::getTaxAmount)
              .filter(Objects::nonNull)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      if (materialLineTax.compareTo(BigDecimal.ZERO) <= 0) {
        return currency(BigDecimal.ZERO);
      }
      BigDecimal taxPerUnit = materialLineTax.divide(materialLineQty, 6, RoundingMode.HALF_UP);
      return currency(taxPerUnit.multiply(returnQuantity));
    }

    BigDecimal inventoryTotal =
        purchase.getLines().stream()
            .map(RawMaterialPurchaseLine::getLineTotal)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (inventoryTotal.compareTo(BigDecimal.ZERO) <= 0) {
      return currency(BigDecimal.ZERO);
    }
    BigDecimal allocationRatio = materialLineTotal.divide(inventoryTotal, 6, RoundingMode.HALF_UP);
    BigDecimal allocatedTax = purchaseTax.multiply(allocationRatio);
    BigDecimal taxPerUnit = allocatedTax.divide(materialLineQty, 6, RoundingMode.HALF_UP);
    return currency(taxPerUnit.multiply(returnQuantity));
  }

  private String resolveReturnReference(
      Company company,
      Supplier supplier,
      PurchaseReturnRequest request,
      String idempotencyKeyHeader) {
    String explicitReference =
        StringUtils.hasText(request.referenceNumber()) ? request.referenceNumber().trim() : null;
    String canonicalIdempotencyKey =
        idempotencyReservationService.normalizeKey(idempotencyKeyHeader);
    if (!StringUtils.hasText(canonicalIdempotencyKey)) {
      String referenceNumber =
          StringUtils.hasText(explicitReference)
              ? explicitReference
              : referenceNumberService.purchaseReturnReference(company, supplier);
      return requireJournalReferenceLength(referenceNumber);
    }
    String requiredIdempotencyKey =
        idempotencyReservationService.requireKey(canonicalIdempotencyKey, "purchase returns");
    if (StringUtils.hasText(explicitReference)
        && !requiredIdempotencyKey.equals(explicitReference)) {
      throw new ApplicationException(
              ErrorCode.VALIDATION_INVALID_INPUT,
              "referenceNumber must match Idempotency-Key for purchase returns")
          .withDetail("referenceNumber", explicitReference)
          .withDetail("idempotencyKey", requiredIdempotencyKey);
    }
    return requireJournalReferenceLength(requiredIdempotencyKey);
  }

  private String requireJournalReferenceLength(String referenceNumber) {
    if (!StringUtils.hasText(referenceNumber)
        || referenceNumber.length() <= MAX_JOURNAL_REFERENCE_LENGTH) {
      return referenceNumber;
    }
    throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_INPUT,
            "referenceNumber cannot exceed 64 characters for purchase returns")
        .withDetail("referenceNumber", referenceNumber)
        .withDetail("maxLength", MAX_JOURNAL_REFERENCE_LENGTH);
  }

  private BigDecimal positive(BigDecimal value, String field) {
    if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Value for " + field + " must be greater than zero");
    }
    return value;
  }

  private RawMaterial requireActiveMaterial(Company company, Long rawMaterialId) {
    try {
      return inventoryLookupService.lockActiveRawMaterial(company, rawMaterialId);
    } catch (IllegalArgumentException ex) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Raw material not found");
    }
  }

  private BigDecimal currency(BigDecimal value) {
    return MoneyUtils.roundCurrency(value);
  }

  private String returnMemo(RawMaterial material, Supplier supplier, String reason) {
    String prefix = StringUtils.hasText(reason) ? reason.trim() : "Purchase return";
    return prefix + " - " + material.getName() + " to " + supplier.getName();
  }

  private GstService.GstBreakdown splitTaxAmountSafe(
      BigDecimal taxableAmount,
      BigDecimal taxAmount,
      String sourceStateCode,
      String supplierStateCode) {
    GstService.GstBreakdown lineBreakdown =
        gstService.splitTaxAmount(taxableAmount, taxAmount, sourceStateCode, supplierStateCode);
    if (lineBreakdown != null) {
      return lineBreakdown;
    }
    return fallbackTaxBreakdown(taxableAmount, taxAmount, sourceStateCode, supplierStateCode);
  }

  private String resolveCompanyStateCode(Company company) {
    if (company == null) {
      return null;
    }
    return gstService.normalizeStateCode(company.getStateCode());
  }

  private String resolveSupplierStateCode(Supplier supplier) {
    if (supplier == null) {
      return null;
    }
    String explicit = gstService.normalizeStateCode(supplier.getStateCode());
    if (StringUtils.hasText(explicit)) {
      return explicit;
    }
    String inferred = inferStateCodeFromGstNumber(supplier.getGstNumber());
    return gstService.normalizeStateCode(inferred);
  }

  private String inferStateCodeFromGstNumber(String gstNumber) {
    if (!StringUtils.hasText(gstNumber)) {
      return null;
    }
    String normalized = gstNumber.trim().toUpperCase(java.util.Locale.ROOT);
    if (normalized.length() < 2) {
      return null;
    }
    return normalized.substring(0, 2);
  }

  private GstService.GstBreakdown fallbackTaxBreakdown(
      BigDecimal taxableAmount,
      BigDecimal taxAmount,
      String sourceStateCode,
      String supplierStateCode) {
    GstService.TaxType taxType =
        gstService.resolveTaxType(sourceStateCode, supplierStateCode, false);
    if (taxType == GstService.TaxType.INTER_STATE) {
      return new GstService.GstBreakdown(
          currency(taxableAmount), BigDecimal.ZERO, BigDecimal.ZERO, currency(taxAmount), taxType);
    }
    BigDecimal roundedTax = currency(taxAmount);
    BigDecimal cgst = currency(roundedTax.divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP));
    BigDecimal sgst = currency(roundedTax.subtract(cgst));
    return new GstService.GstBreakdown(
        currency(taxableAmount), cgst, sgst, BigDecimal.ZERO, taxType);
  }
}
