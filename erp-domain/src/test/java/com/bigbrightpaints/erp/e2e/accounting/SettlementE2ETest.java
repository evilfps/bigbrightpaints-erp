package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.accounting.domain.*;
import com.bigbrightpaints.erp.modules.accounting.domain.PartnerSettlementAllocationRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
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
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the settlement APIs (dealer/supplier) to ensure
 * allocations, discounts, write-offs, FX and resulting journals/allocations persist correctly.
 */
@DisplayName("E2E: Partner Settlements (Dealer focus)")
public class SettlementE2ETest extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "SETTLE-E2E";
    private static final String ADMIN_EMAIL = "settlements@bbp.com";
    private static final String ADMIN_PASSWORD = "settle123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private PartnerSettlementAllocationRepository allocationRepository;
    @Autowired private JournalLineRepository journalLineRepository;

    private HttpHeaders headers;
    private Company company;
    private Account cash;
    private Account ar;
    private Account revenue;
    private Account discount;
    private Account writeOff;
    private Account fxGain;
    private Account fxLoss;
    private Dealer dealer;
    private Invoice invoice;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Settlement Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        cash = ensureAccount("CASH", "Cash", AccountType.ASSET);
        ar = ensureAccount("AR", "Accounts Receivable", AccountType.ASSET);
        revenue = ensureAccount("REV", "Revenue", AccountType.REVENUE);
        discount = ensureAccount("DISC", "Settlement Discounts", AccountType.EXPENSE);
        writeOff = ensureAccount("WRITEOFF", "Bad Debt Write-Off", AccountType.EXPENSE);
        fxGain = ensureAccount("FXGAIN", "FX Gain", AccountType.REVENUE);
        fxLoss = ensureAccount("FXLOSS", "FX Loss", AccountType.EXPENSE);
        dealer = ensureDealer();
        invoice = ensureInvoice();
        headers = authHeaders();
        seedInvoicePosting(invoice);
    }

    private HttpHeaders authHeaders() {
        Map<String, Object> req = Map.of(
                "email", ADMIN_EMAIL,
                "password", ADMIN_PASSWORD,
                "companyCode", COMPANY_CODE
        );
        String token = (String) rest.postForEntity("/api/v1/auth/login", req, Map.class)
                .getBody().get("accessToken");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Company-Id", COMPANY_CODE);
        return h;
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

    private Dealer ensureDealer() {
        return dealerRepository.findByCompanyAndCodeIgnoreCase(company, "D-CODEX")
                .orElseGet(() -> {
                    Dealer d = new Dealer();
                    d.setCompany(company);
                    d.setCode("D-CODEX");
                    d.setName("Codex Dealer");
                    d.setCreditLimit(new BigDecimal("200000"));
                    // Seed outstanding balance equal to test invoice total so settlement does not go negative
                    d.setOutstandingBalance(new BigDecimal("800.00"));
                    d.setReceivableAccount(ar);
                    return dealerRepository.save(d);
                });
    }

    private Invoice ensureInvoice() {
        Invoice inv = new Invoice();
        inv.setCompany(company);
        inv.setDealer(dealer);
        inv.setInvoiceNumber("INV-CODEX-" + System.currentTimeMillis());
        inv.setStatus("POSTED");
        inv.setSubtotal(new BigDecimal("800.00"));
        inv.setTaxTotal(new BigDecimal("0.00"));
        inv.setTotalAmount(new BigDecimal("800.00"));
        inv.setIssueDate(LocalDate.now());
        inv.setDueDate(LocalDate.now().plusDays(30));
        inv.setCurrency("INR");
        inv.setNotes("Test invoice for settlement");
        return invoiceRepository.save(inv);
    }

    /**
     * Seed a posted journal for the invoice to establish AR balance (Dr AR, Cr Revenue).
     */
    private void seedInvoicePosting(Invoice target) {
        BigDecimal amount = target.getTotalAmount() != null ? target.getTotalAmount() : BigDecimal.ZERO;
        Map<String, Object> arLine = Map.of(
                "accountId", ar.getId(),
                "description", "Invoice AR",
                "debit", amount,
                "credit", BigDecimal.ZERO
        );
        Map<String, Object> revLine = Map.of(
                "accountId", revenue.getId(),
                "description", "Revenue",
                "debit", BigDecimal.ZERO,
                "credit", amount
        );

        Map<String, Object> payload = Map.of(
                "entryDate", LocalDate.now(),
                "referenceNumber", "INV-JE-" + target.getInvoiceNumber(),
                "memo", "Seed invoice posting",
                "dealerId", dealer.getId(),
                "lines", List.of(arLine, revLine)
        );

        rest.exchange(
                "/api/v1/accounting/journal-entries",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
    }

    @Test
    @DisplayName("Dealer settlement with discount/write-off/FX loss posts balanced journal and stores allocations")
    void dealerSettlement_WithAdjustments_BalancesAndPersists() {
        Map<String, Object> allocation = Map.of(
                "invoiceId", invoice.getId(),
                "appliedAmount", new BigDecimal("800.00"),
                "discountAmount", new BigDecimal("50.00"),
                "writeOffAmount", new BigDecimal("20.00"),
                "fxAdjustment", new BigDecimal("-10.00"), // loss
                "memo", "partial settle"
        );

        Map<String, Object> payload = Map.of(
                "dealerId", dealer.getId(),
                "cashAccountId", cash.getId(),
                "discountAccountId", discount.getId(),
                "writeOffAccountId", writeOff.getId(),
                "fxLossAccountId", fxLoss.getId(),
                "settlementDate", LocalDate.now(),
                "referenceNumber", "SETTLE-" + UUID.randomUUID(),
                "memo", "Receipt with adjustments",
                "allocations", List.of(allocation)
        );

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/settlements/dealers",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        Number journalIdNum = (Number) ((Map<?, ?>) data.get("journalEntry")).get("id");
        assertThat(journalIdNum).isNotNull();
        Long journalId = journalIdNum.longValue();

        // Journal must balance: debits = 720+50+20+10 = 800, credits = 800
        BigDecimal debits = journalLineRepository.findAll().stream()
                .filter(l -> l.getJournalEntry().getId().equals(journalId))
                .map(JournalLine::getDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = journalLineRepository.findAll().stream()
                .filter(l -> l.getJournalEntry().getId().equals(journalId))
                .map(JournalLine::getCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);

        // Allocation row persisted and linked to invoice
        List<PartnerSettlementAllocation> rows = allocationRepository.findAll().stream()
                .filter(r -> r.getCompany() != null && r.getCompany().getId().equals(company.getId()))
                .collect(Collectors.toList());
        assertThat(rows).anyMatch(r ->
                r.getJournalEntry().getId().equals(journalId)
                        && r.getInvoice() != null
                        && r.getInvoice().getId().equals(invoice.getId())
                        && r.getAllocationAmount().compareTo(new BigDecimal("800.00")) == 0
                        && r.getDiscountAmount().compareTo(new BigDecimal("50.00")) == 0
                        && r.getWriteOffAmount().compareTo(new BigDecimal("20.00")) == 0
                        && r.getFxDifferenceAmount().compareTo(new BigDecimal("-10.00")) == 0);
    }

    @Test
    @DisplayName("Dealer settlement rejects discount without discount account")
    void dealerSettlement_MissingDiscountAccount_ValidationFails() {
        Map<String, Object> allocation = Map.of(
                "invoiceId", invoice.getId(),
                "appliedAmount", new BigDecimal("100.00"),
                "discountAmount", new BigDecimal("5.00")
        );

        Map<String, Object> payload = Map.of(
                "dealerId", dealer.getId(),
                "cashAccountId", cash.getId(),
                // discount account intentionally omitted
                "writeOffAccountId", writeOff.getId(),
                "settlementDate", LocalDate.now(),
                "allocations", List.of(allocation)
        );

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/settlements/dealers",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Dealer settlement idempotency returns existing journal")
    void dealerSettlement_Idempotent_ReusesExisting() {
        String idemKey = "SETTLE-IDEM-1";
        Map<String, Object> allocation = Map.of(
                "invoiceId", invoice.getId(),
                "appliedAmount", new BigDecimal("200.00")
        );
        Map<String, Object> payload = Map.of(
                "dealerId", dealer.getId(),
                "cashAccountId", cash.getId(),
                "allocations", List.of(allocation),
                "idempotencyKey", idemKey
        );

        ResponseEntity<Map> first = rest.exchange(
                "/api/v1/accounting/settlements/dealers",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> second = rest.exchange(
                "/api/v1/accounting/settlements/dealers",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<PartnerSettlementAllocation> rows = allocationRepository.findByCompanyAndIdempotencyKey(company, idemKey);
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("Dealer settlement idempotency allows multiple allocations per key")
    void dealerSettlement_Idempotent_MultiAllocation() {
        Invoice second = ensureInvoice();
        dealer.setOutstandingBalance(new BigDecimal("1600.00"));
        dealerRepository.save(dealer);
        seedInvoicePosting(second);

        String idemKey = "SETTLE-IDEM-MULTI-" + System.nanoTime();
        Map<String, Object> allocationA = Map.of(
                "invoiceId", invoice.getId(),
                "appliedAmount", new BigDecimal("800.00")
        );
        Map<String, Object> allocationB = Map.of(
                "invoiceId", second.getId(),
                "appliedAmount", new BigDecimal("800.00")
        );
        Map<String, Object> payload = Map.of(
                "dealerId", dealer.getId(),
                "cashAccountId", cash.getId(),
                "allocations", List.of(allocationA, allocationB),
                "idempotencyKey", idemKey
        );

        ResponseEntity<Map> first = rest.exchange(
                "/api/v1/accounting/settlements/dealers",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<PartnerSettlementAllocation> rows = allocationRepository.findByCompanyAndIdempotencyKey(company, idemKey);
        assertThat(rows).hasSize(2);

        ResponseEntity<Map> secondCall = rest.exchange(
                "/api/v1/accounting/settlements/dealers",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(secondCall.getStatusCode()).isEqualTo(HttpStatus.OK);

        rows = allocationRepository.findByCompanyAndIdempotencyKey(company, idemKey);
        assertThat(rows).hasSize(2);
    }
}
