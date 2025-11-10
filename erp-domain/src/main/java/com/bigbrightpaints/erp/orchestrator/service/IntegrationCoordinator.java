package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountRequest;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryDashboardDto;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryTaskDto;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryTaskRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionBatchDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionBatchRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanRequest;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeDto;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunRequest;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.modules.reports.dto.AgedDebtorDto;
import com.bigbrightpaints.erp.modules.reports.dto.CashFlowDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.dto.DealerDto;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IntegrationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(IntegrationCoordinator.class);

    private final SalesService salesService;
    private final FactoryService factoryService;
    private final FinishedGoodsService finishedGoodsService;
    private final InvoiceService invoiceService;
    private final AccountingService accountingService;
    private final HrService hrService;
    private final ReportService reportService;

    public IntegrationCoordinator(SalesService salesService,
                                  FactoryService factoryService,
                                  FinishedGoodsService finishedGoodsService,
                                  InvoiceService invoiceService,
                                  AccountingService accountingService,
                                  HrService hrService,
                                  ReportService reportService) {
        this.salesService = salesService;
        this.factoryService = factoryService;
        this.finishedGoodsService = finishedGoodsService;
        this.invoiceService = invoiceService;
        this.accountingService = accountingService;
        this.hrService = hrService;
        this.reportService = reportService;
    }

    public void reserveInventory(String orderId, String companyId) {
        Long id = parseNumericId(orderId);
        if (id != null) {
            finishedGoodsService.reserveForOrder(salesService.getOrderWithItems(id));
            salesService.updateStatus(id, "RESERVED");
            log.info("Reserved inventory for order {}", id);
        }
    }

    public void queueProduction(String orderId, String companyId) {
        ProductionPlanRequest request = new ProductionPlanRequest(
                "PLAN-" + orderId,
                "Order " + orderId,
                1.0,
                LocalDate.now().plusDays(1),
                "Auto-generated from orchestrator");
        factoryService.createPlan(request);
        log.info("Queued production plan for order {}", orderId);
    }

    public void createAccountingEntry(String orderId, BigDecimal amount, String companyId) {
        postJournal("SALE-" + orderId, amount == null ? BigDecimal.ZERO : amount,
                "Auto journal for order " + orderId);
    }

    private void postCogsEntry(String orderId, List<FinishedGoodsService.DispatchPosting> postings) {
        if (postings == null || postings.isEmpty()) {
            return;
        }
        List<JournalEntryRequest.JournalLineRequest> lines = new ArrayList<>();
        for (FinishedGoodsService.DispatchPosting posting : postings) {
            if (posting.cogsAccountId() == null || posting.inventoryAccountId() == null) {
                continue;
            }
            BigDecimal cost = posting.cost();
            if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String memo = "COGS for order " + orderId;
            lines.add(new JournalEntryRequest.JournalLineRequest(posting.cogsAccountId(), memo, cost, BigDecimal.ZERO));
            lines.add(new JournalEntryRequest.JournalLineRequest(posting.inventoryAccountId(), memo, BigDecimal.ZERO, cost));
        }
        if (lines.isEmpty()) {
            return;
        }
        JournalEntryRequest request = new JournalEntryRequest(
                "COGS-" + orderId + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                LocalDate.now(),
                "COGS posting for order " + orderId,
                null,
                lines
        );
        accountingService.createJournalEntry(request);
    }

    public void autoApproveOrder(String orderId, BigDecimal amount, String companyId) {
        reserveInventory(orderId, companyId);
        if (amount != null) {
            createAccountingEntry(orderId, amount, companyId);
        }
        Long id = parseNumericId(orderId);
        if (id != null) {
            List<FinishedGoodsService.DispatchPosting> postings = finishedGoodsService.markSlipDispatched(id);
            postCogsEntry(orderId, postings);
            invoiceService.issueInvoiceForOrder(id);
            salesService.updateStatus(id, "APPROVED");
        }
        log.info("Auto-approved order {} for company {}", orderId, companyId);
    }

    public void updateProductionStatus(String planId, String companyId) {
        Long id = parseNumericId(planId);
        if (id != null) {
            factoryService.updatePlanStatus(id, "COMPLETED");
            log.info("Marked production plan {} as completed", planId);
        }
    }

    public void releaseInventory(String batchId, String companyId) {
        ProductionBatchRequest request = new ProductionBatchRequest(
                batchId + "-DISPATCH",
                0.0,
                "system",
                "Auto release for dispatch " + batchId);
        factoryService.logBatch(null, request);
        log.info("Logged release batch {}", batchId);
    }

    public void postDispatchJournal(String batchId, String companyId) {
        postJournal("DISPATCH-" + batchId, BigDecimal.ZERO, "Dispatch journal for batch " + batchId);
    }

    public void syncEmployees(String companyId) {
        hrService.listEmployees();
        log.info("Synced employees view for company {}", companyId);
    }

    public void generatePayroll(LocalDate payrollDate, String companyId) {
        hrService.createPayrollRun(new PayrollRunRequest(payrollDate, "Auto payroll run"));
        log.info("Triggered payroll run for {}", payrollDate);
    }

    public void postPayrollVouchers(LocalDate payrollDate, String companyId) {
        postJournal("PAYROLL-" + payrollDate, BigDecimal.ZERO, "Payroll vouchers for " + payrollDate);
    }

    public Map<String, Object> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("orders", salesService.listOrders(null).size());
        health.put("plans", factoryService.listPlans().size());
        health.put("accounts", accountingService.listAccounts().size());
        health.put("employees", hrService.listEmployees().size());
        return health;
    }

    public Map<String, Object> fetchAdminDashboard(String companyId) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("orders", fetchOrdersSnapshot());
        snapshot.put("dealers", fetchDealerSnapshot());
        snapshot.put("accounting", fetchAccountingSnapshot());
        snapshot.put("hr", fetchHrSnapshot());
        return snapshot;
    }

    public Map<String, Object> fetchFactoryDashboard(String companyId) {
        Map<String, Object> snapshot = new HashMap<>();
        FactoryDashboardDto dashboard = factoryService.dashboard();
        snapshot.put("production", Map.of(
                "efficiency", dashboard.productionEfficiency(),
                "completed", dashboard.completedPlans(),
                "batchesLogged", dashboard.batchesLogged()));
        snapshot.put("tasks", factoryService.listTasks().size());
        snapshot.put("inventory", fetchInventorySnapshot());
        return snapshot;
    }

    public Map<String, Object> fetchFinanceDashboard(String companyId) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("cashflow", fetchCashflowSnapshot());
        snapshot.put("agedDebtors", reportService.agedDebtors());
        snapshot.put("ledger", fetchAccountingSnapshot());
        return snapshot;
    }

    private Map<String, Object> fetchOrdersSnapshot() {
        List<SalesOrderDto> orders = salesService.listOrders(null);
        long pending = orders.stream().filter(o -> "PENDING".equalsIgnoreCase(o.status())).count();
        long approved = orders.stream().filter(o -> "CONFIRMED".equalsIgnoreCase(o.status())
                || "APPROVED".equalsIgnoreCase(o.status())).count();
        return Map.of("pending", pending, "approved", approved, "total", orders.size());
    }

    private Map<String, Object> fetchDealerSnapshot() {
        List<DealerDto> dealers = salesService.listDealers();
        long active = dealers.stream().filter(d -> "ACTIVE".equalsIgnoreCase(d.status())).count();
        BigDecimal outstanding = dealers.stream()
                .map(DealerDto::outstandingBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("active", active, "total", dealers.size(), "creditUtilization", outstanding);
    }

    private Map<String, Object> fetchAccountingSnapshot() {
        List<AccountDto> accounts = accountingService.listAccounts();
        BigDecimal balance = accounts.stream()
                .map(AccountDto::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("accounts", accounts.size(), "ledgerBalance", balance);
    }

    private Map<String, Object> fetchHrSnapshot() {
        List<EmployeeDto> employees = hrService.listEmployees();
        long active = employees.stream().filter(e -> "ACTIVE".equalsIgnoreCase(e.status())).count();
        long onLeave = hrService.listLeaveRequests().stream()
                .filter(l -> "APPROVED".equalsIgnoreCase(l.status()))
                .count();
        return Map.of("activeEmployees", active, "onLeave", onLeave);
    }

    private Map<String, Object> fetchInventorySnapshot() {
        InventoryValuationDto valuation = reportService.inventoryValuation();
        return Map.of("value", valuation.totalValue(), "lowStock", valuation.lowStockItems());
    }

    private Map<String, Object> fetchCashflowSnapshot() {
        CashFlowDto cashflow = reportService.cashFlow();
        return Map.of("operating", cashflow.operating(),
                "investing", cashflow.investing(),
                "financing", cashflow.financing(),
                "net", cashflow.netChange());
    }

    private void postJournal(String reference, BigDecimal amount, String memo) {
        ensureDefaultAccounts();
        List<AccountDto> accounts = accountingService.listAccounts();
        if (accounts.size() < 2) {
            log.warn("Unable to post journal entry; not enough accounts");
            return;
        }
        AccountDto debit = accounts.get(0);
        AccountDto credit = accounts.get(1);
        JournalEntryRequest request = new JournalEntryRequest(
                reference,
                LocalDate.now(),
                memo,
                null,
                List.of(
                        new JournalEntryRequest.JournalLineRequest(debit.id(), memo, amount.abs(), BigDecimal.ZERO),
                        new JournalEntryRequest.JournalLineRequest(credit.id(), memo, BigDecimal.ZERO, amount.abs())
                ));
        accountingService.createJournalEntry(request);
    }

    private void ensureDefaultAccounts() {
        List<AccountDto> accounts = accountingService.listAccounts();
        if (accounts.size() >= 2) {
            return;
        }
        String suffix = UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        accountingService.createAccount(new AccountRequest("1000-" + suffix, "Auto Cash", "ASSET"));
        accountingService.createAccount(new AccountRequest("4000-" + suffix, "Auto Revenue", "REVENUE"));
    }

    private Long parseNumericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            log.warn("Value {} is not a numeric identifier", id);
            return null;
        }
    }
}
