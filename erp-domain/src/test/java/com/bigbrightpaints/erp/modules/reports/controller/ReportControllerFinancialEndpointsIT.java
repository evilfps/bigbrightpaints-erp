package com.bigbrightpaints.erp.modules.reports.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
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

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
class ReportControllerFinancialEndpointsIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "M8-REPORTS";
  private static final String ACCOUNTING_EMAIL = "m8-reports-accounting@bbp.com";
  private static final String PASSWORD = "M8ReportsPass123!";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private DealerLedgerRepository dealerLedgerRepository;
  @Autowired private AccountingPeriodRepository accountingPeriodRepository;

  private Company company;
  private HttpHeaders headers;
  private Account cashAccount;
  private Account revenueAccount;
  private Account expenseAccount;
  private Dealer dealer;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL,
        PASSWORD,
        "M8 Reports Accounting",
        COMPANY_CODE,
        List.of("ROLE_ACCOUNTING", "ROLE_ADMIN"));

    company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    headers = authHeaders();
    ensureCurrentPeriod();

    cashAccount = ensureAccount("M8-CASH", "M8 Cash", AccountType.ASSET);
    revenueAccount = ensureAccount("M8-REV", "M8 Revenue", AccountType.REVENUE);
    expenseAccount = ensureAccount("M8-EXP", "M8 Expense", AccountType.EXPENSE);

    dealer =
        dealerRepository
            .findByCompanyAndCodeIgnoreCase(company, "M8-DEALER")
            .orElseGet(
                () -> {
                  Dealer created = new Dealer();
                  created.setCompany(company);
                  created.setCode("M8-DEALER");
                  created.setName("M8 Dealer");
                  created.setCompanyName("M8 Dealer Pvt Ltd");
                  created.setStatus("ACTIVE");
                  created.setCreditLimit(new BigDecimal("100000.00"));
                  created.setOutstandingBalance(BigDecimal.ZERO);
                  return dealerRepository.saveAndFlush(created);
                });

    seedRevenueAndExpenseJournals();
    seedOutstandingDealerLedgerEntry();
  }

  @Test
  void financialReportEndpoints_returnExpectedStructuresAndBalancedTotals() {
    LocalDate startDate = LocalDate.now().withDayOfMonth(1);
    LocalDate endDate = LocalDate.now();

    Map<String, Object> trialBalanceData =
        fetchDataMap(
            String.format(
                "/api/v1/reports/trial-balance?startDate=%s&endDate=%s", startDate, endDate));
    BigDecimal totalDebit = decimal(trialBalanceData.get("totalDebit"));
    BigDecimal totalCredit = decimal(trialBalanceData.get("totalCredit"));
    assertThat(totalDebit).isEqualByComparingTo(totalCredit);
    assertThat((List<?>) trialBalanceData.get("rows")).isNotEmpty();

    Map<String, Object> profitLossData =
        fetchDataMap(
            String.format(
                "/api/v1/reports/profit-loss?startDate=%s&endDate=%s", startDate, endDate));
    assertThat(profitLossData).containsKeys("revenue", "operatingExpenses", "netIncome");

    Map<String, Object> balanceSheetData =
        fetchDataMap(
            String.format(
                "/api/v1/reports/balance-sheet?startDate=%s&endDate=%s", startDate, endDate));
    BigDecimal totalAssets = decimal(balanceSheetData.get("totalAssets"));
    BigDecimal totalLiabilities = decimal(balanceSheetData.get("totalLiabilities"));
    BigDecimal totalEquity = decimal(balanceSheetData.get("totalEquity"));
    assertThat(totalAssets).isEqualByComparingTo(totalLiabilities.add(totalEquity));

    Map<String, Object> balanceSheetHierarchy =
        fetchDataMap("/api/v1/reports/balance-sheet/hierarchy");
    assertThat(balanceSheetHierarchy).containsKeys("assets", "liabilities", "equity");
    List<Map<String, Object>> assets = castListOfMap(balanceSheetHierarchy.get("assets"));
    if (!assets.isEmpty()) {
      assertThat(assets.getFirst()).containsKey("children");
    }

    Map<String, Object> incomeStatementHierarchy =
        fetchDataMap("/api/v1/reports/income-statement/hierarchy");
    assertThat(incomeStatementHierarchy).containsKeys("revenue", "expenses");
    List<Map<String, Object>> revenue = castListOfMap(incomeStatementHierarchy.get("revenue"));
    if (!revenue.isEmpty()) {
      assertThat(revenue.getFirst()).containsKey("children");
    }

    Map<String, Object> cashFlowData = fetchDataMap("/api/v1/reports/cash-flow");
    assertThat(cashFlowData).containsKeys("operating", "investing", "financing");

    Map<String, Object> gstReturnData = fetchDataMap("/api/v1/reports/gst-return");
    assertThat(gstReturnData).containsKeys("outputTax", "inputTaxCredit", "netLiability");

    Map<String, Object> gstReconciliationData =
        fetchDataMap("/api/v1/accounting/gst/reconciliation");
    assertThat(gstReconciliationData).containsKeys("collected", "inputTaxCredit", "netLiability");

    Map<String, Object> accountingGstReturnData = fetchDataMap("/api/v1/accounting/gst/return");
    assertThat(accountingGstReturnData).containsKeys("outputTax", "inputTax", "netPayable");

    assertThat(componentTotal(gstReturnData.get("outputTax")))
        .isEqualByComparingTo(componentTotal(gstReconciliationData.get("collected")));
    assertThat(componentTotal(gstReturnData.get("inputTaxCredit")))
        .isEqualByComparingTo(componentTotal(gstReconciliationData.get("inputTaxCredit")));
    assertThat(componentTotal(gstReturnData.get("netLiability")))
        .isEqualByComparingTo(componentTotal(gstReconciliationData.get("netLiability")));

    assertThat(decimal(accountingGstReturnData.get("outputTax")))
        .isEqualByComparingTo(componentTotal(gstReturnData.get("outputTax")));
    assertThat(decimal(accountingGstReturnData.get("inputTax")))
        .isEqualByComparingTo(componentTotal(gstReturnData.get("inputTaxCredit")));
    assertThat(decimal(accountingGstReturnData.get("netPayable")))
        .isEqualByComparingTo(componentTotal(gstReturnData.get("netLiability")));
  }

  @Test
  void agingEndpoints_returnNonEmptyDataWithOutstandingDealerBuckets() {
    List<Map<String, Object>> agedDebtorsData =
        castListOfMap(fetchDataObject("/api/v1/reports/aged-debtors"));
    assertThat(agedDebtorsData).isNotEmpty();
    assertThat(agedDebtorsData.getFirst())
        .containsKeys("dealerId", "dealerName", "current", "oneToThirtyDays", "totalOutstanding");

    Map<String, Object> agedReceivablesData =
        castMap(fetchDataObject("/api/v1/reports/aging/receivables"));
    assertThat(agedReceivablesData).containsKeys("dealers", "totalBuckets", "grandTotal");
    List<Map<String, Object>> dealers = castListOfMap(agedReceivablesData.get("dealers"));
    assertThat(dealers).isNotEmpty();
    Map<String, Object> buckets = castMap(dealers.getFirst().get("buckets"));
    assertThat(buckets).containsKeys("current", "days1to30", "days31to60", "days61to90");
  }

  @Test
  void creditNormalRevenueAccount_hasConsistentSignsAcrossStatementAndTemporalEndpoints() {
    LocalDate fromDate = LocalDate.now().withDayOfMonth(1);
    LocalDate toDate = LocalDate.now();
    LocalDate openingAsOfDate = fromDate.minusDays(1);

    Map<String, Object> statementData =
        fetchDataMap(
            String.format(
                "/api/v1/reports/account-statement?accountId=%d&from=%s&to=%s",
                revenueAccount.getId(), fromDate, toDate));
    BigDecimal statementOpening = decimal(statementData.get("openingBalance"));
    BigDecimal statementClosing = decimal(statementData.get("closingBalance"));

    BigDecimal openingAsOf =
        decimal(
            fetchDataObject(
                String.format(
                    "/api/v1/accounting/accounts/%d/balance/as-of?date=%s",
                    revenueAccount.getId(), openingAsOfDate)));
    BigDecimal closingAsOf =
        decimal(
            fetchDataObject(
                String.format(
                    "/api/v1/accounting/accounts/%d/balance/as-of?date=%s",
                    revenueAccount.getId(), toDate)));

    Map<String, Object> compareData =
        fetchDataMap(
            String.format(
                "/api/v1/accounting/accounts/%d/balance/compare?from=%s&to=%s",
                revenueAccount.getId(), openingAsOfDate, toDate));
    BigDecimal compareChange = decimal(compareData.get("change"));

    Map<String, Object> activityData =
        fetchDataMap(
            String.format(
                "/api/v1/accounting/accounts/%d/activity?from=%s&to=%s",
                revenueAccount.getId(), fromDate, toDate));
    BigDecimal activityOpening = decimal(activityData.get("openingBalance"));
    BigDecimal activityClosing = decimal(activityData.get("closingBalance"));
    BigDecimal activityNetMovement = decimal(activityData.get("netMovement"));
    BigDecimal activityTotalDebits = decimal(activityData.get("totalDebits"));
    BigDecimal activityTotalCredits = decimal(activityData.get("totalCredits"));

    assertThat(statementOpening).isEqualByComparingTo(openingAsOf);
    assertThat(statementClosing).isEqualByComparingTo(closingAsOf);
    assertThat(activityOpening).isEqualByComparingTo(openingAsOf);
    assertThat(activityClosing).isEqualByComparingTo(closingAsOf);
    assertThat(compareChange).isEqualByComparingTo(statementClosing.subtract(statementOpening));
    assertThat(activityNetMovement).isEqualByComparingTo(compareChange);
    assertThat(activityTotalCredits).isGreaterThan(activityTotalDebits);
    assertThat(activityNetMovement).isPositive();
  }

  private void seedRevenueAndExpenseJournals() {
    LocalDate entryDate = LocalDate.now();
    postJournal(
        entryDate,
        "M8-REV-JOURNAL",
        List.of(
            line(cashAccount.getId(), new BigDecimal("1200.00"), BigDecimal.ZERO),
            line(revenueAccount.getId(), BigDecimal.ZERO, new BigDecimal("1200.00"))));

    postJournal(
        entryDate,
        "M8-EXP-JOURNAL",
        List.of(
            line(expenseAccount.getId(), new BigDecimal("300.00"), BigDecimal.ZERO),
            line(cashAccount.getId(), BigDecimal.ZERO, new BigDecimal("300.00"))));
  }

  private void seedOutstandingDealerLedgerEntry() {
    DealerLedgerEntry entry = new DealerLedgerEntry();
    entry.setCompany(company);
    entry.setDealer(dealer);
    entry.setEntryDate(LocalDate.now().minusDays(15));
    entry.setDueDate(LocalDate.now().minusDays(5));
    entry.setReferenceNumber("M8-AR-" + System.nanoTime());
    entry.setInvoiceNumber("M8-INV-" + System.nanoTime());
    entry.setPaymentStatus("UNPAID");
    entry.setAmountPaid(BigDecimal.ZERO);
    entry.setDebit(new BigDecimal("900.00"));
    entry.setCredit(BigDecimal.ZERO);
    dealerLedgerRepository.saveAndFlush(entry);
  }

  private void ensureCurrentPeriod() {
    LocalDate today = LocalDate.now();
    accountingPeriodRepository
        .findByCompanyAndYearAndMonth(company, today.getYear(), today.getMonthValue())
        .orElseGet(
            () -> {
              AccountingPeriod period = new AccountingPeriod();
              period.setCompany(company);
              period.setYear(today.getYear());
              period.setMonth(today.getMonthValue());
              period.setStartDate(today.withDayOfMonth(1));
              period.setEndDate(today.withDayOfMonth(today.lengthOfMonth()));
              return accountingPeriodRepository.saveAndFlush(period);
            });
  }

  private Account ensureAccount(String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account account = new Account();
              account.setCompany(company);
              account.setCode(code);
              account.setName(name);
              account.setType(type);
              return accountRepository.saveAndFlush(account);
            });
  }

  private void postJournal(LocalDate entryDate, String memo, List<Map<String, Object>> lines) {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(Map.of("entryDate", entryDate, "memo", memo, "lines", lines), headers),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private Map<String, Object> line(Long accountId, BigDecimal debit, BigDecimal credit) {
    return Map.of(
        "accountId", accountId,
        "description", "m8 reports line",
        "debit", debit,
        "credit", credit);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> fetchDataMap(String path) {
    return castMap(fetchDataObject(path));
  }

  private Object fetchDataObject(String path) {
    ResponseEntity<Map> response =
        rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody().get("data");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> castListOfMap(Object value) {
    return (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }

  private BigDecimal decimal(Object value) {
    return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
  }

  private BigDecimal componentTotal(Object value) {
    return decimal(castMap(value).get("total"));
  }

  private HttpHeaders authHeaders() {
    Map<String, Object> payload =
        Map.of(
            "email", ACCOUNTING_EMAIL,
            "password", PASSWORD,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> loginResponse =
        rest.postForEntity("/api/v1/auth/login", payload, Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    HttpHeaders authHeaders = new HttpHeaders();
    authHeaders.setBearerAuth(String.valueOf(loginResponse.getBody().get("accessToken")));
    authHeaders.set("X-Company-Code", COMPANY_CODE);
    authHeaders.setContentType(MediaType.APPLICATION_JSON);
    return authHeaders;
  }
}
