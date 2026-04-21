package com.bigbrightpaints.erp.modules.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.admin.domain.ExportRequest;
import com.bigbrightpaints.erp.modules.admin.domain.ExportRequestRepository;
import com.bigbrightpaints.erp.modules.admin.dto.ExportApprovalStatus;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyModule;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import com.bigbrightpaints.erp.test.support.ErpApiRoutes;

@Tag("critical")
class AdminApprovalRbacIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "APPROVAL-RBAC";
  private static final String ADMIN_EMAIL = "approval-admin@bbp.com";
  private static final String ACCOUNTING_EMAIL = "approval-accounting@bbp.com";
  private static final String SUPER_ADMIN_EMAIL = "approval-superadmin@bbp.com";
  private static final String SALES_EMAIL = "approval-sales@bbp.com";
  private static final String FACTORY_EMAIL = "approval-factory@bbp.com";
  private static final String DEALER_EMAIL = "approval-dealer@bbp.com";
  private static final String DEALER_CODE = "APPROVAL-DEALER";
  private static final String PASSWORD = "Approval123!";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private ExportRequestRepository exportRequestRepository;

  @BeforeEach
  void setupUsers() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL, PASSWORD, "Approval Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL,
        PASSWORD,
        "Approval Accounting",
        COMPANY_CODE,
        List.of("ROLE_ACCOUNTING"));
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        PASSWORD,
        "Approval Super Admin",
        COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN"));
    dataSeeder.ensureUser(
        SALES_EMAIL, PASSWORD, "Approval Sales", COMPANY_CODE, List.of("ROLE_SALES"));
    dataSeeder.ensureUser(
        FACTORY_EMAIL, PASSWORD, "Approval Factory", COMPANY_CODE, List.of("ROLE_FACTORY"));
    dataSeeder.ensureUser(
        DEALER_EMAIL, PASSWORD, "Approval Dealer", COMPANY_CODE, List.of("ROLE_DEALER"));
    enableModule(COMPANY_CODE, CompanyModule.HR_PAYROLL);
    ensureDealerPortalMapping();
  }

  @Test
  void adminApprovalsEndpoint_allowsOnlyTenantAdminRole() {
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);
    HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD);
    HttpHeaders superAdminHeaders = authHeaders(SUPER_ADMIN_EMAIL, PASSWORD);
    HttpHeaders salesHeaders = authHeaders(SALES_EMAIL, PASSWORD);

    ResponseEntity<Map> adminResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_APPROVALS,
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);
    assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> accountingResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_APPROVALS,
            HttpMethod.GET,
            new HttpEntity<>(accountingHeaders),
            Map.class);
    assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> superAdminResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_APPROVALS,
            HttpMethod.GET,
            new HttpEntity<>(superAdminHeaders),
            Map.class);
    assertThat(superAdminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> salesResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_APPROVALS,
            HttpMethod.GET,
            new HttpEntity<>(salesHeaders),
            Map.class);
    assertThat(salesResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void adminApprovalsPayloadUsesTypedOriginAndOwnerFields() {
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);
    HttpHeaders salesHeaders = authHeaders(SALES_EMAIL, PASSWORD);

    long dealerId = createDealer("APPROVAL-CONTRACT-" + System.nanoTime(), new BigDecimal("5000"));
    long requestId = createCreditRequest(salesHeaders, dealerId, "1500", "Typed approval payload");
    long exportRequestId = createPendingExportRequest("SALES_SUMMARY", "periodId=7");

    ResponseEntity<Map> approvalsResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_APPROVALS,
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);
    assertThat(approvalsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<?, ?> approvalsBody = approvalsResponse.getBody();
    assertThat(approvalsBody).isNotNull();
    Map<?, ?> approvalsData = (Map<?, ?>) approvalsBody.get("data");
    assertThat(approvalsData).isNotNull();
    List<?> creditApprovals = (List<?>) approvalsData.get("items");
    assertThat(creditApprovals).isNotEmpty();

    Map<?, ?> creditApproval = findCreditApproval(approvalsResponse, requestId);
    assertThat(creditApproval).isNotNull();
    assertThat(creditApproval.get("originType")).isEqualTo("CREDIT_REQUEST");
    assertThat(creditApproval.get("ownerType")).isEqualTo("SALES");
    assertThat(creditApproval.containsKey("type")).isFalse();
    assertThat(creditApproval.containsKey("sourcePortal")).isFalse();
    assertThat(creditApproval.containsKey("reportType")).isFalse();
    assertThat(creditApproval.containsKey("parameters")).isFalse();
    assertThat(creditApproval.containsKey("requesterUserId")).isFalse();
    assertThat(creditApproval.containsKey("requesterEmail")).isFalse();

    List<?> exportApprovals = (List<?>) approvalsData.get("items");
    assertThat(exportApprovals).isNotEmpty();
    Map<?, ?> exportApproval =
        exportApprovals.stream()
            .map(Map.class::cast)
            .filter(item -> ("EXP-" + exportRequestId).equals(item.get("reference")))
            .findFirst()
            .orElseThrow();
    assertThat(exportApproval.get("originType")).isEqualTo("EXPORT_REQUEST");
    assertThat(exportApproval.get("ownerType")).isEqualTo("REPORTS");
    assertThat(exportApproval.containsKey("type")).isFalse();
    assertThat(exportApproval.containsKey("sourcePortal")).isFalse();
    assertThat(exportApproval.get("reportType")).isEqualTo("SALES_SUMMARY");
    assertThat(exportApproval.get("parameters")).isEqualTo("periodId=7");
    assertThat(exportApproval.get("requesterEmail")).isEqualTo(ACCOUNTING_EMAIL);
    assertThat(exportApproval.get("requesterUserId")).isNotNull();
    assertThat(exportApproval.get("actionType")).isEqualTo("APPROVAL_DECISION");
    assertThat(exportApproval.get("actionLabel")).isEqualTo("Review approval");
    assertThat(exportApproval.get("approveEndpoint"))
        .isEqualTo("/api/v1/admin/approvals/EXPORT_REQUEST/" + exportRequestId + "/decisions");
    assertThat(exportApproval.get("rejectEndpoint"))
        .isEqualTo("/api/v1/admin/approvals/EXPORT_REQUEST/" + exportRequestId + "/decisions");
  }

  @Test
  void genericApprovalDecisionEndpoint_allowsOnlyTenantAdmin() {
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);
    HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD);

    long exportRequestId = createPendingExportRequest("SALES_SUMMARY", "periodId=9");

    ResponseEntity<Map> accountingDecision =
        rest.exchange(
            "/api/v1/admin/approvals/EXPORT_REQUEST/" + exportRequestId + "/decisions",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("decision", "APPROVE"), accountingHeaders),
            Map.class);
    assertThat(accountingDecision.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> adminDecision =
        rest.exchange(
            "/api/v1/admin/approvals/EXPORT_REQUEST/" + exportRequestId + "/decisions",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("decision", "APPROVE"), adminHeaders),
            Map.class);
    assertThat(adminDecision.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> body = adminDecision.getBody();
    assertThat(body).isNotNull();
    Map<?, ?> data = (Map<?, ?>) body.get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("originType")).isEqualTo("EXPORT_REQUEST");
    assertThat(data.get("status")).isEqualTo("APPROVED");
  }

  @Test
  void periodCloseGenericDecision_requiresReasonForApproveAndReject() {
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);
    long unknownPeriodId = 9_999_999L;

    ResponseEntity<Map> approveWithoutReason =
        rest.exchange(
            "/api/v1/admin/approvals/PERIOD_CLOSE_REQUEST/" + unknownPeriodId + "/decisions",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("decision", "APPROVE"), adminHeaders),
            Map.class);
    assertThat(approveWithoutReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertValidationFailure(approveWithoutReason, "reason is required to approve this approval");

    ResponseEntity<Map> rejectWithoutReason =
        rest.exchange(
            "/api/v1/admin/approvals/PERIOD_CLOSE_REQUEST/" + unknownPeriodId + "/decisions",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("decision", "REJECT", "reason", "   "), adminHeaders),
            Map.class);
    assertThat(rejectWithoutReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertValidationFailure(rejectWithoutReason, "reason is required to reject this approval");
  }

  @Test
  void creditRequestApprovalActionsAllowOnlyAdminOrAccounting() {
    HttpHeaders salesHeaders = authHeaders(SALES_EMAIL, PASSWORD);
    HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD);
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);

    long dealerId = createDealer("APPROVAL-CREDIT-" + System.nanoTime(), new BigDecimal("5000"));
    long approveId = createCreditRequest(salesHeaders, dealerId, "1500", "Need approval path A");
    long rejectId = createCreditRequest(salesHeaders, dealerId, "1750", "Need approval path B");

    ResponseEntity<Map> salesApprove =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS + "/" + approveId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "RBAC guard validation"), salesHeaders),
            Map.class);
    assertThat(salesApprove.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> accountingApprove =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS + "/" + approveId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Accounting approval"), accountingHeaders),
            Map.class);
    assertThat(accountingApprove.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(extractStatus(accountingApprove)).isEqualTo("APPROVED");

    ResponseEntity<Map> salesReject =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS + "/" + rejectId + "/reject",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "RBAC guard validation"), salesHeaders),
            Map.class);
    assertThat(salesReject.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> adminReject =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS + "/" + rejectId + "/reject",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Admin rejection"), adminHeaders),
            Map.class);
    assertThat(adminReject.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(extractStatus(adminReject)).isEqualTo("REJECTED");
  }

  @Test
  void creditOverrideApprovalActionsAllowOnlyAdminOrAccounting() {
    HttpHeaders salesHeaders = authHeaders(SALES_EMAIL, PASSWORD);
    HttpHeaders factoryHeaders = authHeaders(FACTORY_EMAIL, PASSWORD);
    HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD);
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);

    Map<String, Object> decision = Map.of("reason", "Manual validation");
    long unknownRequestId = 999_999L;

    ResponseEntity<Map> salesApprove =
        rest.exchange(
            "/api/v1/credit/override-requests/" + unknownRequestId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(decision, salesHeaders),
            Map.class);
    assertThat(salesApprove.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertFailureMessage(
        salesApprove, "An admin or accountant must review this credit limit override request.");

    ResponseEntity<Map> factoryReject =
        rest.exchange(
            "/api/v1/credit/override-requests/" + unknownRequestId + "/reject",
            HttpMethod.POST,
            new HttpEntity<>(decision, factoryHeaders),
            Map.class);
    assertThat(factoryReject.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertFailureMessage(
        factoryReject, "An admin or accountant must review this credit limit override request.");

    ResponseEntity<Map> accountingApprove =
        rest.exchange(
            "/api/v1/credit/override-requests/" + unknownRequestId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(decision, accountingHeaders),
            Map.class);
    assertThat(accountingApprove.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertBusinessNotFound(accountingApprove, "Credit override request not found");

    ResponseEntity<Map> adminReject =
        rest.exchange(
            "/api/v1/credit/override-requests/" + unknownRequestId + "/reject",
            HttpMethod.POST,
            new HttpEntity<>(decision, adminHeaders),
            Map.class);
    assertThat(adminReject.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertBusinessNotFound(adminReject, "Credit override request not found");
  }

  @Test
  void payrollApprovalActionsAllowOnlyAdminOrAccounting() {
    HttpHeaders salesHeaders = authHeaders(SALES_EMAIL, PASSWORD);
    HttpHeaders factoryHeaders = authHeaders(FACTORY_EMAIL, PASSWORD);
    HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD);
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);

    long unknownRunId = 999_999L;
    Map<String, String> markPaidPayload = Map.of("paymentReference", "PMT-ADMIN-APPROVAL");

    ResponseEntity<Map> salesApprove =
        rest.exchange(
            "/api/v1/payroll/runs/" + unknownRunId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(null, salesHeaders),
            Map.class);
    assertThat(salesApprove.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> factoryPost =
        rest.exchange(
            "/api/v1/payroll/runs/" + unknownRunId + "/post",
            HttpMethod.POST,
            new HttpEntity<>(null, factoryHeaders),
            Map.class);
    assertThat(factoryPost.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> salesMarkPaid =
        rest.exchange(
            "/api/v1/payroll/runs/" + unknownRunId + "/mark-paid",
            HttpMethod.POST,
            new HttpEntity<>(markPaidPayload, salesHeaders),
            Map.class);
    assertThat(salesMarkPaid.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    ResponseEntity<Map> accountingApprove =
        rest.exchange(
            "/api/v1/payroll/runs/" + unknownRunId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(null, accountingHeaders),
            Map.class);
    assertThat(accountingApprove.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertValidationFailure(accountingApprove, "Payroll run not found");

    ResponseEntity<Map> adminApprove =
        rest.exchange(
            "/api/v1/payroll/runs/" + unknownRunId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(null, adminHeaders),
            Map.class);
    assertThat(adminApprove.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertValidationFailure(adminApprove, "Payroll run not found");

    ResponseEntity<Map> accountingPost =
        rest.exchange(
            "/api/v1/payroll/runs/" + unknownRunId + "/post",
            HttpMethod.POST,
            new HttpEntity<>(null, accountingHeaders),
            Map.class);
    assertThat(accountingPost.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertInvalidReferenceFailure(accountingPost, "Payroll run not found");

    ResponseEntity<Map> adminPost =
        rest.exchange(
            "/api/v1/payroll/runs/" + unknownRunId + "/post",
            HttpMethod.POST,
            new HttpEntity<>(null, adminHeaders),
            Map.class);
    assertThat(adminPost.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertInvalidReferenceFailure(adminPost, "Payroll run not found");

    ResponseEntity<Map> accountingMarkPaid =
        rest.exchange(
            "/api/v1/payroll/runs/" + unknownRunId + "/mark-paid",
            HttpMethod.POST,
            new HttpEntity<>(markPaidPayload, accountingHeaders),
            Map.class);
    assertThat(accountingMarkPaid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertInvalidReferenceFailure(accountingMarkPaid, "Payroll run not found");

    ResponseEntity<Map> adminMarkPaid =
        rest.exchange(
            "/api/v1/payroll/runs/" + unknownRunId + "/mark-paid",
            HttpMethod.POST,
            new HttpEntity<>(markPaidPayload, adminHeaders),
            Map.class);
    assertThat(adminMarkPaid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertInvalidReferenceFailure(adminMarkPaid, "Payroll run not found");
  }

  @Test
  void dealerPortalCreditRequest_createsApprovalQueueEntry() {
    HttpHeaders dealerHeaders = authHeaders(DEALER_EMAIL, PASSWORD);
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);
    Map<String, Object> payload = new HashMap<>();
    payload.put("amountRequested", new BigDecimal("2100"));
    payload.put("reason", "Need permanent limit increase for new order");

    ResponseEntity<Map> createResponse =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS,
            HttpMethod.POST,
            new HttpEntity<>(payload, dealerHeaders),
            Map.class);
    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<?, ?> createBody = createResponse.getBody();
    assertThat(createBody).isNotNull();
    Map<?, ?> createData = (Map<?, ?>) createBody.get("data");
    assertThat(createData).isNotNull();
    long requestId = ((Number) createData.get("id")).longValue();

    ResponseEntity<Map> listResponse =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS,
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);
    assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pendingRequests =
        (List<Map<String, Object>>) listResponse.getBody().get("data");
    assertThat(pendingRequests)
        .anySatisfy(
            row -> {
              assertThat(((Number) row.get("id")).longValue()).isEqualTo(requestId);
              assertThat(String.valueOf(row.get("status"))).isEqualTo("PENDING");
            });

    ResponseEntity<Map> approvalsResponse =
        rest.exchange(
            ErpApiRoutes.ADMIN_APPROVALS,
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);
    assertThat(approvalsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> approvalsBody = approvalsResponse.getBody();
    assertThat(approvalsBody).isNotNull();
    Map<?, ?> approvalsData = (Map<?, ?>) approvalsBody.get("data");
    assertThat(approvalsData).isNotNull();
    List<?> creditApprovals = (List<?>) approvalsData.get("items");
    assertThat(creditApprovals).isNotEmpty();
    Map<?, ?> creditApproval = findCreditApproval(approvalsResponse, requestId);
    assertThat(creditApproval).isNotNull();
    assertThat(creditApproval.get("originType")).isEqualTo("CREDIT_REQUEST");
    assertThat(creditApproval.get("summary").toString()).contains("credit-limit request");
    assertThat(creditApproval.get("requesterEmail")).isEqualTo(DEALER_EMAIL);
    assertThat(creditApproval.get("requesterUserId")).isNotNull();
  }

  @Test
  void salesCreditRequestApproval_updatesDealerDashboardAndClearsAdminQueue() {
    HttpHeaders salesHeaders = authHeaders(SALES_EMAIL, PASSWORD);
    HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);
    HttpHeaders dealerHeaders = authHeaders(DEALER_EMAIL, PASSWORD);

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Dealer dealer =
        dealerRepository.findByCompanyAndCodeIgnoreCase(company, DEALER_CODE).orElseThrow();
    BigDecimal startingLimit = dealer.getCreditLimit();
    BigDecimal increment = new BigDecimal("2500");
    long requestId =
        createCreditRequest(
            salesHeaders, dealer.getId(), increment.toPlainString(), "Q2 target push");

    ResponseEntity<Map> approvalsBefore =
        rest.exchange(
            ErpApiRoutes.ADMIN_APPROVALS,
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);
    assertThat(approvalsBefore.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertCreditRequestQueued(approvalsBefore, requestId);

    ResponseEntity<Map> approveResponse =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS + "/" + requestId + "/approve",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "Approved for Q2 target push"), adminHeaders),
            Map.class);
    assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(extractStatus(approveResponse)).isEqualTo("APPROVED");

    ResponseEntity<Map> approvalsAfter =
        rest.exchange(
            ErpApiRoutes.ADMIN_APPROVALS,
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            Map.class);
    assertThat(approvalsAfter.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(findCreditApproval(approvalsAfter, requestId)).isNull();

    Map<?, ?> dashboardData = dealerDashboardData(dealerHeaders);
    assertThat(new BigDecimal(String.valueOf(dashboardData.get("creditLimit"))))
        .isEqualByComparingTo(startingLimit.add(increment));
    assertThat(dashboardData.get("creditStatus")).isEqualTo("WITHIN_LIMIT");
  }

  private HttpHeaders authHeaders(String email, String password) {
    Map<String, Object> loginPayload =
        Map.of(
            "email", email,
            "password", password,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> loginResponse =
        rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    String token = (String) loginResponse.getBody().get("accessToken");
    assertThat(token).isNotBlank();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.set("X-Company-Code", COMPANY_CODE);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private Map<?, ?> dealerDashboardData(HttpHeaders headers) {
    ResponseEntity<Map> response =
        rest.exchange(
            ErpApiRoutes.DEALER_PORTAL_DASHBOARD,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> body = response.getBody();
    assertThat(body).isNotNull();
    return (Map<?, ?>) body.get("data");
  }

  private long createCreditRequest(HttpHeaders headers, String amountRequested, String reason) {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Dealer dealer =
        dealerRepository.findByCompanyAndCodeIgnoreCase(company, DEALER_CODE).orElseThrow();
    return createCreditRequest(headers, dealer.getId(), amountRequested, reason);
  }

  private long createCreditRequest(
      HttpHeaders headers, Long dealerId, String amountRequested, String reason) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("amountRequested", new BigDecimal(amountRequested));
    payload.put("reason", reason);
    if (dealerId != null) {
      payload.put("dealerId", dealerId);
    }

    ResponseEntity<Map> response =
        rest.exchange(
            ErpApiRoutes.CREDIT_LIMIT_REQUESTS,
            HttpMethod.POST,
            new HttpEntity<>(payload, headers),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<?, ?> body = response.getBody();
    assertThat(body).isNotNull();
    Map<?, ?> data = (Map<?, ?>) body.get("data");
    assertThat(data).isNotNull();
    return ((Number) data.get("id")).longValue();
  }

  private long createDealer(String code, BigDecimal creditLimit) {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Dealer dealer = new Dealer();
    dealer.setCompany(company);
    dealer.setCode(code);
    dealer.setName("Approval Dealer " + code);
    dealer.setStatus("ACTIVE");
    dealer.setCreditLimit(creditLimit);
    return dealerRepository.save(dealer).getId();
  }

  private long createPendingExportRequest(String reportType, String parameters) {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    UserAccount accountingUser =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(ACCOUNTING_EMAIL, COMPANY_CODE)
            .orElseThrow();

    ExportRequest request = new ExportRequest();
    request.setCompany(company);
    request.setUserId(accountingUser.getId());
    request.setReportType(reportType);
    request.setParameters(parameters);
    request.setStatus(ExportApprovalStatus.PENDING);
    return exportRequestRepository.save(request).getId();
  }

  private String extractStatus(ResponseEntity<Map> response) {
    Map<?, ?> body = response.getBody();
    assertThat(body).isNotNull();
    Map<?, ?> data = (Map<?, ?>) body.get("data");
    assertThat(data).isNotNull();
    return String.valueOf(data.get("status"));
  }

  private void assertCreditRequestQueued(ResponseEntity<Map> approvalsResponse, long requestId) {
    assertThat(findCreditApproval(approvalsResponse, requestId)).isNotNull();
  }

  private Map<?, ?> findCreditApproval(ResponseEntity<Map> approvalsResponse, long requestId) {
    return creditApprovals(approvalsResponse).stream()
        .filter(item -> "CREDIT_REQUEST".equals(item.get("originType")))
        .filter(item -> ("CLR-" + requestId).equals(item.get("reference")))
        .findFirst()
        .orElse(null);
  }

  private List<Map<?, ?>> creditApprovals(ResponseEntity<Map> approvalsResponse) {
    Map<?, ?> body = approvalsResponse.getBody();
    assertThat(body).isNotNull();
    Map<?, ?> data = (Map<?, ?>) body.get("data");
    assertThat(data).isNotNull();
    List<?> creditApprovals = (List<?>) data.get("items");
    assertThat(creditApprovals).isNotNull();
    List<Map<?, ?>> approvals = new ArrayList<>();
    for (Object item : creditApprovals) {
      approvals.add((Map<?, ?>) item);
    }
    return approvals;
  }

  private void assertFailureMessage(ResponseEntity<Map> response, String expectedMessage) {
    Map<?, ?> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("message")).isEqualTo(expectedMessage);
    Map<?, ?> data = (Map<?, ?>) body.get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("message")).isEqualTo(expectedMessage);
  }

  private void assertBusinessNotFound(ResponseEntity<Map> response, String expectedMessage) {
    Map<?, ?> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("message")).isEqualTo(expectedMessage);
    Map<?, ?> data = (Map<?, ?>) body.get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("code")).isEqualTo("BUS_003");
    assertThat(data.get("message")).isEqualTo(expectedMessage);
  }

  private void assertValidationFailure(ResponseEntity<Map> response, String expectedMessage) {
    Map<?, ?> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(String.valueOf(body.get("message"))).startsWith(expectedMessage);
    Map<?, ?> data = (Map<?, ?>) body.get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("code")).isEqualTo("VAL_001");
    assertThat(String.valueOf(data.get("message"))).startsWith(expectedMessage);
  }

  private void assertInvalidReferenceFailure(ResponseEntity<Map> response, String expectedMessage) {
    Map<?, ?> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(String.valueOf(body.get("message"))).startsWith(expectedMessage);
    Map<?, ?> data = (Map<?, ?>) body.get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("code")).isEqualTo("VAL_006");
    assertThat(String.valueOf(data.get("message"))).startsWith(expectedMessage);
  }

  private void ensureDealerPortalMapping() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    UserAccount dealerUser =
        userAccountRepository
            .findByEmailIgnoreCaseAndAuthScopeCodeIgnoreCase(DEALER_EMAIL, COMPANY_CODE)
            .orElseThrow();
    Dealer dealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, DEALER_CODE)
            .orElseGet(
                () -> {
                  Dealer created = new Dealer();
                  created.setCompany(company);
                  created.setCode(DEALER_CODE);
                  created.setName("Approval Dealer");
                  created.setEmail(DEALER_EMAIL);
                  created.setStatus("ACTIVE");
                  created.setCreditLimit(new BigDecimal("5000"));
                  return created;
                });
    if (dealer.getStatus() == null || dealer.getStatus().isBlank()) {
      dealer.setStatus("ACTIVE");
    }
    dealer.setPortalUser(dealerUser);
    dealerRepository.save(dealer);
  }
}
