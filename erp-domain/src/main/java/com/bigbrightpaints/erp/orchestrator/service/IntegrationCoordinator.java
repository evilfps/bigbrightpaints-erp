package com.bigbrightpaints.erp.orchestrator.service;

import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.accounting.dto.AccountDto;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.dto.PayrollPaymentRequest;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
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
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeDto;
import com.bigbrightpaints.erp.modules.hr.dto.PayrollRunDto;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.modules.reports.dto.AgedDebtorDto;
import com.bigbrightpaints.erp.modules.reports.dto.CashFlowDto;
import com.bigbrightpaints.erp.modules.reports.dto.InventoryValuationDto;
import com.bigbrightpaints.erp.modules.reports.service.ReportService;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.dto.DealerDto;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderDto;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.orchestrator.config.OrchestratorFeatureFlags;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class IntegrationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(IntegrationCoordinator.class);

    private final SalesService salesService;
    private final FactoryService factoryService;
    private final FinishedGoodsService finishedGoodsService;
    private final AccountingService accountingService;
    private final HrService hrService;
    private final ReportService reportService;
    private final OrderAutoApprovalStateRepository orderAutoApprovalStateRepository;
    private final AccountingFacade accountingFacade;
    private final CompanyRepository companyRepository;
    private final CompanyClock companyClock;
    private final OrchestratorFeatureFlags featureFlags;
    private final TransactionTemplate txTemplate;

    public IntegrationCoordinator(SalesService salesService,
                                  FactoryService factoryService,
                                  FinishedGoodsService finishedGoodsService,
                                  AccountingService accountingService,
                                  HrService hrService,
                                  ReportService reportService,
                                  OrderAutoApprovalStateRepository orderAutoApprovalStateRepository,
                                  AccountingFacade accountingFacade,
                                  CompanyRepository companyRepository,
                                  CompanyClock companyClock,
                                  OrchestratorFeatureFlags featureFlags,
                                  PlatformTransactionManager txManager) {
        this.salesService = salesService;
        this.factoryService = factoryService;
        this.finishedGoodsService = finishedGoodsService;
        this.accountingService = accountingService;
        this.hrService = hrService;
        this.reportService = reportService;
        this.orderAutoApprovalStateRepository = orderAutoApprovalStateRepository;
        this.accountingFacade = accountingFacade;
        this.companyRepository = companyRepository;
        this.companyClock = companyClock;
        this.featureFlags = featureFlags;
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txTemplate = template;
    }

    @Transactional
    public InventoryReservationResult reserveInventory(String orderId, String companyId) {
        return reserveInventory(orderId, companyId, null, null);
    }

    @Transactional
    public InventoryReservationResult reserveInventory(String orderId,
                                                      String companyId,
                                                      String traceId,
                                                      String idempotencyKey) {
        String correlation = correlationSuffix(traceId, idempotencyKey);
        return withCompanyContext(companyId, () -> {
            Long id = requireNumericOrderId(orderId, "reserveInventory");
            attachOrderTrace(id, traceId);
            SalesOrder order = salesService.getOrderWithItems(id);
            InventoryReservationResult reservation = finishedGoodsService.reserveForOrder(order);
            if (!reservation.shortages().isEmpty()) {
                scheduleUrgentProduction(order, reservation.shortages(), traceId, idempotencyKey);
                log.warn("Order {} has {} pending shortage line(s); queued urgent production{}",
                        id,
                        reservation.shortages().size(),
                        correlation);
            } else {
                log.info("Reserved inventory for order {}{}", id, correlation);
            }
            salesService.updateOrchestratorWorkflowStatus(id, "RESERVED");
            return reservation;
        });
    }

    @Transactional
    public void queueProduction(String orderId, String companyId) {
        Company company = requireCompany(companyId, "queueProduction");
        runWithCompanyContext(company.getCode(), () -> {
            ProductionPlanRequest request = new ProductionPlanRequest(
                    "PLAN-" + orderId,
                    "Order " + orderId,
                    1.0,
                    companyClock.today(company).plusDays(1),
                    "Auto-generated from orchestrator");
            factoryService.createPlan(request);
            log.info("Queued production plan for order {}", orderId);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoApprovalResult autoApproveOrder(String orderId, BigDecimal amount, String companyId) {
        return autoApproveOrder(orderId, amount, companyId, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoApprovalResult autoApproveOrder(String orderId,
                                               BigDecimal amount,
                                               String companyId,
                                               String traceId,
                                               String idempotencyKey) {
        String correlation = correlationSuffix(traceId, idempotencyKey);
        String normalizedCompanyId = normalizeCompanyId(companyId);
        if (normalizedCompanyId == null) {
            log.warn("Cannot auto-approve order {} without a company context{}", orderId, correlation);
            return new AutoApprovalResult("PENDING_PRODUCTION", true);
        }
        Long numericId = requireNumericOrderId(orderId, "autoApproveOrder");
        AtomicReference<String> status = new AtomicReference<>("PENDING_PRODUCTION");
        AtomicBoolean awaitingProduction = new AtomicBoolean(false);
        runWithCompanyContext(normalizedCompanyId, () -> {
            attachOrderTrace(numericId, traceId);
            OrderAutoApprovalState state = lockAutoApprovalState(normalizedCompanyId, numericId);
            if (state.isCompleted()) {
                log.info("Auto-approval already completed for order {} (company {}){}",
                        orderId,
                        normalizedCompanyId,
                        correlation);
                status.set(state.isDispatchFinalized() ? "SHIPPED" : "READY_TO_SHIP");
                return;
            }
            state.startAttempt();
            try {
                InventoryReservationResult reservation = null;
                if (!state.isInventoryReserved()) {
                    reservation = reserveInventory(orderId, normalizedCompanyId, traceId, idempotencyKey);
                    awaitingProduction.set(reservation != null && !reservation.shortages().isEmpty());
                    state.markInventoryReserved();
                } else if (reservation == null) {
                    awaitingProduction.set(false);
                }
                if (!state.isOrderStatusUpdated()) {
                    salesService.updateOrchestratorWorkflowStatus(numericId,
                            awaitingProduction.get() ? "PENDING_PRODUCTION" : "READY_TO_SHIP");
                    state.markOrderStatusUpdated();
                }
                log.info("Auto-approved order {} for company {}; awaitingProduction={}{}",
                        orderId,
                        normalizedCompanyId,
                        awaitingProduction.get(),
                        correlation);
                status.set(awaitingProduction.get() ? "PENDING_PRODUCTION" : "READY_TO_SHIP");
                if (!awaitingProduction.get()) {
                    state.markCompleted();
                }
            } catch (RuntimeException ex) {
                state.markFailed(ex.getMessage());
                status.set("FAILED");
                log.error("Auto-approval failed for order {} (company {}){}",
                        orderId,
                        normalizedCompanyId,
                        correlation,
                        ex);
                throw ex;
            }
        });
        return new AutoApprovalResult(status.get(), awaitingProduction.get());
    }

    @Transactional
    public void updateProductionStatus(String planId, String companyId) {
        updateProductionStatus(planId, companyId, null, null);
    }

    @Transactional
    public void updateProductionStatus(String planId,
                                       String companyId,
                                       String traceId,
                                       String idempotencyKey) {
        String correlation = correlationSuffix(traceId, idempotencyKey);
        requireFactoryDispatchEnabled();
        runWithCompanyContext(companyId, () -> {
            Long id = parseNumericId(planId);
            if (id != null) {
                ProductionPlanDto plan = factoryService.updatePlanStatus(id, "COMPLETED");
                log.info("Marked production plan {} as completed{}", planId, correlation);
                extractOrderIdFromPlan(plan)
                        .ifPresent(orderId -> {
                            AutoApprovalResult result = autoApproveOrder(
                                    String.valueOf(orderId),
                                    null,
                                    companyId,
                                    traceId,
                                    idempotencyKey);
                            log.info("Resumed auto-approval for order {} after plan completion; status={}, awaitingProduction={}{}",
                                    orderId,
                                    result.orderStatus(),
                                    result.awaitingProduction(),
                                    correlation);
                        });
            }
        });
    }

    @Transactional
    public AutoApprovalResult updateFulfillment(String orderId, String requestedStatus, String companyId) {
        return updateFulfillment(orderId, requestedStatus, companyId, null, null);
    }

    @Transactional
    public AutoApprovalResult updateFulfillment(String orderId,
                                                String requestedStatus,
                                                String companyId,
                                                String traceId,
                                                String idempotencyKey) {
        return withCompanyContext(companyId, () -> {
            Long id = requireNumericOrderId(orderId, "updateFulfillment");
            attachOrderTrace(id, traceId);
            String status = requestedStatus == null ? "" : requestedStatus.trim().toUpperCase();
            switch (status) {
                case "PROCESSING":
                    salesService.updateOrchestratorWorkflowStatus(id, "PROCESSING");
                    return new AutoApprovalResult("PROCESSING", false);
                case "CANCELLED":
                    OrderAutoApprovalState state = lockAutoApprovalState(companyId, id);
                    state.markFailed("Cancelled");
                    salesService.cancelOrder(id, "Cancelled");
                    return new AutoApprovalResult("CANCELLED", false);
                case "READY_TO_SHIP":
                    return autoApproveOrder(orderId, null, companyId, traceId, idempotencyKey);
                case "SHIPPED":
                case "DISPATCHED":
                case "FULFILLED":
                case "COMPLETED":
                    throw new ApplicationException(
                            ErrorCode.BUSINESS_INVALID_STATE,
                            "Orchestrator cannot update dispatch-like statuses. Use /api/v1/dispatch/confirm."
                    ).withDetail("canonicalPath", "/api/v1/dispatch/confirm")
                            .withDetail("requestedStatus", requestedStatus);
                default:
                    throw com.bigbrightpaints.erp.core.validation.ValidationUtils.invalidInput(
                            "Unsupported fulfillment status: " + requestedStatus);
            }
        });
    }

    @Transactional
    public void releaseInventory(String batchId, String companyId) {
        releaseInventory(batchId, companyId, null, null);
    }

    @Transactional
    public void releaseInventory(String batchId,
                                 String companyId,
                                 String traceId,
                                 String idempotencyKey) {
        String correlation = correlationSuffix(traceId, idempotencyKey);
        requireFactoryDispatchEnabled();
        runWithCompanyContext(companyId, () -> {
            ProductionBatchRequest request = new ProductionBatchRequest(
                    batchId + "-DISPATCH",
                    0.0,
                    "system",
                    correlationMemo("Auto release for dispatch " + batchId, traceId, idempotencyKey));
            factoryService.logBatch(null, request);
            log.info("Logged release batch {}{}", batchId, correlation);
        });
    }

    @Transactional(readOnly = true)
    public void syncEmployees(String companyId) {
        syncEmployees(companyId, null, null);
    }

    @Transactional(readOnly = true)
    public void syncEmployees(String companyId,
                              String traceId,
                              String idempotencyKey) {
        String correlation = correlationSuffix(traceId, idempotencyKey);
        requirePayrollEnabled();
        runWithCompanyContext(companyId, () -> {
            hrService.listEmployees();
            log.info("Synced employees view for company {}{}", companyId, correlation);
        });
    }

    @Transactional
    public PayrollRunDto generatePayroll(LocalDate payrollDate,
                                         BigDecimal totalAmount,
                                         String companyId) {
        return generatePayroll(payrollDate, totalAmount, companyId, null, null);
    }

    @Transactional
    public PayrollRunDto generatePayroll(LocalDate payrollDate,
                                         BigDecimal totalAmount,
                                         String companyId,
                                         String traceId,
                                         String idempotencyKey) {
        requirePayrollEnabled();
        ApplicationException ex = new ApplicationException(
                ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                "Orchestrator payroll run is deprecated; use /api/v1/payroll/runs")
                .withDetail("canonicalPath", "/api/v1/payroll/runs");
        String sanitizedTraceId = CorrelationIdentifierSanitizer.sanitizeOptionalTraceId(traceId);
        String sanitizedIdempotencyKey = CorrelationIdentifierSanitizer.sanitizeOptionalIdempotencyKey(idempotencyKey);
        if (StringUtils.hasText(sanitizedTraceId)) {
            ex.withDetail("traceId", sanitizedTraceId);
        }
        if (StringUtils.hasText(sanitizedIdempotencyKey)) {
            ex.withDetail("idempotencyKey", sanitizedIdempotencyKey);
        }
        throw ex;
    }

    @Transactional
    public JournalEntryDto recordPayrollPayment(Long payrollRunId,
                                                BigDecimal amount,
                                                Long expenseAccountId,
                                                Long cashAccountId,
                                                String companyId) {
        return recordPayrollPayment(payrollRunId, amount, expenseAccountId, cashAccountId, companyId, null, null);
    }

    @Transactional
    public JournalEntryDto recordPayrollPayment(Long payrollRunId,
                                                BigDecimal amount,
                                                Long expenseAccountId,
                                                Long cashAccountId,
                                                String companyId,
                                                String traceId,
                                                String idempotencyKey) {
        requirePayrollEnabled();
        String memo = correlationMemo("Orchestrator payroll payment for run " + payrollRunId, traceId, idempotencyKey);
        return withCompanyContext(companyId, () -> accountingFacade.recordPayrollPayment(
                new PayrollPaymentRequest(payrollRunId, cashAccountId, expenseAccountId, amount, null, memo)));
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
        scheduleUrgentProduction(order, shortages, null, null);
    }

    private void scheduleUrgentProduction(SalesOrder order,
                                          List<InventoryShortage> shortages,
                                          String traceId,
                                          String idempotencyKey) {
        if (shortages == null || shortages.isEmpty()) {
            return;
        }
        LocalDate today = companyClock.today(order.getCompany());
        for (InventoryShortage shortage : shortages) {
            String correlationNotes = correlationMemo(
                    "Urgent replenishment for order " + order.getOrderNumber(),
                    traceId,
                    idempotencyKey);
            ProductionPlanRequest planRequest = new ProductionPlanRequest(
                    "URG-" + order.getId() + "-" + shortage.productCode(),
                    shortage.productName() + " (" + shortage.productCode() + ")",
                    shortage.shortageQuantity().doubleValue(),
                    today,
                    correlationNotes);
            factoryService.createPlan(planRequest);

            String correlationDescription = correlationMemo(
                    "Short by " + shortage.shortageQuantity() + " units for order " + order.getOrderNumber(),
                    traceId,
                    idempotencyKey);
            FactoryTaskRequest taskRequest = new FactoryTaskRequest(
                    "Urgent build " + shortage.productCode(),
                    correlationDescription,
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

    private Company requireCompany(String companyId, String operation) {
        String normalizedCompanyId = normalizeCompanyId(companyId);
        if (normalizedCompanyId == null) {
            throw new ApplicationException(
                    ErrorCode.VALIDATION_INVALID_INPUT,
                    "Missing companyId")
                    .withDetail("field", "companyId")
                    .withDetail("operation", operation);
        }
        return companyRepository.findByCodeIgnoreCase(normalizedCompanyId)
                .or(() -> parseLong(normalizedCompanyId)
                        .flatMap(companyRepository::findById))
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.VALIDATION_INVALID_INPUT,
                        "Unknown companyId")
                        .withDetail("field", "companyId")
                        .withDetail("operation", operation)
                        .withDetail("safeIdentifier",
                                CorrelationIdentifierSanitizer.safeIdentifierForLog(normalizedCompanyId)));
    }

    private String correlationMemo(String baseMemo, String traceId, String idempotencyKey) {
        StringBuilder builder = new StringBuilder(baseMemo != null ? baseMemo : "");
        String sanitizedTraceId = CorrelationIdentifierSanitizer.sanitizeOptionalTraceId(traceId);
        String sanitizedIdempotencyKey = CorrelationIdentifierSanitizer.sanitizeOptionalIdempotencyKey(idempotencyKey);
        if (StringUtils.hasText(sanitizedTraceId)) {
            builder.append(" [trace=").append(sanitizedTraceId).append("]");
        }
        if (StringUtils.hasText(sanitizedIdempotencyKey)) {
            builder.append(" [idem=").append(sanitizedIdempotencyKey).append("]");
        }
        return builder.toString();
    }

    private String correlationSuffix(String traceId, String idempotencyKey) {
        StringBuilder builder = new StringBuilder();
        String safeTraceId = CorrelationIdentifierSanitizer.safeTraceForLog(traceId);
        String safeIdempotencyKey = CorrelationIdentifierSanitizer.safeIdempotencyForLog(idempotencyKey);
        if (StringUtils.hasText(safeTraceId)) {
            builder.append(" [trace=").append(safeTraceId).append("]");
        }
        if (StringUtils.hasText(safeIdempotencyKey)) {
            builder.append(" [idem=").append(safeIdempotencyKey).append("]");
        }
        return builder.toString();
    }

    private void attachOrderTrace(Long orderId, String traceId) {
        if (orderId == null) {
            return;
        }
        String sanitizedTraceId = CorrelationIdentifierSanitizer.sanitizeOptionalTraceId(traceId);
        if (!StringUtils.hasText(sanitizedTraceId)) {
            return;
        }
        salesService.attachTraceId(orderId, sanitizedTraceId);
    }

    private Long requireNumericOrderId(String orderId, String operation) {
        Long id = parseNumericId(orderId);
        if (id != null) {
            return id;
        }
        throw new ApplicationException(
                ErrorCode.VALIDATION_INVALID_INPUT,
                "Invalid orderId format")
                .withDetail("field", "orderId")
                .withDetail("operation", operation)
                .withDetail("expected", "numeric")
                .withDetail("safeIdentifier", CorrelationIdentifierSanitizer.safeIdentifierForLog(orderId));
    }

    private Long parseNumericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException | NullPointerException ex) {
            log.warn("Rejected non-numeric identifier [{}]",
                    CorrelationIdentifierSanitizer.safeIdentifierForLog(id));
            return null;
        }
    }

    private void requireFactoryDispatchEnabled() {
        if (featureFlags != null && !featureFlags.isFactoryDispatchEnabled()) {
            throw new ApplicationException(
                    ErrorCode.BUSINESS_INVALID_STATE,
                    "Orchestrator factory dispatch is disabled (CODE-RED).")
                    .withDetail("canonicalPath", "/api/v1/factory");
        }
    }

    private void requirePayrollEnabled() {
        if (featureFlags != null && !featureFlags.isPayrollEnabled()) {
            throw new ApplicationException(
                    ErrorCode.BUSINESS_INVALID_STATE,
                    "Orchestrator payroll run is disabled (CODE-RED).")
                    .withDetail("canonicalPath", "/api/v1/payroll/runs");
        }
    }

    public record AutoApprovalResult(String orderStatus, boolean awaitingProduction) {}
}
