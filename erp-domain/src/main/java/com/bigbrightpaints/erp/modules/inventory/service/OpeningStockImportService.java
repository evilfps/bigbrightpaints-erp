package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.MoneyUtils;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
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
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse.ImportError;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialDto;
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
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final AccountingService accountingService;
    private final AccountRepository accountRepository;
    private final ReferenceNumberService referenceNumberService;
    private final CompanyClock companyClock;

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
                                     AccountingService accountingService,
                                     AccountRepository accountRepository,
                                     ReferenceNumberService referenceNumberService,
                                     CompanyClock companyClock) {
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
        this.accountingService = accountingService;
        this.accountRepository = accountRepository;
        this.referenceNumberService = referenceNumberService;
        this.companyClock = companyClock;
    }

    @Transactional
    public OpeningStockImportResponse importOpeningStock(MultipartFile file) {
        Company company = companyContextService.requireCurrentCompany();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("CSV file is required");
        }

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

            Long journalEntryId = postOpeningStockJournal(company, inventoryTotals);
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

            return new OpeningStockImportResponse(
                    rowsProcessed,
                    rawMaterialsCreated,
                    rawMaterialBatchesCreated,
                    finishedGoodsCreated,
                    finishedGoodBatchesCreated,
                    errors
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read CSV file", ex);
        }
    }

    private OpeningMovementResult handleRawMaterial(Company company, OpeningRow row) {
        RawMaterial material = resolveRawMaterial(company, row);
        String unit = firstNonBlank(row.unitType, row.unit, material.getUnitType());
        if (!StringUtils.hasText(unit)) {
            throw new IllegalArgumentException("Unit is required for raw material " + row.displayKey());
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
        batch.setManufacturedAt(manufacturedAt != null ? manufacturedAt : Instant.now());
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
                throw new IllegalStateException("Unable to allocate unique batch code for raw material "
                        + describeMaterial(material));
            }
            candidate = batchNumberService.nextRawMaterialBatchCode(material);
        }
        return candidate;
    }

    private void ensureRawMaterialBatchCodeUnique(RawMaterial material, String batchCode) {
        if (rawMaterialBatchRepository.existsByRawMaterialAndBatchCode(material, batchCode)) {
            throw new IllegalArgumentException("Batch code already exists for raw material "
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
            throw new IllegalArgumentException("Raw material name is required when SKU is missing: " + row.displayKey());
        }
        String unitType = firstNonBlank(row.unitType, row.unit);
        if (!StringUtils.hasText(unitType)) {
            throw new IllegalArgumentException("Unit type is required for raw material " + row.displayKey());
        }
        RawMaterialRequest request = new RawMaterialRequest(
                row.name.trim(),
                StringUtils.hasText(row.sku) ? row.sku.trim() : null,
                unitType.trim(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null
        );
        RawMaterialDto created = rawMaterialService.createRawMaterial(request);
        RawMaterial material = rawMaterialRepository.findByCompanyAndId(company, created.id())
                .orElseThrow(() -> new IllegalStateException("Raw material creation failed"));
        if (row.materialType != null) {
            material.setMaterialType(row.materialType);
            rawMaterialRepository.save(material);
        }
        row.markCreated();
        return material;
    }

    private FinishedGood resolveFinishedGood(Company company, OpeningRow row) {
        if (!StringUtils.hasText(row.sku)) {
            throw new IllegalArgumentException("Finished good SKU is required");
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
                .orElseThrow(() -> new IllegalStateException("Finished good creation failed"));
        row.markCreated();
        return finishedGood;
    }

    private static BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero");
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
            throw new IllegalStateException("Raw material " + material.getName() + " is missing an inventory account");
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
            throw new IllegalStateException("Finished good " + finishedGood.getProductCode() + " is missing a valuation account");
        }
        return accountId;
    }

    private Long postOpeningStockJournal(Company company, Map<Long, BigDecimal> inventoryTotals) {
        if (inventoryTotals == null || inventoryTotals.isEmpty()) {
            return null;
        }
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
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
            lines.add(new JournalEntryRequest.JournalLineRequest(
                    entry.getKey(),
                    "Opening stock import",
                    amount,
                    BigDecimal.ZERO));
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        Account openingBalance = resolveOpeningBalanceAccount(company);
        lines.add(new JournalEntryRequest.JournalLineRequest(
                openingBalance.getId(),
                "Opening stock offset",
                BigDecimal.ZERO,
                total));
        String reference = referenceNumberService.openingStockReference(company);
        JournalEntryDto journalEntry = accountingService.createJournalEntry(new JournalEntryRequest(
                reference,
                companyClock.today(company),
                "Opening stock import",
                null,
                null,
                Boolean.FALSE,
                lines));
        return journalEntry.id();
    }

    private Account resolveOpeningBalanceAccount(Company company) {
        Account existing = accountRepository.findByCompanyAndCodeIgnoreCase(company, "OPEN-BAL").orElse(null);
        if (existing != null) {
            if (existing.getType() != AccountType.EQUITY) {
                throw new IllegalStateException("Opening balance account OPEN-BAL must be an equity account");
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
                throw new IllegalArgumentException("type is required (RAW_MATERIAL or FINISHED_GOOD)");
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("RAW") || normalized.equals("RM") || normalized.equals("RAW_MATERIAL")) {
                return StockType.RAW_MATERIAL;
            }
            if (normalized.startsWith("FINISH") || normalized.equals("FG") || normalized.equals("FINISHED_GOOD")) {
                return StockType.FINISHED_GOOD;
            }
            throw new IllegalArgumentException("Unknown type: " + value);
        }

        private static MaterialType parseMaterialType(String value) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "PACKAGING" -> MaterialType.PACKAGING;
                case "PRODUCTION" -> MaterialType.PRODUCTION;
                default -> throw new IllegalArgumentException("Unknown material_type: " + value);
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
                throw new IllegalArgumentException("Invalid numeric value: " + value);
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
                throw new IllegalArgumentException("Invalid date value: " + value + " (expected YYYY-MM-DD)");
            }
        }
    }
}
