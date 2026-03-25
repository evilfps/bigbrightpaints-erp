package com.bigbrightpaints.erp.truthsuite.o2c;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;

import com.bigbrightpaints.erp.codered.support.CoderedDbAssertions;
import com.bigbrightpaints.erp.core.security.CompanyContextHolder;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlip;
import com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import com.bigbrightpaints.erp.modules.invoice.domain.Invoice;
import com.bigbrightpaints.erp.modules.invoice.domain.InvoiceRepository;
import com.bigbrightpaints.erp.modules.invoice.dto.InvoiceDto;
import com.bigbrightpaints.erp.modules.invoice.service.InvoiceService;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.modules.sales.domain.Dealer;
import com.bigbrightpaints.erp.modules.sales.domain.DealerRepository;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrder;
import com.bigbrightpaints.erp.modules.sales.domain.SalesOrderRepository;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderItemRequest;
import com.bigbrightpaints.erp.modules.sales.dto.SalesOrderRequest;
import com.bigbrightpaints.erp.modules.sales.service.SalesService;
import com.bigbrightpaints.erp.shared.dto.LinkedBusinessReferenceDto;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
@Tag("reconciliation")
@TestPropertySource(properties = "erp.auto-approval.enabled=false")
class TS_O2CDispatchProvenanceAndRetiredRouteBoundaryTest extends AbstractIntegrationTest {

  private static final String ADMIN_PASSWORD = "admin123";
  private static final String SALES_PASSWORD = "sales123";
  private static final String FACTORY_PASSWORD = "factory123";
  private static final String COMPANY_STATE_CODE = "27";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private DealerRepository dealerRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private FinishedGoodsService finishedGoodsService;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private SalesService salesService;
  @Autowired private SalesOrderRepository salesOrderRepository;
  @Autowired private PackagingSlipRepository packagingSlipRepository;
  @Autowired private InvoiceRepository invoiceRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private InventoryMovementRepository inventoryMovementRepository;
  @Autowired private InvoiceService invoiceService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearCompanyContext() {
    CompanyContextHolder.clear();
  }

  @Test
  void salesDispatchConfirm_createsNavigableProvenanceLinkedReferencesAndBalancedTruth() {
    DispatchFixture fixture =
        bootstrapDispatchFixture("TS-PROV", new BigDecimal("123.40"), BigDecimal.ZERO);

    ResponseEntity<Map> response =
        rest.exchange(
            "/api/v1/sales/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(
                salesDispatchRequest(fixture, "provenance"),
                authHeaders(loginSales(fixture), fixture.company().getCode())),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> data = requireData(response, "sales dispatch confirm");
    Long invoiceId = longValue(data.get("finalInvoiceId"));
    Long arJournalId = longValue(data.get("arJournalEntryId"));

    PackagingSlip slip =
        packagingSlipRepository
            .findByIdAndCompany(fixture.slip().getId(), fixture.company())
            .orElseThrow();
    SalesOrder order = salesOrderRepository.findById(fixture.order().getId()).orElseThrow();
    Invoice invoice =
        invoiceRepository.findByCompanyAndId(fixture.company(), invoiceId).orElseThrow();
    JournalEntry arJournal =
        journalEntryRepository.findByCompanyAndId(fixture.company(), arJournalId).orElseThrow();
    JournalEntry cogsJournal =
        journalEntryRepository
            .findByCompanyAndId(fixture.company(), slip.getCogsJournalEntryId())
            .orElseThrow();
    List<InventoryMovement> dispatchMovements =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                fixture.company(), slip.getId(), "DISPATCH");

    assertThat(invoiceId).isNotNull();
    assertThat(arJournalId).isNotNull();
    assertThat(slip.getInvoiceId()).isEqualTo(invoiceId);
    assertThat(slip.getJournalEntryId()).isEqualTo(arJournalId);
    assertThat(slip.getCogsJournalEntryId()).isNotNull();
    assertThat(order.getFulfillmentInvoiceId()).isEqualTo(invoiceId);
    assertThat(order.getSalesJournalEntryId()).isEqualTo(arJournalId);
    assertThat(order.getCogsJournalEntryId()).isEqualTo(slip.getCogsJournalEntryId());
    assertThat(arJournal.getReferenceNumber()).startsWith("INV-");
    assertThat(cogsJournal.getReferenceNumber()).startsWith("COGS-");
    assertThat(dispatchMovements).isNotEmpty();
    assertThat(dispatchMovements)
        .extracting(InventoryMovement::getJournalEntryId)
        .containsOnly(slip.getCogsJournalEntryId());
    assertThat(
            dispatchMovements.stream()
                .map(InventoryMovement::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(totalShippedQuantity(slip));

    CoderedDbAssertions.assertOneInvoicePerSlip(
        packagingSlipRepository, invoiceRepository, fixture.company(), slip.getId());
    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, arJournalId);
    CoderedDbAssertions.assertBalancedJournal(journalEntryRepository, slip.getCogsJournalEntryId());
    CoderedDbAssertions.assertDealerLedgerEntriesLinkedToJournal(
        jdbcTemplate, fixture.company().getId(), arJournalId);

    InvoiceDto invoiceDto = getInvoiceWithinCompanyContext(fixture.company().getCode(), invoiceId);

    assertThat(invoiceDto.journalEntryId()).isEqualTo(arJournalId);
    assertThat(invoiceDto.linkedReferences()).hasSize(3);
    LinkedBusinessReferenceDto sourceOrderReference = linkedReference(invoiceDto, "SOURCE_ORDER");
    LinkedBusinessReferenceDto dispatchReference = linkedReference(invoiceDto, "DISPATCH");
    LinkedBusinessReferenceDto accountingReference =
        linkedReference(invoiceDto, "ACCOUNTING_ENTRY");

    assertThat(sourceOrderReference.documentType()).isEqualTo("SALES_ORDER");
    assertThat(sourceOrderReference.documentId()).isEqualTo(order.getId());
    assertThat(sourceOrderReference.documentNumber()).isEqualTo(order.getOrderNumber());
    assertThat(sourceOrderReference.journalEntryId()).isEqualTo(order.getSalesJournalEntryId());

    assertThat(dispatchReference.documentType()).isEqualTo("PACKAGING_SLIP");
    assertThat(dispatchReference.documentId()).isEqualTo(slip.getId());
    assertThat(dispatchReference.documentNumber()).isEqualTo(slip.getSlipNumber());
    assertThat(dispatchReference.journalEntryId()).isEqualTo(slip.getCogsJournalEntryId());

    assertThat(accountingReference.documentType()).isEqualTo("JOURNAL_ENTRY");
    assertThat(accountingReference.documentId()).isEqualTo(arJournal.getId());
    assertThat(accountingReference.documentNumber()).isEqualTo(arJournal.getReferenceNumber());
    assertThat(accountingReference.journalEntryId()).isEqualTo(arJournal.getId());
  }

  @Test
  void pureFactoryDispatchViews_redactPricingInvoiceGstCostAndJournalFields() {
    DispatchFixture fixture =
        bootstrapDispatchFixture("TS-FACTORY", new BigDecimal("125.00"), new BigDecimal("18.00"));
    HttpHeaders factoryHeaders = authHeaders(loginFactory(fixture), fixture.company().getCode());

    ResponseEntity<Map> previewResponse =
        rest.exchange(
            "/api/v1/dispatch/preview/" + fixture.slip().getId(),
            HttpMethod.GET,
            new HttpEntity<>(factoryHeaders),
            Map.class);
    assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> previewData = requireData(previewResponse, "factory dispatch preview");
    Map<?, ?> previewLine = firstMap(previewData, "lines");
    assertThat(previewData.get("totalOrderedAmount")).isNull();
    assertThat(previewData.get("gstBreakdown")).isNull();
    assertThat(previewLine.get("unitPrice")).isNull();
    assertThat(previewLine.get("lineSubtotal")).isNull();
    assertThat(previewLine.get("lineTax")).isNull();
    assertThat(previewLine.get("lineTotal")).isNull();

    ResponseEntity<Map> confirmResponse =
        rest.exchange(
            "/api/v1/sales/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(
                salesDispatchRequest(fixture, "factory-redaction"),
                authHeaders(loginSales(fixture), fixture.company().getCode())),
            Map.class);
    assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    requireData(confirmResponse, "sales dispatch confirm");

    PackagingSlip persisted =
        packagingSlipRepository
            .findByIdAndCompany(fixture.slip().getId(), fixture.company())
            .orElseThrow();
    assertThat(persisted.getInvoiceId()).isNotNull();
    assertThat(persisted.getJournalEntryId()).isNotNull();
    assertThat(persisted.getCogsJournalEntryId()).isNotNull();

    ResponseEntity<Map> slipResponse =
        rest.exchange(
            "/api/v1/dispatch/slip/" + fixture.slip().getId(),
            HttpMethod.GET,
            new HttpEntity<>(factoryHeaders),
            Map.class);
    assertThat(slipResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> slipData = requireData(slipResponse, "factory packaging slip view");
    Map<?, ?> slipLine = firstMap(slipData, "lines");
    assertThat(slipData.get("journalEntryId")).isNull();
    assertThat(slipData.get("cogsJournalEntryId")).isNull();
    assertMissingKeys(slipData, "invoiceId", "finalInvoiceId", "arJournalEntryId");
    assertThat(slipLine.get("unitCost")).isNull();
    assertThat(slipData.get("vehicleNumber")).isEqualTo("MH14ZZ1001");
    assertThat(slipData.get("challanReference")).isEqualTo("CH-factory-redaction");
  }

  @Test
  void salesDispatchConfirmReplay_preservesCanonicalTruth() {
    DispatchFixture fixture =
        bootstrapDispatchFixture("TS-ENDP-REPLAY", new BigDecimal("111.00"), BigDecimal.ZERO);

    ResponseEntity<Map> firstResponse =
        rest.exchange(
            "/api/v1/sales/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(
                salesDispatchRequest(fixture, "sales-first"),
                authHeaders(loginSales(fixture), fixture.company().getCode())),
            Map.class);
    assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    PackagingSlip afterFirst =
        packagingSlipRepository
            .findByIdAndCompany(fixture.slip().getId(), fixture.company())
            .orElseThrow();
    int movementCountBeforeReplay =
        inventoryMovementRepository
            .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                fixture.company(), afterFirst.getId(), "DISPATCH")
            .size();

    ResponseEntity<Map> replayResponse =
        rest.exchange(
            "/api/v1/sales/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(
                salesDispatchRequest(fixture, "sales-replay"),
                authHeaders(loginSales(fixture), fixture.company().getCode())),
            Map.class);
    assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> replayData = requireData(replayResponse, "sales dispatch replay");

    PackagingSlip afterReplay =
        packagingSlipRepository
            .findByIdAndCompany(fixture.slip().getId(), fixture.company())
            .orElseThrow();
    SalesOrder orderAfterReplay =
        salesOrderRepository.findById(fixture.order().getId()).orElseThrow();

    assertThat(longValue(replayData.get("packingSlipId"))).isEqualTo(afterFirst.getId());
    assertThat(longValue(replayData.get("salesOrderId"))).isEqualTo(fixture.order().getId());
    assertThat(longValue(replayData.get("finalInvoiceId"))).isEqualTo(afterFirst.getInvoiceId());
    assertThat(longValue(replayData.get("arJournalEntryId")))
        .isEqualTo(afterFirst.getJournalEntryId());
    assertThat(listValue(replayData, "cogsPostings")).isEmpty();
    assertThat(afterReplay.getInvoiceId()).isEqualTo(afterFirst.getInvoiceId());
    assertThat(afterReplay.getJournalEntryId()).isEqualTo(afterFirst.getJournalEntryId());
    assertThat(afterReplay.getCogsJournalEntryId()).isEqualTo(afterFirst.getCogsJournalEntryId());
    assertThat(orderAfterReplay.getFulfillmentInvoiceId()).isEqualTo(afterFirst.getInvoiceId());
    assertThat(orderAfterReplay.getSalesJournalEntryId())
        .isEqualTo(afterFirst.getJournalEntryId());
    assertThat(orderAfterReplay.getCogsJournalEntryId())
        .isEqualTo(afterFirst.getCogsJournalEntryId());
    assertThat(
            inventoryMovementRepository
                .findByFinishedGood_CompanyAndPackingSlipIdAndMovementTypeIgnoreCaseOrderByCreatedAtAsc(
                    fixture.company(), afterReplay.getId(), "DISPATCH"))
        .hasSize(movementCountBeforeReplay);
  }

  @Test
  void retiredFactoryDispatchConfirmRoute_isAbsent() {
    DispatchFixture fixture =
        bootstrapDispatchFixture("TS-ENDP-ABSENT", new BigDecimal("119.50"), BigDecimal.ZERO);

    ResponseEntity<Map> retiredResponse =
        rest.exchange(
            "/api/v1/dispatch/confirm",
            HttpMethod.POST,
            new HttpEntity<>(
                factoryDispatchRequest(fixture, "factory-replay"),
                authHeaders(loginFactory(fixture), fixture.company().getCode())),
            Map.class);
    assertThat(retiredResponse.getStatusCode())
        .isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED);
  }

  private DispatchFixture bootstrapDispatchFixture(
      String prefix, BigDecimal unitPrice, BigDecimal gstRate) {
    String companyCode = prefix + "-" + shortId();
    Company company = bootstrapCompany(companyCode, COMPANY_STATE_CODE);
    Map<String, Account> accounts = ensureCoreAccounts(company);
    Dealer dealer = ensureDealer(company, accounts.get("AR"), COMPANY_STATE_CODE);
    FinishedGood finishedGood =
        ensureFinishedGoodWithCatalog(company, accounts, "FG-" + shortId(), unitPrice, gstRate);
    finishedGoodsService.registerBatch(
        new FinishedGoodBatchRequest(
            finishedGood.getId(),
            "BATCH-" + shortId(),
            new BigDecimal("50"),
            new BigDecimal("10.00"),
            Instant.now(),
            null));

    SalesOrder order =
        createOrder(
            company,
            dealer,
            finishedGood.getProductCode(),
            new BigDecimal("4"),
            unitPrice,
            gstRate);
    CompanyContextHolder.setCompanyCode(company.getCode());
    finishedGoodsService.reserveForOrder(order);
    PackagingSlip slip =
        packagingSlipRepository.findByCompanyAndSalesOrderId(company, order.getId()).orElseThrow();

    String adminEmail = "admin+" + shortId() + "@truthsuite.test";
    String salesEmail = "sales+" + shortId() + "@truthsuite.test";
    String factoryEmail = "factory+" + shortId() + "@truthsuite.test";
    dataSeeder.ensureUser(
        adminEmail,
        ADMIN_PASSWORD,
        "Truthsuite Admin",
        companyCode,
        List.of("ROLE_ADMIN", "dispatch.confirm"));
    dataSeeder.ensureUser(
        salesEmail,
        SALES_PASSWORD,
        "Truthsuite Sales",
        companyCode,
        List.of("ROLE_SALES", "dispatch.confirm"));
    dataSeeder.ensureUser(
        factoryEmail,
        FACTORY_PASSWORD,
        "Truthsuite Factory",
        companyCode,
        List.of("ROLE_FACTORY"));

    return new DispatchFixture(
        company, dealer, finishedGood, order, slip, adminEmail, salesEmail, factoryEmail);
  }

  private Company bootstrapCompany(String companyCode, String stateCode) {
    dataSeeder.ensureCompany(companyCode, companyCode + " Ltd");
    CompanyContextHolder.setCompanyCode(companyCode);
    Company company = companyRepository.findByCodeIgnoreCase(companyCode).orElseThrow();
    company.setTimezone("UTC");
    company.setBaseCurrency("INR");
    company.setStateCode(stateCode);
    companyRepository.save(company);
    return company;
  }

  private Map<String, Account> ensureCoreAccounts(Company company) {
    Account ar = ensureAccount(company, "AR", "Accounts Receivable", AccountType.ASSET);
    Account inv = ensureAccount(company, "INV", "Inventory", AccountType.ASSET);
    Account cogs = ensureAccount(company, "COGS", "COGS", AccountType.COGS);
    Account rev = ensureAccount(company, "REV", "Revenue", AccountType.REVENUE);
    Account disc = ensureAccount(company, "DISC", "Discounts", AccountType.EXPENSE);
    Account gstOut = ensureAccount(company, "GST-OUT", "GST Output", AccountType.LIABILITY);
    Account gstIn = ensureAccount(company, "GST-IN", "GST Input", AccountType.ASSET);

    updateCompanyDefaults(company, inv, cogs, rev, disc, gstOut, gstIn);

    return Map.of(
        "AR", ar,
        "INV", inv,
        "COGS", cogs,
        "REV", rev,
        "DISC", disc,
        "GST_OUT", gstOut,
        "GST_IN", gstIn);
  }

  private void updateCompanyDefaults(
      Company company,
      Account inv,
      Account cogs,
      Account rev,
      Account disc,
      Account gstOut,
      Account gstIn) {
    if (company == null || company.getId() == null) {
      return;
    }
    for (int attempt = 0; attempt < 2; attempt++) {
      Company fresh = companyRepository.findById(company.getId()).orElseThrow();
      boolean updated = false;
      if (fresh.getDefaultInventoryAccountId() == null) {
        fresh.setDefaultInventoryAccountId(inv.getId());
        updated = true;
      }
      if (fresh.getDefaultCogsAccountId() == null) {
        fresh.setDefaultCogsAccountId(cogs.getId());
        updated = true;
      }
      if (fresh.getDefaultRevenueAccountId() == null) {
        fresh.setDefaultRevenueAccountId(rev.getId());
        updated = true;
      }
      if (fresh.getDefaultDiscountAccountId() == null) {
        fresh.setDefaultDiscountAccountId(disc.getId());
        updated = true;
      }
      if (fresh.getDefaultTaxAccountId() == null) {
        fresh.setDefaultTaxAccountId(gstOut.getId());
        updated = true;
      }
      if (fresh.getGstInputTaxAccountId() == null) {
        fresh.setGstInputTaxAccountId(gstIn.getId());
        updated = true;
      }
      if (fresh.getGstOutputTaxAccountId() == null) {
        fresh.setGstOutputTaxAccountId(gstOut.getId());
        updated = true;
      }
      if (fresh.getGstPayableAccountId() == null) {
        fresh.setGstPayableAccountId(gstOut.getId());
        updated = true;
      }
      if (!updated) {
        return;
      }
      try {
        companyRepository.save(fresh);
        return;
      } catch (ObjectOptimisticLockingFailureException ex) {
        if (attempt == 1) {
          throw ex;
        }
      }
    }
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

  private Dealer ensureDealer(Company company, Account arAccount, String stateCode) {
    return dealerRepository
        .findByCompanyAndCodeIgnoreCase(company, "TS-DEALER")
        .orElseGet(
            () -> {
              Dealer dealer = new Dealer();
              dealer.setCompany(company);
              dealer.setCode("TS-DEALER");
              dealer.setName("Truthsuite Dealer");
              dealer.setStatus("ACTIVE");
              dealer.setReceivableAccount(arAccount);
              dealer.setStateCode(stateCode);
              dealer.setGstRegistrationType(GstRegistrationType.REGULAR);
              dealer.setOutstandingBalance(BigDecimal.ZERO);
              return dealerRepository.save(dealer);
            });
  }

  private FinishedGood ensureFinishedGoodWithCatalog(
      Company company,
      Map<String, Account> accounts,
      String sku,
      BigDecimal unitPrice,
      BigDecimal gstRate) {
    FinishedGoodRequest request =
        new FinishedGoodRequest(
            sku,
            sku + " Name",
            "UNIT",
            "FIFO",
            accounts.get("INV").getId(),
            accounts.get("COGS").getId(),
            accounts.get("REV").getId(),
            accounts.get("DISC").getId(),
            accounts.get("GST_OUT").getId());
    FinishedGood finishedGood =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, sku)
            .orElseGet(
                () -> {
                  var dto = finishedGoodsService.createFinishedGood(request);
                  return finishedGoodRepository.findById(dto.id()).orElseThrow();
                });
    ensureCatalogProduct(company, finishedGood, unitPrice, gstRate);
    return finishedGood;
  }

  private void ensureCatalogProduct(
      Company company, FinishedGood finishedGood, BigDecimal unitPrice, BigDecimal gstRate) {
    ProductionBrand brand =
        productionBrandRepository
            .findByCompanyAndCodeIgnoreCase(company, "TS-BRAND")
            .orElseGet(
                () -> {
                  ProductionBrand created = new ProductionBrand();
                  created.setCompany(company);
                  created.setCode("TS-BRAND");
                  created.setName("Truthsuite Brand");
                  return productionBrandRepository.save(created);
                });
    productionProductRepository
        .findByCompanyAndSkuCode(company, finishedGood.getProductCode())
        .orElseGet(
            () -> {
              ProductionProduct product = new ProductionProduct();
              product.setCompany(company);
              product.setBrand(brand);
              product.setSkuCode(finishedGood.getProductCode());
              product.setProductName(finishedGood.getName());
              product.setBasePrice(unitPrice);
              product.setCategory("GENERAL");
              product.setSizeLabel("STD");
              product.setDefaultColour("NA");
              product.setMinDiscountPercent(BigDecimal.ZERO);
              product.setMinSellingPrice(BigDecimal.ZERO);
              product.setMetadata(new HashMap<>());
              product.setGstRate(gstRate);
              product.setUnitOfMeasure("UNIT");
              product.setActive(true);
              return productionProductRepository.save(product);
            });
  }

  private SalesOrder createOrder(
      Company company,
      Dealer dealer,
      String productCode,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal gstRate) {
    CompanyContextHolder.setCompanyCode(company.getCode());
    BigDecimal subtotal = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    BigDecimal tax =
        gstRate == null || gstRate.compareTo(BigDecimal.ZERO) <= 0
            ? BigDecimal.ZERO
            : subtotal.multiply(gstRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    BigDecimal totalAmount = subtotal.add(tax);
    String gstTreatment =
        gstRate == null || gstRate.compareTo(BigDecimal.ZERO) <= 0 ? "NONE" : "PER_ITEM";
    var orderDto =
        salesService.createOrder(
            new SalesOrderRequest(
                dealer.getId(),
                totalAmount,
                "INR",
                "truthsuite provenance characterization",
                List.of(
                    new SalesOrderItemRequest(
                        productCode, "Truthsuite Item", quantity, unitPrice, gstRate)),
                gstTreatment,
                gstRate,
                Boolean.FALSE,
                UUID.randomUUID().toString()));
    return salesOrderRepository.findById(orderDto.id()).orElseThrow();
  }

  private String loginAdmin(DispatchFixture fixture) {
    return loginToken(fixture.adminEmail(), ADMIN_PASSWORD, fixture.company().getCode());
  }

  private String loginSales(DispatchFixture fixture) {
    return loginToken(fixture.salesEmail(), SALES_PASSWORD, fixture.company().getCode());
  }

  private String loginFactory(DispatchFixture fixture) {
    return loginToken(fixture.factoryEmail(), FACTORY_PASSWORD, fixture.company().getCode());
  }

  private String loginToken(String email, String password, String companyCode) {
    Map<String, Object> request =
        Map.of(
            "email", email,
            "password", password,
            "companyCode", companyCode);
    ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login", request, Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return String.valueOf(response.getBody().get("accessToken"));
  }

  private HttpHeaders authHeaders(String token, String companyCode) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add("X-Company-Code", companyCode);
    return headers;
  }

  private Map<String, Object> salesDispatchRequest(DispatchFixture fixture, String referenceSeed) {
    Map<String, Object> request = new HashMap<>();
    request.put("packingSlipId", fixture.slip().getId());
    request.put("orderId", fixture.order().getId());
    request.put("dispatchNotes", "truthsuite sales dispatch " + referenceSeed);
    request.put("confirmedBy", "truthsuite-admin");
    request.put("lines", salesDispatchLines(fixture.slip()));
    addDispatchMetadata(request, referenceSeed);
    return request;
  }

  private Map<String, Object> factoryDispatchRequest(
      DispatchFixture fixture, String referenceSeed) {
    Map<String, Object> request = new HashMap<>();
    request.put("packagingSlipId", fixture.slip().getId());
    request.put("notes", "truthsuite factory dispatch " + referenceSeed);
    request.put("lines", factoryDispatchLines(fixture.slip()));
    addDispatchMetadata(request, referenceSeed);
    return request;
  }

  private List<Map<String, Object>> salesDispatchLines(PackagingSlip slip) {
    List<Map<String, Object>> lines = new ArrayList<>();
    slip.getLines()
        .forEach(
            line ->
                lines.add(
                    Map.of(
                        "lineId", line.getId(),
                        "shipQty", shippedQuantity(line),
                        "notes", "ship all")));
    return lines;
  }

  private List<Map<String, Object>> factoryDispatchLines(PackagingSlip slip) {
    List<Map<String, Object>> lines = new ArrayList<>();
    slip.getLines()
        .forEach(
            line ->
                lines.add(
                    Map.of(
                        "lineId", line.getId(),
                        "shippedQuantity", shippedQuantity(line),
                        "notes", "ship all")));
    return lines;
  }

  private void addDispatchMetadata(Map<String, Object> request, String referenceSeed) {
    request.put("transporterName", "Rapid Logistics");
    request.put("driverName", "Imran");
    request.put("vehicleNumber", "MH14ZZ1001");
    request.put("challanReference", "CH-" + referenceSeed);
  }

  private InvoiceDto getInvoiceWithinCompanyContext(String companyCode, Long invoiceId) {
    CompanyContextHolder.setCompanyCode(companyCode);
    return invoiceService.getInvoice(invoiceId);
  }

  private LinkedBusinessReferenceDto linkedReference(InvoiceDto invoiceDto, String relationType) {
    return invoiceDto.linkedReferences().stream()
        .filter(reference -> relationType.equals(reference.relationType()))
        .findFirst()
        .orElseThrow();
  }

  private BigDecimal totalShippedQuantity(PackagingSlip slip) {
    return slip.getLines().stream()
        .map(this::shippedQuantity)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal shippedQuantity(
      com.bigbrightpaints.erp.modules.inventory.domain.PackagingSlipLine line) {
    if (line.getShippedQuantity() != null) {
      return line.getShippedQuantity();
    }
    if (line.getOrderedQuantity() != null) {
      return line.getOrderedQuantity();
    }
    return line.getQuantity();
  }

  @SuppressWarnings("unchecked")
  private Map<?, ?> requireData(ResponseEntity<Map> response, String action) {
    assertThat(response.getBody()).as("%s body", action).isNotNull();
    assertThat(response.getBody().get("success"))
        .as("%s success flag", action)
        .isEqualTo(Boolean.TRUE);
    return (Map<?, ?>) response.getBody().get("data");
  }

  @SuppressWarnings("unchecked")
  private Map<?, ?> firstMap(Map<?, ?> data, String key) {
    List<Map<?, ?>> items = (List<Map<?, ?>>) data.get(key);
    assertThat(items).isNotNull().isNotEmpty();
    return items.getFirst();
  }

  @SuppressWarnings("unchecked")
  private List<Map<?, ?>> listValue(Map<?, ?> data, String key) {
    Object value = data.get(key);
    if (value == null) {
      return List.of();
    }
    return (List<Map<?, ?>>) value;
  }

  private Long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string && !string.isBlank()) {
      return Long.parseLong(string);
    }
    return null;
  }

  private void assertMissingKeys(Map<?, ?> data, String... keys) {
    for (String key : keys) {
      assertThat(data.containsKey(key)).as("redacted response should omit %s", key).isFalse();
    }
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private record DispatchFixture(
      Company company,
      Dealer dealer,
      FinishedGood finishedGood,
      SalesOrder order,
      PackagingSlip slip,
      String adminEmail,
      String salesEmail,
      String factoryEmail) {}
}
