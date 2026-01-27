package com.bigbrightpaints.erp.orchestrator;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEventRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class OrchestratorControllerIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "ACME";
    private static final String ORCH_EMAIL = "orch@bbp.com";
    private static final String ORCH_PASSWORD = "orch123";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private PayrollRunRepository payrollRunRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private AccountRepository accountRepository;

    private Long seededOrderId;
    private Long payrollCashAccountId;
    private Long payrollExpenseAccountId;

    @BeforeEach
    void seed() {
        dataSeeder.ensureUser(
                ORCH_EMAIL,
                ORCH_PASSWORD,
                "Orchestrator",
                COMPANY_CODE,
                List.of("ROLE_SALES", "orders.approve", "ROLE_FACTORY", "factory.dispatch", "ROLE_ACCOUNTING", "payroll.run")
        );
        SalesOrder order = dataSeeder.ensureSalesOrder(COMPANY_CODE, "SO-" + System.nanoTime(), new BigDecimal("5000"));
        seededOrderId = order.getId();
        ensurePayrollAccounts();
    }

    private void ensurePayrollAccounts() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        payrollCashAccountId = ensureAccount(company, "CASH-PAYROLL", "Payroll Cash", AccountType.ASSET).getId();
        payrollExpenseAccountId = ensureAccount(company, "EXP-PAYROLL", "Payroll Expense", AccountType.EXPENSE).getId();
    }

    private Account ensureAccount(Company company, String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account account = new Account();
                    account.setCompany(company);
                    account.setCode(code);
                    account.setName(name);
                    account.setType(type);
                    return accountRepository.save(account);
                });
    }

    @Test
    void approve_order_creates_outbox_event() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        long before = outboxEventRepository.count();

        Map<String, Object> body = Map.of(
                "orderId", String.valueOf(seededOrderId),
                "approvedBy", "orch@bbp.com",
                "totalAmount", new BigDecimal("5000")
        );

        ResponseEntity<Map> approveResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(approveResponse.getBody()).containsKey("traceId");
        assertThat(outboxEventRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void payroll_run_is_disabled_by_default_in_code_red() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        long beforeRuns = payrollRunRepository.count();

        Map<String, Object> body = Map.of(
                "payrollDate", LocalDate.now(),
                "initiatedBy", "orch@bbp.com",
                "debitAccountId", payrollExpenseAccountId,
                "creditAccountId", payrollCashAccountId,
                "postingAmount", new BigDecimal("5000")
        );

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/orchestrator/payroll/run",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).containsKey("message");
        assertThat(response.getBody()).containsKey("canonicalPath");
        assertThat(response.getBody().get("canonicalPath")).isEqualTo("/api/v1/hr/payroll-runs");
        assertThat(payrollRunRepository.count()).isEqualTo(beforeRuns);
    }

    private String loginToken() {
        Map<String, Object> req = Map.of(
                "email", ORCH_EMAIL,
                "password", ORCH_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        return (String) rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Company-Id", COMPANY_CODE);
        return headers;
    }
}
