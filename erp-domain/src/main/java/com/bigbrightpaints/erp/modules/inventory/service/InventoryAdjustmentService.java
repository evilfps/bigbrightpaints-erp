package com.bigbrightpaints.erp.modules.inventory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencySignatureBuilder;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.security.SecurityActorResolver;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CostingMethodUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.CostingMethod;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.CostingMethodService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
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

@Service
public class InventoryAdjustmentService {

  private final CompanyContextService companyContextService;
  private final FinishedGoodRepository finishedGoodRepository;
  private final InventoryAdjustmentRepository adjustmentRepository;
  private final InventoryMovementRepository inventoryMovementRepository;
  private final FinishedGoodBatchRepository finishedGoodBatchRepository;
  private final AccountingFacade accountingFacade;
  private final AuditService auditService;
  private final ReferenceNumberService referenceNumberService;
  private final CompanyClock companyClock;
  private final FinishedGoodsService finishedGoodsService;
  private final CostingMethodService costingMethodService;
  private final IdempotencyReservationService idempotencyReservationService =
      new IdempotencyReservationService();
  private final TransactionTemplate transactionTemplate;

  @Autowired(required = false)
  private InventoryPhysicalCountService inventoryPhysicalCountService;

  public InventoryAdjustmentService(
      CompanyContextService companyContextService,
      FinishedGoodRepository finishedGoodRepository,
      InventoryAdjustmentRepository adjustmentRepository,
      InventoryMovementRepository inventoryMovementRepository,
      FinishedGoodBatchRepository finishedGoodBatchRepository,
      AccountingFacade accountingFacade,
      AuditService auditService,
      ReferenceNumberService referenceNumberService,
      CompanyClock companyClock,
      FinishedGoodsService finishedGoodsService,
      CostingMethodService costingMethodService,
      PlatformTransactionManager transactionManager) {
    this.companyContextService = companyContextService;
    this.finishedGoodRepository = finishedGoodRepository;
    this.adjustmentRepository = adjustmentRepository;
    this.inventoryMovementRepository = inventoryMovementRepository;
    this.finishedGoodBatchRepository = finishedGoodBatchRepository;
    this.accountingFacade = accountingFacade;
    this.auditService = auditService;
    this.referenceNumberService = referenceNumberService;
    this.companyClock = companyClock;
    this.finishedGoodsService = finishedGoodsService;
    this.costingMethodService = costingMethodService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public List<InventoryAdjustmentDto> listAdjustments() {
    Company company = companyContextService.requireCurrentCompany();
    return adjustmentRepository.findByCompanyOrderByAdjustmentDateDesc(company).stream()
        .map(this::toDto)
        .toList();
  }

  @Retryable(
      value = {OptimisticLockingFailureException.class, DataIntegrityViolationException.class},
      maxAttempts = 3,
      backoff = @Backoff(delay = 100))
  public InventoryAdjustmentDto createAdjustment(InventoryAdjustmentRequest request) {
    if (request == null || request.lines() == null || request.lines().isEmpty()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Adjustment lines are required");
    }
    List<InventoryAdjustmentRequest.LineRequest> sortedLines =
        request.lines().stream()
            .sorted(Comparator.comparing(InventoryAdjustmentRequest.LineRequest::finishedGoodId))
            .toList();
    Company company = companyContextService.requireCurrentCompany();
    String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
    if (!StringUtils.hasText(idempotencyKey)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Idempotency key is required for inventory adjustments");
    }
    LocalDate resolvedAdjustmentDate =
        request.adjustmentDate() != null ? request.adjustmentDate() : resolveCurrentDate(company);
    String requestSignature =
        buildAdjustmentSignature(request, sortedLines, resolvedAdjustmentDate);
    InventoryAdjustment existing =
        adjustmentRepository
            .findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
            .orElse(null);
    if (existing != null) {
      assertIdempotencyMatch(existing, requestSignature, idempotencyKey);
      return toDto(existing);
    }
    try {
      return transactionTemplate.execute(
          status ->
              createAdjustmentInternal(
                  request,
                  sortedLines,
                  company,
                  resolvedAdjustmentDate,
                  idempotencyKey,
                  requestSignature));
    } catch (RuntimeException ex) {
      if (!idempotencyReservationService.isDataIntegrityViolation(ex)) {
        throw ex;
      }
      InventoryAdjustment concurrent =
          adjustmentRepository
              .findWithLinesByCompanyAndIdempotencyKey(company, idempotencyKey)
              .orElseThrow(() -> ex);
      assertIdempotencyMatch(concurrent, requestSignature, idempotencyKey);
      return toDto(concurrent);
    }
  }

  private InventoryAdjustmentDto createAdjustmentInternal(
      InventoryAdjustmentRequest request,
      List<InventoryAdjustmentRequest.LineRequest> sortedLines,
      Company company,
      LocalDate resolvedAdjustmentDate,
      String idempotencyKey,
      String requestSignature) {
    List<Long> finishedGoodIds =
        sortedLines.stream().map(InventoryAdjustmentRequest.LineRequest::finishedGoodId).toList();
    if (finishedGoodIds.stream().anyMatch(id -> id == null)) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Finished good not found");
    }
    List<Long> uniqueFinishedGoodIds = finishedGoodIds.stream().distinct().toList();
    List<FinishedGood> lockedFinishedGoods =
        finishedGoodRepository.lockByCompanyAndIdInOrderById(company, uniqueFinishedGoodIds);
    Map<Long, FinishedGood> finishedGoodsById = new HashMap<>();
    for (FinishedGood finishedGood : lockedFinishedGoods) {
      finishedGoodsById.put(finishedGood.getId(), finishedGood);
    }
    if (finishedGoodsById.size() != uniqueFinishedGoodIds.size()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Finished good not found");
    }
    InventoryAdjustmentType type =
        request.type() == null ? InventoryAdjustmentType.DAMAGED : request.type();
    boolean increaseInventory = type == InventoryAdjustmentType.RECOUNT_UP;
    InventoryAdjustment adjustment = new InventoryAdjustment();
    adjustment.setCompany(company);
    adjustment.setReferenceNumber(resolveReference(company, type));
    adjustment.setAdjustmentDate(resolvedAdjustmentDate);
    adjustment.setType(type);
    adjustment.setReason(request.reason());
    adjustment.setCreatedBy(resolveCurrentUser());
    adjustment.setIdempotencyKey(idempotencyKey);
    adjustment.setIdempotencyHash(requestSignature);
    for (InventoryAdjustmentRequest.LineRequest lineRequest : sortedLines) {
      FinishedGood finishedGood = finishedGoodsById.get(lineRequest.finishedGoodId());
      if (finishedGood == null) {
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
            "Finished good not found");
      }
      buildLine(adjustment, finishedGood, lineRequest);
    }

    InventoryAdjustment savedDraft = adjustmentRepository.save(adjustment);

    List<InventoryMovement> movements = applyMovements(savedDraft, null, increaseInventory);
    Map<Long, BigDecimal> inventoryCredits = new HashMap<>();
    BigDecimal totalAmount = BigDecimal.ZERO;
    for (InventoryAdjustmentLine line : savedDraft.getLines()) {
      Long valuationAccountId = line.getFinishedGood().getValuationAccountId();
      if (valuationAccountId == null) {
        throw new ApplicationException(
            ErrorCode.VALIDATION_INVALID_REFERENCE,
            "Finished good "
                + line.getFinishedGood().getProductCode()
                + " is missing a valuation account");
      }
      inventoryCredits.merge(valuationAccountId, line.getAmount(), BigDecimal::add);
      totalAmount = totalAmount.add(line.getAmount());
    }
    if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Adjustment amount must be greater than zero");
    }
    savedDraft.setTotalAmount(totalAmount);

    boolean adminOverride = Boolean.TRUE.equals(request.adminOverride());
    String memo = memoFor(savedDraft, request.reason());
    List<JournalCreationRequest.LineRequest> standardizedLines = new java.util.ArrayList<>();
    if (increaseInventory) {
      inventoryCredits.forEach(
          (accountId, amount) ->
              standardizedLines.add(
                  new JournalCreationRequest.LineRequest(
                      accountId, amount, BigDecimal.ZERO, memo)));
      standardizedLines.add(
          new JournalCreationRequest.LineRequest(
              request.adjustmentAccountId(), BigDecimal.ZERO, totalAmount, memo));
    } else {
      standardizedLines.add(
          new JournalCreationRequest.LineRequest(
              request.adjustmentAccountId(), totalAmount, BigDecimal.ZERO, memo));
      inventoryCredits.forEach(
          (accountId, amount) ->
              standardizedLines.add(
                  new JournalCreationRequest.LineRequest(
                      accountId, BigDecimal.ZERO, amount, memo)));
    }
    JournalCreationRequest standardizedAdjustmentRequest =
        new JournalCreationRequest(
            totalAmount,
            request.adjustmentAccountId(),
            inventoryCredits.keySet().stream().findFirst().orElse(null),
            memo,
            "INVENTORY_ADJUSTMENT",
            savedDraft.getReferenceNumber(),
            null,
            standardizedLines,
            savedDraft.getAdjustmentDate(),
            null,
            null,
            adminOverride);
    JournalEntryDto journalEntry =
        accountingFacade.postInventoryAdjustment(
            savedDraft.getType().name(),
            savedDraft.getReferenceNumber(),
            request.adjustmentAccountId(),
            Map.copyOf(inventoryCredits),
            increaseInventory,
            adminOverride,
            standardizedAdjustmentRequest.narration(),
            standardizedAdjustmentRequest.entryDate());
    if (journalEntry == null) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Inventory adjustment journal was not created");
    }
    savedDraft.setJournalEntryId(journalEntry.id());
    savedDraft.setStatus("POSTED");
    movements.forEach(movement -> movement.setJournalEntryId(journalEntry.id()));
    inventoryMovementRepository.saveAll(movements);
    InventoryAdjustment posted = adjustmentRepository.save(savedDraft);
    logInventoryAdjustmentAuditEvent(posted, increaseInventory);
    return toDto(posted);
  }

  private InventoryAdjustmentLine buildLine(
      InventoryAdjustment adjustment,
      FinishedGood finishedGood,
      InventoryAdjustmentRequest.LineRequest lineRequest) {
    BigDecimal quantity = ValidationUtils.requirePositive(lineRequest.quantity(), "quantity");
    ValidationUtils.requirePositive(lineRequest.unitCost(), "unitCost");
    if (adjustment.getType() != InventoryAdjustmentType.RECOUNT_UP) {
      BigDecimal currentStock =
          finishedGood.getCurrentStock() == null ? BigDecimal.ZERO : finishedGood.getCurrentStock();
      BigDecimal reservedStock = safeQuantity(finishedGood.getReservedStock());
      BigDecimal available = currentStock.subtract(reservedStock);
      if (available.compareTo(quantity) < 0) {
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
            "Insufficient available stock for " + finishedGood.getProductCode());
      }
    }
    InventoryAdjustmentLine line = new InventoryAdjustmentLine();
    line.setAdjustment(adjustment);
    line.setFinishedGood(finishedGood);
    line.setQuantity(quantity);
    line.setUnitCost(lineRequest.unitCost());
    line.setAmount(BigDecimal.ZERO);
    line.setNote(lineRequest.note());
    adjustment.addLine(line);
    return line;
  }

  private List<InventoryMovement> applyMovements(
      InventoryAdjustment adjustment, Long journalEntryId, boolean increaseInventory) {
    List<InventoryMovement> movements = new java.util.ArrayList<>();
    adjustment
        .getLines()
        .forEach(
            line -> {
              FinishedGood finishedGood = line.getFinishedGood();
              if (increaseInventory) {
                BigDecimal currentStock =
                    finishedGood.getCurrentStock() == null
                        ? BigDecimal.ZERO
                        : finishedGood.getCurrentStock();
                BigDecimal physicalQuantity = currentStock.add(line.getQuantity());
                finishedGood.setCurrentStock(physicalQuantity);

                BigDecimal lineUnitCost = safeQuantity(line.getUnitCost());
                BigDecimal lineAmount =
                    line.getQuantity().multiply(lineUnitCost).setScale(4, RoundingMode.HALF_UP);
                line.setAmount(lineAmount);
                line.setUnitCost(lineUnitCost);
                recordFinishedGoodPhysicalCount(adjustment, line, physicalQuantity);

                FinishedGoodBatch adjustmentBatch = new FinishedGoodBatch();
                adjustmentBatch.setFinishedGood(finishedGood);
                adjustmentBatch.setBatchCode(
                    batchCodeForRecountAdjustment(finishedGood, adjustment.getReferenceNumber()));
                adjustmentBatch.setQuantityTotal(line.getQuantity());
                adjustmentBatch.setQuantityAvailable(line.getQuantity());
                adjustmentBatch.setUnitCost(line.getUnitCost());
                adjustmentBatch.setManufacturedAt(companyClock.now(finishedGood.getCompany()));
                adjustmentBatch.setSource(
                    com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource
                        .ADJUSTMENT);
                adjustmentBatch = finishedGoodBatchRepository.saveAndFlush(adjustmentBatch);
                finishedGoodsService.invalidateWeightedAverageCost(finishedGood.getId());

                InventoryMovement movement = new InventoryMovement();
                movement.setFinishedGood(finishedGood);
                movement.setFinishedGoodBatch(adjustmentBatch);
                movement.setReferenceType("ADJUSTMENT");
                movement.setReferenceId(adjustment.getReferenceNumber());
                movement.setMovementType("ADJUSTMENT_IN");
                movement.setQuantity(line.getQuantity());
                movement.setUnitCost(line.getUnitCost());
                movement.setJournalEntryId(journalEntryId);
                movements.add(movement);
                return;
              }

              BigDecimal currentStock =
                  finishedGood.getCurrentStock() == null
                      ? BigDecimal.ZERO
                      : finishedGood.getCurrentStock();
              BigDecimal physicalQuantity = currentStock.subtract(line.getQuantity());
              finishedGood.setCurrentStock(physicalQuantity);
              ConsumedBatchCost consumedCost =
                  adjustBatchQuantities(
                      finishedGood, line.getQuantity(), adjustment.getAdjustmentDate());
              finishedGoodsService.invalidateWeightedAverageCost(finishedGood.getId());

              line.setAmount(consumedCost.totalCost());
              line.setUnitCost(consumedCost.unitCost());
              recordFinishedGoodPhysicalCount(adjustment, line, physicalQuantity);

              InventoryMovement movement = new InventoryMovement();
              movement.setFinishedGood(finishedGood);
              if (consumedCost.batchConsumptions().size() == 1) {
                movement.setFinishedGoodBatch(consumedCost.batchConsumptions().getFirst().batch());
              }
              movement.setReferenceType("ADJUSTMENT");
              movement.setReferenceId(adjustment.getReferenceNumber());
              movement.setMovementType("ADJUSTMENT_OUT");
              movement.setQuantity(line.getQuantity());
              movement.setUnitCost(consumedCost.unitCost());
              movement.setJournalEntryId(journalEntryId);
              movements.add(movement);
            });
    return movements;
  }

  private ConsumedBatchCost adjustBatchQuantities(
      FinishedGood finishedGood, BigDecimal quantity, LocalDate adjustmentDate) {
    if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
      return new ConsumedBatchCost(BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }

    CostingMethodUtils.FinishedGoodBatchSelectionMethod selectionMethod =
        resolveSelectionMethod(finishedGood, adjustmentDate);
    BigDecimal weightedAverageCost =
        selectionMethod == CostingMethodUtils.FinishedGoodBatchSelectionMethod.WAC
            ? safeQuantity(finishedGoodsService.currentWeightedAverageCost(finishedGood))
            : null;

    BigDecimal remaining = quantity;
    BigDecimal totalCost = BigDecimal.ZERO;
    List<BatchConsumption> batchConsumptions = new java.util.ArrayList<>();
    for (FinishedGoodBatch batch : selectBatchesByCostingMethod(finishedGood, selectionMethod)) {
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
      BigDecimal costPerUnit =
          selectionMethod == CostingMethodUtils.FinishedGoodBatchSelectionMethod.WAC
              ? safeQuantity(weightedAverageCost)
              : safeQuantity(batch.getUnitCost());
      totalCost = totalCost.add(delta.multiply(costPerUnit));
      batchConsumptions.add(new BatchConsumption(batch, delta, costPerUnit));
      remaining = remaining.subtract(delta);
    }
    if (remaining.compareTo(BigDecimal.ZERO) > 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState(
          "Insufficient batch availability for " + finishedGood.getProductCode());
    }

    BigDecimal normalizedTotalCost = totalCost.setScale(4, RoundingMode.HALF_UP);
    BigDecimal unitCost =
        quantity.compareTo(BigDecimal.ZERO) > 0
            ? normalizedTotalCost.divide(quantity, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
    return new ConsumedBatchCost(normalizedTotalCost, unitCost, List.copyOf(batchConsumptions));
  }

  private CostingMethodUtils.FinishedGoodBatchSelectionMethod resolveSelectionMethod(
      FinishedGood finishedGood, LocalDate adjustmentDate) {
    CostingMethod activeMethod =
        costingMethodService.resolveActiveMethod(finishedGood.getCompany(), adjustmentDate);
    return CostingMethodUtils.resolveFinishedGoodBatchSelectionMethod(activeMethod.name());
  }

  private List<FinishedGoodBatch> selectBatchesByCostingMethod(
      FinishedGood finishedGood,
      CostingMethodUtils.FinishedGoodBatchSelectionMethod selectionMethod) {
    return switch (selectionMethod) {
      case WAC -> finishedGoodBatchRepository.findAllocatableBatches(finishedGood);
      case LIFO -> finishedGoodBatchRepository.findAllocatableBatchesLIFO(finishedGood);
      case FIFO -> finishedGoodBatchRepository.findAllocatableBatchesFIFO(finishedGood);
    };
  }

  private record ConsumedBatchCost(
      BigDecimal totalCost, BigDecimal unitCost, List<BatchConsumption> batchConsumptions) {}

  private record BatchConsumption(
      FinishedGoodBatch batch, BigDecimal quantity, BigDecimal unitCost) {}

  private InventoryAdjustmentDto toDto(InventoryAdjustment adjustment) {
    List<InventoryAdjustmentLineDto> lines =
        adjustment.getLines().stream()
            .map(
                line ->
                    new InventoryAdjustmentLineDto(
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
        lines);
  }

  private BigDecimal safeQuantity(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  private void recordFinishedGoodPhysicalCount(
      InventoryAdjustment adjustment, InventoryAdjustmentLine line, BigDecimal physicalQuantity) {
    if (inventoryPhysicalCountService == null
        || adjustment == null
        || adjustment.getCompany() == null
        || line == null
        || line.getFinishedGood() == null
        || line.getFinishedGood().getId() == null) {
      return;
    }
    inventoryPhysicalCountService.recordFinishedGoodCount(
        adjustment.getCompany(),
        line.getFinishedGood().getId(),
        safeQuantity(physicalQuantity),
        adjustment.getAdjustmentDate(),
        adjustment.getReferenceNumber(),
        line.getNote());
  }

  private String normalizeIdempotencyKey(String raw) {
    return idempotencyReservationService.normalizeKey(raw);
  }

  private void assertIdempotencyMatch(
      InventoryAdjustment adjustment, String expectedSignature, String idempotencyKey) {
    idempotencyReservationService.assertAndRepairSignature(
        adjustment,
        idempotencyKey,
        expectedSignature,
        InventoryAdjustment::getIdempotencyHash,
        InventoryAdjustment::setIdempotencyHash,
        adjustmentRepository::save);
  }

  private String buildAdjustmentSignature(
      InventoryAdjustmentRequest request,
      List<InventoryAdjustmentRequest.LineRequest> sortedLines,
      LocalDate resolvedDate) {
    IdempotencySignatureBuilder signature =
        IdempotencySignatureBuilder.create()
            .add(resolvedDate != null ? resolvedDate : "")
            .add(request.type() != null ? request.type().name() : "")
            .add(request.adjustmentAccountId() != null ? request.adjustmentAccountId() : "")
            .addToken(request.reason())
            .add(Boolean.TRUE.equals(request.adminOverride()));
    for (InventoryAdjustmentRequest.LineRequest line : sortedLines) {
      signature.add(
          (line.finishedGoodId() != null ? line.finishedGoodId() : "")
              + ":"
              + IdempotencyUtils.normalizeAmount(line.quantity())
              + ":"
              + IdempotencyUtils.normalizeAmount(line.unitCost())
              + ":"
              + IdempotencyUtils.normalizeToken(line.note()));
    }
    return signature.buildHash();
  }

  private String memoFor(InventoryAdjustment adjustment, String reason) {
    String suffix = StringUtils.hasText(reason) ? reason.trim() : adjustment.getType().name();
    return "Inventory adjustment - " + suffix;
  }

  private String batchCodeForRecountAdjustment(FinishedGood finishedGood, String referenceNumber) {
    String product =
        finishedGood != null && StringUtils.hasText(finishedGood.getProductCode())
            ? finishedGood.getProductCode().replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT)
            : "FG";
    String reference =
        StringUtils.hasText(referenceNumber)
            ? referenceNumber.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT)
            : "ADJ";
    String suffix =
        reference.length() > 12 ? reference.substring(reference.length() - 12) : reference;
    return "RECOUNT-" + product + "-" + suffix;
  }

  private String resolveReference(Company company, InventoryAdjustmentType type) {
    return referenceNumberService.inventoryAdjustmentReference(
        company, type != null ? type.name() : "GEN");
  }

  private LocalDate resolveCurrentDate(Company company) {
    return companyClock.today(company);
  }

  private String resolveCurrentUser() {
    return SecurityActorResolver.resolveActorWithSystemProcessFallback();
  }

  private void logInventoryAdjustmentAuditEvent(
      InventoryAdjustment adjustment, boolean increaseInventory) {
    if (adjustment == null
        || adjustment.getCompany() == null
        || adjustment.getCompany().getId() == null) {
      return;
    }
    Map<String, String> metadata = new HashMap<>();
    metadata.put("resourceType", "INVENTORY");
    metadata.put("referenceType", "INVENTORY_ADJUSTMENT");
    metadata.put("adjustmentDirection", increaseInventory ? "INCREASE" : "DECREASE");
    if (adjustment.getReferenceNumber() != null) {
      metadata.put("referenceNumber", adjustment.getReferenceNumber());
    }
    if (adjustment.getId() != null) {
      metadata.put("adjustmentId", adjustment.getId().toString());
    }
    if (adjustment.getJournalEntryId() != null) {
      metadata.put("journalEntryId", adjustment.getJournalEntryId().toString());
    }
    if (adjustment.getTotalAmount() != null) {
      metadata.put("totalAmount", adjustment.getTotalAmount().toPlainString());
    }
    if (adjustment.getLines() != null) {
      metadata.put("lineCount", Integer.toString(adjustment.getLines().size()));
    }
    auditService.logSuccess(AuditEvent.INVENTORY_ADJUSTMENT, metadata);
  }
}
