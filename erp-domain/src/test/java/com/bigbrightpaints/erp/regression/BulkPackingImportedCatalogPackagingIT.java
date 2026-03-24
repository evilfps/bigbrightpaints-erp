package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.factory.service.BulkPackingService;
import com.bigbrightpaints.erp.modules.factory.service.PackingJournalLinkHelper;
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
import com.bigbrightpaints.erp.modules.production.dto.CatalogImportResponse;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Regression: Bulk pack accounting links for catalog-imported packaging materials")
class BulkPackingImportedCatalogPackagingIT extends AbstractIntegrationTest {

    @Autowired private AccountRepository accountRepository;
    @Autowired private CompanyRepository companyRepository;
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
    @Autowired private PackingJournalLinkHelper packingJournalLinkHelper;

    private Company company;
    private Account bulkInventoryAccount;
    private Account childInventoryAccount;
    private Account packagingInventoryAccount;
    private Account cogsAccount;
    private Account revenueAccount;
    private Account taxAccount;
    private String companyCode;

    @BeforeEach
    void setUp() {
        companyCode = "M13-S2-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        company = dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
        CompanyContextHolder.setCompanyId(companyCode);
        bulkInventoryAccount = ensureAccount("INV-BULK-M13S2", "Bulk Inventory", AccountType.ASSET);
        childInventoryAccount = ensureAccount("INV-CHILD-M13S2", "Child Inventory", AccountType.ASSET);
        packagingInventoryAccount = ensureAccount("INV-PACK-M13S2", "Packaging Inventory", AccountType.ASSET);
        cogsAccount = ensureAccount("COGS-M13S2", "COGS", AccountType.COGS);
        revenueAccount = ensureAccount("REV-M13S2", "Revenue", AccountType.REVENUE);
        taxAccount = ensureAccount("TAX-M13S2", "Tax", AccountType.LIABILITY);
        company.setDefaultCogsAccountId(cogsAccount.getId());
        company.setDefaultRevenueAccountId(revenueAccount.getId());
        company.setDefaultTaxAccountId(taxAccount.getId());
        company.setDefaultInventoryAccountId(bulkInventoryAccount.getId());
        company = companyRepository.save(company);
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

        RawMaterial packagingMaterial = markAsPackagingMaterial(
                rawMaterialRepository.findByCompanyAndSku(company, "PACK-RM-M13S2").orElseThrow());
        assertThat(packagingMaterial.getInventoryAccountId()).isEqualTo(packagingInventoryAccount.getId());
        addRawMaterialBatch(packagingMaterial, new BigDecimal("50"), new BigDecimal("1.25"));
        createPackagingMapping(packagingMaterial, "1L");

        FinishedGood bulkFg = createFinishedGood("FG-BULK-M13S2", "Bulk Paint", "L", bulkInventoryAccount.getId());
        FinishedGood childFg = createFinishedGood("FG-1L-M13S2", "Paint 1L", "UNIT", childInventoryAccount.getId());
        FinishedGoodBatch bulkBatch = createBulkBatch(bulkFg, new BigDecimal("20"), new BigDecimal("7.50"));

        BulkPackRequest request = new BulkPackRequest(
                bulkBatch.getId(),
                List.of(new BulkPackRequest.PackLine(childFg.getId(), new BigDecimal("5"), "1L", "L")),
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
        Map<Long, BigDecimal> creditByAccount = posted.getLines().stream()
                .filter(line -> line.getAccount() != null)
                .map(line -> Map.entry(
                        line.getAccount().getId(),
                        Optional.ofNullable(line.getCredit()).orElse(BigDecimal.ZERO)))
                .filter(entry -> entry.getValue().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        BigDecimal::add));
        Map<Long, BigDecimal> debitByAccount = posted.getLines().stream()
                .filter(line -> line.getAccount() != null)
                .map(line -> Map.entry(
                        line.getAccount().getId(),
                        Optional.ofNullable(line.getDebit()).orElse(BigDecimal.ZERO)))
                .filter(entry -> entry.getValue().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        BigDecimal::add));
        BigDecimal expectedBulkCredit = new BigDecimal("37.50");
        assertThat(creditByAccount.keySet())
                .containsExactlyInAnyOrder(bulkInventoryAccount.getId(), packagingInventoryAccount.getId());
        assertThat(creditByAccount.get(packagingInventoryAccount.getId())).isEqualByComparingTo(first.packagingCost());
        assertThat(creditByAccount.get(bulkInventoryAccount.getId())).isEqualByComparingTo(expectedBulkCredit);
        assertThat(debitByAccount.keySet()).containsExactly(childInventoryAccount.getId());
        BigDecimal totalDebit = debitByAccount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = creditByAccount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);

        RawMaterial afterFirstPack = rawMaterialRepository.findByCompanyAndSku(company, "PACK-RM-M13S2").orElseThrow();
        assertThat(afterFirstPack.getCurrentStock()).isEqualByComparingTo("45");
        Map<Long, String> firstRawMovementSnapshot = snapshotRawMovements(firstRawMovements);
        Map<Long, String> firstInventoryMovementSnapshot = snapshotInventoryMovements(firstInventoryMovements);

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
                .containsExactlyInAnyOrderElementsOf(firstRawMovementSnapshot.keySet());
        assertThat(replayInventoryMovements.stream().map(InventoryMovement::getId).toList())
                .containsExactlyInAnyOrderElementsOf(firstInventoryMovementSnapshot.keySet());
        assertThat(snapshotRawMovements(replayRawMovements)).isEqualTo(firstRawMovementSnapshot);
        assertThat(snapshotInventoryMovements(replayInventoryMovements)).isEqualTo(firstInventoryMovementSnapshot);
        assertThat(afterReplay.getCurrentStock()).isEqualByComparingTo(afterFirstPack.getCurrentStock());
    }

    @Test
    void bulkPack_importedFinishedGoodsAndPackagingUseCatalogMappedAccounts() {
        CatalogImportResponse rmImport = productionCatalogService.importCatalog(
                rawMaterialCsvWithAccountAlias("PACK-RM-M13S7", "12.00", packagingInventoryAccount.getId()),
                "M13-S7-CAT-IMPORT-RM"
        );
        CatalogImportResponse bulkFgImport = productionCatalogService.importCatalog(
                finishedGoodCsvWithValuationAccount("FG-BULK-M13S7", "Bulk Paint M13-S7", bulkInventoryAccount.getId()),
                "M13-S7-CAT-IMPORT-BULK"
        );
        CatalogImportResponse childFgImport = productionCatalogService.importCatalog(
                finishedGoodCsvWithValuationAccount("FG-1L-M13S7", "Paint 1L M13-S7", childInventoryAccount.getId()),
                "M13-S7-CAT-IMPORT-CHILD"
        );
        assertThat(rmImport.errors()).isEmpty();
        assertThat(bulkFgImport.errors()).isEmpty();
        assertThat(childFgImport.errors()).isEmpty();

        RawMaterial packagingMaterial = markAsPackagingMaterial(
                rawMaterialRepository.findByCompanyAndSku(company, "PACK-RM-M13S7").orElseThrow());
        FinishedGood bulkFg = finishedGoodRepository.findByCompanyAndProductCode(company, "FG-BULK-M13S7").orElseThrow();
        FinishedGood childFg = finishedGoodRepository.findByCompanyAndProductCode(company, "FG-1L-M13S7").orElseThrow();
        assertThat(packagingMaterial.getInventoryAccountId()).isEqualTo(packagingInventoryAccount.getId());
        assertThat(bulkFg.getValuationAccountId()).isEqualTo(bulkInventoryAccount.getId());
        assertThat(childFg.getValuationAccountId()).isEqualTo(childInventoryAccount.getId());

        addRawMaterialBatch(packagingMaterial, new BigDecimal("40"), new BigDecimal("1.10"));
        createPackagingMapping(packagingMaterial, "1L");
        FinishedGoodBatch bulkBatch = createBulkBatch(bulkFg, new BigDecimal("12"), new BigDecimal("6.00"));

        BulkPackRequest request = new BulkPackRequest(
                bulkBatch.getId(),
                List.of(new BulkPackRequest.PackLine(childFg.getId(), new BigDecimal("4"), "1L", "L")),
                LocalDate.now(),
                "packer",
                "imported account linkage",
                "M13-S7-PACK-IDEMP"
        );

        BulkPackResponse response = bulkPackingService.pack(request);
        assertThat(response.journalEntryId()).isNotNull();

        String packReference = resolvePackReference(response.childBatches().getFirst().id());
        List<RawMaterialMovement> rawMovements = rawMaterialMovementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company, InventoryReference.PACKING_RECORD, packReference);
        List<InventoryMovement> inventoryMovements = inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        company,
                        InventoryReference.PACKING_RECORD,
                        packReference);
        assertThat(rawMovements).isNotEmpty();
        assertThat(inventoryMovements).isNotEmpty();
        assertThat(rawMovements)
                .allSatisfy(movement -> assertThat(movement.getJournalEntryId()).isEqualTo(response.journalEntryId()));
        assertThat(inventoryMovements)
                .allSatisfy(movement -> assertThat(movement.getJournalEntryId()).isEqualTo(response.journalEntryId()));

        JournalEntry posted = journalEntryRepository.findByCompanyAndId(company, response.journalEntryId()).orElseThrow();
        Map<Long, BigDecimal> creditByAccount = posted.getLines().stream()
                .filter(line -> line.getAccount() != null)
                .map(line -> Map.entry(
                        line.getAccount().getId(),
                        Optional.ofNullable(line.getCredit()).orElse(BigDecimal.ZERO)))
                .filter(entry -> entry.getValue().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        BigDecimal::add));
        Map<Long, BigDecimal> debitByAccount = posted.getLines().stream()
                .filter(line -> line.getAccount() != null)
                .map(line -> Map.entry(
                        line.getAccount().getId(),
                        Optional.ofNullable(line.getDebit()).orElse(BigDecimal.ZERO)))
                .filter(entry -> entry.getValue().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        BigDecimal::add));
        assertThat(creditByAccount.keySet())
                .containsExactlyInAnyOrder(bulkInventoryAccount.getId(), packagingInventoryAccount.getId());
        assertThat(debitByAccount.keySet()).containsExactly(childInventoryAccount.getId());
        BigDecimal totalDebit = debitByAccount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = creditByAccount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
    }

    @Test
    void linkPackagingMovementsToJournal_rejectsJournalRelinkDrift() throws Exception {
        CatalogImportResponse importResponse = productionCatalogService.importCatalog(
                rawMaterialCsvWithAccountAlias("PACK-RM-M13S7-DRIFT", "18.00", packagingInventoryAccount.getId()),
                "M13-S7-CAT-IMPORT-DRIFT"
        );
        assertThat(importResponse.errors()).isEmpty();
        RawMaterial packagingMaterial = markAsPackagingMaterial(
                rawMaterialRepository.findByCompanyAndSku(company, "PACK-RM-M13S7-DRIFT").orElseThrow());
        addRawMaterialBatch(packagingMaterial, new BigDecimal("30"), new BigDecimal("1.00"));
        createPackagingMapping(packagingMaterial, "1L");

        FinishedGood bulkFg = createFinishedGood("FG-BULK-M13S7-DRIFT", "Bulk Paint Drift", "L", bulkInventoryAccount.getId());
        FinishedGood childFg = createFinishedGood("FG-1L-M13S7-DRIFT", "Paint 1L Drift", "UNIT", childInventoryAccount.getId());
        FinishedGoodBatch bulkBatch = createBulkBatch(bulkFg, new BigDecimal("10"), new BigDecimal("5.00"));
        BulkPackRequest request = new BulkPackRequest(
                bulkBatch.getId(),
                List.of(new BulkPackRequest.PackLine(childFg.getId(), new BigDecimal("3"), "1L", "L")),
                LocalDate.now(),
                "packer",
                "link drift guard",
                "M13-S7-PACK-IDEMP-DRIFT"
        );
        BulkPackResponse response = bulkPackingService.pack(request);
        assertThat(response.journalEntryId()).isNotNull();

        String packReference = resolvePackReference(response.childBatches().getFirst().id());
        Long originalJournalId = response.journalEntryId();
        List<RawMaterialMovement> rawMovements = rawMaterialMovementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company, InventoryReference.PACKING_RECORD, packReference);
        List<InventoryMovement> inventoryMovements = inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        company,
                        InventoryReference.PACKING_RECORD,
                        packReference);
        assertThat(rawMovements).allSatisfy(movement -> assertThat(movement.getJournalEntryId()).isEqualTo(originalJournalId));
        assertThat(inventoryMovements).allSatisfy(movement -> assertThat(movement.getJournalEntryId()).isEqualTo(originalJournalId));

        assertThatThrownBy(() -> packingJournalLinkHelper.linkPackagingMovementsToJournal(
                company,
                packReference,
                originalJournalId + 999L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("already linked to journal");

        List<RawMaterialMovement> afterGuardRawMovements = rawMaterialMovementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company, InventoryReference.PACKING_RECORD, packReference);
        List<InventoryMovement> afterGuardInventoryMovements = inventoryMovementRepository
                .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                        company,
                        InventoryReference.PACKING_RECORD,
                        packReference);
        assertThat(afterGuardRawMovements).allSatisfy(movement -> assertThat(movement.getJournalEntryId()).isEqualTo(originalJournalId));
        assertThat(afterGuardInventoryMovements).allSatisfy(movement -> assertThat(movement.getJournalEntryId()).isEqualTo(originalJournalId));
    }

    private Map<Long, String> snapshotRawMovements(List<RawMaterialMovement> movements) {
        Map<Long, String> snapshot = new HashMap<>();
        for (RawMaterialMovement movement : movements) {
            snapshot.put(
                    movement.getId(),
                    movement.getJournalEntryId() + "|"
                            + movement.getMovementType() + "|"
                            + Optional.ofNullable(movement.getQuantity()).orElse(BigDecimal.ZERO).stripTrailingZeros().toPlainString() + "|"
                            + Optional.ofNullable(movement.getUnitCost()).orElse(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
        }
        return snapshot;
    }

    private Map<Long, String> snapshotInventoryMovements(List<InventoryMovement> movements) {
        Map<Long, String> snapshot = new HashMap<>();
        for (InventoryMovement movement : movements) {
            snapshot.put(
                    movement.getId(),
                    movement.getJournalEntryId() + "|"
                            + movement.getMovementType() + "|"
                            + Optional.ofNullable(movement.getQuantity()).orElse(BigDecimal.ZERO).stripTrailingZeros().toPlainString() + "|"
                            + Optional.ofNullable(movement.getUnitCost()).orElse(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
        }
        return snapshot;
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
                "brand,product_name,sku_code,category,unit_of_measure,gst_rate,inventory_account_id,material_type",
                "RMBrand,Packaging Material," + skuCode + ",RAW_MATERIAL,UNIT," + gstRate + "," + inventoryAccountId + ",PACKAGING"
        );
        return new MockMultipartFile(
                "file",
                "packaging-catalog.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile finishedGoodCsvWithValuationAccount(String skuCode,
                                                                  String productName,
                                                                  Long valuationAccountId) {
        String csv = String.join("\n",
                "brand,product_name,sku_code,category,unit_of_measure,gst_rate,fg_valuation_account_id",
                "FGBrand," + productName + "," + skuCode + ",FINISHED_GOOD,LTR,18.00," + valuationAccountId
        );
        return new MockMultipartFile(
                "file",
                "finished-good-catalog.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );
    }

    private RawMaterial markAsPackagingMaterial(RawMaterial rawMaterial) {
        rawMaterial.setMaterialType(MaterialType.PACKAGING);
        return rawMaterialRepository.save(rawMaterial);
    }
}
