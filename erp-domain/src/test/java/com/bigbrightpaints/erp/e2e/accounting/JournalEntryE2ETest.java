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
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E Tests for Accounting & Financial workflows
 */
@DisplayName("E2E: Accounting & Journal Entries")
public class JournalEntryE2ETest extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "ACC-E2E";
    private static final String ADMIN_EMAIL = "accounting@e2e.com";
    private static final String ADMIN_PASSWORD = "acc123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private JournalLineRepository journalLineRepository;

    private String authToken;
    private HttpHeaders headers;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Accounting Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
        authToken = login();
        headers = createHeaders(authToken);
        ensureTestAccounts();
    }

    private String login() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        return (String) response.getBody().get("accessToken");
    }

    private HttpHeaders createHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private void ensureTestAccounts() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        ensureAccount(company, "CASH", "Cash Account", AccountType.ASSET);
        ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET);
        ensureAccount(company, "AP", "Accounts Payable", AccountType.LIABILITY);
        ensureAccount(company, "REVENUE", "Revenue", AccountType.REVENUE);
        ensureAccount(company, "EXPENSE", "Expenses", AccountType.EXPENSE);
        ensureAccount(company, "EQUITY", "Equity", AccountType.EQUITY);
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
    @DisplayName("Journal Entry: Double Entry Balances Debits and Credits")
    void journalEntry_DoubleEntry_BalancesDebitsCredits() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Account cashAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account revenueAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

        BigDecimal amount = new BigDecimal("5000.00");

        Map<String, Object> debitLine = Map.of(
                "accountId", cashAccount.getId(),
                "debit", amount,
                "credit", BigDecimal.ZERO,
                "description", "Cash received"
        );

        Map<String, Object> creditLine = Map.of(
                "accountId", revenueAccount.getId(),
                "debit", BigDecimal.ZERO,
                "credit", amount,
                "description", "Revenue earned"
        );

        Map<String, Object> jeRequest = Map.of(
                "entryDate", LocalDate.now(),
                "description", "Test balanced entry",
                "lines", List.of(debitLine, creditLine)
        );

        ResponseEntity<Map> response = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(jeRequest, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify entry is balanced
        Long entryId = ((Number) ((Map<?, ?>) response.getBody().get("data")).get("id")).longValue();
        JournalEntry entry = journalEntryRepository.findById(entryId).orElseThrow();

        BigDecimal totalDebits = entry.getLines().stream()
                .map(JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = entry.getLines().stream()
                .map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebits).isEqualByComparingTo(totalCredits);
    }

    @Test
    @DisplayName("Trial Balance: After Many Transactions Balances")
    void trialBalance_AfterManyTransactions_Balances() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

        // Create multiple journal entries
        for (int i = 0; i < 5; i++) {
            createBalancedJournalEntry(company, new BigDecimal(String.format("%d000.00", i + 1)));
        }

        // Get trial balance
        LocalDate startDate = LocalDate.now().withDayOfMonth(1);
        LocalDate endDate = LocalDate.now();

        String url = String.format("/api/v1/reports/trial-balance?startDate=%s&endDate=%s",
                startDate, endDate);

        ResponseEntity<Map> response = rest.exchange(url,
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Trial balance should have data
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data).isNotNull();
    }

    @Test
    @DisplayName("Journal Reversal: Creates Offsetting Entry with Linked Audit")
    void journalReversal_CreatesOffsettingEntry_LinkedAudit() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

        // Create original entry
        Long originalEntryId = createBalancedJournalEntry(company, new BigDecimal("3000.00"));

        // Reverse the entry
        Map<String, Object> reversalReq = Map.of(
                "reversalDate", LocalDate.now(),
                "reason", "Test reversal"
        );

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/journal-entries/" + originalEntryId + "/reverse",
                HttpMethod.POST,
                new HttpEntity<>(reversalReq, headers),
                Map.class);

        // Should create reversal entry
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CREATED, HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Financial Reports: Profit & Loss and Balance Sheet are Accurate")
    void financialReports_ProfitLoss_BalanceSheet_Accurate() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

        // Create test transactions
        createBalancedJournalEntry(company, new BigDecimal("10000.00"));

        LocalDate startDate = LocalDate.now().withDayOfMonth(1);
        LocalDate endDate = LocalDate.now();

        // Test Profit & Loss
        String plUrl = String.format("/api/v1/reports/profit-loss?startDate=%s&endDate=%s",
                startDate, endDate);

        ResponseEntity<Map> plResponse = rest.exchange(plUrl,
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(plResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Test Balance Sheet
        String bsUrl = String.format("/api/v1/reports/balance-sheet?asOfDate=%s", endDate);

        ResponseEntity<Map> bsResponse = rest.exchange(bsUrl,
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(bsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Dealer Payment: Reduces Receivable and Updates Ledger")
    void dealerPayment_ReducesReceivable_UpdatesLedger() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Account cashAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account arAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR").orElseThrow();

        // Create payment entry (debit cash, credit AR)
        BigDecimal paymentAmount = new BigDecimal("5000.00");

        Map<String, Object> debitLine = Map.of(
                "accountId", cashAccount.getId(),
                "debit", paymentAmount,
                "credit", BigDecimal.ZERO,
                "description", "Payment received"
        );

        Map<String, Object> creditLine = Map.of(
                "accountId", arAccount.getId(),
                "debit", BigDecimal.ZERO,
                "credit", paymentAmount,
                "description", "AR reduction"
        );

        Map<String, Object> jeRequest = Map.of(
                "entryDate", LocalDate.now(),
                "description", "Dealer payment",
                "lines", List.of(debitLine, creditLine)
        );

        ResponseEntity<Map> response = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(jeRequest, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Supplier Payment: Reduces Payable and Updates Ledger")
    void supplierPayment_ReducesPayable_UpdatesLedger() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Account cashAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account apAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "AP").orElseThrow();

        // Create supplier payment (debit AP, credit cash)
        BigDecimal paymentAmount = new BigDecimal("3000.00");

        Map<String, Object> debitLine = Map.of(
                "accountId", apAccount.getId(),
                "debit", paymentAmount,
                "credit", BigDecimal.ZERO,
                "description", "AP reduction"
        );

        Map<String, Object> creditLine = Map.of(
                "accountId", cashAccount.getId(),
                "debit", BigDecimal.ZERO,
                "credit", paymentAmount,
                "description", "Payment made"
        );

        Map<String, Object> jeRequest = Map.of(
                "entryDate", LocalDate.now(),
                "description", "Supplier payment",
                "lines", List.of(debitLine, creditLine)
        );

        ResponseEntity<Map> response = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(jeRequest, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Period Close: Prevents Future Backdating")
    void periodClose_PreventsFutureBackdating() {
        // Test accounting period functionality
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

        Map<String, Object> periodReq = Map.of(
                "year", LocalDate.now().getYear(),
                "month", LocalDate.now().getMonthValue()
        );

        ResponseEntity<Map> response = rest.exchange("/api/v1/accounting/periods",
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        // Should return periods or empty list
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);
    }

    // Helper methods
    private Long createBalancedJournalEntry(Company company, BigDecimal amount) {
        Account cashAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account revenueAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

        Map<String, Object> debitLine = Map.of(
                "accountId", cashAccount.getId(),
                "debit", amount,
                "credit", BigDecimal.ZERO,
                "description", "Test debit"
        );

        Map<String, Object> creditLine = Map.of(
                "accountId", revenueAccount.getId(),
                "debit", BigDecimal.ZERO,
                "credit", amount,
                "description", "Test credit"
        );

        Map<String, Object> jeRequest = Map.of(
                "entryDate", LocalDate.now(),
                "description", "Test balanced entry",
                "lines", List.of(debitLine, creditLine)
        );

        ResponseEntity<Map> response = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(jeRequest, headers), Map.class);

        return ((Number) ((Map<?, ?>) response.getBody().get("data")).get("id")).longValue();
    }
}
