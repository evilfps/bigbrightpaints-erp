package com.bigbrightpaints.erp.core.util;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTask;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlan;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlanRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequest;
import com.bigbrightpaints.erp.modules.sales.domain.CreditRequestRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Promotion;
import com.bigbrightpaints.erp.modules.sales.domain.PromotionRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTarget;
import com.bigbrightpaints.erp.modules.sales.domain.SalesTargetRepository;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequest;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequestRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import org.springframework.util.StringUtils;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class CompanyEntityLookup {

    private final DealerRepository dealerRepository;
    private final SupplierRepository supplierRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final InvoiceRepository invoiceRepository;
    private final ProductionBrandRepository productionBrandRepository;
    private final ProductionProductRepository productionProductRepository;
    private final ProductionLogRepository productionLogRepository;
    private final PromotionRepository promotionRepository;
    private final SalesTargetRepository salesTargetRepository;
    private final CreditRequestRepository creditRequestRepository;
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final AccountingPeriodRepository accountingPeriodRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final FactoryTaskRepository factoryTaskRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public CompanyEntityLookup(DealerRepository dealerRepository,
                               SupplierRepository supplierRepository,
                               RawMaterialRepository rawMaterialRepository,
                               FinishedGoodRepository finishedGoodRepository,
                               SalesOrderRepository salesOrderRepository,
                               InvoiceRepository invoiceRepository,
                               ProductionBrandRepository productionBrandRepository,
                               ProductionProductRepository productionProductRepository,
                               ProductionLogRepository productionLogRepository,
                               PromotionRepository promotionRepository,
                               SalesTargetRepository salesTargetRepository,
                               CreditRequestRepository creditRequestRepository,
                               AccountRepository accountRepository,
                               JournalEntryRepository journalEntryRepository,
                               AccountingPeriodRepository accountingPeriodRepository,
                               PayrollRunRepository payrollRunRepository,
                               RawMaterialPurchaseRepository rawMaterialPurchaseRepository,
                               ProductionPlanRepository productionPlanRepository,
                               FactoryTaskRepository factoryTaskRepository,
                               EmployeeRepository employeeRepository,
                               LeaveRequestRepository leaveRequestRepository) {
        this.dealerRepository = dealerRepository;
        this.supplierRepository = supplierRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.invoiceRepository = invoiceRepository;
        this.productionBrandRepository = productionBrandRepository;
        this.productionProductRepository = productionProductRepository;
        this.productionLogRepository = productionLogRepository;
        this.promotionRepository = promotionRepository;
        this.salesTargetRepository = salesTargetRepository;
        this.creditRequestRepository = creditRequestRepository;
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.accountingPeriodRepository = accountingPeriodRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.rawMaterialPurchaseRepository = rawMaterialPurchaseRepository;
        this.productionPlanRepository = productionPlanRepository;
        this.factoryTaskRepository = factoryTaskRepository;
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
    }

    public Dealer requireDealer(Company company, Long dealerId) {
        return dealerRepository.findByCompanyAndId(company, dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Dealer not found: id=" + dealerId));
    }

    public Supplier requireSupplier(Company company, Long supplierId) {
        return supplierRepository.findByCompanyAndId(company, supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier not found: id=" + supplierId));
    }

    public RawMaterial requireRawMaterial(Company company, Long rawMaterialId) {
        return rawMaterialRepository.findByCompanyAndId(company, rawMaterialId)
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found: id=" + rawMaterialId));
    }

    public RawMaterial requireActiveRawMaterial(Company company, Long rawMaterialId) {
        RawMaterial rawMaterial = requireRawMaterial(company, rawMaterialId);
        assertLinkedProductActive(company, rawMaterial.getSku(), "raw material", rawMaterialId);
        return rawMaterial;
    }

    public RawMaterial lockActiveRawMaterial(Company company, Long rawMaterialId) {
        RawMaterial rawMaterial = rawMaterialRepository.lockByCompanyAndId(company, rawMaterialId)
                .orElseThrow(() -> new IllegalArgumentException("Raw material not found: id=" + rawMaterialId));
        assertLinkedProductActive(company, rawMaterial.getSku(), "raw material", rawMaterialId);
        return rawMaterial;
    }

    public SalesOrder requireSalesOrder(Company company, Long orderId) {
        return salesOrderRepository.findByCompanyAndId(company, orderId)
                .orElseThrow(() -> new IllegalArgumentException("Sales order not found: id=" + orderId));
    }

    public Invoice requireInvoice(Company company, Long invoiceId) {
        return invoiceRepository.findByCompanyAndId(company, invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: id=" + invoiceId));
    }

    public ProductionBrand requireProductionBrand(Company company, Long brandId) {
        return productionBrandRepository.findByCompanyAndId(company, brandId)
                .orElseThrow(() -> new IllegalArgumentException("Production brand not found: id=" + brandId));
    }

    public ProductionProduct requireProductionProduct(Company company, Long productId) {
        return productionProductRepository.findByCompanyAndId(company, productId)
                .orElseThrow(() -> new IllegalArgumentException("Production product not found: id=" + productId));
    }

    public FinishedGood requireActiveFinishedGood(Company company, Long finishedGoodId) {
        FinishedGood finishedGood = finishedGoodRepository.findByCompanyAndId(company, finishedGoodId)
                .orElseThrow(() -> new IllegalArgumentException("Finished good not found: id=" + finishedGoodId));
        assertLinkedProductActive(company, finishedGood.getProductCode(), "finished good", finishedGoodId);
        return finishedGood;
    }

    public FinishedGood lockActiveFinishedGood(Company company, Long finishedGoodId) {
        FinishedGood finishedGood = finishedGoodRepository.lockByCompanyAndId(company, finishedGoodId)
                .orElseThrow(() -> new IllegalArgumentException("Finished good not found: id=" + finishedGoodId));
        assertLinkedProductActive(company, finishedGood.getProductCode(), "finished good", finishedGoodId);
        return finishedGood;
    }

    public ProductionLog requireProductionLog(Company company, Long logId) {
        return productionLogRepository.findByCompanyAndId(company, logId)
                .orElseThrow(() -> new IllegalArgumentException("Production log not found: id=" + logId));
    }

    public ProductionLog lockProductionLog(Company company, Long logId) {
        return productionLogRepository.lockByCompanyAndId(company, logId)
                .orElseThrow(() -> new IllegalArgumentException("Production log not found: id=" + logId));
    }

    public Promotion requirePromotion(Company company, Long promotionId) {
        return promotionRepository.findByCompanyAndId(company, promotionId)
                .orElseThrow(() -> new IllegalArgumentException("Promotion not found: id=" + promotionId));
    }

    public SalesTarget requireSalesTarget(Company company, Long targetId) {
        return salesTargetRepository.findByCompanyAndId(company, targetId)
                .orElseThrow(() -> new IllegalArgumentException("Sales target not found: id=" + targetId));
    }

    public CreditRequest requireCreditRequest(Company company, Long creditRequestId) {
        return creditRequestRepository.findByCompanyAndId(company, creditRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Credit request not found: id=" + creditRequestId));
    }

    public Account requireAccount(Company company, Long accountId) {
        return accountRepository.findByCompanyAndId(company, accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: id=" + accountId));
    }

    public JournalEntry requireJournalEntry(Company company, Long journalEntryId) {
        return journalEntryRepository.findByCompanyAndId(company, journalEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal entry not found: id=" + journalEntryId));
    }

    public Optional<JournalEntry> findJournalEntryByReference(Company company, String referenceNumber) {
        return journalEntryRepository.findByCompanyAndReferenceNumber(company, referenceNumber);
    }

    public Optional<JournalEntry> findJournalEntryByReferencePrefix(Company company, String referencePrefix) {
        if (referencePrefix == null || referencePrefix.isBlank()) {
            return Optional.empty();
        }
        return journalEntryRepository.findFirstByCompanyAndReferenceNumberStartingWith(company, referencePrefix);
    }

    public AccountingPeriod requireAccountingPeriod(Company company, Long accountingPeriodId) {
        return accountingPeriodRepository.findByCompanyAndId(company, accountingPeriodId)
                .orElseThrow(() -> new IllegalArgumentException("Accounting period not found: id=" + accountingPeriodId));
    }

    public PayrollRun requirePayrollRun(Company company, Long payrollRunId) {
        return payrollRunRepository.findByCompanyAndId(company, payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: id=" + payrollRunId));
    }

    public PayrollRun lockPayrollRun(Company company, Long payrollRunId) {
        return payrollRunRepository.lockByCompanyAndId(company, payrollRunId)
                .orElseThrow(() -> new IllegalArgumentException("Payroll run not found: id=" + payrollRunId));
    }

    public RawMaterialPurchase requireRawMaterialPurchase(Company company, Long purchaseId) {
        return rawMaterialPurchaseRepository.findByCompanyAndId(company, purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Raw material purchase not found: id=" + purchaseId));
    }

    public ProductionPlan requireProductionPlan(Company company, Long planId) {
        return productionPlanRepository.findByCompanyAndId(company, planId)
                .orElseThrow(() -> new IllegalArgumentException("Production plan not found"));
    }

    public FactoryTask requireFactoryTask(Company company, Long taskId) {
        return factoryTaskRepository.findByCompanyAndId(company, taskId)
                .orElseThrow(() -> new IllegalArgumentException("Factory task not found"));
    }

    public Employee requireEmployee(Company company, Long employeeId) {
        return employeeRepository.findByCompanyAndId(company, employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found"));
    }

    public LeaveRequest requireLeaveRequest(Company company, Long leaveRequestId) {
        return leaveRequestRepository.findByCompanyAndId(company, leaveRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Leave request not found"));
    }

    private void assertLinkedProductActive(Company company, String sku, String entityType, Long entityId) {
        if (company == null || !StringUtils.hasText(sku)) {
            return;
        }
        ProductionProduct linkedProduct = productionProductRepository.findByCompanyAndSkuCodeIgnoreCase(company, sku)
                .orElse(null);
        if (linkedProduct == null || linkedProduct.isActive()) {
            return;
        }
        throw new ApplicationException(ErrorCode.BUSINESS_INVALID_STATE,
                "Catalog item is inactive for " + entityType + " " + sku)
                .withDetail("sku", sku)
                .withDetail(entityType.replace(' ', '_') + "Id", entityId)
                .withDetail("productId", linkedProduct.getId());
    }
}
