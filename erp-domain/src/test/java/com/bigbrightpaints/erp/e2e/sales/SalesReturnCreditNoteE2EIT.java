package com.bigbrightpaints.erp.e2e.sales;

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
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("E2E: Sales Return Credit Note Flow")
class SalesReturnCreditNoteE2EIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "SRN-E2E";
    private static final String ADMIN_EMAIL = "salesreturn@e2e.com";
    private static final String ADMIN_PASSWORD = "return123";

    @Autowired private TestRestTemplate rest;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private FinishedGoodRepository finishedGoodRepository;
    @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private InventoryMovementRepository inventoryMovementRepository;
    @Autowired private ProductionProductRepository productionProductRepository;
    @Autowired private ProductionBrandRepository productionBrandRepository;

    private HttpHeaders headers;
    private Company company;
    private Account ar;
    private Account revenue;
    private Account discount;
    private Account gstOutput;
    private Account inventory;
    private Account cogs;
    private Dealer dealer;
    private FinishedGood finishedGood;

    @BeforeEach
    void setup() {
        dataSeeder.ensureUser(ADMIN_EMAIL, ADMIN_PASSWORD, "Sales Return Admin", COMPANY_CODE,
                List.of("ROLE_ADMIN", "ROLE_ACCOUNTING", "ROLE_SALES"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
        company.setStateCode("MH");
        company = companyRepository.save(company);
        headers = authHeaders();

        ar = ensureAccount("AR-SRN", "Accounts Receivable", AccountType.ASSET);
        revenue = ensureAccount("REV-SRN", "Revenue", AccountType.REVENUE);
        discount = ensureAccount("DISC-SRN", "Discounts", AccountType.EXPENSE);
        gstOutput = ensureAccount("GST-OUT-SRN", "GST Output", AccountType.LIABILITY);
        inventory = ensureAccount("INV-SRN", "Inventory", AccountType.ASSET);
        cogs = ensureAccount("COGS-SRN", "COGS", AccountType.COGS);

        company.setDefaultInventoryAccountId(inventory.getId());
        company.setDefaultCogsAccountId(cogs.getId());
        company.setDefaultRevenueAccountId(revenue.getId());
        company.setDefaultDiscountAccountId(discount.getId());
        company.setDefaultTaxAccountId(gstOutput.getId());
        company.setGstOutputTaxAccountId(gstOutput.getId());
        companyRepository.save(company);

        dealer = ensureDealer();
        finishedGood = ensureFinishedGood();
    }

    @Test
    @DisplayName("Invoice -> sales return preview/post uses canonical dispatch truth and restocks inventory")
    void salesReturn_postsCreditNoteAndRestocksInventory() {
        Invoice invoice = createDispatchedInvoice(new BigDecimal("2"), new BigDecimal("100.00"));
        InvoiceLine invoiceLine = invoice.getLines().getFirst();
        BigDecimal stockAfterDispatch = finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getCurrentStock();

        Map<String, Object> payload = new HashMap<>();
        payload.put("invoiceId", invoice.getId());
        payload.put("reason", "Damaged goods");
        payload.put("lines", List.of(Map.of(
                "invoiceLineId", invoiceLine.getId(),
                "quantity", new BigDecimal("1.00")
        )));

        ResponseEntity<Map> previewResponse = rest.exchange(
                "/api/v1/accounting/sales/returns/preview",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );
        Map<?, ?> previewData = requireData(previewResponse, "sales return preview");
        assertThat(new BigDecimal(String.valueOf(previewData.get("totalReturnAmount"))))
                .isEqualByComparingTo(new BigDecimal("100.00"));

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/sales/returns",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = requireData(response, "sales return");
        Long entryId = ((Number) data.get("id")).longValue();

        JournalEntry entry = journalEntryRepository.findById(entryId).orElseThrow();
        String returnReference = "CRN-" + invoice.getInvoiceNumber();
        assertThat(entry.getReferenceNumber()).isEqualTo(returnReference);
        assertThat(entry.getCorrectionReason()).isEqualTo("SALES_RETURN");
        assertThat(entry.getSourceModule()).isEqualTo("SALES_RETURN");
        assertThat(entry.getSourceReference()).isEqualTo(invoice.getInvoiceNumber());
        assertThat(journalEntryRepository.findByCompanyAndReferenceNumber(company, returnReference)).isPresent();
        assertThat(journalEntryRepository.findFirstByCompanyAndReferenceNumberStartingWith(company, returnReference + "-COGS")).isPresent();

        BigDecimal debitTotal = entry.getLines().stream()
                .map(line -> line.getDebit() != null ? line.getDebit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditTotal = entry.getLines().stream()
                .map(line -> line.getCredit() != null ? line.getCredit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debitTotal).isEqualByComparingTo(creditTotal);

        FinishedGood refreshed = finishedGoodRepository.findById(finishedGood.getId()).orElseThrow();
        assertThat(refreshed.getCurrentStock()).isEqualByComparingTo(stockAfterDispatch.add(new BigDecimal("1.00")));

        List<InventoryMovement> returnMovements = inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc("SALES_RETURN", invoice.getInvoiceNumber());
        assertThat(returnMovements).hasSize(1);
        assertThat(returnMovements.getFirst().getMovementType()).isEqualTo("RETURN");
        assertThat(returnMovements.getFirst().getQuantity()).isEqualByComparingTo("1.00");
        assertThat(returnMovements.getFirst().getJournalEntryId()).isEqualTo(entryId);
    }

    @Test
    @DisplayName("Sales return blocks quantities above dispatched quantity")
    void salesReturn_overReturnIsRejected() {
        Invoice invoice = createDispatchedInvoice(new BigDecimal("1"), new BigDecimal("120.00"));
        InvoiceLine invoiceLine = invoice.getLines().getFirst();
        BigDecimal stockAfterDispatch = finishedGoodRepository.findById(finishedGood.getId()).orElseThrow().getCurrentStock();

        Map<String, Object> payload = new HashMap<>();
        payload.put("invoiceId", invoice.getId());
        payload.put("reason", "Too many units");
        payload.put("lines", List.of(Map.of(
                "invoiceLineId", invoiceLine.getId(),
                "quantity", new BigDecimal("2.00")
        )));

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/accounting/sales/returns",
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody().get("message"))).containsIgnoringCase("return");
        FinishedGood refreshed = finishedGoodRepository.findById(finishedGood.getId()).orElseThrow();
        assertThat(refreshed.getCurrentStock()).isEqualByComparingTo(stockAfterDispatch);
        assertThat(inventoryMovementRepository
                .findByReferenceTypeAndReferenceIdStartingWithOrderByCreatedAtAsc("SALES_RETURN", invoice.getInvoiceNumber()))
                .isEmpty();
    }

    private Invoice createDispatchedInvoice(BigDecimal quantity, BigDecimal unitPrice) {
        Map<String, Object> lineItem = Map.of(
                "productCode", finishedGood.getProductCode(),
                "description", "Sales return test item",
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
                Map.class
        );
        Long orderId = ((Number) requireData(orderResp, "create order").get("id")).longValue();

        ResponseEntity<Map> confirmResp = rest.exchange(
                "/api/v1/sales/orders/" + orderId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Map.class
        );
        requireData(confirmResp, "confirm order");

        Map<String, Object> dispatchReq = Map.of(
                "orderId", orderId,
                "confirmedBy", "sales-return-e2e"
        );
        ResponseEntity<Map> dispatchResp = rest.exchange(
                "/api/v1/sales/dispatch/confirm",
                HttpMethod.POST,
                new HttpEntity<>(dispatchReq, headers),
                Map.class
        );
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
        return dealerRepository.findByCompanyAndCodeIgnoreCase(company, "SRN-DEALER")
                .orElseGet(() -> {
                    Dealer created = new Dealer();
                    created.setCompany(company);
                    created.setCode("SRN-DEALER");
                    created.setName("Sales Return Dealer");
                    created.setEmail("sales-return-dealer@example.com");
                    created.setStateCode(company.getStateCode());
                    created.setGstRegistrationType(GstRegistrationType.REGULAR);
                    created.setReceivableAccount(ar);
                    created.setOutstandingBalance(BigDecimal.ZERO);
                    created.setCreditLimit(new BigDecimal("100000"));
                    return dealerRepository.save(created);
                });
    }

    private FinishedGood ensureFinishedGood() {
        ensureProduct();
        FinishedGood fg = finishedGoodRepository.findByCompanyAndProductCode(company, "FG-SRN")
                .orElseGet(() -> {
                    FinishedGood created = new FinishedGood();
                    created.setCompany(company);
                    created.setProductCode("FG-SRN");
                    created.setName("Sales Return Product");
                    created.setUnit("UNIT");
                    created.setCostingMethod("FIFO");
                    created.setValuationAccountId(inventory.getId());
                    created.setCogsAccountId(cogs.getId());
                    created.setRevenueAccountId(revenue.getId());
                    created.setDiscountAccountId(discount.getId());
                    created.setTaxAccountId(gstOutput.getId());
                    created.setCurrentStock(new BigDecimal("10"));
                    created.setReservedStock(BigDecimal.ZERO);
                    return finishedGoodRepository.save(created);
                });
        if (finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(fg).stream().noneMatch(batch ->
                batch.getQuantityAvailable() != null && batch.getQuantityAvailable().compareTo(BigDecimal.ZERO) > 0)) {
            FinishedGoodBatch batch = new FinishedGoodBatch();
            batch.setFinishedGood(fg);
            batch.setBatchCode("FG-SRN-BATCH");
            batch.setQuantityTotal(new BigDecimal("10"));
            batch.setQuantityAvailable(new BigDecimal("10"));
            batch.setUnitCost(new BigDecimal("50.00"));
            batch.setManufacturedAt(Instant.now());
            finishedGoodBatchRepository.save(batch);
        }
        return fg;
    }

    private ProductionProduct ensureProduct() {
        return productionProductRepository.findByCompanyAndSkuCode(company, "FG-SRN")
                .orElseGet(() -> {
                    ProductionBrand brand = productionBrandRepository.findByCompanyAndCodeIgnoreCase(company, "SRN-BRAND")
                            .orElseGet(() -> {
                                ProductionBrand created = new ProductionBrand();
                                created.setCompany(company);
                                created.setCode("SRN-BRAND");
                                created.setName("Sales Return Brand");
                                return productionBrandRepository.save(created);
                            });
                    ProductionProduct product = new ProductionProduct();
                    product.setCompany(company);
                    product.setBrand(brand);
                    product.setSkuCode("FG-SRN");
                    product.setProductName("Sales Return Product");
                    product.setCategory("FINISHED_GOOD");
                    product.setUnitOfMeasure("UNIT");
                    product.setBasePrice(new BigDecimal("100.00"));
                    product.setGstRate(BigDecimal.ZERO);
                    product.setActive(true);
                    return productionProductRepository.save(product);
                });
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
