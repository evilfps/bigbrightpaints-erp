package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.accounting.service.CompanyDefaultAccountsService;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryDashboardDto;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryTaskDto;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryTaskRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionBatchDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionBatchRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanRequest;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryReservationResult;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService.InventoryShortage;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeDto;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunDto;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunRequest;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.modules.reports.dto.AgedDebtorDto;
import com.bigbrightpaints.erp.modules.reports.dto.CashFlowDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.dto.DealerDto;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto;
import com.bigbrightpaints.erp.modules.sales.service.SalesJournalService;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.modules.sales.util.SalesOrderReference;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalState;
import com.bigbrightpaints.erp.orchestrator.repository.OrderAutoApprovalStateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IntegrationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(IntegrationCoordinator.class);

    private final SalesService salesService;
    private final FactoryService factoryService;
    private final FinishedGoodsService finishedGoodsService;
    private final InvoiceService invoiceService;
    private final AccountingService accountingService;
    private final SalesJournalService salesJournalService;
    private final HrService hrService;
    private final ReportService reportService;
    private final OrderAutoApprovalStateRepository orderAutoApprovalStateRepository;
    private final AccountingFacade accountingFacade;
    private final CompanyEntityLookup companyEntityLookup;
    private final CompanyDefaultAccountsService companyDefaultAccountsService;
    private final CompanyContextService companyContextService;
    private final CompanyClock companyClock;
    private final Long dispatchDebitAccountId;
    private final Long dispatchCreditAccountId;
    private final TransactionTemplate txTemplate;
    private static final AtomicBoolean dispatchAccountWarningLogged = new AtomicBoolean(false);

    public IntegrationCoordinator(SalesService salesService,
                                  FactoryService factoryService,
                                  FinishedGoodsService finishedGoodsService,
                                  InvoiceService invoiceService,
                                  AccountingService accountingService,
                                  SalesJournalService salesJournalService,
                                  HrService hrService,
                                  ReportService reportService,
                                  OrderAutoApprovalStateRepository orderAutoApprovalStateRepository,
                                  AccountingFacade accountingFacade,
                                  CompanyEntityLookup companyEntityLookup,
                                  CompanyDefaultAccountsService companyDefaultAccountsService,
                                  CompanyContextService companyContextService,
                                  CompanyClock companyClock,
                                  PlatformTransactionManager txManager,
                                  @Value("${erp.dispatch.debit-account-id:0}") Long dispatchDebitAccountId,
                                  @Value("${erp.dispatch.credit-account-id:0}") Long dispatchCreditAccountId) {
        this.salesService = salesService;
        this.factoryService = factoryService;
        this.finishedGoodsService = finishedGoodsService;
        this.invoiceService = invoiceService;
        this.accountingService = accountingService;
        this.salesJournalService = salesJournalService;
        this.hrService = hrService;
        this.reportService = reportService;
        this.orderAutoApprovalStateRepository = orderAutoApprovalStateRepository;
        this.accountingFacade = accountingFacade;
        this.companyEntityLookup = companyEntityLookup;
        this.companyDefaultAccountsService = companyDefaultAccountsService;
        this.companyContextService = companyContextService;
        this.companyClock = companyClock;
        this.dispatchDebitAccountId = normalizeAccount(dispatchDebitAccountId);
        this.dispatchCreditAccountId = normalizeAccount(dispatchCreditAccountId);
        if (this.dispatchDebitAccountId == null || this.dispatchCreditAccountId == null) {
            if (dispatchAccountWarningLogged.compareAndSet(false, true)) {
                log.warn("Dispatch debit/credit accounts not configured; COGS postings for dispatch mapping will be skipped. " +
                        "Set erp.dispatch.debit-account-id and erp.dispatch.credit-account-id to enable dispatch journals.");
            }
        }
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate = template;
    }

    @Transactional
    public InventoryReservationResult reserveInventory(String orderId, String companyId) {
        return withCompanyContext(companyId, () -> {
            Long id = parseNumericId(orderId);
            if (id == null) {
                return null;
            }
            SalesOrder order = salesService.getOrderWithItems(id);
            InventoryReservationResult reservation = finishedGoodsService.reserveForOrder(order);
            if (!reservation.shortages().isEmpty()) {
                scheduleUrgentProduction(order, reservation.shortages());
                log.warn("Order {} has {} pending shortage line(s); queued urgent production", id, reservation.shortages().size());
            } else {
                log.info("Reserved inventory for order {}", id);
            }
            salesService.updateStatusInternal(id, "RESERVED");
            return reservation;
        });
    }

    @Transactional
    public void queueProduction(String orderId, String companyId) {
        runWithCompanyContext(companyId, () -> {
            ProductionPlanRequest request = new ProductionPlanRequest(
                    "PLAN-" + orderId,
                    "Order " + orderId,
                    1.0,
                    companyClock.today(companyContextService.requireCurrentCompany()).plusDays(1),
                    "Auto-generated from orchestrator");
            factoryService.createPlan(request);
            log.info("Queued production plan for order {}", orderId);
        });
    }

    @Transactional
    public Long createAccountingEntry(String orderId, String companyId) {
        throw new IllegalStateException(
                "Order-truth journal posting is disabled (CODE-RED). Use dispatch confirmation for invoicing/posting.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoApprovalResult autoApproveOrder(String orderId, BigDecimal amount, String companyId) {
        String normalizedCompanyId = normalizeCompanyId(companyId);
        if (normalizedCompanyId == null) {
            log.warn("Cannot auto-approve order {} without a company context", orderId);
            return new AutoApprovalResult("PENDING_PRODUCTION", true);
        }
        Long numericId = parseNumericId(orderId);
        if (numericId == null) {
            return new AutoApprovalResult("PENDING_PRODUCTION", true);
        }
        AtomicReference<String> status = new AtomicReference<>("PENDING_PRODUCTION");
        AtomicBoolean awaitingProduction = new AtomicBoolean(false);
        runWithCompanyContext(normalizedCompanyId, () -> {
            OrderAutoApprovalState state = lockAutoApprovalState(normalizedCompanyId, numericId);
            if (state.isCompleted()) {
                log.info("Auto-approval already completed for order {} (company {})", orderId, normalizedCompanyId);
                status.set(state.isDispatchFinalized() ? "SHIPPED" : "READY_TO_SHIP");
                return;
            }
            state.startAttempt();
            try {
                InventoryReservationResult reservation = null;
                if (!state.isInventoryReserved()) {
                    reservation = reserveInventory(orderId, normalizedCompanyId);
                    awaitingProduction.set(reservation != null && !reservation.shortages().isEmpty());
                    state.markInventoryReserved();
                } else if (reservation == null) {
                    awaitingProduction.set(false);
                }
                if (!state.isOrderStatusUpdated()) {
                    salesService.updateStatusInternal(numericId, awaitingProduction.get() ? "PENDING_PRODUCTION" : "READY_TO_SHIP");
                    state.markOrderStatusUpdated();
                }
                log.info("Auto-approved order {} for company {}; awaitingProduction={}", orderId, normalizedCompanyId, awaitingProduction.get());
                status.set(awaitingProduction.get() ? "PENDING_PRODUCTION" : "READY_TO_SHIP");
                if (!awaitingProduction.get()) {
                    state.markCompleted();
                }
            } catch (RuntimeException ex) {
                state.markFailed(ex.getMessage());
                status.set("FAILED");
                log.error("Auto-approval failed for order {} (company {})", orderId, normalizedCompanyId, ex);
                throw ex;
            }
        });
        return new AutoApprovalResult(status.get(), awaitingProduction.get());
    }

    @Transactional
    public void updateProductionStatus(String planId, String companyId) {
        runWithCompanyContext(companyId, () -> {
            Long id = parseNumericId(planId);
            if (id != null) {
                ProductionPlanDto plan = factoryService.updatePlanStatus(id, "COMPLETED");
                log.info("Marked production plan {} as completed", planId);
                extractOrderIdFromPlan(plan)
                        .ifPresent(orderId -> {
                            AutoApprovalResult result = autoApproveOrder(String.valueOf(orderId), null, companyId);
                            log.info("Resumed auto-approval for order {} after plan completion; status={}, awaitingProduction={}",
                                    orderId, result.orderStatus(), result.awaitingProduction());
                        });
            }
        });
    }

    @Transactional
    public AutoApprovalResult updateFulfillment(String orderId, String requestedStatus, String companyId) {
        return withCompanyContext(companyId, () -> {
            Long id = parseNumericId(orderId);
            if (id == null) {
                return new AutoApprovalResult("INVALID", false);
            }
            String status = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase();
            switch (status) {
                case "PROCESSING":
                    salesService.updateStatusInternal(id, "PROCESSING");
                    return new AutoApprovalResult("PROCESSING", false);
                case "CANCELLED":
                    OrderAutoApprovalState state = lockAutoApprovalState(companyId, id);
                    state.markFailed("Cancelled");
                    salesService.updateStatusInternal(id, "CANCELLED");
                    return new AutoApprovalResult("CANCELLED", false);
                case "READY_TO_SHIP":
                    return autoApproveOrder(orderId, null, companyId);
                case "SHIPPED":
                case "DISPATCHED":
                    throw new ApplicationException(
                            ErrorCode.BUSINESS_INVALID_STATE,
                            "Orchestrator cannot mark orders SHIPPED/DISPATCHED. Use the canonical dispatch confirm endpoint."
                    ).withDetail("canonicalPath", "/api/v1/sales/dispatch/confirm")
                            .withDetail("requestedStatus", requestedStatus);
                default:
                    throw new IllegalArgumentException("Unsupported fulfillment status: " + requestedStatus);
            }
        });
    }

    @Transactional
    public void releaseInventory(String batchId, String companyId) {
        runWithCompanyContext(companyId, () -> {
            ProductionBatchRequest request = new ProductionBatchRequest(
                    batchId + "-DISPATCH",
                    0.0,
                    "system",
                    "Auto release for dispatch " + batchId);
            factoryService.logBatch(null, request);
            log.info("Logged release batch {}", batchId);
        });
    }

    @Transactional
    public void postDispatchJournal(String batchId,
                                    String companyId,
                                    BigDecimal amount) {
        runWithCompanyContext(companyId, () -> {
            Long debitAccountId = dispatchDebitAccountId;
            Long creditAccountId = dispatchCreditAccountId;
            if (debitAccountId == null || creditAccountId == null) {
                var defaults = companyDefaultAccountsService.requireDefaults();
                debitAccountId = defaults.cogsAccountId();
                creditAccountId = defaults.inventoryAccountId();
                log.warn("Dispatch accounts not configured; using company default COGS/Inventory for batch {}", batchId);
            }
            postJournal("DISPATCH-" + batchId, amount, "Dispatch journal for batch " + batchId, debitAccountId, creditAccountId);
        });
    }

    @Transactional(readOnly = true)
    public void syncEmployees(String companyId) {
        runWithCompanyContext(companyId, () -> {
            hrService.listEmployees();
            log.info("Synced employees view for company {}", companyId);
        });
    }

    @Transactional
    public PayrollRunDto generatePayroll(LocalDate payrollDate,
                                         BigDecimal totalAmount,
                                         String companyId) {
        return withCompanyContext(companyId, () -> {
            PayrollRunDto run = hrService.createPayrollRun(new PayrollRunRequest(
                    payrollDate,
                    totalAmount,
                    "Auto payroll run",
                    "AUTO-" + payrollDate));
            log.info("Triggered payroll run {} for {}", run.id(), payrollDate);
            return run;
        });
    }

    @Transactional
    public JournalEntryDto recordPayrollPayment(Long payrollRunId,
                                                BigDecimal amount,
                                                Long expenseAccountId,
                                                Long cashAccountId,
                                                String companyId) {
        return withCompanyContext(companyId, () -> accountingFacade.recordPayrollPayment(
                new PayrollPaymentRequest(payrollRunId, cashAccountId, expenseAccountId, amount, null, null)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("orders", salesService.listOrders(null).size());
        health.put("plans", factoryService.listPlans().size());
        health.put("accounts", accountingService.listAccounts().size());
        health.put("employees", hrService.listEmployees().size());
        return health;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> fetchAdminDashboard(String companyId) {
        return withCompanyContext(companyId, () -> {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("orders", fetchOrdersSnapshot());
            snapshot.put("dealers", fetchDealerSnapshot());
            snapshot.put("accounting", fetchAccountingSnapshot());
            snapshot.put("hr", fetchHrSnapshot());
            return snapshot;
        });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> fetchFactoryDashboard(String companyId) {
        return withCompanyContext(companyId, () -> {
            Map<String, Object> snapshot = new HashMap<>();
            FactoryDashboardDto dashboard = factoryService.dashboard();
            snapshot.put("production", Map.of(
                    "efficiency", dashboard.productionEfficiency(),
                    "completed", dashboard.completedPlans(),
                    "batchesLogged", dashboard.batchesLogged()));
            snapshot.put("tasks", factoryService.listTasks().size());
            snapshot.put("inventory", fetchInventorySnapshot());
            return snapshot;
        });
    }

    @Transactional(readOnly = true)
    public Map<String, Object> fetchFinanceDashboard(String companyId) {
        return withCompanyContext(companyId, () -> {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("cashflow", fetchCashflowSnapshot());
            snapshot.put("agedDebtors", reportService.agedDebtors());
            snapshot.put("ledger", fetchAccountingSnapshot());
            snapshot.put("reconciliation", reportService.inventoryReconciliation());
            return snapshot;
        });
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

    private void scheduleUrgentProduction(SalesOrder order, List<InventoryShortage> shortages) {
        if (shortages == null || shortages.isEmpty()) {
            return;
        }
        LocalDate today = companyClock.today(order.getCompany());
        for (InventoryShortage shortage : shortages) {
            ProductionPlanRequest planRequest = new ProductionPlanRequest(
                    "URG-" + order.getId() + "-" + shortage.productCode(),
                    shortage.productName() + " (" + shortage.productCode() + ")",
                    shortage.shortageQuantity().doubleValue(),
                    today,
                    "Urgent replenishment for order " + order.getOrderNumber());
            factoryService.createPlan(planRequest);

            FactoryTaskRequest taskRequest = new FactoryTaskRequest(
                    "Urgent build " + shortage.productCode(),
                    "Short by " + shortage.shortageQuantity() + " units for order " + order.getOrderNumber(),
                    "production",
                    "URGENT",
                    today.plusDays(1),
                    order.getId(),
                    null);
            factoryService.createTask(taskRequest);
        }
    }

    private OrderAutoApprovalState lockAutoApprovalState(String companyId, Long orderId) {
        return txTemplate.execute(status -> orderAutoApprovalStateRepository.findByCompanyCodeAndOrderId(companyId, orderId)
                .orElseGet(() -> {
                    try {
                        orderAutoApprovalStateRepository.save(new OrderAutoApprovalState(companyId, orderId));
                    } catch (DataIntegrityViolationException ex) {
                        log.warn("Auto-approval state already exists for order {} in company {}; retrying fetch", orderId, companyId);
                    }
                    return orderAutoApprovalStateRepository.findByCompanyCodeAndOrderId(companyId, orderId)
                            .orElseThrow(() -> new IllegalStateException("Unable to initialize auto-approval state"));
                }));
    }

    private void runWithCompanyContext(String companyId, Runnable action) {
        withCompanyContext(companyId, () -> {
            action.run();
            return null;
        });
    }

    private <T> T withCompanyContext(String companyId, Supplier<T> callback) {
        String normalizedCompanyId = normalizeCompanyId(companyId);
        String previousCompany = CompanyContextHolder.getCompanyCode();
        boolean changed = normalizedCompanyId != null && !Objects.equals(previousCompany, normalizedCompanyId);
        if (changed) {
            CompanyContextHolder.setCompanyCode(normalizedCompanyId);
        }
        try {
            return callback.get();
        } finally {
            if (changed) {
                if (previousCompany != null) {
                    CompanyContextHolder.setCompanyCode(previousCompany);
                } else {
                    CompanyContextHolder.clear();
                }
            }
        }
    }

    private String normalizeCompanyId(String companyId) {
        if (companyId == null) {
            return null;
        }
        String trimmed = companyId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Optional<Long> extractOrderIdFromPlan(ProductionPlanDto plan) {
        if (plan == null) {
            return Optional.empty();
        }
        if (plan.planNumber() != null) {
            String[] parts = plan.planNumber().split("-");
            if (parts.length >= 2) {
                Optional<Long> parsed = parseLong(parts[1]);
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }
        if (plan.notes() != null) {
            String[] tokens = plan.notes().split("\\D+");
            for (String token : tokens) {
                Optional<Long> parsed = parseLong(token);
                if (parsed.isPresent()) {
                    return parsed;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Long> parseLong(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private void postJournal(String reference,
                             BigDecimal amount,
                             String memo,
                             Long debitAccountId,
                             Long creditAccountId) {
        if (debitAccountId == null || creditAccountId == null) {
            log.warn("Skipping {} journal; account mapping missing", reference);
            return;
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("Skipping {} journal; amount is zero", reference);
            return;
        }
        BigDecimal postingAmount = amount.abs();
        accountingFacade.postSimpleJournal(
                reference,
                companyClock.today(companyContextService.requireCurrentCompany()),
                memo,
                debitAccountId,
                creditAccountId,
                postingAmount,
                false
        );
    }

    private Long parseNumericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException ex) {
            log.warn("Value {} is not a numeric identifier", id);
            return null;
        }
    }

    public record AutoApprovalResult(String orderStatus, boolean awaitingProduction) {}

    private Long normalizeAccount(Long accountId) {
        return accountId != null && accountId > 0 ? accountId : null;
    }
}
