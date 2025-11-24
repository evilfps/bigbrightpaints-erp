package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: Period closing, lock, reopen")
class PeriodCloseLockIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "PERIOD";
    private static final String ADMIN_EMAIL = "period-admin@bbp.com";
    private static final String ADMIN_PASSWORD = "period123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;

    private HttpHeaders headers;
    private Company company;
    private Account revenue;
    private Account expense;
    private Account cash;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Period Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        headers = authHeaders();
        revenue = ensureAccount("REV-TEST", "Test Revenue", AccountType.REVENUE);
        expense = ensureAccount("EXP-TEST", "Test Expense", AccountType.EXPENSE);
        cash = ensureAccount("CASH-TEST", "Cash", AccountType.ASSET);
    }

    @Test
    @DisplayName("Closing locks period, posts retained earnings transfer, blocks new postings, reopen auto-reverses and unlocks")
    void closeLockReopenFlow() {
        LocalDate today = LocalDate.now();
        // Seed P&L: profit 60 (100 revenue, 40 expense) using cash movements
        postJournal(today.withDayOfMonth(5),
                List.of(
                        line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
                        line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00")),
                        line(expense.getId(), new BigDecimal("40.00"), BigDecimal.ZERO),
                        line(cash.getId(), BigDecimal.ZERO, new BigDecimal("40.00"))
                ));

        Long periodId = currentPeriodId(today);
        ResponseEntity<Map> closeResp = rest.exchange(
                "/api/v1/accounting/periods/" + periodId + "/close",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Month close", "force", true), headers),
                Map.class);
        assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> periodDto = (Map<String, Object>) closeResp.getBody().get("data");
        assertThat(periodDto.get("status")).isEqualTo("CLOSED");
        Integer closingJeId = (Integer) periodDto.get("closingJournalEntryId");
        assertThat(closingJeId).isNotNull();

        Long closingId = closingJeId.longValue();
        JournalEntry closing = journalEntryRepository.findById(closingId).orElseThrow();
        assertThat(closing.getStatus()).isEqualTo("POSTED");

        // Posting while locked should fail
        ResponseEntity<Map> blocked = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "referenceNumber", "LOCKED-BLOCK",
                        "entryDate", today,
                        "memo", "Should fail",
                        "adminOverride", false,
                        "lines", List.of(
                                line(cash.getId(), new BigDecimal("10.00"), BigDecimal.ZERO),
                                line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("10.00")),
                                line(expense.getId(), new BigDecimal("10.00"), BigDecimal.ZERO),
                                line(cash.getId(), BigDecimal.ZERO, new BigDecimal("10.00"))
                        )
                ), headers),
                Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Reopen -> auto-reversal of closing JE and status OPEN
        ResponseEntity<Map> reopenResp = rest.exchange(
                "/api/v1/accounting/periods/" + periodId + "/reopen",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Need adjustments"), headers),
                Map.class);
        assertThat(reopenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> reopened = (Map<String, Object>) reopenResp.getBody().get("data");
        assertThat(reopened.get("status")).isEqualTo("OPEN");
        assertThat(reopened.get("closingJournalEntryId")).isNull();

        // Now posting succeeds
        ResponseEntity<Map> ok = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "referenceNumber", "AFTER-REOPEN",
                        "entryDate", today,
                        "memo", "Adjustment after reopen",
                        "adminOverride", false,
                        "lines", List.of(line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("5.00")),
                                line(expense.getId(), new BigDecimal("5.00"), BigDecimal.ZERO))
                ), headers),
                Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Posting to closed periods rejected even with admin override")
    void postingIntoClosedPeriodRequiresReopenFirst() {
        LocalDate today = LocalDate.now();
        Long periodId = currentPeriodId(today);

        ResponseEntity<Map> closeResp = rest.exchange(
                "/api/v1/accounting/periods/" + periodId + "/close",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Hard close", "force", true), headers),
                Map.class);
        assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> blocked = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "referenceNumber", "CLOSED-ADMIN-BLOCK",
                        "entryDate", today,
                        "memo", "Admin override should fail",
                        "adminOverride", true,
                        "lines", List.of(
                                line(cash.getId(), new BigDecimal("1.00"), BigDecimal.ZERO),
                                line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("1.00"))
                        )
                ), headers),
                Map.class);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blocked.getBody().get("message").toString()).containsIgnoringCase("locked/closed");
    }

    @Test
    @DisplayName("Reopen requires explicit reason")
    void reopenRequiresReason() {
        LocalDate today = LocalDate.now();
        Long periodId = currentPeriodId(today);

        ResponseEntity<Map> closeResp = rest.exchange(
                "/api/v1/accounting/periods/" + periodId + "/close",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Month end", "force", true), headers),
                Map.class);
        assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> reopenResp = rest.exchange(
                "/api/v1/accounting/periods/" + periodId + "/reopen",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);

        assertThat(reopenResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reopenResp.getBody().get("message").toString()).containsIgnoringCase("reason");
    }

    private Map<String, Object> line(Long accountId, BigDecimal debit, BigDecimal credit) {
        return Map.of(
                "accountId", accountId,
                "description", "test",
                "debit", debit,
                "credit", credit
        );
    }

    private void postJournal(LocalDate date, List<Map<String, Object>> lines) {
        ResponseEntity<Map> resp = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "referenceNumber", "PL-SEED-" + date.toString(),
                        "entryDate", date,
                        "memo", "Seed P&L",
                        "adminOverride", false,
                        "lines", lines
                ), headers),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Long currentPeriodId(LocalDate forDate) {
        ResponseEntity<Map> resp = rest.exchange("/api/v1/accounting/periods",
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        List<Map<String, Object>> list = (List<Map<String, Object>>) resp.getBody().get("data");
        assertThat(list).isNotEmpty();
        for (Map<String, Object> dto : list) {
            Object startObj = dto.get("startDate");
            LocalDate start;
            if (startObj instanceof List<?> arr && arr.size() == 3) {
                start = LocalDate.of(((Number) arr.get(0)).intValue(),
                        ((Number) arr.get(1)).intValue(),
                        ((Number) arr.get(2)).intValue());
            } else {
                start = LocalDate.parse(startObj.toString());
            }
            if (start.getYear() == forDate.getYear() && start.getMonthValue() == forDate.getMonthValue()) {
                return ((Number) dto.get("id")).longValue();
            }
        }
        throw new AssertionError("No period found for date " + forDate);
    }

    private Account ensureAccount(String code, String name, AccountType type) {
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

    private HttpHeaders authHeaders() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("accessToken");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Company-Id", COMPANY_CODE);
        return h;
    }
}
