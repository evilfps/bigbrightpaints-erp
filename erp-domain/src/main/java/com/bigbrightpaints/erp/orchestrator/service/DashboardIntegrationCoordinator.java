package com.bigbrightpaints.erp.orchestrator.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryDashboardDto;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeDto;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.modules.reports.dto.CashFlowDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportQueryRequestBuilder;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.dto.DealerDto;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;

@Service
class DashboardIntegrationCoordinator {

  private final SalesService salesService;
  private final FactoryService factoryService;
  private final AccountingService accountingService;
  private final HrService hrService;
  private final ReportService reportService;
  private final IntegrationCoordinatorSupportService supportService;

  DashboardIntegrationCoordinator(
      SalesService salesService,
      FactoryService factoryService,
      AccountingService accountingService,
      HrService hrService,
      ReportService reportService,
      IntegrationCoordinatorSupportService supportService) {
    this.salesService = salesService;
    this.factoryService = factoryService;
    this.accountingService = accountingService;
    this.hrService = hrService;
    this.reportService = reportService;
    this.supportService = supportService;
  }

  @Transactional(readOnly = true)
  Map<String, Object> health() {
    Company company =
        supportService.requireCompany(
            com.bigbrightpaints.erp.core.security.CompanyContextHolder.getCompanyCode(), "health");
    boolean hrPayrollEnabled = isHrPayrollEnabled(company);
    Map<String, Object> health = new HashMap<>();
    health.put("orders", salesService.listOrders(null).size());
    health.put("plans", factoryService.listPlans().size());
    health.put("accounts", accountingService.listAccounts().size());
    if (hrPayrollEnabled) {
      health.put("employees", hrService.listEmployees().size());
    }
    return health;
  }

  @Transactional(readOnly = true)
  Map<String, Object> fetchAdminDashboard(String companyId) {
    return supportService.withCompanyContext(
        companyId,
        () -> {
          Company company = supportService.requireCompany(companyId, "fetchAdminDashboard");
          Map<String, Object> snapshot = new HashMap<>();
          snapshot.put("orders", fetchOrdersSnapshot());
          snapshot.put("dealers", fetchDealerSnapshot());
          snapshot.put("accounting", fetchAccountingSnapshot());
          if (isHrPayrollEnabled(company)) {
            snapshot.put("hr", fetchHrSnapshot());
          }
          return snapshot;
        });
  }

  @Transactional(readOnly = true)
  Map<String, Object> fetchFactoryDashboard(String companyId) {
    return supportService.withCompanyContext(
        companyId,
        () -> {
          Map<String, Object> snapshot = new HashMap<>();
          FactoryDashboardDto dashboard = factoryService.dashboard();
          snapshot.put(
              "production",
              Map.of(
                  "efficiency", dashboard.productionEfficiency(),
                  "completed", dashboard.completedPlans(),
                  "batchesLogged", dashboard.batchesLogged()));
          snapshot.put("tasks", factoryService.listTasks().size());
          snapshot.put("inventory", fetchInventorySnapshot());
          return snapshot;
        });
  }

  @Transactional(readOnly = true)
  Map<String, Object> fetchFinanceDashboard(String companyId) {
    return supportService.withCompanyContext(
        companyId,
        () -> {
          Map<String, Object> snapshot = new HashMap<>();
          snapshot.put("cashflow", fetchCashflowSnapshot());
          snapshot.put("agedDebtors", reportService.agedDebtors(ReportQueryRequestBuilder.empty()));
          snapshot.put("ledger", fetchAccountingSnapshot());
          snapshot.put("reconciliation", reportService.inventoryReconciliation());
          return snapshot;
        });
  }

  private Map<String, Object> fetchOrdersSnapshot() {
    List<SalesOrderDto> orders = salesService.listOrders(null);
    long pending = orders.stream().filter(o -> "PENDING".equalsIgnoreCase(o.status())).count();
    long approved =
        orders.stream()
            .filter(
                o ->
                    "CONFIRMED".equalsIgnoreCase(o.status())
                        || "APPROVED".equalsIgnoreCase(o.status()))
            .count();
    return Map.of("pending", pending, "approved", approved, "total", orders.size());
  }

  private Map<String, Object> fetchDealerSnapshot() {
    List<DealerDto> dealers = salesService.listDealers();
    long active = dealers.stream().filter(d -> "ACTIVE".equalsIgnoreCase(d.status())).count();
    BigDecimal outstanding =
        dealers.stream()
            .map(DealerDto::outstandingBalance)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return Map.of("active", active, "total", dealers.size(), "creditUtilization", outstanding);
  }

  private Map<String, Object> fetchAccountingSnapshot() {
    List<AccountDto> accounts = accountingService.listAccounts();
    BigDecimal balance =
        accounts.stream().map(AccountDto::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    return Map.of("accounts", accounts.size(), "ledgerBalance", balance);
  }

  private Map<String, Object> fetchHrSnapshot() {
    List<EmployeeDto> employees = hrService.listEmployees();
    long active = employees.stream().filter(e -> "ACTIVE".equalsIgnoreCase(e.status())).count();
    long onLeave =
        hrService.listLeaveRequests().stream()
            .filter(l -> "APPROVED".equalsIgnoreCase(l.status()))
            .count();
    return Map.of("activeEmployees", active, "onLeave", onLeave);
  }

  private boolean isHrPayrollEnabled(Company company) {
    return company != null
        && company.getEnabledModules() != null
        && company.getEnabledModules().contains(CompanyModule.HR_PAYROLL.name());
  }

  private Map<String, Object> fetchInventorySnapshot() {
    InventoryValuationDto valuation = reportService.inventoryValuation();
    return Map.of("value", valuation.totalValue(), "lowStock", valuation.lowStockItems());
  }

  private Map<String, Object> fetchCashflowSnapshot() {
    CashFlowDto cashflow = reportService.cashFlow();
    return Map.of(
        "operating",
        cashflow.operating(),
        "investing",
        cashflow.investing(),
        "financing",
        cashflow.financing(),
        "net",
        cashflow.netChange());
  }
}
