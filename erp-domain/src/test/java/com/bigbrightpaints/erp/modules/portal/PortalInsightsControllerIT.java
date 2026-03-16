package com.bigbrightpaints.erp.modules.portal;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.core.config.SystemSettingsRepository;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.TenantRuntimeEnforcementService;
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
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class PortalInsightsControllerIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "ACME";
    private static final String ADMIN_EMAIL = "admin@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";
    private static final String SUPER_ADMIN_EMAIL = "superadmin@bbp.com";
    private static final String SUPER_ADMIN_PASSWORD = "superadmin123";
    private static final AtomicInteger PAYROLL_PERIOD_SEQUENCE = new AtomicInteger(0);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private SalesOrderRepository salesOrderRepository;
    @Autowired
    private PackagingSlipRepository packagingSlipRepository;
    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private ProductionPlanRepository productionPlanRepository;
    @Autowired
    private ProductionBatchRepository productionBatchRepository;
    @Autowired
    private FactoryTaskRepository factoryTaskRepository;
    @Autowired
    private RawMaterialRepository rawMaterialRepository;
    @Autowired
    private FinishedGoodRepository finishedGoodRepository;
    @Autowired
    private LeaveRequestRepository leaveRequestRepository;
    @Autowired
    private PayrollRunRepository payrollRunRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private DealerRepository dealerRepository;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private SystemSettingsRepository systemSettingsRepository;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private TenantRuntimeEnforcementService tenantRuntimeEnforcementService;

    private Company company;

    @BeforeEach
    void setUp() {
        long seed = System.nanoTime();
        String seedSuffix = Long.toUnsignedString(seed);
        String codeSuffix = seedSuffix.substring(Math.max(0, seedSuffix.length() - 6));

        company = dataSeeder.ensureCompany(COMPANY_CODE, "Acme Corp");
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD, "Super Admin", COMPANY_CODE, List.of("ROLE_SUPER_ADMIN"));
        userAccountRepository.findByEmailIgnoreCase(ADMIN_EMAIL).ifPresent(user -> {
            user.setMustChangePassword(false);
            user.setEnabled(true);
            userAccountRepository.save(user);
        });
        userAccountRepository.findByEmailIgnoreCase(SUPER_ADMIN_EMAIL).ifPresent(user -> {
            user.setMustChangePassword(false);
            user.setEnabled(true);
            userAccountRepository.save(user);
        });
        resetTenantRuntimePolicy(company.getId(), company.getCode());

        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setOrderNumber("ORD-PORTAL-" + codeSuffix);
        order.setStatus("APPROVED");
        order.setTotalAmount(BigDecimal.valueOf(125000));
        order.setCurrency("INR");
        order.setNotes("Portal smoke order");
        order = salesOrderRepository.saveAndFlush(order);

        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("SLIP-" + codeSuffix);
        slip.setStatus("DISPATCHED");
        packagingSlipRepository.save(slip);

        Employee employee = new Employee();
        employee.setCompany(company);
        employee.setFirstName("Harper");
        employee.setLastName("Singh");
        employee.setEmail("harper+" + codeSuffix + "@acme.dev");
        employee.setRole("Production specialists");
        employee.setStatus("ACTIVE");
        employee.setHiredDate(LocalDate.now().minusMonths(6));
        employee = employeeRepository.saveAndFlush(employee);

        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setName("Titanium dioxide");
        material.setSku("RM-TIO2-" + codeSuffix);
        material.setUnitType("KG");
        material.setCurrentStock(new BigDecimal("10"));
        material.setReorderLevel(new BigDecimal("12"));
        rawMaterialRepository.save(material);

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode("FG-BBP-" + codeSuffix);
        finishedGood.setName("Premium paint kit");
        finishedGood.setCurrentStock(new BigDecimal("40"));
        finishedGood.setReservedStock(new BigDecimal("5"));
        finishedGoodRepository.save(finishedGood);

        ProductionPlan plan = new ProductionPlan();
        plan.setCompany(company);
        plan.setPlanNumber("PLAN-" + codeSuffix);
        plan.setProductName("Paint run");
        plan.setQuantity(100);
        plan.setPlannedDate(LocalDate.now());
        plan.setStatus("IN DESIGN");
        plan = productionPlanRepository.saveAndFlush(plan);

        ProductionBatch batch = new ProductionBatch();
        batch.setCompany(company);
        batch.setPlan(plan);
        batch.setBatchNumber("BATCH-" + codeSuffix);
        batch.setQuantityProduced(82);
        batch.setProducedAt(Instant.now());
        productionBatchRepository.save(batch);

        FactoryTask task = new FactoryTask();
        task.setCompany(company);
        task.setTitle("Smart replenishment");
        task.setDescription("Balance finished goods across DCs");
        task.setStatus("EXECUTED");
        factoryTaskRepository.save(task);

        LeaveRequest leaveRequest = new LeaveRequest();
        leaveRequest.setCompany(company);
        leaveRequest.setEmployee(employee);
        leaveRequest.setLeaveType("Vacation");
        leaveRequest.setStartDate(LocalDate.now().plusDays(5));
        leaveRequest.setEndDate(LocalDate.now().plusDays(7));
        leaveRequest.setStatus("APPROVED");
        leaveRequestRepository.save(leaveRequest);

        PayrollRun payrollRun = new PayrollRun();
        payrollRun.setCompany(company);
        int payrollOffset = PAYROLL_PERIOD_SEQUENCE.getAndIncrement();
        LocalDate payrollEnd = LocalDate.of(2035, 12, 31).minusDays(payrollOffset);
        LocalDate payrollStart = payrollEnd.minusDays(29);
        payrollRun.setRunType(PayrollRun.RunType.MONTHLY);
        payrollRun.setPeriodStart(payrollStart);
        payrollRun.setPeriodEnd(payrollEnd);
        payrollRun.setRunDate(payrollEnd);
        payrollRun.setRunNumber("PR-M-" + payrollEnd + "-" + codeSuffix);
        payrollRun.setStatus("DRAFT");
        payrollRunRepository.save(payrollRun);

        Account account = new Account();
        account.setCompany(company);
        account.setCode("1000-" + codeSuffix);
        account.setName("Cash");
        account.setType(AccountType.ASSET);
        account.setBalance(new BigDecimal("250000"));
        accountRepository.save(account);
    }

    @Test
    void dashboardOperationsAndWorkforceEndpointsReturnData() {
        HttpHeaders headers = authenticatedHeaders();

        ResponseEntity<Map> dashboard = rest.exchange("/api/v1/portal/dashboard", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> dashboardData = (Map<?, ?>) dashboard.getBody().get("data");
        assertThat(dashboardData).isNotNull();
        assertThat((List<?>) dashboardData.get("highlights")).isNotEmpty();

        ResponseEntity<Map> operations = rest.exchange("/api/v1/portal/operations", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(operations.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> operationsData = (Map<?, ?>) operations.getBody().get("data");
        assertThat(operationsData).isNotNull();
        assertThat(operationsData.get("summary")).isNotNull();

        ResponseEntity<Map> workforce = rest.exchange("/api/v1/portal/workforce", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(workforce.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> workforceData = (Map<?, ?>) workforce.getBody().get("data");
        assertThat(workforceData).isNotNull();
        assertThat((List<?>) workforceData.get("squads")).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardRevenueUsesRecognizedInvoicesNotOrderStatus() {
        String suffix = Long.toUnsignedString(System.nanoTime());
        Dealer dealer = saveDealer("D-" + suffix);

        SalesOrder partialDispatchOrder = saveSalesOrder("ORD-PORTAL-PART-" + suffix, "PENDING_PRODUCTION", new BigDecimal("88000"));
        SalesOrder shippedOrder = saveSalesOrder("ORD-PORTAL-SHIP-" + suffix, "SHIPPED", new BigDecimal("12000"));
        saveSalesOrder("ORD-PORTAL-CLOSED-" + suffix, "CLOSED", new BigDecimal("51000"));
        saveSalesOrder("ORD-PORTAL-PEND-" + suffix, "PENDING_PRODUCTION", new BigDecimal("65000"));

        saveInvoice(
                dealer,
                partialDispatchOrder,
                "INV-PORTAL-A-" + suffix,
                " issued ",
                new BigDecimal("88000"),
                LocalDate.now().minusDays(2)
        );
        saveInvoice(
                dealer,
                shippedOrder,
                "INV-PORTAL-B-" + suffix,
                "PAID",
                new BigDecimal("12000"),
                LocalDate.now().minusDays(1)
        );

        HttpHeaders headers = authenticatedHeaders();
        ResponseEntity<Map> dashboard = rest.exchange("/api/v1/portal/dashboard", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> dashboardData = (Map<?, ?>) dashboard.getBody().get("data");
        assertThat(dashboardData).isNotNull();
        List<Map<String, Object>> highlights = (List<Map<String, Object>>) dashboardData.get("highlights");
        assertThat(highlights).isNotNull();

        Map<String, Object> revenue = highlights.stream()
                .filter(metric -> "Revenue run rate".equals(metric.get("label")))
                .findFirst()
                .orElseThrow();

        assertThat(revenue.get("value")).isEqualTo("₹100000.00");
        assertThat(revenue.get("detail")).isEqualTo("Last 30d: ₹100000.00");
    }

    @Test
    void portalEndpoints_followCanonicalRuntimePolicy_forBlockAndRecovery() {
        HttpHeaders headers = authenticatedHeaders();
        HttpHeaders superAdminHeaders = superAdminHeaders();

        ResponseEntity<Map> policyResponse = rest.exchange(
                "/api/v1/companies/" + company.getId() + "/tenant-runtime/policy",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "holdState", "BLOCKED",
                        "reasonCode", "INCIDENT_CONTAINMENT"
                ), superAdminHeaders),
                Map.class
        );
        assertThat(policyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> policyBody = policyResponse.getBody();
        assertThat(policyBody).isNotNull();
        Map<?, ?> policyData = (Map<?, ?>) policyBody.get("data");
        assertThat(policyData).isNotNull();
        assertThat(policyData.get("state")).isEqualTo("BLOCKED");
        String policyReference = String.valueOf(policyData.get("auditChainId"));
        assertThat(policyReference).isNotBlank();

        ResponseEntity<Map> dashboard = rest.exchange(
                "/api/v1/portal/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dashboard.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> errorData = (Map<String, Object>) dashboard.getBody().get("data");
        assertThat(errorData).isNotNull();
        assertThat(errorData.get("reason")).isEqualTo("TENANT_BLOCKED");
        assertThat(errorData.get("auditChainId")).isEqualTo(policyReference);

        ResponseEntity<Map> resetResponse = rest.exchange(
                "/api/v1/companies/" + company.getId() + "/tenant-runtime/policy",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "holdState", "ACTIVE",
                        "reasonCode", "INCIDENT_RESOLVED"
                ), superAdminHeaders),
                Map.class
        );
        assertThat(resetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> recoveredDashboard = rest.exchange(
                "/api/v1/portal/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );
        assertThat(recoveredDashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void resetTenantRuntimePolicy(Long companyId, String companyCode) {
        if (companyId == null) {
            return;
        }
        systemSettingsRepository.deleteById("tenant.runtime.hold-state." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.hold-reason." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.max-active-users." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.max-requests-per-minute." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.max-concurrent-requests." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.policy-reference." + companyId);
        systemSettingsRepository.deleteById("tenant.runtime.policy-updated-at." + companyId);
        tenantRuntimeEnforcementService.invalidatePolicyCache(companyCode);
    }

    private SalesOrder saveSalesOrder(String orderNumber, String status, BigDecimal totalAmount) {
        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setOrderNumber(orderNumber);
        order.setStatus(status);
        order.setTotalAmount(totalAmount);
        order.setCurrency("INR");
        return salesOrderRepository.saveAndFlush(order);
    }

    private Dealer saveDealer(String codeSuffix) {
        Dealer dealer = new Dealer();
        dealer.setCompany(company);
        dealer.setName("Dealer " + codeSuffix);
        dealer.setCode("DEALER-" + codeSuffix);
        dealer.setCreditLimit(new BigDecimal("500000"));
        dealer.setOutstandingBalance(BigDecimal.ZERO);
        dealer.setStatus("ACTIVE");
        return dealerRepository.saveAndFlush(dealer);
    }

    private Invoice saveInvoice(Dealer dealer,
                                SalesOrder salesOrder,
                                String invoiceNumber,
                                String status,
                                BigDecimal totalAmount,
                                LocalDate issueDate) {
        Invoice invoice = new Invoice();
        invoice.setCompany(company);
        invoice.setDealer(dealer);
        invoice.setSalesOrder(salesOrder);
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setStatus(status);
        invoice.setSubtotal(totalAmount);
        invoice.setTaxTotal(BigDecimal.ZERO);
        invoice.setTotalAmount(totalAmount);
        invoice.setOutstandingAmount(totalAmount);
        invoice.setCurrency("INR");
        invoice.setIssueDate(issueDate);
        invoice.setDueDate(issueDate.plusDays(30));
        return invoiceRepository.saveAndFlush(invoice);
    }

    private HttpHeaders authenticatedHeaders() {
        return authenticatedHeaders(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private HttpHeaders superAdminHeaders() {
        return authenticatedHeaders(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD);
    }

    private HttpHeaders authenticatedHeaders(String email, String password) {
        Map<String, Object> loginPayload = Map.of(
                "email", email,
                "password", password,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> loginResponse = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) loginResponse.getBody().get("accessToken");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Company-Code", COMPANY_CODE);
        return headers;
    }
}
