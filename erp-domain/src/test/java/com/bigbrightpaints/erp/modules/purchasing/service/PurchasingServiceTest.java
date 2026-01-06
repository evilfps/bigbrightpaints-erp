package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PurchasingServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private RawMaterialPurchaseRepository purchaseRepository;
    @Mock
    private RawMaterialRepository rawMaterialRepository;
    @Mock
    private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock
    private RawMaterialService rawMaterialService;
    @Mock
    private RawMaterialMovementRepository movementRepository;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private ReferenceNumberService referenceNumberService;
    @Mock
    private CompanyClock companyClock;

    private PurchasingService purchasingService;
    private Company company;
    private Supplier supplier;
    private RawMaterial rawMaterial;
    private Account payableAccount;

    @BeforeEach
    void setup() {
        purchasingService = new PurchasingService(
                companyContextService,
                purchaseRepository,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                rawMaterialService,
                movementRepository,
                accountingFacade,
                journalEntryRepository,
                companyEntityLookup,
                referenceNumberService,
                companyClock
        );

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
        company.setName("Test Company");

        payableAccount = new Account();
        ReflectionTestUtils.setField(payableAccount, "id", 100L);

        supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 10L);
        supplier.setCode("SUP001");
        supplier.setName("Test Supplier");
        supplier.setCompany(company);
        supplier.setPayableAccount(payableAccount);

        rawMaterial = new RawMaterial();
        ReflectionTestUtils.setField(rawMaterial, "id", 20L);
        rawMaterial.setName("Test Material");
        rawMaterial.setUnitType("KG");
        rawMaterial.setInventoryAccountId(200L);
        rawMaterial.setCurrentStock(BigDecimal.valueOf(100));
        rawMaterial.setCompany(company);

        lenient().when(referenceNumberService.purchaseReference(any(), any(), any())).thenReturn("RMP-TEST-0001");
        lenient().when(referenceNumberService.purchaseReturnReference(any(), any())).thenReturn("PRN-TEST-0001");
    }

    @Test
    @DisplayName("createPurchase rejects duplicate invoice via pessimistic lock")
    void createPurchase_duplicateInvoice_throws() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);

        RawMaterialPurchase existing = new RawMaterialPurchase();
        existing.setInvoiceNumber("INV-001");
        when(purchaseRepository.lockByCompanyAndInvoiceNumberIgnoreCase(company, "INV-001"))
                .thenReturn(Optional.of(existing));

        RawMaterialPurchaseRequest request = new RawMaterialPurchaseRequest(
                10L,
                "INV-001",
                LocalDate.now(),
                "Test memo",
                List.of(new RawMaterialPurchaseLineRequest(20L, null, BigDecimal.TEN, "KG", BigDecimal.valueOf(5), null))
        );

        assertThatThrownBy(() -> purchasingService.createPurchase(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invoice number already used");

        verify(purchaseRepository).lockByCompanyAndInvoiceNumberIgnoreCase(company, "INV-001");
        verify(purchaseRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordPurchaseReturn uses atomic deduction to prevent negative stock")
    void recordPurchaseReturn_insufficientStock_throws() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));
        when(companyClock.today(company)).thenReturn(LocalDate.now());
        when(accountingFacade.postPurchaseReturn(any(), any(), any(), any(), any(), any()))
                .thenReturn(dummyJournal("REF-001"));

        // Atomic deduction returns 0 (no rows updated = insufficient stock)
        when(rawMaterialRepository.deductStockIfSufficient(eq(20L), any())).thenReturn(0);

        PurchaseReturnRequest request = new PurchaseReturnRequest(
                10L,
                20L,
                BigDecimal.valueOf(150), // More than available stock
                BigDecimal.valueOf(5),
                "Defective",
                null,
                null
        );

        assertThatThrownBy(() -> purchasingService.recordPurchaseReturn(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot return more than on-hand inventory");

        verify(rawMaterialRepository).deductStockIfSufficient(eq(20L), eq(BigDecimal.valueOf(150)));
    }

    @Test
    @DisplayName("recordPurchaseReturn succeeds with atomic deduction when stock sufficient")
    void recordPurchaseReturn_sufficientStock_succeeds() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));
        when(companyClock.today(company)).thenReturn(LocalDate.now());

        JournalEntryDto journalDto = dummyJournal("PRN-SUP001-ABC123");
        when(accountingFacade.postPurchaseReturn(any(), any(), any(), any(), any(), any()))
                .thenReturn(journalDto);

        RawMaterialBatch batch = new RawMaterialBatch();
        ReflectionTestUtils.setField(batch, "id", 55L);
        batch.setBatchCode("RM-BATCH-001");
        batch.setQuantity(BigDecimal.TEN);
        batch.setUnit("KG");
        batch.setCostPerUnit(BigDecimal.valueOf(5));
        batch.setRawMaterial(rawMaterial);
        when(rawMaterialBatchRepository.findAvailableBatchesFIFO(rawMaterial))
                .thenReturn(List.of(batch));
        when(rawMaterialBatchRepository.deductQuantityIfSufficient(55L, BigDecimal.valueOf(5)))
                .thenReturn(1);

        // Atomic deduction returns 1 (success)
        when(rawMaterialRepository.deductStockIfSufficient(eq(20L), eq(BigDecimal.valueOf(5)))).thenReturn(1);

        PurchaseReturnRequest request = new PurchaseReturnRequest(
                10L,
                20L,
                BigDecimal.valueOf(5),
                BigDecimal.valueOf(5),
                "Defective",
                null,
                null
        );

        JournalEntryDto result = purchasingService.recordPurchaseReturn(request);

        verify(rawMaterialRepository).deductStockIfSufficient(20L, BigDecimal.valueOf(5));
        verify(rawMaterialBatchRepository).findAvailableBatchesFIFO(rawMaterial);
        verify(rawMaterialBatchRepository).deductQuantityIfSufficient(55L, BigDecimal.valueOf(5));
        verify(movementRepository).saveAll(any());
        assert result != null;
    }

    @Test
    @DisplayName("createPurchase posts journal before saving purchase to avoid orphans")
    void createPurchase_journalPostedFirst() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        when(purchaseRepository.lockByCompanyAndInvoiceNumberIgnoreCase(company, "INV-002"))
                .thenReturn(Optional.empty());
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));

        JournalEntry journalEntry = new JournalEntry();
        ReflectionTestUtils.setField(journalEntry, "id", 999L);

        JournalEntryDto journalDto = dummyJournal("RMP-SUP001-INV002", 999L);
        when(accountingFacade.postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(journalDto);
        when(companyEntityLookup.requireJournalEntry(company, 999L)).thenReturn(journalEntry);

        RawMaterialService.ReceiptResult receiptResult = new RawMaterialService.ReceiptResult(
                new com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch(),
                null,
                null
        );
        when(rawMaterialService.recordReceipt(any(), any(), any())).thenReturn(receiptResult);
        when(purchaseRepository.save(any())).thenAnswer(inv -> {
            RawMaterialPurchase p = inv.getArgument(0);
            // Verify journal is already linked when save is called
            assert p.getJournalEntry() != null : "Journal should be linked before saving purchase";
            return p;
        });

        RawMaterialPurchaseRequest request = new RawMaterialPurchaseRequest(
                10L,
                "INV-002",
                LocalDate.now(),
                "Test memo",
                List.of(new RawMaterialPurchaseLineRequest(20L, null, BigDecimal.TEN, "KG", BigDecimal.valueOf(5), null))
        );

        purchasingService.createPurchase(request);

        // Verify order: journal posted, then purchase saved with link
        var inOrder = inOrder(accountingFacade, purchaseRepository);
        inOrder.verify(accountingFacade).postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any());
        inOrder.verify(purchaseRepository).save(any());
    }

    private JournalEntryDto dummyJournal(String reference) {
        return dummyJournal(reference, 1L);
    }

    private JournalEntryDto dummyJournal(String reference, Long id) {
        return new JournalEntryDto(
                id,
                null,
                reference,
                LocalDate.now(),
                "memo",
                "POSTED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                Instant.now(),
                Instant.now(),
                Instant.now(),
                "tester",
                "tester",
                "tester"
        );
    }
}
