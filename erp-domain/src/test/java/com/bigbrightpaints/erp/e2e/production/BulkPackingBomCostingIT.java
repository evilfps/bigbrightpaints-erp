package com.bigbrightpaints.erp.e2e.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
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
import com.bigbrightpaints.erp.modules.factory.service.BulkPackingService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("E2E: Bulk packing consumes BOM packaging components")
public class BulkPackingBomCostingIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "WE-BULK";

  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private BulkPackingService bulkPackingService;

  private Company company;
  private Account bulkInventory;
  private Account fgInventory;
  private Account packagingInventory;

  @BeforeEach
  void init() {
    company = dataSeeder.ensureCompany(COMPANY_CODE, COMPANY_CODE + " Ltd");
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
    bulkInventory = ensureAccount("INV-BULK", AccountType.ASSET);
    fgInventory = ensureAccount("INV-FG", AccountType.ASSET);
    packagingInventory = ensureAccount("INV-PACK", AccountType.ASSET);
  }

  @AfterEach
  void cleanupContext() {
    CompanyContextHolder.clear();
  }

  @Test
  @DisplayName("Bulk packing consumes multi-component BOM per size")
  void bulkPackingConsumesPackagingBom() {
    FinishedGood bulkFg = createFinishedGood("FG-BULK", "Bulk Paint", "L", bulkInventory);
    FinishedGood child5 = createFinishedGood("FG-5L", "Paint 5L", "UNIT", fgInventory);
    FinishedGood child10 = createFinishedGood("FG-10L", "Paint 10L", "UNIT", fgInventory);

    FinishedGoodBatch bulkBatch =
        createBulkBatch(bulkFg, new BigDecimal("200"), new BigDecimal("10"));

    RawMaterial can5 = createRawMaterial("CAN-5L", packagingInventory, new BigDecimal("50"));
    RawMaterial can10 = createRawMaterial("CAN-10L", packagingInventory, new BigDecimal("50"));
    RawMaterial label = createRawMaterial("LABEL", packagingInventory, new BigDecimal("50"));

    addBatch(can5, new BigDecimal("50"), new BigDecimal("20"));
    addBatch(can10, new BigDecimal("50"), new BigDecimal("35"));
    addBatch(label, new BigDecimal("50"), new BigDecimal("5"));

    mapPackagingSize("5L", can5);
    mapPackagingSize("5L", label);
    mapPackagingSize("10L", can10);
    mapPackagingSize("10L", label);

    bulkPackingService.pack(
        new BulkPackRequest(
            bulkBatch.getId(),
            List.of(
                new BulkPackRequest.PackLine(child5.getId(), new BigDecimal("10"), "5L", "L"),
                new BulkPackRequest.PackLine(child10.getId(), new BigDecimal("4"), "10L", "L")),
            LocalDate.now(),
            "packer",
            null,
            null));

    assertThat(rawMaterialRepository.findById(can5.getId()).orElseThrow().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("40"));
    assertThat(rawMaterialRepository.findById(can10.getId()).orElseThrow().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("46"));
    assertThat(rawMaterialRepository.findById(label.getId()).orElseThrow().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("36"));

    List<FinishedGoodBatch> childBatches = finishedGoodBatchRepository.findByParentBatch(bulkBatch);
    assertThat(childBatches).hasSize(2);

    FinishedGoodBatch batch5 =
        childBatches.stream()
            .filter(batch -> batch.getFinishedGood().getId().equals(child5.getId()))
            .findFirst()
            .orElseThrow();
    FinishedGoodBatch batch10 =
        childBatches.stream()
            .filter(batch -> batch.getFinishedGood().getId().equals(child10.getId()))
            .findFirst()
            .orElseThrow();

    assertThat(batch5.getUnitCost()).isEqualByComparingTo(new BigDecimal("75.0000"));
    assertThat(batch10.getUnitCost()).isEqualByComparingTo(new BigDecimal("140.0000"));

    List<JournalEntry> journals =
        journalEntryRepository.findByCompanyAndReferenceNumberStartingWith(
            company, "PACK-" + bulkBatch.getBatchCode() + "-");
    assertThat(journals).isNotEmpty();
    JournalEntry journal = journalEntryRepository.findById(journals.get(0).getId()).orElseThrow();

    assertBalanced(journal);
    assertLineAmount(journal, packagingInventory.getId(), new BigDecimal("410.00"), true);
  }

  private Account ensureAccount(String code, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account a = new Account();
              a.setCompany(company);
              a.setCode(code);
              a.setName(code);
              a.setType(type);
              return accountRepository.save(a);
            });
  }

  private FinishedGood createFinishedGood(
      String code, String name, String unit, Account valuationAccount) {
    return finishedGoodRepository
        .findByCompanyAndProductCode(company, code)
        .orElseGet(
            () -> {
              FinishedGood fg = new FinishedGood();
              fg.setCompany(company);
              fg.setProductCode(code);
              fg.setName(name);
              fg.setUnit(unit);
              fg.setCostingMethod("FIFO");
              fg.setValuationAccountId(valuationAccount.getId());
              fg.setCurrentStock(BigDecimal.ZERO);
              fg.setReservedStock(BigDecimal.ZERO);
              return finishedGoodRepository.save(fg);
            });
  }

  private FinishedGoodBatch createBulkBatch(
      FinishedGood fg, BigDecimal quantity, BigDecimal unitCost) {
    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(fg);
    batch.setBatchCode("BULK-" + System.currentTimeMillis());
    batch.setQuantityTotal(quantity);
    batch.setQuantityAvailable(quantity);
    batch.setUnitCost(unitCost);
    batch.setManufacturedAt(Instant.now());
    batch.setBulk(true);
    FinishedGoodBatch saved = finishedGoodBatchRepository.save(batch);
    BigDecimal current = Optional.ofNullable(fg.getCurrentStock()).orElse(BigDecimal.ZERO);
    fg.setCurrentStock(current.add(quantity));
    finishedGoodRepository.save(fg);
    return saved;
  }

  private RawMaterial createRawMaterial(String sku, Account inventoryAccount, BigDecimal stock) {
    RawMaterial rm = new RawMaterial();
    rm.setCompany(company);
    rm.setSku(sku);
    rm.setName(sku);
    rm.setUnitType("UNIT");
    rm.setMaterialType(MaterialType.PACKAGING);
    rm.setInventoryAccountId(inventoryAccount.getId());
    rm.setCurrentStock(stock);
    return rawMaterialRepository.save(rm);
  }

  private void addBatch(RawMaterial rm, BigDecimal quantity, BigDecimal costPerUnit) {
    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(rm);
    batch.setBatchCode(rm.getSku() + "-B1");
    batch.setQuantity(quantity);
    batch.setUnit(Optional.ofNullable(rm.getUnitType()).orElse("UNIT"));
    batch.setCostPerUnit(costPerUnit);
    rawMaterialBatchRepository.save(batch);
  }

  private void mapPackagingSize(String size, RawMaterial material) {
    PackagingSizeMapping mapping = new PackagingSizeMapping();
    mapping.setCompany(company);
    mapping.setPackagingSize(size);
    mapping.setRawMaterial(material);
    mapping.setUnitsPerPack(1);
    mapping.setLitersPerUnit(new BigDecimal(size.replace("L", "")));
    mapping.setActive(true);
    packagingSizeMappingRepository.save(mapping);
  }

  private void assertBalanced(JournalEntry entry) {
    BigDecimal debit =
        entry.getLines().stream()
            .map(l -> Optional.ofNullable(l.getDebit()).orElse(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal credit =
        entry.getLines().stream()
            .map(l -> Optional.ofNullable(l.getCredit()).orElse(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(debit)
        .as("Journal %s balanced", entry.getReferenceNumber())
        .isEqualByComparingTo(credit);
  }

  private void assertLineAmount(
      JournalEntry entry, Long accountId, BigDecimal expected, boolean credit) {
    BigDecimal actual =
        entry.getLines().stream()
            .filter(l -> l.getAccount().getId().equals(accountId))
            .map(l -> credit ? l.getCredit() : l.getDebit())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(actual).isEqualByComparingTo(expected);
  }
}
