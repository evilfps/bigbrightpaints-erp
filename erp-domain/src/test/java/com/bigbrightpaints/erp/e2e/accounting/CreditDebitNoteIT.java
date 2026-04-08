package com.bigbrightpaints.erp.e2e.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceLine;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

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
  @Autowired private PackagingSlipRepository packagingSlipRepository;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private SalesOrderRepository salesOrderRepository;
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
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "CD Admin",
        COMPANY_CODE,
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
  @DisplayName("Credit note normalizes legacy reference to CRN and is idempotent by reference")
  void creditNote_ReversesAndIdempotent() {
    Invoice invoice = createDispatchedInvoice(new BigDecimal("1"), new BigDecimal("150.00"));
    String submittedReference = "CN-" + invoice.getInvoiceNumber();
    String canonicalReference = "CRN-" + invoice.getInvoiceNumber();
    Map<String, Object> payload =
        Map.of(
            "invoiceId",
            invoice.getId(),
            "referenceNumber",
            submittedReference,
            "memo",
            "Credit for return");

    ResponseEntity<Map> first =
        rest.exchange(
            "/api/v1/accounting/credit-notes",
            HttpMethod.POST,
            new HttpEntity<>(payload, headers),
            Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<Map> second =
        rest.exchange(
            "/api/v1/accounting/credit-notes",
            HttpMethod.POST,
            new HttpEntity<>(payload, headers),
            Map.class);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> firstData = (Map<String, Object>) first.getBody().get("data");
    Map<String, Object> secondData = (Map<String, Object>) second.getBody().get("data");
    assertThat(firstData.get("referenceNumber")).isEqualTo(canonicalReference);
    assertThat(secondData.get("referenceNumber")).isEqualTo(canonicalReference);

    JournalEntry note =
        journalEntryRepository
            .findByCompanyAndReferenceNumber(company, canonicalReference)
            .orElseThrow();
    assertThat(note.getReversalOf()).isNotNull();
    assertThat(note.getReversalOf().getId()).isEqualTo(invoice.getJournalEntry().getId());

    BigDecimal debits = sumDebits(note);
    BigDecimal credits = sumCredits(note);
    assertThat(debits).isEqualByComparingTo(credits);

    Invoice refreshed = invoiceRepository.findById(invoice.getId()).orElseThrow();
    assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(refreshed.getStatus()).isEqualTo("VOID");
  }

  @Test
  @DisplayName("Credit note after settlement caps to live outstanding in invoice API")
  void creditNote_afterSettlementOnlyCreditsLiveOutstandingInApi() {
    Invoice invoice = createDispatchedInvoice(new BigDecimal("1"), new BigDecimal("1000.00"));
    BigDecimal appliedAmount = new BigDecimal("800.00");
    String settlementRef = "SET-CN-" + System.currentTimeMillis();
    Map<String, Object> allocation =
        Map.of(
            "invoiceId",
            invoice.getId(),
            "appliedAmount",
            appliedAmount,
            "discountAmount",
            BigDecimal.ZERO,
            "writeOffAmount",
            BigDecimal.ZERO,
            "memo",
            "Partial settlement before credit note");
    Map<String, Object> settlementReq = new HashMap<>();
    settlementReq.put("partnerType", "DEALER");
    settlementReq.put("partnerId", dealer.getId());
    settlementReq.put("cashAccountId", cash.getId());
    settlementReq.put("settlementDate", LocalDate.now());
    settlementReq.put("referenceNumber", settlementRef);
    settlementReq.put("allocations", List.of(allocation));
    HttpHeaders settlementHeaders = headersWithIdempotencyKey(settlementRef);

    ResponseEntity<Map> settleResp =
        rest.exchange(
            "/api/v1/accounting/settlements/dealers",
            HttpMethod.POST,
            new HttpEntity<>(settlementReq, settlementHeaders),
            Map.class);
    assertThat(settleResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    String ref = "CRN-OVER-" + invoice.getInvoiceNumber();
    Map<String, Object> creditReq =
        Map.of(
            "invoiceId",
            invoice.getId(),
            "referenceNumber",
            ref,
            "memo",
            "Over-credit after partial payment");
    ResponseEntity<Map> creditResp =
        rest.exchange(
            "/api/v1/accounting/credit-notes",
            HttpMethod.POST,
            new HttpEntity<>(creditReq, headers),
            Map.class);
    assertThat(creditResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    JournalEntry note =
        journalEntryRepository.findByCompanyAndReferenceNumber(company, ref).orElseThrow();
    BigDecimal noteCredits =
        note.getLines().stream()
            .map(line -> line.getCredit() != null ? line.getCredit() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(noteCredits).isEqualByComparingTo("200.00");

    ResponseEntity<Map> invoiceResp =
        rest.exchange(
            "/api/v1/invoices/" + invoice.getId(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(invoiceResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) invoiceResp.getBody().get("data");
    BigDecimal outstanding = new BigDecimal(data.get("outstandingAmount").toString());
    assertThat(outstanding).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(data.get("status")).isEqualTo("PAID");
  }

  @Test
  @DisplayName("Bad debt write-off exposes distinct WRITTEN_OFF invoice status")
  void badDebtWriteOff_marksInvoiceWrittenOffInReads() {
    Invoice invoice = createDispatchedInvoice(new BigDecimal("1"), new BigDecimal("220.00"));
    String writeOffRef = "BDE-" + invoice.getInvoiceNumber();
    Map<String, Object> writeOffReq =
        Map.of(
            "invoiceId",
            invoice.getId(),
            "expenseAccountId",
            discount.getId(),
            "amount",
            new BigDecimal("220.00"),
            "referenceNumber",
            writeOffRef,
            "memo",
            "Write off uncollectible invoice");

    ResponseEntity<Map> writeOffResp =
        rest.exchange(
            "/api/v1/accounting/bad-debts/write-off",
            HttpMethod.POST,
            new HttpEntity<>(writeOffReq, headers),
            Map.class);
    assertThat(writeOffResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    Invoice refreshed = invoiceRepository.findById(invoice.getId()).orElseThrow();
    assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(refreshed.getStatus()).isEqualTo("WRITTEN_OFF");

    ResponseEntity<Map> invoiceResp =
        rest.exchange(
            "/api/v1/invoices/" + invoice.getId(),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(invoiceResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = (Map<?, ?>) invoiceResp.getBody().get("data");
    assertThat(data.get("status")).isEqualTo("WRITTEN_OFF");
    assertThat(new BigDecimal(data.get("outstandingAmount").toString()))
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("Sales return follow-up read returns 200 with posted CRN journal")
  void salesReturn_followUpReadReturnsCreatedJournal() {
    Invoice invoice = createDispatchedInvoice(new BigDecimal("2"), new BigDecimal("150.00"));
    InvoiceLine invoiceLine = invoice.getLines().getFirst();

    Map<String, Object> returnLine =
        Map.of("invoiceLineId", invoiceLine.getId(), "quantity", new BigDecimal("1"));
    Map<String, Object> salesReturnReq =
        Map.of(
            "invoiceId",
            invoice.getId(),
            "reason",
            "Integration test sales return read",
            "lines",
            List.of(returnLine));

    ResponseEntity<Map> postResp =
        rest.exchange(
            "/api/v1/accounting/sales/returns",
            HttpMethod.POST,
            new HttpEntity<>(salesReturnReq, headers),
            Map.class);
    assertThat(postResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> posted = (Map<?, ?>) postResp.getBody().get("data");
    Long postedJournalId = ((Number) posted.get("id")).longValue();
    assertThat(posted.get("referenceNumber").toString()).startsWith("CRN-");

    ResponseEntity<Map> listResp =
        rest.exchange(
            "/api/v1/accounting/sales/returns",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) listResp.getBody().get("data");
    assertThat(rows)
        .isNotEmpty()
        .anySatisfy(
            row -> {
              assertThat(((Number) row.get("id")).longValue()).isEqualTo(postedJournalId);
              assertThat(row.get("referenceNumber").toString()).startsWith("CRN-");
            });
  }

  @Test
  @DisplayName("Accrual follow-up read returns 200 with created journal entry")
  void accrual_followUpReadReturnsCreatedJournal() {
    Map<String, Object> accrualReq =
        Map.of(
            "debitAccountId",
            discount.getId(),
            "creditAccountId",
            tax.getId(),
            "amount",
            new BigDecimal("275.00"),
            "entryDate",
            LocalDate.now(),
            "memo",
            "Integration test accrual read");

    ResponseEntity<Map> postResp =
        rest.exchange(
            "/api/v1/accounting/accruals",
            HttpMethod.POST,
            new HttpEntity<>(accrualReq, headers),
            Map.class);
    assertThat(postResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> posted = (Map<?, ?>) postResp.getBody().get("data");
    Long postedJournalId = ((Number) posted.get("id")).longValue();

    ResponseEntity<Map> listResp =
        rest.exchange(
            "/api/v1/accounting/journal-entries",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) listResp.getBody().get("data");
    assertThat(rows)
        .isNotEmpty()
        .anySatisfy(
            row -> assertThat(((Number) row.get("id")).longValue()).isEqualTo(postedJournalId));
  }

  private Invoice createDispatchedInvoice(BigDecimal quantity, BigDecimal unitPrice) {
    return createDispatchedInvoice(quantity, unitPrice, BigDecimal.ZERO, "NONE");
  }

  private Invoice createDispatchedInvoice(
      BigDecimal quantity, BigDecimal unitPrice, BigDecimal gstRate, String gstTreatment) {
    Map<String, Object> lineItem =
        Map.of(
            "productCode",
            finishedGood.getProductCode(),
            "description",
            "Credit note test item",
            "quantity",
            quantity,
            "unitPrice",
            unitPrice,
            "gstRate",
            gstRate);
    BigDecimal lineSubtotal = quantity.multiply(unitPrice);
    BigDecimal total = lineSubtotal;
    Map<String, Object> orderReq =
        Map.of(
            "dealerId",
            dealer.getId(),
            "totalAmount",
            total,
            "currency",
            "INR",
            "items",
            List.of(lineItem),
            "gstTreatment",
            gstTreatment);

    ResponseEntity<Map> orderResp =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);
    Long orderId = ((Number) requireData(orderResp, "create order").get("id")).longValue();

    PackagingSlip slip = ensureDispatchSlip(orderId);
    Map<String, Object> dispatchReq = new HashMap<>();
    dispatchReq.put("packagingSlipId", slip.getId());
    dispatchReq.put("notes", "credit note dispatch " + orderId);
    dispatchReq.put(
        "lines",
        slip.getLines().stream()
            .map(
                line ->
                    Map.of(
                        "lineId",
                        line.getId(),
                        "shippedQuantity",
                        line.getOrderedQuantity() != null
                            ? line.getOrderedQuantity()
                            : line.getQuantity()))
            .toList());
    addDispatchMetadata(dispatchReq, "credit-note-" + orderId);
    ResponseEntity<Map> dispatchResp =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(dispatchReq, headers),
            Map.class);
    requireData(dispatchResp, "dispatch order");
    Long invoiceId = packagingSlipRepository.findById(slip.getId()).orElseThrow().getInvoiceId();
    return invoiceRepository.findById(invoiceId).orElseThrow();
  }

  private PackagingSlip ensureDispatchSlip(Long orderId) {
    PackagingSlip existing =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, orderId).orElse(null);
    if (existing != null) {
      return existing;
    }
    com.bigbrightpaints.erp.core.security.CompanyContextHolder.setCompanyCode(company.getCode());
    try {
      SalesOrder order = salesOrderRepository.findById(orderId).orElseThrow();
      finishedGoodsService.reserveForOrder(order);
    } finally {
      com.bigbrightpaints.erp.core.security.CompanyContextHolder.clear();
    }
    return packagingSlipRepository.findByCompanyAndSalesOrderId(company, orderId).orElseThrow();
  }

  private HttpHeaders authHeaders() {
    Map<String, Object> req =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY_CODE);
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

  private HttpHeaders headersWithIdempotencyKey(String idempotencyKey) {
    HttpHeaders scoped = new HttpHeaders();
    scoped.putAll(headers);
    scoped.set("Idempotency-Key", idempotencyKey);
    return scoped;
  }

  private Account ensureAccount(String code, String name, AccountType type) {
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

  private Dealer ensureDealer() {
    return dealerRepository
        .findByCompanyAndCodeIgnoreCase(company, "CD-DEALER")
        .orElseGet(
            () -> {
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
    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, "FG-CDN")
            .orElseGet(
                () -> {
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
    if (finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(fg).stream()
        .noneMatch(
            batch ->
                batch.getQuantityAvailable() != null
                    && batch.getQuantityAvailable().compareTo(BigDecimal.ZERO) > 0)) {
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
    return productionProductRepository
        .findByCompanyAndSkuCode(company, "FG-CDN")
        .orElseGet(
            () -> {
              ProductionBrand brand =
                  productionBrandRepository
                      .findByCompanyAndCodeIgnoreCase(company, "CDN-BRAND")
                      .orElseGet(
                          () -> {
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

  private BigDecimal sumDebits(JournalEntry entry) {
    return entry.getLines().stream()
        .map(line -> line.getDebit() != null ? line.getDebit() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal sumCredits(JournalEntry entry) {
    return entry.getLines().stream()
        .map(line -> line.getCredit() != null ? line.getCredit() : BigDecimal.ZERO)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private Map<?, ?> requireData(ResponseEntity<Map> response, String action) {
    if (!response.getStatusCode().is2xxSuccessful()) {
      throw new AssertionError(
          action + " failed: status=" + response.getStatusCode() + " body=" + response.getBody());
    }
    Map<?, ?> body = response.getBody();
    if (body == null || !(body.get("data") instanceof Map<?, ?> data)) {
      throw new AssertionError(action + " response missing data payload: " + body);
    }
    return data;
  }
}
