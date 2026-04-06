package com.bigbrightpaints.erp.core.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyScopedAccountingLookupService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTask;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlan;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlanRepository;
import com.bigbrightpaints.erp.modules.factory.service.CompanyScopedFactoryLookupService;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequest;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequestRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.hr.service.CompanyScopedHrLookupService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.service.CompanyScopedInventoryLookupService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.service.CompanyScopedInvoiceLookupService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.service.CompanyScopedProductionLookupService;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.service.CompanyScopedPurchasingLookupService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Promotion;
import com.bigbrightpaints.erp.modules.sales.domain.PromotionRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTarget;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTargetRepository;
import com.bigbrightpaints.erp.modules.sales.service.CompanyScopedSalesLookupService;

@Deprecated(forRemoval = true)
@Component
public class CompanyEntityLookup {

  private final CompanyScopedAccountingLookupService accountingLookupService;
  private final CompanyScopedSalesLookupService salesLookupService;
  private final CompanyScopedPurchasingLookupService purchasingLookupService;
  private final CompanyScopedInventoryLookupService inventoryLookupService;
  private final CompanyScopedFactoryLookupService factoryLookupService;
  private final CompanyScopedHrLookupService hrLookupService;
  private final CompanyScopedProductionLookupService productionLookupService;
  private final CompanyScopedInvoiceLookupService invoiceLookupService;

  @Autowired
  public CompanyEntityLookup(
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyScopedSalesLookupService salesLookupService,
      CompanyScopedPurchasingLookupService purchasingLookupService,
      CompanyScopedFactoryLookupService factoryLookupService,
      CompanyScopedHrLookupService hrLookupService,
      CompanyScopedProductionLookupService productionLookupService,
      CompanyScopedInvoiceLookupService invoiceLookupService) {
    this(
        accountingLookupService,
        salesLookupService,
        purchasingLookupService,
        null,
        factoryLookupService,
        hrLookupService,
        productionLookupService,
        invoiceLookupService);
  }

  private CompanyEntityLookup(
      CompanyScopedAccountingLookupService accountingLookupService,
      CompanyScopedSalesLookupService salesLookupService,
      CompanyScopedPurchasingLookupService purchasingLookupService,
      CompanyScopedInventoryLookupService inventoryLookupService,
      CompanyScopedFactoryLookupService factoryLookupService,
      CompanyScopedHrLookupService hrLookupService,
      CompanyScopedProductionLookupService productionLookupService,
      CompanyScopedInvoiceLookupService invoiceLookupService) {
    this.accountingLookupService = accountingLookupService;
    this.salesLookupService = salesLookupService;
    this.purchasingLookupService = purchasingLookupService;
    this.inventoryLookupService = inventoryLookupService;
    this.factoryLookupService = factoryLookupService;
    this.hrLookupService = hrLookupService;
    this.productionLookupService = productionLookupService;
    this.invoiceLookupService = invoiceLookupService;
  }

  public CompanyEntityLookup(
      DealerRepository dealerRepository,
      CompanyScopedPurchasingLookupService purchasingLookupService,
      CompanyScopedInventoryLookupService inventoryLookupService,
      SalesOrderRepository salesOrderRepository,
      InvoiceRepository invoiceRepository,
      ProductionBrandRepository productionBrandRepository,
      ProductionProductRepository productionProductRepository,
      ProductionLogRepository productionLogRepository,
      PromotionRepository promotionRepository,
      SalesTargetRepository salesTargetRepository,
      com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository creditRequestRepository,
      com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository accountRepository,
      com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository
          journalEntryRepository,
      com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository
          accountingPeriodRepository,
      PayrollRunRepository payrollRunRepository,
      ProductionPlanRepository productionPlanRepository,
      FactoryTaskRepository factoryTaskRepository,
      EmployeeRepository employeeRepository,
      LeaveRequestRepository leaveRequestRepository) {
    this(
        new CompanyScopedAccountingLookupService(
            new CompanyScopedLookupService(),
            accountRepository,
            journalEntryRepository,
            accountingPeriodRepository),
        new CompanyScopedSalesLookupService(
            new CompanyScopedLookupService(),
            dealerRepository,
            salesOrderRepository,
            promotionRepository,
            salesTargetRepository),
        purchasingLookupService,
        inventoryLookupService,
        new CompanyScopedFactoryLookupService(
            new CompanyScopedLookupService(),
            productionLogRepository,
            productionPlanRepository,
            factoryTaskRepository),
        new CompanyScopedHrLookupService(
            new CompanyScopedLookupService(),
            employeeRepository,
            leaveRequestRepository,
            payrollRunRepository),
        new CompanyScopedProductionLookupService(
            new CompanyScopedLookupService(),
            productionBrandRepository,
            productionProductRepository),
        new CompanyScopedInvoiceLookupService(new CompanyScopedLookupService(), invoiceRepository));
  }

  public Dealer requireDealer(Company company, Long dealerId) {
    return salesLookupService.requireDealer(company, dealerId);
  }

  public Supplier requireSupplier(Company company, Long supplierId) {
    return purchasingLookupService.requireSupplier(company, supplierId);
  }

  public RawMaterial requireActiveRawMaterial(Company company, Long rawMaterialId) {
    return inventoryLookupService.requireActiveRawMaterial(company, rawMaterialId);
  }

  public RawMaterial lockActiveRawMaterial(Company company, Long rawMaterialId) {
    return inventoryLookupService.lockActiveRawMaterial(company, rawMaterialId);
  }

  public SalesOrder requireSalesOrder(Company company, Long orderId) {
    return salesLookupService.requireSalesOrder(company, orderId);
  }

  public Invoice requireInvoice(Company company, Long invoiceId) {
    return invoiceLookupService.requireInvoice(company, invoiceId);
  }

  public ProductionBrand requireProductionBrand(Company company, Long brandId) {
    return productionLookupService.requireProductionBrand(company, brandId);
  }

  public ProductionProduct requireProductionProduct(Company company, Long productId) {
    return productionLookupService.requireProductionProduct(company, productId);
  }

  public ProductionLog requireProductionLog(Company company, Long logId) {
    return factoryLookupService.requireProductionLog(company, logId);
  }

  public ProductionLog lockProductionLog(Company company, Long logId) {
    return factoryLookupService.lockProductionLog(company, logId);
  }

  public FinishedGood requireActiveFinishedGood(Company company, Long finishedGoodId) {
    return inventoryLookupService.requireActiveFinishedGood(company, finishedGoodId);
  }

  public FinishedGood lockActiveFinishedGood(Company company, Long finishedGoodId) {
    return inventoryLookupService.lockActiveFinishedGood(company, finishedGoodId);
  }

  public Promotion requirePromotion(Company company, Long promotionId) {
    return salesLookupService.requirePromotion(company, promotionId);
  }

  public SalesTarget requireSalesTarget(Company company, Long targetId) {
    return salesLookupService.requireSalesTarget(company, targetId);
  }

  public Account requireAccount(Company company, Long accountId) {
    return accountingLookupService.requireAccount(company, accountId);
  }

  public JournalEntry requireJournalEntry(Company company, Long journalEntryId) {
    return accountingLookupService.requireJournalEntry(company, journalEntryId);
  }

  public AccountingPeriod requireAccountingPeriod(Company company, Long accountingPeriodId) {
    return accountingLookupService.requireAccountingPeriod(company, accountingPeriodId);
  }

  public PayrollRun lockPayrollRun(Company company, Long payrollRunId) {
    return hrLookupService.lockPayrollRun(company, payrollRunId);
  }

  public RawMaterialPurchase requireRawMaterialPurchase(Company company, Long purchaseId) {
    return purchasingLookupService.requireRawMaterialPurchase(company, purchaseId);
  }

  public ProductionPlan requireProductionPlan(Company company, Long planId) {
    return factoryLookupService.requireProductionPlan(company, planId);
  }

  public FactoryTask requireFactoryTask(Company company, Long taskId) {
    return factoryLookupService.requireFactoryTask(company, taskId);
  }

  public Employee requireEmployee(Company company, Long employeeId) {
    return hrLookupService.requireEmployee(company, employeeId);
  }

  public LeaveRequest requireLeaveRequest(Company company, Long leaveRequestId) {
    return hrLookupService.requireLeaveRequest(company, leaveRequestId);
  }
}
