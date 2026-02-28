package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyReservationService;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalCreationRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRequestRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRequestRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.factory.dto.*;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class PackingService {

    private static final String MOVEMENT_TYPE_RECEIPT = "RECEIPT";
    private static final RoundingMode COST_ROUNDING = RoundingMode.HALF_UP;

    private final CompanyContextService companyContextService;
    private final ProductionLogRepository productionLogRepository;
    private final PackingRecordRepository packingRecordRepository;
    private final PackingRequestRecordRepository packingRequestRecordRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;
    private final AccountingFacade accountingFacade;
    private final CompanyClock companyClock;
    private final ProductionLogService productionLogService;
    private final BatchNumberService batchNumberService;
    private final CompanyEntityLookup companyEntityLookup;
    private final PackagingMaterialService packagingMaterialService;
    private final FinishedGoodsService finishedGoodsService;
    private final IdempotencyReservationService idempotencyReservationService = new IdempotencyReservationService();

    public PackingService(CompanyContextService companyContextService,
                          ProductionLogRepository productionLogRepository,
                          PackingRecordRepository packingRecordRepository,
                          PackingRequestRecordRepository packingRequestRecordRepository,
                          FinishedGoodRepository finishedGoodRepository,
                          FinishedGoodBatchRepository finishedGoodBatchRepository,
                          InventoryMovementRepository inventoryMovementRepository,
                          RawMaterialMovementRepository rawMaterialMovementRepository,
                          AccountingFacade accountingFacade,
                          ProductionLogService productionLogService,
                          BatchNumberService batchNumberService,
                          CompanyClock companyClock,
                          CompanyEntityLookup companyEntityLookup,
                          PackagingMaterialService packagingMaterialService,
                          FinishedGoodsService finishedGoodsService) {
        this.companyContextService = companyContextService;
        this.productionLogRepository = productionLogRepository;
        this.packingRecordRepository = packingRecordRepository;
        this.packingRequestRecordRepository = packingRequestRecordRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
        this.accountingFacade = accountingFacade;
        this.productionLogService = productionLogService;
        this.batchNumberService = batchNumberService;
        this.companyClock = companyClock;
        this.companyEntityLookup = companyEntityLookup;
        this.packagingMaterialService = packagingMaterialService;
        this.finishedGoodsService = finishedGoodsService;
    }

    @Transactional
    public ProductionLogDetailDto recordPacking(PackingRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        // Use pessimistic lock to prevent concurrent packing of the same production log
        ProductionLog log = companyEntityLookup.lockProductionLog(company, request.productionLogId());
        if (log.getStatus() == ProductionLogStatus.FULLY_PACKED) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Production log " + log.getProductionCode() + " is already fully packed");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Packing lines are required");
        }
        LocalDate packedDate = request.packedDate() != null ? request.packedDate() : resolveCurrentDate(company);
        String idempotencyKey = idempotencyReservationService.normalizeKey(request.idempotencyKey());
        String idempotencyHash = idempotencyKey != null ? packingRequestHash(request, packedDate) : null;
        PackingRequestRecord reservedRecord = null;
        if (idempotencyKey != null) {
            IdempotencyReservation reservation = reserveIdempotencyRecord(
                    company,
                    request.productionLogId(),
                    idempotencyKey,
                    idempotencyHash
            );
            if (reservation.replayResult() != null) {
                return reservation.replayResult();
            }
            reservedRecord = reservation.record();
        }
        FinishedGood finishedGood = ensureFinishedGood(company, log);

        BigDecimal sessionQuantity = BigDecimal.ZERO;
        Long firstPackingRecordId = null;
        int lineIndex = 0;
        for (PackingLineRequest line : request.lines()) {
            lineIndex++;
            BigDecimal lineQuantity = resolveQuantity(line, lineIndex);
            if (lineQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Line " + lineIndex + " has zero quantity");
            }
            
            // Resolve pieces count for packaging material consumption
            int piecesCount = resolvePiecesCountForLine(line, lineIndex);
            
            PackingRecord record = new PackingRecord();
            record.setCompany(company);
            record.setProductionLog(log);
            record.setFinishedGood(finishedGood);
            record.setPackagingSize(line.packagingSize().trim());
            record.setQuantityPacked(lineQuantity);
            record.setPiecesCount(nullSafe(line.piecesCount()));
            record.setBoxesCount(nullSafe(line.boxesCount()));
            record.setPiecesPerBox(nullSafe(line.piecesPerBox()));
            record.setPackedDate(packedDate);
            record.setPackedBy(clean(request.packedBy()));

            PackingRecord savedRecord = packingRecordRepository.save(record);
            if (firstPackingRecordId == null) {
                firstPackingRecordId = savedRecord.getId();
            }
            String packagingReference = log.getProductionCode() + "-PACK-" + savedRecord.getId();

            // Consume packaging materials (buckets) - auto-deducts from RM stock
            PackagingConsumptionResult packagingResult = packagingMaterialService.consumePackagingMaterial(
                    line.packagingSize(),
                    piecesCount,
                    packagingReference
            );

            // Track packaging cost and material
            if (packagingResult.isConsumed()) {
                record.setPackagingCost(packagingResult.totalCost());
                record.setPackagingQuantity(packagingResult.quantity());
            }
            
            if (packagingResult.isConsumed()
                    && !packagingResult.accountTotalsOrEmpty().isEmpty()
                    && packagingResult.totalCost().compareTo(BigDecimal.ZERO) > 0) {
                postPackagingMaterialJournal(log, packagingResult, packedDate, packagingReference);
            }

            SemiFinishedConsumption semiFinished = consumeSemiFinishedInventory(log, lineQuantity);
            FinishedGoodBatch batch = registerFinishedGoodBatch(log, finishedGood, savedRecord, lineQuantity, packedDate, packagingResult, semiFinished);
            savedRecord.setFinishedGoodBatch(batch);
            packingRecordRepository.save(savedRecord);
            log.getPackingRecords().add(savedRecord);

            sessionQuantity = sessionQuantity.add(lineQuantity);
        }

        if (sessionQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Packed quantity must be greater than zero");
        }

        // Use atomic update to prevent lost updates under concurrent packing
        int updated = productionLogRepository.incrementPackedQuantityAtomic(log.getId(), sessionQuantity);
        if (updated == 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Packed quantity would exceed mixed quantity for " + log.getProductionCode());
        }

        // Refresh entity to get updated values and update status
        ProductionLog refreshedLog = companyEntityLookup.requireProductionLog(company, log.getId());
        updateStatus(refreshedLog, refreshedLog.getTotalPackedQuantity());
        productionLogRepository.save(refreshedLog);
        if (reservedRecord != null) {
            reservedRecord.setPackingRecordId(firstPackingRecordId);
            packingRequestRecordRepository.save(reservedRecord);
        }
        return productionLogService.getLog(log.getId());
    }

    private IdempotencyReservation reserveIdempotencyRecord(Company company,
                                                            Long productionLogId,
                                                            String idempotencyKey,
                                                            String idempotencyHash) {
        Optional<PackingRequestRecord> existing = packingRequestRecordRepository
                .findByCompanyAndIdempotencyKey(company, idempotencyKey);
        if (existing.isPresent()) {
            return resolveExistingIdempotency(existing.get(), productionLogId, idempotencyHash);
        }

        PackingRequestRecord record = new PackingRequestRecord();
        record.setCompany(company);
        record.setIdempotencyKey(idempotencyKey);
        record.setIdempotencyHash(idempotencyHash);
        record.setProductionLogId(productionLogId);
        try {
            PackingRequestRecord saved = packingRequestRecordRepository.saveAndFlush(record);
            return new IdempotencyReservation(saved, null);
        } catch (DataIntegrityViolationException ex) {
            PackingRequestRecord collided = packingRequestRecordRepository
                    .findByCompanyAndIdempotencyKey(company, idempotencyKey)
                    .orElseThrow(() -> ex);
            return resolveExistingIdempotency(collided, productionLogId, idempotencyHash);
        }
    }

    private IdempotencyReservation resolveExistingIdempotency(PackingRequestRecord record,
                                                              Long productionLogId,
                                                              String idempotencyHash) {
        if (!productionLogId.equals(record.getProductionLogId())) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency key already used for a different production log")
                    .withDetail("idempotencyKey", record.getIdempotencyKey())
                    .withDetail("existingProductionLogId", record.getProductionLogId())
                    .withDetail("requestedProductionLogId", productionLogId);
        }

        if (StringUtils.hasText(record.getIdempotencyHash())
                && !record.getIdempotencyHash().equals(idempotencyHash)) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Idempotency payload mismatch for packing request")
                    .withDetail("idempotencyKey", record.getIdempotencyKey())
                    .withDetail("productionLogId", productionLogId);
        }

        ProductionLogDetailDto replay = productionLogService.getLog(record.getProductionLogId());
        return new IdempotencyReservation(record, replay);
    }

    private String packingRequestHash(PackingRequest request, LocalDate packedDate) {
        StringBuilder payload = new StringBuilder();
        payload.append("log=").append(request.productionLogId())
                .append("|date=").append(packedDate)
                .append("|by=").append(clean(request.packedBy()));
        if (request.lines() != null) {
            int idx = 0;
            for (PackingLineRequest line : request.lines()) {
                idx++;
                payload.append("|line").append(idx).append(':')
                        .append(clean(line.packagingSize()))
                        .append(':').append(decimalToken(line.quantityLiters()))
                        .append(':').append(line.piecesCount())
                        .append(':').append(line.boxesCount())
                        .append(':').append(line.piecesPerBox());
            }
        }
        return IdempotencyUtils.sha256Hex(payload.toString());
    }

    private String decimalToken(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private record IdempotencyReservation(PackingRequestRecord record,
                                          ProductionLogDetailDto replayResult) {
    }

    @Transactional
    public List<UnpackedBatchDto> listUnpackedBatches() {
        Company company = companyContextService.requireCurrentCompany();
        List<ProductionLogStatus> statuses = List.of(ProductionLogStatus.READY_TO_PACK, ProductionLogStatus.PARTIAL_PACKED);
        return productionLogRepository.findByCompanyAndStatusInOrderByProducedAtAsc(company, statuses).stream()
                .map(log -> new UnpackedBatchDto(
                        log.getId(),
                        log.getProductionCode(),
                        log.getProduct().getProductName(),
                        log.getBatchColour(),
                        Optional.ofNullable(log.getMixedQuantity()).orElse(BigDecimal.ZERO),
                        Optional.ofNullable(log.getTotalPackedQuantity()).orElse(BigDecimal.ZERO),
                        Optional.ofNullable(log.getMixedQuantity()).orElse(BigDecimal.ZERO)
                                .subtract(Optional.ofNullable(log.getTotalPackedQuantity()).orElse(BigDecimal.ZERO))
                                .max(BigDecimal.ZERO),
                        log.getStatus().name(),
                        log.getProducedAt()
                ))
                .toList();
    }

    public List<PackingRecordDto> packingHistory(Long productionLogId) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionLog log = companyEntityLookup.requireProductionLog(company, productionLogId);
        return packingRecordRepository.findByCompanyAndProductionLogOrderByPackedDateAscIdAsc(company, log).stream()
                .sorted(Comparator.comparing(PackingRecord::getPackedDate).thenComparing(PackingRecord::getId))
                .map(record -> new PackingRecordDto(
                        record.getId(),
                        record.getPackagingSize(),
                        record.getQuantityPacked(),
                        record.getPiecesCount(),
                        record.getBoxesCount(),
                        record.getPiecesPerBox(),
                        record.getPackedDate(),
                        record.getPackedBy()
                ))
                .toList();
    }

    @Transactional
    public ProductionLogDetailDto completePacking(Long productionLogId) {
        Company company = companyContextService.requireCurrentCompany();
        // Use pessimistic lock to prevent concurrent completion attempts
        ProductionLog log = companyEntityLookup.lockProductionLog(company, productionLogId);
        if (log.getStatus() == ProductionLogStatus.FULLY_PACKED) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Production log " + log.getProductionCode() + " is already completed");
        }
        BigDecimal mixedQty = Optional.ofNullable(log.getMixedQuantity()).orElse(BigDecimal.ZERO);
        BigDecimal packedQty = Optional.ofNullable(log.getTotalPackedQuantity()).orElse(BigDecimal.ZERO);
        BigDecimal wastageQty = mixedQty.subtract(packedQty);
        if (wastageQty.compareTo(BigDecimal.ZERO) < 0) {
            wastageQty = BigDecimal.ZERO;
        }
        FinishedGood finishedGood = ensureFinishedGood(company, log);
        if (wastageQty.compareTo(BigDecimal.ZERO) > 0) {
            consumeSemiFinishedWastage(log, wastageQty);
        }
        postCompletionEntries(company, log, finishedGood, packedQty, wastageQty);
        log.setStatus(ProductionLogStatus.FULLY_PACKED);
        log.setWastageQuantity(wastageQty);
        productionLogRepository.save(log);
        return productionLogService.getLog(log.getId());
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

    private BigDecimal calculateWastage(ProductionLog log) {
        BigDecimal mixed = Optional.ofNullable(log.getMixedQuantity()).orElse(BigDecimal.ZERO);
        BigDecimal packed = Optional.ofNullable(log.getTotalPackedQuantity()).orElse(BigDecimal.ZERO);
        BigDecimal result = mixed.subtract(packed);
        return result.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : result;
    }

    private FinishedGood ensureFinishedGood(Company company, ProductionLog log) {
        ProductionProduct product = log.getProduct();
        return finishedGoodRepository.lockByCompanyAndProductCode(company, product.getSkuCode())
                .orElseGet(() -> initializeFinishedGood(company, product));
    }

    private FinishedGoodBatch registerFinishedGoodBatch(ProductionLog log,
                                                        FinishedGood finishedGood,
                                                        PackingRecord record,
                                                        BigDecimal quantity,
                                                        LocalDate packedDate,
                                                        PackagingConsumptionResult packagingResult,
                                                        SemiFinishedConsumption semiFinished) {
        // Capture cost snapshot before any mutations
        BigDecimal baseUnitCost = semiFinished != null && semiFinished.unitCost() != null
                ? semiFinished.unitCost()
                : Optional.ofNullable(log.getUnitCost()).orElse(BigDecimal.ZERO);
        
        // Add packaging cost per unit (bucket cost / liters in this line)
        BigDecimal packagingCostPerUnit = BigDecimal.ZERO;
        if (packagingResult.isConsumed() && quantity.compareTo(BigDecimal.ZERO) > 0) {
            packagingCostPerUnit = packagingResult.totalCost()
                    .divide(quantity, 4, RoundingMode.HALF_UP);
        }
        BigDecimal totalUnitCost = baseUnitCost.add(packagingCostPerUnit);

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode(batchNumberService.nextFinishedGoodBatchCode(finishedGood, packedDate));
        batch.setQuantityTotal(quantity);
        batch.setQuantityAvailable(quantity);
        batch.setUnitCost(totalUnitCost);
        batch.setManufacturedAt(log.getProducedAt());
        batch.setBulk(true);  // Mark as bulk so it can be converted to sized FG via BulkPackingService
        if (semiFinished != null) {
            batch.setParentBatch(semiFinished.batch());
        }
        FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(batch);

        BigDecimal current = Optional.ofNullable(finishedGood.getCurrentStock()).orElse(BigDecimal.ZERO);
        finishedGood.setCurrentStock(current.add(quantity));
        finishedGoodRepository.save(finishedGood);
        finishedGoodsService.invalidateWeightedAverageCost(finishedGood.getId());

        InventoryMovement movement = new InventoryMovement();
        movement.setFinishedGood(finishedGood);
        movement.setFinishedGoodBatch(savedBatch);
        movement.setReferenceType(InventoryReference.PRODUCTION_LOG);
        movement.setReferenceId(log.getProductionCode());
        movement.setMovementType(MOVEMENT_TYPE_RECEIPT);
        movement.setQuantity(quantity);
        movement.setUnitCost(totalUnitCost);
        InventoryMovement savedMovement = inventoryMovementRepository.save(movement);

        // Post WIP->FG journal immediately to prevent desync on crash
        // Include packaging cost in the journal value
        postPackingSessionJournal(log, finishedGood, quantity, baseUnitCost, packagingCostPerUnit, packedDate, savedMovement, semiFinished);

        return savedBatch;
    }
    
    private int resolvePiecesCountForLine(PackingLineRequest line, int lineNumber) {
        if (line.piecesCount() != null && line.piecesCount() > 0) {
            return line.piecesCount();
        }
        if (line.boxesCount() != null && line.boxesCount() > 0
                && line.piecesPerBox() != null && line.piecesPerBox() > 0) {
            return line.boxesCount() * line.piecesPerBox();
        }
        throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Pieces count or boxes × pieces per box required for line " + lineNumber);
    }

    private void postPackingSessionJournal(ProductionLog log,
                                           FinishedGood finishedGood,
                                           BigDecimal quantity,
                                           BigDecimal productionUnitCost,
                                           BigDecimal packagingCostPerUnit,
                                           LocalDate packedDate,
                                           InventoryMovement movement,
                                           SemiFinishedConsumption semiFinished) {
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Long wipAccountId = requireWipAccountId(log.getProduct());
        Long semiFinishedAccountId = semiFinished != null
                ? semiFinished.semiFinished().getValuationAccountId()
                : requireSemiFinishedAccountId(log.getProduct());
        Long fgAccountId = finishedGood.getValuationAccountId();
        if (fgAccountId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Finished good " + finishedGood.getProductCode() + " missing valuation account");
        }
        BigDecimal productionValue = MoneyUtils.safeMultiply(productionUnitCost, quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal packagingValue = MoneyUtils.safeMultiply(packagingCostPerUnit, quantity).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalValue = productionValue.add(packagingValue);
        if (totalValue.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String reference = log.getProductionCode() + "-PACK-" + movement.getId();
        String memo = "FG receipt for " + log.getProductionCode() + " (qty: " + quantity + ")";

        List<JournalEntryRequest.JournalLineRequest> lines = new java.util.ArrayList<>();
        lines.add(new JournalEntryRequest.JournalLineRequest(fgAccountId, memo, totalValue, BigDecimal.ZERO));
        if (productionValue.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    semiFinishedAccountId,
                    memo + " - semi-finished",
                    BigDecimal.ZERO,
                    productionValue));
        }
        if (packagingValue.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    wipAccountId,
                    memo + " - packaging",
                    BigDecimal.ZERO,
                    packagingValue));
        }

        JournalEntryDto entry = accountingFacade.postPackingJournal(reference, packedDate, memo, lines);
        if (entry != null) {
            movement.setJournalEntryId(entry.id());
            inventoryMovementRepository.save(movement);
            if (semiFinished != null && semiFinished.movement() != null) {
                semiFinished.movement().setJournalEntryId(entry.id());
                inventoryMovementRepository.save(semiFinished.movement());
            }
        }
    }

    private SemiFinishedConsumption consumeSemiFinishedInventory(ProductionLog log, BigDecimal quantity) {
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        Company company = companyContextService.requireCurrentCompany();
        String semiSku = semiFinishedSku(log.getProduct());
        FinishedGood semiFinished = finishedGoodRepository.lockByCompanyAndProductCode(company, semiSku)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Semi-finished SKU " + semiSku + " not found for production " + log.getProductionCode()));
        FinishedGoodBatch batch = finishedGoodBatchRepository
                .lockByFinishedGoodAndBatchCode(semiFinished, log.getProductionCode())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Semi-finished batch " + log.getProductionCode() + " not found"));
        if (batch.getQuantityAvailable().compareTo(quantity) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Insufficient semi-finished stock for " + log.getProductionCode());
        }

        batch.allocate(quantity);
        BigDecimal total = batch.getQuantityTotal() != null ? batch.getQuantityTotal() : BigDecimal.ZERO;
        batch.setQuantityTotal(total.subtract(quantity));
        finishedGoodBatchRepository.save(batch);

        semiFinished.adjustStock(quantity.negate(), "PACKING");
        finishedGoodRepository.save(semiFinished);
        finishedGoodsService.invalidateWeightedAverageCost(semiFinished.getId());

        InventoryMovement issue = new InventoryMovement();
        issue.setFinishedGood(semiFinished);
        issue.setFinishedGoodBatch(batch);
        issue.setReferenceType(InventoryReference.PRODUCTION_LOG);
        issue.setReferenceId(log.getProductionCode());
        issue.setMovementType("ISSUE");
        issue.setQuantity(quantity);
        issue.setUnitCost(batch.getUnitCost());
        InventoryMovement savedIssue = inventoryMovementRepository.save(issue);

        return new SemiFinishedConsumption(semiFinished, batch, savedIssue, batch.getUnitCost());
    }

    private void consumeSemiFinishedWastage(ProductionLog log, BigDecimal wastageQty) {
        if (wastageQty == null || wastageQty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Company company = companyContextService.requireCurrentCompany();
        String semiSku = semiFinishedSku(log.getProduct());
        FinishedGood semiFinished = finishedGoodRepository.lockByCompanyAndProductCode(company, semiSku)
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Semi-finished SKU " + semiSku + " not found for production " + log.getProductionCode()));
        FinishedGoodBatch batch = finishedGoodBatchRepository
                .lockByFinishedGoodAndBatchCode(semiFinished, log.getProductionCode())
                .orElseThrow(() -> new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Semi-finished batch " + log.getProductionCode() + " not found"));
        if (batch.getQuantityAvailable().compareTo(wastageQty) < 0) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Insufficient semi-finished stock for wastage on " + log.getProductionCode());
        }

        batch.allocate(wastageQty);
        BigDecimal total = batch.getQuantityTotal() != null ? batch.getQuantityTotal() : BigDecimal.ZERO;
        batch.setQuantityTotal(total.subtract(wastageQty));
        finishedGoodBatchRepository.save(batch);

        semiFinished.adjustStock(wastageQty.negate(), "PACKING_WASTAGE");
        finishedGoodRepository.save(semiFinished);
        finishedGoodsService.invalidateWeightedAverageCost(semiFinished.getId());

        InventoryMovement issue = new InventoryMovement();
        issue.setFinishedGood(semiFinished);
        issue.setFinishedGoodBatch(batch);
        issue.setReferenceType(InventoryReference.PRODUCTION_LOG);
        issue.setReferenceId(log.getProductionCode());
        issue.setMovementType("WASTAGE");
        issue.setQuantity(wastageQty);
        issue.setUnitCost(batch.getUnitCost());
        inventoryMovementRepository.save(issue);
    }

    private Long requireSemiFinishedAccountId(ProductionProduct product) {
        Long accountId = Optional.ofNullable(metadataLong(product, "semiFinishedAccountId"))
                .orElse(metadataLong(product, "fgValuationAccountId"));
        if (accountId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Product " + product.getProductName() + " missing semiFinishedAccountId metadata");
        }
        return accountId;
    }

    private String semiFinishedSku(ProductionProduct product) {
        return product.getSkuCode() + "-BULK";
    }

    private record SemiFinishedConsumption(FinishedGood semiFinished,
                                           FinishedGoodBatch batch,
                                           InventoryMovement movement,
                                           BigDecimal unitCost) {
    }

    private void postPackagingMaterialJournal(ProductionLog log,
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
        Long wipAccountId = requireWipAccountId(log.getProduct());
        BigDecimal totalCost = accountTotals.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        if (totalCost.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        String reference = referenceId + "-PACKMAT";
        String memo = "Packaging material consumption for " + log.getProductionCode();
        List<JournalEntryRequest.JournalLineRequest> lines = new java.util.ArrayList<>();
        lines.add(new JournalEntryRequest.JournalLineRequest(wipAccountId, memo, totalCost, BigDecimal.ZERO));
        for (Map.Entry<Long, BigDecimal> entry : accountTotals.entrySet()) {
            BigDecimal amount = entry.getValue().setScale(2, RoundingMode.HALF_UP);
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                lines.add(new JournalEntryRequest.JournalLineRequest(
                        entry.getKey(), memo, BigDecimal.ZERO, amount));
            }
        }

        BigDecimal totalDebit = lines.stream()
                .map(JournalEntryRequest.JournalLineRequest::debit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = lines.stream()
                .map(JournalEntryRequest.JournalLineRequest::credit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDebit.compareTo(totalCredit) != 0 && !lines.isEmpty()) {
            BigDecimal variance = totalCredit.subtract(totalDebit);
            JournalEntryRequest.JournalLineRequest debitLine = lines.get(0);
            lines.set(0, new JournalEntryRequest.JournalLineRequest(
                    debitLine.accountId(),
                    debitLine.description(),
                    debitLine.debit().add(variance),
                    debitLine.credit()));
        }

        JournalEntryDto entry = accountingFacade.postPackingJournal(reference, packedDate, memo, lines);
        if (entry != null) {
            linkPackagingMovementsToJournal(log.getCompany(), referenceId, entry.id());
        }
    }

    private void linkPackagingMovementsToJournal(Company company, String referenceId, Long journalEntryId) {
        if (journalEntryId == null) {
            return;
        }
        List<RawMaterialMovement> movements = rawMaterialMovementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company, InventoryReference.PACKING_RECORD, referenceId);
        if (movements.isEmpty()) {
            return;
        }
        List<RawMaterialMovement> toUpdate = new ArrayList<>();
        for (RawMaterialMovement movement : movements) {
            Long existingJournalId = movement.getJournalEntryId();
            if (existingJournalId == null) {
                movement.setJournalEntryId(journalEntryId);
                toUpdate.add(movement);
                continue;
            }
            if (!Objects.equals(existingJournalId, journalEntryId)) {
                throw new ApplicationException(
                        ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                        "Packing reference " + referenceId + " already linked to journal " + existingJournalId);
            }
        }
        if (!toUpdate.isEmpty()) {
            rawMaterialMovementRepository.saveAll(toUpdate);
        }
    }

    private void postCompletionEntries(Company company,
                                       ProductionLog log,
                                       FinishedGood finishedGood,
                                       BigDecimal packedQty,
                                       BigDecimal wastageQty) {
        // FG receipt journals are now posted per packing session in postPackingSessionJournal
        // Only wastage journal is posted during completion
        if (wastageQty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal materialUnitCost = calculateUnitCost(log.getMaterialCostTotal(), log.getMixedQuantity());
            BigDecimal baseUnitCost = Optional.ofNullable(log.getUnitCost()).orElse(BigDecimal.ZERO);
            if (baseUnitCost.compareTo(BigDecimal.ZERO) <= 0) {
                baseUnitCost = materialUnitCost;
            }
            Long wipAccountId = requireWipAccountId(log.getProduct());
            LocalDate entryDate = resolveJournalDate(company, log);
            BigDecimal wastageValue = MoneyUtils.safeMultiply(baseUnitCost, wastageQty).setScale(2, RoundingMode.HALF_UP);
            Long wastageAccountId = metadataLong(log.getProduct(), "wastageAccountId");
            if (wastageAccountId == null) {
                throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                        "Product " + log.getProduct().getProductName() + " missing wastageAccountId metadata");
            }
            accountingFacade.createStandardJournal(new JournalCreationRequest(
                    wastageValue,
                    wastageAccountId,
                    wipAccountId,
                    "Manufacturing wastage for " + log.getProductionCode(),
                    "FACTORY_PACKING",
                    log.getProductionCode() + "-WASTE",
                    null,
                    null,
                    entryDate,
                    null,
                    null,
                    Boolean.FALSE
            ));
        }
    }

    private FinishedGood initializeFinishedGood(Company company, ProductionProduct product) {
        Long valuationAccountId = metadataLong(product, "fgValuationAccountId");
        Long cogsAccountId = metadataLong(product, "fgCogsAccountId");
        Long revenueAccountId = metadataLong(product, "fgRevenueAccountId");
        Long discountAccountId = metadataLong(product, "fgDiscountAccountId");
        Long taxAccountId = metadataLong(product, "fgTaxAccountId");
        if (valuationAccountId == null || cogsAccountId == null || revenueAccountId == null
                || discountAccountId == null || taxAccountId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Product " + product.getProductName() + " missing finished good account metadata");
        }
        FinishedGood created = new FinishedGood();
        created.setCompany(company);
        created.setProductCode(product.getSkuCode());
        created.setName(product.getProductName());
        created.setUnit(Optional.ofNullable(product.getUnitOfMeasure()).orElse("UNIT"));
        created.setCostingMethod("FIFO");
        created.setValuationAccountId(valuationAccountId);
        created.setCogsAccountId(cogsAccountId);
        created.setRevenueAccountId(revenueAccountId);
        created.setDiscountAccountId(discountAccountId);
        created.setTaxAccountId(taxAccountId);
        created.setCurrentStock(BigDecimal.ZERO);
        created.setReservedStock(BigDecimal.ZERO);
        return finishedGoodRepository.save(created);
    }

    private BigDecimal resolveQuantity(PackingLineRequest line, int lineNumber) {
        if (line.quantityLiters() != null && line.quantityLiters().compareTo(BigDecimal.ZERO) > 0) {
            return line.quantityLiters();
        }
        BigDecimal pieces = resolvePieces(line);
        if (pieces == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Pieces or boxes must be provided for line " + lineNumber);
        }
        BigDecimal packSize = resolvePackageSize(line.packagingSize(), lineNumber);
        return MoneyUtils.safeMultiply(packSize, pieces);
    }

    private BigDecimal resolvePieces(PackingLineRequest line) {
        if (line.piecesCount() != null && line.piecesCount() > 0) {
            return BigDecimal.valueOf(line.piecesCount());
        }
        if (line.boxesCount() != null && line.boxesCount() > 0
                && line.piecesPerBox() != null && line.piecesPerBox() > 0) {
            return BigDecimal.valueOf(line.boxesCount().longValue() * line.piecesPerBox());
        }
        return null;
    }

    private BigDecimal resolvePackageSize(String size, int lineNumber) {
        if (!StringUtils.hasText(size)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Packaging size is required for line " + lineNumber);
        }
        BigDecimal parsed = PackagingSizeParser.parseSizeInLitersAllowBareNumber(size);
        if (parsed == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid packaging size '" + size + "' on line " + lineNumber);
        }
        return parsed;
    }

    private LocalDate resolveCurrentDate(Company company) {
        return companyClock.today(company);
    }

    private LocalDate resolveJournalDate(Company company, ProductionLog log) {
        ZoneId zoneId = Optional.ofNullable(company.getTimezone())
                .filter(StringUtils::hasText)
                .map(ZoneId::of)
                .orElse(ZoneOffset.UTC);
        return log.getProducedAt().atZone(zoneId).toLocalDate();
    }

    private Long requireWipAccountId(ProductionProduct product) {
        Long accountId = metadataLong(product, "wipAccountId");
        if (accountId == null) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_REFERENCE,
                    "Product " + product.getProductName() + " missing wipAccountId metadata");
        }
        return accountId;
    }

    private Long metadataLong(ProductionProduct product, String key) {
        if (product.getMetadata() == null) {
            return null;
        }
        Object candidate = product.getMetadata().get(key);
        if (candidate instanceof Number number) {
            return number.longValue();
        }
        if (candidate instanceof String str && StringUtils.hasText(str)) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private BigDecimal calculateUnitCost(BigDecimal total, BigDecimal quantity) {
        if (total == null || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return total.divide(quantity, 6, COST_ROUNDING);
    }

    private String clean(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Integer nullSafe(Integer value) {
        return value != null && value > 0 ? value : null;
    }
}
