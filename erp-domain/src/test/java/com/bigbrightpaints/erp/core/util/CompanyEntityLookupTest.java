package com.bigbrightpaints.erp.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.service.CompanyScopedInventoryLookupService;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.service.CompanyScopedPurchasingLookupService;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.PromotionRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTargetRepository;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class CompanyEntityLookupTest {

  @Mock private DealerRepository dealerRepository;
  @Mock private CompanyScopedPurchasingLookupService purchasingLookupService;
  @Mock private CompanyScopedInventoryLookupService inventoryLookupService;
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
  @Mock private ProductionPlanRepository productionPlanRepository;
  @Mock private FactoryTaskRepository factoryTaskRepository;
  @Mock private EmployeeRepository employeeRepository;
  @Mock private LeaveRequestRepository leaveRequestRepository;

  private CompanyEntityLookup lookup;
  private Company company;

  @BeforeEach
  void setUp() {
    lookup =
        new CompanyEntityLookup(
            dealerRepository,
            purchasingLookupService,
            inventoryLookupService,
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
            productionPlanRepository,
            factoryTaskRepository,
            employeeRepository,
            leaveRequestRepository);
    company = new Company();
    ReflectionTestUtils.setField(company, "id", 44L);
    company.setCode("BBP");
    company.setTimezone("UTC");
  }

  @Test
  void requireSupplier_delegatesToPurchasingLookupService() {
    Supplier supplier = new Supplier();
    when(purchasingLookupService.requireSupplier(company, 77L)).thenReturn(supplier);

    assertThat(lookup.requireSupplier(company, 77L)).isSameAs(supplier);
  }

  @Test
  void requireActiveRawMaterial_delegatesToInventoryLookupService() {
    RawMaterial material = rawMaterial(10L, "RM-BBP-TIO2");
    when(inventoryLookupService.requireActiveRawMaterial(company, 10L)).thenReturn(material);

    assertThat(lookup.requireActiveRawMaterial(company, 10L)).isSameAs(material);
  }

  @Test
  void lockActiveRawMaterial_delegatesToInventoryLookupService() {
    RawMaterial material = rawMaterial(11L, "RM-BBP-ZINC");
    when(inventoryLookupService.lockActiveRawMaterial(company, 11L)).thenReturn(material);

    assertThat(lookup.lockActiveRawMaterial(company, 11L)).isSameAs(material);
  }

  @Test
  void lockActiveFinishedGood_delegatesToInventoryLookupService() {
    FinishedGood finishedGood = finishedGood(22L, "FG-BBP-EMULSION");
    when(inventoryLookupService.lockActiveFinishedGood(company, 22L)).thenReturn(finishedGood);

    assertThat(lookup.lockActiveFinishedGood(company, 22L)).isSameAs(finishedGood);
  }

  @Test
  void requireRawMaterialPurchase_delegatesToPurchasingLookupService() {
    RawMaterialPurchase purchase = new RawMaterialPurchase();
    when(purchasingLookupService.requireRawMaterialPurchase(company, 91L)).thenReturn(purchase);

    assertThat(lookup.requireRawMaterialPurchase(company, 91L)).isSameAs(purchase);
  }

  @Test
  void requireActiveFinishedGood_delegatesToInventoryLookupService() {
    FinishedGood finishedGood = finishedGood(23L, "FG-BBP-SATIN");
    when(inventoryLookupService.requireActiveFinishedGood(company, 23L)).thenReturn(finishedGood);

    assertThat(lookup.requireActiveFinishedGood(company, 23L)).isSameAs(finishedGood);
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
}
