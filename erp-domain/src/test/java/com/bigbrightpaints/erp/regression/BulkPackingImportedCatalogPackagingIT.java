package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.factory.service.BulkPackingService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Bulk pack accounting links for catalog-imported packaging materials")
class BulkPackingImportedCatalogPackagingIT extends AbstractIntegrationTest {

    @Autowired private AccountRepository accountRepository;
    @Autowired private ProductionCatalogService productionCatalogService;
    @Autowired private BulkPackingService bulkPackingService;
    @Autowired private RawMaterialRepository rawMaterialRepository;
    @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;

    private Company company;
    private Account bulkInventoryAccount;
    private Account childInventoryAccount;
    private Account packagingInventoryAccount;
    private String companyCode;

    @BeforeEach
    void setUp() {
        companyCode = "M13-S2-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        bulkInventoryAccount = ensureAccount("INV-BULK-M13S2", "Bulk Inventory", AccountType.ASSET);
        childInventoryAccount = ensureAccount("INV-CHILD-M13S2", "Child Inventory", AccountType.ASSET);
        packagingInventoryAccount = ensureAccount("INV-PACK-M13S2", "Packaging Inventory", AccountType.ASSET);
    }

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    void bulkPack_replayDoesNotDuplicateImportedPackagingAccountingMovements() {
        CatalogImportResponse importResponse = productionCatalogService.importCatalog(
                rawMaterialCsvWithAccountAlias("PACK-RM-M13S2", "18.00", packagingInventoryAccount.getId()),
                "M13-S2-CAT-IMPORT-1"
        );
        assertThat(importResponse.errors()).isEmpty();

        RawMaterial packagingMaterial = rawMaterialRepository.findByCompanyAndSku(company, "PACK-RM-M13S2").orElseThrow();
        assertThat(packagingMaterial.getInventoryAccountId()).isEqualTo(packagingInventoryAccount.getId());
        addRawMaterialBatch(packagingMaterial, new BigDecimal("50"), new BigDecimal("1.25"));
        createPackagingMapping(packagingMaterial, "1L");

        FinishedGood bulkFg = createFinishedGood("FG-BULK-M13S2", "Bulk Paint", "L", bulkInventoryAccount.getId());
        FinishedGood childFg = createFinishedGood("FG-1L-M13S2", "Paint 1L", "UNIT", childInventoryAccount.getId());
        FinishedGoodBatch bulkBatch = createBulkBatch(bulkFg, new BigDecimal("20"), new BigDecimal("7.50"));

        BulkPackRequest request = new BulkPackRequest(
                bulkBatch.getId(),
                List.of(new BulkPackRequest.PackLine(childFg.getId(), new BigDecimal("5"), "1L", "L")),
                null,
                false,
                LocalDate.now(),
                "packer",
                "import-linked pack",
                "M13-S2-PACK-IDEMP"
        );

        BulkPackResponse first = bulkPackingService.pack(request);
        assertThat(first.journalEntryId()).isNotNull();
        assertThat(first.packagingCost()).isGreaterThan(BigDecimal.ZERO);

        String packReference = resolvePackReference(first.childBatches().getFirst().id());
        List<RawMaterialMovement> firstRawMovements = rawMaterialMovementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company, InventoryReference.PACKING_RECORD, packReference);
        List<InventoryMovement> firstInventoryMovements = inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        company,
                        InventoryReference.PACKING_RECORD,
                        packReference);

        assertThat(firstRawMovements).isNotEmpty();
        assertThat(firstInventoryMovements).isNotEmpty();
        assertThat(firstRawMovements)
                .allSatisfy(movement -> assertThat(movement.getJournalEntryId()).isEqualTo(first.journalEntryId()));
        assertThat(journalEntryRepository.findByCompanyAndReferenceNumber(company, packReference)).isPresent();
        JournalEntry posted = journalEntryRepository.findByCompanyAndId(company, first.journalEntryId()).orElseThrow();
        BigDecimal packagingCredit = posted.getLines().stream()
                .filter(line -> line.getAccount() != null)
                .filter(line -> Objects.equals(line.getAccount().getId(), packagingInventoryAccount.getId()))
                .map(line -> Optional.ofNullable(line.getCredit()).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(packagingCredit).isEqualByComparingTo(first.packagingCost());

        RawMaterial afterFirstPack = rawMaterialRepository.findByCompanyAndSku(company, "PACK-RM-M13S2").orElseThrow();
        assertThat(afterFirstPack.getCurrentStock()).isEqualByComparingTo("45");
        List<Long> firstRawMovementIds = firstRawMovements.stream().map(RawMaterialMovement::getId).toList();
        List<Long> firstInventoryMovementIds = firstInventoryMovements.stream().map(InventoryMovement::getId).toList();

        BulkPackResponse replay = bulkPackingService.pack(request);
        assertThat(replay.journalEntryId()).isEqualTo(first.journalEntryId());
        assertThat(replay.packagingCost()).isEqualByComparingTo(first.packagingCost());

        List<RawMaterialMovement> replayRawMovements = rawMaterialMovementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company, InventoryReference.PACKING_RECORD, packReference);
        List<InventoryMovement> replayInventoryMovements = inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        company,
                        InventoryReference.PACKING_RECORD,
                        packReference);
        RawMaterial afterReplay = rawMaterialRepository.findByCompanyAndSku(company, "PACK-RM-M13S2").orElseThrow();

        assertThat(replayRawMovements).hasSize(firstRawMovements.size());
        assertThat(replayInventoryMovements).hasSize(firstInventoryMovements.size());
        assertThat(replayRawMovements.stream().map(RawMaterialMovement::getId).toList())
                .containsExactlyElementsOf(firstRawMovementIds);
        assertThat(replayInventoryMovements.stream().map(InventoryMovement::getId).toList())
                .containsExactlyElementsOf(firstInventoryMovementIds);
        assertThat(afterReplay.getCurrentStock()).isEqualByComparingTo(afterFirstPack.getCurrentStock());
    }

    private String resolvePackReference(Long childBatchId) {
        List<String> references = inventoryMovementRepository.findAll().stream()
                .filter(movement -> Objects.equals(movement.getReferenceType(), InventoryReference.PACKING_RECORD))
                .filter(movement -> "RECEIPT".equalsIgnoreCase(movement.getMovementType()))
                .filter(movement -> movement.getFinishedGoodBatch() != null)
                .filter(movement -> Objects.equals(movement.getFinishedGoodBatch().getId(), childBatchId))
                .map(InventoryMovement::getReferenceId)
                .distinct()
                .collect(Collectors.toList());
        assertThat(references).hasSize(1);
        return references.getFirst();
    }

    private Account ensureAccount(String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }

    private FinishedGood createFinishedGood(String code, String name, String unit, Long valuationAccountId) {
        FinishedGood fg = new FinishedGood();
        fg.setCompany(company);
        fg.setProductCode(code);
        fg.setName(name);
        fg.setUnit(unit);
        fg.setCostingMethod("FIFO");
        fg.setCurrentStock(BigDecimal.ZERO);
        fg.setReservedStock(BigDecimal.ZERO);
        fg.setValuationAccountId(valuationAccountId);
        return finishedGoodRepository.save(fg);
    }

    private FinishedGoodBatch createBulkBatch(FinishedGood finishedGood, BigDecimal quantity, BigDecimal unitCost) {
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("BULK-" + System.currentTimeMillis());
        batch.setQuantityTotal(quantity);
        batch.setQuantityAvailable(quantity);
        batch.setUnitCost(unitCost);
        batch.setManufacturedAt(Instant.now());
        batch.setBulk(true);
        FinishedGoodBatch saved = finishedGoodBatchRepository.save(batch);
        BigDecimal current = Optional.ofNullable(finishedGood.getCurrentStock()).orElse(BigDecimal.ZERO);
        finishedGood.setCurrentStock(current.add(quantity));
        finishedGoodRepository.save(finishedGood);
        return saved;
    }

    private void addRawMaterialBatch(RawMaterial rawMaterial, BigDecimal quantity, BigDecimal costPerUnit) {
        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(rawMaterial);
        batch.setBatchCode(rawMaterial.getSku() + "-B1");
        batch.setQuantity(quantity);
        batch.setUnit(Optional.ofNullable(rawMaterial.getUnitType()).orElse("UNIT"));
        batch.setCostPerUnit(costPerUnit);
        rawMaterialBatchRepository.save(batch);
        BigDecimal current = Optional.ofNullable(rawMaterial.getCurrentStock()).orElse(BigDecimal.ZERO);
        rawMaterial.setCurrentStock(current.add(quantity));
        rawMaterialRepository.save(rawMaterial);
    }

    private void createPackagingMapping(RawMaterial rawMaterial, String packagingSize) {
        PackagingSizeMapping mapping = new PackagingSizeMapping();
        mapping.setCompany(company);
        mapping.setPackagingSize(packagingSize);
        mapping.setRawMaterial(rawMaterial);
        mapping.setUnitsPerPack(1);
        mapping.setLitersPerUnit(BigDecimal.ONE);
        mapping.setActive(true);
        packagingSizeMappingRepository.save(mapping);
    }

    private MockMultipartFile rawMaterialCsvWithAccountAlias(String skuCode, String gstRate, Long inventoryAccountId) {
        String csv = String.join("\n",
                "brand,product_name,sku_code,category,unit_of_measure,gst_rate,inventory_account_id",
                "RMBrand,Packaging Material," + skuCode + ",RAW_MATERIAL,UNIT," + gstRate + "," + inventoryAccountId
        );
        return new MockMultipartFile(
                "file",
                "packaging-catalog.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );
    }
}
