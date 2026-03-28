package com.bigbrightpaints.erp.e2e.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.service.DealerLedgerService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("Portal Finance Ledger View")
public class DealerLedgerIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "LEDGER-E2E";
  private static final String ADMIN_EMAIL = "ledger@test.com";
  private static final String ADMIN_PASSWORD = "ledger123";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private DealerLedgerService dealerLedgerService;

  private HttpHeaders headers;
  private Dealer dealer;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Ledger Admin",
        COMPANY_CODE,
        List.of("ROLE_ADMIN", "ROLE_SALES"));
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    headers = authHeaders();
    dealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, "D-LEDGER")
            .orElseGet(
                () -> {
                  Dealer d = new Dealer();
                  d.setCompany(company);
                  d.setName("Ledger Dealer");
                  d.setCode("D-LEDGER");
                  d.setCreditLimit(new BigDecimal("100000"));
                  return dealerRepository.save(d);
                });
    ensureAccount(company, "AR-LEDGER", "AR Ledger", AccountType.ASSET);
    ensureAccount(company, "REV-LEDGER", "Revenue", AccountType.REVENUE);
  }

  private HttpHeaders authHeaders() {
    Map<String, Object> req =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
    String token = (String) login.getBody().get("accessToken");
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    h.set("X-Company-Code", COMPANY_CODE);
    return h;
  }

  private Account ensureAccount(Company company, String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account a = new Account();
              a.setCompany(company);
              a.setCode(code);
              a.setName(name);
              a.setType(type);
              return accountRepository.save(a);
            });
  }

  @Test
  @DisplayName("Portal finance ledger shows running balance and references")
  void dealerLedger_showsRunningBalance() {
    // Seed ledger entries: two invoices totalling 70k
    var ctx1 =
        new DealerLedgerService.LedgerContext(
            LocalDate.now().minusDays(2),
            "INV-1001",
            "Invoice 1001",
            new BigDecimal("50000"),
            BigDecimal.ZERO,
            null);
    dealerLedgerService.recordLedgerEntry(dealer, ctx1);
    var ctx2 =
        new DealerLedgerService.LedgerContext(
            LocalDate.now().minusDays(1),
            "INV-1002",
            "Invoice 1002",
            new BigDecimal("20000"),
            BigDecimal.ZERO,
            null);
    dealerLedgerService.recordLedgerEntry(dealer, ctx2);

    ResponseEntity<Map> resp =
        rest.exchange(
            "/api/v1/portal/finance/ledger?dealerId=" + dealer.getId(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
    assertThat(data.get("dealerId")).isEqualTo(dealer.getId().intValue());
    assertThat(new BigDecimal(data.get("currentBalance").toString())).isEqualByComparingTo("70000");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) data.get("entries");
    assertThat(entries).hasSize(2);
    assertThat(entries.get(1).get("reference")).isEqualTo("INV-1002");
    assertThat(new BigDecimal(entries.get(1).get("runningBalance").toString()))
        .isEqualByComparingTo("70000");
  }
}
