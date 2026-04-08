package com.bigbrightpaints.erp.modules.reports.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryStatus;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecord;
import com.bigbrightpaints.erp.modules.factory.domain.PackingRecordRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLog;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogStatus;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrand;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProduct;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
class ReportControllerCostingEndpointsIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "M8-COST-RPT";
  private static final String ACCOUNTING_EMAIL = "m8-costing@bbp.com";
  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private ProductionBrandRepository productionBrandRepository;
  @Autowired private ProductionProductRepository productionProductRepository;
  @Autowired private ProductionLogRepository productionLogRepository;
  @Autowired private PackingRecordRepository packingRecordRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;

  private Company company;
  private HttpHeaders headers;
  private Long productionLogId;
  private Long productId;
  private String packagingInventoryCode;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL,
        PASSWORD,
        "M8 Costing Accountant",
        COMPANY_CODE,
        List.of("ROLE_ACCOUNTING", "ROLE_ADMIN"));
    company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    headers = authHeaders();
    seedCostingData();
  }

  @Test
  void costingEndpointsAndPackingSourceFilter_returnExpectedPayloads() {
    Map<String, Object> productCosting =
        fetchDataMap("/api/v1/reports/product-costing?itemId=" + productId);
    assertThat(productCosting)
        .containsKeys(
            "materialCost", "packagingCost", "labourCost", "overheadCost", "totalUnitCost");

    List<Map<String, Object>> packingJournals =
        castListOfMap(fetchDataObject("/api/v1/accounting/journal-entries?source=PACKING"));
    assertThat(packingJournals).isNotEmpty();
    List<Map<String, Object>> lines = castListOfMap(packingJournals.getFirst().get("lines"));
    assertThat(lines)
        .anySatisfy(line -> assertThat(line.get("accountCode")).isEqualTo(packagingInventoryCode));

    Map<String, Object> costAllocation = fetchDataMap("/api/v1/reports/cost-allocation");
    assertThat(costAllocation).containsKeys("allocationRules", "amountsPerBatch", "totalAllocated");
    assertThat(castListOfMap(costAllocation.get("amountsPerBatch"))).isNotEmpty();

    Map<String, Object> costBreakdown =
        fetchDataMap("/api/v1/reports/production-logs/" + productionLogId + "/cost-breakdown");
    assertThat(costBreakdown)
        .containsKeys("materialCost", "labourCost", "overheadCost", "totalCost");

    List<Map<String, Object>> monthlyEntries =
        castListOfMap(fetchDataObject("/api/v1/reports/monthly-production-costs"));
    assertThat(monthlyEntries).isNotEmpty();
    assertThat(monthlyEntries.getFirst()).containsKeys("month", "totalCost");

    List<Map<String, Object>> wastageEntries =
        castListOfMap(fetchDataObject("/api/v1/reports/wastage"));
    assertThat(wastageEntries).isNotEmpty();
  }

  private void seedCostingData() {
    String suffix = Long.toString(System.nanoTime());

    ProductionBrand brand = new ProductionBrand();
    brand.setCompany(company);
    brand.setCode("M8-COST-BR-" + suffix);
    brand.setName("M8 Costing Brand " + suffix);
    brand = productionBrandRepository.saveAndFlush(brand);

    ProductionProduct product = new ProductionProduct();
    product.setCompany(company);
    product.setBrand(brand);
    product.setSkuCode("M8-COST-SKU-" + suffix);
    product.setProductName("M8 Costing Product " + suffix);
    product.setCategory("PAINT");
    product.setUnitOfMeasure("LITER");
    product.setBasePrice(new BigDecimal("120.00"));
    product.setGstRate(new BigDecimal("18.00"));
    product.setMinDiscountPercent(BigDecimal.ZERO);
    product.setMinSellingPrice(new BigDecimal("100.00"));
    product = productionProductRepository.saveAndFlush(product);
    productId = product.getId();

    ProductionLog log = new ProductionLog();
    log.setCompany(company);
    log.setBrand(brand);
    log.setProduct(product);
    log.setProductionCode("M8-COST-LOG-" + suffix);
    log.setBatchColour("WHITE");
    log.setBatchSize(new BigDecimal("100.00"));
    log.setUnitOfMeasure("LITER");
    log.setStatus(ProductionLogStatus.FULLY_PACKED);
    log.setMixedQuantity(new BigDecimal("100.00"));
    log.setTotalPackedQuantity(new BigDecimal("95.00"));
    log.setWastageQuantity(new BigDecimal("5.00"));
    log.setMaterialCostTotal(new BigDecimal("3000.00"));
    log.setLaborCostTotal(new BigDecimal("1200.00"));
    log.setOverheadCostTotal(new BigDecimal("800.00"));
    log.setUnitCost(new BigDecimal("52.6316"));
    log.setProducedAt(Instant.now().minusSeconds(86_400));
    log = productionLogRepository.saveAndFlush(log);
    productionLogId = log.getId();

    FinishedGood finishedGood = new FinishedGood();
    finishedGood.setCompany(company);
    finishedGood.setProductCode("M8-COST-FG-" + suffix);
    finishedGood.setName("M8 Costing Finished Good");
    finishedGood.setUnit("LITER");
    finishedGood = finishedGoodRepository.saveAndFlush(finishedGood);

    PackingRecord record = new PackingRecord();
    record.setCompany(company);
    record.setProductionLog(log);
    record.setFinishedGood(finishedGood);
    record.setPackagingSize("1L");
    record.setQuantityPacked(new BigDecimal("95.00"));
    record.setPackedDate(LocalDate.now().minusDays(1));
    record.setPackagingCost(new BigDecimal("500.00"));
    packingRecordRepository.saveAndFlush(record);

    Account wipAccount = createAccount("M8-WIP-" + suffix, "WIP Account", AccountType.ASSET);
    Account packagingInventoryAccount =
        createAccount("M8-PKG-" + suffix, "Packaging Inventory", AccountType.ASSET);
    Account finishedGoodsAccount =
        createAccount("M8-FG-" + suffix, "Finished Goods", AccountType.ASSET);
    Account overheadExpenseAccount =
        createAccount("M8-OVH-" + suffix, "Overhead Expense", AccountType.EXPENSE);
    packagingInventoryCode = packagingInventoryAccount.getCode();

    persistJournal(
        "PACK-" + suffix,
        "FACTORY_PACKING",
        "Packaging material consumption",
        wipAccount,
        new BigDecimal("500.00"),
        packagingInventoryAccount,
        new BigDecimal("500.00"));

    String periodKey = LocalDate.now().toString().substring(0, 7).replace("-", "");
    persistJournal(
        "CVAR-" + log.getProductionCode() + "-" + periodKey,
        "FACTORY_COST_VARIANCE",
        "Cost variance allocation",
        finishedGoodsAccount,
        new BigDecimal("200.00"),
        overheadExpenseAccount,
        new BigDecimal("200.00"));
  }

  private Account createAccount(String code, String name, AccountType type) {
    Account account = new Account();
    account.setCompany(company);
    account.setCode(code);
    account.setName(name);
    account.setType(type);
    return accountRepository.saveAndFlush(account);
  }

  private void persistJournal(
      String reference,
      String sourceModule,
      String memo,
      Account debitAccount,
      BigDecimal debitAmount,
      Account creditAccount,
      BigDecimal creditAmount) {
    JournalEntry entry = new JournalEntry();
    entry.setCompany(company);
    entry.setReferenceNumber(reference);
    entry.setEntryDate(LocalDate.now().minusDays(1));
    entry.setMemo(memo);
    entry.setStatus(JournalEntryStatus.POSTED);
    entry.setJournalType(JournalEntryType.AUTOMATED);
    entry.setSourceModule(sourceModule);
    entry.addLine(line(debitAccount, memo, debitAmount, BigDecimal.ZERO));
    entry.addLine(line(creditAccount, memo, BigDecimal.ZERO, creditAmount));
    journalEntryRepository.saveAndFlush(entry);
  }

  private JournalLine line(
      Account account, String description, BigDecimal debit, BigDecimal credit) {
    JournalLine line = new JournalLine();
    line.setAccount(account);
    line.setDescription(description);
    line.setDebit(debit);
    line.setCredit(credit);
    return line;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> fetchDataMap(String path) {
    return (Map<String, Object>) fetchDataObject(path);
  }

  private Object fetchDataObject(String path) {
    ResponseEntity<Map> response =
        rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    return response.getBody().get("data");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> castListOfMap(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private HttpHeaders authHeaders() {
    Map<String, Object> payload =
        Map.of(
            "email", ACCOUNTING_EMAIL,
            "password", PASSWORD,
            "companyCode", COMPANY_CODE);
    ResponseEntity<Map> loginResponse =
        rest.postForEntity("/api/v1/auth/login", payload, Map.class);
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    HttpHeaders authHeaders = new HttpHeaders();
    authHeaders.setBearerAuth(String.valueOf(loginResponse.getBody().get("accessToken")));
    authHeaders.set("X-Company-Code", COMPANY_CODE);
    return authHeaders;
  }
}
