package com.bigbrightpaints.erp.modules.portal.service;

import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.company.service.ModuleGatingService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTask;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionBatch;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionBatchRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlan;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlanRepository;
import com.bigbrightpaints.erp.modules.hr.domain.Employee;
import com.bigbrightpaints.erp.modules.hr.domain.EmployeeRepository;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequest;
import com.bigbrightpaints.erp.modules.hr.domain.LeaveRequestRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRun;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.portal.dto.DashboardInsights;
import com.bigbrightpaints.erp.modules.portal.dto.OperationsInsights;
import com.bigbrightpaints.erp.modules.portal.dto.WorkforceInsights;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PortalInsightsService {
    private static final Set<String> REVENUE_RECOGNIZED_INVOICE_STATUSES = Set.of(
            "ISSUED",
            "PAID",
            "PARTIAL"
    );
    private static final String RECOGNIZED_REVENUE_SUM_QUERY = """
            select sum(i.totalAmount)
            from Invoice i
            where i.company = :company
              and upper(trim(i.status)) in :statuses
            """;
    private static final String RECOGNIZED_REVENUE_SUM_SINCE_QUERY = """
            select sum(i.totalAmount)
            from Invoice i
            where i.company = :company
              and upper(trim(i.status)) in :statuses
              and i.issueDate >= :fromDate
            """;

    private final CompanyContextService companyContextService;
    private final EntityManager entityManager;
    private final DealerRepository dealerRepository;
    private final PackagingSlipRepository packagingSlipRepository;
    private final EmployeeRepository employeeRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final FactoryTaskRepository factoryTaskRepository;
    private final RawMaterialRepository rawMaterialRepository;
    private final FinishedGoodRepository finishedGoodRepository;
    private final FinishedGoodBatchRepository finishedGoodBatchRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final AccountRepository accountRepository;
    private final CompanyClock companyClock;
    private final ModuleGatingService moduleGatingService;

    public PortalInsightsService(CompanyContextService companyContextService,
                                 EntityManager entityManager,
                                 DealerRepository dealerRepository,
                                 PackagingSlipRepository packagingSlipRepository,
                                 EmployeeRepository employeeRepository,
                                 ProductionPlanRepository productionPlanRepository,
                                 ProductionBatchRepository productionBatchRepository,
                                 FactoryTaskRepository factoryTaskRepository,
                                 RawMaterialRepository rawMaterialRepository,
                                 FinishedGoodRepository finishedGoodRepository,
                                 FinishedGoodBatchRepository finishedGoodBatchRepository,
                                 LeaveRequestRepository leaveRequestRepository,
                                 PayrollRunRepository payrollRunRepository,
                                 AccountRepository accountRepository,
                                 CompanyClock companyClock,
                                 ModuleGatingService moduleGatingService) {
        this.companyContextService = companyContextService;
        this.entityManager = entityManager;
        this.dealerRepository = dealerRepository;
        this.packagingSlipRepository = packagingSlipRepository;
        this.employeeRepository = employeeRepository;
        this.productionPlanRepository = productionPlanRepository;
        this.productionBatchRepository = productionBatchRepository;
        this.factoryTaskRepository = factoryTaskRepository;
        this.rawMaterialRepository = rawMaterialRepository;
        this.finishedGoodRepository = finishedGoodRepository;
        this.finishedGoodBatchRepository = finishedGoodBatchRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.accountRepository = accountRepository;
        this.companyClock = companyClock;
        this.moduleGatingService = moduleGatingService;
    }

    public DashboardInsights dashboard() {
        Company company = companyContextService.requireCurrentCompany();
        boolean hrPayrollEnabled = moduleGatingService.isEnabled(company, CompanyModule.HR_PAYROLL);
        LocalDate recognizedRevenueCutoff = companyClock.today(company).minusDays(30);
        BigDecimal recognizedRevenue = sumRecognizedRevenue(company);
        BigDecimal recognizedRevenueLast30 = sumRecognizedRevenueOnOrAfter(company, recognizedRevenueCutoff);
        List<PackagingSlip> slips = packagingSlipRepository.findByCompanyOrderByCreatedAtDesc(company);
        List<Employee> employees = hrPayrollEnabled
                ? employeeRepository.findByCompanyOrderByFirstNameAsc(company)
                : List.of();
        List<ProductionPlan> plans = productionPlanRepository.findByCompanyOrderByPlannedDateDesc(company);

        String revenue = currency(recognizedRevenue);
        String last30 = currency(recognizedRevenueLast30);
        long dealers = dealerRepository.findByCompanyOrderByNameAsc(company).size();
        long activeEmployees = employees.stream().filter(emp -> "ACTIVE".equalsIgnoreCase(emp.getStatus())).count();
        double fulfilment = ratio(
                slips.stream().filter(slip -> "DISPATCHED".equalsIgnoreCase(slip.getStatus())).count(),
                slips.size()
        );

        List<DashboardInsights.HighlightMetric> highlights = new ArrayList<>();
        highlights.add(new DashboardInsights.HighlightMetric("Revenue run rate", revenue, "Last 30d: " + last30));
        highlights.add(new DashboardInsights.HighlightMetric(
                "Fulfilment SLA",
                percent(fulfilment),
                slips.isEmpty() ? "Awaiting fulfilment data" : "Dispatch ratio last 90d"));
        if (hrPayrollEnabled) {
            highlights.add(new DashboardInsights.HighlightMetric(
                    "Active workforce",
                    formatNumber(activeEmployees),
                    employees.isEmpty() ? "No workforce records" : "Employees with ACTIVE status"));
        }
        highlights.add(new DashboardInsights.HighlightMetric(
                "Dealer coverage",
                formatNumber(dealers),
                dealers == 0 ? "Seed dealer records to unlock insights" : "Connected dealer entities"));

        Map<String, Long> pipeline = plans.stream()
                .collect(Collectors.groupingBy(plan -> plan.getStatus().toUpperCase(Locale.ROOT), Collectors.counting()));

        List<DashboardInsights.PipelineStage> pipelineStages = pipeline.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(4)
                .map(entry -> new DashboardInsights.PipelineStage(entry.getKey(), entry.getValue()))
                .toList();

        List<DashboardInsights.HrPulseMetric> hrPulse = List.of();
        if (hrPayrollEnabled) {
            long onLeave = leaveRequestRepository.findByCompanyOrderByCreatedAtDesc(company).stream()
                    .filter(request -> "APPROVED".equalsIgnoreCase(request.getStatus()))
                    .count();
            long payrollDrafts = payrollRunRepository.findByCompanyOrderByRunDateDesc(company).stream()
                    .filter(run -> "DRAFT".equalsIgnoreCase(run.getStatusString()))
                    .count();
            hrPulse = List.of(
                    new DashboardInsights.HrPulseMetric("Engagement", percent(ratio(activeEmployees, employees.size())), "Active employees"),
                    new DashboardInsights.HrPulseMetric("Leave utilisation", percent(ratio(onLeave, employees.size())), "Approved leave records"),
                    new DashboardInsights.HrPulseMetric("Payroll readiness", formatNumber(payrollDrafts), "Draft payroll runs awaiting approval"));
        }

        return new DashboardInsights(highlights, pipelineStages, hrPulse);
    }

    public OperationsInsights operations() {
        Company company = companyContextService.requireCurrentCompany();
        List<RawMaterial> materials = rawMaterialRepository.findByCompanyOrderByNameAsc(company);
        List<FactoryTask> tasks = factoryTaskRepository.findByCompanyOrderByCreatedAtDesc(company);
        List<ProductionPlan> plans = productionPlanRepository.findByCompanyOrderByPlannedDateDesc(company);
        List<ProductionBatch> batches = productionBatchRepository.findByCompanyOrderByProducedAtDesc(company);
        List<PackagingSlip> slips = packagingSlipRepository.findByCompanyOrderByCreatedAtDesc(company);
        List<FinishedGood> finishedGoods = finishedGoodRepository.findByCompanyOrderByProductCodeAsc(company);
        List<Account> accounts = accountRepository.findByCompanyOrderByCodeAsc(company);

        Instant now = companyClock.now(company);
        double productionVelocity = ratio(
                batches.stream().filter(batch -> batch.getProducedAt().isAfter(now.minus(7, ChronoUnit.DAYS))).count(),
                Math.max(plans.size(), 1)
        ) * 100;
        double logisticsSla = ratio(
                slips.stream().filter(slip -> "DISPATCHED".equalsIgnoreCase(slip.getStatus())).count(),
                Math.max(slips.size(), 1)
        ) * 100;

        BigDecimal assetTotal = accounts.stream()
                .filter(account -> account.getType() == AccountType.ASSET)
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal liabilityTotal = accounts.stream()
                .filter(account -> account.getType() == AccountType.LIABILITY)
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal workingCapital = assetTotal.add(liabilityTotal);

        OperationsInsights.OperationsSummary summary = new OperationsInsights.OperationsSummary(
                round(productionVelocity),
                round(logisticsSla),
                currency(workingCapital)
        );

        List<OperationsInsights.SupplyAlert> supplyAlerts = materials.stream()
                .sorted(Comparator.comparing(RawMaterial::getCurrentStock))
                .limit(5)
                .map(this::toSupplyAlert)
                .toList();

        List<OperationsInsights.AutomationRun> automationRuns = tasks.stream()
                .limit(4)
                .map(task -> new OperationsInsights.AutomationRun(
                        task.getTitle(),
                        task.getStatus(),
                        task.getDescription() != null ? task.getDescription() : detailForTask(task)
                ))
                .toList();

        return new OperationsInsights(summary, supplyAlerts, automationRuns);
    }

    public WorkforceInsights workforce() {
        Company company = companyContextService.requireCurrentCompany();
        moduleGatingService.requireEnabled(company, CompanyModule.HR_PAYROLL, "/api/v1/portal/workforce");
        List<Employee> employees = employeeRepository.findByCompanyOrderByFirstNameAsc(company);
        List<LeaveRequest> leaveRequests = leaveRequestRepository.findByCompanyOrderByCreatedAtDesc(company);
        List<PayrollRun> payrollRuns = payrollRunRepository.findByCompanyOrderByRunDateDesc(company);

        Map<String, Long> byRole = employees.stream()
                .collect(Collectors.groupingBy(emp -> {
                    String role = emp.getRole();
                    return role == null || role.isBlank() ? "General workforce" : role.trim();
                }, Collectors.counting()));

        List<WorkforceInsights.SquadSummary> squads = byRole.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(entry -> new WorkforceInsights.SquadSummary(
                        entry.getKey(),
                        formatNumber(entry.getValue()) + " employees",
                        percent(ratio(entry.getValue(), employees.size())) + " of workforce"
                ))
                .toList();

        LocalDate today = companyClock.today(company);
        List<WorkforceInsights.UpcomingMoment> moments = new ArrayList<>();
        payrollRuns.stream()
                .filter(run -> !run.getRunDate().isBefore(today))
                .limit(2)
                .forEach(run -> moments.add(new WorkforceInsights.UpcomingMoment(
                        "Payroll run",
                        run.getRunDate().toString(),
                        run.getNotes() != null ? run.getNotes() : "Pending payroll approval"
                )));
        leaveRequests.stream()
                .filter(request -> request.getStartDate() != null && !request.getStartDate().isBefore(today))
                .limit(2)
                .forEach(request -> moments.add(new WorkforceInsights.UpcomingMoment(
                        "Leave: " + request.getLeaveType(),
                        request.getStartDate() + " to " + request.getEndDate(),
                        request.getReason() != null ? request.getReason() : "Scheduled leave"
                )));

        List<WorkforceInsights.PerformanceLeader> leaders = employees.stream()
                .filter(emp -> "ACTIVE".equalsIgnoreCase(emp.getStatus()))
                .sorted(Comparator.comparing(Employee::getHiredDate, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(3)
                .map(emp -> new WorkforceInsights.PerformanceLeader(
                        emp.getFirstName() + " " + emp.getLastName(),
                        emp.getRole() != null ? emp.getRole() : "Operator",
                        emp.getStatus() + " · joined " + (emp.getHiredDate() != null ? emp.getHiredDate() : "recently")
                ))
                .toList();

        return new WorkforceInsights(squads, moments, leaders);
    }

    private OperationsInsights.SupplyAlert toSupplyAlert(RawMaterial material) {
        BigDecimal stock = material.getCurrentStock();
        BigDecimal reorder = material.getReorderLevel();
        String status;
        if (stock.compareTo(reorder) <= 0) {
            status = "Critical";
        } else if (stock.compareTo(reorder.multiply(BigDecimal.valueOf(1.25))) <= 0) {
            status = "Watch";
        } else {
            status = "Healthy";
        }
        String detail = "Stock " + stock.stripTrailingZeros().toPlainString() + " " + material.getUnitType()
                + " · reorder " + reorder.stripTrailingZeros().toPlainString();
        return new OperationsInsights.SupplyAlert(material.getName(), status, detail);
    }

    private String detailForTask(FactoryTask task) {
        if (task.getDueDate() != null) {
            return "Due " + task.getDueDate();
        }
        return "Created recently";
    }

    private BigDecimal sumRecognizedRevenue(Company company) {
        return zeroIfNull(entityManager.createQuery(RECOGNIZED_REVENUE_SUM_QUERY, BigDecimal.class)
                .setParameter("company", company)
                .setParameter("statuses", REVENUE_RECOGNIZED_INVOICE_STATUSES)
                .getSingleResult());
    }

    private BigDecimal sumRecognizedRevenueOnOrAfter(Company company, LocalDate fromDate) {
        return zeroIfNull(entityManager.createQuery(RECOGNIZED_REVENUE_SUM_SINCE_QUERY, BigDecimal.class)
                .setParameter("company", company)
                .setParameter("statuses", REVENUE_RECOGNIZED_INVOICE_STATUSES)
                .setParameter("fromDate", fromDate)
                .getSingleResult());
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0d;
        }
        return (double) numerator / (double) denominator;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String currency(BigDecimal amount) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        return "₹" + scaled.toPlainString();
    }

    private static String percent(double value) {
        return String.format(Locale.ENGLISH, "%.1f%%", value * 100);
    }

    private static double round(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private static String formatNumber(long value) {
        return String.format(Locale.ENGLISH, "%,d", value);
    }
}
