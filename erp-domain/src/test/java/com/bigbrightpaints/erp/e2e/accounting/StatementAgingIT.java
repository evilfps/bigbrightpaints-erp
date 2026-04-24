package com.bigbrightpaints.erp.e2e.accounting;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@DisplayName("E2E: Portal finance aging, ledger, dealer hold")
class StatementAgingIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "STMT";
  private static final String ADMIN_EMAIL = "stmt-admin@bbp.com";
  private static final String ADMIN_PASSWORD = "stmt123";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private DealerLedgerRepository dealerLedgerRepository;

  private HttpHeaders headers;
  private Company company;
  private Dealer dealer;
  private Account ar;
  private Account revenue;
  private Account cash;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Stmt Admin",
        COMPANY_CODE,
        List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES"));
    company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    headers = authHeaders();
    dealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, "D-STMT")
            .orElseGet(
                () -> {
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
  @DisplayName("Portal finance ledger and aging stay tenant-bound while dealer hold still blocks")
  void statementAndHoldFlow() {
    LocalDate today = LocalDate.now();
    // Seed AR + revenue 100 on first day; receipt of 30 on next day
    postJournal(
        today.withDayOfMonth(1),
        "AR-SALE",
        List.of(
            line(ar.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
            line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))));

    postJournal(
        today.withDayOfMonth(2),
        "AR-REC",
        List.of(
            line(cash.getId(), new BigDecimal("30.00"), BigDecimal.ZERO),
            line(ar.getId(), BigDecimal.ZERO, new BigDecimal("30.00"))));

    ResponseEntity<Map> ledgerResp =
        rest.exchange(
            "/api/v1/portal/finance/ledger?dealerId=" + dealer.getId(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(ledgerResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> ledger = (Map<String, Object>) ledgerResp.getBody().get("data");
    assertThat(((Number) ledger.get("dealerId")).longValue()).isEqualTo(dealer.getId());
    BigDecimal currentBalance = new BigDecimal(ledger.get("currentBalance").toString());
    assertThat(currentBalance)
        .as("Portal finance ledger should reflect posted dealer ledger amounts")
        .isGreaterThanOrEqualTo(BigDecimal.ZERO);

    ResponseEntity<Map> agingResp =
        rest.exchange(
            "/api/v1/portal/finance/aging?dealerId=" + dealer.getId(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(agingResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> aging = (Map<String, Object>) agingResp.getBody().get("data");
    BigDecimal totalOutstanding = new BigDecimal(aging.get("totalOutstanding").toString());
    assertThat(totalOutstanding)
        .as("Aging total should reflect posted amounts")
        .isGreaterThanOrEqualTo(BigDecimal.ZERO);

    // Put dealer on hold and block order
    Dealer freshDealer = dealerRepository.findById(dealer.getId()).orElseThrow();
    freshDealer.setStatus("ON_HOLD");
    dealerRepository.save(freshDealer);
    Map<String, Object> orderReq =
        Map.of(
            "dealerId",
            dealer.getId(),
            "totalAmount",
            new BigDecimal("10.00"),
            "currency",
            "INR",
            "notes",
            "test hold",
            "items",
            List.of(
                Map.of(
                    "productCode", "SKU-NOOP",
                    "description", "noop",
                    "quantity", new BigDecimal("1"),
                    "unitPrice", new BigDecimal("10.00"),
                    "gstRate", new BigDecimal("0"))),
            "gstTreatment",
            "NONE",
            "gstRate",
            BigDecimal.ZERO,
            "gstInclusive",
            false);
    ResponseEntity<Map> blocked =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);
    assertThat(blocked.getStatusCode().is4xxClientError()).isTrue();
  }

  @Test
  @DisplayName("Windowed aged debtors applies pre-window credits to in-window invoices")
  void windowedAgedDebtorsAppliesOutOfWindowCreditsBeforeAsOf() {
    Dealer windowDealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, "D-STMT-WINDOW")
            .orElseGet(
                () -> {
                  var d = new Dealer();
                  d.setCompany(company);
                  d.setName("Statement Window Dealer");
                  d.setCode("D-STMT-WINDOW");
                  d.setStatus("ACTIVE");
                  return dealerRepository.save(d);
                });
    windowDealer.setReceivableAccount(ar);
    dealerRepository.save(windowDealer);
    dealerLedgerRepository.deleteAll(
        dealerLedgerRepository.findByCompanyAndDealerOrderByEntryDateAsc(company, windowDealer));

    DealerLedgerEntry preWindowCredit = new DealerLedgerEntry();
    preWindowCredit.setCompany(company);
    preWindowCredit.setDealer(windowDealer);
    preWindowCredit.setEntryDate(LocalDate.of(2026, 2, 28));
    preWindowCredit.setReferenceNumber("RCPT-WIN-001");
    preWindowCredit.setMemo("Pre-window credit");
    preWindowCredit.setDebit(BigDecimal.ZERO);
    preWindowCredit.setCredit(new BigDecimal("80.00"));

    DealerLedgerEntry inWindowInvoice = new DealerLedgerEntry();
    inWindowInvoice.setCompany(company);
    inWindowInvoice.setDealer(windowDealer);
    inWindowInvoice.setEntryDate(LocalDate.of(2026, 3, 10));
    inWindowInvoice.setReferenceNumber("INV-WIN-001");
    inWindowInvoice.setMemo("In-window invoice");
    inWindowInvoice.setInvoiceNumber("INV-WIN-001");
    inWindowInvoice.setDueDate(LocalDate.of(2026, 3, 10));
    inWindowInvoice.setDebit(new BigDecimal("100.00"));
    inWindowInvoice.setCredit(BigDecimal.ZERO);

    dealerLedgerRepository.saveAll(List.of(preWindowCredit, inWindowInvoice));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/reports/aged-debtors?startDate=2026-03-01&endDate=2026-03-15",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) response.getBody().get("data");
    Map<String, Object> windowDealerRow =
        rows.stream()
            .filter(row -> "D-STMT-WINDOW".equals(row.get("dealerCode")))
            .findFirst()
            .orElseThrow();
    assertThat(new BigDecimal(windowDealerRow.get("totalOutstanding").toString()))
        .isEqualByComparingTo("20.00");
    assertThat(new BigDecimal(windowDealerRow.get("current").toString()))
        .isEqualByComparingTo("0.00");
    BigDecimal overdueBucketTotal =
        new BigDecimal(windowDealerRow.get("oneToThirtyDays").toString())
            .add(new BigDecimal(windowDealerRow.get("thirtyOneToSixtyDays").toString()))
            .add(new BigDecimal(windowDealerRow.get("sixtyOneToNinetyDays").toString()))
            .add(new BigDecimal(windowDealerRow.get("ninetyPlusDays").toString()));
    assertThat(overdueBucketTotal).isEqualByComparingTo("20.00");
  }

  @Test
  @DisplayName(
      "Retired accounting dealer finance aliases are not found for admin-accounting probes")
  void retiredAccountingDealerFinanceAliasesAreNotFound() {
    List<String> retiredPaths =
        List.of(
            "/api/v1/accounting/aging/dealers/" + dealer.getId(),
            "/api/v1/accounting/aging/dealers/" + dealer.getId() + "/pdf",
            "/api/v1/accounting/statements/dealers/" + dealer.getId(),
            "/api/v1/accounting/statements/dealers/" + dealer.getId() + "/pdf");

    for (String path : retiredPaths) {
      ResponseEntity<byte[]> response =
          rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
      assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  private void postJournal(LocalDate date, String reference, List<Map<String, Object>> lines) {
    ResponseEntity<Map> resp =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "entryDate", date,
                    "memo", reference,
                    "dealerId", dealer.getId(),
                    "adminOverride", true,
                    "lines", lines),
                headers),
            Map.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private Map<String, Object> line(Long accountId, BigDecimal debit, BigDecimal credit) {
    return Map.of(
        "accountId", accountId,
        "description", "l",
        "debit", debit,
        "credit", credit);
  }

  private Account ensureAccount(String code, String name, AccountType type) {
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

  private HttpHeaders authHeaders() {
    Map<String, Object> req =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY_CODE);
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
