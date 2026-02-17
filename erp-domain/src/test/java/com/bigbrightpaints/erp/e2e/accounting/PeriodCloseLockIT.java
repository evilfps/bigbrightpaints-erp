package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceipt;
import com.bigbrightpaints.erp.modules.purchasing.domain.GoodsReceiptRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrder;
import com.bigbrightpaints.erp.modules.purchasing.domain.PurchaseOrderRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.test.support.TestDateUtils;
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
    private static final String ACCOUNTING_EMAIL = "period-accounting@bbp.com";
    private static final String ACCOUNTING_PASSWORD = "periodacct123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private GoodsReceiptRepository goodsReceiptRepository;

    private HttpHeaders headers;
    private HttpHeaders accountingHeaders;
    private Company company;
    private Account revenue;
    private Account expense;
    private Account cash;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Period Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
        dataSeeder.ensureUser(ACCOUNTING_EMAIL, ACCOUNTING_PASSWORD, "Period Accountant", COMPANY_CODE,
                List.of("ROLE_ACCOUNTING"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        headers = authHeaders();
        accountingHeaders = authHeaders(ACCOUNTING_EMAIL, ACCOUNTING_PASSWORD, COMPANY_CODE);
        revenue = ensureAccount("REV-TEST", "Test Revenue", AccountType.REVENUE);
        expense = ensureAccount("EXP-TEST", "Test Expense", AccountType.EXPENSE);
        cash = ensureAccount("CASH-TEST", "Cash", AccountType.ASSET);
    }

    @Test
    @DisplayName("Closing locks period, posts retained earnings transfer, blocks new postings, reopen auto-reverses and unlocks")
    void closeLockReopenFlow() {
        LocalDate today = TestDateUtils.safeDate(company);
        LocalDate testMonth = today;
        int day = testMonth.getDayOfMonth();
        LocalDate revenueDate = testMonth.withDayOfMonth(Math.max(1, day - 2));
        LocalDate expenseDate = testMonth.withDayOfMonth(Math.max(1, day - 1));
        LocalDate lockedDate = testMonth.withDayOfMonth(day);
        // Seed P&L: profit 60 (100 revenue, 40 expense) using cash movements
        // Post revenue entry first
        postJournal(revenueDate,
                List.of(
                        line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
                        line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))
                ));
        // Post expense entry separately to avoid duplicate account issues
        postJournal(expenseDate,
                List.of(
                        line(expense.getId(), new BigDecimal("40.00"), BigDecimal.ZERO),
                        line(cash.getId(), BigDecimal.ZERO, new BigDecimal("40.00"))
                ));

        Long periodId = currentPeriodId(testMonth);
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
                        "entryDate", lockedDate,
                        "memo", "Should fail",
                        "adminOverride", false,
                        "lines", List.of(
                                line(cash.getId(), new BigDecimal("10.00"), BigDecimal.ZERO),
                                line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("10.00"))
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
    @DisplayName("Close period fails when uninvoiced goods receipts exist")
    void closePeriodRejectsUninvoicedReceipts() {
        LocalDate today = TestDateUtils.safeDate(company);
        ensurePeriodOpen(today);
        GoodsReceipt receipt = createUninvoicedGoodsReceipt(today);
        try {
            Long periodId = currentPeriodId(today);
            ResponseEntity<Map> closeResp = rest.exchange(
                    "/api/v1/accounting/periods/" + periodId + "/close",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("note", "Attempt close", "force", true), headers),
                    Map.class);

            assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            String message = closeResp.getBody().get("message").toString();
            assertThat(message.toLowerCase())
                    .satisfiesAnyOf(
                            value -> assertThat(value).contains("goods receipts"),
                            value -> assertThat(value).contains("invalid state"));
            Object errorData = closeResp.getBody().get("data");
            assertThat(errorData).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) errorData).get("code"))
                    .isEqualTo(ErrorCode.BUSINESS_INVALID_STATE.getCode());
        } finally {
            cleanupReceipt(receipt);
        }
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
    @DisplayName("Reversal blocked when posting into closed period (even with admin override)")
    void reversalBlockedWhenPeriodClosed() {
        LocalDate today = TestDateUtils.safeDate(company);
        Long periodId = currentPeriodId(today);
        rest.exchange(
                "/api/v1/accounting/periods/" + periodId + "/reopen",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Ensure period open"), headers),
                Map.class);
        Long entryId = postJournal(today,
                List.of(
                        line(cash.getId(), new BigDecimal("50.00"), BigDecimal.ZERO),
                        line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("50.00"))
                ));
        ResponseEntity<Map> closeResp = rest.exchange(
                "/api/v1/accounting/periods/" + periodId + "/close",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Hard close", "force", true), headers),
                Map.class);
        assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> reversalResp = rest.exchange(
                "/api/v1/accounting/journal-entries/" + entryId + "/reverse",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "reversalDate", today,
                        "reason", "Closed-period reversal attempt",
                        "adminOverride", true
                ), headers),
                Map.class);

        assertThat(reversalResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reversalResp.getBody().get("message").toString()).containsIgnoringCase("locked/closed");
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

    @Test
    @DisplayName("Accounting role can reopen old closed period with auto-reversal")
    void reopenOldClosedPeriod_allowsAccountingRoleWithAutoReversal() {
        LocalDate today = TestDateUtils.safeDate(company);
        LocalDate oldPeriodDate = today.minusMonths(3);
        oldPeriodDate = oldPeriodDate.withDayOfMonth(Math.min(15, oldPeriodDate.lengthOfMonth()));
        LocalDate revenueDate = oldPeriodDate.withDayOfMonth(Math.max(1, oldPeriodDate.getDayOfMonth() - 2));
        LocalDate expenseDate = oldPeriodDate.withDayOfMonth(Math.max(1, oldPeriodDate.getDayOfMonth() - 1));

        postJournalWithOverride(revenueDate,
                List.of(
                        line(cash.getId(), new BigDecimal("100.00"), BigDecimal.ZERO),
                        line(revenue.getId(), BigDecimal.ZERO, new BigDecimal("100.00"))
                ),
                true,
                headers);
        postJournalWithOverride(expenseDate,
                List.of(
                        line(expense.getId(), new BigDecimal("25.00"), BigDecimal.ZERO),
                        line(cash.getId(), BigDecimal.ZERO, new BigDecimal("25.00"))
                ),
                true,
                headers);

        Long periodId = currentPeriodId(oldPeriodDate);
        ResponseEntity<Map> closeResp = rest.exchange(
                "/api/v1/accounting/periods/" + periodId + "/close",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Old period close", "force", true), headers),
                Map.class);
        assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> closed = (Map<String, Object>) closeResp.getBody().get("data");
        assertThat(closed.get("status")).isEqualTo("CLOSED");
        assertThat(closed.get("closingJournalEntryId")).isNotNull();

        ResponseEntity<Map> reopenResp = rest.exchange(
                "/api/v1/accounting/periods/" + periodId + "/reopen",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Historical correction"), accountingHeaders),
                Map.class);
        assertThat(reopenResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> reopened = (Map<String, Object>) reopenResp.getBody().get("data");
        assertThat(reopened.get("status")).isEqualTo("OPEN");
        assertThat(reopened.get("closingJournalEntryId")).isNull();
    }

    private Map<String, Object> line(Long accountId, BigDecimal debit, BigDecimal credit) {
        return Map.of(
                "accountId", accountId,
                "description", "test",
                "debit", debit,
                "credit", credit
        );
    }

    private Long postJournal(LocalDate date, List<Map<String, Object>> lines) {
        return postJournalWithOverride(date, lines, false, headers);
    }

    private Long postJournalWithOverride(LocalDate date,
                                         List<Map<String, Object>> lines,
                                         boolean adminOverride,
                                         HttpHeaders requestHeaders) {
        ResponseEntity<Map> resp = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "entryDate", date,
                        "memo", "Seed P&L",
                        "adminOverride", adminOverride,
                        "lines", lines
                ), requestHeaders),
                Map.class);
        assertThat(resp.getStatusCode()).as("Journal posting should succeed: " + resp.getBody()).isEqualTo(HttpStatus.OK);
        return ((Number) ((Map<?, ?>) resp.getBody().get("data")).get("id")).longValue();
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

    private GoodsReceipt createUninvoicedGoodsReceipt(LocalDate receiptDate) {
        Supplier supplier = new Supplier();
        supplier.setCompany(company);
        supplier.setCode("SUP-UNINV-" + System.nanoTime());
        supplier.setName("Uninvoiced Supplier");
        Supplier savedSupplier = supplierRepository.save(supplier);

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setCompany(company);
        purchaseOrder.setSupplier(savedSupplier);
        purchaseOrder.setOrderNumber("PO-UNINV-" + System.nanoTime());
        purchaseOrder.setOrderDate(receiptDate);
        PurchaseOrder savedOrder = purchaseOrderRepository.save(purchaseOrder);

        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setCompany(company);
        receipt.setSupplier(savedSupplier);
        receipt.setPurchaseOrder(savedOrder);
        receipt.setReceiptNumber("GRN-UNINV-" + System.nanoTime());
        receipt.setReceiptDate(receiptDate);
        receipt.setStatus("RECEIVED");
        return goodsReceiptRepository.save(receipt);
    }

    private void ensurePeriodOpen(LocalDate referenceDate) {
        Long periodId = currentPeriodId(referenceDate);
        rest.exchange(
                "/api/v1/accounting/periods/" + periodId + "/reopen",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Ensure open for test"), headers),
                Map.class);
    }

    private void cleanupReceipt(GoodsReceipt receipt) {
        if (receipt == null) {
            return;
        }
        goodsReceiptRepository.delete(receipt);
    }

    private HttpHeaders authHeaders() {
        return authHeaders(ADMIN_EMAIL, ADMIN_PASSWORD, COMPANY_CODE);
    }

    private HttpHeaders authHeaders(String email, String password, String companyCode) {
        Map<String, Object> req = Map.of(
                "email", email,
                "password", password,
                "companyCode", companyCode
        );
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) login.getBody().get("accessToken");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Company-Id", companyCode);
        return h;
    }
}
