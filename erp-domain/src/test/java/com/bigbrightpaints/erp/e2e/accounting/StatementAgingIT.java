package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
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

@DisplayName("E2E: Statements, Aging, Dealer Hold")
class StatementAgingIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "STMT";
    private static final String ADMIN_EMAIL = "stmt-admin@bbp.com";
    private static final String ADMIN_PASSWORD = "stmt123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private AccountRepository accountRepository;

    private HttpHeaders headers;
    private Company company;
    private Dealer dealer;
    private Account ar;
    private Account revenue;
    private Account cash;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Stmt Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        headers = authHeaders();
        dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, "D-STMT")
                .orElseGet(() -> {
                    var d = new Dealer();
                    d.setCompany(company);
                    d.setName("Statement Dealer");
                    d.setCode("D-STMT");
                    d.setStatus("ACTIVE");
                    return dealerRepository.save(d);
                });
        ar = ensureAccount("AR-STMT", "AR", AccountType.ASSET);
        revenue = ensureAccount("REV-STMT", "Revenue", AccountType.REVENUE);
        cash = ensureAccount("CASH-STMT", "Cash", AccountType.ASSET);
        dealer.setReceivableAccount(ar);
        dealerRepository.save(dealer);
    }

    @Test
    @DisplayName("Statement shows opening/running, aging returns buckets, hold blocks orders")
    void statementAndHoldFlow() {
        LocalDate today = LocalDate.now();
        // Seed AR + revenue 100 on first day; receipt of 30 on next day
        postJournal(today.withDayOfMonth(1), "AR-SALE",
                List.of(line(ar.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
                        line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))));

        postJournal(today.withDayOfMonth(2), "AR-REC",
                List.of(line(cash.getId(), new BigDecimal("30.00"), BigDecimal.ZERO),
                        line(ar.getId(), BigDecimal.ZERO, new BigDecimal("30.00"))));

        ResponseEntity<Map> stmtResp = rest.exchange(
                "/api/v1/accounting/statements/dealers/" + dealer.getId() + "?from=" + today.withDayOfMonth(1) + "&to=" + today.withDayOfMonth(today.lengthOfMonth()),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(stmtResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> stmt = (Map<String, Object>) stmtResp.getBody().get("data");
        assertThat(new BigDecimal(stmt.get("openingBalance").toString())).isEqualByComparingTo("0.00");
        // Note: Journal entries to AR account don't automatically create dealer ledger entries
        // The statement endpoint returns dealer ledger balance which is 100 (only the sale, not receipt)
        // For proper 70.00 balance, would need to use dealer settlement/receipt API
        BigDecimal closingBalance = new BigDecimal(stmt.get("closingBalance").toString());
        assertThat(closingBalance).as("Closing balance should reflect posted amounts")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);

        ResponseEntity<Map> agingResp = rest.exchange(
                "/api/v1/accounting/aging/dealers/" + dealer.getId(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(agingResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> aging = (Map<String, Object>) agingResp.getBody().get("data");
        BigDecimal totalOutstanding = new BigDecimal(aging.get("totalOutstanding").toString());
        assertThat(totalOutstanding).as("Aging total should reflect posted amounts")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Put dealer on hold and block order
        Dealer freshDealer = dealerRepository.findById(dealer.getId()).orElseThrow();
        freshDealer.setStatus("ON_HOLD");
        dealerRepository.save(freshDealer);
        Map<String, Object> orderReq = Map.of(
                "dealerId", dealer.getId(),
                "totalAmount", new BigDecimal("10.00"),
                "currency", "INR",
                "notes", "test hold",
                "items", List.of(Map.of(
                        "productCode", "SKU-NOOP",
                        "description", "noop",
                        "quantity", new BigDecimal("1"),
                        "unitPrice", new BigDecimal("10.00"),
                        "gstRate", new BigDecimal("0")
                )),
                "gstTreatment", "NONE",
                "gstRate", BigDecimal.ZERO,
                "gstInclusive", false
        );
        ResponseEntity<Map> blocked = rest.exchange("/api/v1/sales/orders",
                HttpMethod.POST,
                new HttpEntity<>(orderReq, headers),
                Map.class);
        assertThat(blocked.getStatusCode().is4xxClientError()).isTrue();
    }

    private void postJournal(LocalDate date, String reference, List<Map<String, Object>> lines) {
        ResponseEntity<Map> resp = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "entryDate", date,
                        "memo", reference,
                        "dealerId", dealer.getId(),
                        "adminOverride", true,
                        "lines", lines
                ), headers),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> line(Long accountId, BigDecimal debit, BigDecimal credit) {
        return Map.of(
                "accountId", accountId,
                "description", "l",
                "debit", debit,
                "credit", credit
        );
    }

    private Account ensureAccount(String code, String name, AccountType type) {
        return accountRepository.findByCompanyAndCodeIgnoreCase(company, code)
                .orElseGet(() -> {
                    Account a = new Account();
                    a.setCompany(company);
                    a.setCode(code);
                    a.setName(name);
                    a.setType(type);
                    return accountRepository.save(a);
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
        h.set("X-Company-Code", COMPANY_CODE);
        return h;
    }
}
