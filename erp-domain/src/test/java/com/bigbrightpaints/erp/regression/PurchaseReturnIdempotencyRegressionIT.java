package com.bigbrightpaints.erp.regression;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import com.bigbrightpaints.erp.modules.purchasing.service.PurchasingService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Regression: Purchase return idempotency avoids duplicate movements")
class PurchaseReturnIdempotencyRegressionIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "LF-022";

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private RawMaterialRepository rawMaterialRepository;

    @Autowired
    private RawMaterialBatchRepository rawMaterialBatchRepository;

    @Autowired
    private RawMaterialMovementRepository movementRepository;

    @Autowired
    private PurchasingService purchasingService;

    @Autowired
    private RawMaterialPurchaseRepository purchaseRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    private Company company;
    private Supplier supplier;
    private RawMaterial material;
    private RawMaterialPurchase purchase;
    private LocalDate invoiceDate;
    private LocalDate firstReturnDate;
    private LocalDate secondReturnDate;

    @BeforeEach
    void setUp() {
        String seedSuffix = Long.toString(System.nanoTime());
        String seedBatchCode = "RM-LF022-B1-" + seedSuffix;
        invoiceDate = LocalDate.now().minusDays(5);
        firstReturnDate = invoiceDate.plusDays(4);
        secondReturnDate = invoiceDate.plusDays(5);
        company = dataSeeder.ensureCompany(COMPANY_CODE, "LF-022 Materials");
        CompanyContextHolder.setCompanyId(COMPANY_CODE);

        Account inventory = ensureAccount(company, "INV-LF022", "Inventory", AccountType.ASSET);
        Account payable = ensureAccount(company, "AP-LF022", "Accounts Payable", AccountType.LIABILITY);

        supplier = supplierRepository.findByCompanyAndCodeIgnoreCase(company, "SUP-LF022")
                .orElseGet(() -> {
                    Supplier created = new Supplier();
                    created.setCompany(company);
                    created.setName("LF-022 Supplier");
                    created.setCode("SUP-LF022");
                    created.setStatus(SupplierStatus.ACTIVE);
                    created.setPayableAccount(payable);
                    created.setOutstandingBalance(BigDecimal.ZERO);
                    return supplierRepository.save(created);
                });
        supplier.setStatus(SupplierStatus.ACTIVE);
        supplier.setPayableAccount(payable);
        supplier.setOutstandingBalance(BigDecimal.ZERO);
        supplier = supplierRepository.save(supplier);

        material = new RawMaterial();
        material.setCompany(company);
        material.setName("LF-022 Resin");
        material.setSku("RM-LF022-" + seedSuffix);
        material.setUnitType("KG");
        material.setInventoryAccountId(inventory.getId());
        material.setCurrentStock(new BigDecimal("10.00"));
        material = rawMaterialRepository.save(material);

        RawMaterialBatch batch = new RawMaterialBatch();
        batch.setRawMaterial(material);
        batch.setBatchCode(seedBatchCode);
        batch.setQuantity(new BigDecimal("10.00"));
        batch.setUnit("KG");
        batch.setCostPerUnit(new BigDecimal("5.00"));
        rawMaterialBatchRepository.save(batch);

        RawMaterialPurchase seeded = new RawMaterialPurchase();
        seeded.setCompany(company);
        seeded.setSupplier(supplier);
        seeded.setInvoiceNumber("PR-LF022-INV-" + seedSuffix);
        seeded.setInvoiceDate(invoiceDate);
        seeded.setTotalAmount(new BigDecimal("20.00"));
        seeded.setOutstandingAmount(new BigDecimal("20.00"));
        seeded.setStatus("POSTED");

        RawMaterialPurchaseLine line = new RawMaterialPurchaseLine();
        line.setPurchase(seeded);
        line.setRawMaterial(material);
        line.setBatchCode(seedBatchCode);
        line.setQuantity(new BigDecimal("4.00"));
        line.setUnit("KG");
        line.setCostPerUnit(new BigDecimal("5.00"));
        line.setLineTotal(new BigDecimal("20.00"));
        seeded.getLines().add(line);

        JournalEntry purchaseJournal = new JournalEntry();
        purchaseJournal.setCompany(company);
        purchaseJournal.setReferenceNumber("PINV-LF022-" + seedSuffix);
        purchaseJournal.setEntryDate(invoiceDate);
        purchaseJournal.setMemo("Seeded purchase journal");
        purchaseJournal.setStatus("POSTED");
        seeded.setJournalEntry(journalEntryRepository.save(purchaseJournal));

        purchase = purchaseRepository.save(seeded);
    }

    @AfterEach
    void tearDown() {
        CompanyContextHolder.clear();
    }

    @Test
    void purchaseReturnReplayDoesNotDuplicateMovements() {
        PurchaseReturnRequest request = new PurchaseReturnRequest(
                supplier.getId(),
                purchase.getId(),
                material.getId(),
                new BigDecimal("4.00"),
                new BigDecimal("5.00"),
                "PR-LF022-001",
                firstReturnDate,
                "Damaged"
        );

        JournalEntryDto first = purchasingService.recordPurchaseReturn(request);
        BigDecimal stockAfterFirst = rawMaterialRepository.findById(material.getId()).orElseThrow().getCurrentStock();
        RawMaterialPurchase purchaseAfterFirst = purchaseRepository.findById(purchase.getId()).orElseThrow();
        List<RawMaterialMovement> movements = movementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company,
                        InventoryReference.PURCHASE_RETURN,
                        "PR-LF022-001");
        assertThat(movements).hasSize(1);
        assertThat(movements).allMatch(movement -> movement.getJournalEntryId() != null);
        assertThat(purchaseAfterFirst.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(purchaseAfterFirst.getStatus()).isEqualTo("VOID");
        assertThat(journalEntryRepository.findById(first.id()))
                .get()
                .satisfies(entry -> {
                    assertThat(entry.getCorrectionType()).isNotNull();
                    assertThat(entry.getCorrectionReason()).isEqualTo("PURCHASE_RETURN");
                    assertThat(entry.getSourceModule()).isEqualTo("PURCHASING_RETURN");
                    assertThat(entry.getSourceReference()).isEqualTo(purchase.getInvoiceNumber());
                });

        JournalEntryDto second = purchasingService.recordPurchaseReturn(request);
        BigDecimal stockAfterSecond = rawMaterialRepository.findById(material.getId()).orElseThrow().getCurrentStock();
        RawMaterialPurchase purchaseAfterSecond = purchaseRepository.findById(purchase.getId()).orElseThrow();
        List<RawMaterialMovement> movementsAfter = movementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company,
                        InventoryReference.PURCHASE_RETURN,
                        "PR-LF022-001");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(stockAfterSecond).isEqualByComparingTo(stockAfterFirst);
        assertThat(movementsAfter).hasSize(movements.size());
        assertThat(movementsAfter).allMatch(movement -> movement.getJournalEntryId().equals(first.id()));
        assertThat(purchaseAfterSecond.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(purchaseAfterSecond.getStatus()).isEqualTo("VOID");
    }

    @Test
    void purchaseReturnReplaySucceedsAfterSupplierBecomesReferenceOnly() {
        PurchaseReturnRequest request = new PurchaseReturnRequest(
                supplier.getId(),
                purchase.getId(),
                material.getId(),
                new BigDecimal("4.00"),
                new BigDecimal("5.00"),
                "PR-LF022-002",
                firstReturnDate,
                "Damaged"
        );

        JournalEntryDto first = purchasingService.recordPurchaseReturn(request);

        supplier = supplierRepository.findById(supplier.getId()).orElseThrow();
        supplier.setStatus(SupplierStatus.SUSPENDED);
        supplier = supplierRepository.saveAndFlush(supplier);

        JournalEntryDto replay = purchasingService.recordPurchaseReturn(request);
        List<RawMaterialMovement> movementsAfterReplay = movementRepository
                .findByRawMaterialCompanyAndReferenceTypeAndReferenceId(company,
                        InventoryReference.PURCHASE_RETURN,
                        "PR-LF022-002");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(movementsAfterReplay).hasSize(1);
        assertThat(movementsAfterReplay).allMatch(movement -> movement.getJournalEntryId().equals(first.id()));
        assertThat(purchaseRepository.findById(purchase.getId()).orElseThrow().getOutstandingAmount())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void partialThenFinalReturn_marksPurchaseVoidOnlyWhenFullyReturned() {
        PurchaseReturnRequest firstReturn = new PurchaseReturnRequest(
                supplier.getId(),
                purchase.getId(),
                material.getId(),
                new BigDecimal("2.00"),
                new BigDecimal("5.00"),
                "PR-LF022-010",
                firstReturnDate,
                "Damaged"
        );
        purchasingService.recordPurchaseReturn(firstReturn);

        RawMaterialPurchase afterFirst = purchaseRepository.findById(purchase.getId()).orElseThrow();
        assertThat(afterFirst.getOutstandingAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(afterFirst.getStatus()).isEqualTo("PARTIAL");

        PurchaseReturnRequest secondReturn = new PurchaseReturnRequest(
                supplier.getId(),
                purchase.getId(),
                material.getId(),
                new BigDecimal("2.00"),
                new BigDecimal("5.00"),
                "PR-LF022-011",
                secondReturnDate,
                "Damaged"
        );
        purchasingService.recordPurchaseReturn(secondReturn);

        RawMaterialPurchase afterSecond = purchaseRepository.findById(purchase.getId()).orElseThrow();
        assertThat(afterSecond.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(afterSecond.getStatus()).isEqualTo("VOID");
    }

    @Test
    void purchaseReturnRejectsQuantityAboveRemainingReturnable() {
        PurchaseReturnRequest firstReturn = new PurchaseReturnRequest(
                supplier.getId(),
                purchase.getId(),
                material.getId(),
                new BigDecimal("3.00"),
                new BigDecimal("5.00"),
                "PR-LF022-020",
                firstReturnDate,
                "Damaged"
        );
        purchasingService.recordPurchaseReturn(firstReturn);

        PurchaseReturnRequest overReturn = new PurchaseReturnRequest(
                supplier.getId(),
                purchase.getId(),
                material.getId(),
                new BigDecimal("2.00"),
                new BigDecimal("5.00"),
                "PR-LF022-021",
                secondReturnDate,
                "Damaged"
        );

        assertThatThrownBy(() -> purchasingService.recordPurchaseReturn(overReturn))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("remaining returnable quantity");

        RawMaterialPurchase afterRejected = purchaseRepository.findById(purchase.getId()).orElseThrow();
        assertThat(afterRejected.getOutstandingAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(afterRejected.getStatus()).isEqualTo("PARTIAL");
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
}
