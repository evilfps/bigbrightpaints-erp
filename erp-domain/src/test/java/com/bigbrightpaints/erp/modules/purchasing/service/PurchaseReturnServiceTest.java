package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnPreviewDto;
import com.bigbrightpaints.erp.modules.purchasing.dto.PurchaseReturnRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class PurchaseReturnServiceTest {

    @Mock private CompanyContextService companyContextService;
    @Mock private RawMaterialPurchaseRepository purchaseRepository;
    @Mock private RawMaterialRepository rawMaterialRepository;
    @Mock private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock private RawMaterialMovementRepository movementRepository;
    @Mock private AccountingFacade accountingFacade;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private CompanyEntityLookup companyEntityLookup;
    @Mock private ReferenceNumberService referenceNumberService;
    @Mock private CompanyClock companyClock;
    @Mock private GstService gstService;
    @Mock private PurchaseReturnAllocationService allocationService;

    private PurchaseReturnService purchaseReturnService;
    private Company company;
    private Supplier supplier;
    private RawMaterial material;
    private RawMaterialPurchase purchase;

    @BeforeEach
    void setUp() {
        purchaseReturnService = new PurchaseReturnService(
                companyContextService,
                purchaseRepository,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                movementRepository,
                accountingFacade,
                journalEntryRepository,
                companyEntityLookup,
                referenceNumberService,
                companyClock,
                gstService,
                allocationService
        );

        company = new Company();
        ReflectionTestUtils.setField(company, "id", 1L);
        company.setStateCode("KA");

        supplier = new Supplier();
        ReflectionTestUtils.setField(supplier, "id", 10L);
        supplier.setCompany(company);
        supplier.setCode("SUP-10");
        supplier.setName("Supplier 10");
        supplier.setStatus(SupplierStatus.ACTIVE);

        material = new RawMaterial();
        ReflectionTestUtils.setField(material, "id", 20L);
        material.setCompany(company);
        material.setName("Resin");
        material.setInventoryAccountId(200L);

        purchase = new RawMaterialPurchase();
        ReflectionTestUtils.setField(purchase, "id", 30L);
        purchase.setCompany(company);
        purchase.setSupplier(supplier);
        purchase.setStatus("POSTED");
        JournalEntry journalEntry = new JournalEntry();
        ReflectionTestUtils.setField(journalEntry, "id", 300L);
        journalEntry.setStatus("POSTED");
        purchase.setJournalEntry(journalEntry);
        purchase.setTaxAmount(BigDecimal.ZERO);
        RawMaterialPurchaseLine line = new RawMaterialPurchaseLine();
        line.setPurchase(purchase);
        line.setRawMaterial(material);
        line.setQuantity(new BigDecimal("4.0000"));
        line.setLineTotal(new BigDecimal("20.00"));
        purchase.getLines().add(line);

        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
        lenient().when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        lenient().when(purchaseRepository.lockByCompanyAndId(company, 30L)).thenReturn(Optional.of(purchase));
        lenient().when(companyEntityLookup.lockActiveRawMaterial(company, 20L)).thenReturn(material);
        lenient().when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(eq(company), eq(com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference.PURCHASE_RETURN), eq("PR-30")))
                .thenReturn(List.of());
    }

    @Test
    void recordPurchaseReturn_rejectsReferenceOnlySupplierBeforeMutations() {
        supplier.setStatus(SupplierStatus.SUSPENDED);

        PurchaseReturnRequest request = new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                new BigDecimal("1.0000"),
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        );

        assertThatThrownBy(() -> purchaseReturnService.recordPurchaseReturn(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_INVALID_STATE);
                    assertThat(ex).hasMessageContaining("reference only")
                            .hasMessageContaining("post purchase returns");
                });

        verifyNoInteractions(accountingFacade, allocationService);
        verify(rawMaterialRepository, never()).deductStockIfSufficient(any(), any());
    }

    @Test
    void recordPurchaseReturn_rejectsMissingPayableAccountBeforeAllocationMutations() {
        supplier.setPayableAccount(null);

        PurchaseReturnRequest request = new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                new BigDecimal("1.0000"),
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        );

        assertThatThrownBy(() -> purchaseReturnService.recordPurchaseReturn(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_STATE);
                    assertThat(ex).hasMessageContaining("missing a payable account");
                });

        verifyNoInteractions(accountingFacade, allocationService);
        verify(rawMaterialRepository, never()).deductStockIfSufficient(any(), any());
    }

    @Test
    void recordPurchaseReturn_checksRemainingQuantityAfterTransactionalSupplierPasses() {
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 40L);
        supplier.setPayableAccount(payable);
        when(allocationService.remainingReturnableQuantity(purchase, material)).thenReturn(BigDecimal.ZERO);

        PurchaseReturnRequest request = new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                new BigDecimal("1.0000"),
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        );

        assertThatThrownBy(() -> purchaseReturnService.recordPurchaseReturn(request))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
                    assertThat(ex).hasMessageContaining("already been returned");
                });

        verify(allocationService).remainingReturnableQuantity(purchase, material);
        verifyNoInteractions(accountingFacade);
        verify(rawMaterialRepository, never()).deductStockIfSufficient(any(), any());
    }

    @Test
    void recordPurchaseReturn_rejectsUnknownRawMaterial() {
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 40L);
        supplier.setPayableAccount(payable);
        when(companyEntityLookup.lockActiveRawMaterial(company, 20L))
                .thenThrow(new IllegalArgumentException("Raw material not found: id=20"));

        PurchaseReturnRequest request = new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                new BigDecimal("1.0000"),
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        );

        assertThatThrownBy(() -> purchaseReturnService.recordPurchaseReturn(request))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Raw material not found");
    }

    @Test
    void previewPurchaseReturn_computesCanonicalPreviewAndDefaults() {
        purchase.setInvoiceNumber("PI-30");
        purchase.setTaxAmount(new BigDecimal("4.00"));
        purchase.getLines().getFirst().setTaxAmount(new BigDecimal("4.00"));
        when(allocationService.remainingReturnableQuantity(purchase, material)).thenReturn(new BigDecimal("3.0000"));
        when(referenceNumberService.purchaseReturnReference(company, supplier)).thenReturn("PR-AUTO-30");
        when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 3, 10));

        PurchaseReturnPreviewDto preview = purchaseReturnService.previewPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                null,
                null,
                "Damaged"
        ));

        assertThat(preview.purchaseId()).isEqualTo(30L);
        assertThat(preview.purchaseInvoiceNumber()).isEqualTo("PI-30");
        assertThat(preview.rawMaterialId()).isEqualTo(20L);
        assertThat(preview.rawMaterialName()).isEqualTo("Resin");
        assertThat(preview.requestedQuantity()).isEqualByComparingTo("1.00");
        assertThat(preview.remainingReturnableQuantity()).isEqualByComparingTo("2.00");
        assertThat(preview.lineAmount()).isEqualByComparingTo("5.00");
        assertThat(preview.taxAmount()).isEqualByComparingTo("1.00");
        assertThat(preview.totalAmount()).isEqualByComparingTo("6.00");
        assertThat(preview.returnDate()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(preview.referenceNumber()).isEqualTo("PR-AUTO-30");
    }

    @Test
    void previewPurchaseReturn_honorsExplicitReferenceAndReturnDate() {
        purchase.setInvoiceNumber("PI-30");
        when(allocationService.remainingReturnableQuantity(purchase, material)).thenReturn(new BigDecimal("3.0000"));

        PurchaseReturnPreviewDto preview = purchaseReturnService.previewPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                " PR-EXPLICIT-30 ",
                LocalDate.of(2026, 3, 11),
                "Damaged"
        ));

        assertThat(preview.returnDate()).isEqualTo(LocalDate.of(2026, 3, 11));
        assertThat(preview.referenceNumber()).isEqualTo("PR-EXPLICIT-30");
    }

    @Test
    void previewPurchaseReturn_rejectsPurchaseWithoutPostedJournal() {
        purchase.setJournalEntry(null);

        assertThatThrownBy(() -> purchaseReturnService.previewPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted purchases can be corrected through purchase return");

        verifyNoInteractions(accountingFacade);
    }

    @Test
    void previewPurchaseReturn_rejectsPurchaseWithoutSupplierAssociation() {
        purchase.setSupplier(null);

        assertThatThrownBy(() -> purchaseReturnService.previewPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Purchase does not belong to the supplier");
    }

    @Test
    void previewPurchaseReturn_rejectsVoidedPurchaseStatus() {
        purchase.setStatus("VOID");

        assertThatThrownBy(() -> purchaseReturnService.previewPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted purchases can be corrected through purchase return");

        verifyNoInteractions(accountingFacade);
    }

    @Test
    void previewPurchaseReturn_rejectsPurchaseForDifferentSupplier() {
        Supplier otherSupplier = new Supplier();
        ReflectionTestUtils.setField(otherSupplier, "id", 11L);
        otherSupplier.setCompany(company);
        otherSupplier.setName("Supplier 11");
        purchase.setSupplier(otherSupplier);

        assertThatThrownBy(() -> purchaseReturnService.previewPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Purchase does not belong to the supplier");
    }

    @Test
    void previewPurchaseReturn_rejectsMaterialNotPresentInPurchase() {
        RawMaterial otherMaterial = new RawMaterial();
        ReflectionTestUtils.setField(otherMaterial, "id", 21L);
        otherMaterial.setCompany(company);
        otherMaterial.setName("Solvent");
        when(companyEntityLookup.lockActiveRawMaterial(company, 21L)).thenReturn(otherMaterial);

        assertThatThrownBy(() -> purchaseReturnService.previewPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                21L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Purchase does not include raw material Solvent");
    }

    @Test
    void previewPurchaseReturn_rejectsWhenPurchaseLinesLackLinkedMaterial() {
        purchase.getLines().getFirst().setRawMaterial(null);

        assertThatThrownBy(() -> purchaseReturnService.previewPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Purchase does not include raw material Resin");
    }

    @Test
    void previewPurchaseReturn_rejectsQuantityBeyondRemainingReturnableQuantity() {
        when(allocationService.remainingReturnableQuantity(purchase, material)).thenReturn(new BigDecimal("0.7500"));

        assertThatThrownBy(() -> purchaseReturnService.previewPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        )))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
                    assertThat(ex).hasMessageContaining("exceeds remaining returnable quantity");
                });
    }

    @Test
    void recordPurchaseReturn_replayRelinksExistingMovementsAndCorrectionMetadata() {
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 40L);
        supplier.setPayableAccount(payable);
        purchase.setInvoiceNumber("PI-30");

        JournalEntry postedReturnEntry = new JournalEntry();
        ReflectionTestUtils.setField(postedReturnEntry, "id", 901L);

        com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement existingMovement =
                new com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement();
        existingMovement.setRawMaterial(material);
        existingMovement.setReferenceId("PR-30");
        existingMovement.setReferenceType(com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference.PURCHASE_RETURN);
        existingMovement.setQuantity(BigDecimal.ONE);
        existingMovement.setUnitCost(new BigDecimal("5.00"));

        when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company,
                com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference.PURCHASE_RETURN,
                "PR-30"
        )).thenReturn(List.of(existingMovement));
        when(accountingFacade.postPurchaseReturn(
                eq(10L),
                eq("PR-30"),
                eq(LocalDate.of(2026, 3, 9)),
                eq("Damaged - Resin to Supplier 10"),
                eq(Map.of(200L, new BigDecimal("5.00"))),
                eq(null),
                eq(null),
                eq(new BigDecimal("5.00"))
        )).thenReturn(journalEntryDto(901L, "PR-30", LocalDate.of(2026, 3, 9), "Damaged - Resin to Supplier 10"));
        when(journalEntryRepository.findByCompanyAndId(company, 901L)).thenReturn(Optional.of(postedReturnEntry));

        JournalEntryDto result = purchaseReturnService.recordPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        ));

        assertThat(result.id()).isEqualTo(901L);
        assertThat(postedReturnEntry.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);
        assertThat(postedReturnEntry.getCorrectionReason()).isEqualTo("PURCHASE_RETURN");
        assertThat(postedReturnEntry.getSourceModule()).isEqualTo("PURCHASING_RETURN");
        assertThat(postedReturnEntry.getSourceReference()).isEqualTo("PI-30");
        assertThat(existingMovement.getJournalEntryId()).isEqualTo(901L);
        verify(journalEntryRepository).save(postedReturnEntry);
        verify(movementRepository).saveAll(List.of(existingMovement));
        verify(rawMaterialRepository, never()).deductStockIfSufficient(any(), any());
        verify(allocationService, never()).applyPurchaseReturnQuantity(any(), any(), any());
    }

    @Test
    void recordPurchaseReturn_replayWithAlignedJournalAndMovementSkipsPersistence() {
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 40L);
        supplier.setPayableAccount(payable);
        purchase.setInvoiceNumber("PI-30");

        JournalEntry postedReturnEntry = new JournalEntry();
        ReflectionTestUtils.setField(postedReturnEntry, "id", 901L);
        postedReturnEntry.setCorrectionType(JournalCorrectionType.REVERSAL);
        postedReturnEntry.setCorrectionReason("PURCHASE_RETURN");
        postedReturnEntry.setSourceModule("PURCHASING_RETURN");
        postedReturnEntry.setSourceReference("PI-30");

        com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement existingMovement =
                new com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement();
        existingMovement.setRawMaterial(material);
        existingMovement.setReferenceId("PR-30");
        existingMovement.setReferenceType(com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference.PURCHASE_RETURN);
        existingMovement.setQuantity(BigDecimal.ONE);
        existingMovement.setUnitCost(new BigDecimal("5.00"));
        existingMovement.setJournalEntryId(901L);

        when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company,
                com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference.PURCHASE_RETURN,
                "PR-30"
        )).thenReturn(List.of(existingMovement));
        when(accountingFacade.postPurchaseReturn(
                eq(10L),
                eq("PR-30"),
                eq(LocalDate.of(2026, 3, 9)),
                eq("Damaged - Resin to Supplier 10"),
                eq(Map.of(200L, new BigDecimal("5.00"))),
                eq(null),
                eq(null),
                eq(new BigDecimal("5.00"))
        )).thenReturn(journalEntryDto(901L, "PR-30", LocalDate.of(2026, 3, 9), "Damaged - Resin to Supplier 10"));
        when(journalEntryRepository.findByCompanyAndId(company, 901L)).thenReturn(Optional.of(postedReturnEntry));

        JournalEntryDto result = purchaseReturnService.recordPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        ));

        assertThat(result.id()).isEqualTo(901L);
        verify(journalEntryRepository, never()).save(postedReturnEntry);
        verify(movementRepository, never()).saveAll(any());
        verify(rawMaterialRepository, never()).deductStockIfSufficient(any(), any());
        verify(allocationService, never()).applyPurchaseReturnQuantity(any(), any(), any());
    }

    @Test
    void recordPurchaseReturn_replayWithNullAccountingEntrySkipsMovementRelink() {
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 40L);
        supplier.setPayableAccount(payable);

        RawMaterialMovement existingMovement = new RawMaterialMovement();
        existingMovement.setRawMaterial(material);
        existingMovement.setReferenceId("PR-30");
        existingMovement.setReferenceType(InventoryReference.PURCHASE_RETURN);
        existingMovement.setQuantity(BigDecimal.ONE);
        existingMovement.setUnitCost(new BigDecimal("5.00"));

        when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company,
                InventoryReference.PURCHASE_RETURN,
                "PR-30"
        )).thenReturn(List.of(existingMovement));
        when(accountingFacade.postPurchaseReturn(
                eq(10L),
                eq("PR-30"),
                eq(LocalDate.of(2026, 3, 9)),
                eq("Damaged - Resin to Supplier 10"),
                eq(Map.of(200L, new BigDecimal("5.00"))),
                eq(null),
                eq(null),
                eq(new BigDecimal("5.00"))
        )).thenReturn(null);

        JournalEntryDto result = purchaseReturnService.recordPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        ));

        assertThat(result).isNull();
        verify(movementRepository, never()).saveAll(any());
    }

    @Test
    void recordPurchaseReturn_usesTrimmedExplicitReferenceWithoutAutoNumberLookup() {
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 40L);
        supplier.setPayableAccount(payable);

        RawMaterialMovement existingMovement = new RawMaterialMovement();
        existingMovement.setRawMaterial(material);
        existingMovement.setReferenceId("PR-EXPLICIT-30");
        existingMovement.setReferenceType(InventoryReference.PURCHASE_RETURN);
        existingMovement.setQuantity(BigDecimal.ONE);
        existingMovement.setUnitCost(new BigDecimal("5.00"));

        when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company,
                InventoryReference.PURCHASE_RETURN,
                "PR-EXPLICIT-30"
        )).thenReturn(List.of(existingMovement));
        when(accountingFacade.postPurchaseReturn(
                eq(10L),
                eq("PR-EXPLICIT-30"),
                eq(LocalDate.of(2026, 3, 9)),
                eq("Damaged - Resin to Supplier 10"),
                eq(Map.of(200L, new BigDecimal("5.00"))),
                eq(null),
                eq(null),
                eq(new BigDecimal("5.00"))
        )).thenReturn(journalEntryDto(902L, "PR-EXPLICIT-30", LocalDate.of(2026, 3, 9), "Damaged - Resin to Supplier 10"));
        when(journalEntryRepository.findByCompanyAndId(company, 902L)).thenReturn(Optional.of(new JournalEntry()));

        JournalEntryDto result = purchaseReturnService.recordPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                " PR-EXPLICIT-30 ",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        ));

        assertThat(result.id()).isEqualTo(902L);
        verify(referenceNumberService, never()).purchaseReturnReference(any(), any());
        verify(rawMaterialRepository, never()).deductStockIfSufficient(any(), any());
    }

    @Test
    void recordPurchaseReturn_generatesReferenceWhenExplicitReferenceMissing() {
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 40L);
        supplier.setPayableAccount(payable);

        RawMaterialMovement existingMovement = new RawMaterialMovement();
        existingMovement.setRawMaterial(material);
        existingMovement.setReferenceId("PR-AUTO-30");
        existingMovement.setReferenceType(InventoryReference.PURCHASE_RETURN);
        existingMovement.setQuantity(BigDecimal.ONE);
        existingMovement.setUnitCost(new BigDecimal("5.00"));

        when(referenceNumberService.purchaseReturnReference(company, supplier)).thenReturn("PR-AUTO-30");
        when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company,
                InventoryReference.PURCHASE_RETURN,
                "PR-AUTO-30"
        )).thenReturn(List.of(existingMovement));
        when(accountingFacade.postPurchaseReturn(
                eq(10L),
                eq("PR-AUTO-30"),
                eq(LocalDate.of(2026, 3, 9)),
                eq("Damaged - Resin to Supplier 10"),
                eq(Map.of(200L, new BigDecimal("5.00"))),
                eq(null),
                eq(null),
                eq(new BigDecimal("5.00"))
        )).thenReturn(journalEntryDto(903L, "PR-AUTO-30", LocalDate.of(2026, 3, 9), "Damaged - Resin to Supplier 10"));

        JournalEntryDto result = purchaseReturnService.recordPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                " ",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        ));

        assertThat(result.id()).isEqualTo(903L);
        verify(referenceNumberService).purchaseReturnReference(company, supplier);
    }

    @Test
    void recordPurchaseReturn_replayRelinksWhenExistingMovementPointsToDifferentJournal() {
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 40L);
        supplier.setPayableAccount(payable);
        purchase.setInvoiceNumber("PI-30");

        JournalEntry postedReturnEntry = new JournalEntry();
        ReflectionTestUtils.setField(postedReturnEntry, "id", 904L);

        RawMaterialMovement existingMovement = new RawMaterialMovement();
        existingMovement.setRawMaterial(material);
        existingMovement.setReferenceId("PR-30");
        existingMovement.setReferenceType(InventoryReference.PURCHASE_RETURN);
        existingMovement.setQuantity(BigDecimal.ONE);
        existingMovement.setUnitCost(new BigDecimal("5.00"));
        existingMovement.setJournalEntryId(777L);

        when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company,
                InventoryReference.PURCHASE_RETURN,
                "PR-30"
        )).thenReturn(List.of(existingMovement));
        when(accountingFacade.postPurchaseReturn(
                eq(10L),
                eq("PR-30"),
                eq(LocalDate.of(2026, 3, 9)),
                eq("Damaged - Resin to Supplier 10"),
                eq(Map.of(200L, new BigDecimal("5.00"))),
                eq(null),
                eq(null),
                eq(new BigDecimal("5.00"))
        )).thenReturn(journalEntryDto(904L, "PR-30", LocalDate.of(2026, 3, 9), "Damaged - Resin to Supplier 10"));
        when(journalEntryRepository.findByCompanyAndId(company, 904L)).thenReturn(Optional.of(postedReturnEntry));

        JournalEntryDto result = purchaseReturnService.recordPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        ));

        assertThat(result.id()).isEqualTo(904L);
        assertThat(existingMovement.getJournalEntryId()).isEqualTo(904L);
        verify(movementRepository).saveAll(List.of(existingMovement));
    }

    @Test
    void recordPurchaseReturn_replayRejectsMovementDriftAgainstRequestedMaterial() {
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 40L);
        supplier.setPayableAccount(payable);

        RawMaterial otherMaterial = new RawMaterial();
        ReflectionTestUtils.setField(otherMaterial, "id", 21L);
        otherMaterial.setCompany(company);
        otherMaterial.setName("Solvent");

        com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement existingMovement =
                new com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement();
        existingMovement.setRawMaterial(otherMaterial);
        existingMovement.setReferenceId("PR-30");
        existingMovement.setReferenceType(com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference.PURCHASE_RETURN);
        existingMovement.setQuantity(BigDecimal.ONE);
        existingMovement.setUnitCost(new BigDecimal("5.00"));

        when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(
                company,
                com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference.PURCHASE_RETURN,
                "PR-30"
        )).thenReturn(List.of(existingMovement));

        assertThatThrownBy(() -> purchaseReturnService.recordPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Purchase return reference already used with different payload");

        verifyNoInteractions(accountingFacade);
    }

    @Test
    void purchaseReturnHelpers_rejectReplayWhenQuantityDiffers() {
        com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement existingMovement =
                new com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement();
        existingMovement.setRawMaterial(material);
        existingMovement.setQuantity(new BigDecimal("0.75"));
        existingMovement.setUnitCost(new BigDecimal("5.00"));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                purchaseReturnService,
                "validateReturnReplay",
                material,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-QTY-MISMATCH",
                List.of(existingMovement)
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Purchase return reference already used with different payload");
    }

    @Test
    void purchaseReturnHelpers_rejectReplayWhenUnitCostDiffers() {
        com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement existingMovement =
                new com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement();
        existingMovement.setRawMaterial(material);
        existingMovement.setQuantity(BigDecimal.ONE);
        existingMovement.setUnitCost(new BigDecimal("4.50"));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                purchaseReturnService,
                "validateReturnReplay",
                material,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-COST-MISMATCH",
                List.of(existingMovement)
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Purchase return reference already used with different payload");
    }

    @Test
    void recordPurchaseReturn_rejectsOnHandShortfallAfterPosting() {
        Account payable = new Account();
        ReflectionTestUtils.setField(payable, "id", 40L);
        supplier.setPayableAccount(payable);
        when(allocationService.remainingReturnableQuantity(purchase, material)).thenReturn(new BigDecimal("3.0000"));
        when(accountingFacade.postPurchaseReturn(
                eq(10L),
                eq("PR-30"),
                eq(LocalDate.of(2026, 3, 9)),
                eq("Damaged - Resin to Supplier 10"),
                eq(Map.of(200L, new BigDecimal("5.00"))),
                eq(null),
                eq(null),
                eq(new BigDecimal("5.00"))
        )).thenReturn(journalEntryDto(901L, "PR-30", LocalDate.of(2026, 3, 9), "Damaged - Resin to Supplier 10"));
        when(rawMaterialRepository.deductStockIfSufficient(20L, BigDecimal.ONE)).thenReturn(0);

        assertThatThrownBy(() -> purchaseReturnService.recordPurchaseReturn(new PurchaseReturnRequest(
                10L,
                30L,
                20L,
                BigDecimal.ONE,
                new BigDecimal("5.00"),
                "PR-30",
                LocalDate.of(2026, 3, 9),
                "Damaged"
        )))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Cannot return more than on-hand inventory for Resin");

        verify(allocationService, never()).applyPurchaseReturnQuantity(any(), any(), any());
    }

    @Test
    void purchaseReturnHelpers_noopWhenReplayOrPostedPurchaseContextIsMissing() {
        ReflectionTestUtils.invokeMethod(purchaseReturnService, "ensurePostedPurchase", new Object[]{null});

        ReflectionTestUtils.invokeMethod(
                purchaseReturnService,
                "ensureLinkedCorrectionJournal",
                company,
                null,
                new JournalEntry(),
                "PI-NOOP"
        );
        ReflectionTestUtils.invokeMethod(
                purchaseReturnService,
                "ensureLinkedCorrectionJournal",
                company,
                journalEntryDto(null, "PR-NOOP", LocalDate.now(), "noop"),
                new JournalEntry(),
                "PI-NOOP"
        );

        JournalEntry sourceWithoutId = new JournalEntry();
        ReflectionTestUtils.invokeMethod(
                purchaseReturnService,
                "ensureLinkedCorrectionJournal",
                company,
                journalEntryDto(999L, "PR-NOOP", LocalDate.now(), "noop"),
                sourceWithoutId,
                "PI-NOOP"
        );

        verify(journalEntryRepository, never()).findByCompanyAndId(any(), any());
        verify(journalEntryRepository, never()).save(any());
    }

    @Test
    void purchaseReturnHelpers_rejectReversedAndBlankPurchaseStatuses() {
        RawMaterialPurchase reversed = new RawMaterialPurchase();
        reversed.setJournalEntry(purchase.getJournalEntry());
        reversed.setStatus("REVERSED");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                purchaseReturnService,
                "ensurePostedPurchase",
                reversed
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted purchases can be corrected");

        RawMaterialPurchase blankStatus = new RawMaterialPurchase();
        blankStatus.setJournalEntry(purchase.getJournalEntry());
        blankStatus.setStatus("   ");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                purchaseReturnService,
                "ensurePostedPurchase",
                blankStatus
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted purchases can be corrected");

        RawMaterialPurchase draftStatus = new RawMaterialPurchase();
        draftStatus.setJournalEntry(purchase.getJournalEntry());
        draftStatus.setStatus("DRAFT");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                purchaseReturnService,
                "ensurePostedPurchase",
                draftStatus
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted purchases can be corrected");

        RawMaterialPurchase journalWithoutId = new RawMaterialPurchase();
        journalWithoutId.setStatus("POSTED");
        journalWithoutId.setJournalEntry(new JournalEntry());

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                purchaseReturnService,
                "ensurePostedPurchase",
                journalWithoutId
        ))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Only posted purchases can be corrected");

        ReflectionTestUtils.invokeMethod(
                purchaseReturnService,
                "ensureLinkedCorrectionJournal",
                company,
                journalEntryDto(999L, "PR-NOOP", LocalDate.now(), "noop"),
                null,
                "PI-NOOP"
        );

        verify(journalEntryRepository, never()).findByCompanyAndId(any(), any());
        verify(journalEntryRepository, never()).save(any());
    }

    @Test
    void purchaseReturnHelpers_skipSaveWhenCorrectionJournalAlreadyAligned() {
        JournalEntry source = new JournalEntry();
        ReflectionTestUtils.setField(source, "id", 991L);

        JournalEntry aligned = new JournalEntry();
        aligned.setCorrectionType(JournalCorrectionType.REVERSAL);
        aligned.setCorrectionReason("PURCHASE_RETURN");
        aligned.setSourceModule("PURCHASING_RETURN");
        aligned.setSourceReference("PI-ALIGNED");

        when(journalEntryRepository.findByCompanyAndId(company, 992L)).thenReturn(Optional.of(aligned));

        ReflectionTestUtils.invokeMethod(
                purchaseReturnService,
                "ensureLinkedCorrectionJournal",
                company,
                journalEntryDto(992L, "PR-ALIGNED", LocalDate.now(), "aligned"),
                source,
                "PI-ALIGNED"
        );

        verify(journalEntryRepository, never()).save(aligned);
    }

    private JournalEntryDto journalEntryDto(Long id, String reference, LocalDate entryDate, String memo) {
        return new JournalEntryDto(
                id,
                null,
                reference,
                entryDate,
                memo,
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
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
