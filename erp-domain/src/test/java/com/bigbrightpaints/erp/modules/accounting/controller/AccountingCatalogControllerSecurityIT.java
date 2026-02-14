package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImport;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImportRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AccountingCatalogControllerSecurityIT extends AbstractIntegrationTest {

    private static final String COMPANY_CODE = "CAT-SEC-A1";
    private static final String OTHER_COMPANY_CODE = "CAT-SEC-B1";
    private static final String PASSWORD = "CatalogSec123!";
    private static final String ADMIN_EMAIL = "catalog-sec-admin@bbp.com";
    private static final String ACCOUNTING_EMAIL = "catalog-sec-accounting@bbp.com";
    private static final String SALES_EMAIL = "catalog-sec-sales@bbp.com";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CatalogImportRepository catalogImportRepository;

    @Autowired
    private RawMaterialRepository rawMaterialRepository;

    @Autowired
    private ProductionBrandRepository productionBrandRepository;

    @Autowired
    private ProductionProductRepository productionProductRepository;

    @BeforeEach
    void setUpUsers() {
        dataSeeder.ensureUser(ADMIN_EMAIL, PASSWORD, "Catalog Sec Admin", COMPANY_CODE, List.of("ROLE_ADMIN"));
        dataSeeder.ensureUser(ACCOUNTING_EMAIL, PASSWORD, "Catalog Sec Accounting", COMPANY_CODE, List.of("ROLE_ACCOUNTING"));
        dataSeeder.ensureUser(SALES_EMAIL, PASSWORD, "Catalog Sec Sales", COMPANY_CODE, List.of("ROLE_SALES"));
        dataSeeder.ensureCompany(OTHER_COMPANY_CODE, "Catalog Sec Other Co");
    }

    @Test
    void accountingCatalogImport_allowsAdminAndAccounting_only() {
        ResponseEntity<Map> adminResponse = importCatalog(
                authHeaders(ADMIN_EMAIL, PASSWORD, COMPANY_CODE),
                "RBAC-RM-" + shortId(),
                "CAT-RBAC-ADMIN-" + shortId());
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> accountingResponse = importCatalog(
                authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE),
                "RBAC-RM-" + shortId(),
                "CAT-RBAC-ACC-" + shortId());
        assertThat(accountingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> salesResponse = importCatalog(
                authHeaders(SALES_EMAIL, PASSWORD, COMPANY_CODE),
                "RBAC-RM-" + shortId(),
                "CAT-RBAC-SALES-" + shortId());
        assertThat(salesResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void accountingCatalogImport_rejectsCrossCompanyHeaderMismatch() {
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        accountingHeaders.set("X-Company-Id", OTHER_COMPANY_CODE);

        ResponseEntity<Map> mismatchResponse = importCatalog(
                accountingHeaders,
                "RBAC-RM-MISMATCH-" + shortId(),
                "CAT-RBAC-MISMATCH-" + shortId());

        assertThat(mismatchResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void accountingCatalogImport_rejectsIdempotencyKeyPayloadMismatchWithoutPartialMutations() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-IDEMP-MISMATCH-" + shortId();
        String firstSku = "RM-IDEMP-A-" + shortId();
        String secondSku = "RM-IDEMP-B-" + shortId();

        ResponseEntity<Map> firstResponse = importCatalog(accountingHeaders, firstSku, idempotencyKey);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        Optional<CatalogImport> firstRecord = catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey);
        assertThat(firstRecord).isPresent();
        CatalogImport winnerRecord = firstRecord.get();
        int brandsAfterWinner = productionBrandRepository.findByCompanyOrderByNameAsc(company).size();
        int productsAfterWinner = productionProductRepository.findByCompanyOrderByProductNameAsc(company).size();

        ResponseEntity<Map> secondResponse = importCatalog(accountingHeaders, secondSku, idempotencyKey);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(secondResponse.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> errorData = (Map<String, Object>) secondResponse.getBody().get("data");
        assertThat(errorData).containsEntry("code", "CONC_001");

        Optional<CatalogImport> persistedRecord = catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey);
        assertThat(persistedRecord).isPresent();
        assertThat(persistedRecord.get().getId()).isEqualTo(winnerRecord.getId());
        assertThat(persistedRecord.get().getRowsProcessed()).isEqualTo(winnerRecord.getRowsProcessed());
        assertThat(persistedRecord.get().getBrandsCreated()).isEqualTo(winnerRecord.getBrandsCreated());
        assertThat(persistedRecord.get().getProductsCreated()).isEqualTo(winnerRecord.getProductsCreated());
        assertThat(persistedRecord.get().getProductsUpdated()).isEqualTo(winnerRecord.getProductsUpdated());
        assertThat(persistedRecord.get().getRawMaterialsSeeded()).isEqualTo(winnerRecord.getRawMaterialsSeeded());
        assertThat(persistedRecord.get().getErrorsJson()).isEqualTo(winnerRecord.getErrorsJson());
        assertThat(productionBrandRepository.findByCompanyOrderByNameAsc(company)).hasSize(brandsAfterWinner);
        assertThat(productionProductRepository.findByCompanyOrderByProductNameAsc(company)).hasSize(productsAfterWinner);
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, firstSku)).isPresent();
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, secondSku)).isEmpty();
    }

    @Test
    void accountingCatalogImport_concurrentMismatchProducesSingleWinnerAndConflict() throws Exception {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-IDEMP-RACE-" + shortId();
        String firstSku = "RM-RACE-A-" + shortId();
        String secondSku = "RM-RACE-B-" + shortId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map>> firstFuture = pool.submit(() ->
                    importCatalogAfterBarrier(accountingHeaders, firstSku, idempotencyKey, ready, start));
            Future<ResponseEntity<Map>> secondFuture = pool.submit(() ->
                    importCatalogAfterBarrier(accountingHeaders, secondSku, idempotencyKey, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ResponseEntity<Map> firstResponse = firstFuture.get(30, TimeUnit.SECONDS);
            ResponseEntity<Map> secondResponse = secondFuture.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map>> responses = List.of(firstResponse, secondResponse);

            long okCount = responses.stream().filter(response -> response.getStatusCode() == HttpStatus.OK).count();
            long conflictCount = responses.stream().filter(response -> response.getStatusCode() == HttpStatus.CONFLICT).count();
            assertThat(okCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);

            ResponseEntity<Map> conflictResponse = responses.stream()
                    .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                    .findFirst()
                    .orElseThrow();
            assertThat(conflictResponse.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> errorData = (Map<String, Object>) conflictResponse.getBody().get("data");
            assertThat(errorData).containsEntry("code", "CONC_001");

            Optional<CatalogImport> persistedRecord = catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey);
            assertThat(persistedRecord).isPresent();

            boolean firstCreated = rawMaterialRepository.findByCompanyAndSku(company, firstSku).isPresent();
            boolean secondCreated = rawMaterialRepository.findByCompanyAndSku(company, secondSku).isPresent();
            assertThat(firstCreated ^ secondCreated).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void accountingCatalogImport_rejectsMissingFilePartWithoutMutations() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-MISSING-FILE-" + shortId();

        ResponseEntity<Map> response = importCatalogWithoutFile(accountingHeaders, idempotencyKey);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)).isEmpty();
    }

    @Test
    void accountingCatalogImport_rejectsEmptyFileWithoutMutations() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-EMPTY-FILE-" + shortId();

        ResponseEntity<Map> response = importCatalogWithCustomFile(
                accountingHeaders,
                "",
                "empty.csv",
                "text/csv",
                idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)).isEmpty();
    }

    @Test
    void accountingCatalogImport_rejectsWrongFileContentTypeWithoutMutations() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-WRONG-TYPE-" + shortId();
        String sku = "RM-WRONG-TYPE-" + shortId();

        ResponseEntity<Map> response = importCatalogWithCustomFile(
                accountingHeaders,
                catalogCsvContent(sku),
                "catalog-" + sku + ".txt",
                "text/plain",
                idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)).isEmpty();
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, sku)).isEmpty();
    }

    @Test
    void accountingCatalogImport_rejectsDisallowedMimeEvenWhenFileNameLooksCsv() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-WRONG-MIME-CSV-" + shortId();
        String sku = "RM-WRONG-MIME-CSV-" + shortId();

        ResponseEntity<Map> response = importCatalogWithCustomFile(
                accountingHeaders,
                catalogCsvContent(sku),
                "catalog-" + sku + ".csv",
                "text/plain",
                idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)).isEmpty();
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, sku)).isEmpty();
    }

    @Test
    void accountingCatalogImport_rejectsOversizedRowWithoutInventoryMutation() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-OVERSIZED-" + shortId();
        String oversizedName = "P".repeat(2100);
        String sku = "RM-OVERSIZE-" + shortId();
        String csv = String.join("\n",
                "brand,product_name,sku_code,category,unit_of_measure,gst_rate",
                "RBAC Brand," + oversizedName + "," + sku + ",RAW_MATERIAL,KG,18.00");

        ResponseEntity<Map> response = importCatalogWithCustomFile(
                accountingHeaders,
                csv,
                "catalog-" + sku + ".csv",
                "text/csv",
                idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).containsEntry("rowsProcessed", 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) data.get("errors");
        assertThat(errors).isNotEmpty();
        assertThat(errors.getFirst().get("message").toString()).contains("exceeds max length");
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, sku)).isEmpty();
    }

    private ResponseEntity<Map> importCatalog(HttpHeaders headers, String sku, String idempotencyKey) {
        return importCatalogWithCustomFile(
                headers,
                catalogCsvContent(sku),
                "catalog-" + sku + ".csv",
                "text/csv",
                idempotencyKey);
    }

    private ResponseEntity<Map> importCatalogAfterBarrier(HttpHeaders headers,
                                                          String sku,
                                                          String idempotencyKey,
                                                          CountDownLatch ready,
                                                          CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent import");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting concurrent import start", ex);
        }
        HttpHeaders perCallHeaders = new HttpHeaders();
        perCallHeaders.putAll(headers);
        return importCatalog(perCallHeaders, sku, idempotencyKey);
    }

    private ResponseEntity<Map> importCatalogWithCustomFile(HttpHeaders headers,
                                                            String csvContent,
                                                            String fileName,
                                                            String fileContentType,
                                                            String idempotencyKey) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        if (fileContentType != null) {
            fileHeaders.setContentType(MediaType.parseMediaType(fileContentType));
        }
        body.add("file", new HttpEntity<>(catalogCsvResource(fileName, csvContent), fileHeaders));

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        requestHeaders.set("Idempotency-Key", idempotencyKey);

        return rest.exchange(
                "/api/v1/accounting/catalog/import",
                HttpMethod.POST,
                new HttpEntity<>(body, requestHeaders),
                Map.class);
    }

    private ResponseEntity<Map> importCatalogWithoutFile(HttpHeaders headers, String idempotencyKey) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        requestHeaders.set("Idempotency-Key", idempotencyKey);

        return rest.exchange(
                "/api/v1/accounting/catalog/import",
                HttpMethod.POST,
                new HttpEntity<>(body, requestHeaders),
                Map.class);
    }

    private HttpHeaders authHeaders(String email, String password, String companyCode) {
        Map<String, Object> loginPayload = Map.of(
                "email", email,
                "password", password,
                "companyCode", companyCode
        );
        ResponseEntity<Map> loginResponse = rest.postForEntity("/api/v1/auth/login", loginPayload, Map.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        String token = (String) loginResponse.getBody().get("accessToken");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Company-Id", companyCode);
        return headers;
    }

    private String catalogCsvContent(String sku) {
        return String.join("\n",
                "brand,product_name,sku_code,category,unit_of_measure,gst_rate",
                "RBAC Brand,RBAC Product " + sku + "," + sku + ",RAW_MATERIAL,KG,18.00"
        );
    }

    private ByteArrayResource catalogCsvResource(String fileName, String csvContent) {
        return new ByteArrayResource(csvContent.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
