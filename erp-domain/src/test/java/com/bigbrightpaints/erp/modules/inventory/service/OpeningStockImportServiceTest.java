package com.bigbrightpaints.erp.modules.inventory.service;

import com.bigbrightpaints.erp.core.audit.AuditEvent;
import com.bigbrightpaints.erp.core.audit.AuditService;
import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountRepository;
import com.bigbrightpaints.erp.modules.accounting.domain.AccountType;
import com.bigbrightpaints.erp.modules.accounting.domain.JournalEntryRepository;
import com.bigbrightpaints.erp.modules.accounting.dto.JournalEntryDto;
import com.bigbrightpaints.erp.modules.accounting.service.AccountingFacade;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGoodRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.InventoryMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.OpeningStockImport;
import com.bigbrightpaints.erp.modules.inventory.domain.OpeningStockImportRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterial;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatch;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialBatchRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialMovementRepository;
import com.bigbrightpaints.erp.modules.inventory.domain.RawMaterialRepository;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodDto;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodRequest;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportHistoryItem;
import com.bigbrightpaints.erp.modules.inventory.dto.OpeningStockImportResponse;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialDto;
import com.bigbrightpaints.erp.modules.inventory.dto.RawMaterialRequest;
import com.bigbrightpaints.erp.modules.production.dto.SkuReadinessDto;
import com.bigbrightpaints.erp.modules.production.service.SkuReadinessService;
import com.bigbrightpaints.erp.shared.dto.PageResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("critical")
class OpeningStockImportServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private RawMaterialRepository rawMaterialRepository;
    @Mock
    private RawMaterialBatchRepository rawMaterialBatchRepository;
    @Mock
    private RawMaterialMovementRepository rawMaterialMovementRepository;
    @Mock
    private FinishedGoodRepository finishedGoodRepository;
    @Mock
    private FinishedGoodBatchRepository finishedGoodBatchRepository;
    @Mock
    private InventoryMovementRepository inventoryMovementRepository;
    @Mock
    private SkuReadinessService skuReadinessService;
    @Mock
    private BatchNumberService batchNumberService;
    @Mock
    private RawMaterialService rawMaterialService;
    @Mock
    private FinishedGoodsService finishedGoodsService;
    @Mock
    private AccountingFacade accountingFacade;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private JournalEntryRepository journalEntryRepository;
    @Mock
    private OpeningStockImportRepository openingStockImportRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private com.bigbrightpaints.erp.core.util.CompanyClock companyClock;
    @Mock
    private org.springframework.core.env.Environment environment;

    private OpeningStockImportService service;
    private Company company;

    @BeforeEach
    void setUp() {
        service = new OpeningStockImportService(
                companyContextService,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                rawMaterialMovementRepository,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                inventoryMovementRepository,
                skuReadinessService,
                batchNumberService,
                accountingFacade,
                accountRepository,
                journalEntryRepository,
                openingStockImportRepository,
                auditService,
                new ObjectMapper(),
                companyClock,
                environment,
                new ResourcelessTransactionManager(),
                true
        );

        company = new Company();
        company.setCode("ACME");
        company.setTimezone("UTC");
        lenient().when(companyContextService.requireCurrentCompany()).thenReturn(company);
        lenient().when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(false);
        lenient().when(companyClock.today(company)).thenReturn(LocalDate.of(2026, 2, 3));
        lenient().when(skuReadinessService.forSku(eq(company), any(String.class), any()))
                .thenAnswer(invocation -> readyReadiness(invocation.getArgument(1, String.class)));
    }

    @Test
    void importOpeningStock_duplicateSkuInCsvAddsRowErrorAndSkipsDuplicateMutation() {
        String csv = String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION",
                "RAW_MATERIAL,RM-1,Resin Duplicate,KG,KG,RM-B2,2,5.00,PRODUCTION"
        );
        MockMultipartFile file = csvFile(csv);

        stubDefaultImportState(file);

        Account inventoryAccount = account(11L, "INV", "Inventory", AccountType.ASSET);
        Account openingBalance = account(22L, "OPEN-BAL", "Opening Balance", AccountType.EQUITY);
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "OPEN-BAL"))
                .thenReturn(Optional.of(openingBalance));

        RawMaterial createdMaterial = new RawMaterial();
        ReflectionTestUtils.setField(createdMaterial, "id", 101L);
        createdMaterial.setCompany(company);
        createdMaterial.setSku("RM-1");
        createdMaterial.setName("Resin");
        createdMaterial.setUnitType("KG");
        createdMaterial.setInventoryAccountId(inventoryAccount.getId());
        createdMaterial.setCurrentStock(BigDecimal.ZERO);
        when(rawMaterialRepository.findByCompanyAndSku(company, "RM-1")).thenReturn(Optional.of(createdMaterial));

        when(rawMaterialBatchRepository.existsByRawMaterialAndBatchCode(eq(createdMaterial), any())).thenReturn(false);
        when(rawMaterialBatchRepository.save(any(RawMaterialBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.save(any(RawMaterial.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialMovementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialMovementRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(accountingFacade.postInventoryAdjustment(
                eq("OPENING_STOCK"),
                any(String.class),
                eq(openingBalance.getId()),
                any(Map.class),
                eq(true),
                eq(false),
                eq("Opening stock import"),
                eq(LocalDate.of(2026, 2, 3))
        )).thenReturn(new JournalEntryDto(
                501L,
                null,
                "OPEN-STOCK-ACME-ABC",
                LocalDate.of(2026, 2, 3),
                null,
                "POSTED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null
        ));

        OpeningStockImportResponse response = service.importOpeningStock(file, "dup-sku-key");

        assertThat(response.rowsProcessed()).isEqualTo(1);
        assertThat(response.rawMaterialsCreated()).isZero();
        assertThat(response.rawMaterialBatchesCreated()).isEqualTo(1);
        assertThat(response.finishedGoodsCreated()).isZero();
        assertThat(response.finishedGoodBatchesCreated()).isZero();
        assertThat(response.results()).hasSize(1);
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().rowNumber()).isEqualTo(2L);
        assertThat(response.errors().getFirst().message())
                .isEqualTo("Duplicate SKU in import file: RM-1 (first seen at row 1)");

        verifyNoInteractions(rawMaterialService, finishedGoodsService);
        verify(accountingFacade).postInventoryAdjustment(
                eq("OPENING_STOCK"),
                any(String.class),
                eq(openingBalance.getId()),
                any(Map.class),
                eq(true),
                eq(false),
                eq("Opening stock import"),
                eq(LocalDate.of(2026, 2, 3))
        );
    }

    @Test
    void importOpeningStock_processesMixedRawMaterialAndFinishedGoodRows() {
        String csv = String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type,manufactured_at",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION,",
                "FINISHED_GOOD,FG-1,Gloss Paint,L,L,FG-B1,4,12.00,,2026-02-01"
        );
        MockMultipartFile file = csvFile(csv);

        stubDefaultImportState(file);

        Account openingBalance = account(22L, "OPEN-BAL", "Opening Balance", AccountType.EQUITY);
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "OPEN-BAL"))
                .thenReturn(Optional.of(openingBalance));

        RawMaterial rawMaterial = new RawMaterial();
        ReflectionTestUtils.setField(rawMaterial, "id", 101L);
        rawMaterial.setCompany(company);
        rawMaterial.setSku("RM-1");
        rawMaterial.setName("Resin");
        rawMaterial.setInventoryAccountId(11L);
        rawMaterial.setCurrentStock(BigDecimal.ZERO);
        when(rawMaterialRepository.findByCompanyAndSku(company, "RM-1")).thenReturn(Optional.of(rawMaterial));

        FinishedGood finishedGood = new FinishedGood();
        ReflectionTestUtils.setField(finishedGood, "id", 202L);
        finishedGood.setCompany(company);
        finishedGood.setProductCode("FG-1");
        finishedGood.setName("Gloss Paint");
        finishedGood.setCogsAccountId(44L);
        finishedGood.setRevenueAccountId(55L);
        finishedGood.setTaxAccountId(66L);
        finishedGood.setValuationAccountId(33L);
        finishedGood.setCurrentStock(BigDecimal.ZERO);
        when(finishedGoodRepository.findByCompanyAndProductCode(company, "FG-1")).thenReturn(Optional.of(finishedGood));
        SkuReadinessDto finishedGoodPreBatch = new SkuReadinessDto(
                "FG-1",
                readyStage(),
                readyStage(),
                readyStage(),
                new SkuReadinessDto.Stage(false, List.of("NO_FINISHED_GOOD_BATCH_STOCK"))
        );
        when(skuReadinessService.forSku(company, "FG-1", SkuReadinessService.ExpectedStockType.FINISHED_GOOD))
                .thenReturn(finishedGoodPreBatch, readyReadiness("FG-1"));

        when(rawMaterialBatchRepository.existsByRawMaterialAndBatchCode(eq(rawMaterial), any())).thenReturn(false);
        when(rawMaterialBatchRepository.save(any(RawMaterialBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.save(any(RawMaterial.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialMovementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(finishedGoodBatchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(finishedGoodRepository.save(any(FinishedGood.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryMovementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialMovementRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryMovementRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(accountingFacade.postInventoryAdjustment(
                eq("OPENING_STOCK"),
                any(String.class),
                eq(openingBalance.getId()),
                any(Map.class),
                eq(true),
                eq(false),
                eq("Opening stock import"),
                eq(LocalDate.of(2026, 2, 3))
        )).thenReturn(new JournalEntryDto(
                800L,
                null,
                "OPEN-STOCK-ACME-MIXED",
                LocalDate.of(2026, 2, 3),
                null,
                "POSTED",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null
        ));

        OpeningStockImportResponse response = service.importOpeningStock(file, "mixed-rows-key");

        assertThat(response.rowsProcessed()).isEqualTo(2);
        assertThat(response.rawMaterialsCreated()).isZero();
        assertThat(response.rawMaterialBatchesCreated()).isEqualTo(1);
        assertThat(response.finishedGoodsCreated()).isZero();
        assertThat(response.finishedGoodBatchesCreated()).isEqualTo(1);
        assertThat(response.results()).hasSize(2);
        assertThat(response.errors()).isEmpty();
        assertThat(response.results().stream()
                .filter(result -> "FG-1".equals(result.sku()))
                .findFirst()
                .orElseThrow()
                .readiness()
                .sales()
                .ready()).isTrue();
        assertThat(response.results().stream()
                .filter(result -> "FG-1".equals(result.sku()))
                .findFirst()
                .orElseThrow()
                .readiness()
                .sales()
                .blockers()).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, BigDecimal>> inventoryLinesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(accountingFacade).postInventoryAdjustment(
                eq("OPENING_STOCK"),
                any(String.class),
                eq(openingBalance.getId()),
                inventoryLinesCaptor.capture(),
                eq(true),
                eq(false),
                eq("Opening stock import"),
                eq(LocalDate.of(2026, 2, 3))
        );
        Map<Long, BigDecimal> capturedLines = inventoryLinesCaptor.getValue();
        assertThat(capturedLines)
                .containsEntry(11L, new BigDecimal("50.00"))
                .containsEntry(33L, new BigDecimal("48.00"));
        verify(skuReadinessService, times(1))
                .forSku(company, "RM-1", SkuReadinessService.ExpectedStockType.RAW_MATERIAL);
        verify(skuReadinessService, times(2))
                .forSku(company, "FG-1", SkuReadinessService.ExpectedStockType.FINISHED_GOOD);
    }

    @Test
    void importOpeningStock_requiresExplicitIdempotencyKey() {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));

        assertThatThrownBy(() -> service.importOpeningStock(file, "  "))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_MISSING_REQUIRED_FIELD);
                    assertThat(ex.getMessage()).isEqualTo("Idempotency key is required for opening stock imports");
                });
    }

    @Test
    void importOpeningStock_reportsUnknownBlockerWhenReadinessFailsWithoutDetail() {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-UNKNOWN,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));

        stubDefaultImportState(file);
        when(skuReadinessService.forSku(company, "RM-UNKNOWN", SkuReadinessService.ExpectedStockType.RAW_MATERIAL))
                .thenReturn(new SkuReadinessDto(
                        "RM-UNKNOWN",
                        new SkuReadinessDto.Stage(true, List.of()),
                        new SkuReadinessDto.Stage(false, List.of()),
                        new SkuReadinessDto.Stage(true, List.of()),
                        new SkuReadinessDto.Stage(true, List.of())
                ));

        OpeningStockImportResponse response = service.importOpeningStock(file, "unknown-blocker-key");

        assertThat(response.rowsProcessed()).isZero();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().message())
                .isEqualTo("SKU RM-UNKNOWN is not inventory-ready for opening stock: UNKNOWN");
    }

    @Test
    void importOpeningStock_reportsMirrorLookupDriftForPreparedSkus() {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type,manufactured_at",
                "RAW_MATERIAL,RM-READY,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION,",
                "FINISHED_GOOD,FG-READY,Gloss Paint,L,L,FG-B1,4,12.00,,2026-02-01"
        ));

        stubDefaultImportState(file);

        OpeningStockImportResponse response = service.importOpeningStock(file, "missing-mirror-key");

        assertThat(response.rowsProcessed()).isZero();
        assertThat(response.errors()).hasSize(2);
        assertThat(response.errors())
                .extracting(error -> error.message())
                .containsExactlyInAnyOrder(
                        "Raw material mirror missing for prepared SKU RM-READY",
                        "Finished good mirror missing for prepared SKU FG-READY"
                );
        verifyNoInteractions(accountingFacade);
    }

    @Test
    void serializeResults_returnsNullForEmptyAndSerializationFailure() throws Exception {
        assertThat((Object) ReflectionTestUtils.invokeMethod(service, "serializeResults", List.of())).isNull();

        ObjectMapper failingObjectMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        when(failingObjectMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("boom"));

        OpeningStockImportService failingService = new OpeningStockImportService(
                companyContextService,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                rawMaterialMovementRepository,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                inventoryMovementRepository,
                skuReadinessService,
                batchNumberService,
                accountingFacade,
                accountRepository,
                journalEntryRepository,
                openingStockImportRepository,
                auditService,
                failingObjectMapper,
                companyClock,
                environment,
                new ResourcelessTransactionManager(),
                true
        );

        String serialized = ReflectionTestUtils.invokeMethod(
                failingService,
                "serializeResults",
                List.of(new OpeningStockImportResponse.ImportRowResult(1L, "FG-1", "FINISHED_GOOD", readyReadiness("FG-1"))));

        assertThat(serialized).isNull();
    }

    @Test
    void importOpeningStock_rejectsOverlongIdempotencyKey() {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));

        assertThatThrownBy(() -> service.importOpeningStock(file, "x".repeat(129)))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_INPUT);
                    assertThat(ex.getMessage()).isEqualTo("Idempotency key exceeds 128 characters");
                });
    }

    @Test
    void importOpeningStock_rejectsWhenProdProfileDisablesImport() {
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(true);
        OpeningStockImportService disabledService = new OpeningStockImportService(
                companyContextService,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                rawMaterialMovementRepository,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                inventoryMovementRepository,
                skuReadinessService,
                batchNumberService,
                accountingFacade,
                accountRepository,
                journalEntryRepository,
                openingStockImportRepository,
                auditService,
                new ObjectMapper(),
                companyClock,
                environment,
                new ResourcelessTransactionManager(),
                false
        );

        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));

        assertThatThrownBy(() -> disabledService.importOpeningStock(file, "migration-key"))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION);
                    assertThat(ex.getMessage()).isEqualTo("Opening stock import is disabled; enable migration mode to proceed.");
                    assertThat(ex.getDetails())
                            .containsEntry("setting", "erp.inventory.opening-stock.enabled")
                            .containsEntry("canonicalPath", "/api/v1/inventory/opening-stock");
                });
    }

    @Test
    void importOpeningStock_replaysPersistedImportWhenProdProfileDisablesNewImports() throws Exception {
        OpeningStockImportService disabledService = new OpeningStockImportService(
                companyContextService,
                rawMaterialRepository,
                rawMaterialBatchRepository,
                rawMaterialMovementRepository,
                finishedGoodRepository,
                finishedGoodBatchRepository,
                inventoryMovementRepository,
                skuReadinessService,
                batchNumberService,
                accountingFacade,
                accountRepository,
                journalEntryRepository,
                openingStockImportRepository,
                auditService,
                new ObjectMapper(),
                companyClock,
                environment,
                new ResourcelessTransactionManager(),
                false
        );
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));
        String fileHash = com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex(file.getBytes());

        OpeningStockImport existing = new OpeningStockImport();
        existing.setCompany(company);
        existing.setIdempotencyKey("same-key");
        existing.setIdempotencyHash(fileHash);
        existing.setRowsProcessed(2);
        existing.setRawMaterialBatchesCreated(1);
        existing.setResultsJson("""
                [{"rowNumber":1,"sku":"RM-1","stockType":"RAW_MATERIAL","readiness":{"sku":"RM-1","catalog":{"ready":true,"blockers":[]},"inventory":{"ready":true,"blockers":[]},"production":{"ready":true,"blockers":[]},"sales":{"ready":false,"blockers":["RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE"]}}}]
                """);

        when(openingStockImportRepository.findByCompanyAndIdempotencyKey(company, "same-key"))
                .thenReturn(Optional.of(existing));

        OpeningStockImportResponse replay = disabledService.importOpeningStock(file, "same-key");

        assertThat(replay.rowsProcessed()).isEqualTo(2);
        assertThat(replay.results()).hasSize(1);
        assertThat(replay.results().getFirst().sku()).isEqualTo("RM-1");
        verify(journalEntryRepository, never()).findByCompanyAndReferenceNumber(any(), any());
        verify(accountingFacade, never()).postInventoryAdjustment(
                any(String.class),
                any(String.class),
                any(Long.class),
                any(Map.class),
                any(Boolean.class),
                any(Boolean.class),
                any(String.class),
                any(LocalDate.class)
        );
    }

    @Test
    void importOpeningStock_reportsRowParseErrorsWithoutSkuOrReadiness() {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "UNKNOWN,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));

        stubDefaultImportState(file);

        OpeningStockImportResponse response = service.importOpeningStock(file, "bad-type-key");

        assertThat(response.rowsProcessed()).isZero();
        assertThat(response.results()).isEmpty();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().message()).isEqualTo("Unknown type: UNKNOWN");
        assertThat(response.errors().getFirst().sku()).isNull();
        assertThat(response.errors().getFirst().readiness()).isNull();
    }

    @Test
    void importOpeningStock_reportsMissingSkuWithoutReadinessLookup() {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));

        stubDefaultImportState(file);

        OpeningStockImportResponse response = service.importOpeningStock(file, "missing-sku-key");

        assertThat(response.rowsProcessed()).isZero();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().message())
                .isEqualTo("SKU is required for opening stock; only prepared SKUs are accepted");
        assertThat(response.errors().getFirst().sku()).isNull();
        assertThat(response.errors().getFirst().readiness()).isNull();
        verify(skuReadinessService, never()).forSku(eq(company), isNull(), any());
    }

    @Test
    void importOpeningStock_reportsUnknownReadinessBlockerWhenStageBlockersAreEmpty() {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "FINISHED_GOOD,FG-UNKNOWN,Gloss Paint,L,L,FG-B1,4,12.00,,"
        ));

        stubDefaultImportState(file);

        SkuReadinessDto readiness = new SkuReadinessDto(
                "FG-UNKNOWN",
                new SkuReadinessDto.Stage(false, List.of()),
                new SkuReadinessDto.Stage(true, List.of()),
                new SkuReadinessDto.Stage(false, List.of("WIP_ACCOUNT_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("NO_FINISHED_GOOD_BATCH_STOCK"))
        );
        when(skuReadinessService.forSku(
                company,
                "FG-UNKNOWN",
                SkuReadinessService.ExpectedStockType.FINISHED_GOOD
        )).thenReturn(readiness);

        OpeningStockImportResponse response = service.importOpeningStock(file, "unknown-blocker-key");

        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().message())
                .isEqualTo("SKU FG-UNKNOWN is not catalog-ready for opening stock: UNKNOWN");
        assertThat(response.errors().getFirst().readiness()).isEqualTo(readiness);
    }

    @Test
    void importOpeningStock_reportsReadinessFailureForSkuMissingInventoryMirror() {
        String csv = String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-ORPHAN,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        );
        MockMultipartFile file = csvFile(csv);

        stubDefaultImportState(file);

        SkuReadinessDto readiness = new SkuReadinessDto(
                "RM-ORPHAN",
                readyStage(),
                new SkuReadinessDto.Stage(false, List.of("RAW_MATERIAL_MIRROR_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("RAW_MATERIAL_MIRROR_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE"))
        );
        when(skuReadinessService.forSku(
                company,
                "RM-ORPHAN",
                SkuReadinessService.ExpectedStockType.RAW_MATERIAL
        )).thenReturn(readiness);

        OpeningStockImportResponse response = service.importOpeningStock(file, "strict-key");

        assertThat(response.rowsProcessed()).isZero();
        assertThat(response.results()).isEmpty();
        assertThat(response.errors()).hasSize(1);
        OpeningStockImportResponse.ImportError error = response.errors().getFirst();
        assertThat(error.message()).contains("RM-ORPHAN is not inventory-ready");
        assertThat(error.sku()).isEqualTo("RM-ORPHAN");
        assertThat(error.stockType()).isEqualTo("RAW_MATERIAL");
        assertThat(error.readiness()).isEqualTo(readiness);
        verify(accountingFacade, never()).postInventoryAdjustment(
                any(String.class),
                any(String.class),
                any(Long.class),
                any(Map.class),
                any(Boolean.class),
                any(Boolean.class),
                any(String.class),
                any(LocalDate.class)
        );
    }

    @Test
    void importOpeningStock_reportsCatalogReadinessFailureForMissingProductMaster() {
        String csv = String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "FINISHED_GOOD,FG-MISSING,Gloss Paint,L,L,FG-B1,4,12.00,,"
        );
        MockMultipartFile file = csvFile(csv);

        stubDefaultImportState(file);

        SkuReadinessDto readiness = new SkuReadinessDto(
                "FG-MISSING",
                new SkuReadinessDto.Stage(false, List.of("PRODUCT_MASTER_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("FINISHED_GOOD_MIRROR_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("PRODUCT_MASTER_MISSING", "FINISHED_GOOD_MIRROR_MISSING", "WIP_ACCOUNT_MISSING")),
                new SkuReadinessDto.Stage(false, List.of("PRODUCT_MASTER_MISSING", "FINISHED_GOOD_MIRROR_MISSING", "NO_FINISHED_GOOD_BATCH_STOCK"))
        );
        when(skuReadinessService.forSku(
                company,
                "FG-MISSING",
                SkuReadinessService.ExpectedStockType.FINISHED_GOOD
        )).thenReturn(readiness);

        OpeningStockImportResponse response = service.importOpeningStock(file, "catalog-failure-key");

        assertThat(response.rowsProcessed()).isZero();
        assertThat(response.results()).isEmpty();
        assertThat(response.errors()).hasSize(1);
        OpeningStockImportResponse.ImportError error = response.errors().getFirst();
        assertThat(error.message()).contains("FG-MISSING is not catalog-ready");
        assertThat(error.sku()).isEqualTo("FG-MISSING");
        assertThat(error.stockType()).isEqualTo("FINISHED_GOOD");
        assertThat(error.readiness()).isEqualTo(readiness);
    }

    @Test
    void listImportHistory_returnsPagedMappedItemsForCurrentCompany() {
        OpeningStockImport first = new OpeningStockImport();
        ReflectionTestUtils.setField(first, "id", 9L);
        first.setCompany(company);
        first.setIdempotencyKey("key-1");
        first.setReferenceNumber("OPEN-STOCK-ACME-001");
        first.setFileName("opening-1.csv");
        first.setJournalEntryId(700L);
        first.setRowsProcessed(3);
        first.setRawMaterialsCreated(2);
        first.setRawMaterialBatchesCreated(2);
        first.setFinishedGoodsCreated(1);
        first.setFinishedGoodBatchesCreated(1);
        first.setErrorsJson("[{\"rowNumber\":2,\"message\":\"Invalid cost\"}]");
        ReflectionTestUtils.setField(first, "createdAt", Instant.parse("2026-02-03T10:15:30Z"));

        OpeningStockImport second = new OpeningStockImport();
        ReflectionTestUtils.setField(second, "id", 8L);
        second.setCompany(company);
        second.setIdempotencyKey("key-2");
        second.setReferenceNumber("OPEN-STOCK-ACME-000");
        second.setFileName("opening-0.csv");
        second.setJournalEntryId(null);
        second.setRowsProcessed(0);
        second.setRawMaterialsCreated(0);
        second.setRawMaterialBatchesCreated(0);
        second.setFinishedGoodsCreated(0);
        second.setFinishedGoodBatchesCreated(0);
        second.setErrorsJson(null);
        ReflectionTestUtils.setField(second, "createdAt", Instant.parse("2026-02-01T00:00:00Z"));

        Page<OpeningStockImport> pageResult = new PageImpl<>(List.of(first, second), PageRequest.of(0, 20), 2);
        when(openingStockImportRepository.findByCompany(eq(company), any(PageRequest.class)))
                .thenReturn(pageResult);

        PageResponse<OpeningStockImportHistoryItem> history = service.listImportHistory(0, 20);

        assertThat(history.totalElements()).isEqualTo(2);
        assertThat(history.totalPages()).isEqualTo(1);
        assertThat(history.page()).isEqualTo(0);
        assertThat(history.size()).isEqualTo(20);
        assertThat(history.content()).hasSize(2);

        OpeningStockImportHistoryItem item1 = history.content().getFirst();
        assertThat(item1.id()).isEqualTo(9L);
        assertThat(item1.idempotencyKey()).isEqualTo("key-1");
        assertThat(item1.referenceNumber()).isEqualTo("OPEN-STOCK-ACME-001");
        assertThat(item1.fileName()).isEqualTo("opening-1.csv");
        assertThat(item1.journalEntryId()).isEqualTo(700L);
        assertThat(item1.rowsProcessed()).isEqualTo(3);
        assertThat(item1.errorCount()).isEqualTo(1);
        assertThat(item1.createdAt()).isEqualTo(Instant.parse("2026-02-03T10:15:30Z"));

        OpeningStockImportHistoryItem item2 = history.content().get(1);
        assertThat(item2.id()).isEqualTo(8L);
        assertThat(item2.errorCount()).isZero();
        assertThat(item2.journalEntryId()).isNull();

        ArgumentCaptor<PageRequest> pageRequestCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(openingStockImportRepository).findByCompany(eq(company), pageRequestCaptor.capture());
        PageRequest captured = pageRequestCaptor.getValue();
        assertThat(captured.getPageNumber()).isEqualTo(0);
        assertThat(captured.getPageSize()).isEqualTo(20);
        assertThat(captured.getSort().toString()).contains("createdAt: DESC").contains("id: DESC");
    }

    @Test
    void importOpeningStock_replaysPersistedImportForSameIdempotencyHash() throws Exception {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));
        String fileHash = com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex(file.getBytes());

        OpeningStockImport existing = new OpeningStockImport();
        existing.setCompany(company);
        existing.setIdempotencyKey("same-key");
        existing.setIdempotencyHash(fileHash);
        existing.setRowsProcessed(4);
        existing.setRawMaterialsCreated(1);
        existing.setRawMaterialBatchesCreated(1);
        existing.setFinishedGoodsCreated(1);
        existing.setFinishedGoodBatchesCreated(1);
        existing.setErrorsJson("[{\"rowNumber\":3,\"message\":\"Invalid quantity\"}]");

        when(openingStockImportRepository.findByCompanyAndIdempotencyKey(company, "same-key"))
                .thenReturn(Optional.of(existing));

        OpeningStockImportResponse replay = service.importOpeningStock(file, "same-key");

        assertThat(replay.rowsProcessed()).isEqualTo(4);
        assertThat(replay.rawMaterialsCreated()).isEqualTo(1);
        assertThat(replay.rawMaterialBatchesCreated()).isEqualTo(1);
        assertThat(replay.finishedGoodsCreated()).isEqualTo(1);
        assertThat(replay.finishedGoodBatchesCreated()).isEqualTo(1);
        assertThat(replay.errors()).hasSize(1);
        assertThat(replay.errors().getFirst().rowNumber()).isEqualTo(3L);
        assertThat(replay.errors().getFirst().message()).isEqualTo("Invalid quantity");

        verify(accountingFacade, never()).postInventoryAdjustment(
                any(String.class),
                any(String.class),
                any(Long.class),
                any(Map.class),
                any(Boolean.class),
                any(Boolean.class),
                any(String.class),
                any(LocalDate.class)
        );
        verify(auditService, never()).logSuccess(eq(AuditEvent.DATA_CREATE), any(Map.class));
    }

    @Test
    void importOpeningStock_replaysPersistedResultsAndErrors() throws Exception {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));
        String fileHash = com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex(file.getBytes());

        OpeningStockImport existing = new OpeningStockImport();
        existing.setCompany(company);
        existing.setIdempotencyKey("same-key");
        existing.setIdempotencyHash(fileHash);
        existing.setRowsProcessed(1);
        existing.setRawMaterialsCreated(0);
        existing.setRawMaterialBatchesCreated(1);
        existing.setFinishedGoodsCreated(0);
        existing.setFinishedGoodBatchesCreated(0);
        existing.setResultsJson("""
                [{"rowNumber":1,"sku":"RM-1","stockType":"RAW_MATERIAL","readiness":{"sku":"RM-1","catalog":{"ready":true,"blockers":[]},"inventory":{"ready":true,"blockers":[]},"production":{"ready":true,"blockers":[]},"sales":{"ready":false,"blockers":["RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE"]}}}]
                """);
        existing.setErrorsJson("""
                [{"rowNumber":2,"message":"Invalid quantity","sku":"RM-2","stockType":"RAW_MATERIAL","readiness":null}]
                """);

        when(openingStockImportRepository.findByCompanyAndIdempotencyKey(company, "same-key"))
                .thenReturn(Optional.of(existing));

        OpeningStockImportResponse replay = service.importOpeningStock(file, "same-key");

        assertThat(replay.results()).hasSize(1);
        assertThat(replay.results().getFirst().sku()).isEqualTo("RM-1");
        assertThat(replay.results().getFirst().readiness().sales().blockers())
                .containsExactly("RAW_MATERIAL_SKU_NOT_SALES_ORDERABLE");
        assertThat(replay.errors()).hasSize(1);
        assertThat(replay.errors().getFirst().sku()).isEqualTo("RM-2");
        assertThat(replay.errors().getFirst().message()).isEqualTo("Invalid quantity");
    }

    @Test
    void importOpeningStock_replaysInvalidResultsJsonAsEmptyList() throws Exception {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));
        String fileHash = com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex(file.getBytes());

        OpeningStockImport existing = new OpeningStockImport();
        existing.setCompany(company);
        existing.setIdempotencyKey("same-key");
        existing.setIdempotencyHash(fileHash);
        existing.setRowsProcessed(1);
        existing.setResultsJson("{not-json}");
        existing.setErrorsJson(null);

        when(openingStockImportRepository.findByCompanyAndIdempotencyKey(company, "same-key"))
                .thenReturn(Optional.of(existing));

        OpeningStockImportResponse replay = service.importOpeningStock(file, "same-key");

        assertThat(replay.results()).isEmpty();
        assertThat(replay.errors()).isEmpty();
    }

    @Test
    void importOpeningStock_reportsUnexpectedRowErrorsWithReadinessContext() {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));

        stubDefaultImportState(file);
        RawMaterial rawMaterial = new RawMaterial();
        rawMaterial.setCompany(company);
        rawMaterial.setSku("RM-1");
        rawMaterial.setName("Resin");
        rawMaterial.setUnitType("KG");
        rawMaterial.setInventoryAccountId(11L);
        rawMaterial.setCurrentStock(BigDecimal.ZERO);
        when(rawMaterialRepository.findByCompanyAndSku(company, "RM-1")).thenReturn(Optional.of(rawMaterial));
        when(rawMaterialBatchRepository.existsByRawMaterialAndBatchCode(eq(rawMaterial), any())).thenReturn(false);
        when(rawMaterialBatchRepository.save(any(RawMaterialBatch.class))).thenThrow(new RuntimeException("batch-write-boom"));

        OpeningStockImportResponse response = service.importOpeningStock(file, "unexpected-key");

        assertThat(response.rowsProcessed()).isZero();
        assertThat(response.errors()).hasSize(1);
        assertThat(response.errors().getFirst().message()).isEqualTo("Unexpected error: batch-write-boom");
        assertThat(response.errors().getFirst().sku()).isEqualTo("RM-1");
        assertThat(response.errors().getFirst().readiness()).isEqualTo(readyReadiness("RM-1"));
    }

    @Test
    void importOpeningStock_rejectsIdempotencyReplayWhenPayloadDiffers() throws Exception {
        MockMultipartFile file = csvFile(String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        ));

        OpeningStockImport existing = new OpeningStockImport();
        existing.setCompany(company);
        existing.setIdempotencyKey("same-key");
        existing.setIdempotencyHash("different-hash");

        when(openingStockImportRepository.findByCompanyAndIdempotencyKey(company, "same-key"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.importOpeningStock(file, "same-key"))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT);
                    assertThat(ex.getMessage()).isEqualTo("Idempotency key already used with different payload");
                    assertThat(ex.getDetails()).containsEntry("idempotencyKey", "same-key");
                });

        verify(accountingFacade, never()).postInventoryAdjustment(
                any(String.class),
                any(String.class),
                any(Long.class),
                any(Map.class),
                any(Boolean.class),
                any(Boolean.class),
                any(String.class),
                any(LocalDate.class)
        );
    }

    @Test
    void importOpeningStock_rejectsWhenOpeningBalanceAccountMissing() {
        String csv = String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        );
        MockMultipartFile file = csvFile(csv);

        stubDefaultImportState(file);
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "OPEN-BAL"))
                .thenReturn(Optional.empty());

        RawMaterial rawMaterial = new RawMaterial();
        rawMaterial.setCompany(company);
        rawMaterial.setSku("RM-1");
        rawMaterial.setName("Resin");
        rawMaterial.setUnitType("KG");
        rawMaterial.setInventoryAccountId(11L);
        rawMaterial.setCurrentStock(BigDecimal.ZERO);
        when(rawMaterialRepository.findByCompanyAndSku(company, "RM-1")).thenReturn(Optional.of(rawMaterial));
        when(rawMaterialBatchRepository.existsByRawMaterialAndBatchCode(eq(rawMaterial), any())).thenReturn(false);
        when(rawMaterialBatchRepository.save(any(RawMaterialBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.save(any(RawMaterial.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialMovementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.importOpeningStock(file, "missing-open-bal"))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_STATE);
                    assertThat(ex.getMessage()).isEqualTo(
                            "Opening balance account OPEN-BAL is missing; complete company defaults and repair seeded accounts before importing opening stock");
                });
    }

    @Test
    void importOpeningStock_rejectsWhenOpeningBalanceAccountIsNotEquity() {
        String csv = String.join("\n",
                "type,sku,name,unit,unit_type,batch_code,quantity,unit_cost,material_type",
                "RAW_MATERIAL,RM-1,Resin,KG,KG,RM-B1,10,5.00,PRODUCTION"
        );
        MockMultipartFile file = csvFile(csv);

        stubDefaultImportState(file);
        when(accountRepository.findByCompanyAndCodeIgnoreCase(company, "OPEN-BAL"))
                .thenReturn(Optional.of(account(22L, "OPEN-BAL", "Opening Balance", AccountType.ASSET)));

        RawMaterial rawMaterial = new RawMaterial();
        rawMaterial.setCompany(company);
        rawMaterial.setSku("RM-1");
        rawMaterial.setName("Resin");
        rawMaterial.setUnitType("KG");
        rawMaterial.setInventoryAccountId(11L);
        rawMaterial.setCurrentStock(BigDecimal.ZERO);
        when(rawMaterialRepository.findByCompanyAndSku(company, "RM-1")).thenReturn(Optional.of(rawMaterial));
        when(rawMaterialBatchRepository.existsByRawMaterialAndBatchCode(eq(rawMaterial), any())).thenReturn(false);
        when(rawMaterialBatchRepository.save(any(RawMaterialBatch.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialRepository.save(any(RawMaterial.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(rawMaterialMovementRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.importOpeningStock(file, "wrong-open-bal"))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_STATE);
                    assertThat(ex.getMessage()).isEqualTo("Opening balance account OPEN-BAL must be an equity account");
                });
    }

    @Test
    void importOpeningStock_failsFastWhenFileHashCannotBeComputed() {
        MultipartFile brokenFile = new MultipartFile() {
            @Override
            public String getName() {
                return "file";
            }

            @Override
            public String getOriginalFilename() {
                return "opening-stock.csv";
            }

            @Override
            public String getContentType() {
                return "text/csv";
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public long getSize() {
                return 12;
            }

            @Override
            public byte[] getBytes() throws IOException {
                throw new IOException("simulated-io");
            }

            @Override
            public InputStream getInputStream() {
                return InputStream.nullInputStream();
            }

            @Override
            public void transferTo(java.io.File dest) {
                throw new UnsupportedOperationException();
            }
        };

        assertThatThrownBy(() -> service.importOpeningStock(brokenFile, "broken-file-key"))
                .isInstanceOfSatisfying(ApplicationException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_INVALID_STATE);
                    assertThat(ex.getMessage()).isEqualTo("Failed to compute opening stock file hash");
                });
    }

    private void stubDefaultImportState(MockMultipartFile file) {
        String fileHash;
        try {
            fileHash = com.bigbrightpaints.erp.core.idempotency.IdempotencyUtils.sha256Hex(file.getBytes());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        when(openingStockImportRepository.findByCompanyAndIdempotencyKey(eq(company), any(String.class)))
                .thenReturn(Optional.empty());
        when(journalEntryRepository.findByCompanyAndReferenceNumber(eq(company), any(String.class)))
                .thenReturn(Optional.empty());

        when(openingStockImportRepository.saveAndFlush(any(OpeningStockImport.class)))
                .thenAnswer(invocation -> {
                    OpeningStockImport record = invocation.getArgument(0);
                    ReflectionTestUtils.setField(record, "id", 321L);
                    record.setIdempotencyHash(fileHash);
                    ReflectionTestUtils.setField(record, "createdAt", Instant.parse("2026-02-03T00:00:00Z"));
                    return record;
                });
    }

    private Account account(Long id, String code, String name, AccountType type) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        account.setCode(code);
        account.setName(name);
        account.setType(type);
        account.setCompany(company);
        return account;
    }

    private MockMultipartFile csvFile(String csv) {
        return new MockMultipartFile(
                "file",
                "opening-stock.csv",
                "text/csv",
                csv.getBytes(StandardCharsets.UTF_8)
        );
    }

    private SkuReadinessDto readyReadiness(String sku) {
        return new SkuReadinessDto(
                sku,
                readyStage(),
                readyStage(),
                readyStage(),
                readyStage()
        );
    }

    private SkuReadinessDto.Stage readyStage() {
        return new SkuReadinessDto.Stage(true, List.of());
    }
}
