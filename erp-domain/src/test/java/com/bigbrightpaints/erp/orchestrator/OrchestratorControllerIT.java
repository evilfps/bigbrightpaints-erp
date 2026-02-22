package com.bigbrightpaints.erp.orchestrator;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.hr.domain.PayrollRunRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.orchestrator.repository.OutboxEventRepository;
import com.bigbrightpaints.erp.orchestrator.repository.AuditRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private AuditRepository auditRepository;
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
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
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
    @Transactional
    void approve_order_is_idempotent_and_audited() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        String idempotencyKey = "idem-" + UUID.randomUUID();
        String requestId = "req-" + UUID.randomUUID();
        headers.add("Idempotency-Key", idempotencyKey);
        headers.add("X-Request-Id", requestId);
        long outboxBefore = outboxEventRepository.count();

        Map<String, Object> body = Map.of(
                "orderId", String.valueOf(seededOrderId),
                "approvedBy", "orch@bbp.com",
                "totalAmount", new BigDecimal("5000")
        );

        ResponseEntity<Map> firstResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        ResponseEntity<Map> secondResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String traceId = (String) firstResponse.getBody().get("traceId");
        assertThat(secondResponse.getBody().get("traceId")).isEqualTo(traceId);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore + 1);

        Long companyId = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow().getId();
        assertThat(outboxEventRepository.findByCompanyIdAndRequestId(companyId, requestId))
                .allMatch(event -> requestId.equals(event.getRequestId())
                        && idempotencyKey.equals(event.getIdempotencyKey())
                        && traceId.equals(event.getTraceId()));
        assertThat(auditRepository.findByCompanyIdAndRequestIdOrderByTimestampAsc(companyId, requestId))
                .allMatch(record -> idempotencyKey.equals(record.getIdempotencyKey())
                        && traceId.equals(record.getTraceId()));
    }

    @Test
    void approve_order_request_id_fallback_normalizes_equivalent_payload_replay() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        headers.add("X-Request-Id", "req-" + UUID.randomUUID());
        long outboxBefore = outboxEventRepository.count();

        Map<String, Object> firstBody = Map.of(
                "orderId", String.valueOf(seededOrderId),
                "approvedBy", " orch@bbp.com ",
                "totalAmount", new BigDecimal("5000.00")
        );

        Map<String, Object> secondBody = Map.of(
                "orderId", String.valueOf(seededOrderId),
                "approvedBy", "orch@bbp.com",
                "totalAmount", new BigDecimal("5000")
        );

        ResponseEntity<Map> firstResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(firstBody, headers),
                Map.class);

        ResponseEntity<Map> secondResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(secondBody, headers),
                Map.class);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getBody().get("traceId")).isEqualTo(firstResponse.getBody().get("traceId"));
        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore + 1);
    }

    @Test
    void approve_order_rejects_idempotency_mismatch() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        String idempotencyKey = "idem-" + UUID.randomUUID();
        headers.add("Idempotency-Key", idempotencyKey);

        Map<String, Object> body = Map.of(
                "orderId", String.valueOf(seededOrderId),
                "approvedBy", "orch@bbp.com",
                "totalAmount", new BigDecimal("5000")
        );

        Map<String, Object> mismatchBody = Map.of(
                "orderId", String.valueOf(seededOrderId),
                "approvedBy", "orch@bbp.com",
                "totalAmount", new BigDecimal("6000")
        );

        ResponseEntity<Map> firstResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        ResponseEntity<String> secondResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(mismatchBody, headers),
                String.class);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fulfillment_accepts_request_id_fallback_when_idempotency_header_missing() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        String requestId = "req-" + UUID.randomUUID();
        headers.add("X-Request-Id", requestId);
        long outboxBefore = outboxEventRepository.count();

        Map<String, Object> body = Map.of(
                "status", "PROCESSING",
                "notes", "start production");

        ResponseEntity<Map> firstResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        ResponseEntity<Map> secondResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String traceId = (String) firstResponse.getBody().get("traceId");
        assertThat(secondResponse.getBody().get("traceId")).isEqualTo(traceId);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore + 1);
    }

    @Test
    void fulfillment_auto_derives_idempotency_key_when_headers_missing() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        long outboxBefore = outboxEventRepository.count();

        Map<String, Object> body = Map.of(
                "status", "PROCESSING",
                "notes", "start production auto");

        ResponseEntity<Map> firstResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        ResponseEntity<Map> secondResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String traceId = (String) firstResponse.getBody().get("traceId");
        assertThat(secondResponse.getBody().get("traceId")).isEqualTo(traceId);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore + 1);
    }

    @Test
    void fulfillment_auto_key_treats_blank_and_missing_notes_as_equivalent() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        long outboxBefore = outboxEventRepository.count();

        Map<String, Object> firstBody = new HashMap<>();
        firstBody.put("status", "PROCESSING");
        Map<String, Object> secondBody = Map.of(
                "status", "PROCESSING",
                "notes", "   ");

        ResponseEntity<Map> firstResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(firstBody, headers),
                Map.class);

        ResponseEntity<Map> secondResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(secondBody, headers),
                Map.class);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getBody().get("traceId")).isEqualTo(firstResponse.getBody().get("traceId"));
        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore + 1);
    }

    @Test
    void fulfillment_auto_key_normalizes_status_case_for_replay() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        long outboxBefore = outboxEventRepository.count();

        Map<String, Object> lowerCaseBody = Map.of(
                "status", "processing",
                "notes", "start production case-normalized");
        Map<String, Object> upperCaseBody = Map.of(
                "status", "PROCESSING",
                "notes", "start production case-normalized");

        ResponseEntity<Map> firstResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(lowerCaseBody, headers),
                Map.class);

        ResponseEntity<Map> secondResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(upperCaseBody, headers),
                Map.class);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String traceId = (String) firstResponse.getBody().get("traceId");
        assertThat(secondResponse.getBody().get("traceId")).isEqualTo(traceId);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore + 1);
    }

    @Test
    void fulfillment_request_id_fallback_hashes_oversized_request_id() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        headers.add("X-Request-Id", "req-" + "x".repeat(420));
        long outboxBefore = outboxEventRepository.count();

        Map<String, Object> body = Map.of(
                "status", "PROCESSING",
                "notes", "start production oversized request id");

        ResponseEntity<Map> firstResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        ResponseEntity<Map> secondResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String traceId = (String) firstResponse.getBody().get("traceId");
        assertThat(secondResponse.getBody().get("traceId")).isEqualTo(traceId);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore + 1);
    }

    @Test
    void fulfillment_explicit_idempotency_key_keeps_payload_case_contract() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        headers.add("Idempotency-Key", "idem-" + UUID.randomUUID());

        Map<String, Object> lowerCaseBody = Map.of(
                "status", "processing",
                "notes", "explicit key payload contract");
        Map<String, Object> upperCaseBody = Map.of(
                "status", "PROCESSING",
                "notes", "explicit key payload contract");

        ResponseEntity<Map> firstResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(lowerCaseBody, headers),
                Map.class);

        ResponseEntity<Map> secondResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(upperCaseBody, headers),
                Map.class);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void dispatch_alias_without_body_is_gone_with_canonical_path() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/orchestrator/dispatch",
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).containsKey("message");
        assertThat(response.getBody()).containsEntry("canonicalPath", "/api/v1/sales/dispatch/confirm");
    }

    @Test
    void dispatch_alias_path_variant_is_gone_with_canonical_path() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/orchestrator/dispatch/" + seededOrderId,
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).containsKey("message");
        assertThat(response.getBody()).containsEntry("canonicalPath", "/api/v1/sales/dispatch/confirm");
    }

    @Test
    void payroll_run_is_disabled_by_default_in_code_red() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
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
        assertThat(response.getBody().get("canonicalPath")).isEqualTo("/api/v1/payroll/runs");
        assertThat(payrollRunRepository.count()).isEqualTo(beforeRuns);
    }

    @Test
    void fulfillment_rejects_shipped_status_updates_without_dispatch_confirmation() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());

        Map<String, Object> body = Map.of(
                "status", "DISPATCHED",
                "notes", "test");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/fulfillment",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("success", false);
        Object payload = response.getBody().get("data");
        assertThat(payload).isInstanceOf(Map.class);
        Map<?, ?> error = (Map<?, ?>) payload;
        assertThat(error.get("code")).isEqualTo("BUS_001");
    }

    @Test
    void rejects_x_company_id_mismatch_against_token_company() {
        dataSeeder.ensureCompany("EVIL", "Evil Corp");
        String token = loginToken();

        HttpHeaders headers = authHeaders(token);
        headers.set("X-Company-Code", "EVIL");
        headers.add("Idempotency-Key", UUID.randomUUID().toString());

        Map<String, Object> body = Map.of(
                "orderId", String.valueOf(seededOrderId),
                "approvedBy", "orch@bbp.com",
                "totalAmount", new BigDecimal("5000")
        );

        ResponseEntity<String> approveResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rejects_mismatched_company_header_aliases() {
        String token = loginToken();
        HttpHeaders headers = authHeaders(token);
        headers.set("X-Company-Id", "EVIL");
        headers.add("Idempotency-Key", UUID.randomUUID().toString());

        Map<String, Object> body = Map.of(
                "orderId", String.valueOf(seededOrderId),
                "approvedBy", "orch@bbp.com",
                "totalAmount", new BigDecimal("5000")
        );

        ResponseEntity<String> approveResponse = rest.exchange(
                "/api/v1/orchestrator/orders/" + seededOrderId + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(approveResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
        headers.add("X-Company-Code", COMPANY_CODE);
        return headers;
    }
}
