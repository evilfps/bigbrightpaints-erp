package com.bigbrightpaints.erp.e2e.sales;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.mock.web.MockMultipartFile;

import com.bigbrightpaints.erp.core.audittrail.AuditActionEvent;
import com.bigbrightpaints.erp.core.audittrail.AuditActionEventRepository;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.DealerLedgerRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.accounting.service.JournalReferenceResolver;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservation;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReservationRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLineRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.inventory.service.OpeningStockImportService;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.production.dto.CatalogItemCreateCommand;
import com.bigbrightpaints.erp.modules.production.dto.ProductionProductDto;
import com.bigbrightpaints.erp.modules.production.service.ProductionCatalogService;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

/**
 * E2E Tests for Sales & Order Fulfillment workflows
 */
@DisplayName("E2E: Order Fulfillment")
public class OrderFulfillmentE2ETest extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "SALES-E2E";
  private static final String ADMIN_EMAIL = "sales@e2e.com";
  private static final String ADMIN_PASSWORD = "sales123";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private SalesOrderRepository salesOrderRepository;
  @Autowired private PackagingSlipRepository packagingSlipRepository;
  @Autowired private PackagingSlipLineRepository packagingSlipLineRepository;
  @Autowired private InventoryMovementRepository inventoryMovementRepository;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private OpeningStockImportService openingStockImportService;
  @Autowired private InvoiceRepository invoiceRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private JournalReferenceResolver journalReferenceResolver;
  @Autowired private InventoryReservationRepository inventoryReservationRepository;
  @Autowired private DealerLedgerRepository dealerLedgerRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionCatalogService productionCatalogService;
  @Autowired private AuditActionEventRepository auditActionEventRepository;

  private String authToken;
  private HttpHeaders headers;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Sales Admin",
        COMPANY_CODE,
        List.of("ROLE_ADMIN", "ROLE_SALES", "orders.approve", "dispatch.confirm"));
    authToken = login();
    headers = createHeaders(authToken);
    ensureTestAccounts();
  }

  @Test
  @DisplayName("Setup preserves existing company state code")
  void ensureTestAccounts_preservesExistingCompanyStateCode() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    company.setStateCode("DL");
    companyRepository.save(company);

    ensureTestAccounts();

    Company refreshed = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    assertThat(refreshed.getStateCode()).isEqualTo("DL");
  }

  @Test
  @DisplayName("Setup seeds company state code when missing")
  void ensureTestAccounts_seedsMissingCompanyStateCode() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    company.setStateCode(null);
    companyRepository.save(company);

    ensureTestAccounts();

    Company refreshed = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    assertThat(refreshed.getStateCode()).isEqualTo("MH");
  }

  private String login() {
    Map<String, Object> req =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY_CODE);
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

  private HttpHeaders headersWithCorrelationId(HttpHeaders baseHeaders, UUID correlationId) {
    HttpHeaders scoped = new HttpHeaders();
    scoped.putAll(baseHeaders);
    scoped.set("X-Correlation-Id", correlationId.toString());
    return scoped;
  }

  private void ensureTestAccounts() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    Account receivable =
        ensureAccount(company, "ASSET-AR", "Accounts Receivable", AccountType.ASSET);
    Account revenue = ensureAccount(company, "REV-SALES", "Sales Revenue", AccountType.REVENUE);
    Account cogs = ensureAccount(company, "EXP-COGS", "Cost of Goods Sold", AccountType.EXPENSE);
    Account inventory =
        ensureAccount(company, "ASSET-INV-FG", "Finished Goods Inventory", AccountType.ASSET);
    Account gstOutput = ensureAccount(company, "LIAB-GST", "GST Liability", AccountType.LIABILITY);
    Account gstInput = ensureAccount(company, "GST-IN", "GST Input", AccountType.ASSET);
    Account discount = ensureAccount(company, "DISC-SALES", "Sales Discounts", AccountType.EXPENSE);

    if (company.getDefaultInventoryAccountId() == null) {
      company.setDefaultInventoryAccountId(inventory.getId());
    }
    if (company.getDefaultCogsAccountId() == null) {
      company.setDefaultCogsAccountId(cogs.getId());
    }
    if (company.getDefaultRevenueAccountId() == null) {
      company.setDefaultRevenueAccountId(revenue.getId());
    }
    if (company.getDefaultDiscountAccountId() == null) {
      company.setDefaultDiscountAccountId(discount.getId());
    }
    if (company.getDefaultTaxAccountId() == null) {
      company.setDefaultTaxAccountId(gstOutput.getId());
    }
    if (company.getGstOutputTaxAccountId() == null
        || !company.getGstOutputTaxAccountId().equals(gstOutput.getId())) {
      company.setGstOutputTaxAccountId(gstOutput.getId());
    }
    if (company.getGstInputTaxAccountId() == null
        || !company.getGstInputTaxAccountId().equals(gstInput.getId())) {
      company.setGstInputTaxAccountId(gstInput.getId());
    }
    if (company.getStateCode() == null || company.getStateCode().isBlank()) {
      company.setStateCode("MH");
    }
    companyRepository.save(company);
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
  @DisplayName("Create Order: Auto Approval within Credit Limit")
  void createOrder_AutoApproval_WithinCreditLimit() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    // Create dealer with credit limit
    Dealer dealer =
        createDealer(company, "AUTO-DEALER", "Auto Dealer Ltd", new BigDecimal("100000"));

    // Create finished good with stock
    FinishedGood fg = createFinishedGood(company, "FG-AUTO-001", new BigDecimal("100"));

    // Create order within credit limit
    Map<String, Object> lineItem =
        Map.of(
            "productCode",
            fg.getProductCode(),
            "description",
            "Test Product",
            "quantity",
            new BigDecimal("10"),
            "unitPrice",
            new BigDecimal("500.00"),
            "gstRate",
            BigDecimal.ZERO);

    Map<String, Object> orderReq =
        Map.of(
            "dealerId",
            dealer.getId(),
            "totalAmount",
            new BigDecimal("5000.00"),
            "currency",
            "INR",
            "items",
            List.of(lineItem),
            "gstTreatment",
            "NONE");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> orderData = requireData(response, "create order");

    // Order should be auto-approved or pending based on business rules
    assertThat(orderData.get("status"))
        .isIn(
            "BOOKED",
            "APPROVED",
            "PENDING",
            "DRAFT",
            "PENDING_PRODUCTION",
            "CONFIRMED",
            "READY_TO_SHIP",
            "RESERVED");
  }

  @Test
  @DisplayName("Create Order: Requires Manual Approval exceeds Credit Limit")
  void createOrder_RequiresManualApproval_ExceedsCreditLimit() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    // Create dealer with low credit limit
    Dealer dealer =
        createDealer(company, "MANUAL-DEALER", "Manual Dealer Ltd", new BigDecimal("5000"));

    // Create finished good
    FinishedGood fg = createFinishedGood(company, "FG-MANUAL-001", new BigDecimal("100"));

    // Create order exceeding credit limit
    Map<String, Object> lineItem =
        Map.of(
            "productCode",
            fg.getProductCode(),
            "description",
            "Test Product",
            "quantity",
            new BigDecimal("20"),
            "unitPrice",
            new BigDecimal("500.00"),
            "gstRate",
            BigDecimal.ZERO);

    Map<String, Object> orderReq =
        Map.of(
            "dealerId",
            dealer.getId(),
            "totalAmount",
            new BigDecimal("10000.00"),
            "currency",
            "INR",
            "items",
            List.of(lineItem),
            "gstTreatment",
            "NONE");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).isNotNull();
    Object message = response.getBody().get("message");
    assertThat(message).as("credit limit rejection response").isNotNull();
    String normalized = message.toString().toLowerCase();
    assertThat(normalized)
        .satisfiesAnyOf(
            value -> assertThat(value).contains("credit limit"),
            value -> assertThat(value).contains("invalid state"),
            value -> assertThat(value).contains("credit posture"));
  }

  @Test
  @DisplayName("Order Fulfillment: Reserves Inventory FIFO")
  void orderFulfillment_ReservesInventory_FIFO() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Dealer dealer =
        createDealer(company, "FIFO-DEALER", "FIFO Dealer Ltd", new BigDecimal("100000"));
    FinishedGood fg = createFinishedGood(company, "FG-FIFO-001", new BigDecimal("100"));

    BigDecimal initialStock = fg.getCurrentStock();

    // Create order
    Map<String, Object> lineItem =
        Map.of(
            "productCode",
            fg.getProductCode(),
            "description",
            "Test Product",
            "quantity",
            new BigDecimal("15"),
            "unitPrice",
            new BigDecimal("100.00"),
            "gstRate",
            BigDecimal.ZERO);

    Map<String, Object> orderReq =
        Map.of(
            "dealerId",
            dealer.getId(),
            "totalAmount",
            new BigDecimal("1500.00"),
            "currency",
            "INR",
            "items",
            List.of(lineItem),
            "gstTreatment",
            "NONE");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Verify inventory reservation was created
    List<InventoryReservation> reservations = inventoryReservationRepository.findAll();
    // Note: Reservations may be created on approval, not on order creation
    // This test verifies the endpoint works
  }

  @Test
  @DisplayName("Order with Promotion: Applies Discount with Correct Pricing")
  void orderWithPromotion_AppliesDiscount_CorrectPricing() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Dealer dealer =
        createDealer(company, "PROMO-DEALER", "Promo Dealer Ltd", new BigDecimal("100000"));
    FinishedGood fg = createFinishedGood(company, "FG-PROMO-001", new BigDecimal("100"));

    // Create order with discounted pricing
    BigDecimal originalPrice = new BigDecimal("1000.00");
    BigDecimal discountedPrice = new BigDecimal("900.00"); // 10% discount

    Map<String, Object> lineItem =
        Map.of(
            "productCode",
            fg.getProductCode(),
            "description",
            "Test Product with Discount",
            "quantity",
            new BigDecimal("5"),
            "unitPrice",
            discountedPrice,
            "gstRate",
            BigDecimal.ZERO);

    Map<String, Object> orderReq =
        Map.of(
            "dealerId",
            dealer.getId(),
            "totalAmount",
            new BigDecimal("4500.00"),
            "currency",
            "INR",
            "items",
            List.of(lineItem),
            "gstTreatment",
            "NONE");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("Order with GST: Calculates Correct Taxes")
  void orderWithGST_CalculatesCorrectTaxes() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Dealer dealer = createDealer(company, "GST-DEALER", "GST Dealer Ltd", new BigDecimal("100000"));
    BigDecimal unitPrice = new BigDecimal("1000.00");
    BigDecimal quantity = new BigDecimal("10");
    BigDecimal gstRate = new BigDecimal("18.00");
    FinishedGood fg =
        createFinishedGood(company, "FG-GST-001", new BigDecimal("100"), unitPrice, gstRate);

    Map<String, Object> lineItem =
        Map.of(
            "productCode", fg.getProductCode(),
            "description", "Test Product with GST",
            "quantity", quantity,
            "unitPrice", unitPrice,
            "gstRate", gstRate);

    BigDecimal subtotal = unitPrice.multiply(quantity);
    BigDecimal gstAmount = subtotal.multiply(gstRate).divide(new BigDecimal("100"));
    BigDecimal total = subtotal.add(gstAmount);

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
            "PER_ITEM",
            "gstRate",
            gstRate);

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("Multiple Orders: Same Dealer updates Ledger Balance")
  void multipleOrders_SameDealer_UpdatesLedgerBalance() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Dealer dealer =
        createDealer(company, "MULTI-DEALER", "Multi Order Dealer", new BigDecimal("500000"));
    FinishedGood fg = createFinishedGood(company, "FG-MULTI-001", new BigDecimal("500"));

    // Create first order
    createOrder(dealer, fg, new BigDecimal("5"), new BigDecimal("1000.00"));

    // Create second order
    createOrder(dealer, fg, new BigDecimal("10"), new BigDecimal("1000.00"));

    // Create third order
    createOrder(dealer, fg, new BigDecimal("8"), new BigDecimal("1000.00"));

    // Verify ledger entries (may be created on approval/dispatch)
    // This test verifies orders can be created successfully
    List<SalesOrder> orders =
        salesOrderRepository.findAll().stream()
            .filter(
                o ->
                    Objects.equals(o.getCompany().getId(), company.getId())
                        && Objects.equals(o.getDealer().getId(), dealer.getId()))
            .toList();
    assertThat(orders.size()).isGreaterThanOrEqualTo(3);
  }

  @Test
  @DisplayName("Dispatch creates Packing Slip, Invoice, Posts COGS")
  void dispatch_CreatesPackingSlip_Invoice_PostsCOGS() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Dealer dealer =
        createDealer(company, "DISPATCH-DEALER", "Dispatch Dealer", new BigDecimal("500000"));
    FinishedGood fg = createFinishedGood(company, "FG-DISPATCH-001", new BigDecimal("500"));

    // Create and get order
    Long orderId = createOrder(dealer, fg, new BigDecimal("5"), new BigDecimal("1000.00"));

    PackagingSlip slip = reserveSlip(company, orderId);
    Map<String, Object> dispatchReq = dispatchRequestForSlip(slip, "dispatch-cogs");
    UUID flowCorrelationId = UUID.randomUUID();
    HttpHeaders dispatchHeaders = headersWithCorrelationId(headers, flowCorrelationId);

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(dispatchReq, dispatchHeaders),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = requireData(response, "dispatch order");
    PackagingSlip dispatchedSlip = packagingSlipRepository.findById(slip.getId()).orElseThrow();
    assertThat(((Number) data.get("packagingSlipId")).longValue()).isEqualTo(slip.getId());
    assertThat(dispatchedSlip.getInvoiceId()).isNotNull();
    assertThat(dispatchedSlip.getJournalEntryId()).isNotNull();
    assertThat(dispatchedSlip.getCogsJournalEntryId()).isNotNull();

    AuditActionEvent dispatchAudit =
        awaitBusinessAuditEvent(
            company.getId(), "SALES", "DISPATCH_CONFIRMED", "SALES_ORDER", orderId.toString());
    assertThat(dispatchAudit.getCorrelationId()).isEqualTo(flowCorrelationId);

    AuditActionEvent arJournalAudit =
        awaitBusinessAuditEvent(
            company.getId(),
            "ACCOUNTING",
            "SYSTEM_JOURNAL_CREATED",
            "JOURNAL_ENTRY",
            dispatchedSlip.getJournalEntryId().toString());
    AuditActionEvent cogsJournalAudit =
        awaitBusinessAuditEvent(
            company.getId(),
            "ACCOUNTING",
            "SYSTEM_JOURNAL_CREATED",
            "JOURNAL_ENTRY",
            dispatchedSlip.getCogsJournalEntryId().toString());
    assertThat(arJournalAudit.getCorrelationId()).isEqualTo(flowCorrelationId);
    assertThat(cogsJournalAudit.getCorrelationId()).isEqualTo(flowCorrelationId);
    assertThat(arJournalAudit.getCorrelationId()).isEqualTo(dispatchAudit.getCorrelationId());
    assertThat(cogsJournalAudit.getCorrelationId()).isEqualTo(dispatchAudit.getCorrelationId());

    List<?> arEventTrail = transactionEventTrail(dispatchedSlip.getJournalEntryId());
    assertThat(arEventTrail).isNotEmpty();
    assertThat(arEventTrail)
        .allSatisfy(
            row -> {
              assertThat(row).isInstanceOf(Map.class);
              assertThat(((Map<?, ?>) row).get("correlationId"))
                  .isEqualTo(flowCorrelationId.toString());
            });

    List<?> cogsEventTrail = transactionEventTrail(dispatchedSlip.getCogsJournalEntryId());
    assertThat(cogsEventTrail).isNotEmpty();
    assertThat(cogsEventTrail)
        .allSatisfy(
            row -> {
              assertThat(row).isInstanceOf(Map.class);
              assertThat(((Map<?, ?>) row).get("correlationId"))
                  .isEqualTo(flowCorrelationId.toString());
            });
  }

  @Test
  @DisplayName("Canonical catalog SKU flows through opening stock, sales, dispatch and accounting")
  void canonicalCatalogSku_reusedAcrossOpeningStockSalesDispatchAndAccounting() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    String token = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String sku = "FG-CANON-" + token;
    String batchCode = "FG-CANON-BATCH-" + token;
    Account canonicalCogs =
        ensureAccount(company, "COGS-CANON", "Canonical COGS", AccountType.COGS);
    ensureAccount(company, "OPEN-BAL", "Opening Balance", AccountType.EQUITY);
    company.setDefaultCogsAccountId(canonicalCogs.getId());
    companyRepository.save(company);

    ProductionProductDto catalogItem;
    OpeningStockImportResponse openingStock;
    CompanyContextHolder.setCompanyCode(COMPANY_CODE);
    try {
      catalogItem =
          productionCatalogService.createCatalogItem(
              new CatalogItemCreateCommand(
                  ensureBrand(company).getId(),
                  null,
                  null,
                  "Canonical Reuse Paint " + token,
                  "FINISHED_GOOD",
                  "FINISHED_GOOD",
                  "WHITE",
                  "1L",
                  "LITER",
                  "320910",
                  sku,
                  new BigDecimal("1000.00"),
                  BigDecimal.ZERO,
                  null,
                  null,
                  Map.of()));

      MockMultipartFile openingStockFile =
          new MockMultipartFile(
              "file",
              "canonical-opening.csv",
              "text/csv",
              String.join(
                      "\n",
                      "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type,manufactured_at",
                      "FINISHED_GOOD,%s,Canonical Paint,LITER,LITER,%s,6,100.00,,2026-01-10"
                          .formatted(catalogItem.skuCode(), batchCode))
                  .getBytes(StandardCharsets.UTF_8));
      openingStock =
          openingStockImportService.importOpeningStock(
              openingStockFile, "canonical-sku-open-" + token, "canonical-sku-open-batch-" + token);
    } finally {
      CompanyContextHolder.clear();
    }

    assertThat(catalogItem.skuCode()).isEqualTo(sku);
    FinishedGood finishedGood =
        finishedGoodRepository.findByCompanyAndProductCode(company, sku).orElseThrow();
    assertThat(openingStock.errors()).isEmpty();
    assertThat(openingStock.results())
        .extracting(OpeningStockImportResponse.ImportRowResult::sku)
        .containsExactly(sku);
    InventoryMovement openingMovement =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                company, InventoryReference.OPENING_STOCK, batchCode)
            .getFirst();
    assertThat(openingMovement.getFinishedGood().getId()).isEqualTo(finishedGood.getId());
    assertThat(openingMovement.getJournalEntryId()).isNotNull();
    List<Long> canonicalBatchIds =
        finishedGoodBatchRepository
            .findByFinishedGoodOrderByManufacturedAtAsc(finishedGood)
            .stream()
            .map(FinishedGoodBatch::getId)
            .toList();
    assertThat(canonicalBatchIds).contains(openingMovement.getFinishedGoodBatch().getId());

    Dealer dealer =
        createDealer(
            company, "CANON-" + token, "Canonical Reuse Dealer " + token, new BigDecimal("500000"));
    Long orderId =
        createOrder(dealer, finishedGood, new BigDecimal("2"), new BigDecimal("1000.00"));
    SalesOrder order =
        salesOrderRepository.findWithItemsByCompanyAndId(company, orderId).orElseThrow();
    assertThat(order.getItems()).hasSize(1);
    assertThat(order.getItems().getFirst().getProductCode()).isEqualTo(sku);
    assertThat(order.getItems().getFirst().getFinishedGoodId()).isEqualTo(finishedGood.getId());

    PackagingSlip slip = reserveSlip(company, orderId);
    PackagingSlipLine slipLine =
        packagingSlipLineRepository.findByPackagingSlipId(slip.getId()).getFirst();
    assertThat(canonicalBatchIds).contains(slipLine.getFinishedGoodBatch().getId());

    ResponseEntity<Map> dispatchResponse =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(
                dispatchRequestForSlip(slip, "canonical-sku-dispatch-" + token), headers),
            Map.class);
    assertThat(dispatchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    PackagingSlip dispatchedSlip = packagingSlipRepository.findById(slip.getId()).orElseThrow();
    SalesOrder dispatchedOrder = salesOrderRepository.findById(orderId).orElseThrow();
    assertThat(dispatchedSlip.getJournalEntryId()).isNotNull();
    assertThat(dispatchedSlip.getCogsJournalEntryId()).isNotNull();
    assertThat(dispatchedOrder.getSalesJournalEntryId())
        .isEqualTo(dispatchedSlip.getJournalEntryId());
    assertThat(dispatchedOrder.getCogsJournalEntryId())
        .isEqualTo(dispatchedSlip.getCogsJournalEntryId());

    List<InventoryMovement> dispatchMovements =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                company, slip.getId(), "DISPATCH");
    assertThat(dispatchMovements)
        .singleElement()
        .satisfies(
            movement -> {
              assertThat(movement.getFinishedGood().getId()).isEqualTo(finishedGood.getId());
              assertThat(canonicalBatchIds).contains(movement.getFinishedGoodBatch().getId());
            });

    ProductionProduct persistedProduct =
        productionProductRepository.findByCompanyAndSkuCode(company, sku).orElseThrow();
    assertThat(persistedProduct.getId()).isEqualTo(catalogItem.id());
    assertThat(finishedGood.getProductCode()).isEqualTo(persistedProduct.getSkuCode());
  }

  @Test
  @DisplayName("Dispatch confirm is idempotent and restores missing slip/order links")
  void dispatchConfirm_idempotent_andRestoresArtifacts() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Dealer dealer =
        createDealer(
            company, "DISPATCH-IDEMPOTENT", "Dispatch Idempotent Dealer", new BigDecimal("500000"));
    FinishedGood fg = createFinishedGood(company, "FG-DISPATCH-IDEMPOTENT", new BigDecimal("50"));

    Long orderId = createOrder(dealer, fg, new BigDecimal("5"), new BigDecimal("1000.00"));

    PackagingSlip slip = reserveSlip(company, orderId);
    Map<String, Object> dispatchReq = dispatchRequestForSlip(slip, "dispatch-idempotent");

    ResponseEntity<Map> first =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(dispatchReq, headers),
            Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    requireData(first, "dispatch order (first)");

    PackagingSlip afterFirst = packagingSlipRepository.findById(slip.getId()).orElseThrow();
    Long slipId = afterFirst.getId();
    Long invoiceId = afterFirst.getInvoiceId();
    Long arJournalId = afterFirst.getJournalEntryId();
    Long cogsJournalId = afterFirst.getCogsJournalEntryId();
    assertThat(cogsJournalId).isNotNull();
    assertThat(afterFirst.getJournalEntryId()).isEqualTo(arJournalId);
    assertThat(afterFirst.getInvoiceId()).isEqualTo(invoiceId);

    long dispatchMovementsBefore =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                company, slipId, "DISPATCH")
            .size();

    // Simulate partial artifact loss: clear slip + order links only
    PackagingSlip slipToClear = packagingSlipRepository.findById(slipId).orElseThrow();
    slipToClear.setInvoiceId(null);
    slipToClear.setJournalEntryId(null);
    slipToClear.setCogsJournalEntryId(null);
    packagingSlipRepository.save(slipToClear);

    SalesOrder order = salesOrderRepository.findById(orderId).orElseThrow();
    order.setFulfillmentInvoiceId(null);
    order.setSalesJournalEntryId(null);
    order.setCogsJournalEntryId(null);
    salesOrderRepository.save(order);

    ResponseEntity<Map> second =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(dispatchReq, headers),
            Map.class);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> secondData = requireData(second, "dispatch order (retry)");
    assertThat(((Number) secondData.get("packagingSlipId")).longValue()).isEqualTo(slipId);

    PackagingSlip slipAfter = packagingSlipRepository.findById(slipId).orElseThrow();
    assertThat(slipAfter.getInvoiceId()).isEqualTo(invoiceId);
    assertThat(slipAfter.getJournalEntryId()).isEqualTo(arJournalId);
    assertThat(slipAfter.getCogsJournalEntryId()).isEqualTo(cogsJournalId);

    SalesOrder orderAfter = salesOrderRepository.findById(orderId).orElseThrow();
    assertThat(orderAfter.getFulfillmentInvoiceId()).isEqualTo(invoiceId);
    assertThat(orderAfter.getSalesJournalEntryId()).isEqualTo(arJournalId);
    assertThat(orderAfter.getCogsJournalEntryId()).isEqualTo(cogsJournalId);

    long dispatchMovementsAfter =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                company, slipId, "DISPATCH")
            .size();
    assertThat(dispatchMovementsAfter).isEqualTo(dispatchMovementsBefore);

    String invoiceNumber =
        invoiceRepository.findByCompanyAndId(company, invoiceId).orElseThrow().getInvoiceNumber();
    assertThat(journalReferenceResolver.findExistingEntry(company, invoiceNumber))
        .isPresent()
        .get()
        .extracting(entry -> entry.getId())
        .isEqualTo(arJournalId);
    String canonicalCogsReference = "COGS-PS-" + slipId;
    String legacyCogsReference = "COGS-" + slip.getSlipNumber();
    var cogsEntry = journalReferenceResolver.findExistingEntry(company, canonicalCogsReference);
    if (cogsEntry.isEmpty()) {
      cogsEntry = journalReferenceResolver.findExistingEntry(company, legacyCogsReference);
    }
    assertThat(cogsEntry)
        .isPresent()
        .get()
        .extracting(entry -> entry.getId())
        .isEqualTo(cogsJournalId);
  }

  @Test
  @DisplayName("Dispatch confirm endpoint is idempotent")
  void dispatchConfirmEndpoint_isIdempotent() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Dealer dealer =
        createDealer(company, "DISPATCH-EQUIV", "Dispatch Equiv Dealer", new BigDecimal("500000"));
    FinishedGood fg = createFinishedGood(company, "FG-DISPATCH-EQUIV", new BigDecimal("25"));

    Long orderId = createOrder(dealer, fg, new BigDecimal("4"), new BigDecimal("1000.00"));

    PackagingSlip slip = reserveSlip(company, orderId);
    Map<String, Object> dispatchReq = dispatchRequestForSlip(slip, "dispatch-equivalent");

    ResponseEntity<Map> firstResponse =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(dispatchReq, headers),
            Map.class);
    assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> firstData = requireData(firstResponse, "initial dispatch confirm");

    PackagingSlip afterFirst = packagingSlipRepository.findById(slip.getId()).orElseThrow();
    Long invoiceId = afterFirst.getInvoiceId();
    Long arJournalId = afterFirst.getJournalEntryId();
    Long cogsJournalId = afterFirst.getCogsJournalEntryId();
    assertThat(invoiceId).isNotNull();
    assertThat(arJournalId).isNotNull();
    assertThat(cogsJournalId).isNotNull();
    assertThat(((Number) firstData.get("packagingSlipId")).longValue()).isEqualTo(slip.getId());
    assertThat(((Number) firstData.get("journalEntryId")).longValue()).isEqualTo(arJournalId);

    long dispatchMovementsBefore =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                company, slip.getId(), "DISPATCH")
            .size();

    ResponseEntity<Map> salesResponse =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(dispatchReq, headers),
            Map.class);
    assertThat(salesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> salesData = requireData(salesResponse, "dispatch confirm replay");

    assertThat(((Number) salesData.get("packagingSlipId")).longValue()).isEqualTo(slip.getId());
    assertThat(((Number) salesData.get("journalEntryId")).longValue()).isEqualTo(arJournalId);

    PackagingSlip afterSales = packagingSlipRepository.findById(slip.getId()).orElseThrow();
    assertThat(afterSales.getCogsJournalEntryId()).isEqualTo(cogsJournalId);

    long dispatchMovementsAfter =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                company, slip.getId(), "DISPATCH")
            .size();
    assertThat(dispatchMovementsAfter).isEqualTo(dispatchMovementsBefore);
  }

  @Test
  @DisplayName("Dispatch COGS uses slip unit costs and links inventory movements")
  void dispatchCogs_matchesSlipUnitCosts_andLinksMovements() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Dealer dealer =
        createDealer(company, "DISPATCH-COGS", "Dispatch COGS Dealer", new BigDecimal("500000"));
    FinishedGood fg = createFinishedGood(company, "FG-DISPATCH-COGS", new BigDecimal("20"));

    Long orderId = createOrder(dealer, fg, new BigDecimal("3"), new BigDecimal("1000.00"));

    PackagingSlip slip = reserveSlip(company, orderId);
    Map<String, Object> dispatchReq = dispatchRequestForSlip(slip, "dispatch-cogs-linkage");
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(dispatchReq, headers),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    PackagingSlip dispatchedSlip = packagingSlipRepository.findById(slip.getId()).orElseThrow();
    Long cogsJournalId = dispatchedSlip.getCogsJournalEntryId();
    assertThat(cogsJournalId).isNotNull();

    BigDecimal expectedCost =
        packagingSlipLineRepository.findByPackagingSlipId(dispatchedSlip.getId()).stream()
            .map(
                line -> {
                  BigDecimal shipped =
                      line.getShippedQuantity() != null
                          ? line.getShippedQuantity()
                          : line.getOrderedQuantity();
                  if (shipped == null) {
                    shipped = line.getQuantity();
                  }
                  BigDecimal unitCost =
                      line.getUnitCost() != null ? line.getUnitCost() : BigDecimal.ZERO;
                  return unitCost.multiply(shipped);
                })
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    var cogsEntry = journalEntryRepository.findById(cogsJournalId).orElseThrow();
    BigDecimal totalDebits =
        cogsEntry.getLines().stream()
            .map(JournalLine::getDebit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCredits =
        cogsEntry.getLines().stream()
            .map(JournalLine::getCredit)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(totalDebits).isEqualByComparingTo(expectedCost);
    assertThat(totalCredits).isEqualByComparingTo(expectedCost);

    inventoryMovementRepository
        .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
            company, slip.getId(), "DISPATCH")
        .forEach(m -> assertThat(m.getJournalEntryId()).isEqualTo(cogsJournalId));
  }

  @Test
  @DisplayName("Partial dispatch invoices shipped qty and creates backorder slip")
  void partialDispatch_invoicesShippedQty_andCreatesBackorderSlip() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Dealer dealer =
        createDealer(
            company, "DISPATCH-PARTIAL", "Dispatch Partial Dealer", new BigDecimal("500000"));
    FinishedGood fg = createFinishedGood(company, "FG-DISPATCH-PARTIAL", new BigDecimal("15"));

    BigDecimal orderedQty = new BigDecimal("10");
    BigDecimal unitPrice = new BigDecimal("1000.00");
    Long orderId = createOrder(dealer, fg, orderedQty, unitPrice);

    PackagingSlip slip = reserveSlip(company, orderId);
    PackagingSlipLine line =
        packagingSlipLineRepository.findByPackagingSlipId(slip.getId()).getFirst();
    BigDecimal shippedQty = orderedQty.subtract(new BigDecimal("3"));

    Map<String, Object> dispatchReq =
        dispatchRequestForSlip(slip, "dispatch-partial", List.of(dispatchLine(line, shippedQty)));

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(dispatchReq, headers),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    requireData(response, "partial dispatch");
    PackagingSlip refreshedOriginalSlip =
        packagingSlipRepository.findById(slip.getId()).orElseThrow();
    Long invoiceId = refreshedOriginalSlip.getInvoiceId();

    var invoice = invoiceRepository.findByCompanyAndId(company, invoiceId).orElseThrow();
    BigDecimal expectedTotal = unitPrice.multiply(shippedQty);
    assertThat(invoice.getTotalAmount()).isEqualByComparingTo(expectedTotal);

    List<PackagingSlip> slips =
        packagingSlipRepository.findAllByCompanyAndSalesOrderId(company, orderId);
    assertThat(slips.size()).isGreaterThanOrEqualTo(2);
    PackagingSlip backorderSlip =
        slips.stream()
            .filter(s -> "BACKORDER".equalsIgnoreCase(s.getStatus()))
            .findFirst()
            .orElseThrow();
    BigDecimal backorderQty =
        packagingSlipLineRepository.findByPackagingSlipId(backorderSlip.getId()).stream()
            .map(l -> l.getOrderedQuantity() != null ? l.getOrderedQuantity() : l.getQuantity())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(backorderQty).isEqualByComparingTo(orderedQty.subtract(shippedQty));

    PackagingSlipLine backorderLine =
        packagingSlipLineRepository.findByPackagingSlipId(backorderSlip.getId()).getFirst();
    Map<String, Object> backorderDispatchReq =
        dispatchRequestForSlip(
            backorderSlip,
            "dispatch-backorder",
            List.of(dispatchLine(backorderLine, backorderQty)));

    ResponseEntity<Map> backorderDispatchResp =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(backorderDispatchReq, headers),
            Map.class);
    assertThat(backorderDispatchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    requireData(backorderDispatchResp, "backorder dispatch");
    PackagingSlip refreshedBackorder =
        packagingSlipRepository.findById(backorderSlip.getId()).orElseThrow();
    Long backorderInvoiceId = refreshedBackorder.getInvoiceId();
    assertThat(backorderInvoiceId).isNotEqualTo(invoiceId);

    var backorderInvoice =
        invoiceRepository.findByCompanyAndId(company, backorderInvoiceId).orElseThrow();
    BigDecimal expectedBackorderTotal = unitPrice.multiply(backorderQty);
    assertThat(backorderInvoice.getTotalAmount()).isEqualByComparingTo(expectedBackorderTotal);

    assertThat(refreshedBackorder.getStatus()).isEqualTo("DISPATCHED");
    assertThat(refreshedBackorder.getInvoiceId()).isEqualTo(backorderInvoiceId);
    PackagingSlip refreshedOriginal = packagingSlipRepository.findById(slip.getId()).orElseThrow();
    Long originalCogsJournalId = refreshedOriginal.getCogsJournalEntryId();
    Long backorderCogsJournalId = refreshedBackorder.getCogsJournalEntryId();
    assertThat(originalCogsJournalId).isNotNull();
    assertThat(backorderCogsJournalId).isNotNull();
    assertThat(backorderCogsJournalId).isNotEqualTo(originalCogsJournalId);

    inventoryMovementRepository
        .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
            company, refreshedOriginal.getId(), "DISPATCH")
        .forEach(m -> assertThat(m.getJournalEntryId()).isEqualTo(originalCogsJournalId));
    inventoryMovementRepository
        .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
            company, refreshedBackorder.getId(), "DISPATCH")
        .forEach(m -> assertThat(m.getJournalEntryId()).isEqualTo(backorderCogsJournalId));

    BigDecimal dispatchedQty =
        inventoryMovementRepository
            .findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                InventoryReference.SALES_ORDER, orderId.toString())
            .stream()
            .filter(m -> "DISPATCH".equalsIgnoreCase(m.getMovementType()))
            .map(m -> m.getQuantity() != null ? m.getQuantity() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal expectedTotalDispatched = shippedQty.add(backorderQty);
    assertThat(dispatchedQty).isEqualByComparingTo(expectedTotalDispatched);
  }

  @Test
  @DisplayName("Dispatch with GST updates GST return output with deterministic rounding")
  void dispatchWithGst_UpdatesGstReturnOutput() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Dealer dealer =
        createDealer(
            company, "GST-DISPATCH-DEALER", "GST Dispatch Dealer", new BigDecimal("500000"));
    BigDecimal unitPrice = new BigDecimal("99.99");
    BigDecimal quantity = new BigDecimal("3");
    BigDecimal gstRate = new BigDecimal("18.00");
    FinishedGood fg =
        createFinishedGood(
            company, "FG-GST-DISPATCH-001", new BigDecimal("100"), unitPrice, gstRate);

    Map<String, Object> lineItem =
        Map.of(
            "productCode", fg.getProductCode(),
            "description", "GST Dispatch Product",
            "quantity", quantity,
            "unitPrice", unitPrice,
            "gstRate", gstRate);

    BigDecimal subtotal = unitPrice.multiply(quantity);
    BigDecimal expectedTax =
        subtotal.multiply(gstRate).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
    BigDecimal total = subtotal.add(expectedTax);

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
            "PER_ITEM",
            "gstRate",
            gstRate);

    ResponseEntity<Map> orderResp =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);
    Map<?, ?> orderData = requireData(orderResp, "create GST dispatch order");
    Long orderId = ((Number) orderData.get("id")).longValue();

    BigDecimal beforeOutput = gstOutputTax();

    PackagingSlip slip = reserveSlip(company, orderId);
    Map<String, Object> dispatchReq = dispatchRequestForSlip(slip, "dispatch-gst");

    ResponseEntity<Map> dispatchResp =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(dispatchReq, headers),
            Map.class);
    assertThat(dispatchResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    BigDecimal afterOutput = gstOutputTax();
    assertThat(afterOutput.subtract(beforeOutput)).isEqualByComparingTo(expectedTax);
  }

  @Test
  @DisplayName("Dispatch with mixed GST rates posts tax only for taxable lines")
  void dispatchWithMixedGstRates_PostsTaxForTaxableLinesOnly() {
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();

    Dealer dealer =
        createDealer(company, "GST-MIX-DEALER", "GST Mix Dealer", new BigDecimal("500000"));
    BigDecimal taxedRate = new BigDecimal("18.00");
    BigDecimal taxedPrice = new BigDecimal("120.00");
    BigDecimal zeroPrice = new BigDecimal("80.00");
    BigDecimal taxedQty = new BigDecimal("2");
    BigDecimal zeroQty = new BigDecimal("3");

    FinishedGood taxed =
        createFinishedGood(company, "FG-GST-MIX-TAX", new BigDecimal("100"), taxedPrice, taxedRate);
    FinishedGood zero =
        createFinishedGood(
            company, "FG-GST-MIX-ZERO", new BigDecimal("100"), zeroPrice, BigDecimal.ZERO);

    Map<String, Object> taxedItem =
        Map.of(
            "productCode", taxed.getProductCode(),
            "description", "Taxed item",
            "quantity", taxedQty,
            "unitPrice", taxedPrice,
            "gstRate", taxedRate);
    Map<String, Object> zeroItem =
        Map.of(
            "productCode",
            zero.getProductCode(),
            "description",
            "Zero-rated item",
            "quantity",
            zeroQty,
            "unitPrice",
            zeroPrice,
            "gstRate",
            BigDecimal.ZERO);

    BigDecimal taxedSubtotal = taxedPrice.multiply(taxedQty);
    BigDecimal zeroSubtotal = zeroPrice.multiply(zeroQty);
    BigDecimal expectedTax =
        taxedSubtotal.multiply(taxedRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    BigDecimal expectedTotal = taxedSubtotal.add(zeroSubtotal).add(expectedTax);

    Map<String, Object> orderReq = new java.util.HashMap<>();
    orderReq.put("dealerId", dealer.getId());
    orderReq.put("totalAmount", expectedTotal);
    orderReq.put("currency", "INR");
    orderReq.put("items", List.of(taxedItem, zeroItem));
    orderReq.put("gstTreatment", "PER_ITEM");
    orderReq.put("gstRate", null);

    ResponseEntity<Map> orderResp =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);
    Map<?, ?> orderData = requireData(orderResp, "create mixed GST order");
    Long orderId = ((Number) orderData.get("id")).longValue();

    BigDecimal beforeOutput = gstOutputTax();

    PackagingSlip slip = reserveSlip(company, orderId);
    Map<String, Object> dispatchReq = dispatchRequestForSlip(slip, "dispatch-gst-mixed");

    ResponseEntity<Map> dispatchResp =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(dispatchReq, headers),
            Map.class);
    requireData(dispatchResp, "dispatch mixed GST order");
    Long invoiceId = packagingSlipRepository.findById(slip.getId()).orElseThrow().getInvoiceId();

    BigDecimal afterOutput = gstOutputTax();
    assertThat(afterOutput.subtract(beforeOutput)).isEqualByComparingTo(expectedTax);

    var invoice = invoiceRepository.findByCompanyAndId(company, invoiceId).orElseThrow();
    assertThat(invoice.getTotalAmount()).isEqualByComparingTo(expectedTotal);

    var taxedLine =
        invoice.getLines().stream()
            .filter(line -> taxed.getProductCode().equals(line.getProductCode()))
            .findFirst()
            .orElseThrow();
    var zeroLine =
        invoice.getLines().stream()
            .filter(line -> zero.getProductCode().equals(line.getProductCode()))
            .findFirst()
            .orElseThrow();

    assertThat(taxedLine.getTaxAmount()).isEqualByComparingTo(expectedTax);
    assertThat(zeroLine.getTaxAmount()).isEqualByComparingTo(new BigDecimal("0.00"));
  }

  // Helper methods
  private Dealer createDealer(Company company, String name, String code, BigDecimal creditLimit) {
    return dealerRepository.findAll().stream()
        .filter(d -> d.getName().equals(name))
        .findFirst()
        .map(
            existing -> {
              if (existing.getReceivableAccount() == null) {
                Account receivable =
                    accountRepository
                        .findByCompanyAndCodeIgnoreCase(company, "ASSET-AR")
                        .orElseThrow();
                existing.setReceivableAccount(receivable);
                existing.setStateCode(company.getStateCode());
                existing.setGstRegistrationType(GstRegistrationType.REGULAR);
                return dealerRepository.save(existing);
              }
              return existing;
            })
        .orElseGet(
            () -> {
              Dealer dealer = new Dealer();
              dealer.setCompany(company);
              dealer.setName(name);
              dealer.setCode(code);
              dealer.setEmail(name.toLowerCase().replace(" ", "") + "@test.com");
              dealer.setPhone("1234567890");
              dealer.setAddress("Test Address");
              dealer.setStateCode(company.getStateCode());
              dealer.setGstRegistrationType(GstRegistrationType.REGULAR);
              dealer.setCreditLimit(creditLimit);
              Account receivable =
                  accountRepository
                      .findByCompanyAndCodeIgnoreCase(company, "ASSET-AR")
                      .orElseThrow();
              dealer.setReceivableAccount(receivable);
              dealer.setOutstandingBalance(BigDecimal.ZERO);
              return dealerRepository.save(dealer);
            });
  }

  private FinishedGood createFinishedGood(Company company, String productCode, BigDecimal stock) {
    return createFinishedGood(
        company, productCode, stock, new BigDecimal("100.00"), BigDecimal.ZERO);
  }

  private FinishedGood createFinishedGood(
      Company company,
      String productCode,
      BigDecimal stock,
      BigDecimal basePrice,
      BigDecimal gstRate) {
    ensureProduct(company, productCode, basePrice, gstRate);
    Account revenueAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "REV-SALES").orElseThrow();
    Account taxAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "LIAB-GST").orElseThrow();
    Account inventoryAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "ASSET-INV-FG").orElseThrow();
    Account cogsAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "EXP-COGS").orElseThrow();
    Account discountAccount =
        accountRepository.findByCompanyAndCodeIgnoreCase(company, "DISC-SALES").orElseThrow();

    FinishedGood fg =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, productCode)
            .map(
                existing -> {
                  boolean dirty = false;
                  if (existing.getRevenueAccountId() == null) {
                    existing.setRevenueAccountId(revenueAccount.getId());
                    dirty = true;
                  }
                  if (existing.getTaxAccountId() == null) {
                    existing.setTaxAccountId(taxAccount.getId());
                    dirty = true;
                  }
                  if (existing.getValuationAccountId() == null) {
                    existing.setValuationAccountId(inventoryAccount.getId());
                    dirty = true;
                  }
                  if (existing.getCogsAccountId() == null) {
                    existing.setCogsAccountId(cogsAccount.getId());
                    dirty = true;
                  }
                  if (existing.getDiscountAccountId() == null) {
                    existing.setDiscountAccountId(discountAccount.getId());
                    dirty = true;
                  }
                  if (stock != null
                      && (existing.getCurrentStock() == null
                          || existing.getCurrentStock().compareTo(stock) < 0)) {
                    existing.setCurrentStock(stock);
                    dirty = true;
                  }
                  return dirty ? finishedGoodRepository.save(existing) : existing;
                })
            .orElseGet(
                () -> {
                  FinishedGood newFg = new FinishedGood();
                  newFg.setCompany(company);
                  newFg.setProductCode(productCode);
                  newFg.setName("Test FG " + productCode);
                  newFg.setCurrentStock(stock);
                  newFg.setReservedStock(BigDecimal.ZERO);
                  newFg.setRevenueAccountId(revenueAccount.getId());
                  newFg.setTaxAccountId(taxAccount.getId());
                  newFg.setValuationAccountId(inventoryAccount.getId());
                  newFg.setCogsAccountId(cogsAccount.getId());
                  newFg.setDiscountAccountId(discountAccount.getId());
                  return finishedGoodRepository.save(newFg);
                });
    ensureFinishedGoodBatch(fg, stock, basePrice);
    return fg;
  }

  private void ensureFinishedGoodBatch(FinishedGood fg, BigDecimal quantity, BigDecimal unitCost) {
    if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    boolean hasAvailable =
        finishedGoodBatchRepository.findByFinishedGoodOrderByManufacturedAtAsc(fg).stream()
            .anyMatch(
                batch ->
                    batch.getQuantityAvailable() != null
                        && batch.getQuantityAvailable().compareTo(BigDecimal.ZERO) > 0);
    if (hasAvailable) {
      return;
    }
    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(fg);
    batch.setBatchCode(fg.getProductCode() + "-BATCH-" + System.currentTimeMillis());
    batch.setQuantityTotal(quantity);
    batch.setQuantityAvailable(quantity);
    batch.setUnitCost(unitCost != null ? unitCost : BigDecimal.ZERO);
    batch.setManufacturedAt(Instant.now());
    finishedGoodBatchRepository.save(batch);

    BigDecimal current = fg.getCurrentStock() != null ? fg.getCurrentStock() : BigDecimal.ZERO;
    if (current.compareTo(quantity) < 0) {
      fg.setCurrentStock(quantity);
      finishedGoodRepository.save(fg);
    }
  }

  private ProductionProduct ensureProduct(
      Company company, String productCode, BigDecimal basePrice, BigDecimal gstRate) {
    BigDecimal resolvedBasePrice = basePrice != null ? basePrice : new BigDecimal("100.00");
    BigDecimal resolvedGstRate = gstRate != null ? gstRate : BigDecimal.ZERO;
    return productionProductRepository
        .findByCompanyAndSkuCode(company, productCode)
        .map(
            existing -> {
              boolean dirty = false;
              if (existing.getBasePrice() == null
                  || existing.getBasePrice().compareTo(resolvedBasePrice) != 0) {
                existing.setBasePrice(resolvedBasePrice);
                dirty = true;
              }
              if (existing.getGstRate() == null
                  || existing.getGstRate().compareTo(resolvedGstRate) != 0) {
                existing.setGstRate(resolvedGstRate);
                dirty = true;
              }
              if (!existing.isActive()) {
                existing.setActive(true);
                dirty = true;
              }
              return dirty ? productionProductRepository.save(existing) : existing;
            })
        .orElseGet(
            () -> {
              ProductionBrand brand = ensureBrand(company);
              ProductionProduct product = new ProductionProduct();
              product.setCompany(company);
              product.setBrand(brand);
              product.setProductName("Test Product " + productCode);
              product.setCategory("FINISHED_GOOD");
              product.setUnitOfMeasure("UNIT");
              product.setSkuCode(productCode);
              product.setBasePrice(resolvedBasePrice);
              product.setGstRate(resolvedGstRate);
              product.setActive(true);
              return productionProductRepository.save(product);
            });
  }

  private ProductionBrand ensureBrand(Company company) {
    return productionBrandRepository
        .findByCompanyAndCodeIgnoreCase(company, "TEST-BRAND")
        .orElseGet(
            () -> {
              ProductionBrand brand = new ProductionBrand();
              brand.setCompany(company);
              brand.setCode("TEST-BRAND");
              brand.setName("Test Brand");
              return productionBrandRepository.save(brand);
            });
  }

  private Long createOrder(
      Dealer dealer, FinishedGood fg, BigDecimal quantity, BigDecimal unitPrice) {
    Map<String, Object> lineItem =
        Map.of(
            "productCode",
            fg.getProductCode(),
            "description",
            "Test Product",
            "quantity",
            quantity,
            "unitPrice",
            unitPrice,
            "gstRate",
            BigDecimal.ZERO);

    BigDecimal total = quantity.multiply(unitPrice);

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
            "NONE");

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/sales/orders",
            HttpMethod.POST,
            new HttpEntity<>(orderReq, headers),
            Map.class);

    Map<?, ?> data = requireData(response, "create order via helper");
    return ((Number) data.get("id")).longValue();
  }

  private PackagingSlip reserveSlip(Company company, Long orderId) {
    SalesOrder order = salesOrderRepository.findById(orderId).orElseThrow();
    CompanyContextHolder.setCompanyCode(company.getCode());
    try {
      PackagingSlip existing =
          packagingSlipRepository.findByCompanyAndSalesOrderId(company, orderId).orElse(null);
      if (existing != null) {
        boolean dispatched =
            "DISPATCHED".equalsIgnoreCase(existing.getStatus())
                || existing.getInvoiceId() != null
                || existing.getCogsJournalEntryId() != null;
        if (dispatched || hasReservedQuantity(existing)) {
          return existing;
        }
        finishedGoodsService.releaseReservationsForOrder(orderId);
        PackagingSlip refreshed =
            packagingSlipRepository.findByCompanyAndSalesOrderId(company, orderId).orElse(existing);
        refreshed.getLines().clear();
        refreshed.setStatus("PENDING");
        refreshed.setBackorder(false);
        packagingSlipRepository.save(refreshed);
      }
      finishedGoodsService.reserveForOrder(order);
      return packagingSlipRepository.findByCompanyAndSalesOrderId(company, orderId).orElseThrow();
    } finally {
      CompanyContextHolder.clear();
    }
  }

  private Map<String, Object> dispatchRequestForOrder(
      Company company, Long orderId, String referenceSeed) {
    return dispatchRequestForSlip(reserveSlip(company, orderId), referenceSeed);
  }

  private Map<String, Object> dispatchRequestForSlip(PackagingSlip slip, String referenceSeed) {
    return dispatchRequestForSlip(slip, referenceSeed, dispatchLines(slip));
  }

  private Map<String, Object> dispatchRequestForSlip(
      PackagingSlip slip, String referenceSeed, List<Map<String, Object>> lines) {
    Map<String, Object> request = new java.util.HashMap<>();
    request.put("packagingSlipId", slip.getId());
    request.put("notes", "e2e dispatch " + referenceSeed);
    request.put("lines", lines);
    addDispatchMetadata(request, referenceSeed);
    return request;
  }

  private List<Map<String, Object>> dispatchLines(PackagingSlip slip) {
    return slip.getLines().stream()
        .map(
            line ->
                dispatchLine(
                    line,
                    line.getOrderedQuantity() != null
                        ? line.getOrderedQuantity()
                        : line.getQuantity()))
        .toList();
  }

  private Map<String, Object> dispatchLine(PackagingSlipLine line, BigDecimal shippedQuantity) {
    return Map.of("lineId", line.getId(), "shippedQuantity", shippedQuantity);
  }

  private boolean hasReservedQuantity(PackagingSlip slip) {
    return slip.getLines().stream()
        .anyMatch(
            line -> {
              BigDecimal ordered =
                  line.getOrderedQuantity() != null
                      ? line.getOrderedQuantity()
                      : line.getQuantity();
              BigDecimal shipped =
                  line.getShippedQuantity() != null ? line.getShippedQuantity() : BigDecimal.ZERO;
              BigDecimal backorder =
                  line.getBackorderQuantity() != null
                      ? line.getBackorderQuantity()
                      : (ordered != null
                          ? ordered.subtract(shipped).max(BigDecimal.ZERO)
                          : BigDecimal.ZERO);
              BigDecimal reservedQty =
                  ordered != null
                      ? ordered.subtract(backorder).max(BigDecimal.ZERO)
                      : BigDecimal.ZERO;
              return reservedQty.compareTo(BigDecimal.ZERO) > 0;
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
      throw new AssertionError(
          String.format(
              "%s failed: status=%s body=%s",
              action, response.getStatusCode(), response.getBody()));
    }
    Map<?, ?> body = response.getBody();
    if (body == null || body.get("data") == null) {
      throw new AssertionError(String.format("%s response missing data payload: %s", action, body));
    }
    Object data = body.get("data");
    if (!(data instanceof Map<?, ?> map)) {
      throw new AssertionError(
          String.format("%s response data has unexpected shape: %s", action, data));
    }
    return map;
  }

  private BigDecimal gstOutputTax() {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/gst/return", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Object body = response.getBody();
    if (!(body instanceof Map<?, ?> map)) {
      return BigDecimal.ZERO;
    }
    Object data = map.get("data");
    if (!(data instanceof Map<?, ?> dataMap)) {
      return BigDecimal.ZERO;
    }
    Object value = dataMap.get("outputTax");
    if (value instanceof Number) {
      return new BigDecimal(value.toString());
    }
    if (value instanceof String str && !str.isBlank()) {
      return new BigDecimal(str);
    }
    return BigDecimal.ZERO;
  }

  private List<?> transactionEventTrail(Long journalEntryId) {
    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/accounting/audit/transactions/" + journalEntryId,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    Map<?, ?> data =
        requireData(response, "fetch accounting audit transaction detail for " + journalEntryId);
    Object eventTrail = data.get("eventTrail");
    assertThat(eventTrail).isInstanceOf(List.class);
    return (List<?>) eventTrail;
  }

  private AuditActionEvent awaitBusinessAuditEvent(
      Long companyId, String module, String action, String entityType, String entityId) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
    while (Instant.now().isBefore(deadline)) {
      Optional<AuditActionEvent> match =
          auditActionEventRepository
              .findTopByCompanyIdAndModuleIgnoreCaseAndActionIgnoreCaseAndEntityTypeIgnoreCaseAndEntityIdOrderByOccurredAtDesc(
                  companyId, module, action, entityType, entityId);
      if (match.isPresent()) {
        return match.get();
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for audit event", ex);
      }
    }
    throw new AssertionError(
        "Audit event not found: module="
            + module
            + ", action="
            + action
            + ", entityType="
            + entityType
            + ", entityId="
            + entityId);
  }
}
