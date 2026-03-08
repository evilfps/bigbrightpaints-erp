package com.bigbrightpaints.erp.modules.admin;

import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminApprovalRbacIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "APPROVAL-RBAC";
    private static final String ADMIN_EMAIL = "approval-admin@bbp.com";
    private static final String ACCOUNTING_EMAIL = "approval-accounting@bbp.com";
    private static final String SALES_EMAIL = "approval-sales@bbp.com";
    private static final String FACTORY_EMAIL = "approval-factory@bbp.com";
    private static final String DEALER_EMAIL = "approval-dealer@bbp.com";
    private static final String DEALER_CODE = "APPROVAL-DEALER";
    private static final String PASSWORD = "Approval123!";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private DealerRepository dealerRepository;

    @BeforeEach
    void setupUsers() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Approval Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(ACCOUNTING_EMAIL, PASSWORD, "Approval Accounting", COMPANY_CODE, List.of("ROLE_ACCOUNTING"));
        dataSeeder.ensureUser(SALES_EMAIL, PASSWORD, "Approval Sales", COMPANY_CODE, List.of("ROLE_SALES"));
        dataSeeder.ensureUser(FACTORY_EMAIL, PASSWORD, "Approval Factory", COMPANY_CODE, List.of("ROLE_FACTORY"));
        dataSeeder.ensureUser(DEALER_EMAIL, PASSWORD, "Approval Dealer", COMPANY_CODE, List.of("ROLE_DEALER"));
        ensureDealerPortalMapping();
    }

    @Test
    void adminApprovalsEndpointAllowsAdminAndAccountingRoles() {
        HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD);
        HttpHeaders salesHeaders = authHeaders(SALES_EMAIL, PASSWORD);

        ResponseEntity<Map> adminResponse = rest.exchange(
                "/api/v1/admin/approvals",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                Map.class);
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> accountingResponse = rest.exchange(
                "/api/v1/admin/approvals",
                HttpMethod.GET,
                new HttpEntity<>(accountingHeaders),
                Map.class);
        assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> salesResponse = rest.exchange(
                "/api/v1/admin/approvals",
                HttpMethod.GET,
                new HttpEntity<>(salesHeaders),
                Map.class);
        assertThat(salesResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void creditRequestApprovalActionsAllowOnlyAdminOrAccounting() {
        HttpHeaders salesHeaders = authHeaders(SALES_EMAIL, PASSWORD);
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD);
        HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);

        long dealerId = createDealer("APPROVAL-CREDIT-" + System.nanoTime(), new BigDecimal("5000"));
        long approveId = createCreditRequest(salesHeaders, dealerId, "1500", "Need approval path A");
        long rejectId = createCreditRequest(salesHeaders, dealerId, "1750", "Need approval path B");

        ResponseEntity<Map> salesApprove = rest.exchange(
                "/api/v1/sales/credit-requests/" + approveId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "RBAC guard validation"), salesHeaders),
                Map.class);
        assertThat(salesApprove.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> accountingApprove = rest.exchange(
                "/api/v1/sales/credit-requests/" + approveId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Accounting approval"), accountingHeaders),
                Map.class);
        assertThat(accountingApprove.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(extractStatus(accountingApprove)).isEqualTo("APPROVED");

        ResponseEntity<Map> salesReject = rest.exchange(
                "/api/v1/sales/credit-requests/" + rejectId + "/reject",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "RBAC guard validation"), salesHeaders),
                Map.class);
        assertThat(salesReject.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> adminReject = rest.exchange(
                "/api/v1/sales/credit-requests/" + rejectId + "/reject",
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

        ResponseEntity<Map> salesApprove = rest.exchange(
                "/api/v1/credit/override-requests/" + unknownRequestId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(decision, salesHeaders),
                Map.class);
        assertThat(salesApprove.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertFailureMessage(
                salesApprove,
                "An admin or accountant must review this credit limit override request.");

        ResponseEntity<Map> factoryReject = rest.exchange(
                "/api/v1/credit/override-requests/" + unknownRequestId + "/reject",
                HttpMethod.POST,
                new HttpEntity<>(decision, factoryHeaders),
                Map.class);
        assertThat(factoryReject.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertFailureMessage(
                factoryReject,
                "An admin or accountant must review this credit limit override request.");

        ResponseEntity<Map> accountingApprove = rest.exchange(
                "/api/v1/credit/override-requests/" + unknownRequestId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(decision, accountingHeaders),
                Map.class);
        assertThat(accountingApprove.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertBusinessNotFound(accountingApprove, "Credit override request not found");

        ResponseEntity<Map> adminReject = rest.exchange(
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

        ResponseEntity<Map> salesApprove = rest.exchange(
                "/api/v1/payroll/runs/" + unknownRunId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(null, salesHeaders),
                Map.class);
        assertThat(salesApprove.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> factoryPost = rest.exchange(
                "/api/v1/payroll/runs/" + unknownRunId + "/post",
                HttpMethod.POST,
                new HttpEntity<>(null, factoryHeaders),
                Map.class);
        assertThat(factoryPost.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> salesMarkPaid = rest.exchange(
                "/api/v1/payroll/runs/" + unknownRunId + "/mark-paid",
                HttpMethod.POST,
                new HttpEntity<>(markPaidPayload, salesHeaders),
                Map.class);
        assertThat(salesMarkPaid.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> accountingApprove = rest.exchange(
                "/api/v1/payroll/runs/" + unknownRunId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(null, accountingHeaders),
                Map.class);
        assertThat(accountingApprove.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> adminApprove = rest.exchange(
                "/api/v1/payroll/runs/" + unknownRunId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(null, adminHeaders),
                Map.class);
        assertThat(adminApprove.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> accountingPost = rest.exchange(
                "/api/v1/payroll/runs/" + unknownRunId + "/post",
                HttpMethod.POST,
                new HttpEntity<>(null, accountingHeaders),
                Map.class);
        assertThat(accountingPost.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> adminPost = rest.exchange(
                "/api/v1/payroll/runs/" + unknownRunId + "/post",
                HttpMethod.POST,
                new HttpEntity<>(null, adminHeaders),
                Map.class);
        assertThat(adminPost.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> accountingMarkPaid = rest.exchange(
                "/api/v1/payroll/runs/" + unknownRunId + "/mark-paid",
                HttpMethod.POST,
                new HttpEntity<>(markPaidPayload, accountingHeaders),
                Map.class);
        assertThat(accountingMarkPaid.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> adminMarkPaid = rest.exchange(
                "/api/v1/payroll/runs/" + unknownRunId + "/mark-paid",
                HttpMethod.POST,
                new HttpEntity<>(markPaidPayload, adminHeaders),
                Map.class);
        assertThat(adminMarkPaid.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void dealerPortalCreditRequest_isReadOnlyAndDoesNotCreateApprovalQueueEntry() {
        HttpHeaders dealerHeaders = authHeaders(DEALER_EMAIL, PASSWORD);
        HttpHeaders adminHeaders = authHeaders(ADMIN_EMAIL, PASSWORD);

        Map<String, Object> payload = new HashMap<>();
        payload.put("amountRequested", new BigDecimal("2100"));
        payload.put("reason", "Need temporary limit increase for new order");

        ResponseEntity<Map> createResponse = rest.exchange(
                "/api/v1/dealer-portal/credit-requests",
                HttpMethod.POST,
                new HttpEntity<>(payload, dealerHeaders),
                Map.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertFailureMessage(
                createResponse,
                "Dealer portal is read-only. Ask your sales or admin contact to review credit-limit changes.");

        ResponseEntity<Map> approvalsResponse = rest.exchange(
                "/api/v1/admin/approvals",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                Map.class);
        assertThat(approvalsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> approvalsBody = approvalsResponse.getBody();
        assertThat(approvalsBody).isNotNull();
        Map<?, ?> approvalsData = (Map<?, ?>) approvalsBody.get("data");
        assertThat(approvalsData).isNotNull();
        List<?> creditApprovals = (List<?>) approvalsData.get("creditRequests");
        assertThat(creditApprovals).isEmpty();
    }

    private HttpHeaders authHeaders(String email, String password) {
        Map<String, Object> loginPayload = Map.of(
                "email", email,
                "password", password,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> loginResponse = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) loginResponse.getBody().get("accessToken");
        assertThat(token).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Company-Code", COMPANY_CODE);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private long createCreditRequest(HttpHeaders headers, String amountRequested, String reason) {
        return createCreditRequest(headers, null, amountRequested, reason);
    }

    private long createCreditRequest(HttpHeaders headers, Long dealerId, String amountRequested, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amountRequested", new BigDecimal(amountRequested));
        payload.put("reason", reason);
        if (dealerId != null) {
            payload.put("dealerId", dealerId);
        }

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/sales/credit-requests",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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

    private String extractStatus(ResponseEntity<Map> response) {
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        Map<?, ?> data = (Map<?, ?>) body.get("data");
        assertThat(data).isNotNull();
        return String.valueOf(data.get("status"));
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

    private void ensureDealerPortalMapping() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        UserAccount dealerUser = userAccountRepository.findByEmailIgnoreCase(DEALER_EMAIL).orElseThrow();
        Dealer dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, DEALER_CODE)
                .orElseGet(() -> {
                    Dealer created = new Dealer();
                    created.setCompany(company);
                    created.setCode(DEALER_CODE);
                    created.setName("Approval Dealer");
                    created.setEmail(DEALER_EMAIL);
                    created.setCreditLimit(new BigDecimal("5000"));
                    return created;
                });
        dealer.setPortalUser(dealerUser);
        dealerRepository.save(dealer);
    }
}
