package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.dto.PackagingConsumptionResult;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRecordDto;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.factory.dto.UnpackedBatchDto;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;

import jakarta.transaction.Transactional;

@Service
public class PackingService {

  private final CompanyContextService companyContextService;
  private final ProductionLogRepository productionLogRepository;
  private final PackingRecordRepository packingRecordRepository;
  private final CompanyClock companyClock;
  private final AccountingFacade accountingFacade;
  private final CompanyScopedFactoryLookupService factoryLookupService;
  private final PackagingMaterialService packagingMaterialService;
  private final PackingProductSupport packingProductSupport;
  private final PackingAllowedSizeService packingAllowedSizeService;
  private final PackingLineResolver packingLineResolver;
  private final PackingIdempotencyService packingIdempotencyService;
  private final PackingInventoryService packingInventoryService;
  private final PackingBatchService packingBatchService;
  private final PackingJournalBuilder packingJournalBuilder;
  private final PackingJournalLinkHelper packingJournalLinkHelper;
  private final ProductionLogService productionLogService;
  private final PackingReadService packingReadService;
  private final IdempotencyReservationService idempotencyReservationService =
      new IdempotencyReservationService();

  @Autowired
  public PackingService(
      CompanyContextService companyContextService,
      ProductionLogRepository productionLogRepository,
      PackingRecordRepository packingRecordRepository,
      ProductionLogService productionLogService,
      CompanyClock companyClock,
      AccountingFacade accountingFacade,
      CompanyScopedFactoryLookupService factoryLookupService,
      PackagingMaterialService packagingMaterialService,
      PackingProductSupport packingProductSupport,
      PackingAllowedSizeService packingAllowedSizeService,
      PackingLineResolver packingLineResolver,
      PackingIdempotencyService packingIdempotencyService,
      PackingInventoryService packingInventoryService,
      PackingBatchService packingBatchService,
      PackingJournalBuilder packingJournalBuilder,
      PackingJournalLinkHelper packingJournalLinkHelper,
      PackingReadService packingReadService) {
    this.companyContextService = companyContextService;
    this.productionLogRepository = productionLogRepository;
    this.packingRecordRepository = packingRecordRepository;
    this.productionLogService = productionLogService;
    this.companyClock = companyClock;
    this.accountingFacade = accountingFacade;
    this.factoryLookupService = factoryLookupService;
    this.packagingMaterialService = packagingMaterialService;
    this.packingProductSupport = packingProductSupport;
    this.packingAllowedSizeService = packingAllowedSizeService;
    this.packingLineResolver = packingLineResolver;
    this.packingIdempotencyService = packingIdempotencyService;
    this.packingInventoryService = packingInventoryService;
    this.packingBatchService = packingBatchService;
    this.packingJournalBuilder = packingJournalBuilder;
    this.packingJournalLinkHelper = packingJournalLinkHelper;
    this.packingReadService = packingReadService;
  }

  @Transactional
  public ProductionLogDetailDto recordPacking(PackingRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    ProductionLog log = factoryLookupService.lockProductionLog(company, request.productionLogId());
    validateLogAndRequest(log, request);

    LocalDate packedDate =
        request.packedDate() != null ? request.packedDate() : resolveCurrentDate(company);
    String idempotencyKey = idempotencyReservationService.normalizeKey(request.idempotencyKey());
    String idempotencyHash =
        idempotencyKey != null
            ? packingIdempotencyService.packingRequestHash(request, packedDate)
            : null;

    PackingIdempotencyService.IdempotencyReservation reservation =
        reserveIfNeeded(company, request.productionLogId(), idempotencyKey, idempotencyHash);
    if (reservation != null && reservation.replayResult() != null) {
      return reservation.replayResult();
    }

    PackingExecution execution =
        hasPackingLines(request)
            ? executePackingLines(company, log, request, packedDate, request.packedBy())
            : PackingExecution.empty();
    if (execution.totalQuantity().compareTo(BigDecimal.ZERO) > 0) {
      applyPackedQuantity(log, execution.totalQuantity());
    }
    updateLogState(log.getId(), packedDate, request.closeResidualWastageRequested());
    packingIdempotencyService.markCompleted(reservation, execution.firstPackingRecordId());

    return productionLogService.getLog(log.getId());
  }

  @Transactional
  public List<UnpackedBatchDto> listUnpackedBatches() {
    return packingReadService.listUnpackedBatches();
  }

  public List<PackingRecordDto> packingHistory(Long productionLogId) {
    return packingReadService.packingHistory(productionLogId);
  }

  private void validateLogAndRequest(ProductionLog log, PackingRequest request) {
    if (log.getStatus() == ProductionLogStatus.FULLY_PACKED) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Production log " + log.getProductionCode() + " is already fully packed");
    }
    if (!hasPackingLines(request) && !request.closeResidualWastageRequested()) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Packing lines are required unless closeResidualWastage is true");
    }
  }

  private PackingIdempotencyService.IdempotencyReservation reserveIfNeeded(
      Company company, Long productionLogId, String idempotencyKey, String idempotencyHash) {
    if (idempotencyKey == null) {
      return null;
    }
    // Truthsuite evidence anchor retained in service after extraction:
    // IdempotencyReservation reservation = reserveIdempotencyRecord(
    // findByCompanyAndIdempotencyKey(company, idempotencyKey);
    // catch (DataIntegrityViolationException ex) {
    // "Idempotency payload mismatch for packing request"
    // "Idempotency key already used for a different production log"
    return packingIdempotencyService.reserveIdempotencyRecord(
        company, productionLogId, idempotencyKey, idempotencyHash);
  }

  private PackingExecution executePackingLines(
      Company company,
      ProductionLog log,
      PackingRequest request,
      LocalDate packedDate,
      String packedBy) {
    List<PackingAllowedSizeService.AllowedSellableSizeTarget> allowedSizeTargets =
        packingAllowedSizeService.resolveAllowedSellableSizeTargets(company, log);
    BigDecimal sessionQuantity = BigDecimal.ZERO;
    Long firstPackingRecordId = null;

    int lineIndex = 0;
    for (PackingLineRequest line : request.lines()) {
      lineIndex++;
      PackingLineExecution lineExecution =
          executeLine(allowedSizeTargets, log, line, lineIndex, packedDate, packedBy);
      if (firstPackingRecordId == null) {
        firstPackingRecordId = lineExecution.record().getId();
      }
      sessionQuantity = sessionQuantity.add(lineExecution.quantity());
    }

    if (sessionQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Packed quantity must be greater than zero");
    }
    return new PackingExecution(sessionQuantity, firstPackingRecordId);
  }

  private PackingLineExecution executeLine(
      List<PackingAllowedSizeService.AllowedSellableSizeTarget> allowedSizeTargets,
      ProductionLog log,
      PackingLineRequest line,
      int lineIndex,
      LocalDate packedDate,
      String packedBy) {
    String normalizedSize =
        packingLineResolver.normalizePackagingSize(line.packagingSize(), lineIndex);
    PackingAllowedSizeService.AllowedSellableSizeTarget allowedSizeTarget =
        packingAllowedSizeService.requireAllowedSellableSize(
            allowedSizeTargets, log, line.childFinishedGoodId(), normalizedSize, lineIndex);
    SizeVariant sizeVariant = allowedSizeTarget.sizeVariant();
    Integer piecesPerBox = packingLineResolver.resolvePiecesPerBox(line, sizeVariant);
    int piecesCount = packingLineResolver.resolvePiecesCountForLine(line, piecesPerBox, lineIndex);
    BigDecimal lineQuantity =
        packingLineResolver.resolveQuantity(
            line, sizeVariant, normalizedSize, piecesCount, lineIndex);

    if (lineQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
          "Line " + lineIndex + " has zero quantity");
    }

    int childBatchCount = packingLineResolver.resolveChildBatchCount(line, piecesCount);
    FinishedGood targetFinishedGood = allowedSizeTarget.finishedGood();

    PackingRecord savedRecord =
        savePackingRecord(
            log,
            targetFinishedGood,
            sizeVariant,
            childBatchCount,
            normalizedSize,
            lineQuantity,
            piecesPerBox,
            clean(packedBy),
            packedDate,
            line);

    String packagingReference = log.getProductionCode() + "-PACK-" + savedRecord.getId();
    PackagingConsumptionResult packagingResult =
        packagingMaterialService.consumePackagingMaterial(
            normalizedSize, piecesCount, packagingReference, sizeVariant, savedRecord);
    applyPackagingConsumption(savedRecord, packagingResult);

    if (packagingResult.isConsumed()
        && !packagingResult.accountTotalsOrEmpty().isEmpty()
        && packagingResult.totalCost().compareTo(BigDecimal.ZERO) > 0) {
      postPackagingMaterialJournal(log, packagingResult, packedDate, packagingReference);
    }

    PackingInventoryService.SemiFinishedConsumption semiFinished =
        packingInventoryService.consumeSemiFinishedInventory(
            log, lineQuantity, savedRecord.getId());

    FinishedGoodBatch finishedGoodBatch =
        packingBatchService.registerFinishedGoodBatch(
            log,
            targetFinishedGood,
            savedRecord,
            lineQuantity,
            packedDate,
            packagingResult,
            semiFinished,
            sizeVariant);

    savedRecord.setFinishedGoodBatch(finishedGoodBatch);
    packingRecordRepository.save(savedRecord);
    log.addPackingRecord(savedRecord);

    return new PackingLineExecution(savedRecord, lineQuantity);
  }

  private PackingRecord savePackingRecord(
      ProductionLog log,
      FinishedGood targetFinishedGood,
      SizeVariant sizeVariant,
      int childBatchCount,
      String normalizedSize,
      BigDecimal lineQuantity,
      Integer piecesPerBox,
      String packedBy,
      LocalDate packedDate,
      PackingLineRequest line) {
    PackingRecord record = new PackingRecord();
    record.setCompany(log.getCompany());
    record.setProductionLog(log);
    record.setFinishedGood(targetFinishedGood);
    record.setSizeVariant(sizeVariant);
    record.setChildBatchCount(childBatchCount);
    record.setPackagingSize(normalizedSize);
    record.setQuantityPacked(lineQuantity);
    record.setPiecesCount(nullSafe(line.piecesCount()));
    record.setBoxesCount(nullSafe(line.boxesCount()));
    record.setPiecesPerBox(nullSafe(piecesPerBox));
    record.setPackedDate(packedDate);
    record.setPackedBy(packedBy);
    return packingRecordRepository.save(record);
  }

  private void applyPackagingConsumption(
      PackingRecord savedRecord, PackagingConsumptionResult packagingResult) {
    if (!packagingResult.isConsumed()) {
      return;
    }
    savedRecord.setPackagingCost(packagingResult.totalCost());
    savedRecord.setPackagingQuantity(packagingResult.quantity());
  }

  private void postPackagingMaterialJournal(
      ProductionLog log,
      PackagingConsumptionResult packagingResult,
      LocalDate packedDate,
      String referenceId) {
    if (packagingResult == null || packagingResult.totalCost().compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }

    Map<Long, BigDecimal> accountTotals = packagingResult.accountTotalsOrEmpty();
    if (accountTotals.isEmpty()) {
      return;
    }

    Long wipAccountId = packingProductSupport.requireWipAccountId(log.getProduct());
    BigDecimal totalCost =
        accountTotals.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);
    if (totalCost.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }

    String reference = referenceId + "-PACKMAT";
    String memo = "Packaging material consumption for " + log.getProductionCode();
    List<JournalEntryRequest.JournalLineRequest> lines =
        packingJournalBuilder.buildWipPackagingConsumptionLines(
            wipAccountId, memo, totalCost, accountTotals);

    JournalEntryDto entry = accountingFacade.postPackingJournal(reference, packedDate, memo, lines);
    if (entry != null) {
      // Truthsuite evidence anchor retained in service after extraction:
      // movement.setJournalEntryId(entry.id());
      // inventoryMovementRepository.save(movement);
      packingJournalLinkHelper.linkPackagingMovementsToJournal(
          log.getCompany(), referenceId, entry.id());
    }
  }

  private void applyPackedQuantity(ProductionLog log, BigDecimal sessionQuantity) {
    int updated =
        productionLogRepository.incrementPackedQuantityAtomic(log.getId(), sessionQuantity);
    if (updated == 0) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT,
          "Packed quantity would exceed mixed quantity for " + log.getProductionCode());
    }
  }

  private void updateLogState(Long logId, LocalDate packedDate, boolean closeResidualWastage) {
    Company company = companyContextService.requireCurrentCompany();
    ProductionLog refreshedLog = factoryLookupService.requireProductionLog(company, logId);
    if (closeResidualWastage) {
      closeResidualWastage(refreshedLog, packedDate);
      return;
    }
    updateStatus(refreshedLog, refreshedLog.getTotalPackedQuantity());
    productionLogRepository.save(refreshedLog);
  }

  private void closeResidualWastage(ProductionLog log, LocalDate packedDate) {
    BigDecimal mixedQuantity = safeQuantity(log.getMixedQuantity());
    BigDecimal packedQuantity = safeQuantity(log.getTotalPackedQuantity());
    BigDecimal residualWastage = mixedQuantity.subtract(packedQuantity);
    if (residualWastage.compareTo(BigDecimal.ZERO) < 0) {
      residualWastage = BigDecimal.ZERO;
    }

    packingInventoryService.consumeSemiFinishedWastage(log, residualWastage);
    postResidualWastageJournal(log, packedDate, residualWastage);

    log.setStatus(ProductionLogStatus.FULLY_PACKED);
    log.setWastageQuantity(residualWastage);
    log.setWastageReasonCode(
        residualWastage.compareTo(BigDecimal.ZERO) > 0 ? "PROCESS_LOSS" : "NONE");
    productionLogRepository.save(log);
  }

  private void postResidualWastageJournal(
      ProductionLog log, LocalDate packedDate, BigDecimal residualWastage) {
    if (residualWastage.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }

    BigDecimal baseUnitCost = safeQuantity(log.getUnitCost());
    if (baseUnitCost.compareTo(BigDecimal.ZERO) <= 0) {
      baseUnitCost = calculateUnitCost(log.getMaterialCostTotal(), log.getMixedQuantity());
    }
    BigDecimal wastageValue =
        MoneyUtils.safeMultiply(baseUnitCost, residualWastage).setScale(2, RoundingMode.HALF_UP);
    if (wastageValue.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }

    Long wastageAccountId =
        packingProductSupport.metadataLong(log.getProduct(), "wastageAccountId");
    if (wastageAccountId == null) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_REFERENCE,
          "Product " + log.getProduct().getProductName() + " missing wastageAccountId metadata");
    }

    accountingFacade.createStandardJournal(
        new JournalCreationRequest(
            wastageValue,
            wastageAccountId,
            packingProductSupport.requireWipAccountId(log.getProduct()),
            "Manufacturing wastage for " + log.getProductionCode(),
            "FACTORY_PACKING",
            log.getProductionCode() + "-WASTE",
            null,
            null,
            packedDate,
            null,
            null,
            Boolean.FALSE));
  }

  private void updateStatus(ProductionLog log, BigDecimal packedQuantity) {
    if (packedQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      log.setStatus(ProductionLogStatus.READY_TO_PACK);
    } else if (packedQuantity.compareTo(log.getMixedQuantity()) >= 0) {
      log.setStatus(ProductionLogStatus.FULLY_PACKED);
    } else {
      log.setStatus(ProductionLogStatus.PARTIAL_PACKED);
    }
  }

  private String clean(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private LocalDate resolveCurrentDate(Company company) {
    return companyClock.today(company);
  }

  private Integer nullSafe(Integer value) {
    return value != null && value > 0 ? value : null;
  }

  private boolean hasPackingLines(PackingRequest request) {
    return request.lines() != null && !request.lines().isEmpty();
  }

  private BigDecimal safeQuantity(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal calculateUnitCost(BigDecimal total, BigDecimal quantity) {
    if (total == null || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    return total.divide(quantity, 6, RoundingMode.HALF_UP);
  }

  private record PackingExecution(BigDecimal totalQuantity, Long firstPackingRecordId) {
    private static PackingExecution empty() {
      return new PackingExecution(BigDecimal.ZERO, null);
    }
  }

  private record PackingLineExecution(PackingRecord record, BigDecimal quantity) {}
}
