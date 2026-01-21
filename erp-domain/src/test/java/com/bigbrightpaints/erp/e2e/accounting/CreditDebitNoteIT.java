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

@DisplayName("E2E: Credit/Debit Notes")
public class CreditDebitNoteIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "CDN-E2E";
    private static final String ADMIN_EMAIL = "cdnote@bbp.com";
    private static final String ADMIN_PASSWORD = "cd123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;

    private HttpHeaders headers;
    private Company company;
    private Account ar;
    private Account rev;
    private Account ap;
    private Account inventory;
    private Dealer dealer;
    private Invoice invoice;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "CD Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        ar = ensureAccount("AR", "Accounts Receivable", AccountType.ASSET);
        rev = ensureAccount("REV", "Revenue", AccountType.REVENUE);
        ap = ensureAccount("AP", "Accounts Payable", AccountType.LIABILITY);
        inventory = ensureAccount("INV", "Inventory", AccountType.ASSET);
        dealer = ensureDealer();
        invoice = ensureInvoice();
        headers = authHeaders();
        seedInvoicePosting();
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
        return dealerRepository.findByCompanyAndCodeIgnoreCase(company, "CD-DEALER")
                .orElseGet(() -> {
                    Dealer d = new Dealer();
                    d.setCompany(company);
                    d.setCode("CD-DEALER");
                    d.setName("Credit Dealer");
                    d.setCreditLimit(new BigDecimal("500000"));
                    d.setOutstandingBalance(new BigDecimal("1000.00"));
                    d.setReceivableAccount(ar);
                    return dealerRepository.save(d);
                });
    }

    private Invoice ensureInvoice() {
        Invoice inv = new Invoice();
        inv.setCompany(company);
        inv.setDealer(dealer);
        inv.setInvoiceNumber("CDN-INV-" + System.currentTimeMillis());
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
                "description", "Invoice AR",
                "debit", new BigDecimal("1000.00"),
                "credit", BigDecimal.ZERO
        );
        Map<String, Object> revLine = Map.of(
                "accountId", rev.getId(),
                "description", "Revenue",
                "debit", BigDecimal.ZERO,
                "credit", new BigDecimal("1000.00")
        );
        Map<String, Object> payload = Map.of(
                "entryDate", LocalDate.now(),
                "referenceNumber", "INV-JE-" + System.currentTimeMillis(),
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
    @DisplayName("Credit note reverses invoice journal and is idempotent by reference")
    void creditNote_ReversesAndIdempotent() {
        String ref = "CN-" + System.currentTimeMillis();
        Map<String, Object> payload = Map.of(
                "invoiceId", invoice.getId(),
                "referenceNumber", ref,
                "memo", "Credit for return"
        );

        ResponseEntity<Map> first = rest.exchange("/api/v1/accounting/credit-notes",
                HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> second = rest.exchange("/api/v1/accounting/credit-notes",
                HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<JournalEntry> entries = journalEntryRepository.findAll().stream()
                .filter(je -> ref.equals(je.getReferenceNumber()))
                .toList();
        assertThat(entries).hasSize(1);

        JournalEntry note = entries.get(0);
        BigDecimal debits = note.getLines().stream().map(l -> l.getDebit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = note.getLines().stream().map(l -> l.getCredit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);

        Invoice refreshed = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(refreshed.getStatus()).isEqualTo("VOID");
    }

    @Test
    @DisplayName("Credit note allowed on fully paid invoice")
    void creditNote_allowsPaidInvoice() {
        Invoice paidInvoice = invoiceRepository.findById(invoice.getId()).orElseThrow();
        paidInvoice.setOutstandingAmount(BigDecimal.ZERO);
        paidInvoice.setStatus("PAID");
        invoiceRepository.save(paidInvoice);

        String ref = "CN-PAID-" + System.currentTimeMillis();
        Map<String, Object> payload = Map.of(
                "invoiceId", invoice.getId(),
                "referenceNumber", ref,
                "memo", "Credit note for paid invoice"
        );

        ResponseEntity<Map> resp = rest.exchange("/api/v1/accounting/credit-notes",
                HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        Invoice refreshed = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(refreshed.getStatus()).isEqualTo("VOID");
    }

    @Test
    @DisplayName("Journal entry with AR account requires dealer context")
    void journalEntry_requiresDealerForAr() {
        Map<String, Object> arLine = Map.of(
                "accountId", ar.getId(),
                "description", "AR line",
                "debit", new BigDecimal("250.00"),
                "credit", BigDecimal.ZERO
        );
        Map<String, Object> revLine = Map.of(
                "accountId", rev.getId(),
                "description", "Revenue",
                "debit", BigDecimal.ZERO,
                "credit", new BigDecimal("250.00")
        );
        Map<String, Object> payload = Map.of(
                "entryDate", LocalDate.now(),
                "referenceNumber", "AR-NO-DEALER-" + System.currentTimeMillis(),
                "memo", "AR without dealer",
                "lines", List.of(arLine, revLine)
        );

        ResponseEntity<Map> resp = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Journal entry with AP account requires supplier context")
    void journalEntry_requiresSupplierForAp() {
        Map<String, Object> invLine = Map.of(
                "accountId", inventory.getId(),
                "description", "Inventory",
                "debit", new BigDecimal("300.00"),
                "credit", BigDecimal.ZERO
        );
        Map<String, Object> apLine = Map.of(
                "accountId", ap.getId(),
                "description", "AP line",
                "debit", BigDecimal.ZERO,
                "credit", new BigDecimal("300.00")
        );
        Map<String, Object> payload = Map.of(
                "entryDate", LocalDate.now(),
                "referenceNumber", "AP-NO-SUPPLIER-" + System.currentTimeMillis(),
                "memo", "AP without supplier",
                "lines", List.of(invLine, apLine)
        );

        ResponseEntity<Map> resp = rest.exchange("/api/v1/accounting/journal-entries",
                HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
