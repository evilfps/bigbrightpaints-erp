package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.OpeningStockImport;
import com.bigbrightpaints.erp.modules.inventory.domain.OpeningStockImportRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse.ImportError;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class OpeningStockImportService {

    private static final String DEFAULT_BATCH_REF = "OPENING";

    private final CompanyContextService companyContextService;
    private final RawMaterialRepository rawMaterialRepository;
    private final RawMaterialBatchRepository rawMaterialBatchRepository;
    private final RawMaterialMovementRepository rawMaterialMovementRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final InventoryMovementRepository inventoryMovementRepository;
    private final BatchNumberService batchNumberService;
    private final RawMaterialService rawMaterialService;
    private final FinishedGoodsService finishedGoodsService;
    private final AccountingFacade accountingFacade;
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final OpeningStockImportRepository openingStockImportRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final CompanyClock companyClock;
    private final boolean openingStockImportEnabled;
    private final Environment environment;
    private final TransactionTemplate transactionTemplate;

    public OpeningStockImportService(CompanyContextService companyContextService,
                                     RawMaterialRepository rawMaterialRepository,
                                     RawMaterialBatchRepository rawMaterialBatchRepository,
                                     RawMaterialMovementRepository rawMaterialMovementRepository,
                                     FinishedGoodRepository finishedGoodRepository,
                                     FinishedGoodBatchRepository finishedGoodBatchRepository,
                                     InventoryMovementRepository inventoryMovementRepository,
                                     BatchNumberService batchNumberService,
                                     RawMaterialService rawMaterialService,
                                     FinishedGoodsService finishedGoodsService,
                                     AccountingFacade accountingFacade,
                                     AccountRepository accountRepository,
                                     JournalEntryRepository journalEntryRepository,
                                     OpeningStockImportRepository openingStockImportRepository,
                                     AuditService auditService,
                                     ObjectMapper objectMapper,
                                     CompanyClock companyClock,
                                     Environment environment,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${erp.inventory.opening-stock.enabled:false}") boolean openingStockImportEnabled) {
        this.companyContextService = companyContextService;
        this.rawMaterialRepository = rawMaterialRepository;
        this.rawMaterialBatchRepository = rawMaterialBatchRepository;
        this.rawMaterialMovementRepository = rawMaterialMovementRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.inventoryMovementRepository = inventoryMovementRepository;
        this.batchNumberService = batchNumberService;
        this.rawMaterialService = rawMaterialService;
        this.finishedGoodsService = finishedGoodsService;
        this.accountingFacade = accountingFacade;
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.openingStockImportRepository = openingStockImportRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.companyClock = companyClock;
        this.environment = environment;
        this.openingStockImportEnabled = openingStockImportEnabled;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public OpeningStockImportResponse importOpeningStock(MultipartFile file) {
        return importOpeningStock(file, null);
    }

    public OpeningStockImportResponse importOpeningStock(MultipartFile file, String idempotencyKey) {
        Company company = companyContextService.requireCurrentCompany();
        if (file == null || file.isEmpty()) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("CSV file is required");
        }
        assertImportAllowed();
        String fileHash = resolveFileHash(file);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey, fileHash);
        String importReference = resolveImportReference(company, fileHash);

        OpeningStockImport existing = openingStockImportRepository.findByCompanyAndIdempotencyKey(company, normalizedKey)
                .orElse(null);
        if (existing != null) {
            assertIdempotencyMatch(existing, fileHash, normalizedKey);
            return toResponse(existing);
        }

        if (journalEntryRepository.findByCompanyAndReferenceNumber(company, importReference).isPresent()) {
            throw new ApplicationException(ErrorCode.BUSINESS_DUPLICATE_ENTRY,
                    "Opening stock import already processed for this file")
                    .withDetail("referenceNumber", importReference);
        }

        try {
            OpeningStockImportResponse response = transactionTemplate.execute(status ->
                    importOpeningStockInternal(company, file, normalizedKey, fileHash, importReference));
            if (response == null) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Opening stock import failed to return a response");
            }
            return response;
        } catch (RuntimeException ex) {
            if (!isDataIntegrityViolation(ex)) {
                throw ex;
            }
            OpeningStockImport concurrent = openingStockImportRepository.findByCompanyAndIdempotencyKey(company, normalizedKey)
                    .orElseThrow(() -> ex);
            assertIdempotencyMatch(concurrent, fileHash, normalizedKey);
            return toResponse(concurrent);
        }
    }

    private OpeningStockImportResponse importOpeningStockInternal(Company company,
                                                                  MultipartFile file,
                                                                  String idempotencyKey,
                                                                  String fileHash,
                                                                  String importReference) {
        OpeningStockImport record = new OpeningStockImport();
        record.setCompany(company);
        record.setIdempotencyKey(idempotencyKey);
        record.setIdempotencyHash(fileHash);
        record.setReferenceNumber(importReference);
        record.setFileHash(fileHash);
        record.setFileName(file.getOriginalFilename());
        record = openingStockImportRepository.saveAndFlush(record);

        ImportResult result = processImport(company, file, importReference);
        OpeningStockImportResponse response = result.response();

        record.setRowsProcessed(response.rowsProcessed());
        record.setRawMaterialsCreated(response.rawMaterialsCreated());
        record.setRawMaterialBatchesCreated(response.rawMaterialBatchesCreated());
        record.setFinishedGoodsCreated(response.finishedGoodsCreated());
        record.setFinishedGoodBatchesCreated(response.finishedGoodBatchesCreated());
        record.setErrorsJson(serializeErrors(response.errors()));
        record.setJournalEntryId(result.journalEntryId());
        openingStockImportRepository.save(record);

        Map<String, String> auditMetadata = new HashMap<>();
        auditMetadata.put("operation", "opening-stock-import");
        auditMetadata.put("idempotencyKey", idempotencyKey);
        auditMetadata.put("referenceNumber", importReference);
        auditMetadata.put("fileHash", fileHash);
        auditMetadata.put("rowsProcessed", Integer.toString(response.rowsProcessed()));
        if (result.journalEntryId() != null) {
            auditMetadata.put("journalEntryId", result.journalEntryId().toString());
        }
        auditService.logSuccess(AuditEvent.DATA_CREATE, auditMetadata);
        return response;
    }

    private ImportResult processImport(Company company, MultipartFile file, String importReference) {
        int rowsProcessed = 0;
        int rawMaterialsCreated = 0;
        int rawMaterialBatchesCreated = 0;
        int finishedGoodsCreated = 0;
        int finishedGoodBatchesCreated = 0;
        List<ImportError> errors = new ArrayList<>();
        Map<Long, BigDecimal> inventoryTotals = new HashMap<>();
        List<RawMaterialMovement> rawMovements = new ArrayList<>();
        List<InventoryMovement> finishedMovements = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreHeaderCase(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {

            for (CSVRecord record : parser) {
                OpeningRow row;
                try {
                    row = OpeningRow.from(record);
                } catch (IllegalArgumentException ex) {
                    errors.add(new ImportError(record.getRecordNumber(), ex.getMessage()));
                    continue;
                }
                if (row == null) {
                    continue;
                }
                try {
                    OpeningMovementResult movementResult;
                    if (row.type == StockType.RAW_MATERIAL) {
                        movementResult = handleRawMaterial(company, row);
                        if (row.createdNew) {
                            rawMaterialsCreated++;
                        }
                        rawMaterialBatchesCreated++;
                    } else {
                        movementResult = handleFinishedGood(company, row);
                        if (row.createdNew) {
                            finishedGoodsCreated++;
                        }
                        finishedGoodBatchesCreated++;
                    }
                    inventoryTotals.merge(movementResult.inventoryAccountId(), movementResult.amount(), BigDecimal::add);
                    if (movementResult.rawMovement() != null) {
                        rawMovements.add(movementResult.rawMovement());
                    }
                    if (movementResult.inventoryMovement() != null) {
                        finishedMovements.add(movementResult.inventoryMovement());
                    }
                    rowsProcessed++;
                } catch (IllegalArgumentException ex) {
                    errors.add(new ImportError(record.getRecordNumber(), ex.getMessage()));
                } catch (Exception ex) {
                    errors.add(new ImportError(record.getRecordNumber(), "Unexpected error: " + ex.getMessage()));
                }
            }

            Long journalEntryId = postOpeningStockJournal(company, inventoryTotals, importReference);
            if (journalEntryId != null) {
                rawMovements.forEach(movement -> movement.setJournalEntryId(journalEntryId));
                finishedMovements.forEach(movement -> movement.setJournalEntryId(journalEntryId));
                if (!rawMovements.isEmpty()) {
                    rawMaterialMovementRepository.saveAll(rawMovements);
                }
                if (!finishedMovements.isEmpty()) {
                    inventoryMovementRepository.saveAll(finishedMovements);
                }
            }

            OpeningStockImportResponse response = new OpeningStockImportResponse(
                    rowsProcessed,
                    rawMaterialsCreated,
                    rawMaterialBatchesCreated,
                    finishedGoodsCreated,
                    finishedGoodBatchesCreated,
                    errors
            );
            return new ImportResult(response, journalEntryId);
        } catch (IOException ex) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Failed to read CSV file", ex);
        }
    }

    private void assertImportAllowed() {
        if (isProdProfile() && !openingStockImportEnabled) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Opening stock import is disabled; enable migration mode to proceed.")
                    .withDetail("setting", "erp.inventory.opening-stock.enabled")
                    .withDetail("canonicalPath", "/api/v1/inventory/opening-stock");
        }
    }

    private boolean isProdProfile() {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
    }

    private String normalizeIdempotencyKey(String idempotencyKey, String fileHash) {
        String trimmed = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
        String resolved = StringUtils.hasText(trimmed) ? trimmed : fileHash;
        if (!StringUtils.hasText(resolved)) {
            throw new ApplicationException(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
                    "Idempotency key is required for opening stock imports");
        }
        if (resolved.length() > 128) {
            throw new ApplicationException(ErrorCode.VALIDATION_INVALID_INPUT,
                    "Idempotency key exceeds 128 characters");
        }
        return resolved;
    }

    private void assertIdempotencyMatch(OpeningStockImport record, String expectedHash, String idempotencyKey) {
        String storedSignature = StringUtils.hasText(record.getIdempotencyHash())
                ? record.getIdempotencyHash()
                : record.getFileHash();
        if (StringUtils.hasText(storedSignature)) {
            if (!storedSignature.equals(expectedHash)) {
                throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                        "Idempotency key already used with different payload")
                        .withDetail("idempotencyKey", idempotencyKey);
            }
            return;
        }
        record.setIdempotencyHash(expectedHash);
        openingStockImportRepository.save(record);
    }

    private OpeningStockImportResponse toResponse(OpeningStockImport record) {
        List<ImportError> errors = deserializeErrors(record.getErrorsJson());
        return new OpeningStockImportResponse(
                record.getRowsProcessed(),
                record.getRawMaterialsCreated(),
                record.getRawMaterialBatchesCreated(),
                record.getFinishedGoodsCreated(),
                record.getFinishedGoodBatchesCreated(),
                errors
        );
    }

    private String serializeErrors(List<ImportError> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<ImportError> deserializeErrors(String errorsJson) {
        if (!StringUtils.hasText(errorsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(errorsJson, new TypeReference<List<ImportError>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private boolean isDataIntegrityViolation(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof DataIntegrityViolationException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private String resolveImportReference(Company company, String fileHash) {
        String companyCode = sanitizeCompanyCode(company != null ? company.getCode() : null);
        String shortHash = StringUtils.hasText(fileHash) ? fileHash.substring(0, Math.min(12, fileHash.length())) : "UNKNOWN";
        return "OPEN-STOCK-%s-%s".formatted(companyCode, shortHash);
    }

    private String sanitizeCompanyCode(String code) {
        if (!StringUtils.hasText(code)) {
            return "COMPANY";
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return normalized.isBlank() ? "COMPANY" : normalized;
    }

    private String resolveFileHash(MultipartFile file) {
        try {
            return IdempotencyUtils.sha256Hex(file.getBytes());
        } catch (Exception ex) {
            // Fallback: stable-ish hash for common IO failures (still protects against accidental retries)
            return Integer.toHexString(file.getOriginalFilename() != null ? file.getOriginalFilename().hashCode() : 0);
        }
    }

    private OpeningMovementResult handleRawMaterial(Company company, OpeningRow row) {
        RawMaterial material = resolveRawMaterial(company, row);
        String unit = firstNonBlank(row.unitType, row.unit, material.getUnitType());
        if (!StringUtils.hasText(unit)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Unit is required for raw material " + row.displayKey());
        }
        BigDecimal quantity = requirePositive(row.quantity, "quantity");
        BigDecimal unitCost = requirePositive(row.unitCost, "unit_cost");
        Long inventoryAccountId = resolveInventoryAccountId(company, material);
        String batchCode = resolveRawMaterialBatchCode(material, row.batchCode);

        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(material);
        batch.setBatchCode(batchCode);
        batch.setQuantity(quantity);
        batch.setUnit(unit);
        batch.setCostPerUnit(unitCost);
        batch.setSupplierName(DEFAULT_BATCH_REF);
        RawMaterialBatch savedBatch = rawMaterialBatchRepository.save(batch);

        BigDecimal currentStock = Optional.ofNullable(material.getCurrentStock()).orElse(BigDecimal.ZERO);
        material.setCurrentStock(currentStock.add(quantity));
        rawMaterialRepository.save(material);

        RawMaterialMovement movement = new RawMaterialMovement();
        movement.setRawMaterial(material);
        movement.setRawMaterialBatch(savedBatch);
        movement.setReferenceType(InventoryReference.OPENING_STOCK);
        movement.setReferenceId(batchCode);
        movement.setMovementType("RECEIPT");
        movement.setQuantity(quantity);
        movement.setUnitCost(unitCost);
        RawMaterialMovement savedMovement = rawMaterialMovementRepository.save(movement);

        return new OpeningMovementResult(
                inventoryAccountId,
                MoneyUtils.safeMultiply(quantity, unitCost),
                savedMovement,
                null);
    }

    private OpeningMovementResult handleFinishedGood(Company company, OpeningRow row) {
        FinishedGood finishedGood = resolveFinishedGood(company, row);
        BigDecimal quantity = requirePositive(row.quantity, "quantity");
        BigDecimal unitCost = requirePositive(row.unitCost, "unit_cost");
        Long inventoryAccountId = resolveInventoryAccountId(company, finishedGood);
        Instant manufacturedAt = row.manufacturedDate != null
                ? row.manufacturedDate.atStartOfDay(resolveZone(company)).toInstant()
                : null;
        String batchCode = StringUtils.hasText(row.batchCode)
                ? row.batchCode.trim()
                : batchNumberService.nextFinishedGoodBatchCode(finishedGood, row.manufacturedDate);

        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode(batchCode);
        batch.setQuantityTotal(quantity);
        batch.setQuantityAvailable(quantity);
        batch.setUnitCost(unitCost);
        batch.setManufacturedAt(manufacturedAt != null ? manufacturedAt : CompanyTime.now(company));
        FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(batch);

        BigDecimal currentStock = Optional.ofNullable(finishedGood.getCurrentStock()).orElse(BigDecimal.ZERO);
        finishedGood.setCurrentStock(currentStock.add(quantity));
        finishedGoodRepository.save(finishedGood);

        InventoryMovement movement = new InventoryMovement();
        movement.setFinishedGood(finishedGood);
        movement.setFinishedGoodBatch(savedBatch);
        movement.setReferenceType(InventoryReference.OPENING_STOCK);
        movement.setReferenceId(batchCode);
        movement.setMovementType("RECEIPT");
        movement.setQuantity(quantity);
        movement.setUnitCost(unitCost);
        InventoryMovement savedMovement = inventoryMovementRepository.save(movement);

        return new OpeningMovementResult(
                inventoryAccountId,
                MoneyUtils.safeMultiply(quantity, unitCost),
                null,
                savedMovement);
    }

    private String resolveRawMaterialBatchCode(RawMaterial material, String requested) {
        if (StringUtils.hasText(requested)) {
            String trimmed = requested.trim();
            ensureRawMaterialBatchCodeUnique(material, trimmed);
            return trimmed;
        }
        return nextUniqueRawMaterialBatchCode(material);
    }

    private String nextUniqueRawMaterialBatchCode(RawMaterial material) {
        String candidate = batchNumberService.nextRawMaterialBatchCode(material);
        int attempts = 0;
        while (rawMaterialBatchRepository.existsByRawMaterialAndBatchCode(material, candidate)) {
            if (attempts++ > 10) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Unable to allocate unique batch code for raw material "
                        + describeMaterial(material));
            }
            candidate = batchNumberService.nextRawMaterialBatchCode(material);
        }
        return candidate;
    }

    private void ensureRawMaterialBatchCodeUnique(RawMaterial material, String batchCode) {
        if (rawMaterialBatchRepository.existsByRawMaterialAndBatchCode(material, batchCode)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Batch code already exists for raw material "
                    + describeMaterial(material) + ": " + batchCode);
        }
    }

    private String describeMaterial(RawMaterial material) {
        if (StringUtils.hasText(material.getSku())) {
            return material.getSku();
        }
        if (StringUtils.hasText(material.getName())) {
            return material.getName();
        }
        return material.getId() != null ? material.getId().toString() : "unknown";
    }

    private RawMaterial resolveRawMaterial(Company company, OpeningRow row) {
        if (StringUtils.hasText(row.sku)) {
            Optional<RawMaterial> existing = rawMaterialRepository.findByCompanyAndSku(company, row.sku.trim());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        if (!StringUtils.hasText(row.name)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Raw material name is required when SKU is missing: " + row.displayKey());
        }
        String unitType = firstNonBlank(row.unitType, row.unit);
        if (!StringUtils.hasText(unitType)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Unit type is required for raw material " + row.displayKey());
        }
        RawMaterialRequest request = new RawMaterialRequest(
                row.name.trim(),
                StringUtils.hasText(row.sku) ? row.sku.trim() : null,
                unitType.trim(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null
        );
        RawMaterialDto created = rawMaterialService.createRawMaterial(request);
        RawMaterial material = rawMaterialRepository.findByCompanyAndId(company, created.id())
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Raw material creation failed"));
        if (row.materialType != null) {
            material.setMaterialType(row.materialType);
            rawMaterialRepository.save(material);
        }
        row.markCreated();
        return material;
    }

    private FinishedGood resolveFinishedGood(Company company, OpeningRow row) {
        if (!StringUtils.hasText(row.sku)) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Finished good SKU is required");
        }
        Optional<FinishedGood> existing = finishedGoodRepository.findByCompanyAndProductCode(company, row.sku.trim());
        if (existing.isPresent()) {
            return existing.get();
        }
        String name = StringUtils.hasText(row.name) ? row.name.trim() : row.sku.trim();
        String unit = StringUtils.hasText(row.unit) ? row.unit.trim() : "PCS";
        FinishedGoodRequest request = new FinishedGoodRequest(
                row.sku.trim(),
                name,
                unit,
                null,
                null,
                null,
                null,
                null,
                null
        );
        FinishedGoodDto created = finishedGoodsService.createFinishedGood(request);
        FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndId(company, created.id())
                .orElseThrow(() -> com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Finished good creation failed"));
        row.markCreated();
        return finishedGood;
    }

    private static BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(field + " must be greater than zero");
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static ZoneId resolveZone(Company company) {
        return ZoneId.of(company.getTimezone() == null ? "UTC" : company.getTimezone());
    }

    private Long resolveInventoryAccountId(Company company, RawMaterial material) {
        Long accountId = material.getInventoryAccountId();
        if (accountId == null) {
            accountId = company.getDefaultInventoryAccountId();
            if (accountId != null) {
                material.setInventoryAccountId(accountId);
            }
        }
        if (accountId == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Raw material " + material.getName() + " is missing an inventory account");
        }
        return accountId;
    }

    private Long resolveInventoryAccountId(Company company, FinishedGood finishedGood) {
        Long accountId = finishedGood.getValuationAccountId();
        if (accountId == null) {
            accountId = company.getDefaultInventoryAccountId();
            if (accountId != null) {
                finishedGood.setValuationAccountId(accountId);
            }
        }
        if (accountId == null) {
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Finished good " + finishedGood.getProductCode() + " is missing a valuation account");
        }
        return accountId;
    }

    private Long postOpeningStockJournal(Company company, Map<Long, BigDecimal> inventoryTotals, String reference) {
        if (inventoryTotals == null || inventoryTotals.isEmpty()) {
            return null;
        }
        Map<Long, BigDecimal> filteredLines = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        List<Map.Entry<Long, BigDecimal>> sortedEntries = inventoryTotals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (Map.Entry<Long, BigDecimal> entry : sortedEntries) {
            BigDecimal amount = entry.getValue() == null ? BigDecimal.ZERO : entry.getValue();
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            total = total.add(amount);
            filteredLines.put(entry.getKey(), amount);
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0 || filteredLines.isEmpty()) {
            return null;
        }
        Account openingBalance = resolveOpeningBalanceAccount(company);
        JournalEntryDto journalEntry = accountingFacade.postInventoryAdjustment(
                "OPENING_STOCK",
                reference,
                openingBalance.getId(),
                filteredLines,
                true,
                false,
                "Opening stock import",
                companyClock.today(company));
        return journalEntry != null ? journalEntry.id() : null;
    }

    private Account resolveOpeningBalanceAccount(Company company) {
        Account existing = accountRepository.findByCompanyAndCodeIgnoreCase(company, "OPEN-BAL").orElse(null);
        if (existing != null) {
            if (existing.getType() != AccountType.EQUITY) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidState("Opening balance account OPEN-BAL must be an equity account");
            }
            return existing;
        }
        Account account = new Account();
        account.setCompany(company);
        account.setCode("OPEN-BAL");
        account.setName("Opening Balance");
        account.setType(AccountType.EQUITY);
        return accountRepository.save(account);
    }

    private enum StockType {
        RAW_MATERIAL,
        FINISHED_GOOD
    }

    private record OpeningMovementResult(Long inventoryAccountId,
                                         BigDecimal amount,
                                         RawMaterialMovement rawMovement,
                                         InventoryMovement inventoryMovement) {}

    private record ImportResult(OpeningStockImportResponse response, Long journalEntryId) {}

    private static final class OpeningRow {
        private final StockType type;
        private final String sku;
        private final String name;
        private final String unit;
        private final String unitType;
        private final String batchCode;
        private final BigDecimal quantity;
        private final BigDecimal unitCost;
        private final MaterialType materialType;
        private final LocalDate manufacturedDate;
        private boolean createdNew;

        private OpeningRow(StockType type,
                           String sku,
                           String name,
                           String unit,
                           String unitType,
                           String batchCode,
                           BigDecimal quantity,
                           BigDecimal unitCost,
                           MaterialType materialType,
                           LocalDate manufacturedDate) {
            this.type = type;
            this.sku = sku;
            this.name = name;
            this.unit = unit;
            this.unitType = unitType;
            this.batchCode = batchCode;
            this.quantity = quantity;
            this.unitCost = unitCost;
            this.materialType = materialType;
            this.manufacturedDate = manufacturedDate;
        }

        static OpeningRow from(CSVRecord record) {
            String typeValue = readValue(record, "type", "item_type");
            String sku = readValue(record, "sku", "product_code", "sku_code");
            String name = readValue(record, "name", "product_name");
            String unit = readValue(record, "unit", "unit_of_measure");
            String unitType = readValue(record, "unit_type");
            String batchCode = readValue(record, "batch_code", "batch");
            BigDecimal quantity = decimal(record, "quantity", "qty");
            BigDecimal unitCost = decimal(record, "unit_cost", "cost_per_unit", "cost");
            String materialTypeRaw = readValue(record, "material_type");
            MaterialType materialType = parseMaterialType(materialTypeRaw);
            LocalDate manufacturedDate = date(record, "manufactured_at", "batch_date");

            if (!StringUtils.hasText(typeValue) && !StringUtils.hasText(sku) && !StringUtils.hasText(name)) {
                return null;
            }
            StockType type = parseType(typeValue);
            return new OpeningRow(type, sku, name, unit, unitType, batchCode, quantity, unitCost, materialType, manufacturedDate);
        }

        String displayKey() {
            String id = StringUtils.hasText(sku) ? sku : name;
            return StringUtils.hasText(id) ? id : "row";
        }

        void markCreated() {
            this.createdNew = true;
        }

        private static StockType parseType(String value) {
            if (!StringUtils.hasText(value)) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("type is required (RAW_MATERIAL or FINISHED_GOOD)");
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("RAW") || normalized.equals("RM") || normalized.equals("RAW_MATERIAL")) {
                return StockType.RAW_MATERIAL;
            }
            if (normalized.startsWith("FINISH") || normalized.equals("FG") || normalized.equals("FINISHED_GOOD")) {
                return StockType.FINISHED_GOOD;
            }
            throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Unknown type: " + value);
        }

        private static MaterialType parseMaterialType(String value) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "PACKAGING" -> MaterialType.PACKAGING;
                case "PRODUCTION" -> MaterialType.PRODUCTION;
                default -> throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Unknown material_type: " + value);
            };
        }

        private static String readValue(CSVRecord record, String... keys) {
            Map<String, String> map = record.toMap();
            for (String key : keys) {
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                        String value = entry.getValue();
                        return StringUtils.hasText(value) ? value.trim() : null;
                    }
                }
            }
            return null;
        }

        private static BigDecimal decimal(CSVRecord record, String... keys) {
            String value = readValue(record, keys);
            if (!StringUtils.hasText(value)) {
                return null;
            }
            try {
                return new BigDecimal(value.trim());
            } catch (NumberFormatException ex) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid numeric value: " + value);
            }
        }

        private static LocalDate date(CSVRecord record, String... keys) {
            String value = readValue(record, keys);
            if (!StringUtils.hasText(value)) {
                return null;
            }
            try {
                return LocalDate.parse(value.trim());
            } catch (Exception ex) {
                throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput("Invalid date value: " + value + " (expected YYYY-MM-DD)");
            }
        }
    }
}
