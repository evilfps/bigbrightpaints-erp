package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: Accruals and Bad Debt Write-Off")
public class AccrualBadDebtIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "ACCR-E2E";
    private static final String ADMIN_EMAIL = "accrual@bbp.com";
    private static final String ADMIN_PASSWORD = "accr123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;

    private HttpHeaders headers;
    private Company company;
    private Account accrualExp;
    private Account accrualLiab;
    private Account badDebtExp;
    private Account ar;
    private Dealer dealer;
    private Invoice invoice;
    private ClientHttpRequestFactory originalRequestFactory;

    @BeforeEach
    void setup() {
        configureRestTemplateTimeouts();
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Accrual Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        accrualExp = ensureAccount("ACCR-EXP", "Accrued Expense", AccountType.EXPENSE);
        accrualLiab = ensureAccount("ACCR-LIAB", "Accrual Liability", AccountType.LIABILITY);
        badDebtExp = ensureAccount("BDE", "Bad Debt Expense", AccountType.EXPENSE);
        ar = ensureAccount("AR", "Accounts Receivable", AccountType.ASSET);
        dealer = ensureDealer();
        invoice = ensureInvoice();
        headers = authHeaders();
        seedInvoicePosting();
    }

    @AfterEach
    void resetRestTemplateFactory() {
        if (originalRequestFactory != null) {
            rest.getRestTemplate().setRequestFactory(originalRequestFactory);
        }
    }

    private void configureRestTemplateTimeouts() {
        originalRequestFactory = rest.getRestTemplate().getRequestFactory();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(30_000);
        requestFactory.setReadTimeout(120_000);
        rest.getRestTemplate().setRequestFactory(requestFactory);
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

    private Dealer ensureDealer() {
        return dealerRepository.findByCompanyAndCodeIgnoreCase(company, "ACCR-DEALER")
                .orElseGet(() -> {
                    Dealer d = new Dealer();
                    d.setCompany(company);
                    d.setCode("ACCR-DEALER");
                    d.setName("Accrual Dealer");
                    d.setCreditLimit(new BigDecimal("500000"));
                    d.setReceivableAccount(ar);
                    d.setOutstandingBalance(new BigDecimal("1000.00"));
                    return dealerRepository.save(d);
                });
    }

    private Invoice ensureInvoice() {
        Invoice inv = new Invoice();
        inv.setCompany(company);
        inv.setDealer(dealer);
        inv.setInvoiceNumber("ACCR-INV-" + System.currentTimeMillis());
        inv.setStatus("POSTED");
        inv.setSubtotal(new BigDecimal("1000.00"));
        inv.setTaxTotal(BigDecimal.ZERO);
        inv.setTotalAmount(new BigDecimal("1000.00"));
        inv.setIssueDate(LocalDate.now());
        inv.setDueDate(LocalDate.now().plusDays(30));
        inv.setCurrency("INR");
        return invoiceRepository.save(inv);
    }

    private void seedInvoicePosting() {
        Map<String, Object> arLine = Map.of(
                "accountId", ar.getId(),
                "debit", new BigDecimal("1000.00"),
                "credit", BigDecimal.ZERO,
                "description", "Invoice AR"
        );
        Map<String, Object> revLine = Map.of(
                "accountId", accrualLiab.getId(), // placeholder to keep balanced; not used for assertions
                "debit", BigDecimal.ZERO,
                "credit", new BigDecimal("1000.00"),
                "description", "Revenue"
        );
        Map<String, Object> payload = Map.of(
                "entryDate", LocalDate.now(),
                "memo", "Seed invoice posting",
                "dealerId", dealer.getId(),
                "lines", List.of(arLine, revLine)
        );
        ResponseEntity<Map> resp = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Number entryId = (Number) ((Map<?, ?>) resp.getBody().get("data")).get("id");
        JournalEntry je = journalEntryRepository.findById(entryId.longValue()).orElseThrow();
        invoice.setJournalEntry(je);
        invoiceRepository.save(invoice);
    }

    @Test
    @DisplayName("Accrual posts and auto-reverses on specified date")
    void accrual_AutoReversal() {
        Map<String, Object> payload = Map.of(
                "debitAccountId", accrualExp.getId(),
                "creditAccountId", accrualLiab.getId(),
                "amount", new BigDecimal("250.00"),
                "entryDate", LocalDate.now(),
                "autoReverseDate", LocalDate.now().plusDays(30),
                "referenceNumber", "ACCR-" + System.currentTimeMillis(),
                "adminOverride", true
        );

        ResponseEntity<Map> resp = rest.exchange("/api/v1/accounting/accruals",
                HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String ref = (String) ((Map<?, ?>) resp.getBody().get("data")).get("referenceNumber");
        List<JournalEntry> entries = journalEntryRepository.findAll().stream()
                .filter(je -> je.getReferenceNumber().startsWith(ref)).toList();
        assertThat(entries.size()).isEqualTo(2); // accrual + reversal
    }

    @Test
    @DisplayName("Bad debt write-off posts Dr Expense / Cr AR")
    void badDebtWriteOff_PostsJournal() {
        Map<String, Object> payload = Map.of(
                "invoiceId", invoice.getId(),
                "expenseAccountId", badDebtExp.getId(),
                "amount", new BigDecimal("300.00"),
                "referenceNumber", "BDE-" + System.currentTimeMillis(),
                "memo", "Admin-approved bad debt write-off",
                "adminOverride", true
        );
        ResponseEntity<Map> resp = rest.exchange("/api/v1/accounting/bad-debts/write-off",
                HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
