package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: Partner Settlements (Dealer focus)")
class SettlementE2ETest extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "SETTLE-E2E";
    private static final String ADMIN_EMAIL = "settlements@bbp.com";
    private static final String ADMIN_PASSWORD = "settle123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private InvoiceRepository invoiceRepository;

    private HttpHeaders headers;
    private Company company;
    private Account cash;
    private Account discount;
    private Dealer dealer;
    private Invoice invoice;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Settlement Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        cash = ensureAccount("CASH", "Cash", AccountType.ASSET);
        discount = ensureAccount("DISC", "Settlement Discounts", AccountType.EXPENSE);
        dealer = ensureDealer();
        headers = authHeaders();
        invoice = ensureInvoice();
    }

    @Test
    @DisplayName("Dealer settlement rejects discount without discount account")
    void dealerSettlement_MissingDiscountAccount_ValidationFails() {
        Map<String, Object> allocation = Map.of(
                "invoiceId", invoice.getId(),
                "appliedAmount", new BigDecimal("100.00"),
                "discountAmount", new BigDecimal("5.00")
        );
        Map<String, Object> payload = new HashMap<>();
        payload.put("dealerId", dealer.getId());
        payload.put("cashAccountId", cash.getId());
        payload.put("amount", new BigDecimal("95.00"));
        payload.put("settlementDate", LocalDate.now());
        payload.put("referenceNumber", "SETTLE-MISS-" + UUID.randomUUID());
        payload.put("allocations", List.of(allocation));

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/settlements/dealers",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Dealer settlement rejects over-application")
    void dealerSettlement_OverApplicationRejected() {
        String idemKey = "SETTLE-OVER-" + UUID.randomUUID();
        Map<String, Object> payload = new HashMap<>();
        payload.put("dealerId", dealer.getId());
        payload.put("cashAccountId", cash.getId());
        payload.put("amount", new BigDecimal("900.00"));
        payload.put("settlementDate", LocalDate.now());
        payload.put("referenceNumber", idemKey);
        payload.put("idempotencyKey", idemKey);
        payload.put("allocations", List.of(Map.of(
                "invoiceId", invoice.getId(),
                "appliedAmount", new BigDecimal("900.00")
        )));

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/settlements/dealers",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Invoice refreshed = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(new BigDecimal("800.00"));
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
        assertThat(token).isNotBlank();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Company-Code", COMPANY_CODE);
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
        Dealer persisted = dealerRepository.findByCompanyAndCodeIgnoreCase(company, "D-CODEX")
                .orElseGet(() -> {
                    Dealer created = new Dealer();
                    created.setCompany(company);
                    created.setCode("D-CODEX");
                    created.setName("Codex Dealer");
                    created.setCreditLimit(new BigDecimal("200000"));
                    return dealerRepository.save(created);
                });
        persisted.setOutstandingBalance(new BigDecimal("800.00"));
        return dealerRepository.saveAndFlush(persisted);
    }

    private Invoice ensureInvoice() {
        Invoice created = new Invoice();
        created.setCompany(company);
        created.setDealer(dealer);
        created.setInvoiceNumber("INV-CODEX-" + System.nanoTime());
        created.setStatus("POSTED");
        created.setSubtotal(new BigDecimal("800.00"));
        created.setTaxTotal(BigDecimal.ZERO);
        created.setTotalAmount(new BigDecimal("800.00"));
        created.setOutstandingAmount(new BigDecimal("800.00"));
        created.setIssueDate(LocalDate.now());
        created.setDueDate(LocalDate.now().plusDays(30));
        created.setCurrency("INR");
        created.setNotes("Test invoice for settlement");
        return invoiceRepository.saveAndFlush(created);
    }
}
