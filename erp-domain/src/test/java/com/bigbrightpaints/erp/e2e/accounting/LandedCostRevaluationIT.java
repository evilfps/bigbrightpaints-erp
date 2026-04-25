package com.bigbrightpaints.erp.e2e.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntry;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalLine;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.MaterialType;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchase;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseLine;
import com.bigbrightpaints.erp.modules.purchasing.domain.RawMaterialPurchaseRepository;
import com.bigbrightpaints.erp.modules.purchasing.domain.Supplier;
import com.bigbrightpaints.erp.modules.purchasing.domain.SupplierRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

import jakarta.persistence.EntityManager;

@DisplayName("E2E: Landed cost, revaluation, audit digest")
public class LandedCostRevaluationIT extends AbstractIntegrationTest {

  private static final String COMPANY = "VALWIP";
  private static final String ADMIN_EMAIL = "valuation@bbp.com";
  private static final String ADMIN_PASSWORD = "val123";

  @Autowired private TestRestTemplate rest;
  @Autowired private CompanyRepository companyRepository;
  @Autowired private AccountRepository accountRepository;
  @Autowired private JournalEntryRepository journalEntryRepository;
  @Autowired private SupplierRepository supplierRepository;
  @Autowired private RawMaterialPurchaseRepository rawMaterialPurchaseRepository;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private RawMaterialMovementRepository rawMaterialMovementRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Autowired private EntityManager entityManager;

  private HttpHeaders headers;
  private Company company;
  private Account inventory;
  private Account offset;
  private Account reval;
  private Account payable;

  @BeforeEach
  void setup() {
    dataSeeder.ensureUser(
        ADMIN_EMAIL,
        ADMIN_PASSWORD,
        "Valuation Admin",
        COMPANY,
        java.util.List.of("ROLE_ADMIN", "ROLE_ACCOUNTING"));
    company = companyRepository.findByCodeIgnoreCase(COMPANY).orElseThrow();
    inventory = ensureAccount("INV-VAL", "Inventory", AccountType.ASSET);
    offset = ensureAccount("LC-OFF", "Landed Cost Offset", AccountType.LIABILITY);
    reval = ensureAccount("REVAL", "Revaluation Reserve", AccountType.EQUITY);
    payable = ensureAccount("AP-VAL", "Accounts Payable", AccountType.LIABILITY);
    headers = authHeaders();
  }

  @Test
  void landedCost_and_revaluation_and_digest() {
    RawMaterialPurchase purchase = ensurePurchase();
    String revaluationReference = "REVAL-" + UUID.randomUUID();

    Map<String, Object> landedReq =
        Map.of(
            "rawMaterialPurchaseId", purchase.getId(),
            "amount", new BigDecimal("250.00"),
            "inventoryAccountId", inventory.getId(),
            "offsetAccountId", offset.getId(),
            "memo", "Freight");
    ResponseEntity<Map> landedResp =
        rest.postForEntity(
            "/api/v1/accounting/inventory/landed-cost",
            new org.springframework.http.HttpEntity<>(landedReq, headers),
            Map.class);
    if (!landedResp.getStatusCode().is2xxSuccessful()) {
      System.out.println("Landed cost response: " + landedResp);
      System.out.println("Body: " + landedResp.getBody());
    }
    assertThat(landedResp.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> revalReq =
        Map.of(
            "inventoryAccountId",
            inventory.getId(),
            "revaluationAccountId",
            reval.getId(),
            "deltaAmount",
            new BigDecimal("-50.00"),
            "referenceNumber",
            revaluationReference,
            "memo",
            "Manual reval");
    ResponseEntity<Map> revalResp =
        rest.postForEntity(
            "/api/v1/accounting/inventory/revaluation",
            new org.springframework.http.HttpEntity<>(revalReq, headers),
            Map.class);
    assertThat(revalResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    JournalEntry revaluationEntry =
        journalEntryRepository
            .findByCompanyAndReferenceNumber(company, revaluationReference)
            .orElseThrow();
    assertThat(revaluationEntry.getLines()).hasSize(2);
    JournalLine inventoryLine =
        revaluationEntry.getLines().stream()
            .filter(line -> line.getAccount().getId().equals(inventory.getId()))
            .findFirst()
            .orElseThrow();
    JournalLine revaluationLine =
        revaluationEntry.getLines().stream()
            .filter(line -> line.getAccount().getId().equals(reval.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(inventoryLine.getDebit()).isEqualByComparingTo("0.00");
    assertThat(inventoryLine.getCredit()).isEqualByComparingTo("50.00");
    assertThat(revaluationLine.getDebit()).isEqualByComparingTo("50.00");
    assertThat(revaluationLine.getCredit()).isEqualByComparingTo("0.00");

    ResponseEntity<Map> auditFeed =
        rest.exchange(
            "/api/v1/accounting/audit/events?page=0&size=20",
            org.springframework.http.HttpMethod.GET,
            new org.springframework.http.HttpEntity<>(headers),
            Map.class);
    assertThat(auditFeed.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, ?> body = auditFeed.getBody();
    assertThat(body).isNotNull();
  }

  @Test
  void landedCostReplay_doesNotReapplyBatchAndMovementCosts() {
    LandedCostFixture fixture = ensurePurchaseWithReceiptLine();
    String replayKey = "LC-REPLAY-" + UUID.randomUUID();

    Map<String, Object> request =
        Map.of(
            "rawMaterialPurchaseId",
            fixture.purchaseId(),
            "amount",
            new BigDecimal("100.00"),
            "inventoryAccountId",
            inventory.getId(),
            "offsetAccountId",
            offset.getId(),
            "memo",
            "Replay-safe landed cost",
            "idempotencyKey",
            replayKey);

    ResponseEntity<Map> first =
        rest.postForEntity(
            "/api/v1/accounting/inventory/landed-cost",
            new org.springframework.http.HttpEntity<>(request, headers),
            Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    Long firstJournalId = asLong(data(first).get("id"));
    assertThat(firstJournalId).isNotNull();

    RawMaterialPurchaseLine lineAfterFirst = findPurchaseLine(fixture.lineId());
    BigDecimal lineUnitCostAfterFirst = lineAfterFirst.getCostPerUnit();
    BigDecimal lineTotalAfterFirst = lineAfterFirst.getLineTotal();
    BigDecimal batchCostAfterFirst =
        rawMaterialBatchRepository.findById(fixture.batchId()).orElseThrow().getCostPerUnit();
    BigDecimal movementCostAfterFirst =
        rawMaterialMovementRepository.findById(fixture.movementId()).orElseThrow().getUnitCost();

    ResponseEntity<Map> replay =
        rest.postForEntity(
            "/api/v1/accounting/inventory/landed-cost",
            new org.springframework.http.HttpEntity<>(request, headers),
            Map.class);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(asLong(data(replay).get("id"))).isEqualTo(firstJournalId);

    RawMaterialPurchaseLine lineAfterReplay = findPurchaseLine(fixture.lineId());
    assertThat(lineAfterReplay.getCostPerUnit()).isEqualByComparingTo(lineUnitCostAfterFirst);
    assertThat(lineAfterReplay.getLineTotal()).isEqualByComparingTo(lineTotalAfterFirst);
    assertThat(
            rawMaterialBatchRepository.findById(fixture.batchId()).orElseThrow().getCostPerUnit())
        .isEqualByComparingTo(batchCostAfterFirst);
    assertThat(
            rawMaterialMovementRepository
                .findById(fixture.movementId())
                .orElseThrow()
                .getUnitCost())
        .isEqualByComparingTo(movementCostAfterFirst);

    Map<String, Object> mismatchedRequest = new HashMap<>(request);
    mismatchedRequest.put("amount", new BigDecimal("125.00"));
    ResponseEntity<Map> mismatched =
        rest.postForEntity(
            "/api/v1/accounting/inventory/landed-cost",
            new org.springframework.http.HttpEntity<>(mismatchedRequest, headers),
            Map.class);
    assertThat(mismatched.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            journalEntryRepository.findByCompanyAndReferenceNumber(company, replayKey).stream()
                .count())
        .isEqualTo(1L);
  }

  @Test
  void revaluationReplay_doesNotReapplyBatchDelta() {
    Account scopedInventory =
        ensureAccount("INV-RPL-" + UUID.randomUUID(), "Replay inventory", AccountType.ASSET);
    FinishedGoodBatch firstBatch =
        ensureFinishedGoodBatch(
            scopedInventory,
            "RPL-FG-1-" + UUID.randomUUID(),
            new BigDecimal("10"),
            new BigDecimal("20.00"));
    FinishedGoodBatch secondBatch =
        ensureFinishedGoodBatch(
            scopedInventory,
            "RPL-FG-2-" + UUID.randomUUID(),
            new BigDecimal("15"),
            new BigDecimal("30.00"));
    String replayKey = "REVAL-REPLAY-" + UUID.randomUUID();

    Map<String, Object> request =
        Map.of(
            "inventoryAccountId",
            scopedInventory.getId(),
            "revaluationAccountId",
            reval.getId(),
            "deltaAmount",
            new BigDecimal("12.00"),
            "memo",
            "Replay-safe revaluation",
            "idempotencyKey",
            replayKey);

    ResponseEntity<Map> first =
        rest.postForEntity(
            "/api/v1/accounting/inventory/revaluation",
            new org.springframework.http.HttpEntity<>(request, headers),
            Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    Long firstJournalId = asLong(data(first).get("id"));
    assertThat(firstJournalId).isNotNull();

    BigDecimal firstBatchCostAfterFirst =
        finishedGoodBatchRepository.findById(firstBatch.getId()).orElseThrow().getUnitCost();
    BigDecimal secondBatchCostAfterFirst =
        finishedGoodBatchRepository.findById(secondBatch.getId()).orElseThrow().getUnitCost();

    ResponseEntity<Map> replay =
        rest.postForEntity(
            "/api/v1/accounting/inventory/revaluation",
            new org.springframework.http.HttpEntity<>(request, headers),
            Map.class);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(asLong(data(replay).get("id"))).isEqualTo(firstJournalId);

    assertThat(finishedGoodBatchRepository.findById(firstBatch.getId()).orElseThrow().getUnitCost())
        .isEqualByComparingTo(firstBatchCostAfterFirst);
    assertThat(
            finishedGoodBatchRepository.findById(secondBatch.getId()).orElseThrow().getUnitCost())
        .isEqualByComparingTo(secondBatchCostAfterFirst);

    Map<String, Object> mismatchedRequest = new HashMap<>(request);
    mismatchedRequest.put("deltaAmount", new BigDecimal("15.00"));
    ResponseEntity<Map> mismatched =
        rest.postForEntity(
            "/api/v1/accounting/inventory/revaluation",
            new org.springframework.http.HttpEntity<>(mismatchedRequest, headers),
            Map.class);
    assertThat(mismatched.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            journalEntryRepository.findByCompanyAndReferenceNumber(company, replayKey).stream()
                .count())
        .isEqualTo(1L);
    assertThat(finishedGoodBatchRepository.findById(firstBatch.getId()).orElseThrow().getUnitCost())
        .isEqualByComparingTo(firstBatchCostAfterFirst);
    assertThat(
            finishedGoodBatchRepository.findById(secondBatch.getId()).orElseThrow().getUnitCost())
        .isEqualByComparingTo(secondBatchCostAfterFirst);
  }

  private RawMaterialPurchase ensurePurchase() {
    Supplier supplier =
        supplierRepository
            .findByCompanyAndCodeIgnoreCase(company, "VSUP")
            .orElseGet(
                () -> {
                  Supplier s = new Supplier();
                  s.setCompany(company);
                  s.setName("Val Supplier");
                  s.setCode("VSUP");
                  s.setEmail("val-supplier@bbp.com");
                  s.setPayableAccount(payable);
                  return supplierRepository.save(s);
                });
    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setInvoiceNumber("VAL-" + UUID.randomUUID());
    purchase.setInvoiceDate(LocalDate.now());
    purchase.setTotalAmount(new BigDecimal("1000"));
    purchase.setStatus("POSTED");
    return rawMaterialPurchaseRepository.save(purchase);
  }

  private LandedCostFixture ensurePurchaseWithReceiptLine() {
    Supplier supplier =
        supplierRepository
            .findByCompanyAndCodeIgnoreCase(company, "VSUP")
            .orElseGet(
                () -> {
                  Supplier s = new Supplier();
                  s.setCompany(company);
                  s.setName("Val Supplier");
                  s.setCode("VSUP");
                  s.setEmail("val-supplier@bbp.com");
                  s.setPayableAccount(payable);
                  return supplierRepository.save(s);
                });
    String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    RawMaterial rawMaterial = new RawMaterial();
    rawMaterial.setCompany(company);
    rawMaterial.setSku("RM-" + suffix);
    rawMaterial.setName("Raw material " + suffix);
    rawMaterial.setUnitType("KG");
    rawMaterial.setMaterialType(MaterialType.PRODUCTION);
    rawMaterial.setInventoryAccountId(inventory.getId());
    rawMaterial.setCurrentStock(new BigDecimal("50"));
    RawMaterial savedMaterial = rawMaterialRepository.save(rawMaterial);

    RawMaterialBatch batch = new RawMaterialBatch();
    batch.setRawMaterial(savedMaterial);
    batch.setBatchCode("BATCH-" + suffix);
    batch.setQuantity(new BigDecimal("50"));
    batch.setUnit("KG");
    batch.setCostPerUnit(new BigDecimal("10.00"));
    RawMaterialBatch savedBatch = rawMaterialBatchRepository.save(batch);

    RawMaterialPurchase purchase = new RawMaterialPurchase();
    purchase.setCompany(company);
    purchase.setSupplier(supplier);
    purchase.setInvoiceNumber("VAL-LINE-" + suffix);
    purchase.setInvoiceDate(LocalDate.now());
    purchase.setTotalAmount(new BigDecimal("500.00"));
    purchase.setStatus("POSTED");

    RawMaterialPurchaseLine line = new RawMaterialPurchaseLine();
    line.setPurchase(purchase);
    line.setRawMaterial(savedMaterial);
    line.setRawMaterialBatch(savedBatch);
    line.setBatchCode(savedBatch.getBatchCode());
    line.setQuantity(new BigDecimal("50"));
    line.setUnit("KG");
    line.setCostPerUnit(new BigDecimal("10.00"));
    line.setLineTotal(new BigDecimal("500.00"));
    purchase.getLines().add(line);
    RawMaterialPurchase savedPurchase = rawMaterialPurchaseRepository.saveAndFlush(purchase);
    Long lineId = savedPurchase.getLines().getFirst().getId();

    RawMaterialMovement movement = new RawMaterialMovement();
    movement.setRawMaterial(savedMaterial);
    movement.setRawMaterialBatch(savedBatch);
    movement.setReferenceType("GOODS_RECEIPT");
    movement.setReferenceId("GRN-" + suffix);
    movement.setMovementType("RECEIPT");
    movement.setQuantity(new BigDecimal("50"));
    movement.setUnitCost(new BigDecimal("10.00"));
    RawMaterialMovement savedMovement = rawMaterialMovementRepository.saveAndFlush(movement);

    return new LandedCostFixture(
        savedPurchase.getId(), lineId, savedBatch.getId(), savedMovement.getId());
  }

  private FinishedGoodBatch ensureFinishedGoodBatch(
      Account valuationAccount, String productCode, BigDecimal quantity, BigDecimal unitCost) {
    FinishedGood finishedGood = new FinishedGood();
    finishedGood.setCompany(company);
    finishedGood.setProductCode(productCode);
    finishedGood.setName("FG " + productCode);
    finishedGood.setUnit("L");
    finishedGood.setCurrentStock(quantity);
    finishedGood.setReservedStock(BigDecimal.ZERO);
    finishedGood.setValuationAccountId(valuationAccount.getId());
    FinishedGood savedFinishedGood = finishedGoodRepository.saveAndFlush(finishedGood);

    FinishedGoodBatch batch = new FinishedGoodBatch();
    batch.setFinishedGood(savedFinishedGood);
    batch.setBatchCode(productCode + "-B1");
    batch.setQuantityTotal(quantity);
    batch.setQuantityAvailable(quantity);
    batch.setUnitCost(unitCost);
    batch.setManufacturedAt(Instant.now());
    return finishedGoodBatchRepository.saveAndFlush(batch);
  }

  private RawMaterialPurchaseLine findPurchaseLine(Long lineId) {
    RawMaterialPurchaseLine line = entityManager.find(RawMaterialPurchaseLine.class, lineId);
    assertThat(line).isNotNull();
    return line;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> data(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    return (Map<String, Object>) response.getBody().get("data");
  }

  private Long asLong(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  private Account ensureAccount(String code, String name, AccountType type) {
    return accountRepository
        .findByCompanyAndCodeIgnoreCase(company, code)
        .orElseGet(
            () -> {
              Account a = new Account();
              a.setCompany(company);
              a.setCode(code);
              a.setName(name);
              a.setType(type);
              a.setBalance(BigDecimal.ZERO);
              return accountRepository.save(a);
            });
  }

  private HttpHeaders authHeaders() {
    Map<String, Object> req =
        Map.of(
            "email", ADMIN_EMAIL,
            "password", ADMIN_PASSWORD,
            "companyCode", COMPANY);
    ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login", req, Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    String token = (String) login.getBody().get("accessToken");
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(token);
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set("X-Company-Code", COMPANY);
    return h;
  }

  private record LandedCostFixture(Long purchaseId, Long lineId, Long batchId, Long movementId) {}
}
