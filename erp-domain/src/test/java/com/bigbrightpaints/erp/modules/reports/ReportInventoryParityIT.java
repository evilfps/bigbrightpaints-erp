package com.bigbrightpaints.erp.modules.reports;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ReportInventoryParityIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "M14-S19-RPT";
    private static final String ACCOUNTING_EMAIL = "m14s19-reports-accounting@bbp.com";
    private static final String PASSWORD = "M14SlicePass123!";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private FinishedGoodRepository finishedGoodRepository;

    @Autowired
    private FinishedGoodBatchRepository finishedGoodBatchRepository;

    private Company company;

    @BeforeEach
    void setUp() {
        dataSeeder.ensureUser(
                ACCOUNTING_EMAIL,
                PASSWORD,
                "M14-S19 Reports Accounting",
                COMPANY_CODE,
                List.of("ROLE_ACCOUNTING"));
        company = companyRepository.findByCodeIgnoreCase(COMPANY_CODE).orElseThrow();
    }

    @Test
    void stockSummaryAndInventoryValuationStayInParityAcrossCostMethods() {
        HttpHeaders headers = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);

        Map<String, Object> baselineValuation = inventoryValuation(headers);
        BigDecimal baselineTotal = asDecimal(baselineValuation.get("totalValue"));
        long baselineLowStock = asLong(baselineValuation.get("lowStockItems"));

        String prefix = "M14S19-" + shortId();
        String fifoCode = prefix + "-FIFO";
        String lifoCode = prefix + "-LIFO";
        String wacCode = prefix + "-WAC";
        String reservedDriftCode = prefix + "-RES-DRIFT";

        seedFinishedGoodWithBatches(
                fifoCode,
                "FIFO",
                new BigDecimal("5"),
                new BigDecimal("2"),
                List.of(
                        new BatchSeed(fifoCode + "-OLD", new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("5"), Instant.parse("2026-01-01T00:00:00Z")),
                        new BatchSeed(fifoCode + "-NEW", new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("20"), Instant.parse("2026-01-02T00:00:00Z"))
                ));
        seedFinishedGoodWithBatches(
                lifoCode,
                "LIFO",
                new BigDecimal("5"),
                new BigDecimal("1"),
                List.of(
                        new BatchSeed(lifoCode + "-OLD", new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("5"), Instant.parse("2026-01-01T00:00:00Z")),
                        new BatchSeed(lifoCode + "-NEW", new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("20"), Instant.parse("2026-01-02T00:00:00Z"))
                ));
        seedFinishedGoodWithBatches(
                wacCode,
                "WAC",
                new BigDecimal("10"),
                new BigDecimal("3"),
                List.of(
                        new BatchSeed(wacCode + "-A", new BigDecimal("4"), new BigDecimal("4"), new BigDecimal("8"), Instant.parse("2026-01-01T00:00:00Z")),
                        new BatchSeed(wacCode + "-B", new BigDecimal("6"), new BigDecimal("6"), new BigDecimal("12"), Instant.parse("2026-01-02T00:00:00Z"))
                ));
        seedFinishedGoodWithBatches(
                reservedDriftCode,
                "FIFO",
                new BigDecimal("2"),
                new BigDecimal("5"),
                List.of(new BatchSeed(
                        reservedDriftCode + "-ONLY",
                        new BigDecimal("2"),
                        BigDecimal.ZERO,
                        new BigDecimal("7"),
                        Instant.parse("2026-01-03T00:00:00Z"))));

        List<Map<String, Object>> trackedRows = stockSummaryRows(headers).stream()
                .filter(row -> String.valueOf(row.get("code")).startsWith(prefix))
                .toList();

        assertThat(trackedRows).hasSize(4);

        Map<String, Map<String, Object>> byCode = trackedRows.stream()
                .collect(Collectors.toMap(row -> String.valueOf(row.get("code")), row -> row));

        assertThat(asDecimal(byCode.get(fifoCode).get("weightedAverageCost")))
                .isEqualByComparingTo(new BigDecimal("14"));
        assertThat(asDecimal(byCode.get(lifoCode).get("weightedAverageCost")))
                .isEqualByComparingTo(new BigDecimal("20"));
        assertThat(asDecimal(byCode.get(wacCode).get("weightedAverageCost")))
                .isEqualByComparingTo(new BigDecimal("10.4"));

        BigDecimal expectedValueDelta = trackedRows.stream()
                .map(row -> asDecimal(row.get("currentStock")).multiply(asDecimal(row.get("weightedAverageCost"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        long expectedLowStockDelta = trackedRows.stream()
                .filter(row -> asDecimal(row.get("currentStock")).compareTo(asDecimal(row.get("reservedStock"))) < 0)
                .count();

        Map<String, Object> afterValuation = inventoryValuation(headers);
        BigDecimal afterTotal = asDecimal(afterValuation.get("totalValue"));
        long afterLowStock = asLong(afterValuation.get("lowStockItems"));

        assertThat(afterTotal.subtract(baselineTotal))
                .isEqualByComparingTo(expectedValueDelta);
        assertThat(afterLowStock - baselineLowStock)
                .isEqualTo(expectedLowStockDelta);
    }

    private void seedFinishedGoodWithBatches(String productCode,
                                             String costingMethod,
                                             BigDecimal currentStock,
                                             BigDecimal reservedStock,
                                             List<BatchSeed> batches) {
        FinishedGood finishedGood = new FinishedGood();
        finishedGood.setCompany(company);
        finishedGood.setProductCode(productCode);
        finishedGood.setName(productCode);
        finishedGood.setUnit("UNIT");
        finishedGood.setCostingMethod(costingMethod);
        finishedGood.setCurrentStock(currentStock);
        finishedGood.setReservedStock(reservedStock);
        finishedGood.setValuationAccountId(100L);
        finishedGood.setCogsAccountId(200L);
        finishedGood.setRevenueAccountId(300L);
        finishedGood.setTaxAccountId(400L);
        FinishedGood saved = finishedGoodRepository.saveAndFlush(finishedGood);

        for (BatchSeed batchSeed : batches) {
            FinishedGoodBatch batch = new FinishedGoodBatch();
            batch.setFinishedGood(saved);
            batch.setBatchCode(batchSeed.batchCode());
            batch.setQuantityTotal(batchSeed.quantityTotal());
            batch.setQuantityAvailable(batchSeed.quantityAvailable());
            batch.setUnitCost(batchSeed.unitCost());
            batch.setManufacturedAt(batchSeed.manufacturedAt());
            finishedGoodBatchRepository.saveAndFlush(batch);
        }
    }

    private HttpHeaders authHeaders(String email, String password, String companyCode) {
        Map<String, Object> loginPayload = Map.of(
                "email", email,
                "password", password,
                "companyCode", companyCode
        );
        ResponseEntity<Map> loginResponse = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(String.valueOf(loginResponse.getBody().get("accessToken")));
        headers.set("X-Company-Code", companyCode);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stockSummaryRows(HttpHeaders headers) {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/finished-goods/stock-summary",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inventoryValuation(HttpHeaders headers) {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/reports/inventory-valuation?date=2099-01-01",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
    }

    private BigDecimal asDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private record BatchSeed(String batchCode,
                             BigDecimal quantityTotal,
                             BigDecimal quantityAvailable,
                             BigDecimal unitCost,
                             Instant manufacturedAt) {
    }
}
