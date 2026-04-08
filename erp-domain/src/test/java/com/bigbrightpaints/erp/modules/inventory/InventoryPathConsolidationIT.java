package com.bigbrightpaints.erp.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryBatchSource;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovement;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryReference;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;

@Tag("critical")
class InventoryPathConsolidationIT extends AbstractIntegrationTest {

  private static final String COMPANY_CODE = "INVPATH";
  private static final String ACCOUNTING_EMAIL = "inventory-accounting@bbp.com";
  private static final String PASSWORD = "changeme";

  @Autowired private TestRestTemplate rest;
  @Autowired private RawMaterialRepository rawMaterialRepository;
  @Autowired private RawMaterialBatchRepository rawMaterialBatchRepository;
  @Autowired private FinishedGoodRepository finishedGoodRepository;
  @Autowired private FinishedGoodBatchRepository finishedGoodBatchRepository;
  @Autowired private InventoryMovementRepository inventoryMovementRepository;

  private Long finishedGoodId;
  private Long finishedGoodBatchId;

  @BeforeEach
  void setUp() {
    dataSeeder.ensureUser(
        ACCOUNTING_EMAIL,
        PASSWORD,
        "Inventory Accounting",
        COMPANY_CODE,
        List.of("ROLE_ACCOUNTING"));
    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    seedRawMaterial(company);
    seedFinishedGood(company);
  }

  @Test
  void rawMaterialEndpoints_areMappedUnderApiV1AndAccessibleToAccounting() {
    HttpHeaders headers = authHeaders();

    ResponseEntity<Map> stockResponse =
        rest.exchange(
            "/api/v1/raw-materials/stock", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    assertThat(stockResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> stockRows = responseDataList(stockResponse);
    assertThat(stockRows).isNotEmpty();
    assertThat(stockRows.getFirst()).containsKeys("materialId", "quantity");

    ResponseEntity<Map> inventoryResponse =
        rest.exchange(
            "/api/v1/raw-materials/stock/inventory",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(inventoryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> inventoryRows = responseDataList(inventoryResponse);
    assertThat(inventoryRows).isNotEmpty();
    assertThat(inventoryRows.getFirst()).containsKeys("materialId", "quantity", "status");
  }

  @Test
  void finishedGoodsEndpoints_areMappedAndAccessibleToAccounting() {
    HttpHeaders headers = authHeaders();

    ResponseEntity<Map> listResponse =
        rest.exchange(
            "/api/v1/finished-goods?page=0&size=10",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page = responseDataMap(listResponse);
    assertThat(page).containsKeys("content", "totalElements", "totalPages", "page", "size");
    List<Map<String, Object>> content = castList(page.get("content"));
    assertThat(content).isNotEmpty();
    assertThat(content.getFirst()).containsKeys("id", "name", "productCode");

    ResponseEntity<Map> summaryResponse =
        rest.exchange(
            "/api/v1/finished-goods/stock-summary",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(summaryResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> summaryRows = responseDataList(summaryResponse);
    assertThat(summaryRows).isNotEmpty();
    assertThat(summaryRows.getFirst())
        .containsKeys("finishedGoodId", "totalStock", "reservedStock");

    ResponseEntity<Map> batchesResponse =
        rest.exchange(
            "/api/v1/finished-goods/" + finishedGoodId + "/batches",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(batchesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> batches = responseDataList(batchesResponse);
    assertThat(batches).isNotEmpty();
    assertThat(batches.getFirst()).containsKeys("batchId", "quantity", "expiryDate");

    ResponseEntity<Map> lowStockResponse =
        rest.exchange(
            "/api/v1/finished-goods/low-stock?threshold=1000",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(lowStockResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(responseDataList(lowStockResponse)).isNotEmpty();

    ResponseEntity<Map> thresholdGetResponse =
        rest.exchange(
            "/api/v1/finished-goods/" + finishedGoodId + "/low-stock-threshold",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(thresholdGetResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(responseDataMap(thresholdGetResponse)).containsKey("threshold");

    ResponseEntity<Map> thresholdPutResponse =
        rest.exchange(
            "/api/v1/finished-goods/" + finishedGoodId + "/low-stock-threshold",
            HttpMethod.PUT,
            new HttpEntity<>(Map.of("threshold", new BigDecimal("42.00")), jsonHeaders()),
            Map.class);
    assertThat(thresholdPutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(responseDataMap(thresholdPutResponse).get("threshold")).isEqualTo(42.0);
  }

  @Test
  void inventoryBatchRoutes_andAdjustmentsRoute_areMappedForAccounting() {
    HttpHeaders headers = authHeaders();

    ResponseEntity<Map> expiringResponse =
        rest.exchange(
            "/api/v1/inventory/batches/expiring-soon?days=30",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(expiringResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> expiringRows = responseDataList(expiringResponse);
    assertThat(expiringRows).isNotEmpty();
    assertThat(expiringRows.getFirst()).containsKeys("batchId", "expiryDate");

    ResponseEntity<Map> movementResponse =
        rest.exchange(
            "/api/v1/inventory/batches/" + finishedGoodBatchId + "/movements",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class);
    assertThat(movementResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map<String, Object>> movementRows = responseDataList(movementResponse);
    assertThat(movementRows).isNotEmpty();
    assertThat(movementRows.getFirst()).containsKeys("movementType", "quantity", "timestamp");

    ResponseEntity<Map> adjustmentsResponse =
        rest.exchange(
            "/api/v1/inventory/adjustments", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    assertThat(adjustmentsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void inventoryAdjustmentsHistory_remainsReadableAfterCreate() {
    ResponseEntity<Map> beforeCreateResponse =
        rest.exchange(
            "/api/v1/inventory/adjustments",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            Map.class);
    assertThat(beforeCreateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    Company company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    FinishedGood fixtureFinishedGood =
        finishedGoodRepository.findByCompanyAndProductCode(company, "FG-FIXTURE").orElseThrow();
    assertThat(company.getDefaultCogsAccountId()).isNotNull();

    String idempotencyKey = "INVPATH-ADJ-" + UUID.randomUUID();
    Map<String, Object> createRequest =
        Map.of(
            "adjustmentDate",
            LocalDate.now().toString(),
            "type",
            "DAMAGED",
            "adjustmentAccountId",
            company.getDefaultCogsAccountId(),
            "reason",
            "InventoryPathConsolidationIT read cycle",
            "adminOverride",
            false,
            "idempotencyKey",
            idempotencyKey,
            "lines",
            List.of(
                Map.of(
                    "finishedGoodId",
                    fixtureFinishedGood.getId(),
                    "quantity",
                    new BigDecimal("1.00"),
                    "unitCost",
                    new BigDecimal("12.50"),
                    "note",
                    "Regression proof row")));
    HttpHeaders createHeaders = jsonHeaders();
    createHeaders.set("Idempotency-Key", idempotencyKey);
    ResponseEntity<Map> createResponse =
        rest.exchange(
            "/api/v1/inventory/adjustments",
            HttpMethod.POST,
            new HttpEntity<>(createRequest, createHeaders),
            Map.class);
    assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> createdAdjustment = responseDataMap(createResponse);
    assertThat(createdAdjustment).containsKeys("referenceNumber", "lines");

    ResponseEntity<Map> afterCreateResponse =
        rest.exchange(
            "/api/v1/inventory/adjustments",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders()),
            Map.class);
    assertThat(afterCreateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

    List<Map<String, Object>> rows = responseDataList(afterCreateResponse);
    assertThat(rows)
        .anySatisfy(
            row -> {
              assertThat(row.get("referenceNumber"))
                  .isEqualTo(createdAdjustment.get("referenceNumber"));
              List<Map<String, Object>> lines = castList(row.get("lines"));
              assertThat(lines).isNotEmpty();
              assertThat(lines.getFirst())
                  .containsKeys(
                      "finishedGoodId", "finishedGoodName", "quantity", "unitCost", "amount");
            });
  }

  private void seedRawMaterial(Company company) {
    RawMaterial material =
        rawMaterialRepository
            .findByCompanyAndSku(company, "RM-INVPATH-001")
            .orElseGet(
                () -> {
                  RawMaterial created = new RawMaterial();
                  created.setCompany(company);
                  created.setName("Inventory Path Raw Material");
                  created.setSku("RM-INVPATH-001");
                  created.setUnitType("KG");
                  created.setCurrentStock(new BigDecimal("50.00"));
                  created.setReorderLevel(new BigDecimal("25.00"));
                  created.setMinStock(new BigDecimal("10.00"));
                  created.setMaxStock(new BigDecimal("200.00"));
                  return rawMaterialRepository.save(created);
                });

    rawMaterialBatchRepository.findByRawMaterial(material).stream()
        .filter(batch -> "RM-INVPATH-BATCH".equals(batch.getBatchCode()))
        .findFirst()
        .orElseGet(
            () -> {
              RawMaterialBatch batch = new RawMaterialBatch();
              batch.setRawMaterial(material);
              batch.setBatchCode("RM-INVPATH-BATCH");
              batch.setQuantity(new BigDecimal("10.00"));
              batch.setUnit("KG");
              batch.setCostPerUnit(new BigDecimal("12.50"));
              batch.setManufacturedAt(Instant.now().minusSeconds(86_400));
              batch.setExpiryDate(LocalDate.now().plusDays(7));
              batch.setSource(InventoryBatchSource.PURCHASE);
              return rawMaterialBatchRepository.save(batch);
            });
  }

  private void seedFinishedGood(Company company) {
    FinishedGood finishedGood =
        finishedGoodRepository
            .findByCompanyAndProductCode(company, "FG-INVPATH-001")
            .orElseGet(
                () -> {
                  FinishedGood created = new FinishedGood();
                  created.setCompany(company);
                  created.setProductCode("FG-INVPATH-001");
                  created.setName("Inventory Path Finished Good");
                  created.setUnit("LITER");
                  created.setCurrentStock(new BigDecimal("120.00"));
                  created.setReservedStock(new BigDecimal("15.00"));
                  created.setLowStockThreshold(new BigDecimal("20.00"));
                  return finishedGoodRepository.save(created);
                });
    finishedGoodId = finishedGood.getId();

    FinishedGoodBatch batch =
        finishedGoodBatchRepository
            .findByFinishedGoodOrderByManufacturedAtAsc(finishedGood)
            .stream()
            .filter(existing -> "FG-INVPATH-BATCH".equals(existing.getBatchCode()))
            .findFirst()
            .orElseGet(
                () -> {
                  FinishedGoodBatch created = new FinishedGoodBatch();
                  created.setFinishedGood(finishedGood);
                  created.setBatchCode("FG-INVPATH-BATCH");
                  created.setQuantityTotal(new BigDecimal("80.00"));
                  created.setQuantityAvailable(new BigDecimal("65.00"));
                  created.setUnitCost(new BigDecimal("95.00"));
                  created.setManufacturedAt(Instant.now().minusSeconds(86_400));
                  created.setExpiryDate(LocalDate.now().plusDays(14));
                  created.setSource(InventoryBatchSource.PRODUCTION);
                  return finishedGoodBatchRepository.save(created);
                });
    finishedGoodBatchId = batch.getId();

    boolean movementExists =
        inventoryMovementRepository.findByFinishedGoodBatchOrderByCreatedAtAsc(batch).stream()
            .findAny()
            .isPresent();
    if (!movementExists) {
      InventoryMovement movement = new InventoryMovement();
      movement.setFinishedGood(finishedGood);
      movement.setFinishedGoodBatch(batch);
      movement.setReferenceType(InventoryReference.MANUFACTURING_ORDER);
      movement.setReferenceId("INVPATH-" + UUID.randomUUID());
      movement.setMovementType("RECEIPT");
      movement.setQuantity(new BigDecimal("65.00"));
      movement.setUnitCost(new BigDecimal("95.00"));
      inventoryMovementRepository.save(movement);
    }
  }

  private HttpHeaders authHeaders() {
    ResponseEntity<Map> login =
        rest.postForEntity(
            "/api/v1/auth/login",
            Map.of(
                "email", ACCOUNTING_EMAIL,
                "password", PASSWORD,
                "companyCode", COMPANY_CODE),
            Map.class);
    assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(login.getBody()).isNotNull();

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth((String) login.getBody().get("accessToken"));
    headers.set("X-Company-Code", COMPANY_CODE);
    return headers;
  }

  private HttpHeaders jsonHeaders() {
    HttpHeaders headers = authHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> responseDataMap(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    return (Map<String, Object>) response.getBody().get("data");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> responseDataList(ResponseEntity<Map> response) {
    assertThat(response.getBody()).isNotNull();
    return castList(response.getBody().get("data"));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> castList(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
