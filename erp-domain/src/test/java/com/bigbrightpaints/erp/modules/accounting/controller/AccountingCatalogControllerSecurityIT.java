package com.bigbrightpaints.erp.modules.accounting.controller;

import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImport;
import com.bigbrightpaints.erp.modules.production.domain.CatalogImportRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionBrandRepository;
import com.bigbrightpaints.erp.modules.production.domain.ProductionProductRepository;
import com.bigbrightpaints.erp.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import jakarta.persistence.EntityManager;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

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

    @SpyBean(proxyTargetAware = true)
    private RawMaterialRepository rawMaterialRepository;

    @Autowired
    private ProductionBrandRepository productionBrandRepository;

    @Autowired
    private ProductionProductRepository productionProductRepository;

    @Autowired
    private EntityManager entityManager;

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
    void accountingCatalogImport_sameSkuMismatchDoesNotMutateWinnerRows() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-IDEMP-SAME-SKU-" + shortId();
        String sharedSku = "RM-IDEMP-SHARED-" + shortId();
        String winnerProductName = "RBAC Winner " + shortId();
        String loserProductName = "RBAC Loser " + shortId();

        ResponseEntity<Map> firstResponse = importCatalogWithCustomFile(
                accountingHeaders,
                catalogCsvContent(sharedSku, winnerProductName),
                "catalog-" + sharedSku + "-winner.csv",
                "text/csv",
                idempotencyKey);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        CatalogImport winnerImport = catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)
                .orElseThrow();
        var winnerProduct = productionProductRepository.findByCompanyAndSkuCode(company, sharedSku).orElseThrow();
        var winnerMaterial = rawMaterialRepository.findByCompanyAndSku(company, sharedSku).orElseThrow();
        Long winnerProductId = winnerProduct.getId();
        String winnerPersistedProductName = winnerProduct.getProductName();
        Long winnerMaterialId = winnerMaterial.getId();
        String winnerPersistedMaterialName = winnerMaterial.getName();

        ResponseEntity<Map> secondResponse = importCatalogWithCustomFile(
                accountingHeaders,
                catalogCsvContent(sharedSku, loserProductName),
                "catalog-" + sharedSku + "-loser.csv",
                "text/csv",
                idempotencyKey);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(secondResponse.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> errorData = (Map<String, Object>) secondResponse.getBody().get("data");
        assertThat(errorData).containsEntry("code", "CONC_001");

        CatalogImport persistedImport = catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)
                .orElseThrow();
        assertThat(persistedImport.getId()).isEqualTo(winnerImport.getId());
        assertThat(persistedImport.getRowsProcessed()).isEqualTo(winnerImport.getRowsProcessed());
        assertThat(persistedImport.getProductsCreated()).isEqualTo(winnerImport.getProductsCreated());
        assertThat(persistedImport.getProductsUpdated()).isEqualTo(winnerImport.getProductsUpdated());

        var productAfterConflict = productionProductRepository.findByCompanyAndSkuCode(company, sharedSku).orElseThrow();
        var materialAfterConflict = rawMaterialRepository.findByCompanyAndSku(company, sharedSku).orElseThrow();
        assertThat(productAfterConflict.getId()).isEqualTo(winnerProductId);
        assertThat(productAfterConflict.getProductName()).isEqualTo(winnerPersistedProductName);
        assertThat(productAfterConflict.getProductName()).isEqualTo(winnerProductName);
        assertThat(materialAfterConflict.getId()).isEqualTo(winnerMaterialId);
        assertThat(materialAfterConflict.getName()).isEqualTo(winnerPersistedMaterialName);
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
    void accountingCatalogImport_concurrentMismatchWinnerReplayStaysStableAndLoserNeverMaterializes() throws Exception {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-IDEMP-RACE-REPLAY-" + shortId();
        String firstSku = "RM-RACE-R1-" + shortId();
        String secondSku = "RM-RACE-R2-" + shortId();

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

            boolean firstWon = firstResponse.getStatusCode() == HttpStatus.OK;
            String winnerSku = firstWon ? firstSku : secondSku;
            String loserSku = firstWon ? secondSku : firstSku;

            CatalogImport winnerRecord = catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)
                    .orElseThrow();
            Long winnerRecordId = winnerRecord.getId();
            Integer winnerRowsProcessed = winnerRecord.getRowsProcessed();
            Integer winnerProductsCreated = winnerRecord.getProductsCreated();
            Integer winnerProductsUpdated = winnerRecord.getProductsUpdated();
            Integer winnerRawMaterialsSeeded = winnerRecord.getRawMaterialsSeeded();
            String winnerErrorsJson = winnerRecord.getErrorsJson();

            ResponseEntity<Map> winnerReplay = importCatalog(accountingHeaders, winnerSku, idempotencyKey);
            assertThat(winnerReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(winnerReplay.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> winnerReplayData = (Map<String, Object>) winnerReplay.getBody().get("data");
            assertThat(winnerReplayData).containsEntry("rowsProcessed", winnerRowsProcessed);
            assertThat(winnerReplayData).containsEntry("productsCreated", winnerProductsCreated);
            assertThat(winnerReplayData).containsEntry("productsUpdated", winnerProductsUpdated);
            assertThat(winnerReplayData).containsEntry("rawMaterialsSeeded", winnerRawMaterialsSeeded);

            ResponseEntity<Map> loserReplayFirst = importCatalog(accountingHeaders, loserSku, idempotencyKey);
            assertThat(loserReplayFirst.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(loserReplayFirst.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> loserReplayFirstError = (Map<String, Object>) loserReplayFirst.getBody().get("data");
            assertThat(loserReplayFirstError).containsEntry("code", "CONC_001");

            ResponseEntity<Map> loserReplaySecond = importCatalog(accountingHeaders, loserSku, idempotencyKey);
            assertThat(loserReplaySecond.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(loserReplaySecond.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> loserReplaySecondError = (Map<String, Object>) loserReplaySecond.getBody().get("data");
            assertThat(loserReplaySecondError).containsEntry("code", "CONC_001");

            CatalogImport persistedAfterReplays = catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)
                    .orElseThrow();
            assertThat(persistedAfterReplays.getId()).isEqualTo(winnerRecordId);
            assertThat(persistedAfterReplays.getRowsProcessed()).isEqualTo(winnerRowsProcessed);
            assertThat(persistedAfterReplays.getProductsCreated()).isEqualTo(winnerProductsCreated);
            assertThat(persistedAfterReplays.getProductsUpdated()).isEqualTo(winnerProductsUpdated);
            assertThat(persistedAfterReplays.getRawMaterialsSeeded()).isEqualTo(winnerRawMaterialsSeeded);
            assertThat(persistedAfterReplays.getErrorsJson()).isEqualTo(winnerErrorsJson);

            assertThat(rawMaterialRepository.findByCompanyAndSku(company, winnerSku)).isPresent();
            assertThat(rawMaterialRepository.findByCompanyAndSku(company, loserSku)).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void accountingCatalogImport_staleRetryReplayKeepsWinnerPayloadStable() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-STALE-RETRY-" + shortId();
        String winnerSku = "RM-STALE-" + shortId();
        String winnerProductName = "RBAC Stale Winner " + shortId();
        AtomicBoolean failFirstSave = new AtomicBoolean(true);

        doAnswer(invocation -> {
            RawMaterial material = invocation.getArgument(0);
            if (material != null
                    && winnerSku.equalsIgnoreCase(material.getSku())
                    && failFirstSave.compareAndSet(true, false)) {
                throw new ObjectOptimisticLockingFailureException(
                        RawMaterial.class,
                        material.getId() == null ? -1L : material.getId());
            }
            return persistRawMaterial(material);
        }).when(rawMaterialRepository).save(any(RawMaterial.class));

        try {
            ResponseEntity<Map> firstResponse = importCatalogWithCustomFile(
                    accountingHeaders,
                    catalogCsvContent(winnerSku, winnerProductName),
                    "catalog-" + winnerSku + ".csv",
                    "text/csv",
                    idempotencyKey);
            assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(failFirstSave).isFalse();
            verify(rawMaterialRepository, atLeast(2))
                    .save(argThat(material -> material != null && winnerSku.equalsIgnoreCase(material.getSku())));

            CatalogImport winnerRecord = catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)
                    .orElseThrow();
            Long winnerRecordId = winnerRecord.getId();
            Integer winnerRowsProcessed = winnerRecord.getRowsProcessed();
            Integer winnerProductsCreated = winnerRecord.getProductsCreated();
            Integer winnerProductsUpdated = winnerRecord.getProductsUpdated();
            Integer winnerRawMaterialsSeeded = winnerRecord.getRawMaterialsSeeded();
            String winnerErrorsJson = winnerRecord.getErrorsJson();

            var winnerProduct = productionProductRepository.findByCompanyAndSkuCode(company, winnerSku).orElseThrow();
            var winnerMaterial = rawMaterialRepository.findByCompanyAndSku(company, winnerSku).orElseThrow();
            Long winnerProductId = winnerProduct.getId();
            String winnerPersistedProductName = winnerProduct.getProductName();
            Long winnerMaterialId = winnerMaterial.getId();
            String winnerPersistedMaterialName = winnerMaterial.getName();

            ResponseEntity<Map> replayResponse = importCatalogWithCustomFile(
                    accountingHeaders,
                    catalogCsvContent(winnerSku, winnerProductName),
                    "catalog-" + winnerSku + "-replay.csv",
                    "text/csv",
                    idempotencyKey);
            assertThat(replayResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replayResponse.getBody()).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> replayData = (Map<String, Object>) replayResponse.getBody().get("data");
            assertThat(replayData).containsEntry("rowsProcessed", winnerRowsProcessed);
            assertThat(replayData).containsEntry("productsCreated", winnerProductsCreated);
            assertThat(replayData).containsEntry("productsUpdated", winnerProductsUpdated);
            assertThat(replayData).containsEntry("rawMaterialsSeeded", winnerRawMaterialsSeeded);

            CatalogImport persistedAfterReplay = catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)
                    .orElseThrow();
            assertThat(persistedAfterReplay.getId()).isEqualTo(winnerRecordId);
            assertThat(persistedAfterReplay.getRowsProcessed()).isEqualTo(winnerRowsProcessed);
            assertThat(persistedAfterReplay.getProductsCreated()).isEqualTo(winnerProductsCreated);
            assertThat(persistedAfterReplay.getProductsUpdated()).isEqualTo(winnerProductsUpdated);
            assertThat(persistedAfterReplay.getRawMaterialsSeeded()).isEqualTo(winnerRawMaterialsSeeded);
            assertThat(persistedAfterReplay.getErrorsJson()).isEqualTo(winnerErrorsJson);

            var productAfterReplay = productionProductRepository.findByCompanyAndSkuCode(company, winnerSku).orElseThrow();
            var materialAfterReplay = rawMaterialRepository.findByCompanyAndSku(company, winnerSku).orElseThrow();
            assertThat(productAfterReplay.getId()).isEqualTo(winnerProductId);
            assertThat(productAfterReplay.getProductName()).isEqualTo(winnerPersistedProductName);
            assertThat(materialAfterReplay.getId()).isEqualTo(winnerMaterialId);
            assertThat(materialAfterReplay.getName()).isEqualTo(winnerPersistedMaterialName);
        } finally {
            reset(rawMaterialRepository);
        }
    }

    private RawMaterial persistRawMaterial(RawMaterial material) {
        if (material == null) {
            return null;
        }
        if (material.getId() == null) {
            entityManager.persist(material);
            entityManager.flush();
            return material;
        }
        RawMaterial merged = entityManager.merge(material);
        entityManager.flush();
        return merged;
    }

    @Test
    void accountingCatalogImport_rejectsMissingFilePartWithoutMutations() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-MISSING-FILE-" + shortId();

        ResponseEntity<Map> response = importCatalogWithoutFile(accountingHeaders, idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
    void accountingCatalogImport_acceptsApplicationCsvContentType() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-ALLOW-APPLICATION-CSV-" + shortId();
        String sku = "RM-ALLOW-APPLICATION-CSV-" + shortId();

        ResponseEntity<Map> response = importCatalogWithCustomFile(
                accountingHeaders,
                catalogCsvContent(sku),
                "catalog-" + sku + ".csv",
                "application/csv",
                idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)).isPresent();
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, sku)).isPresent();
    }

    @Test
    void accountingCatalogImport_acceptsVndMsExcelContentType() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-ALLOW-VND-MS-EXCEL-" + shortId();
        String sku = "RM-ALLOW-VND-MS-EXCEL-" + shortId();

        ResponseEntity<Map> response = importCatalogWithCustomFile(
                accountingHeaders,
                catalogCsvContent(sku),
                "catalog-" + sku + ".csv",
                "application/vnd.ms-excel",
                idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)).isPresent();
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, sku)).isPresent();
    }

    @Test
    void accountingCatalogImport_acceptsMixedCaseParameterizedTextCsvContentType() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-ALLOW-MIXED-TEXT-CSV-" + shortId();
        String sku = "RM-ALLOW-MIXED-TEXT-CSV-" + shortId();

        ResponseEntity<Map> response = importCatalogWithRawPartContentType(
                accountingHeaders,
                catalogCsvContent(sku),
                "catalog-" + sku + ".csv",
                "TeXT/CSV; CHARSET=UTF-8",
                idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)).isPresent();
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, sku)).isPresent();
    }

    @Test
    void accountingCatalogImport_acceptsMixedCaseParameterizedVndMsExcelAlias() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-ALLOW-MIXED-VND-MS-EXCEL-" + shortId();
        String sku = "RM-ALLOW-MIXED-VND-MS-EXCEL-" + shortId();

        ResponseEntity<Map> response = importCatalogWithRawPartContentType(
                accountingHeaders,
                catalogCsvContent(sku),
                "catalog-" + sku + ".csv",
                "Application/VnD.Ms-Excel; charset=UTF-8; profile=legacy",
                idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)).isPresent();
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, sku)).isPresent();
    }

    @Test
    void accountingCatalogImport_rejectsNearMissMimeEvenWithParameters() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-REJECT-NEAR-MISS-MIME-" + shortId();
        String sku = "RM-REJECT-NEAR-MISS-MIME-" + shortId();

        ResponseEntity<Map> response = importCatalogWithRawPartContentType(
                accountingHeaders,
                catalogCsvContent(sku),
                "catalog-" + sku + ".csv",
                "text/csvx; charset=UTF-8",
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
    void accountingCatalogImport_acceptsMissingFileContentTypeWhenFileNameIsCsv() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-MISSING-TYPE-CSV-" + shortId();
        String sku = "RM-MISSING-TYPE-CSV-" + shortId();

        ResponseEntity<Map> response = importCatalogWithoutPartContentType(
                accountingHeaders,
                catalogCsvContent(sku),
                "catalog-" + sku + ".csv",
                idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data).containsEntry("rowsProcessed", 1);
        assertThat(catalogImportRepository.findByCompanyAndIdempotencyKey(company, idempotencyKey)).isPresent();
        assertThat(rawMaterialRepository.findByCompanyAndSku(company, sku)).isPresent();
    }

    @Test
    void accountingCatalogImport_rejectsMissingFileContentTypeWhenFileNameIsNotCsv() {
        Company company = dataSeeder.ensureCompany(COMPANY_CODE, "Catalog Sec Co");
        HttpHeaders accountingHeaders = authHeaders(ACCOUNTING_EMAIL, PASSWORD, COMPANY_CODE);
        String idempotencyKey = "CAT-MISSING-TYPE-TXT-" + shortId();
        String sku = "RM-MISSING-TYPE-TXT-" + shortId();

        ResponseEntity<Map> response = importCatalogWithoutPartContentType(
                accountingHeaders,
                catalogCsvContent(sku),
                "catalog-" + sku + ".txt",
                idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> errorData = (Map<String, Object>) response.getBody().get("data");
        assertThat(errorData).containsEntry("code", "FILE_003");
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

    private ResponseEntity<Map> importCatalogWithoutPartContentType(HttpHeaders headers,
                                                                    String csvContent,
                                                                    String fileName,
                                                                    String idempotencyKey) {
        String boundary = "----CatalogBoundary" + shortId();
        String multipartBody = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "\r\n"
                + csvContent + "\r\n"
                + "--" + boundary + "--\r\n";

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.set("Content-Type", "multipart/form-data; boundary=" + boundary);
        requestHeaders.set("Idempotency-Key", idempotencyKey);

        return rest.exchange(
                "/api/v1/accounting/catalog/import",
                HttpMethod.POST,
                new HttpEntity<>(multipartBody, requestHeaders),
                Map.class);
    }

    private ResponseEntity<Map> importCatalogWithRawPartContentType(HttpHeaders headers,
                                                                    String csvContent,
                                                                    String fileName,
                                                                    String fileContentType,
                                                                    String idempotencyKey) {
        String boundary = "----CatalogBoundary" + shortId();
        String multipartBody = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + fileContentType + "\r\n"
                + "\r\n"
                + csvContent + "\r\n"
                + "--" + boundary + "--\r\n";

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.set("Content-Type", "multipart/form-data; boundary=" + boundary);
        requestHeaders.set("Idempotency-Key", idempotencyKey);

        return rest.exchange(
                "/api/v1/accounting/catalog/import",
                HttpMethod.POST,
                new HttpEntity<>(multipartBody, requestHeaders),
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
        return catalogCsvContent(sku, "RBAC Product " + sku);
    }

    private String catalogCsvContent(String sku, String productName) {
        return String.join("\n",
                "brand,product_name,sku_code,category,unit_of_measure,gst_rate",
                "RBAC Brand," + productName + "," + sku + ",RAW_MATERIAL,KG,18.00"
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
