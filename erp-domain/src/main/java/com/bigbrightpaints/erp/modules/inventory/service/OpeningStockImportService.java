package com.bigbrightpaints.erp.modules.inventory.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
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

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.core.validation.ValidationUtils;
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
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.OpeningStockImport;
import com.bigbrightpaints.erp.modules.inventory.domain.OpeningStockImportRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportHistoryItem;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse.ImportError;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse.ImportRowResult;
import com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto;
import com.bigbrightpaints.erp.modules.production.service.SkuReadinessService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;

@Service
public class OpeningStockImportService {

  private static final String DEFAULT_BATCH_REF = "OPENING";
  private static final int MAX_HISTORY_PAGE_SIZE = 100;
  private static final long LEGACY_MANUFACTURED_AT_TOLERANCE_SECONDS = 300L;

  private final CompanyContextService companyContextService;
  private final RawMaterialRepository rawMaterialRepository;
  private final RawMaterialBatchRepository rawMaterialBatchRepository;
  private final RawMaterialMovementRepository rawMaterialMovementRepository;
  private final FinishedGoodRepository finishedGoodRepository;
  private final FinishedGoodBatchRepository finishedGoodBatchRepository;
  private final InventoryMovementRepository inventoryMovementRepository;
  private final SkuReadinessService skuReadinessService;
  private final BatchNumberService batchNumberService;
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

  public OpeningStockImportService(
      CompanyContextService companyContextService,
      RawMaterialRepository rawMaterialRepository,
      RawMaterialBatchRepository rawMaterialBatchRepository,
      RawMaterialMovementRepository rawMaterialMovementRepository,
      FinishedGoodRepository finishedGoodRepository,
      FinishedGoodBatchRepository finishedGoodBatchRepository,
      InventoryMovementRepository inventoryMovementRepository,
      SkuReadinessService skuReadinessService,
      BatchNumberService batchNumberService,
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
    this.skuReadinessService = skuReadinessService;
    this.batchNumberService = batchNumberService;
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

  public OpeningStockImportResponse importOpeningStock(
      MultipartFile file, String idempotencyKey, String openingStockBatchKey) {
    Company company = companyContextService.requireCurrentCompany();
    if (file == null || file.isEmpty()) {
      throw ValidationUtils.invalidInput("CSV file is required");
    }
    String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
    String normalizedBatchKey = normalizeOpeningStockBatchKey(openingStockBatchKey);
    String importReference = resolveImportReference(company, normalizedBatchKey);

    OpeningStockImport existing =
        openingStockImportRepository
            .findByCompanyAndIdempotencyKey(company, normalizedKey)
            .orElse(null);
    if (existing != null) {
      assertReplayMatch(existing, normalizedKey, normalizedBatchKey);
      return toResponse(existing);
    }

    OpeningStockImport batchKeyConflict =
        openingStockImportRepository
            .findByCompanyAndOpeningStockBatchKey(company, normalizedBatchKey)
            .orElse(null);
    if (batchKeyConflict != null) {
      throw openingStockReplayConflict(batchKeyConflict, normalizedKey, normalizedBatchKey);
    }

    assertImportAllowed();

    if (journalEntryRepository
        .findByCompanyAndReferenceNumber(company, importReference)
        .isPresent()) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_DUPLICATE_ENTRY,
              "Opening stock batch already processed for this openingStockBatchKey")
          .withDetail("openingStockBatchKey", normalizedBatchKey)
          .withDetail("referenceNumber", importReference)
          .withDetail(
              "operatorAction",
              "Reverse the prior opening stock using the provided referenceNumber, then import a"
                  + " new opening stock batch using a distinct openingStockBatchKey and"
                  + " Idempotency-Key.");
    }

    String contentFingerprint = fingerprintFile(file);
    OpeningStockImport contentReplay = findContentReplay(company, contentFingerprint);
    if (contentReplay != null
        && !normalizedBatchKey.equals(contentReplay.getOpeningStockBatchKey())) {
      throw openingStockContentReplayConflict(contentReplay, normalizedKey, normalizedBatchKey);
    }

    try {
      OpeningStockImportResponse response =
          transactionTemplate.execute(
              status ->
                  importOpeningStockInternal(
                      company,
                      file,
                      normalizedKey,
                      normalizedBatchKey,
                      contentFingerprint,
                      importReference));
      if (response == null) {
        throw ValidationUtils.invalidState("Opening stock import failed to return a response");
      }
      return response;
    } catch (RuntimeException ex) {
      if (!isDataIntegrityViolation(ex)) {
        throw ex;
      }
      OpeningStockImport concurrent =
          openingStockImportRepository
              .findByCompanyAndIdempotencyKey(company, normalizedKey)
              .orElse(null);
      if (concurrent == null) {
        OpeningStockImport concurrentBatchKey =
            openingStockImportRepository
                .findByCompanyAndOpeningStockBatchKey(company, normalizedBatchKey)
                .orElse(null);
        if (concurrentBatchKey != null) {
          throw openingStockReplayConflict(concurrentBatchKey, normalizedKey, normalizedBatchKey);
        }
        throw ex;
      }
      assertReplayMatch(concurrent, normalizedKey, normalizedBatchKey);
      return toResponse(concurrent);
    }
  }

  public PageResponse<OpeningStockImportHistoryItem> listImportHistory(int page, int size) {
    Company company = companyContextService.requireCurrentCompany();
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), MAX_HISTORY_PAGE_SIZE);
    PageRequest pageable =
        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    Page<OpeningStockImport> historyPage =
        openingStockImportRepository.findByCompany(company, pageable);
    List<OpeningStockImportHistoryItem> items =
        historyPage.getContent().stream().map(this::toHistoryItem).toList();
    return PageResponse.of(items, historyPage.getTotalElements(), safePage, safeSize);
  }

  private OpeningStockImportResponse importOpeningStockInternal(
      Company company,
      MultipartFile file,
      String idempotencyKey,
      String openingStockBatchKey,
      String contentFingerprint,
      String importReference) {
    OpeningStockImport record = new OpeningStockImport();
    record.setCompany(company);
    record.setIdempotencyKey(idempotencyKey);
    record.setReferenceNumber(importReference);
    record.setOpeningStockBatchKey(openingStockBatchKey);
    record.setFileName(file.getOriginalFilename());
    record.setContentFingerprint(contentFingerprint);
    record = openingStockImportRepository.saveAndFlush(record);

    ImportResult result = processImport(company, file, importReference, openingStockBatchKey);
    OpeningStockImportResponse response = result.response();

    record.setRowsProcessed(response.rowsProcessed());
    record.setRawMaterialBatchesCreated(response.rawMaterialBatchesCreated());
    record.setFinishedGoodBatchesCreated(response.finishedGoodBatchesCreated());
    record.setResultsJson(serializeResults(response.results()));
    record.setErrorsJson(serializeErrors(response.errors()));
    record.setJournalEntryId(result.journalEntryId());
    openingStockImportRepository.save(record);

    Map<String, String> auditMetadata = new HashMap<>();
    auditMetadata.put("operation", "opening-stock-import");
    auditMetadata.put("idempotencyKey", idempotencyKey);
    auditMetadata.put("openingStockBatchKey", openingStockBatchKey);
    auditMetadata.put("referenceNumber", importReference);
    auditMetadata.put("rowsProcessed", Integer.toString(response.rowsProcessed()));
    if (result.journalEntryId() != null) {
      auditMetadata.put("journalEntryId", result.journalEntryId().toString());
    }
    auditService.logSuccess(AuditEvent.DATA_CREATE, auditMetadata);
    return response;
  }

  private ImportResult processImport(
      Company company, MultipartFile file, String importReference, String openingStockBatchKey) {
    int rowsProcessed = 0;
    int rawMaterialBatchesCreated = 0;
    int finishedGoodBatchesCreated = 0;
    List<ImportRowResult> results = new ArrayList<>();
    List<ImportError> errors = new ArrayList<>();
    Map<Long, BigDecimal> inventoryTotals = new HashMap<>();
    List<RawMaterialMovement> rawMovements = new ArrayList<>();
    List<InventoryMovement> finishedMovements = new ArrayList<>();

    try (BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
        CSVParser parser =
            CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .build()
                .parse(reader)) {

      Map<String, Long> seenSkuRows = new HashMap<>();
      for (CSVRecord record : parser) {
        OpeningRow row;
        try {
          row = OpeningRow.from(record);
        } catch (ApplicationException ex) {
          errors.add(new ImportError(record.getRecordNumber(), ex.getMessage(), null, null, null));
          continue;
        }
        if (row == null) {
          continue;
        }
        String normalizedSku = normalizeSku(row.sku);
        if (normalizedSku != null) {
          Long firstSeenRow = seenSkuRows.putIfAbsent(normalizedSku, record.getRecordNumber());
          if (firstSeenRow != null) {
            errors.add(
                new ImportError(
                    record.getRecordNumber(),
                    "Duplicate SKU in import file: "
                        + row.sku
                        + " (first seen at row "
                        + firstSeenRow
                        + ")",
                    normalizedSku,
                    row.type.name(),
                    readinessFor(company, normalizedSku, row.type)));
            continue;
          }
        }
        try {
          OpeningMovementResult movementResult;
          if (row.type == StockType.RAW_MATERIAL || row.type == StockType.PACKAGING_RAW_MATERIAL) {
            movementResult = handleRawMaterial(company, row);
            rawMaterialBatchesCreated++;
          } else {
            movementResult = handleFinishedGood(company, row);
            finishedGoodBatchesCreated++;
          }
          inventoryTotals.merge(
              movementResult.inventoryAccountId(), movementResult.amount(), BigDecimal::add);
          if (movementResult.rawMovement() != null) {
            rawMovements.add(movementResult.rawMovement());
          }
          if (movementResult.inventoryMovement() != null) {
            finishedMovements.add(movementResult.inventoryMovement());
          }
          results.add(
              new ImportRowResult(
                  record.getRecordNumber(),
                  movementResult.sku(),
                  row.type.name(),
                  movementResult.readiness()));
          rowsProcessed++;
        } catch (ApplicationException ex) {
          String normalizedSkuForError = normalizeSku(row.sku);
          errors.add(
              new ImportError(
                  record.getRecordNumber(),
                  ex.getMessage(),
                  normalizedSkuForError,
                  row.type.name(),
                  readinessForError(company, normalizedSkuForError, row.type)));
        } catch (Exception ex) {
          String normalizedSkuForError = normalizeSku(row.sku);
          errors.add(
              new ImportError(
                  record.getRecordNumber(),
                  "Unexpected error: " + ex.getMessage(),
                  normalizedSkuForError,
                  row.type.name(),
                  readinessForError(company, normalizedSkuForError, row.type)));
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

      OpeningStockImportResponse response =
          new OpeningStockImportResponse(
              openingStockBatchKey,
              rowsProcessed,
              rawMaterialBatchesCreated,
              finishedGoodBatchesCreated,
              results,
              errors);
      return new ImportResult(response, journalEntryId);
    } catch (IOException ex) {
      throw ValidationUtils.invalidState("Failed to read CSV file", ex);
    }
  }

  private void assertImportAllowed() {
    if (isProdProfile() && !openingStockImportEnabled) {
      throw new ApplicationException(
              ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
              "Opening stock import is disabled; enable migration mode to proceed.")
          .withDetail("setting", "erp.inventory.opening-stock.enabled")
          .withDetail("canonicalPath", "/api/v1/inventory/opening-stock");
    }
  }

  private boolean isProdProfile() {
    return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
  }

  private String normalizeIdempotencyKey(String idempotencyKey) {
    String resolved = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
    if (!StringUtils.hasText(resolved)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "Idempotency key is required for opening stock imports");
    }
    if (resolved.length() > 128) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "Idempotency key exceeds 128 characters");
    }
    return resolved;
  }

  private String normalizeOpeningStockBatchKey(String openingStockBatchKey) {
    String resolved =
        StringUtils.hasText(openingStockBatchKey) ? openingStockBatchKey.trim() : null;
    if (!StringUtils.hasText(resolved)) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD,
          "openingStockBatchKey is required for opening stock imports");
    }
    if (resolved.length() > 128) {
      throw new ApplicationException(
          ErrorCode.VALIDATION_INVALID_INPUT, "openingStockBatchKey exceeds 128 characters");
    }
    return resolved;
  }

  private String normalizeSku(String sku) {
    return StringUtils.hasText(sku) ? sku.trim().toUpperCase(Locale.ROOT) : null;
  }

  private String fingerprintFile(MultipartFile file) {
    try (InputStream inputStream = file.getInputStream()) {
      String normalizedPayload = normalizeFilePayload(inputStream.readAllBytes());
      if (!StringUtils.hasText(normalizedPayload)) {
        throw ValidationUtils.invalidInput("CSV file is required");
      }
      List<String> fingerprintRows = parseFingerprintRows(normalizedPayload);
      if (fingerprintRows.isEmpty()) {
        return IdempotencyUtils.sha256Hex(normalizedPayload);
      }
      return fingerprintRows(fingerprintRows);
    } catch (IOException ex) {
      throw ValidationUtils.invalidState("Failed to read CSV file", ex);
    }
  }

  private OpeningStockImport findContentReplay(Company company, String contentFingerprint) {
    OpeningStockImport exactMatch =
        openingStockImportRepository
            .findFirstByCompanyAndContentFingerprintOrderByCreatedAtAscIdAsc(
                company, contentFingerprint)
            .orElse(null);
    if (exactMatch != null) {
      return exactMatch;
    }
    return findLegacyContentReplay(company, contentFingerprint);
  }

  private OpeningStockImport findLegacyContentReplay(Company company, String contentFingerprint) {
    List<OpeningStockImport> candidates =
        openingStockImportRepository.findByCompanyOrderByCreatedAtAscIdAsc(company);
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    for (OpeningStockImport candidate : candidates) {
      if (!isLegacyContentFingerprint(candidate)) {
        continue;
      }
      String rebuiltFingerprint = rebuildLegacyContentFingerprint(company, candidate);
      if (!StringUtils.hasText(rebuiltFingerprint)) {
        continue;
      }
      if (!rebuiltFingerprint.equals(candidate.getContentFingerprint())) {
        candidate.setContentFingerprint(rebuiltFingerprint);
        openingStockImportRepository.save(candidate);
      }
      if (rebuiltFingerprint.equals(contentFingerprint)) {
        return candidate;
      }
    }
    return null;
  }

  private boolean isLegacyContentFingerprint(OpeningStockImport record) {
    if (record == null || !StringUtils.hasText(record.getContentFingerprint())) {
      return false;
    }
    return record
        .getContentFingerprint()
        .equals(legacyContentFingerprint(record.getOpeningStockBatchKey(), record.getIdempotencyKey()));
  }

  private String legacyContentFingerprint(String openingStockBatchKey, String idempotencyKey) {
    String legacySeed =
        StringUtils.hasText(openingStockBatchKey)
            ? openingStockBatchKey
            : StringUtils.hasText(idempotencyKey) ? idempotencyKey : "";
    return IdempotencyUtils.sha256Hex(legacySeed);
  }

  private String rebuildLegacyContentFingerprint(Company company, OpeningStockImport record) {
    if (record == null || record.getJournalEntryId() == null) {
      return null;
    }
    List<String> fingerprintRows = new ArrayList<>();

    List<RawMaterialMovement> rawMovements =
        rawMaterialMovementRepository.findByRawMaterial_CompanyAndJournalEntryIdAndReferenceTypeOrderByIdAsc(
            company, record.getJournalEntryId(), InventoryReference.OPENING_STOCK);
    if (rawMovements != null) {
      for (RawMaterialMovement movement : rawMovements) {
        if (movement.getRawMaterial() == null) {
          continue;
        }
        RawMaterialBatch batch = movement.getRawMaterialBatch();
        StockType stockType =
            movement.getRawMaterial().getMaterialType()
                    == com.bigbrightpaints.erp.modules.inventory.domain.MaterialType.PACKAGING
                ? StockType.PACKAGING_RAW_MATERIAL
                : StockType.RAW_MATERIAL;
        fingerprintRows.add(
            fingerprintRow(
                stockType,
                movement.getRawMaterial().getSku(),
                movement.getQuantity(),
                movement.getUnitCost(),
                resolveLegacyManufacturedDate(company, record, batch),
                batch != null ? batch.getExpiryDate() : null));
      }
    }

    List<InventoryMovement> finishedMovements =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndJournalEntryIdAndReferenceTypeOrderByIdAsc(
                company, record.getJournalEntryId(), InventoryReference.OPENING_STOCK);
    if (finishedMovements != null) {
      for (InventoryMovement movement : finishedMovements) {
        if (movement.getFinishedGood() == null) {
          continue;
        }
        FinishedGoodBatch batch = movement.getFinishedGoodBatch();
        fingerprintRows.add(
            fingerprintRow(
                StockType.FINISHED_GOOD,
                movement.getFinishedGood().getProductCode(),
                movement.getQuantity(),
                movement.getUnitCost(),
                resolveLegacyManufacturedDate(company, record, batch),
                batch != null ? batch.getExpiryDate() : null));
      }
    }

    if (fingerprintRows.isEmpty()) {
      return null;
    }
    return fingerprintRows(fingerprintRows);
  }

  private LocalDate resolveLegacyManufacturedDate(
      Company company, OpeningStockImport record, RawMaterialBatch batch) {
    if (batch == null) {
      return null;
    }
    return resolveLegacyManufacturedDate(company, record, batch.getManufacturedAt());
  }

  private LocalDate resolveLegacyManufacturedDate(
      Company company, OpeningStockImport record, FinishedGoodBatch batch) {
    if (batch == null) {
      return null;
    }
    return resolveLegacyManufacturedDate(company, record, batch.getManufacturedAt());
  }

  private LocalDate resolveLegacyManufacturedDate(
      Company company, OpeningStockImport record, Instant manufacturedAt) {
    if (manufacturedAt == null) {
      return null;
    }
    Instant createdAt = record.getCreatedAt();
    if (createdAt != null) {
      Instant earliest = createdAt.minusSeconds(LEGACY_MANUFACTURED_AT_TOLERANCE_SECONDS);
      Instant latest = createdAt.plusSeconds(LEGACY_MANUFACTURED_AT_TOLERANCE_SECONDS);
      if (!manufacturedAt.isBefore(earliest) && !manufacturedAt.isAfter(latest)) {
        return null;
      }
    }
    return manufacturedAt.atZone(resolveZone(company)).toLocalDate();
  }

  private List<String> parseFingerprintRows(String normalizedPayload) throws IOException {
    List<String> fingerprintRows = new ArrayList<>();
    try (CSVParser parser =
        CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreHeaderCase(true)
            .setTrim(true)
            .build()
            .parse(new BufferedReader(new StringReader(normalizedPayload)))) {
      for (CSVRecord record : parser) {
        OpeningRow row;
        try {
          row = OpeningRow.from(record);
        } catch (ApplicationException ex) {
          continue;
        }
        if (row == null) {
          continue;
        }
        fingerprintRows.add(
            fingerprintRow(
                row.type,
                row.sku,
                row.quantity,
                row.unitCost,
                row.manufacturedDate,
                row.expiryDate));
      }
    }
    return fingerprintRows;
  }

  private String fingerprintRows(List<String> fingerprintRows) {
    List<String> normalizedRows = new ArrayList<>(fingerprintRows);
    normalizedRows.sort(String::compareTo);
    return IdempotencyUtils.sha256Hex(String.join("\n", normalizedRows));
  }

  private String fingerprintRow(
      StockType stockType,
      String sku,
      BigDecimal quantity,
      BigDecimal unitCost,
      LocalDate manufacturedDate,
      LocalDate expiryDate) {
    String normalizedSku = normalizeSku(sku);
    return String.join(
        "|",
        stockType.name(),
        normalizedSku == null ? "" : normalizedSku,
        normalizeDecimal(quantity),
        normalizeDecimal(unitCost),
        normalizeDate(manufacturedDate),
        normalizeDate(expiryDate));
  }

  private String normalizeDecimal(BigDecimal value) {
    if (value == null) {
      return "";
    }
    BigDecimal normalized = value.stripTrailingZeros();
    if (normalized.scale() < 0) {
      normalized = normalized.setScale(0);
    }
    return normalized.toPlainString();
  }

  private String normalizeDate(LocalDate value) {
    return value == null ? "" : value.toString();
  }

  private String normalizeFilePayload(byte[] bytes) {
    String payload = new String(bytes, StandardCharsets.UTF_8);
    if (!payload.isEmpty() && payload.charAt(0) == '\uFEFF') {
      payload = payload.substring(1);
    }
    return payload.replace("\r\n", "\n").replace('\r', '\n');
  }

  private void assertReplayMatch(
      OpeningStockImport record, String idempotencyKey, String openingStockBatchKey) {
    String storedBatchKey =
        StringUtils.hasText(record.getOpeningStockBatchKey())
            ? record.getOpeningStockBatchKey()
            : null;
    if (storedBatchKey != null && !storedBatchKey.equals(openingStockBatchKey)) {
      throw new ApplicationException(
              ErrorCode.CONCURRENCY_CONFLICT,
              "Idempotency key already used with different openingStockBatchKey")
          .withDetail("idempotencyKey", idempotencyKey)
          .withDetail("openingStockBatchKey", openingStockBatchKey)
          .withDetail("existingOpeningStockBatchKey", storedBatchKey);
    }
  }

  private OpeningStockImportResponse toResponse(OpeningStockImport record) {
    List<ImportRowResult> results = deserializeResults(record.getResultsJson());
    List<ImportError> errors = deserializeErrors(record.getErrorsJson());
    return new OpeningStockImportResponse(
        record.getOpeningStockBatchKey(),
        record.getRowsProcessed(),
        record.getRawMaterialBatchesCreated(),
        record.getFinishedGoodBatchesCreated(),
        results,
        errors);
  }

  private OpeningStockImportHistoryItem toHistoryItem(OpeningStockImport record) {
    List<ImportError> errors = deserializeErrors(record.getErrorsJson());
    return new OpeningStockImportHistoryItem(
        record.getId(),
        record.getIdempotencyKey(),
        record.getOpeningStockBatchKey(),
        record.getReferenceNumber(),
        record.getFileName(),
        record.getJournalEntryId(),
        record.getRowsProcessed(),
        record.getRawMaterialBatchesCreated(),
        record.getFinishedGoodBatchesCreated(),
        errors.size(),
        record.getCreatedAt());
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

  private String serializeResults(List<ImportRowResult> results) {
    if (results == null || results.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(results);
    } catch (Exception ex) {
      return null;
    }
  }

  private List<ImportRowResult> deserializeResults(String resultsJson) {
    if (!StringUtils.hasText(resultsJson)) {
      return List.of();
    }
    try {
      return objectMapper.readValue(resultsJson, new TypeReference<List<ImportRowResult>>() {});
    } catch (Exception ex) {
      return List.of();
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

  private String resolveImportReference(Company company, String openingStockBatchKey) {
    String companyCode = sanitizeCompanyCode(company != null ? company.getCode() : null);
    String referenceHash = IdempotencyUtils.sha256Hex(openingStockBatchKey);
    String shortHash = referenceHash.substring(0, Math.min(12, referenceHash.length()));
    return "OPEN-STOCK-%s-%s".formatted(companyCode, shortHash);
  }

  private ApplicationException openingStockReplayConflict(
      OpeningStockImport existing, String attemptedIdempotencyKey, String attemptedBatchKey) {
    return new ApplicationException(
            ErrorCode.BUSINESS_DUPLICATE_ENTRY,
            "Opening stock batch key already exists. Reuse the original Idempotency-Key to retry,"
                + " or reverse the prior opening stock before importing a materially distinct"
                + " batch.")
        .withDetail("existingIdempotencyKey", existing.getIdempotencyKey())
        .withDetail("openingStockBatchKey", existing.getOpeningStockBatchKey())
        .withDetail("referenceNumber", existing.getReferenceNumber())
        .withDetail("attemptedIdempotencyKey", attemptedIdempotencyKey)
        .withDetail("attemptedOpeningStockBatchKey", attemptedBatchKey)
        .withDetail(
            "operatorAction",
            "Reuse the original Idempotency-Key for a retry, or reverse the prior opening stock"
                + " before importing a distinct batch.");
  }

  private ApplicationException openingStockContentReplayConflict(
      OpeningStockImport existing, String attemptedIdempotencyKey, String attemptedBatchKey) {
    return new ApplicationException(
            ErrorCode.BUSINESS_DUPLICATE_ENTRY,
            "Opening stock file already imported. Reuse the original Idempotency-Key and"
                + " openingStockBatchKey to retry, or reverse the prior opening stock before"
                + " importing materially distinct content.")
        .withDetail("existingIdempotencyKey", existing.getIdempotencyKey())
        .withDetail("existingOpeningStockBatchKey", existing.getOpeningStockBatchKey())
        .withDetail("referenceNumber", existing.getReferenceNumber())
        .withDetail("attemptedIdempotencyKey", attemptedIdempotencyKey)
        .withDetail("attemptedOpeningStockBatchKey", attemptedBatchKey);
  }

  private String sanitizeCompanyCode(String code) {
    if (!StringUtils.hasText(code)) {
      return "COMPANY";
    }
    String normalized = code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    return normalized.isBlank() ? "COMPANY" : normalized;
  }

  private OpeningMovementResult handleRawMaterial(Company company, OpeningRow row) {
    String sku = requirePreparedSku(row);
    SkuReadinessDto readiness =
        requireOpeningStockReady(company, sku, row, expectedStockType(row.type));
    RawMaterial material =
        rawMaterialRepository
            .findByCompanyAndSku(company, sku)
            .orElseThrow(
                () ->
                    ValidationUtils.invalidState(
                        "Raw material mirror missing for prepared SKU " + sku));
    String unit = firstNonBlank(row.unitType, row.unit, material.getUnitType());
    if (!StringUtils.hasText(unit)) {
      throw ValidationUtils.invalidInput("Unit is required for raw material " + row.displayKey());
    }
    BigDecimal quantity = ValidationUtils.requirePositive(row.quantity, "quantity");
    BigDecimal unitCost = ValidationUtils.requirePositive(row.unitCost, "unit_cost");
    Long inventoryAccountId = resolveInventoryAccountId(material);
    String batchCode = resolveRawMaterialBatchCode(material, row.batchCode);

    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(material);
    batch.setBatchCode(batchCode);
    batch.setQuantity(quantity);
    batch.setUnit(unit);
    batch.setCostPerUnit(unitCost);
    batch.setSupplierName(DEFAULT_BATCH_REF);
    Instant manufacturedAt =
        row.manufacturedDate != null
            ? row.manufacturedDate.atStartOfDay(resolveZone(company)).toInstant()
            : CompanyTime.now(company);
    batch.setManufacturedAt(manufacturedAt);
    batch.setExpiryDate(row.expiryDate);
    batch.setSource(InventoryBatchSource.ADJUSTMENT);
    RawMaterialBatch savedBatch = rawMaterialBatchRepository.save(batch);

    BigDecimal currentStock =
        Optional.ofNullable(material.getCurrentStock()).orElse(BigDecimal.ZERO);
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
        sku,
        inventoryAccountId,
        MoneyUtils.safeMultiply(quantity, unitCost),
        readiness,
        savedMovement,
        null);
  }

  private OpeningMovementResult handleFinishedGood(Company company, OpeningRow row) {
    String sku = requirePreparedSku(row);
    SkuReadinessDto readiness =
        requireOpeningStockReady(
            company, sku, row, SkuReadinessService.ExpectedStockType.FINISHED_GOOD);
    FinishedGood finishedGood =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, sku)
            .orElseThrow(
                () ->
                    ValidationUtils.invalidState(
                        "Finished good mirror missing for prepared SKU " + sku));
    BigDecimal quantity = ValidationUtils.requirePositive(row.quantity, "quantity");
    BigDecimal unitCost = ValidationUtils.requirePositive(row.unitCost, "unit_cost");
    Long inventoryAccountId = resolveInventoryAccountId(finishedGood);
    Instant manufacturedAt =
        row.manufacturedDate != null
            ? row.manufacturedDate.atStartOfDay(resolveZone(company)).toInstant()
            : null;
    String batchCode =
        StringUtils.hasText(row.batchCode)
            ? row.batchCode.trim()
            : batchNumberService.nextFinishedGoodBatchCode(finishedGood, row.manufacturedDate);

    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(finishedGood);
    batch.setBatchCode(batchCode);
    batch.setQuantityTotal(quantity);
    batch.setQuantityAvailable(quantity);
    batch.setUnitCost(unitCost);
    batch.setManufacturedAt(manufacturedAt != null ? manufacturedAt : CompanyTime.now(company));
    batch.setExpiryDate(row.expiryDate);
    batch.setSource(InventoryBatchSource.ADJUSTMENT);
    FinishedGoodBatch savedBatch = finishedGoodBatchRepository.save(batch);

    BigDecimal currentStock =
        Optional.ofNullable(finishedGood.getCurrentStock()).orElse(BigDecimal.ZERO);
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
    SkuReadinessDto updatedReadiness =
        skuReadinessService.forSku(
            company, sku, SkuReadinessService.ExpectedStockType.FINISHED_GOOD);

    return new OpeningMovementResult(
        sku,
        inventoryAccountId,
        MoneyUtils.safeMultiply(quantity, unitCost),
        updatedReadiness,
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
        throw ValidationUtils.invalidState(
            "Unable to allocate unique batch code for raw material " + describeMaterial(material));
      }
      candidate = batchNumberService.nextRawMaterialBatchCode(material);
    }
    return candidate;
  }

  private void ensureRawMaterialBatchCodeUnique(RawMaterial material, String batchCode) {
    if (rawMaterialBatchRepository.existsByRawMaterialAndBatchCode(material, batchCode)) {
      throw ValidationUtils.invalidInput(
          "Batch code already exists for raw material "
              + describeMaterial(material)
              + ": "
              + batchCode);
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

  private String requirePreparedSku(OpeningRow row) {
    String sku = normalizeSku(row.sku);
    if (!StringUtils.hasText(sku)) {
      throw ValidationUtils.invalidInput(
          "SKU is required for opening stock; only prepared SKUs are accepted");
    }
    return sku;
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

  private SkuReadinessDto requireOpeningStockReady(
      Company company,
      String sku,
      OpeningRow row,
      SkuReadinessService.ExpectedStockType expectedStockType) {
    SkuReadinessDto readiness = skuReadinessService.forSku(company, sku, expectedStockType);
    if (!readiness.catalog().ready()) {
      throw openingStockReadinessFailure(
          sku, row, "catalog", readiness.catalog().blockers(), readiness);
    }
    if (!readiness.inventory().ready()) {
      throw openingStockReadinessFailure(
          sku, row, "inventory", readiness.inventory().blockers(), readiness);
    }
    return readiness;
  }

  private SkuReadinessDto readinessFor(Company company, String sku, StockType stockType) {
    return skuReadinessService.forSku(company, sku, expectedStockType(stockType));
  }

  private SkuReadinessService.ExpectedStockType expectedStockType(StockType stockType) {
    if (stockType == StockType.PACKAGING_RAW_MATERIAL) {
      return SkuReadinessService.ExpectedStockType.PACKAGING_RAW_MATERIAL;
    }
    if (stockType == StockType.RAW_MATERIAL) {
      return SkuReadinessService.ExpectedStockType.RAW_MATERIAL;
    }
    return SkuReadinessService.ExpectedStockType.FINISHED_GOOD;
  }

  private SkuReadinessDto readinessForError(Company company, String sku, StockType stockType) {
    if (!StringUtils.hasText(sku)) {
      return null;
    }
    return readinessFor(company, sku, stockType);
  }

  private ApplicationException openingStockReadinessFailure(
      String sku, OpeningRow row, String stage, List<String> blockers, SkuReadinessDto readiness) {
    String blockerText =
        blockers == null || blockers.isEmpty() ? "UNKNOWN" : String.join(", ", blockers);
    return new ApplicationException(
            ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
            "SKU " + sku + " is not " + stage + "-ready for opening stock: " + blockerText)
        .withDetail("sku", sku)
        .withDetail("stockType", row.type.name())
        .withDetail("stage", stage)
        .withDetail("blockers", blockers)
        .withDetail("readiness", readiness);
  }

  private Long resolveInventoryAccountId(RawMaterial material) {
    Long accountId = material.getInventoryAccountId();
    if (accountId == null) {
      throw ValidationUtils.invalidState(
          "Raw material " + material.getName() + " is missing an inventory account");
    }
    return accountId;
  }

  private Long resolveInventoryAccountId(FinishedGood finishedGood) {
    Long accountId = finishedGood.getValuationAccountId();
    if (accountId == null) {
      throw ValidationUtils.invalidState(
          "Finished good " + finishedGood.getProductCode() + " is missing a valuation account");
    }
    return accountId;
  }

  private Long postOpeningStockJournal(
      Company company, Map<Long, BigDecimal> inventoryTotals, String reference) {
    if (inventoryTotals == null || inventoryTotals.isEmpty()) {
      return null;
    }
    Map<Long, BigDecimal> filteredLines = new HashMap<>();
    BigDecimal total = BigDecimal.ZERO;
    List<Map.Entry<Long, BigDecimal>> sortedEntries =
        inventoryTotals.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
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
    JournalEntryDto journalEntry =
        accountingFacade.postInventoryAdjustment(
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
    Account existing =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "OPEN-BAL").orElse(null);
    if (existing != null) {
      if (existing.getType() != AccountType.EQUITY) {
        throw ValidationUtils.invalidState(
            "Opening balance account OPEN-BAL must be an equity account");
      }
      return existing;
    }
    throw ValidationUtils.invalidState(
        "Opening balance account OPEN-BAL is missing; complete company defaults and repair seeded"
            + " accounts before importing opening stock");
  }

  private enum StockType {
    RAW_MATERIAL,
    PACKAGING_RAW_MATERIAL,
    FINISHED_GOOD
  }

  private record OpeningMovementResult(
      String sku,
      Long inventoryAccountId,
      BigDecimal amount,
      SkuReadinessDto readiness,
      RawMaterialMovement rawMovement,
      InventoryMovement inventoryMovement) {}

  private record ImportResult(OpeningStockImportResponse response, Long journalEntryId) {}

  private static final class OpeningRow {
    private final StockType type;
    private final String sku;
    private final String unit;
    private final String unitType;
    private final String batchCode;
    private final BigDecimal quantity;
    private final BigDecimal unitCost;
    private final LocalDate manufacturedDate;
    private final LocalDate expiryDate;

    private OpeningRow(
        StockType type,
        String sku,
        String unit,
        String unitType,
        String batchCode,
        BigDecimal quantity,
        BigDecimal unitCost,
        LocalDate manufacturedDate,
        LocalDate expiryDate) {
      this.type = type;
      this.sku = sku;
      this.unit = unit;
      this.unitType = unitType;
      this.batchCode = batchCode;
      this.quantity = quantity;
      this.unitCost = unitCost;
      this.manufacturedDate = manufacturedDate;
      this.expiryDate = expiryDate;
    }

    static OpeningRow from(CSVRecord record) {
      String typeValue = readValue(record, "type", "item_type");
      String sku = readValue(record, "sku", "product_code", "sku_code");
      String unit = readValue(record, "unit", "unit_of_measure");
      String unitType = readValue(record, "unit_type");
      String batchCode = readValue(record, "batch_code", "batch");
      BigDecimal quantity = decimal(record, "quantity", "qty");
      BigDecimal unitCost = decimal(record, "unit_cost", "cost_per_unit", "cost");
      LocalDate manufacturedDate =
          date(record, "manufactured_at", "manufacturing_date", "manufacturingDate", "batch_date");
      LocalDate expiryDate = date(record, "expiry_date", "expiryDate", "expiry");

      if (!StringUtils.hasText(typeValue) && !StringUtils.hasText(sku)) {
        return null;
      }
      StockType type = parseType(typeValue);
      return new OpeningRow(
          type, sku, unit, unitType, batchCode, quantity, unitCost, manufacturedDate, expiryDate);
    }

    String displayKey() {
      return StringUtils.hasText(sku) ? sku : "row";
    }

    private static StockType parseType(String value) {
      if (!StringUtils.hasText(value)) {
        throw ValidationUtils.invalidInput(
            "type is required (RAW_MATERIAL, PACKAGING_RAW_MATERIAL, or FINISHED_GOOD)");
      }
      String normalized = value.trim().toUpperCase(Locale.ROOT);
      if (normalized.startsWith("PACK")
          || normalized.equals("PKG")
          || normalized.equals("PACKAGING_RAW_MATERIAL")) {
        return StockType.PACKAGING_RAW_MATERIAL;
      }
      if (normalized.startsWith("RAW")
          || normalized.equals("RM")
          || normalized.equals("RAW_MATERIAL")) {
        return StockType.RAW_MATERIAL;
      }
      if (normalized.startsWith("FINISH")
          || normalized.equals("FG")
          || normalized.equals("FINISHED_GOOD")) {
        return StockType.FINISHED_GOOD;
      }
      throw ValidationUtils.invalidInput("Unknown type: " + value);
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
        throw ValidationUtils.invalidInput("Invalid numeric value: " + value);
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
        throw ValidationUtils.invalidInput(
            "Invalid date value: " + value + " (expected YYYY-MM-DD)");
      }
    }
  }
}
