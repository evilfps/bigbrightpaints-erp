package com.bigbrightpaints.erp.e2e.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMappingRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackagingSizeMapping;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariant;
import com.bigbrightpaints.erp.modules.factory.domain.SizeVariantRepository;
import com.bigbrightpaints.erp.modules.factory.dto.PackingLineRequest;
import com.bigbrightpaints.erp.modules.factory.dto.PackingRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogDetailDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionLogRequest;
import com.bigbrightpaints.erp.modules.factory.service.PackingService;
import com.bigbrightpaints.erp.modules.factory.service.ProductionLogService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.DispatchConfirmRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@TestPropertySource(properties = "erp.auto-approval.enabled=false")
class DispatchOperationalBoundaryIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "DISPATCH-OPS";
  private static final String FACTORY_EMAIL = "factory.ops@test.com";
  private static final String FACTORY_PASSWORD = "factory123";
  private static final String SALES_EMAIL = "sales.ops@test.com";
  private static final String SALES_PASSWORD = "sales123";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private SalesOrderRepository salesOrderRepository;
  @Autowired private SalesService salesService;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private PackagingSlipRepository packagingSlipRepository;
  @Autowired private InventoryMovementRepository inventoryMovementRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private PackagingSizeMappingRepository packagingSizeMappingRepository;
  @Autowired private SizeVariantRepository sizeVariantRepository;
  @Autowired private ProductionLogService productionLogService;
  @Autowired private PackingService packingService;

  private Company company;
  private Dealer dealer;
  private Map<String, Account> accounts;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        FACTORY_EMAIL, FACTORY_PASSWORD, "Factory Ops", COMPANY_CODE, List.of("ROLE_FACTORY"));
    dataSeeder.ensureUser(
        SALES_EMAIL,
        SALES_PASSWORD,
        "Sales Ops",
        COMPANY_CODE,
        List.of("ROLE_SALES", "dispatch.confirm"));
    CompanyContextHolder.setCompanyId(COMPANY_CODE);
    company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    company.setBaseCurrency("INR");
    company.setTimezone("UTC");
    company.setStateCode("27");
    company = companyRepository.save(company);

    accounts = ensureAccounts(company);
    company = companyRepository.findById(company.getId()).orElseThrow();
    company.setDefaultInventoryAccountId(accounts.get("INV").getId());
    company.setDefaultCogsAccountId(accounts.get("COGS").getId());
    company.setDefaultRevenueAccountId(accounts.get("REV").getId());
    company.setDefaultDiscountAccountId(accounts.get("DISC").getId());
    company.setDefaultTaxAccountId(accounts.get("TAX").getId());
    company.setGstOutputTaxAccountId(accounts.get("TAX").getId());
    company = companyRepository.save(company);
    dealer = ensureDealer(company, "OPS-DEALER", "Ops Dealer", accounts.get("AR"));
    dealer.setStateCode("27");
    dealerRepository.save(dealer);
  }

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void
      factoryDispatchFlow_redactsCommercialFields_persistsLogistics_andReplaysWithoutDuplicateMovements() {
    String sku = "FG-OPS-" + UUID.randomUUID().toString().substring(0, 6);
    FinishedGood fg = createFinishedGood(sku);
    ensureCatalogProduct(fg, new BigDecimal("125.00"), new BigDecimal("18.00"));
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            fg.getId(),
            "BATCH-OPS",
            new BigDecimal("12"),
            new BigDecimal("70.00"),
            Instant.now(),
            null));

    SalesOrderRequest orderReq =
        new SalesOrderRequest(
            dealer.getId(),
            new BigDecimal("500.00"),
            "INR",
            null,
            List.of(
                new SalesOrderItemRequest(
                    fg.getProductCode(),
                    "Operational Item",
                    new BigDecimal("4"),
                    new BigDecimal("125.00"),
                    new BigDecimal("18.00"))),
            "EXCLUSIVE",
            null,
            null,
            null);
    Long orderId = salesService.createOrder(orderReq).id();
    SalesOrder order = salesOrderRepository.findById(orderId).orElseThrow();
    finishedGoodsService.reserveForOrder(order);
    PackagingSlip slip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, orderId).orElseThrow();

    HttpHeaders factoryHeaders = authHeaders(loginFactoryToken());
    HttpHeaders salesHeaders = authHeaders(loginSalesToken());
    ResponseEntity<Map> previewResponse =
        rest.exchange(
            "/api/v1/dispatch/preview/" + slip.getId(),
            HttpMethod.GET,
            new HttpEntity<>(factoryHeaders),
            Map.class);
    assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> previewData = requireData(previewResponse);
    assertThat(previewData.get("gstBreakdown")).isNull();
    assertThat(previewData.get("totalAvailableAmount")).isNull();
    Map<?, ?> previewLine = ((List<Map<?, ?>>) previewData.get("lines")).getFirst();
    assertThat(previewLine.get("unitPrice")).isNull();
    assertThat(previewLine.get("lineTotal")).isNull();

    ResponseEntity<Map> pendingResponse =
        rest.exchange(
            "/api/v1/dispatch/pending", HttpMethod.GET, new HttpEntity<>(factoryHeaders), Map.class);
    assertThat(pendingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<?, ?>> pendingSlips = requireListData(pendingResponse);
    assertThat(pendingSlips).hasSize(1);
    Map<?, ?> pendingSlip = pendingSlips.getFirst();
    assertThat(pendingSlip.get("id")).isEqualTo(slip.getId().intValue());
    assertThat(pendingSlip.get("journalEntryId")).isNull();
    assertThat(pendingSlip.get("cogsJournalEntryId")).isNull();

    Map<String, Object> confirmRequest =
        Map.of(
            "packingSlipId", slip.getId(),
            "orderId", orderId,
            "lines",
                List.of(
                    Map.of(
                        "lineId",
                        slip.getLines().getFirst().getId(),
                        "shipQty",
                        new BigDecimal("4"),
                        "notes",
                        "ship all")),
            "dispatchNotes", "ready for dispatch",
            "confirmedBy", "factory-user",
            "transporterName", "Rapid Logistics",
            "driverName", "Imran",
            "vehicleNumber", "MH14ZZ1001",
            "challanReference", "CH-7788");

    ResponseEntity<Map> firstResponse =
        rest.exchange(
            "/api/v1/sales/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(confirmRequest, salesHeaders),
            Map.class);
    assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    requireData(firstResponse);

    PackagingSlip persisted = packagingSlipRepository.findById(slip.getId()).orElseThrow();
    assertThat(persisted.getTransporterName()).isEqualTo("Rapid Logistics");
    assertThat(persisted.getDriverName()).isEqualTo("Imran");
    assertThat(persisted.getVehicleNumber()).isEqualTo("MH14ZZ1001");
    assertThat(persisted.getChallanReference()).isEqualTo("CH-7788");
    assertThat(persisted.getInvoiceId()).isNotNull();
    assertThat(persisted.getJournalEntryId()).isNotNull();
    assertThat(persisted.getCogsJournalEntryId()).isNotNull();

    ResponseEntity<Map> slipResponse =
        rest.exchange(
            "/api/v1/dispatch/slip/" + slip.getId(),
            HttpMethod.GET,
            new HttpEntity<>(factoryHeaders),
            Map.class);
    assertThat(slipResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> slipData = requireData(slipResponse);
    assertThat(slipData.get("journalEntryId")).isNull();
    assertThat(slipData.get("cogsJournalEntryId")).isNull();
    assertThat(slipData.get("challanReference")).isEqualTo("CH-7788");
    assertThat(slipData.get("deliveryChallanNumber")).isNotNull();
    assertThat(slipData.get("deliveryChallanPdfPath"))
        .isEqualTo("/api/v1/dispatch/slip/" + slip.getId() + "/challan/pdf");

    ResponseEntity<Map> orderSlipResponse =
        rest.exchange(
            "/api/v1/dispatch/order/" + orderId,
            HttpMethod.GET,
            new HttpEntity<>(factoryHeaders),
            Map.class);
    assertThat(orderSlipResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> orderSlipData = requireData(orderSlipResponse);
    assertThat(orderSlipData.get("journalEntryId")).isNull();
    assertThat(orderSlipData.get("cogsJournalEntryId")).isNull();
    assertThat(orderSlipData.get("challanReference")).isEqualTo("CH-7788");

    long movementCount =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                company, slip.getId(), "DISPATCH")
            .size();

    ResponseEntity<Map> replayResponse =
        rest.exchange(
            "/api/v1/sales/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(confirmRequest, salesHeaders),
            Map.class);
    assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    requireData(replayResponse);

    PackagingSlip replayed = packagingSlipRepository.findById(slip.getId()).orElseThrow();
    assertThat(replayed.getInvoiceId()).isEqualTo(persisted.getInvoiceId());
    assertThat(replayed.getJournalEntryId()).isEqualTo(persisted.getJournalEntryId());
    assertThat(replayed.getCogsJournalEntryId()).isEqualTo(persisted.getCogsJournalEntryId());
    assertThat(
            inventoryMovementRepository
                .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                    company, slip.getId(), "DISPATCH")
                .size())
        .isEqualTo(movementCount);

    ResponseEntity<byte[]> challanResponse =
        rest.exchange(
            "/api/v1/dispatch/slip/" + slip.getId() + "/challan/pdf",
            HttpMethod.GET,
            new HttpEntity<>(factoryHeaders),
            byte[].class);
    assertThat(challanResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(challanResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(challanResponse.getBody()).isNotNull();
    assertThat(challanResponse.getBody().length).isGreaterThan(100);
  }

  @Test
  void packedSellableOutput_mustExistBeforeOrderCanDispatch() {
    String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    ProductionBrand brand = createExecutionBrand("OPS-BRAND-" + suffix);
    ProductionProduct product =
        createExecutionProduct("FG-PACK-" + suffix, "Packed Dispatch " + suffix, brand);
    FinishedGood sellableTarget = ensureSellablePackTarget(product, "10L");

    RawMaterial base =
        createRawMaterial(
            "RM-BASE-" + suffix,
            "Dispatch Base " + suffix,
            MaterialType.PRODUCTION,
            accounts.get("INV").getId(),
            new BigDecimal("200.00"),
            "KG");
    addRawMaterialBatch(base, new BigDecimal("200.00"), new BigDecimal("12.00"));

    RawMaterial bucket =
        createRawMaterial(
            "RM-BUCKET-" + suffix,
            "Dispatch Bucket " + suffix,
            MaterialType.PACKAGING,
            accounts.get("PACK").getId(),
            new BigDecimal("20.00"),
            "UNIT");
    addRawMaterialBatch(bucket, new BigDecimal("20.00"), new BigDecimal("4.00"));
    mapPackagingSize("10L", bucket);

    ProductionLogDetailDto log =
        productionLogService.createLog(
            new ProductionLogRequest(
                brand.getId(),
                product.getId(),
                "WHITE",
                new BigDecimal("100.00"),
                "L",
                new BigDecimal("100.00"),
                LocalDate.now().toString(),
                "pack-before-dispatch",
                "Factory Ops",
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(
                    new ProductionLogRequest.MaterialUsageRequest(
                        base.getId(), new BigDecimal("40.00"), "KG"))));

    SalesOrderRequest orderReq =
        new SalesOrderRequest(
            dealer.getId(),
            new BigDecimal("1500.00"),
            "INR",
            null,
            List.of(
                new SalesOrderItemRequest(
                    product.getSkuCode(),
                    "Packed Dispatchable Item",
                    new BigDecimal("10.00"),
                    new BigDecimal("150.00"),
                    BigDecimal.ZERO)),
            "EXCLUSIVE",
            null,
            null,
            null);
    Long orderId = salesService.createOrder(orderReq).id();
    SalesOrder order = salesOrderRepository.findById(orderId).orElseThrow();

    FinishedGoodsService.InventoryReservationResult beforePack = finishedGoodsService.reserveForOrder(order);
    assertThat(beforePack.packagingSlip()).isNotNull();
    assertThat(beforePack.packagingSlip().status()).isEqualTo("PENDING_PRODUCTION");
    assertThat(beforePack.packagingSlip().lines()).isEmpty();
    assertThat(beforePack.shortages()).isNotEmpty();

    assertThatThrownBy(
            () ->
                salesService.confirmDispatch(
                    new DispatchConfirmRequest(
                        beforePack.packagingSlip().id(),
                        orderId,
                        List.of(),
                        "Attempt before pack",
                        "factory-user",
                        false,
                        null,
                        null)))
        .isInstanceOf(ApplicationException.class)
        .hasMessageContaining("No shippable quantity available for dispatch");

    PackagingSlip pendingSlip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, orderId).orElseThrow();
    assertThat(pendingSlip.getStatus()).isEqualTo("PENDING_PRODUCTION");
    assertThat(pendingSlip.getInvoiceId()).isNull();
    assertThat(pendingSlip.getJournalEntryId()).isNull();
    assertThat(pendingSlip.getCogsJournalEntryId()).isNull();

    packingService.recordPacking(
        new PackingRequest(
            log.id(),
            LocalDate.now(),
            "Packer",
            "PACK-DISPATCH-" + suffix,
            List.of(
                new PackingLineRequest(
                    sellableTarget.getId(),
                    null,
                    "10L",
                    new BigDecimal("100.00"),
                    10,
                    null,
                    null))));

    FinishedGoodsService.InventoryReservationResult afterPack = finishedGoodsService.reserveForOrder(order);
    assertThat(afterPack.packagingSlip()).isNotNull();
    assertThat(afterPack.packagingSlip().status()).isEqualTo("RESERVED");
    assertThat(afterPack.packagingSlip().lines()).hasSize(1);

    var response =
        salesService.confirmDispatch(
            new DispatchConfirmRequest(
                afterPack.packagingSlip().id(),
                orderId,
                List.of(),
                "Dispatch after pack",
                "factory-user",
                false,
                null,
                null));
    assertThat(response.dispatched()).isTrue();

    PackagingSlip dispatched =
        packagingSlipRepository.findById(afterPack.packagingSlip().id()).orElseThrow();
    assertThat(dispatched.getInvoiceId()).isNotNull();
    assertThat(dispatched.getJournalEntryId()).isNotNull();
    assertThat(dispatched.getCogsJournalEntryId()).isNotNull();
    assertThat(
            finishedGoodRepository.findById(sellableTarget.getId()).orElseThrow().getCurrentStock())
        .isEqualByComparingTo(new BigDecimal("90.00"));
  }

  @SuppressWarnings("unchecked")
  private Map<?, ?> requireData(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("success")).isEqualTo(Boolean.TRUE);
    return (Map<?, ?>) response.getBody().get("data");
  }

  @SuppressWarnings("unchecked")
  private List<Map<?, ?>> requireListData(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().get("success")).isEqualTo(Boolean.TRUE);
    return (List<Map<?, ?>>) response.getBody().get("data");
  }

  private String loginFactoryToken() {
    Map<String, Object> req =
        Map.of(
            "email", FACTORY_EMAIL,
            "password", FACTORY_PASSWORD,
            "companyCode", COMPANY_CODE);
    return (String)
        rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
  }

  private String loginSalesToken() {
    Map<String, Object> req =
        Map.of(
            "email", SALES_EMAIL,
            "password", SALES_PASSWORD,
            "companyCode", COMPANY_CODE);
    return (String)
        rest.postForEntity("/api/v1/auth/login", req, Map.class).getBody().get("accessToken");
  }

  private HttpHeaders authHeaders(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add("X-Company-Code", COMPANY_CODE);
    return headers;
  }

  private Map<String, Account> ensureAccounts(Company company) {
    return Map.of(
        "AR", ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET),
        "INV", ensureAccount(company, "INV", "Inventory", AccountType.ASSET),
        "PACK", ensureAccount(company, "PACK", "Packaging Inventory", AccountType.ASSET),
        "WIP", ensureAccount(company, "WIP", "Work In Progress", AccountType.ASSET),
        "COGS", ensureAccount(company, "COGS", "Cost of Goods Sold", AccountType.EXPENSE),
        "REV", ensureAccount(company, "REV", "Revenue", AccountType.REVENUE),
        "DISC", ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE),
        "TAX", ensureAccount(company, "TAX", "Tax Payable", AccountType.LIABILITY));
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
              account.setActive(true);
              account.setBalance(BigDecimal.ZERO);
              return accountRepository.save(account);
            });
  }

  private Dealer ensureDealer(Company company, String code, String name, Account arAccount) {
    return dealerRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Dealer d = new Dealer();
              d.setCompany(company);
              d.setCode(code);
              d.setName(name);
              d.setStatus("ACTIVE");
              d.setReceivableAccount(arAccount);
              return dealerRepository.save(d);
            });
  }

  private FinishedGood createFinishedGood(String productCode) {
    FinishedGoodRequest req =
        new FinishedGoodRequest(
            productCode,
            productCode + " Name",
            "UNIT",
            "FIFO",
            accounts.get("INV").getId(),
            accounts.get("COGS").getId(),
            accounts.get("REV").getId(),
            accounts.get("DISC").getId(),
            accounts.get("TAX").getId());
    return finishedGoodRepository
        .findByCompanyAndProductCode(company, productCode)
        .orElseGet(
            () -> {
              var dto = finishedGoodsService.createFinishedGood(req);
              return finishedGoodRepository.findById(dto.id()).orElseThrow();
            });
  }

  private void ensureCatalogProduct(FinishedGood fg, BigDecimal basePrice, BigDecimal gstRate) {
    ProductionBrand brand =
        productionBrandRepository
            .findByCompanyAndCodeIgnoreCase(fg.getCompany(), "OPS-BRAND")
            .orElseGet(
                () -> {
                  ProductionBrand b = new ProductionBrand();
                  b.setCompany(fg.getCompany());
                  b.setCode("OPS-BRAND");
                  b.setName("Ops Brand");
                  return productionBrandRepository.save(b);
                });
    productionProductRepository
        .findByCompanyAndSkuCode(fg.getCompany(), fg.getProductCode())
        .orElseGet(
            () -> {
              ProductionProduct p = new ProductionProduct();
              p.setCompany(fg.getCompany());
              p.setBrand(brand);
              p.setSkuCode(fg.getProductCode());
              p.setProductName(fg.getName());
              p.setBasePrice(basePrice);
              p.setCategory("GENERAL");
              p.setSizeLabel("STD");
              p.setDefaultColour("NA");
              p.setMinDiscountPercent(BigDecimal.ZERO);
              p.setMinSellingPrice(BigDecimal.ZERO);
              p.setMetadata(new java.util.HashMap<>());
              p.setGstRate(gstRate);
              p.setUnitOfMeasure("UNIT");
              p.setActive(true);
              return productionProductRepository.save(p);
            });
  }

  private ProductionBrand createExecutionBrand(String code) {
    return productionBrandRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              ProductionBrand brand = new ProductionBrand();
              brand.setCompany(company);
              brand.setCode(code);
              brand.setName(code);
              return productionBrandRepository.save(brand);
            });
  }

  private ProductionProduct createExecutionProduct(String sku, String name, ProductionBrand brand) {
    return productionProductRepository
        .findByCompanyAndSkuCode(company, sku)
        .orElseGet(
            () -> {
              ProductionProduct product = new ProductionProduct();
              product.setCompany(company);
              product.setBrand(brand);
              product.setSkuCode(sku);
              product.setProductName(name);
              product.setCategory("FINISHED_GOOD");
              product.setUnitOfMeasure("L");
              product.setMetadata(new HashMap<>());
              product.getMetadata().put("wipAccountId", accounts.get("WIP").getId());
              product.getMetadata().put("semiFinishedAccountId", accounts.get("INV").getId());
              product.getMetadata().put("fgValuationAccountId", accounts.get("INV").getId());
              product.getMetadata().put("fgCogsAccountId", accounts.get("COGS").getId());
              product.getMetadata().put("fgRevenueAccountId", accounts.get("REV").getId());
              product.getMetadata().put("fgDiscountAccountId", accounts.get("DISC").getId());
              product.getMetadata().put("fgTaxAccountId", accounts.get("TAX").getId());
              product.setBasePrice(new BigDecimal("150.00"));
              product.setGstRate(BigDecimal.ZERO);
              product.setActive(true);
              return productionProductRepository.save(product);
            });
  }

  private FinishedGood ensureSellablePackTarget(ProductionProduct product, String sizeLabel) {
    sizeVariantRepository
        .findByCompanyAndProductOrderBySizeLabelAsc(company, product)
        .stream()
        .filter(variant -> sizeLabel.equalsIgnoreCase(variant.getSizeLabel()))
        .findFirst()
        .orElseGet(
            () -> {
              SizeVariant variant = new SizeVariant();
              variant.setCompany(company);
              variant.setProduct(product);
              variant.setSizeLabel(sizeLabel);
              variant.setCartonQuantity(1);
              variant.setLitersPerUnit(new BigDecimal(sizeLabel.replace("L", "")));
              variant.setActive(true);
              return sizeVariantRepository.save(variant);
            });
    return finishedGoodRepository
        .findByCompanyAndProductCode(company, product.getSkuCode())
        .orElseGet(
            () -> {
              FinishedGood finishedGood = new FinishedGood();
              finishedGood.setCompany(company);
              finishedGood.setProductCode(product.getSkuCode());
              finishedGood.setName(product.getProductName());
              finishedGood.setUnit("L");
              finishedGood.setValuationAccountId(accounts.get("INV").getId());
              finishedGood.setCogsAccountId(accounts.get("COGS").getId());
              finishedGood.setRevenueAccountId(accounts.get("REV").getId());
              finishedGood.setDiscountAccountId(accounts.get("DISC").getId());
              finishedGood.setTaxAccountId(accounts.get("TAX").getId());
              return finishedGoodRepository.save(finishedGood);
            });
  }

  private RawMaterial createRawMaterial(
      String sku,
      String name,
      MaterialType materialType,
      Long inventoryAccountId,
      BigDecimal currentStock,
      String unitType) {
    RawMaterial rawMaterial = new RawMaterial();
    rawMaterial.setCompany(company);
    rawMaterial.setSku(sku);
    rawMaterial.setName(name);
    rawMaterial.setMaterialType(materialType);
    rawMaterial.setInventoryAccountId(inventoryAccountId);
    rawMaterial.setCurrentStock(currentStock);
    rawMaterial.setUnitType(unitType);
    return rawMaterialRepository.save(rawMaterial);
  }

  private void addRawMaterialBatch(
      RawMaterial rawMaterial, BigDecimal quantity, BigDecimal costPerUnit) {
    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(rawMaterial);
    batch.setBatchCode(rawMaterial.getSku() + "-B1");
    batch.setQuantity(quantity);
    batch.setUnit(rawMaterial.getUnitType());
    batch.setCostPerUnit(costPerUnit);
    rawMaterialBatchRepository.save(batch);
  }

  private void mapPackagingSize(String sizeLabel, RawMaterial packagingMaterial) {
    PackagingSizeMapping mapping = new PackagingSizeMapping();
    mapping.setCompany(company);
    mapping.setPackagingSize(sizeLabel);
    mapping.setRawMaterial(packagingMaterial);
    mapping.setUnitsPerPack(1);
    mapping.setLitersPerUnit(new BigDecimal(sizeLabel.replace("L", "")));
    mapping.setActive(true);
    packagingSizeMappingRepository.save(mapping);
  }
}
