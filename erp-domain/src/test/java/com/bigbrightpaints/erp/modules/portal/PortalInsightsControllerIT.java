package com.bigbrightpaints.erp.modules.portal;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
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

import static org.assertj.core.api.Assertions.assertThat;

public class PortalInsightsControllerIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "ACME";
    private static final String ADMIN_EMAIL = "admin@bbp.com";
    private static final String ADMIN_PASSWORD = "admin123";

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

    private Company company;

    @BeforeEach
    void setUp() {
        company = dataSeeder.ensureCompany(COMPANY_CODE, "Acme Corp");
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));

        SalesOrder order = new SalesOrder();
        order.setCompany(company);
        order.setOrderNumber("ORD-PORTAL");
        order.setStatus("APPROVED");
        order.setTotalAmount(BigDecimal.valueOf(125000));
        order.setCurrency("INR");
        order.setNotes("Portal smoke order");
        salesOrderRepository.save(order);

        PackagingSlip slip = new PackagingSlip();
        slip.setCompany(company);
        slip.setSalesOrder(order);
        slip.setSlipNumber("SLIP-1");
        slip.setStatus("DISPATCHED");
        packagingSlipRepository.save(slip);

        Employee employee = new Employee();
        employee.setCompany(company);
        employee.setFirstName("Harper");
        employee.setLastName("Singh");
        employee.setEmail("harper@acme.dev");
        employee.setRole("Production specialists");
        employee.setStatus("ACTIVE");
        employee.setHiredDate(LocalDate.now().minusMonths(6));
        employeeRepository.save(employee);

        RawMaterial material = new RawMaterial();
        material.setCompany(company);
        material.setName("Titanium dioxide");
        material.setSku("RM-TIO2");
        material.setUnitType("KG");
        material.setCurrentStock(new BigDecimal("10"));
        material.setReorderLevel(new BigDecimal("12"));
        rawMaterialRepository.save(material);

        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode("FG-BBP");
        finishedGood.setName("Premium paint kit");
        finishedGood.setCurrentStock(new BigDecimal("40"));
        finishedGood.setReservedStock(new BigDecimal("5"));
        finishedGoodRepository.save(finishedGood);

        ProductionPlan plan = new ProductionPlan();
        plan.setCompany(company);
        plan.setPlanNumber("PLAN-1");
        plan.setProductName("Paint run");
        plan.setQuantity(100);
        plan.setPlannedDate(LocalDate.now());
        plan.setStatus("IN DESIGN");
        productionPlanRepository.save(plan);

        ProductionBatch batch = new ProductionBatch();
        batch.setCompany(company);
        batch.setPlan(plan);
        batch.setBatchNumber("BATCH-1");
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
        payrollRun.setRunDate(LocalDate.now().plusDays(3));
        payrollRun.setStatus("DRAFT");
        payrollRunRepository.save(payrollRun);

        Account account = new Account();
        account.setCompany(company);
        account.setCode("1000");
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

    private HttpHeaders authenticatedHeaders() {
        Map<String, Object> loginPayload = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> loginResponse = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) loginResponse.getBody().get("accessToken");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Company-Id", COMPANY_CODE);
        return headers;
    }
}
