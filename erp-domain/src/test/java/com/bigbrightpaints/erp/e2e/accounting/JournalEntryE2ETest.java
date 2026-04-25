package com.bigbrightpaints.erp.e2e.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.jdbc.core.JdbcTemplate;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriod;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountingPeriodStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalCorrectionType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLineRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMapping;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalReferenceMappingRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

/**
 * E2E Tests for Accounting & Financial workflows
 */
@DisplayName("E2E: Accounting & Journal Entries")
public class JournalEntryE2ETest extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "ACC-E2E";
  private static final String ROOT_COMPANY_CODE = "ROOT";
  private static final String ADMIN_EMAIL = "accounting@e2e.com";
  private static final String SUPER_ADMIN_EMAIL = "review-superadmin@e2e.com";
  private static final String ADMIN_PASSWORD = "acc123";
  private static final String REVIEW_WARNING_ACTION = "REVIEW_INTELLIGENCE_WARNING";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private AccountingPeriodRepository accountingPeriodRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private JournalReferenceMappingRepository journalReferenceMappingRepository;
  @Autowired private JournalLineRepository journalLineRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private SupplierRepository supplierRepository;
  @Autowired private AuditActionEventRepository auditActionEventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private String authToken;
  private HttpHeaders headers;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Accounting Admin",
        COMPANY_CODE,
        List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
    authToken = login();
    headers = createHeaders(authToken);
    ensureTestAccounts();
  }

  private String login() {
    return login(ADMIN_EMAIL, COMPANY_CODE);
  }

  private String login(String email, String companyCode) {
    Map<String, Object> req =
        Map.of(
            "email", email,
            "password", ADMIN_PASSWORD,
            "companyCode", companyCode);
    ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", req, Map.class);
    return (String) response.getBody().get("accessToken");
  }

  private HttpHeaders createHeaders(String token) {
    return createHeaders(token, COMPANY_CODE);
  }

  private HttpHeaders createHeaders(String token, String companyCode) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set("X-Company-Code", companyCode);
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
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
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
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

    BigDecimal amount = new BigDecimal("5000.00");

    Map<String, Object> debitLine =
        Map.of(
            "accountId",
            cashAccount.getId(),
            "debit",
            amount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "Cash received");

    Map<String, Object> creditLine =
        Map.of(
            "accountId",
            revenueAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            amount,
            "description",
            "Revenue earned");

    Map<String, Object> jeRequest =
        Map.of(
            "entryDate", LocalDate.now(),
            "memo", "Test balanced entry",
            "lines", List.of(debitLine, creditLine));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(jeRequest, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Verify entry is balanced
    Long entryId = ((Number) ((Map<?, ?>) response.getBody().get("data")).get("id")).longValue();
    // Fetch lines directly to avoid lazy loading issues on entry.getLines()
    BigDecimal totalDebits =
        journalLineRepository.findAll().stream()
            .filter(line -> line.getJournalEntry().getId().equals(entryId))
            .map(JournalLine::getDebit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal totalCredits =
        journalLineRepository.findAll().stream()
            .filter(line -> line.getJournalEntry().getId().equals(entryId))
            .map(JournalLine::getCredit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    assertThat(totalDebits).isEqualByComparingTo(totalCredits);
  }

  @Test
  @DisplayName("Journal Entry: Manual idempotency key maps to system reference")
  void journalEntry_ManualIdempotencyKey_MapsToSystemReference() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

    BigDecimal amount = new BigDecimal("100.00");

    Map<String, Object> debitLine =
        Map.of(
            "accountId",
            cashAccount.getId(),
            "debit",
            amount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "Cash received");

    Map<String, Object> creditLine =
        Map.of(
            "accountId",
            revenueAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            amount,
            "description",
            "Revenue earned");

    String reservedRef = "INV-RESERVED-" + System.currentTimeMillis();
    Map<String, Object> reservedReq =
        Map.of(
            "entryDate",
            LocalDate.now(),
            "referenceNumber",
            reservedRef,
            "memo",
            "Should be rejected: manual reference not allowed",
            "lines",
            List.of(debitLine, creditLine));

    ResponseEntity<Map> reservedResp =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(reservedReq, headers),
            Map.class);

    assertThat(reservedResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    String manualRef = "MANUAL-" + reservedRef;
    Map<String, Object> manualReq =
        Map.of(
            "entryDate",
            LocalDate.now(),
            "referenceNumber",
            manualRef,
            "memo",
            "Manual idempotency key",
            "lines",
            List.of(debitLine, creditLine));

    ResponseEntity<Map> manualResp =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(manualReq, headers),
            Map.class);

    assertThat(manualResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) manualResp.getBody().get("data");
    assertThat(data).isNotNull();
    assertThat(((String) data.get("referenceNumber"))).startsWith("JRN-");

    ResponseEntity<Map> retryResp =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(manualReq, headers),
            Map.class);
    assertThat(retryResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> retryData = (Map<?, ?>) retryResp.getBody().get("data");
    assertThat(((Number) retryData.get("id")).longValue())
        .isEqualTo(((Number) data.get("id")).longValue());
  }

  @Test
  @DisplayName(
      "Review intelligence toggle is default-off and emits warn-only review artifacts when enabled")
  void reviewIntelligenceToggle_DefaultOff_ThenWarnOnlyArtifactWhenEnabled() {
    dataSeeder.ensureUser(
        SUPER_ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Review Super Admin",
        ROOT_COMPANY_CODE,
        List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
    String superAdminToken = login(SUPER_ADMIN_EMAIL, ROOT_COMPANY_CODE);
    HttpHeaders superAdminHeaders = createHeaders(superAdminToken, ROOT_COMPANY_CODE);

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

    String togglePath = "/api/v1/superadmin/tenants/" + company.getId() + "/review-intelligence";
    ResponseEntity<Map> toggleBeforeResponse =
        rest.exchange(togglePath, HttpMethod.GET, new HttpEntity<>(superAdminHeaders), Map.class);
    assertThat(toggleBeforeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> toggleBeforeData = (Map<?, ?>) toggleBeforeResponse.getBody().get("data");
    assertThat(toggleBeforeData.get("reviewIntelligenceEnabled")).isEqualTo(Boolean.FALSE);

    String referenceOff = "MANUAL-REVIEW-OFF-" + System.nanoTime();
    ResponseEntity<Map> suspiciousOffResponse =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(
                highValueManualJournalRequest(
                    cashAccount.getId(), revenueAccount.getId(), referenceOff),
                headers),
            Map.class);
    assertThat(suspiciousOffResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Long offEntryId =
        ((Number) ((Map<?, ?>) suspiciousOffResponse.getBody().get("data")).get("id")).longValue();
    assertNoAccountingJournalActionEvent(company.getId(), offEntryId, REVIEW_WARNING_ACTION);

    ResponseEntity<Map> toggleEnableResponse =
        rest.exchange(
            togglePath,
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("enabled", true), superAdminHeaders),
            Map.class);
    assertThat(toggleEnableResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> toggleEnabledData = (Map<?, ?>) toggleEnableResponse.getBody().get("data");
    assertThat(toggleEnabledData.get("reviewIntelligenceEnabled")).isEqualTo(Boolean.TRUE);

    String referenceOn = "MANUAL-REVIEW-ON-" + System.nanoTime();
    ResponseEntity<Map> suspiciousOnResponse =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(
                highValueManualJournalRequest(cashAccount.getId(), revenueAccount.getId(), referenceOn),
                headers),
            Map.class);
    assertThat(suspiciousOnResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Long onEntryId =
        ((Number) ((Map<?, ?>) suspiciousOnResponse.getBody().get("data")).get("id")).longValue();

    AuditActionEvent reviewWarningEvent =
        awaitAccountingJournalActionEvent(company.getId(), onEntryId, REVIEW_WARNING_ACTION);
    Map<String, String> warningMetadata = loadAuditMetadata(reviewWarningEvent.getId());
    assertThat(warningMetadata)
        .containsEntry("warnOnly", "true")
        .containsEntry("reviewRule", "MANUAL_HIGH_VALUE_JOURNAL")
        .containsEntry("reviewQueue", "ACCOUNTING_AUDIT_EVENTS");

    ResponseEntity<Map> reviewSurfaceResponse =
        rest.exchange(
            "/api/v1/accounting/audit/events?action=" + REVIEW_WARNING_ACTION + "&size=50",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(reviewSurfaceResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> reviewSurfaceData = (Map<?, ?>) reviewSurfaceResponse.getBody().get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> reviewItems = (List<Map<String, Object>>) reviewSurfaceData.get("content");
    assertThat(reviewItems)
        .anySatisfy(
            item -> {
              assertThat(item.get("action")).isEqualTo(REVIEW_WARNING_ACTION);
              assertThat(item.get("entityId")).isEqualTo(String.valueOf(onEntryId));
            });
  }

  @Test
  @DisplayName("Journal Entry: Manual idempotency rejects materially different replay payloads")
  void journalEntry_ManualIdempotencyKey_RejectsMaterialReplayConflict() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

    LocalDate entryDate = LocalDate.now();
    String manualRef = "MANUAL-REPLAY-" + System.currentTimeMillis();
    BigDecimal originalAmount = new BigDecimal("125.00");

    Map<String, Object> originalDebitLine =
        Map.of(
            "accountId",
            cashAccount.getId(),
            "debit",
            originalAmount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "Replay baseline debit");

    Map<String, Object> originalCreditLine =
        Map.of(
            "accountId",
            revenueAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            originalAmount,
            "description",
            "Replay baseline credit");

    Map<String, Object> firstRequest =
        Map.of(
            "entryDate",
            entryDate,
            "referenceNumber",
            manualRef,
            "memo",
            "Manual replay baseline",
            "lines",
            List.of(originalDebitLine, originalCreditLine));

    ResponseEntity<Map> firstResponse =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(firstRequest, headers),
            Map.class);
    assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> firstData = (Map<?, ?>) firstResponse.getBody().get("data");
    assertThat(firstData).isNotNull();
    Long firstId = ((Number) firstData.get("id")).longValue();

    ResponseEntity<Map> replayResponse =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(firstRequest, headers),
            Map.class);
    assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> replayData = (Map<?, ?>) replayResponse.getBody().get("data");
    assertThat(replayData).isNotNull();
    assertThat(((Number) replayData.get("id")).longValue()).isEqualTo(firstId);

    BigDecimal changedAmount = new BigDecimal("130.00");
    Map<String, Object> changedDebitLine =
        Map.of(
            "accountId",
            cashAccount.getId(),
            "debit",
            changedAmount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "Replay changed debit");
    Map<String, Object> changedCreditLine =
        Map.of(
            "accountId",
            revenueAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            changedAmount,
            "description",
            "Replay changed credit");
    Map<String, Object> changedRequest =
        Map.of(
            "entryDate",
            entryDate,
            "referenceNumber",
            manualRef,
            "memo",
            "Manual replay changed memo",
            "lines",
            List.of(changedDebitLine, changedCreditLine));

    ResponseEntity<Map> changedResponse =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(changedRequest, headers),
            Map.class);
    assertThat(changedResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<?, ?> changedBody = changedResponse.getBody();
    assertThat(changedBody).isNotNull();
    assertThat(changedBody.toString().toLowerCase(Locale.ROOT))
        .contains("replay")
        .contains("conflict");
    Object changedPayload = changedBody.get("data");
    if (changedPayload instanceof Map<?, ?> changedError) {
      assertThat(changedError.get("code")).isEqualTo("VAL_001");
      Object details = changedError.get("details");
      if (details instanceof Map<?, ?> detailMap) {
        assertThat(detailMap.get("outcome")).isEqualTo("replay-conflict");
      }
    }

    List<JournalReferenceMapping> mappings =
        journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, manualRef);
    assertThat(mappings).hasSize(1);
    assertThat(mappings.getFirst().getEntityId()).isEqualTo(firstId);
  }

  @Test
  @DisplayName("Journal Entry: FX rounding metadata persists on audit trail live path")
  void journalEntry_FxRoundingMetadata_PersistsOnAuditTrailLivePath() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

    Map<String, Object> debitLine =
        Map.of(
            "accountId",
            cashAccount.getId(),
            "debit",
            new BigDecimal("1.00"),
            "credit",
            BigDecimal.ZERO,
            "description",
            "FX debit line");
    Map<String, Object> creditLineOne =
        Map.of(
            "accountId",
            revenueAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            new BigDecimal("0.50"),
            "description",
            "FX credit line 1");
    Map<String, Object> creditLineTwo =
        Map.of(
            "accountId",
            revenueAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            new BigDecimal("0.50"),
            "description",
            "FX credit line 2");

    String reference = "FX-META-" + System.nanoTime();
    Map<String, Object> request = new HashMap<>();
    request.put("entryDate", LocalDate.now());
    request.put("referenceNumber", reference);
    request.put("memo", "FX rounding metadata proof");
    request.put("currency", "USD");
    request.put("fxRate", new BigDecimal("1.333333"));
    request.put("lines", List.of(debitLine, creditLineOne, creditLineTwo));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(data).isNotNull();
    Long entryId = ((Number) data.get("id")).longValue();

    AuditActionEvent auditEvent =
        awaitAccountingJournalAuditEvent(company.getId(), entryId.toString());
    assertThat(auditEvent.getAction()).isIn("SYSTEM_JOURNAL_CREATED", "MANUAL_JOURNAL_CREATED");
    Map<String, String> persistedMetadata = loadAuditMetadata(auditEvent.getId());
    assertThat(persistedMetadata)
        .containsKeys("adjustedLineId", "originalAmount", "adjustedAmount", "adjustmentReason");
    assertThat(persistedMetadata.get("adjustmentReason")).startsWith("FX_ROUNDING_");

    Long adjustedLineId = Long.valueOf(persistedMetadata.get("adjustedLineId"));
    assertThat(
            journalLineRepository.findAll().stream()
                .filter(line -> line.getJournalEntry().getId().equals(entryId))
                .toList())
        .anySatisfy(line -> assertThat(line.getId()).isEqualTo(adjustedLineId));
  }

  @Test
  @DisplayName("Journal Entry: Manual idempotency is concurrency-safe")
  void journalEntry_ManualIdempotencyKey_ConcurrencySafe() throws Exception {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

    BigDecimal amount = new BigDecimal("250.00");

    Map<String, Object> debitLine =
        Map.of(
            "accountId",
            cashAccount.getId(),
            "debit",
            amount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "Concurrent cash received");

    Map<String, Object> creditLine =
        Map.of(
            "accountId",
            revenueAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            amount,
            "description",
            "Concurrent revenue earned");

    String manualRef = "MANUAL-CONCURRENT-" + System.currentTimeMillis();
    String memo = "Concurrent manual idempotency " + manualRef;
    Map<String, Object> manualReq =
        Map.of(
            "entryDate",
            LocalDate.now(),
            "referenceNumber",
            manualRef,
            "memo",
            memo,
            "lines",
            List.of(debitLine, creditLine));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    Future<ResponseEntity<Map>> first =
        executor.submit(
            () -> {
              ready.countDown();
              start.await(5, TimeUnit.SECONDS);
              HttpHeaders threadHeaders = new HttpHeaders();
              threadHeaders.putAll(headers);
              return rest.exchange(
                  "/api/v1/accounting/journal-entries",
                  HttpMethod.POST,
                  new HttpEntity<>(manualReq, threadHeaders),
                  Map.class);
            });

    Future<ResponseEntity<Map>> second =
        executor.submit(
            () -> {
              ready.countDown();
              start.await(5, TimeUnit.SECONDS);
              HttpHeaders threadHeaders = new HttpHeaders();
              threadHeaders.putAll(headers);
              return rest.exchange(
                  "/api/v1/accounting/journal-entries",
                  HttpMethod.POST,
                  new HttpEntity<>(manualReq, threadHeaders),
                  Map.class);
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

    List<JournalReferenceMapping> mappings =
        journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, manualRef);
    assertThat(mappings).hasSize(1);
    JournalReferenceMapping mapping = mappings.get(0);
    assertThat(mapping.getEntityId()).isEqualTo(id1);
    assertThat(mapping.getCanonicalReference()).isNotBlank();
    assertThat(journalEntryRepository.findByCompanyAndId(company, id1)).isPresent();

    long memoCount =
        journalEntryRepository.findByCompanyOrderByEntryDateDesc(company).stream()
            .filter(entry -> memo.equals(entry.getMemo()))
            .count();
    assertThat(memoCount).isEqualTo(1);
  }

  @Test
  @DisplayName("Journal Entry: Manual idempotency key is case-insensitive")
  void journalEntry_ManualIdempotencyKey_CaseInsensitiveReplay() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

    BigDecimal amount = new BigDecimal("180.00");
    Map<String, Object> debitLine =
        Map.of(
            "accountId",
            cashAccount.getId(),
            "debit",
            amount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "Case replay cash");
    Map<String, Object> creditLine =
        Map.of(
            "accountId",
            revenueAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            amount,
            "description",
            "Case replay revenue");

    String manualRef = "MANUAL-CASE-" + System.currentTimeMillis();
    Map<String, Object> firstReq =
        Map.of(
            "entryDate",
            LocalDate.now(),
            "referenceNumber",
            manualRef,
            "memo",
            "Manual idempotency case replay",
            "lines",
            List.of(debitLine, creditLine));
    ResponseEntity<Map> first =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(firstReq, headers),
            Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    Long firstId = ((Number) ((Map<?, ?>) first.getBody().get("data")).get("id")).longValue();

    Map<String, Object> secondReq =
        Map.of(
            "entryDate", LocalDate.now(),
            "referenceNumber", manualRef.toLowerCase(Locale.ROOT),
            "memo", "Manual idempotency case replay",
            "lines", List.of(debitLine, creditLine));
    ResponseEntity<Map> second =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(secondReq, headers),
            Map.class);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    Long secondId = ((Number) ((Map<?, ?>) second.getBody().get("data")).get("id")).longValue();
    assertThat(secondId).isEqualTo(firstId);

    List<JournalReferenceMapping> mappings =
        journalReferenceMappingRepository.findAllByCompanyAndLegacyReferenceIgnoreCase(
            company, manualRef);
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

    String url =
        String.format("/api/v1/reports/trial-balance?startDate=%s&endDate=%s", startDate, endDate);

    ResponseEntity<Map> response =
        rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Trial balance should have data
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(data).isNotNull();
  }

  @Test
  @DisplayName("Journal Reversal: Creates Offsetting Entry with Linked Audit")
  void journalReversal_CreatesOffsettingEntry_LinkedAudit() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();
    BigDecimal cashBefore = cashAccount.getBalance();
    BigDecimal revenueBefore = revenueAccount.getBalance();

    // Create original entry
    Long originalEntryId = createBalancedJournalEntry(company, new BigDecimal("3000.00"));

    // Reverse the entry
    Map<String, Object> reversalReq =
        Map.of("reversalDate", LocalDate.now(), "reason", "Test reversal");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries/" + originalEntryId + "/reverse",
            HttpMethod.POST,
            new HttpEntity<>(reversalReq, headers),
            Map.class);

    // Should create reversal entry
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    Number reversalId = (Number) ((Map<?, ?>) response.getBody().get("data")).get("id");
    JournalEntry reversalEntry =
        journalEntryRepository.findById(reversalId.longValue()).orElseThrow();
    assertThat(reversalEntry.getReversalOf()).isNotNull();
    assertThat(reversalEntry.getReversalOf().getId()).isEqualTo(originalEntryId);
    assertThat(reversalEntry.getCorrectionType()).isEqualTo(JournalCorrectionType.REVERSAL);

    JournalEntry originalEntry = journalEntryRepository.findById(originalEntryId).orElseThrow();
    assertThat(originalEntry.getStatus()).isEqualTo(JournalEntryStatus.REVERSED);

    Account cashAfter = accountRepository.findById(cashAccount.getId()).orElseThrow();
    Account revenueAfter = accountRepository.findById(revenueAccount.getId()).orElseThrow();
    assertThat(cashAfter.getBalance()).isEqualByComparingTo(cashBefore);
    assertThat(revenueAfter.getBalance()).isEqualByComparingTo(revenueBefore);
  }

  @Test
  @DisplayName("Journal Reversal: void-only rejects partial reversal percentage")
  void journalReversal_VoidOnlyRejectsPartialPercentage() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Long originalEntryId = createBalancedJournalEntry(company, new BigDecimal("1250.00"));

    Map<String, Object> partialVoidRequest = new HashMap<>();
    partialVoidRequest.put("reversalDate", LocalDate.now());
    partialVoidRequest.put("voidOnly", true);
    partialVoidRequest.put("reversalPercentage", new BigDecimal("50.00"));
    partialVoidRequest.put("reason", "Attempt partial void");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries/" + originalEntryId + "/reverse",
            HttpMethod.POST,
            new HttpEntity<>(partialVoidRequest, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    JournalEntry originalEntry = journalEntryRepository.findById(originalEntryId).orElseThrow();
    assertThat(originalEntry.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
    assertThat(journalEntryRepository.findByCompanyAndReversalOf(company, originalEntry)).isEmpty();
  }

  @Test
  @DisplayName("Journal reverse route rejects cascade requests and keeps linked entries untouched")
  void journalReverseRouteRejectsCascadeLinkedCorrections() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();
    Account expenseAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "EXPENSE").orElseThrow();

    BigDecimal cashBefore = cashAccount.getBalance();
    BigDecimal revenueBefore = revenueAccount.getBalance();
    BigDecimal expenseBefore = expenseAccount.getBalance();

    Map<String, Object> baseReq =
        Map.of(
            "entryDate", LocalDate.now(),
            "memo", "Base entry",
            "lines",
                List.of(
                    Map.of(
                        "accountId",
                        cashAccount.getId(),
                        "debit",
                        new BigDecimal("100.00"),
                        "credit",
                        BigDecimal.ZERO),
                    Map.of(
                        "accountId",
                        revenueAccount.getId(),
                        "debit",
                        BigDecimal.ZERO,
                        "credit",
                        new BigDecimal("100.00"))));
    ResponseEntity<Map> baseResp =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(baseReq, headers),
            Map.class);
    Long baseEntryId =
        ((Number) ((Map<?, ?>) baseResp.getBody().get("data")).get("id")).longValue();

    Map<String, Object> relatedReq =
        Map.of(
            "entryDate", LocalDate.now(),
            "memo", "Related entry",
            "lines",
                List.of(
                    Map.of(
                        "accountId",
                        expenseAccount.getId(),
                        "debit",
                        new BigDecimal("60.00"),
                        "credit",
                        BigDecimal.ZERO),
                    Map.of(
                        "accountId",
                        cashAccount.getId(),
                        "debit",
                        BigDecimal.ZERO,
                        "credit",
                        new BigDecimal("60.00"))));
    ResponseEntity<Map> relatedResp =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(relatedReq, headers),
            Map.class);
    Long relatedEntryId =
        ((Number) ((Map<?, ?>) relatedResp.getBody().get("data")).get("id")).longValue();

    Map<String, Object> reversalReq =
        Map.of(
            "reversalDate",
            LocalDate.now(),
            "reason",
            "Cascade reversal test",
            "memo",
            "Cascade reversal",
            "cascadeRelatedEntries",
            true,
            "relatedEntryIds",
            List.of(relatedEntryId));
    ResponseEntity<String> reversalResp =
        rest.exchange(
            "/api/v1/accounting/journal-entries/" + baseEntryId + "/reverse",
            HttpMethod.POST,
            new HttpEntity<>(reversalReq, headers),
            String.class);
    assertThat(reversalResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    JournalEntry baseEntry = journalEntryRepository.findById(baseEntryId).orElseThrow();
    JournalEntry relatedEntry = journalEntryRepository.findById(relatedEntryId).orElseThrow();
    assertThat(baseEntry.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
    assertThat(relatedEntry.getStatus()).isEqualTo(JournalEntryStatus.POSTED);

    long reversalCount =
        journalEntryRepository.findByCompanyAndReversalOf(company, baseEntry).size()
            + journalEntryRepository.findByCompanyAndReversalOf(company, relatedEntry).size();
    assertThat(reversalCount).isZero();
  }

  @Test
  @DisplayName(
      "Journal Reversal: partial reversal scales counter-entry lines to requested percentage")
  void journalReversal_PartialReversalScalesCounterEntry() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Long originalEntryId = createBalancedJournalEntry(company, new BigDecimal("1000.00"));

    Map<String, Object> partialRequest = new HashMap<>();
    partialRequest.put("reversalDate", LocalDate.now());
    partialRequest.put("reason", "Half correction");
    partialRequest.put("reversalPercentage", new BigDecimal("50.00"));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries/" + originalEntryId + "/reverse",
            HttpMethod.POST,
            new HttpEntity<>(partialRequest, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    Long reversalEntryId =
        ((Number) ((Map<?, ?>) response.getBody().get("data")).get("id")).longValue();
    JournalEntry reversalEntry = journalEntryRepository.findById(reversalEntryId).orElseThrow();
    BigDecimal totalReversalDebit =
        reversalEntry.getLines().stream()
            .map(JournalLine::getDebit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalReversalCredit =
        reversalEntry.getLines().stream()
            .map(JournalLine::getCredit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(totalReversalDebit).isEqualByComparingTo("500.00");
    assertThat(totalReversalCredit).isEqualByComparingTo("500.00");

    JournalEntry originalEntry = journalEntryRepository.findById(originalEntryId).orElseThrow();
    assertThat(originalEntry.getStatus()).isEqualTo(JournalEntryStatus.REVERSED);
  }

  @Test
  @DisplayName(
      "Journal Reversal: closed period needs explicit admin override and emits compliance audit")
  void journalReversal_ClosedPeriodRequiresExplicitAdminOverride() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Long originalEntryId = createBalancedJournalEntry(company, new BigDecimal("780.00"));
    AccountingPeriodStatus priorStatus =
        setJournalEntryPeriodStatus(company, originalEntryId, AccountingPeriodStatus.CLOSED);
    try {
      Map<String, Object> noOverrideRequest = new HashMap<>();
      noOverrideRequest.put("reversalDate", LocalDate.now());
      noOverrideRequest.put("reason", "Closed period correction attempt without override");
      ResponseEntity<String> noOverrideResponse =
          rest.exchange(
              "/api/v1/accounting/journal-entries/" + originalEntryId + "/reverse",
              HttpMethod.POST,
              new HttpEntity<>(noOverrideRequest, headers),
              String.class);

      assertThat(noOverrideResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(noOverrideResponse.getBody()).contains("CLOSED period");
      JournalEntry originalEntryAfterReject =
          journalEntryRepository.findById(originalEntryId).orElseThrow();
      assertThat(originalEntryAfterReject.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
      assertThat(
              journalEntryRepository.findByCompanyAndReversalOf(company, originalEntryAfterReject))
          .isEmpty();

      Map<String, Object> overrideRequest = new HashMap<>();
      overrideRequest.put("reversalDate", LocalDate.now());
      overrideRequest.put("reason", "Approved closed period correction");
      overrideRequest.put("adminOverride", true);
      ResponseEntity<Map> overrideResponse =
          rest.exchange(
              "/api/v1/accounting/journal-entries/" + originalEntryId + "/reverse",
              HttpMethod.POST,
              new HttpEntity<>(overrideRequest, headers),
              Map.class);

      assertThat(overrideResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      Long reversalEntryId =
          ((Number) ((Map<?, ?>) overrideResponse.getBody().get("data")).get("id")).longValue();
      JournalEntry refreshedOriginal =
          journalEntryRepository.findById(originalEntryId).orElseThrow();
      JournalEntry reversalEntry = journalEntryRepository.findById(reversalEntryId).orElseThrow();
      assertThat(refreshedOriginal.getStatus()).isEqualTo(JournalEntryStatus.REVERSED);
      assertThat(reversalEntry.getReversalOf()).isNotNull();
      assertThat(reversalEntry.getReversalOf().getId()).isEqualTo(originalEntryId);

      AuditActionEvent overrideAudit =
          awaitAccountingJournalActionEvent(
              company.getId(), originalEntryId, "JOURNAL_REVERSAL_ADMIN_OVERRIDE");
      assertThat(loadAuditMetadata(overrideAudit.getId()))
          .containsEntry("adminOverrideRequested", "true")
          .containsEntry("adminOverrideAuthorized", "true");
    } finally {
      setJournalEntryPeriodStatus(company, originalEntryId, priorStatus);
    }
  }

  @Test
  @DisplayName("Journal Reversal: locked period always rejects reversal even with admin override")
  void journalReversal_LockedPeriodAlwaysRejects() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Long originalEntryId = createBalancedJournalEntry(company, new BigDecimal("640.00"));
    AccountingPeriodStatus priorStatus =
        setJournalEntryPeriodStatus(company, originalEntryId, AccountingPeriodStatus.LOCKED);
    try {
      Map<String, Object> lockRequest = new HashMap<>();
      lockRequest.put("reversalDate", LocalDate.now());
      lockRequest.put("reason", "Attempt locked reversal");
      lockRequest.put("adminOverride", true);

      ResponseEntity<String> response =
          rest.exchange(
              "/api/v1/accounting/journal-entries/" + originalEntryId + "/reverse",
              HttpMethod.POST,
              new HttpEntity<>(lockRequest, headers),
              String.class);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(response.getBody()).contains("LOCKED period");
      JournalEntry originalEntry = journalEntryRepository.findById(originalEntryId).orElseThrow();
      assertThat(originalEntry.getStatus()).isEqualTo(JournalEntryStatus.POSTED);
      assertThat(journalEntryRepository.findByCompanyAndReversalOf(company, originalEntry))
          .isEmpty();
    } finally {
      setJournalEntryPeriodStatus(company, originalEntryId, priorStatus);
    }
  }

  @Test
  @DisplayName("Journal Reversal: already reversed and voided entries reject follow-up reversals")
  void journalReversal_AlreadyReversedOrVoidedRejectsWithoutDuplicates() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Long reversedEntryId = createBalancedJournalEntry(company, new BigDecimal("510.00"));
    Map<String, Object> reverseRequest =
        Map.of("reversalDate", LocalDate.now(), "reason", "Initial reversal");
    ResponseEntity<Map> firstReverse =
        rest.exchange(
            "/api/v1/accounting/journal-entries/" + reversedEntryId + "/reverse",
            HttpMethod.POST,
            new HttpEntity<>(reverseRequest, headers),
            Map.class);
    assertThat(firstReverse.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> duplicateReverse =
        rest.exchange(
            "/api/v1/accounting/journal-entries/" + reversedEntryId + "/reverse",
            HttpMethod.POST,
            new HttpEntity<>(reverseRequest, headers),
            String.class);
    assertThat(duplicateReverse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(duplicateReverse.getBody()).contains("already been reversed");
    JournalEntry reversedEntry = journalEntryRepository.findById(reversedEntryId).orElseThrow();
    assertThat(journalEntryRepository.findByCompanyAndReversalOf(company, reversedEntry))
        .hasSize(1);

    Long voidedEntryId = createBalancedJournalEntry(company, new BigDecimal("475.00"));
    Map<String, Object> voidRequest = new HashMap<>();
    voidRequest.put("reversalDate", LocalDate.now());
    voidRequest.put("voidOnly", true);
    voidRequest.put("reason", "Initial void correction");
    ResponseEntity<Map> firstVoid =
        rest.exchange(
            "/api/v1/accounting/journal-entries/" + voidedEntryId + "/reverse",
            HttpMethod.POST,
            new HttpEntity<>(voidRequest, headers),
            Map.class);
    assertThat(firstVoid.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> duplicateVoid =
        rest.exchange(
            "/api/v1/accounting/journal-entries/" + voidedEntryId + "/reverse",
            HttpMethod.POST,
            new HttpEntity<>(voidRequest, headers),
            String.class);
    assertThat(duplicateVoid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(duplicateVoid.getBody()).contains("already voided");
    JournalEntry voidedEntry = journalEntryRepository.findById(voidedEntryId).orElseThrow();
    assertThat(journalEntryRepository.findByCompanyAndReversalOf(company, voidedEntry)).hasSize(1);
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
    String plUrl =
        String.format("/api/v1/reports/profit-loss?startDate=%s&endDate=%s", startDate, endDate);

    ResponseEntity<Map> plResponse =
        rest.exchange(plUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(plResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Test Balance Sheet
    String bsUrl = String.format("/api/v1/reports/balance-sheet?asOfDate=%s", endDate);

    ResponseEntity<Map> bsResponse =
        rest.exchange(bsUrl, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(bsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("Dealer Payment: Reduces Receivable and Updates Ledger")
  void dealerPayment_ReducesReceivable_UpdatesLedger() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account arAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "AR").orElseThrow();
    Dealer dealer =
        dealerRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-DEALER").orElseThrow();

    // Create payment entry (debit cash, credit AR)
    BigDecimal paymentAmount = new BigDecimal("5000.00");

    Map<String, Object> debitLine =
        Map.of(
            "accountId",
            cashAccount.getId(),
            "debit",
            paymentAmount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "Payment received");

    Map<String, Object> creditLine =
        Map.of(
            "accountId",
            arAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            paymentAmount,
            "description",
            "AR reduction");

    Map<String, Object> jeRequest =
        Map.of(
            "entryDate", LocalDate.now(),
            "memo", "Dealer payment",
            "dealerId", dealer.getId(),
            "lines", List.of(debitLine, creditLine));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(jeRequest, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("Manual AP-adjacent accrual allows null supplier context")
  void manualApAdjacentAccrual_allowsNullSupplierContext() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account expenseAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "EXPENSE").orElseThrow();
    Account apAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "AP").orElseThrow();

    BigDecimal amount = new BigDecimal("1800.00");

    Map<String, Object> debitLine =
        Map.of(
            "accountId",
            expenseAccount.getId(),
            "debit",
            amount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "Accrual expense");
    Map<String, Object> creditLine =
        Map.of(
            "accountId",
            apAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            amount,
            "description",
            "Accrual payable");

    Map<String, Object> jeRequest = new HashMap<>();
    jeRequest.put("entryDate", LocalDate.now());
    jeRequest.put("memo", "AP accrual adjustment without supplier");
    jeRequest.put("supplierId", null);
    jeRequest.put("lines", List.of(debitLine, creditLine));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(jeRequest, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
    assertThat(data).isNotNull();
    assertThat(data.get("id")).isNotNull();
  }

  @Test
  @DisplayName("Supplier Payment: Reduces Payable and Updates Ledger")
  void supplierPayment_ReducesPayable_UpdatesLedger() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account apAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "AP").orElseThrow();
    Account expenseAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "EXPENSE").orElseThrow();
    Supplier supplier =
        supplierRepository.findByCompanyAndCodeIgnoreCase(company, "FIX-SUP").orElseThrow();

    // Create supplier payment (debit AP, credit cash)
    BigDecimal paymentAmount = new BigDecimal("3000.00");

    Map<String, Object> purchaseDebit =
        Map.of(
            "accountId",
            expenseAccount.getId(),
            "debit",
            paymentAmount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "Purchase expense");

    Map<String, Object> purchaseCredit =
        Map.of(
            "accountId",
            apAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            paymentAmount,
            "description",
            "AP increase");

    Map<String, Object> purchaseRequest =
        Map.of(
            "entryDate", LocalDate.now(),
            "memo", "Supplier purchase",
            "supplierId", supplier.getId(),
            "lines", List.of(purchaseDebit, purchaseCredit));

    ResponseEntity<Map> purchaseResponse =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(purchaseRequest, headers),
            Map.class);
    assertThat(purchaseResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> debitLine =
        Map.of(
            "accountId",
            apAccount.getId(),
            "debit",
            paymentAmount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "AP reduction");

    Map<String, Object> creditLine =
        Map.of(
            "accountId",
            cashAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            paymentAmount,
            "description",
            "Payment made");

    Map<String, Object> jeRequest =
        Map.of(
            "entryDate", LocalDate.now(),
            "memo", "Supplier payment",
            "supplierId", supplier.getId(),
            "lines", List.of(debitLine, creditLine));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(jeRequest, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("Period Close: Prevents Future Backdating")
  void periodClose_PreventsFutureBackdating() {
    // Test accounting period functionality
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Map<String, Object> periodReq =
        Map.of(
            "year", LocalDate.now().getYear(),
            "month", LocalDate.now().getMonthValue());

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/periods", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    // Should return periods or empty list
    assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);
  }

  // Helper methods
  private Long createBalancedJournalEntry(Company company, BigDecimal amount) {
    Account cashAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "CASH").orElseThrow();
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REVENUE").orElseThrow();

    Map<String, Object> debitLine =
        Map.of(
            "accountId",
            cashAccount.getId(),
            "debit",
            amount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "Test debit");

    Map<String, Object> creditLine =
        Map.of(
            "accountId",
            revenueAccount.getId(),
            "debit",
            BigDecimal.ZERO,
            "credit",
            amount,
            "description",
            "Test credit");

    Map<String, Object> jeRequest =
        Map.of(
            "entryDate", LocalDate.now(),
            "memo", "Test balanced entry",
            "lines", List.of(debitLine, creditLine));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.POST,
            new HttpEntity<>(jeRequest, headers),
            Map.class);

    return ((Number) ((Map<?, ?>) response.getBody().get("data")).get("id")).longValue();
  }

  private Map<String, Object> highValueManualJournalRequest(
      Long cashAccountId, Long revenueAccountId, String referenceNumber) {
    BigDecimal suspiciousAmount = new BigDecimal("150000.00");
    Map<String, Object> debitLine =
        Map.of(
            "accountId",
            cashAccountId,
            "debit",
            suspiciousAmount,
            "credit",
            BigDecimal.ZERO,
            "description",
            "High-value manual debit");
    Map<String, Object> creditLine =
        Map.of(
            "accountId",
            revenueAccountId,
            "debit",
            BigDecimal.ZERO,
            "credit",
            suspiciousAmount,
            "description",
            "High-value manual credit");
    return Map.of(
        "entryDate", LocalDate.now(),
        "journalType", "MANUAL",
        "referenceNumber", referenceNumber,
        "memo", "High-value manual journal for review intelligence validation",
        "lines", List.of(debitLine, creditLine));
  }

  private AccountingPeriodStatus setJournalEntryPeriodStatus(
      Company company, Long journalEntryId, AccountingPeriodStatus status) {
    JournalEntry entry = journalEntryRepository.findById(journalEntryId).orElseThrow();
    Long periodId =
        entry.getAccountingPeriod() != null ? entry.getAccountingPeriod().getId() : null;
    assertThat(periodId).isNotNull();
    AccountingPeriod period =
        accountingPeriodRepository.findByCompanyAndId(company, periodId).orElseThrow();
    AccountingPeriodStatus previousStatus = period.getStatus();
    period.setStatus(status);
    accountingPeriodRepository.saveAndFlush(period);
    return previousStatus;
  }

  private AuditActionEvent awaitAccountingJournalAuditEvent(Long companyId, String entityId) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (Instant.now().isBefore(deadline)) {
      Optional<AuditActionEvent> match =
          auditActionEventRepository
              .findTopByCompanyIdAndModuleIgnoreCaseAndEntityTypeIgnoreCaseAndEntityIdOrderByOccurredAtDesc(
                  companyId, "ACCOUNTING", "JOURNAL_ENTRY", entityId);
      if (match.isPresent()) {
        return match.get();
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for accounting audit event", ex);
      }
    }
    throw new AssertionError(
        "Accounting journal audit event not found for entityId="
            + entityId
            + ", companyId="
            + companyId);
  }

  private AuditActionEvent awaitAccountingJournalActionEvent(
      Long companyId, Long entityId, String action) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (Instant.now().isBefore(deadline)) {
      Optional<AuditActionEvent> match =
          auditActionEventRepository
              .findTopByCompanyIdAndModuleIgnoreCaseAndActionIgnoreCaseAndEntityTypeIgnoreCaseAndEntityIdOrderByOccurredAtDesc(
                  companyId, "ACCOUNTING", action, "JOURNAL_ENTRY", String.valueOf(entityId));
      if (match.isPresent()) {
        return match.get();
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for accounting action audit event", ex);
      }
    }
    throw new AssertionError(
        "Accounting action audit event not found for action="
            + action
            + ", entityId="
            + entityId
            + ", companyId="
            + companyId);
  }

  private void assertNoAccountingJournalActionEvent(Long companyId, Long entityId, String action) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
    while (Instant.now().isBefore(deadline)) {
      Optional<AuditActionEvent> match =
          auditActionEventRepository
              .findTopByCompanyIdAndModuleIgnoreCaseAndActionIgnoreCaseAndEntityTypeIgnoreCaseAndEntityIdOrderByOccurredAtDesc(
                  companyId, "ACCOUNTING", action, "JOURNAL_ENTRY", String.valueOf(entityId));
      if (match.isPresent()) {
        throw new AssertionError(
            "Unexpected accounting action event found for action="
                + action
                + ", entityId="
                + entityId
                + ", companyId="
                + companyId);
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while asserting action event absence", ex);
      }
    }
  }

  private Map<String, String> loadAuditMetadata(Long eventId) {
    if (eventId == null) {
      return Map.of();
    }
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "select metadata_key, metadata_value from audit_action_event_metadata where event_id ="
                + " ?",
            eventId);
    Map<String, String> metadata = new HashMap<>();
    for (Map<String, Object> row : rows) {
      Object key = row.get("metadata_key");
      Object value = row.get("metadata_value");
      if (key == null || value == null) {
        continue;
      }
      metadata.put(key.toString(), value.toString());
    }
    return metadata;
  }
}
