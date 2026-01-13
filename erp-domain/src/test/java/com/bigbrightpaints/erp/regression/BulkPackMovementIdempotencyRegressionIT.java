package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackRequest;
import com.bigbrightpaints.erp.modules.factory.dto.BulkPackResponse;
import com.bigbrightpaints.erp.modules.factory.service.BulkPackingService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Regression: Bulk pack links ISSUE/RECEIPT movements and is idempotent")
class BulkPackMovementIdempotencyRegressionIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "LF-016-017";
    private static final LocalDate PACK_DATE = LocalDate.of(2026, 1, 13);

    @Autowired private AccountRepository accountRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private BulkPackingService bulkPackingService;

    private Company company;
    private Account inventoryAccount;
    private FinishedGood bulkFinishedGood;
    private FinishedGood childFinishedGood;
    private FinishedGoodBatch bulkBatch;

    @BeforeEach
    void setUp() {
        company = dataSeeder.ensureCompany(COMPANY_CODE, COMPANY_CODE + " Ltd");
        CompanyContextHolder.setCompanyId(COMPANY_CODE);

        inventoryAccount = ensureAccount(company, "INV-LF016", "Inventory", AccountType.ASSET);
        bulkFinishedGood = createFinishedGood(company, "BULK-" + UUID.randomUUID(), inventoryAccount);
        childFinishedGood = createFinishedGood(company, "CHILD-" + UUID.randomUUID(), inventoryAccount);
        bulkBatch = createBulkBatch(bulkFinishedGood, new BigDecimal("10.00"), new BigDecimal("5.00"));
    }

    @AfterEach
    void clearContext() {
        CompanyContextHolder.clear();
    }

    @Test
    @Transactional
    void bulkPackCreatesMovementsAndIsIdempotent() {
        BulkPackRequest request = new BulkPackRequest(
                bulkBatch.getId(),
                List.of(new BulkPackRequest.PackLine(
                        childFinishedGood.getId(),
                        new BigDecimal("2"),
                        "1L",
                        "L")),
                List.of(),
                PACK_DATE,
                "auditor",
                "LF-016/017 regression"
        );

        BulkPackResponse first = bulkPackingService.pack(request);
        assertThat(first.journalEntryId()).isNotNull();
        JournalEntry journal = journalEntryRepository.findById(first.journalEntryId()).orElseThrow();
        String reference = journal.getReferenceNumber();
        assertThat(reference).startsWith("PACK-");
        assertThat(reference.length()).isLessThanOrEqualTo(64);
        String hash = reference.substring(reference.length() - 16);
        String prefix = reference.substring(0, reference.length() - 17);
        assertThat(reference.substring(reference.length() - 17, reference.length() - 16)).isEqualTo("-");
        assertThat(hash).matches("[a-f0-9]{16}");
        assertThat(bulkBatch.getBatchCode()).startsWith(prefix.substring("PACK-".length()));

        List<InventoryMovement> movements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc("PACKAGING", reference);
        assertThat(movements).isNotEmpty();

        InventoryMovement issue = movements.stream()
                .filter(movement -> "ISSUE".equalsIgnoreCase(movement.getMovementType()))
                .findFirst()
                .orElseThrow();
        assertThat(issue.getFinishedGoodBatch().getId()).isEqualTo(bulkBatch.getId());
        assertThat(issue.getQuantity()).isEqualByComparingTo(new BigDecimal("2"));

        List<InventoryMovement> receipts = movements.stream()
                .filter(movement -> "RECEIPT".equalsIgnoreCase(movement.getMovementType()))
                .toList();
        assertThat(receipts).hasSize(1);

        List<FinishedGoodBatch> childBatches = finishedGoodBatchRepository.findByParentBatch(bulkBatch);
        assertThat(childBatches).hasSize(1);
        assertThat(receipts.get(0).getFinishedGoodBatch().getId())
                .isIn(childBatches.stream().map(FinishedGoodBatch::getId).toList());

        assertThat(movements).allMatch(movement -> movement.getJournalEntryId() != null);
        assertThat(movements).allMatch(movement -> movement.getJournalEntryId().equals(journal.getId()));

        int movementCount = movements.size();
        int childBatchCount = childBatches.size();

        BulkPackResponse second = bulkPackingService.pack(request);
        assertThat(second.journalEntryId()).isEqualTo(first.journalEntryId());

        List<InventoryMovement> movementsAfter = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc("PACKAGING", reference);
        assertThat(movementsAfter).hasSize(movementCount);
        assertThat(finishedGoodBatchRepository.findByParentBatch(bulkBatch)).hasSize(childBatchCount);
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
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

    private FinishedGood createFinishedGood(Company company, String code, Account inventoryAccount) {
        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode(code);
        finishedGood.setName(code);
        finishedGood.setUnit("UNIT");
        finishedGood.setValuationAccountId(inventoryAccount.getId());
        finishedGood.setCurrentStock(BigDecimal.ZERO);
        finishedGood.setReservedStock(BigDecimal.ZERO);
        return finishedGoodRepository.save(finishedGood);
    }

    private FinishedGoodBatch createBulkBatch(FinishedGood finishedGood, BigDecimal quantity, BigDecimal unitCost) {
        FinishedGoodBatch batch = new FinishedGoodBatch();
        batch.setFinishedGood(finishedGood);
        batch.setBatchCode("BULK-BATCH-" + UUID.randomUUID());
        batch.setQuantityTotal(quantity);
        batch.setQuantityAvailable(quantity);
        batch.setUnitCost(unitCost);
        batch.setManufacturedAt(Instant.now());
        batch.setBulk(true);
        FinishedGoodBatch saved = finishedGoodBatchRepository.save(batch);

        finishedGood.setCurrentStock(quantity);
        finishedGoodRepository.save(finishedGood);
        return saved;
    }
}
