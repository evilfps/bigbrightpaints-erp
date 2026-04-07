package com.bigbrightpaints.erp.modules.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditLog;
import com.bigbrightpaints.erp.core.audit.AuditLogRepository;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialAdjustment;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialAdjustmentRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialAdjustmentDto;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialAdjustmentRequest;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
class RawMaterialAdjustmentServiceTest extends AbstractIntegrationTest {

  @Autowired private RawMaterialService rawMaterialService;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private RawMaterialRepository rawMaterialRepository;

  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;

  @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;

  @Autowired private RawMaterialAdjustmentRepository rawMaterialAdjustmentRepository;

  @Autowired private AccountRepository accountRepository;

  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private AuditLogRepository auditLogRepository;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void adjustStock_increaseCreatesBatchMovementAndJournal() {
    Company company = seedCompany("RM-ADJ-IN");
    Map<String, Account> accounts = ensureAccounts(company);
    RawMaterial material =
        createRawMaterial(
            company, "RM-ADJ-IN-" + shortId(), accounts.get("INV").getId(), new BigDecimal("5.00"));

    String idempotencyKey = "RM-ADJ-IN-" + UUID.randomUUID();
    RawMaterialAdjustmentRequest request =
        new RawMaterialAdjustmentRequest(
            LocalDate.now(),
            RawMaterialAdjustmentRequest.AdjustmentDirection.INCREASE,
            accounts.get("VAR").getId(),
            "cycle count",
            Boolean.FALSE,
            idempotencyKey,
            List.of(
                new RawMaterialAdjustmentRequest.LineRequest(
                    material.getId(),
                    new BigDecimal("3.00"),
                    new BigDecimal("12.50"),
                    "extra stock")));

    RawMaterialAdjustmentDto response;
    CompanyContextHolder.setCompanyCode(company.getCode());
    try {
      response = rawMaterialService.adjustStock(request);
    } finally {
      CompanyContextHolder.clear();
    }

    RawMaterial reloaded = rawMaterialRepository.findById(material.getId()).orElseThrow();
    assertThat(reloaded.getCurrentStock()).isEqualByComparingTo(new BigDecimal("8.00"));

    RawMaterialAdjustment storedAdjustment =
        rawMaterialAdjustmentRepository
            .findByCompanyAndIdempotencyKey(company, idempotencyKey)
            .orElseThrow();
    assertThat(storedAdjustment.getJournalEntryId()).isEqualTo(response.journalEntryId());
    assertThat(storedAdjustment.getTotalAmount()).isEqualByComparingTo(new BigDecimal("37.5000"));
    assertThat(storedAdjustment.getStatus()).isEqualTo("POSTED");

    List<RawMaterialMovement> movements =
        rawMaterialMovementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
            company, "RAW_MATERIAL_ADJUSTMENT", storedAdjustment.getReferenceNumber());
    assertThat(movements).hasSize(1);
    RawMaterialMovement movement = movements.getFirst();
    assertThat(movement.getMovementType()).isEqualTo("ADJUSTMENT_IN");
    assertThat(movement.getQuantity()).isEqualByComparingTo(new BigDecimal("3.00"));
    assertThat(movement.getJournalEntryId()).isEqualTo(response.journalEntryId());
    assertThat(movement.getRawMaterialBatch()).isNotNull();

    Long adjustmentBatchId = movement.getRawMaterialBatch().getId();
    RawMaterialBatch adjustmentBatch =
        rawMaterialBatchRepository.findById(adjustmentBatchId).orElseThrow();
    assertThat(adjustmentBatch.getSource().name()).isEqualTo("ADJUSTMENT");
    assertThat(adjustmentBatch.getQuantity()).isEqualByComparingTo(new BigDecimal("3.00"));

    JournalEntry journal = journalEntryRepository.findById(response.journalEntryId()).orElseThrow();
    BigDecimal totalDebit =
        journal.getLines().stream()
            .map(line -> line.getDebit() == null ? BigDecimal.ZERO : line.getDebit())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCredit =
        journal.getLines().stream()
            .map(line -> line.getCredit() == null ? BigDecimal.ZERO : line.getCredit())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(totalDebit).isEqualByComparingTo(new BigDecimal("37.5000"));
    assertThat(totalCredit).isEqualByComparingTo(new BigDecimal("37.5000"));

    assertThat(journal.getLines())
        .anyMatch(
            line ->
                line.getAccount().getId().equals(accounts.get("INV").getId())
                    && line.getDebit().compareTo(new BigDecimal("37.5000")) == 0
                    && line.getCredit().compareTo(BigDecimal.ZERO) == 0);
    assertThat(journal.getLines())
        .anyMatch(
            line ->
                line.getAccount().getId().equals(accounts.get("VAR").getId())
                    && line.getCredit().compareTo(new BigDecimal("37.5000")) == 0
                    && line.getDebit().compareTo(BigDecimal.ZERO) == 0);

    List<AuditLog> adjustmentAuditLogs =
        auditLogRepository.findByEventTypeWithMetadataOrderByTimestampDesc(
            AuditEvent.INVENTORY_ADJUSTMENT);
    assertThat(adjustmentAuditLogs)
        .anySatisfy(
            log -> {
              assertThat(log.getCompanyId()).isEqualTo(company.getId());
              assertThat(log.getMetadata())
                  .containsEntry("resourceType", "INVENTORY")
                  .containsEntry("referenceType", "RAW_MATERIAL_ADJUSTMENT")
                  .containsEntry("referenceNumber", storedAdjustment.getReferenceNumber());
            });
  }

  @Test
  void adjustStock_decreaseConsumesFifoBatchesAndIsIdempotent() {
    Company company = seedCompany("RM-ADJ-OUT");
    Map<String, Account> accounts = ensureAccounts(company);
    RawMaterial material =
        createRawMaterial(
            company,
            "RM-ADJ-OUT-" + shortId(),
            accounts.get("INV").getId(),
            new BigDecimal("10.00"));

    RawMaterialBatch oldest =
        createBatch(
            material, "RM-OLD-" + shortId(), new BigDecimal("4.00"), new BigDecimal("8.00"));
    RawMaterialBatch newest =
        createBatch(
            material, "RM-NEW-" + shortId(), new BigDecimal("6.00"), new BigDecimal("9.00"));

    String idempotencyKey = "RM-ADJ-OUT-" + UUID.randomUUID();
    RawMaterialAdjustmentRequest request =
        new RawMaterialAdjustmentRequest(
            LocalDate.now(),
            RawMaterialAdjustmentRequest.AdjustmentDirection.DECREASE,
            accounts.get("VAR").getId(),
            "stock count down",
            Boolean.FALSE,
            idempotencyKey,
            List.of(
                new RawMaterialAdjustmentRequest.LineRequest(
                    material.getId(),
                    new BigDecimal("5.00"),
                    new BigDecimal("10.00"),
                    "shrinkage")));

    RawMaterialAdjustmentDto first;
    RawMaterialAdjustmentDto second;
    CompanyContextHolder.setCompanyCode(company.getCode());
    try {
      first = rawMaterialService.adjustStock(request);
      second = rawMaterialService.adjustStock(request);
    } finally {
      CompanyContextHolder.clear();
    }

    assertThat(second.id()).isEqualTo(first.id());

    RawMaterial reloaded = rawMaterialRepository.findById(material.getId()).orElseThrow();
    assertThat(reloaded.getCurrentStock()).isEqualByComparingTo(new BigDecimal("5.00"));

    RawMaterialBatch oldestAfter =
        rawMaterialBatchRepository.findById(oldest.getId()).orElseThrow();
    RawMaterialBatch newestAfter =
        rawMaterialBatchRepository.findById(newest.getId()).orElseThrow();
    assertThat(oldestAfter.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(newestAfter.getQuantity()).isEqualByComparingTo(new BigDecimal("5.00"));

    RawMaterialAdjustment storedAdjustment =
        rawMaterialAdjustmentRepository
            .findByCompanyAndIdempotencyKey(company, idempotencyKey)
            .orElseThrow();

    List<RawMaterialMovement> movements =
        rawMaterialMovementRepository
            .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, "RAW_MATERIAL_ADJUSTMENT", storedAdjustment.getReferenceNumber())
            .stream()
            .sorted(Comparator.comparing(RawMaterialMovement::getId))
            .toList();
    assertThat(movements).hasSize(2);
    assertThat(movements).allMatch(movement -> movement.getMovementType().equals("ADJUSTMENT_OUT"));
    assertThat(movements.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("4.00"));
    assertThat(movements.get(1).getQuantity()).isEqualByComparingTo(new BigDecimal("1.00"));

    JournalEntry journal = journalEntryRepository.findById(first.journalEntryId()).orElseThrow();
    assertThat(journal.getLines())
        .anyMatch(
            line ->
                line.getAccount().getId().equals(accounts.get("VAR").getId())
                    && line.getDebit().compareTo(new BigDecimal("50.0000")) == 0);
    assertThat(journal.getLines())
        .anyMatch(
            line ->
                line.getAccount().getId().equals(accounts.get("INV").getId())
                    && line.getCredit().compareTo(new BigDecimal("50.0000")) == 0);
  }

  @Test
  void adjustStock_rejectsPayloadMismatchForSameIdempotencyKey() {
    Company company = seedCompany("RM-ADJ-MISMATCH");
    Map<String, Account> accounts = ensureAccounts(company);
    RawMaterial material =
        createRawMaterial(
            company,
            "RM-ADJ-MISMATCH-" + shortId(),
            accounts.get("INV").getId(),
            new BigDecimal("12.00"));

    String idempotencyKey = "RM-ADJ-MISMATCH-" + UUID.randomUUID();
    RawMaterialAdjustmentRequest first =
        new RawMaterialAdjustmentRequest(
            LocalDate.now(),
            RawMaterialAdjustmentRequest.AdjustmentDirection.INCREASE,
            accounts.get("VAR").getId(),
            "base",
            Boolean.FALSE,
            idempotencyKey,
            List.of(
                new RawMaterialAdjustmentRequest.LineRequest(
                    material.getId(), new BigDecimal("2.00"), new BigDecimal("5.00"), "baseline")));

    RawMaterialAdjustmentRequest mismatch =
        new RawMaterialAdjustmentRequest(
            LocalDate.now(),
            RawMaterialAdjustmentRequest.AdjustmentDirection.DECREASE,
            accounts.get("VAR").getId(),
            "base",
            Boolean.FALSE,
            idempotencyKey,
            List.of(
                new RawMaterialAdjustmentRequest.LineRequest(
                    material.getId(), new BigDecimal("2.00"), new BigDecimal("5.00"), "baseline")));

    CompanyContextHolder.setCompanyCode(company.getCode());
    try {
      rawMaterialService.adjustStock(first);

      assertThatThrownBy(() -> rawMaterialService.adjustStock(mismatch))
          .isInstanceOf(ApplicationException.class)
          .hasMessageContaining("Idempotency key already used");
    } finally {
      CompanyContextHolder.clear();
    }
  }

  @Test
  void adjustStock_rejectsDecreaseWhenBatchCoverageMissing() {
    Company company = seedCompany("RM-ADJ-BATCH-INSUF");
    Map<String, Account> accounts = ensureAccounts(company);
    RawMaterial material =
        createRawMaterial(
            company,
            "RM-ADJ-BATCH-INSUF-" + shortId(),
            accounts.get("INV").getId(),
            new BigDecimal("6.00"));

    createBatch(
        material, "RM-BATCH-ONLY-" + shortId(), new BigDecimal("2.00"), new BigDecimal("10.00"));

    RawMaterialAdjustmentRequest request =
        new RawMaterialAdjustmentRequest(
            LocalDate.now(),
            RawMaterialAdjustmentRequest.AdjustmentDirection.DECREASE,
            accounts.get("VAR").getId(),
            "batch insufficient",
            Boolean.FALSE,
            "RM-ADJ-BATCH-INSUF-" + UUID.randomUUID(),
            List.of(
                new RawMaterialAdjustmentRequest.LineRequest(
                    material.getId(),
                    new BigDecimal("4.00"),
                    new BigDecimal("10.00"),
                    "insufficient batches")));

    CompanyContextHolder.setCompanyCode(company.getCode());
    try {
      assertThatThrownBy(() -> rawMaterialService.adjustStock(request))
          .isInstanceOf(ApplicationException.class)
          .hasMessageContaining("Insufficient batch availability");
    } finally {
      CompanyContextHolder.clear();
    }

    RawMaterial reloaded = rawMaterialRepository.findById(material.getId()).orElseThrow();
    assertThat(reloaded.getCurrentStock()).isEqualByComparingTo(new BigDecimal("6.00"));
  }

  private Company seedCompany(String code) {
    Company company = dataSeeder.ensureCompany(code, code + " Ltd");
    company.setTimezone("UTC");
    company.setBaseCurrency("INR");
    return company;
  }

  private Map<String, Account> ensureAccounts(Company company) {
    Account inventory =
        ensureAccount(
            company, "RM_INV_" + company.getCode(), "Raw Material Inventory", AccountType.ASSET);
    Account variance =
        ensureAccount(
            company, "RM_VAR_" + company.getCode(), "Raw Material Variance", AccountType.EXPENSE);

    Company refreshed = companyRepository.findById(company.getId()).orElseThrow();
    if (refreshed.getDefaultInventoryAccountId() == null) {
      refreshed.setDefaultInventoryAccountId(inventory.getId());
      companyRepository.saveAndFlush(refreshed);
    }

    return Map.of("INV", inventory, "VAR", variance);
  }

  private Account ensureAccount(Company company, String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode(code);
              account.setName(name);
              account.setType(type);
              account.setActive(true);
              account.setBalance(BigDecimal.ZERO);
              return accountRepository.saveAndFlush(account);
            });
  }

  private RawMaterial createRawMaterial(
      Company company, String sku, Long inventoryAccountId, BigDecimal currentStock) {
    RawMaterial material = new RawMaterial();
    material.setCompany(company);
    material.setName("Raw Material " + sku);
    material.setSku(sku);
    material.setUnitType("KG");
    material.setCurrentStock(currentStock);
    material.setInventoryAccountId(inventoryAccountId);
    return rawMaterialRepository.saveAndFlush(material);
  }

  private RawMaterialBatch createBatch(
      RawMaterial material, String batchCode, BigDecimal quantity, BigDecimal costPerUnit) {
    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(material);
    batch.setBatchCode(batchCode);
    batch.setQuantity(quantity);
    batch.setUnit("KG");
    batch.setCostPerUnit(costPerUnit);
    batch.setSource(com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource.PURCHASE);
    return rawMaterialBatchRepository.saveAndFlush(batch);
  }

  private String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
