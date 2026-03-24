package com.bigbrightpaints.erp.core.util;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlanRepository;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequestRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.PromotionRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTargetRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class CompanyEntityLookupTest {

    @Mock private DealerRepository dealerRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialRepository rawMaterialRepository;
    @Mock private FinishedGoodRepository finishedGoodRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private ProductionBrandRepository productionBrandRepository;
    @Mock private ProductionProductRepository productionProductRepository;
    @Mock private ProductionLogRepository productionLogRepository;
    @Mock private PromotionRepository promotionRepository;
    @Mock private SalesTargetRepository salesTargetRepository;
    @Mock private CreditRequestRepository creditRequestRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private AccountingPeriodRepository accountingPeriodRepository;
    @Mock private PayrollRunRepository payrollRunRepository;
    @Mock private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private FactoryTaskRepository factoryTaskRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;

    private CompanyEntityLookup lookup;
    private Company company;

    @BeforeEach
    void setUp() {
        lookup = new CompanyEntityLookup(
                dealerRepository,
                supplierRepository,
                rawMaterialRepository,
                finishedGoodRepository,
                salesOrderRepository,
                invoiceRepository,
                productionBrandRepository,
                productionProductRepository,
                productionLogRepository,
                promotionRepository,
                salesTargetRepository,
                creditRequestRepository,
                accountRepository,
                journalEntryRepository,
                accountingPeriodRepository,
                payrollRunRepository,
                rawMaterialPurchaseRepository,
                productionPlanRepository,
                factoryTaskRepository,
                employeeRepository,
                leaveRequestRepository
        );
        company = new Company();
        ReflectionTestUtils.setField(company, "id", 44L);
        company.setCode("BBP");
        company.setTimezone("UTC");
    }

    @Test
    void requireActiveRawMaterial_returnsMaterialWhenLinkedProductIsActive() {
        RawMaterial material = rawMaterial(10L, "RM-BBP-TIO2");
        ProductionProduct product = productionProduct(88L, true);
        when(rawMaterialRepository.findByCompanyAndId(company, 10L)).thenReturn(Optional.of(material));
        when(productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, "RM-BBP-TIO2"))
                .thenReturn(Optional.of(product));

        RawMaterial resolved = lookup.requireActiveRawMaterial(company, 10L);

        assertThat(resolved).isSameAs(material);
    }

    @Test
    void lockActiveRawMaterial_skipsCatalogLookupWhenSkuIsBlank() {
        RawMaterial material = rawMaterial(11L, "   ");
        when(rawMaterialRepository.lockByCompanyAndId(company, 11L)).thenReturn(Optional.of(material));

        RawMaterial resolved = lookup.lockActiveRawMaterial(company, 11L);

        assertThat(resolved).isSameAs(material);
        verifyNoInteractions(productionProductRepository);
    }

    @Test
    void requireActiveRawMaterial_rejectsInactiveLinkedProduct() {
        RawMaterial material = rawMaterial(12L, "RM-BBP-ZINC");
        ProductionProduct product = productionProduct(89L, false);
        when(rawMaterialRepository.findByCompanyAndId(company, 12L)).thenReturn(Optional.of(material));
        when(productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, "RM-BBP-ZINC"))
                .thenReturn(Optional.of(product));

        assertThatThrownBy(() -> lookup.requireActiveRawMaterial(company, 12L))
                .isInstanceOf(ApplicationException.class)
                .matches(ex -> ((ApplicationException) ex).getErrorCode() == ErrorCode.BUSINESS_INVALID_STATE)
                .hasMessageContaining("Catalog item is inactive for raw material RM-BBP-ZINC");
    }

    @Test
    void requireActiveFinishedGood_returnsFinishedGoodWhenCatalogEntryIsMissing() {
        FinishedGood finishedGood = finishedGood(21L, "FG-BBP-PRIMER");
        when(finishedGoodRepository.findByCompanyAndId(company, 21L)).thenReturn(Optional.of(finishedGood));
        when(productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, "FG-BBP-PRIMER"))
                .thenReturn(Optional.empty());

        FinishedGood resolved = lookup.requireActiveFinishedGood(company, 21L);

        assertThat(resolved).isSameAs(finishedGood);
    }

    @Test
    void requireActiveRawMaterial_skipsCatalogLookupWhenCompanyIsNull() {
        RawMaterial material = rawMaterial(13L, "RM-NO-COMPANY");
        when(rawMaterialRepository.findByCompanyAndId(null, 13L)).thenReturn(Optional.of(material));

        RawMaterial resolved = lookup.requireActiveRawMaterial(null, 13L);

        assertThat(resolved).isSameAs(material);
        verifyNoInteractions(productionProductRepository);
    }

    @Test
    void lockActiveFinishedGood_returnsFinishedGoodWhenLinkedProductIsActive() {
        FinishedGood finishedGood = finishedGood(23L, "FG-BBP-SATIN");
        ProductionProduct product = productionProduct(91L, true);
        when(finishedGoodRepository.lockByCompanyAndId(company, 23L)).thenReturn(Optional.of(finishedGood));
        when(productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, "FG-BBP-SATIN"))
                .thenReturn(Optional.of(product));

        FinishedGood resolved = lookup.lockActiveFinishedGood(company, 23L);

        assertThat(resolved).isSameAs(finishedGood);
    }

    @Test
    void lockActiveFinishedGood_rejectsInactiveLinkedProduct() {
        FinishedGood finishedGood = finishedGood(22L, "FG-BBP-EMULSION");
        ProductionProduct product = productionProduct(90L, false);
        when(finishedGoodRepository.lockByCompanyAndId(company, 22L)).thenReturn(Optional.of(finishedGood));
        when(productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, "FG-BBP-EMULSION"))
                .thenReturn(Optional.of(product));

        assertThatThrownBy(() -> lookup.lockActiveFinishedGood(company, 22L))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("Catalog item is inactive for finished good FG-BBP-EMULSION");

        verify(finishedGoodRepository).lockByCompanyAndId(company, 22L);
    }

    private RawMaterial rawMaterial(Long id, String sku) {
        RawMaterial material = new RawMaterial();
        ReflectionTestUtils.setField(material, "id", id);
        material.setSku(sku);
        return material;
    }

    private FinishedGood finishedGood(Long id, String productCode) {
        FinishedGood finishedGood = new FinishedGood();
        ReflectionTestUtils.setField(finishedGood, "id", id);
        finishedGood.setProductCode(productCode);
        return finishedGood;
    }

    private ProductionProduct productionProduct(Long id, boolean active) {
        ProductionProduct product = new ProductionProduct();
        ReflectionTestUtils.setField(product, "id", id);
        product.setActive(active);
        return product;
    }
}
