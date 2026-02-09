package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingPeriodService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.service.RawMaterialService;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseLineRequest;
import com.bigbrightpaints.erp.modules.purchasing.dto.RawMaterialPurchaseRequest;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    private PurchaseOrderRepository purchaseOrderRepository;
    @Mock
    private RawMaterialRepository rawMaterialRepository;
    @Mock
    private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock
    private RawMaterialService rawMaterialService;
    @Mock
    private RawMaterialMovementRepository movementRepository;
    @Mock
    private GoodsReceiptRepository goodsReceiptRepository;
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
    @Mock
    private AccountingPeriodService accountingPeriodService;
    @Mock
    private PlatformTransactionManager transactionManager;

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
                purchaseOrderRepository,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                rawMaterialService,
                movementRepository,
                goodsReceiptRepository,
                accountingFacade,
                journalEntryRepository,
                companyEntityLookup,
                referenceNumberService,
                companyClock,
                accountingPeriodService,
                transactionManager
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
                200L,
                300L,
                BigDecimal.ZERO,
                List.of(new RawMaterialPurchaseLineRequest(20L, null, BigDecimal.TEN, "KG", BigDecimal.valueOf(5), null, null, null))
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
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 30L);
        purchase.setSupplier(supplier);
        purchase.setTotalAmount(BigDecimal.valueOf(1000));
        purchase.setOutstandingAmount(BigDecimal.valueOf(1000));
        RawMaterialPurchaseLine purchaseLine = new RawMaterialPurchaseLine();
        purchaseLine.setPurchase(purchase);
        purchaseLine.setRawMaterial(rawMaterial);
        purchaseLine.setQuantity(BigDecimal.valueOf(200));
        purchase.getLines().add(purchaseLine);
        when(purchaseRepository.lockByCompanyAndId(company, 30L)).thenReturn(Optional.of(purchase));
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));
        when(companyClock.today(company)).thenReturn(LocalDate.now());
        when(accountingFacade.postPurchaseReturn(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(dummyJournal("REF-001"));

        // Atomic deduction returns 0 (no rows updated = insufficient stock)
        when(rawMaterialRepository.deductStockIfSufficient(eq(20L), any())).thenReturn(0);

        PurchaseReturnRequest request = new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.valueOf(150), // More than available stock
                BigDecimal.valueOf(5),
                null,
                null,
                "Defective"
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
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 40L);
        purchase.setSupplier(supplier);
        purchase.setTotalAmount(BigDecimal.valueOf(100));
        purchase.setOutstandingAmount(BigDecimal.valueOf(100));
        RawMaterialPurchaseLine purchaseLine = new RawMaterialPurchaseLine();
        purchaseLine.setPurchase(purchase);
        purchaseLine.setRawMaterial(rawMaterial);
        purchaseLine.setQuantity(BigDecimal.TEN);
        purchase.getLines().add(purchaseLine);
        when(purchaseRepository.lockByCompanyAndId(company, 40L)).thenReturn(Optional.of(purchase));
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));
        when(companyClock.today(company)).thenReturn(LocalDate.now());

        JournalEntryDto journalDto = dummyJournal("PRN-SUP001-ABC123");
        when(accountingFacade.postPurchaseReturn(any(), any(), any(), any(), any(), any(), any()))
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
                40L,
                20L,
                BigDecimal.valueOf(5),
                BigDecimal.valueOf(5),
                null,
                null,
                "Defective"
        );

        JournalEntryDto result = purchasingService.recordPurchaseReturn(request);

        verify(rawMaterialRepository).deductStockIfSufficient(20L, BigDecimal.valueOf(5));
        verify(rawMaterialBatchRepository).findAvailableBatchesFIFO(rawMaterial);
        verify(rawMaterialBatchRepository).deductQuantityIfSufficient(55L, BigDecimal.valueOf(5));
        verify(movementRepository).saveAll(any());
        assert result != null;
    }

    @Test
    @DisplayName("recordPurchaseReturn rejects quantity above remaining returnable quantity")
    void recordPurchaseReturn_quantityExceedsRemaining_throws() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 50L);
        purchase.setSupplier(supplier);
        purchase.setTotalAmount(BigDecimal.valueOf(100));
        purchase.setOutstandingAmount(BigDecimal.valueOf(100));
        RawMaterialPurchaseLine purchaseLine = new RawMaterialPurchaseLine();
        purchaseLine.setPurchase(purchase);
        purchaseLine.setRawMaterial(rawMaterial);
        purchaseLine.setQuantity(BigDecimal.TEN);
        purchaseLine.setReturnedQuantity(BigDecimal.valueOf(8));
        purchase.getLines().add(purchaseLine);
        when(purchaseRepository.lockByCompanyAndId(company, 50L)).thenReturn(Optional.of(purchase));
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));
        when(companyClock.today(company)).thenReturn(LocalDate.now());
        when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, "PURCHASE_RETURN", "PRN-TEST-0001")).thenReturn(List.of());

        PurchaseReturnRequest request = new PurchaseReturnRequest(
                10L,
                50L,
                20L,
                BigDecimal.valueOf(3),
                BigDecimal.valueOf(5),
                null,
                null,
                "Defective"
        );

        assertThatThrownBy(() -> purchasingService.recordPurchaseReturn(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("remaining returnable quantity");

        verify(accountingFacade, never()).postPurchaseReturn(any(), any(), any(), any(), any(), any(), any());
        verify(rawMaterialRepository, never()).deductStockIfSufficient(any(), any());
    }

    @Test
    @DisplayName("recordPurchaseReturn rejects amount that exceeds outstanding payable")
    void recordPurchaseReturn_amountExceedsOutstanding_throws() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        RawMaterialPurchase purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 60L);
        purchase.setSupplier(supplier);
        purchase.setTotalAmount(BigDecimal.valueOf(20));
        purchase.setOutstandingAmount(BigDecimal.valueOf(5));
        RawMaterialPurchaseLine purchaseLine = new RawMaterialPurchaseLine();
        purchaseLine.setPurchase(purchase);
        purchaseLine.setRawMaterial(rawMaterial);
        purchaseLine.setQuantity(BigDecimal.TEN);
        purchase.getLines().add(purchaseLine);
        when(purchaseRepository.lockByCompanyAndId(company, 60L)).thenReturn(Optional.of(purchase));
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));
        when(companyClock.today(company)).thenReturn(LocalDate.now());
        when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company, "PURCHASE_RETURN", "PRN-TEST-0001")).thenReturn(List.of());

        PurchaseReturnRequest request = new PurchaseReturnRequest(
                10L,
                60L,
                20L,
                BigDecimal.ONE,
                BigDecimal.TEN,
                null,
                null,
                "Defective"
        );

        assertThatThrownBy(() -> purchasingService.recordPurchaseReturn(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("exceeds outstanding payable");

        verify(accountingFacade, never()).postPurchaseReturn(any(), any(), any(), any(), any(), any(), any());
        verify(rawMaterialRepository, never()).deductStockIfSufficient(any(), any());
    }

    @Test
    @DisplayName("createPurchase posts journal before saving purchase to avoid orphans")
    void createPurchase_journalPostedFirst() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        when(purchaseRepository.lockByCompanyAndInvoiceNumberIgnoreCase(company, "INV-002"))
                .thenReturn(Optional.empty());
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));
        GoodsReceipt goodsReceipt = stubGoodsReceipt(300L, 200L, BigDecimal.TEN, BigDecimal.valueOf(5));

        JournalEntry journalEntry = new JournalEntry();
        ReflectionTestUtils.setField(journalEntry, "id", 999L);

        JournalEntryDto journalDto = dummyJournal("RMP-SUP001-INV002", 999L);
        when(accountingFacade.postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(journalDto);
        when(companyEntityLookup.requireJournalEntry(company, 999L)).thenReturn(journalEntry);

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
                200L,
                300L,
                BigDecimal.ZERO,
                List.of(new RawMaterialPurchaseLineRequest(20L, null, BigDecimal.TEN, "KG", BigDecimal.valueOf(5), null, null, null))
        );

        purchasingService.createPurchase(request);

        // Verify order: journal posted, then purchase saved with link
        var inOrder = inOrder(accountingFacade, purchaseRepository);
        inOrder.verify(accountingFacade).postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any());
        inOrder.verify(purchaseRepository).save(any());
    }

    @Test
    @DisplayName("createPurchase posts input tax lines when taxAmount provided")
    void createPurchase_postsTaxLines() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        when(purchaseRepository.lockByCompanyAndInvoiceNumberIgnoreCase(company, "INV-003"))
                .thenReturn(Optional.empty());
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));
        GoodsReceipt goodsReceipt = stubGoodsReceipt(301L, 201L, BigDecimal.TEN, BigDecimal.valueOf(5));

        JournalEntryDto journalDto = dummyJournal("RMP-SUP001-INV003", 999L);
        when(accountingFacade.postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(journalDto);

        when(purchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal taxAmount = new BigDecimal("9.00");
        RawMaterialPurchaseRequest request = new RawMaterialPurchaseRequest(
                10L,
                "INV-003",
                LocalDate.now(),
                "Taxed purchase",
                201L,
                301L,
                taxAmount,
                List.of(new RawMaterialPurchaseLineRequest(20L, null, BigDecimal.TEN, "KG", BigDecimal.valueOf(5), null, null, null))
        );

        purchasingService.createPurchase(request);

        ArgumentCaptor<Map<Long, BigDecimal>> taxLinesCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<BigDecimal> totalAmountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountingFacade).postPurchaseJournal(
                eq(10L),
                eq("INV-003"),
                any(),
                any(),
                any(),
                taxLinesCaptor.capture(),
                totalAmountCaptor.capture(),
                any());

        Map<Long, BigDecimal> taxLines = taxLinesCaptor.getValue();
        assertThat(taxLines).isNotNull();
        assertThat(taxLines.get(null)).isEqualByComparingTo(taxAmount);
        assertThat(totalAmountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("59.00"));
    }

    @Test
    @DisplayName("createPurchase auto-computes tax when taxAmount omitted (exclusive)")
    void createPurchase_autoComputesTaxExclusive() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        when(purchaseRepository.lockByCompanyAndInvoiceNumberIgnoreCase(company, "INV-004"))
                .thenReturn(Optional.empty());
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));
        GoodsReceipt goodsReceipt = stubGoodsReceipt(302L, 202L, new BigDecimal("10"), new BigDecimal("5.00"));

        JournalEntryDto journalDto = dummyJournal("RMP-SUP001-INV004", 1001L);
        when(accountingFacade.postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(journalDto);
        when(purchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RawMaterialPurchaseRequest request = new RawMaterialPurchaseRequest(
                10L,
                "INV-004",
                LocalDate.now(),
                "Auto tax exclusive",
                202L,
                302L,
                null,
                List.of(new RawMaterialPurchaseLineRequest(
                        20L, null, new BigDecimal("10"), "KG", new BigDecimal("5.00"),
                        new BigDecimal("18.00"), Boolean.FALSE, null))
        );

        purchasingService.createPurchase(request);

        ArgumentCaptor<Map<Long, BigDecimal>> taxLinesCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<Long, BigDecimal>> inventoryCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<BigDecimal> totalAmountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountingFacade).postPurchaseJournal(
                eq(10L),
                eq("INV-004"),
                any(),
                any(),
                inventoryCaptor.capture(),
                taxLinesCaptor.capture(),
                totalAmountCaptor.capture(),
                any());

        Map<Long, BigDecimal> inventoryLines = inventoryCaptor.getValue();
        Map<Long, BigDecimal> taxLines = taxLinesCaptor.getValue();
        assertThat(inventoryLines.get(200L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(taxLines.get(null)).isEqualByComparingTo(new BigDecimal("9.00"));
        assertThat(totalAmountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("59.00"));
    }

    @Test
    @DisplayName("createPurchase auto-computes tax when prices are tax-inclusive")
    void createPurchase_autoComputesTaxInclusive() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        when(purchaseRepository.lockByCompanyAndInvoiceNumberIgnoreCase(company, "INV-005"))
                .thenReturn(Optional.empty());
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));
        GoodsReceipt goodsReceipt = stubGoodsReceipt(303L, 203L, new BigDecimal("10"), new BigDecimal("5.90"));

        JournalEntryDto journalDto = dummyJournal("RMP-SUP001-INV005", 1002L);
        when(accountingFacade.postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(journalDto);
        when(purchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RawMaterialPurchaseRequest request = new RawMaterialPurchaseRequest(
                10L,
                "INV-005",
                LocalDate.now(),
                "Auto tax inclusive",
                203L,
                303L,
                null,
                List.of(new RawMaterialPurchaseLineRequest(
                        20L, null, new BigDecimal("10"), "KG", new BigDecimal("5.90"),
                        new BigDecimal("18.00"), Boolean.TRUE, null))
        );

        purchasingService.createPurchase(request);

        ArgumentCaptor<Map<Long, BigDecimal>> taxLinesCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<Long, BigDecimal>> inventoryCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<BigDecimal> totalAmountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountingFacade).postPurchaseJournal(
                eq(10L),
                eq("INV-005"),
                any(),
                any(),
                inventoryCaptor.capture(),
                taxLinesCaptor.capture(),
                totalAmountCaptor.capture(),
                any());

        Map<Long, BigDecimal> inventoryLines = inventoryCaptor.getValue();
        Map<Long, BigDecimal> taxLines = taxLinesCaptor.getValue();
        assertThat(inventoryLines.get(200L)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(taxLines.get(null)).isEqualByComparingTo(new BigDecimal("9.00"));
        assertThat(totalAmountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("59.00"));
    }

    @Test
    @DisplayName("createPurchase rounds GST to paise for exclusive rates")
    void createPurchase_roundsTaxToPaise() {
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        when(purchaseRepository.lockByCompanyAndInvoiceNumberIgnoreCase(company, "INV-006"))
                .thenReturn(Optional.empty());
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(rawMaterial));
        GoodsReceipt goodsReceipt = stubGoodsReceipt(304L, 204L, new BigDecimal("3"), new BigDecimal("1.99"));

        JournalEntryDto journalDto = dummyJournal("RMP-SUP001-INV006", 1003L);
        when(accountingFacade.postPurchaseJournal(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(journalDto);
        when(purchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RawMaterialPurchaseRequest request = new RawMaterialPurchaseRequest(
                10L,
                "INV-006",
                LocalDate.now(),
                "Auto tax rounding",
                204L,
                304L,
                null,
                List.of(new RawMaterialPurchaseLineRequest(
                        20L, null, new BigDecimal("3"), "KG", new BigDecimal("1.99"),
                        new BigDecimal("18.00"), Boolean.FALSE, null))
        );

        purchasingService.createPurchase(request);

        ArgumentCaptor<Map<Long, BigDecimal>> taxLinesCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<BigDecimal> totalAmountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(accountingFacade).postPurchaseJournal(
                eq(10L),
                eq("INV-006"),
                any(),
                any(),
                any(),
                taxLinesCaptor.capture(),
                totalAmountCaptor.capture(),
                any());

        Map<Long, BigDecimal> taxLines = taxLinesCaptor.getValue();
        assertThat(taxLines.get(null)).isEqualByComparingTo(new BigDecimal("1.07"));
        assertThat(totalAmountCaptor.getValue()).isEqualByComparingTo(new BigDecimal("7.04"));
    }

    private JournalEntryDto dummyJournal(String reference) {
        return dummyJournal(reference, 1L);
    }

    private GoodsReceipt stubGoodsReceipt(Long receiptId,
                                          Long orderId,
                                          BigDecimal quantity,
                                          BigDecimal costPerUnit) {
        PurchaseOrder order = buildPurchaseOrder(orderId, supplier, rawMaterial, quantity, costPerUnit);
        GoodsReceipt receipt = buildGoodsReceipt(receiptId, order, rawMaterial, quantity, costPerUnit);
        when(goodsReceiptRepository.lockByCompanyAndId(company, receiptId)).thenReturn(Optional.of(receipt));
        when(purchaseRepository.findByCompanyAndGoodsReceipt(company, receipt)).thenReturn(Optional.empty());
        return receipt;
    }

    private PurchaseOrder buildPurchaseOrder(Long orderId,
                                             Supplier supplier,
                                             RawMaterial material,
                                             BigDecimal quantity,
                                             BigDecimal costPerUnit) {
        PurchaseOrder order = new PurchaseOrder();
        ReflectionTestUtils.setField(order, "id", orderId);
        order.setCompany(company);
        order.setSupplier(supplier);
        order.setOrderNumber("PO-" + orderId);
        order.setOrderDate(LocalDate.now());

        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setPurchaseOrder(order);
        line.setRawMaterial(material);
        line.setQuantity(quantity);
        line.setUnit(material.getUnitType());
        line.setCostPerUnit(costPerUnit);
        line.setLineTotal(quantity.multiply(costPerUnit));
        order.getLines().add(line);
        return order;
    }

    private GoodsReceipt buildGoodsReceipt(Long receiptId,
                                           PurchaseOrder order,
                                           RawMaterial material,
                                           BigDecimal quantity,
                                           BigDecimal costPerUnit) {
        GoodsReceipt receipt = new GoodsReceipt();
        ReflectionTestUtils.setField(receipt, "id", receiptId);
        receipt.setCompany(company);
        receipt.setSupplier(order.getSupplier());
        receipt.setPurchaseOrder(order);
        receipt.setReceiptNumber("GRN-" + receiptId);
        receipt.setReceiptDate(LocalDate.now());
        receipt.setStatus("RECEIVED");

        GoodsReceiptLine line = new GoodsReceiptLine();
        line.setGoodsReceipt(receipt);
        line.setRawMaterial(material);
        line.setBatchCode("BATCH-" + receiptId);
        line.setQuantity(quantity);
        line.setUnit(material.getUnitType());
        line.setCostPerUnit(costPerUnit);
        line.setLineTotal(quantity.multiply(costPerUnit));
        RawMaterialBatch batch = new RawMaterialBatch();
        ReflectionTestUtils.setField(batch, "id", receiptId + 1000);
        batch.setRawMaterial(material);
        batch.setBatchCode(line.getBatchCode());
        batch.setQuantity(quantity);
        batch.setUnit(material.getUnitType());
        batch.setCostPerUnit(costPerUnit);
        line.setRawMaterialBatch(batch);
        receipt.getLines().add(line);
        return receipt;
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
