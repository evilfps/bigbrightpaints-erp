package com.bigbrightpaints.erp.modules.purchasing.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.GstService;
import com.bigbrightpaints.erp.modules.accounting.service.ReferenceNumberService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierStatus;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        purchase.setTaxAmount(BigDecimal.ZERO);
        RawMaterialPurchaseLine line = new RawMaterialPurchaseLine();
        line.setPurchase(purchase);
        line.setRawMaterial(material);
        line.setQuantity(new BigDecimal("4.0000"));
        line.setLineTotal(new BigDecimal("20.00"));
        purchase.getLines().add(line);

        when(companyContextService.requireCurrentCompany()).thenReturn(company);
        when(companyEntityLookup.requireSupplier(company, 10L)).thenReturn(supplier);
        when(purchaseRepository.lockByCompanyAndId(company, 30L)).thenReturn(Optional.of(purchase));
        when(rawMaterialRepository.lockByCompanyAndId(company, 20L)).thenReturn(Optional.of(material));
        when(movementRepository.findByRawMaterialCompanyAndReferenceTypeAndReferenceId(eq(company), eq(com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference.PURCHASE_RETURN), eq("PR-30")))
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
}
