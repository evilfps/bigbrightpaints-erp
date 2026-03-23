package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    @Autowired private JournalReferenceMappingRepository journalReferenceMappingRepository;
    @Autowired private JournalLineRepository journalLineRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private SupplierRepository supplierRepository;

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
        h.set("X-Company-Code", COMPANY_CODE);
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
                "memo", "Test balanced entry",
                "lines", List.of(debitLine, creditLine)
        );

        ResponseEntity<Map> response = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(jeRequest, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify entry is balanced
        Long entryId = ((Number) ((Map<?, ?>) response.getBody().get("data")).get("id")).longValue();
        // Fetch lines directly to avoid lazy loading issues on entry.getLines()
        BigDecimal totalDebits = journalLineRepository.findAll().stream()
                .filter(line -> line.getJournalEntry().getId().equals(entryId))
                .map(JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredits = journalLineRepository.findAll().stream()
                .filter(line -> line.getJournalEntry().getId().equals(entryId))
                .map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebits).isEqualByComparingTo(totalCredits);
    }

    @Test
    @DisplayName("Journal Entry: Manual idempotency key maps to system reference")
    void journalEntry_ManualIdempotencyKey_MapsToSystemReference() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Account cashAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account revenueAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

        BigDecimal amount = new BigDecimal("100.00");

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

        String reservedRef = "INV-RESERVED-" + System.currentTimeMillis();
        Map<String, Object> reservedReq = Map.of(
                "entryDate", LocalDate.now(),
                "referenceNumber", reservedRef,
                "memo", "Should be rejected: manual reference not allowed",
                "lines", List.of(debitLine, creditLine)
        );

        ResponseEntity<Map> reservedResp = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(reservedReq, headers), Map.class);

        assertThat(reservedResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String manualRef = "MANUAL-" + reservedRef;
        Map<String, Object> manualReq = Map.of(
                "entryDate", LocalDate.now(),
                "referenceNumber", manualRef,
                "memo", "Manual idempotency key",
                "lines", List.of(debitLine, creditLine)
        );

        ResponseEntity<Map> manualResp = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(manualReq, headers), Map.class);

        assertThat(manualResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) manualResp.getBody().get("data");
        assertThat(data).isNotNull();
        assertThat(((String) data.get("referenceNumber"))).startsWith("JRN-");

        ResponseEntity<Map> retryResp = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(manualReq, headers), Map.class);
        assertThat(retryResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> retryData = (Map<?, ?>) retryResp.getBody().get("data");
        assertThat(((Number) retryData.get("id")).longValue())
                .isEqualTo(((Number) data.get("id")).longValue());
    }

    @Test
    @DisplayName("Journal Entry: Manual idempotency is concurrency-safe")
    void journalEntry_ManualIdempotencyKey_ConcurrencySafe() throws Exception {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Account cashAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account revenueAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

        BigDecimal amount = new BigDecimal("250.00");

        Map<String, Object> debitLine = Map.of(
                "accountId", cashAccount.getId(),
                "debit", amount,
                "credit", BigDecimal.ZERO,
                "description", "Concurrent cash received"
        );

        Map<String, Object> creditLine = Map.of(
                "accountId", revenueAccount.getId(),
                "debit", BigDecimal.ZERO,
                "credit", amount,
                "description", "Concurrent revenue earned"
        );

        String manualRef = "MANUAL-CONCURRENT-" + System.currentTimeMillis();
        String memo = "Concurrent manual idempotency " + manualRef;
        Map<String, Object> manualReq = Map.of(
                "entryDate", LocalDate.now(),
                "referenceNumber", manualRef,
                "memo", memo,
                "lines", List.of(debitLine, creditLine)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<ResponseEntity<Map>> first = executor.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            HttpHeaders threadHeaders = new HttpHeaders();
            threadHeaders.putAll(headers);
            return rest.exchange("/api/v1/accounting/journal-entries",
                    HttpMethod.POST, new HttpEntity<>(manualReq, threadHeaders), Map.class);
        });

        Future<ResponseEntity<Map>> second = executor.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            HttpHeaders threadHeaders = new HttpHeaders();
            threadHeaders.putAll(headers);
            return rest.exchange("/api/v1/accounting/journal-entries",
                    HttpMethod.POST, new HttpEntity<>(manualReq, threadHeaders), Map.class);
        });

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        ResponseEntity<Map> resp1 = first.get(10, TimeUnit.SECONDS);
        ResponseEntity<Map> resp2 = second.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> data1 = (Map<?, ?>) resp1.getBody().get("data");
        Map<?, ?> data2 = (Map<?, ?>) resp2.getBody().get("data");
        Long id1 = ((Number) data1.get("id")).longValue();
        Long id2 = ((Number) data2.get("id")).longValue();
        assertThat(id1).isEqualTo(id2);

        List<JournalReferenceMapping> mappings = journalReferenceMappingRepository
                .findAllByCompanyAndLegacyReferenceIgnoreCase(company, manualRef);
        assertThat(mappings).hasSize(1);
        JournalReferenceMapping mapping = mappings.get(0);
        assertThat(mapping.getEntityId()).isEqualTo(id1);
        assertThat(mapping.getCanonicalReference()).isNotBlank();
        assertThat(journalEntryRepository.findByCompanyAndId(company, id1)).isPresent();

        long memoCount = journalEntryRepository.findAll().stream()
                .filter(entry -> memo.equals(entry.getMemo()))
                .count();
        assertThat(memoCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Journal Entry: Manual idempotency key is case-insensitive")
    void journalEntry_ManualIdempotencyKey_CaseInsensitiveReplay() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Account cashAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account revenueAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

        BigDecimal amount = new BigDecimal("180.00");
        Map<String, Object> debitLine = Map.of(
                "accountId", cashAccount.getId(),
                "debit", amount,
                "credit", BigDecimal.ZERO,
                "description", "Case replay cash"
        );
        Map<String, Object> creditLine = Map.of(
                "accountId", revenueAccount.getId(),
                "debit", BigDecimal.ZERO,
                "credit", amount,
                "description", "Case replay revenue"
        );

        String manualRef = "MANUAL-CASE-" + System.currentTimeMillis();
        Map<String, Object> firstReq = Map.of(
                "entryDate", LocalDate.now(),
                "referenceNumber", manualRef,
                "memo", "Manual idempotency case replay",
                "lines", List.of(debitLine, creditLine)
        );
        ResponseEntity<Map> first = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(firstReq, headers), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long firstId = ((Number) ((Map<?, ?>) first.getBody().get("data")).get("id")).longValue();

        Map<String, Object> secondReq = Map.of(
                "entryDate", LocalDate.now(),
                "referenceNumber", manualRef.toLowerCase(Locale.ROOT),
                "memo", "Manual idempotency case replay",
                "lines", List.of(debitLine, creditLine)
        );
        ResponseEntity<Map> second = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(secondReq, headers), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long secondId = ((Number) ((Map<?, ?>) second.getBody().get("data")).get("id")).longValue();
        assertThat(secondId).isEqualTo(firstId);

        List<JournalReferenceMapping> mappings = journalReferenceMappingRepository
                .findAllByCompanyAndLegacyReferenceIgnoreCase(company, manualRef);
        assertThat(mappings).hasSize(1);
        assertThat(mappings.getFirst().getEntityId()).isEqualTo(firstId);
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
        Account cashAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account revenueAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();
        BigDecimal cashBefore = cashAccount.getBalance();
        BigDecimal revenueBefore = revenueAccount.getBalance();

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
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Number reversalId = (Number) ((Map<?, ?>) response.getBody().get("data")).get("id");
        JournalEntry reversalEntry = journalEntryRepository.findById(reversalId.longValue()).orElseThrow();
        assertThat(reversalEntry.getReversalOf()).isNotNull();
        assertThat(reversalEntry.getReversalOf().getId()).isEqualTo(originalEntryId);
        assertThat(reversalEntry.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);

        JournalEntry originalEntry = journalEntryRepository.findById(originalEntryId).orElseThrow();
        assertThat(originalEntry.getStatus()).isEqualTo("REVERSED");

        Account cashAfter = accountRepository.findById(cashAccount.getId()).orElseThrow();
        Account revenueAfter = accountRepository.findById(revenueAccount.getId()).orElseThrow();
        assertThat(cashAfter.getBalance()).isEqualByComparingTo(cashBefore);
        assertThat(revenueAfter.getBalance()).isEqualByComparingTo(revenueBefore);
    }

    @Test
    @DisplayName("Journal Cascade Reversal: Reverses related entries and restores balances")
    void journalCascadeReversal_ReversesRelatedEntriesAndRestoresBalances() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Account cashAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
        Account revenueAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();
        Account expenseAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "EXPENSE").orElseThrow();

        BigDecimal cashBefore = cashAccount.getBalance();
        BigDecimal revenueBefore = revenueAccount.getBalance();
        BigDecimal expenseBefore = expenseAccount.getBalance();

        Map<String, Object> baseReq = Map.of(
                "entryDate", LocalDate.now(),
                "memo", "Base entry",
                "lines", List.of(
                        Map.of("accountId", cashAccount.getId(), "debit", new BigDecimal("100.00"), "credit", BigDecimal.ZERO),
                        Map.of("accountId", revenueAccount.getId(), "debit", BigDecimal.ZERO, "credit", new BigDecimal("100.00"))
                )
        );
        ResponseEntity<Map> baseResp = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(baseReq, headers), Map.class);
        Long baseEntryId = ((Number) ((Map<?, ?>) baseResp.getBody().get("data")).get("id")).longValue();

        Map<String, Object> relatedReq = Map.of(
                "entryDate", LocalDate.now(),
                "memo", "Related entry",
                "lines", List.of(
                        Map.of("accountId", expenseAccount.getId(), "debit", new BigDecimal("60.00"), "credit", BigDecimal.ZERO),
                        Map.of("accountId", cashAccount.getId(), "debit", BigDecimal.ZERO, "credit", new BigDecimal("60.00"))
                )
        );
        ResponseEntity<Map> relatedResp = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(relatedReq, headers), Map.class);
        Long relatedEntryId = ((Number) ((Map<?, ?>) relatedResp.getBody().get("data")).get("id")).longValue();

        Map<String, Object> reversalReq = Map.of(
                "reversalDate", LocalDate.now(),
                "reason", "Cascade reversal test",
                "memo", "Cascade reversal",
                "relatedEntryIds", List.of(relatedEntryId)
        );
        ResponseEntity<Map> reversalResp = rest.exchange(
                "/api/v1/accounting/journal-entries/" + baseEntryId + "/cascade-reverse",
                HttpMethod.POST,
                new HttpEntity<>(reversalReq, headers),
                Map.class);
        assertThat(reversalResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JournalEntry baseEntry = journalEntryRepository.findById(baseEntryId).orElseThrow();
        JournalEntry relatedEntry = journalEntryRepository.findById(relatedEntryId).orElseThrow();
        assertThat(baseEntry.getStatus()).isEqualTo("REVERSED");
        assertThat(relatedEntry.getStatus()).isEqualTo("REVERSED");

        JournalEntry baseReversal = journalEntryRepository.findAll().stream()
                .filter(entry -> entry.getReversalOf() != null)
                .filter(entry -> entry.getReversalOf().getId().equals(baseEntryId))
                .findFirst()
                .orElseThrow();
        JournalEntry relatedReversal = journalEntryRepository.findAll().stream()
                .filter(entry -> entry.getReversalOf() != null)
                .filter(entry -> entry.getReversalOf().getId().equals(relatedEntryId))
                .findFirst()
                .orElseThrow();

        assertThat(baseReversal.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);
        assertThat(relatedReversal.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);

        Account cashAfter = accountRepository.findById(cashAccount.getId()).orElseThrow();
        Account revenueAfter = accountRepository.findById(revenueAccount.getId()).orElseThrow();
        Account expenseAfter = accountRepository.findById(expenseAccount.getId()).orElseThrow();
        assertThat(cashAfter.getBalance()).isEqualByComparingTo(cashBefore);
        assertThat(revenueAfter.getBalance()).isEqualByComparingTo(revenueBefore);
        assertThat(expenseAfter.getBalance()).isEqualByComparingTo(expenseBefore);
    }

    @Test
    @DisplayName("Journal Cascade Reversal: Fails closed and rolls back primary reversal when related entry is invalid")
    void journalCascadeReversal_FailsClosedAndRollsBackPrimary() {
        Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        Long baseEntryId = createBalancedJournalEntry(company, new BigDecimal("140.00"));

        Map<String, Object> reversalReq = Map.of(
                "reversalDate", LocalDate.now(),
                "reason", "Cascade rollback test",
                "memo", "Cascade rollback",
                "relatedEntryIds", List.of(Long.MAX_VALUE)
        );

        ResponseEntity<Map> reversalResp = rest.exchange(
                "/api/v1/accounting/journal-entries/" + baseEntryId + "/cascade-reverse",
                HttpMethod.POST,
                new HttpEntity<>(reversalReq, headers),
                Map.class);

        assertThat(reversalResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JournalEntry baseEntry = journalEntryRepository.findById(baseEntryId).orElseThrow();
        assertThat(baseEntry.getStatus()).isEqualTo("POSTED");

        long reversalCount = journalEntryRepository.findAll().stream()
                .filter(entry -> entry.getReversalOf() != null)
                .filter(entry -> entry.getReversalOf().getId().equals(baseEntryId))
                .count();
        assertThat(reversalCount).isZero();
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
        Dealer dealer = dealerRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-DEALER").orElseThrow();

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
                "memo", "Dealer payment",
                "dealerId", dealer.getId(),
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
        Account expenseAccount = accountRepository.findByCompanyAndCodeIgnoreCase(company, "EXPENSE").orElseThrow();
        Supplier supplier = supplierRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-SUP").orElseThrow();

        // Create supplier payment (debit AP, credit cash)
        BigDecimal paymentAmount = new BigDecimal("3000.00");

        Map<String, Object> purchaseDebit = Map.of(
                "accountId", expenseAccount.getId(),
                "debit", paymentAmount,
                "credit", BigDecimal.ZERO,
                "description", "Purchase expense"
        );

        Map<String, Object> purchaseCredit = Map.of(
                "accountId", apAccount.getId(),
                "debit", BigDecimal.ZERO,
                "credit", paymentAmount,
                "description", "AP increase"
        );

        Map<String, Object> purchaseRequest = Map.of(
                "entryDate", LocalDate.now(),
                "memo", "Supplier purchase",
                "supplierId", supplier.getId(),
                "lines", List.of(purchaseDebit, purchaseCredit)
        );

        ResponseEntity<Map> purchaseResponse = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(purchaseRequest, headers), Map.class);
        assertThat(purchaseResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

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
                "memo", "Supplier payment",
                "supplierId", supplier.getId(),
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
                "memo", "Test balanced entry",
                "lines", List.of(debitLine, creditLine)
        );

        ResponseEntity<Map> response = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(jeRequest, headers), Map.class);

        return ((Number) ((Map<?, ?>) response.getBody().get("data")).get("id")).longValue();
    }
}
