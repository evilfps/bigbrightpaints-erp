package com.bigbrightpaints.erp.e2e.accounting;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: Credit/Debit Notes")
class CreditDebitNoteIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "CDN-E2E";
    private static final String ADMIN_EMAIL = "cdnote@bbp.com";
    private static final String ADMIN_PASSWORD = "cd123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Autowired private ProductionProductRepository productionProductRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;

    private HttpHeaders headers;
    private Company company;
    private Account ar;
    private Account rev;
    private Account cash;
    private Account inventory;
    private Account cogs;
    private Account discount;
    private Account tax;
    private Dealer dealer;
    private FinishedGood finishedGood;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "CD Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        company.setStateCode("MH");
        company = companyRepository.save(company);
        ar = ensureAccount("AR", "Accounts Receivable", AccountType.ASSET);
        rev = ensureAccount("REV", "Revenue", AccountType.REVENUE);
        cash = ensureAccount("CASH", "Cash", AccountType.ASSET);
        inventory = ensureAccount("INV", "Inventory", AccountType.ASSET);
        cogs = ensureAccount("COGS", "COGS", AccountType.COGS);
        discount = ensureAccount("DISC", "Discounts", AccountType.EXPENSE);
        tax = ensureAccount("TAX", "Tax Payable", AccountType.LIABILITY);
        company.setDefaultInventoryAccountId(inventory.getId());
        company.setDefaultCogsAccountId(cogs.getId());
        company.setDefaultRevenueAccountId(rev.getId());
        company.setDefaultDiscountAccountId(discount.getId());
        company.setDefaultTaxAccountId(tax.getId());
        companyRepository.save(company);
        dealer = ensureDealer();
        finishedGood = ensureFinishedGood();
        headers = authHeaders();
    }

    @Test
    @DisplayName("Credit note reverses canonical invoice journal and is idempotent by reference")
    void creditNote_ReversesAndIdempotent() {
        Invoice invoice = createDispatchedInvoice(new BigDecimal("1"), new BigDecimal("150.00"));
        String reference = "CN-" + invoice.getInvoiceNumber();
        Map<String, Object> payload = Map.of(
                "invoiceId", invoice.getId(),
                "referenceNumber", reference,
                "memo", "Credit for return"
        );

        ResponseEntity<Map> first = rest.exchange(
                "/api/v1/accounting/credit-notes",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> second = rest.exchange(
                "/api/v1/accounting/credit-notes",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        JournalEntry note = journalEntryRepository.findByCompanyAndReferenceNumber(company, reference).orElseThrow();
        assertThat(note.getReversalOf()).isNotNull();
        assertThat(note.getReversalOf().getId()).isEqualTo(invoice.getJournalEntry().getId());

        BigDecimal debits = note.getLines().stream().map(line -> line.getDebit() != null ? line.getDebit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = note.getLines().stream().map(line -> line.getCredit() != null ? line.getCredit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);

        Invoice refreshed = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(refreshed.getStatus()).isEqualTo("VOID");
    }

    @Test
    @DisplayName("Credit note after settlement exposes negative outstanding balance in invoice API")
    void creditNote_overCreditShowsNegativeOutstandingInApi() {
        Invoice invoice = createDispatchedInvoice(new BigDecimal("1"), new BigDecimal("1000.00"));
        BigDecimal appliedAmount = new BigDecimal("800.00");
        String settlementRef = "SET-CN-" + System.currentTimeMillis();
        Map<String, Object> allocation = Map.of(
                "invoiceId", invoice.getId(),
                "appliedAmount", appliedAmount,
                "discountAmount", BigDecimal.ZERO,
                "writeOffAmount", BigDecimal.ZERO,
                "memo", "Partial settlement before credit note"
        );
        Map<String, Object> settlementReq = new HashMap<>();
        settlementReq.put("dealerId", dealer.getId());
        settlementReq.put("cashAccountId", cash.getId());
        settlementReq.put("settlementDate", LocalDate.now());
        settlementReq.put("referenceNumber", settlementRef);
        settlementReq.put("idempotencyKey", settlementRef);
        settlementReq.put("allocations", List.of(allocation));

        ResponseEntity<Map> settleResp = rest.exchange(
                "/api/v1/accounting/settlements/dealers",
                HttpMethod.POST,
                new HttpEntity<>(settlementReq, headers),
                Map.class);
        assertThat(settleResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String ref = "CN-OVER-" + invoice.getInvoiceNumber();
        Map<String, Object> creditReq = Map.of(
                "invoiceId", invoice.getId(),
                "referenceNumber", ref,
                "memo", "Over-credit after partial payment"
        );
        ResponseEntity<Map> creditResp = rest.exchange(
                "/api/v1/accounting/credit-notes",
                HttpMethod.POST,
                new HttpEntity<>(creditReq, headers),
                Map.class);
        assertThat(creditResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> invoiceResp = rest.exchange(
                "/api/v1/invoices/" + invoice.getId(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(invoiceResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) invoiceResp.getBody().get("data");
        BigDecimal outstanding = new BigDecimal(data.get("outstandingAmount").toString());
        assertThat(outstanding).isEqualByComparingTo(appliedAmount.negate());
        assertThat(data.get("status")).isEqualTo("VOID");
    }

    private Invoice createDispatchedInvoice(BigDecimal quantity, BigDecimal unitPrice) {
        Map<String, Object> lineItem = Map.of(
                "productCode", finishedGood.getProductCode(),
                "description", "Credit note test item",
                "quantity", quantity,
                "unitPrice", unitPrice,
                "gstRate", BigDecimal.ZERO
        );
        BigDecimal total = quantity.multiply(unitPrice);
        Map<String, Object> orderReq = Map.of(
                "dealerId", dealer.getId(),
                "totalAmount", total,
                "currency", "INR",
                "items", List.of(lineItem),
                "gstTreatment", "NONE"
        );

        ResponseEntity<Map> orderResp = rest.exchange(
                "/api/v1/sales/orders",
                HttpMethod.POST,
                new HttpEntity<>(orderReq, headers),
                Map.class);
        Long orderId = ((Number) requireData(orderResp, "create order").get("id")).longValue();

        Map<String, Object> dispatchReq = new HashMap<>();
        dispatchReq.put("orderId", orderId);
        dispatchReq.put("confirmedBy", "credit-note-e2e");
        addDispatchMetadata(dispatchReq, "credit-note-" + orderId);
        ResponseEntity<Map> dispatchResp = rest.exchange(
                "/api/v1/sales/dispatch/confirm",
                HttpMethod.POST,
                new HttpEntity<>(dispatchReq, headers),
                Map.class);
        Long invoiceId = ((Number) requireData(dispatchResp, "dispatch order").get("finalInvoiceId")).longValue();
        return invoiceRepository.findById(invoiceId).orElseThrow();
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
                    Dealer created = new Dealer();
                    created.setCompany(company);
                    created.setCode("CD-DEALER");
                    created.setName("Credit Dealer");
                    created.setEmail("credit-dealer@example.com");
                    created.setStateCode(company.getStateCode());
                    created.setGstRegistrationType(GstRegistrationType.REGULAR);
                    created.setCreditLimit(new BigDecimal("500000"));
                    created.setOutstandingBalance(BigDecimal.ZERO);
                    created.setReceivableAccount(ar);
                    return dealerRepository.save(created);
                });
    }

    private FinishedGood ensureFinishedGood() {
        ensureProduct();
        FinishedGood fg = finishedGoodRepository.findByCompanyAndProductCode(company, "FG-CDN")
                .orElseGet(() -> {
                    FinishedGood created = new FinishedGood();
                    created.setCompany(company);
                    created.setProductCode("FG-CDN");
                    created.setName("Credit Note Product");
                    created.setUnit("UNIT");
                    created.setCostingMethod("FIFO");
                    created.setCurrentStock(new BigDecimal("10"));
                    created.setReservedStock(BigDecimal.ZERO);
                    created.setValuationAccountId(inventory.getId());
                    created.setCogsAccountId(cogs.getId());
                    created.setRevenueAccountId(rev.getId());
                    created.setDiscountAccountId(discount.getId());
                    return finishedGoodRepository.save(created);
                });
        if (finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(fg).stream().noneMatch(batch ->
                batch.getQuantityAvailable() != null && batch.getQuantityAvailable().compareTo(BigDecimal.ZERO) > 0)) {
            FinishedGoodBatch batch = new FinishedGoodBatch();
            batch.setFinishedGood(fg);
            batch.setBatchCode("FG-CDN-BATCH");
            batch.setQuantityTotal(new BigDecimal("10"));
            batch.setQuantityAvailable(new BigDecimal("10"));
            batch.setUnitCost(new BigDecimal("60.00"));
            batch.setManufacturedAt(Instant.now());
            finishedGoodBatchRepository.save(batch);
        }
        return fg;
    }

    private ProductionProduct ensureProduct() {
        return productionProductRepository.findByCompanyAndSkuCode(company, "FG-CDN")
                .orElseGet(() -> {
                    ProductionBrand brand = productionBrandRepository.findByCompanyAndCodeIgnoreCase(company, "CDN-BRAND")
                            .orElseGet(() -> {
                                ProductionBrand created = new ProductionBrand();
                                created.setCompany(company);
                                created.setCode("CDN-BRAND");
                                created.setName("Credit Note Brand");
                                return productionBrandRepository.save(created);
                            });
                    ProductionProduct product = new ProductionProduct();
                    product.setCompany(company);
                    product.setBrand(brand);
                    product.setSkuCode("FG-CDN");
                    product.setProductName("Credit Note Product");
                    product.setCategory("FINISHED_GOOD");
                    product.setUnitOfMeasure("UNIT");
                    product.setBasePrice(new BigDecimal("150.00"));
                    product.setGstRate(BigDecimal.ZERO);
                    product.setActive(true);
                    return productionProductRepository.save(product);
                });
    }

    private void addDispatchMetadata(Map<String, Object> request, String referenceSeed) {
        request.put("transporterName", "BB Logistics");
        request.put("driverName", "Driver " + referenceSeed);
        request.put("vehicleNumber", "MH12" + Math.abs(referenceSeed.hashCode()));
        request.put("challanReference", "CH-" + referenceSeed);
    }

    private Map<?, ?> requireData(ResponseEntity<Map> response, String action) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError(action + " failed: status=" + response.getStatusCode() + " body=" + response.getBody());
        }
        Map<?, ?> body = response.getBody();
        if (body == null || !(body.get("data") instanceof Map<?, ?> data)) {
            throw new AssertionError(action + " response missing data payload: " + body);
        }
        return data;
    }
}
