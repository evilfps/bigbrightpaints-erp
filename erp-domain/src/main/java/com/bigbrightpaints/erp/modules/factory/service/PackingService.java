package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
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
import com.bigbrightpaints.erp.modules.inventory.service.BatchNumberService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class PackingService {

    private static final String MOVEMENT_TYPE_RECEIPT = "RECEIPT";
    private static final RoundingMode COST_ROUNDING = RoundingMode.HALF_UP;

    private final CompanyContextService companyContextService;
    private final ProductionLogRepository productionLogRepository;
    private final PackingRecordRepository packingRecordRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final AccountingFacade accountingFacade;
    private final ProductionLogService productionLogService;
    private final BatchNumberService batchNumberService;

    public PackingService(CompanyContextService companyContextService,
                          ProductionLogRepository productionLogRepository,
                          PackingRecordRepository packingRecordRepository,
                          FinishedGoodRepository finishedGoodRepository,
                          FinishedGoodBatchRepository finishedGoodBatchRepository,
                          InventoryMovementRepository inventoryMovementRepository,
                          AccountingFacade accountingFacade,
                          ProductionLogService productionLogService,
                          BatchNumberService batchNumberService) {
        this.companyContextService = companyContextService;
        this.productionLogRepository = productionLogRepository;
        this.packingRecordRepository = packingRecordRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.accountingFacade = accountingFacade;
        this.productionLogService = productionLogService;
        this.batchNumberService = batchNumberService;
    }

    @Transactional
    public ProductionLogDetailDto recordPacking(PackingRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionLog log = productionLogRepository.findByCompanyAndId(company, request.productionLogId())
                .orElseThrow(() -> new IllegalArgumentException("Production log not found"));
        if (log.getStatus() == ProductionLogStatus.FULLY_PACKED) {
            throw new IllegalStateException("Production log " + log.getProductionCode() + " is already fully packed");
        }
        if (request.lines() == null || request.lines().isEmpty()) {
            throw new IllegalArgumentException("Packing lines are required");
        }
        FinishedGood finishedGood = ensureFinishedGood(company, log);
        LocalDate packedDate = request.packedDate() != null ? request.packedDate() : resolveCurrentDate(company);

        BigDecimal sessionQuantity = BigDecimal.ZERO;
        int lineIndex = 0;
        for (PackingLineRequest line : request.lines()) {
            lineIndex++;
            BigDecimal lineQuantity = resolveQuantity(line, lineIndex);
            if (lineQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Line " + lineIndex + " has zero quantity");
            }
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

            FinishedGoodBatch batch = registerFinishedGoodBatch(log, finishedGood, savedRecord, lineQuantity, packedDate);
            savedRecord.setFinishedGoodBatch(batch);
            packingRecordRepository.save(savedRecord);
            log.getPackingRecords().add(savedRecord);

            sessionQuantity = sessionQuantity.add(lineQuantity);
        }

        if (sessionQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Packed quantity must be greater than zero");
        }

        BigDecimal updated = log.getTotalPackedQuantity().add(sessionQuantity);
        if (updated.compareTo(log.getMixedQuantity()) > 0) {
            throw new IllegalArgumentException("Packed quantity exceeds mixed quantity for " + log.getProductionCode());
        }
        log.setTotalPackedQuantity(updated);
        log.setWastageQuantity(calculateWastage(log));
        updateStatus(log, updated);
        productionLogRepository.save(log);
        return productionLogService.getLog(log.getId());
    }

    public List<UnpackedBatchDto> listUnpackedBatches() {
        Company company = companyContextService.requireCurrentCompany();
        List<ProductionLogStatus> statuses = List.of(ProductionLogStatus.READY_TO_PACK, ProductionLogStatus.PARTIAL_PACKED);
        return productionLogRepository.findByCompanyAndStatusInOrderByProducedAtAsc(company, statuses).stream()
                .map(log -> new UnpackedBatchDto(
                        log.getId(),
                        log.getProductionCode(),
                        log.getProduct().getProductName(),
                        log.getBatchColour(),
                        log.getMixedQuantity(),
                        log.getTotalPackedQuantity(),
                        log.getMixedQuantity().subtract(log.getTotalPackedQuantity()).max(BigDecimal.ZERO),
                        log.getStatus().name(),
                        log.getProducedAt()
                ))
                .toList();
    }

    public List<PackingRecordDto> packingHistory(Long productionLogId) {
        Company company = companyContextService.requireCurrentCompany();
        ProductionLog log = productionLogRepository.findByCompanyAndId(company, productionLogId)
                .orElseThrow(() -> new IllegalArgumentException("Production log not found"));
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
        ProductionLog log = productionLogRepository.findByCompanyAndId(company, productionLogId)
                .orElseThrow(() -> new IllegalArgumentException("Production log not found"));
        BigDecimal mixedQty = log.getMixedQuantity();
        BigDecimal packedQty = log.getTotalPackedQuantity();
        BigDecimal wastageQty = mixedQty.subtract(packedQty);
        if (wastageQty.compareTo(BigDecimal.ZERO) < 0) {
            wastageQty = BigDecimal.ZERO;
        }
        FinishedGood finishedGood = ensureFinishedGood(company, log);
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
                                                        LocalDate packedDate) {
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode(batchNumberService.nextFinishedGoodBatchCode(finishedGood, packedDate));
        batch.setQuantityTotal(quantity);
        batch.setQuantityAvailable(quantity);
        batch.setUnitCost(Optional.ofNullable(log.getUnitCost()).orElse(BigDecimal.ZERO));
        batch.setManufacturedAt(log.getProducedAt());
        FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(batch);

        BigDecimal current = Optional.ofNullable(finishedGood.getCurrentStock()).orElse(BigDecimal.ZERO);
        finishedGood.setCurrentStock(current.add(quantity));
        finishedGoodRepository.save(finishedGood);

        InventoryMovement movement = new InventoryMovement();
        movement.setFinishedGood(finishedGood);
        movement.setFinishedGoodBatch(savedBatch);
        movement.setReferenceType(InventoryReference.PRODUCTION_LOG);
        movement.setReferenceId(log.getProductionCode());
        movement.setMovementType(MOVEMENT_TYPE_RECEIPT);
        movement.setQuantity(quantity);
        movement.setUnitCost(Optional.ofNullable(log.getUnitCost()).orElse(BigDecimal.ZERO));
        inventoryMovementRepository.save(movement);
        return savedBatch;
    }

    private void postCompletionEntries(Company company,
                                       ProductionLog log,
                                       FinishedGood finishedGood,
                                       BigDecimal packedQty,
                                       BigDecimal wastageQty) {
        BigDecimal materialUnitCost = calculateUnitCost(log.getMaterialCostTotal(), log.getMixedQuantity());
        Long wipAccountId = requireWipAccountId(log.getProduct());
        LocalDate entryDate = resolveJournalDate(company, log);
        if (packedQty.compareTo(BigDecimal.ZERO) > 0) {
            Long fgAccountId = finishedGood.getValuationAccountId();
            if (fgAccountId == null) {
                throw new IllegalStateException("Finished good " + finishedGood.getProductCode() + " missing valuation account");
            }
            BigDecimal packedValue = materialUnitCost.multiply(packedQty).setScale(2, RoundingMode.HALF_UP);
            JournalEntryDto entry = accountingFacade.postSimpleJournal(
                    log.getProductionCode() + "-PACK",
                    entryDate,
                    "Finished goods receipt for " + log.getProductionCode(),
                    fgAccountId,
                    wipAccountId,
                    packedValue,
                    false
            );
            linkInventoryMovementsToJournal(log.getProductionCode(), entry.id());
        }
        if (wastageQty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal wastageValue = materialUnitCost.multiply(wastageQty).setScale(2, RoundingMode.HALF_UP);
            Long wastageAccountId = metadataLong(log.getProduct(), "wastageAccountId");
            if (wastageAccountId == null) {
                throw new IllegalStateException("Product " + log.getProduct().getProductName() + " missing wastageAccountId metadata");
            }
            accountingFacade.postSimpleJournal(
                    log.getProductionCode() + "-WASTE",
                    entryDate,
                    "Manufacturing wastage for " + log.getProductionCode(),
                    wastageAccountId,
                    wipAccountId,
                    wastageValue,
                    false
            );
        }
    }

    private void linkInventoryMovementsToJournal(String referenceId, Long journalEntryId) {
        if (journalEntryId == null) {
            return;
        }
        List<InventoryMovement> movements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(InventoryReference.PRODUCTION_LOG, referenceId);
        if (movements.isEmpty()) {
            return;
        }
        movements.forEach(movement -> movement.setJournalEntryId(journalEntryId));
        inventoryMovementRepository.saveAll(movements);
    }

    private FinishedGood initializeFinishedGood(Company company, ProductionProduct product) {
        Long valuationAccountId = metadataLong(product, "fgValuationAccountId");
        Long cogsAccountId = metadataLong(product, "fgCogsAccountId");
        Long revenueAccountId = metadataLong(product, "fgRevenueAccountId");
        Long discountAccountId = metadataLong(product, "fgDiscountAccountId");
        Long taxAccountId = metadataLong(product, "fgTaxAccountId");
        if (valuationAccountId == null || cogsAccountId == null || revenueAccountId == null
                || discountAccountId == null || taxAccountId == null) {
            throw new IllegalStateException("Product " + product.getProductName()
                    + " missing finished good account metadata");
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
            throw new IllegalArgumentException("Pieces or boxes must be provided for line " + lineNumber);
        }
        BigDecimal packSize = resolvePackageSize(line.packagingSize(), lineNumber);
        return packSize.multiply(pieces);
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
            throw new IllegalArgumentException("Packaging size is required for line " + lineNumber);
        }
        String normalized = size.trim().toUpperCase(Locale.ROOT);
        if (normalized.endsWith("L")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid packaging size '" + size + "' on line " + lineNumber);
        }
    }

    private LocalDate resolveCurrentDate(Company company) {
        ZoneId zoneId = Optional.ofNullable(company.getTimezone())
                .filter(StringUtils::hasText)
                .map(ZoneId::of)
                .orElse(ZoneOffset.UTC);
        return LocalDate.now(zoneId);
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
            throw new IllegalStateException("Product " + product.getProductName() + " missing wipAccountId metadata");
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
