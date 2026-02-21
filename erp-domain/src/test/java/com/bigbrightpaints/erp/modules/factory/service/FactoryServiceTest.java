package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTask;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionBatch;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionBatchRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlan;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlanRepository;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryTaskRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionBatchDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionBatchRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanRequest;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FactoryServiceTest {

    @Mock
    private CompanyContextService companyContextService;
    @Mock
    private ProductionPlanRepository planRepository;
    @Mock
    private ProductionBatchRepository batchRepository;
    @Mock
    private FactoryTaskRepository taskRepository;
    @Mock
    private FinishedGoodsService finishedGoodsService;
    @Mock
    private CompanyEntityLookup companyEntityLookup;
    @Mock
    private Environment environment;

    private FactoryService factoryService;
    private Company company;

    @BeforeEach
    void setUp() {
        factoryService = new FactoryService(
                companyContextService,
                planRepository,
                batchRepository,
                taskRepository,
                finishedGoodsService,
                companyEntityLookup,
                environment,
                false
        );
        company = new Company();
        company.setTimezone("UTC");
        when(companyContextService.requireCurrentCompany()).thenReturn(company);
    }

    @Test
    void createPlan_replaySamePayloadReturnsExisting() {
        ProductionPlanRequest request = new ProductionPlanRequest(
                " PLAN-001 ",
                "Primer White",
                120.0,
                LocalDate.of(2026, 2, 20),
                "first run"
        );
        ProductionPlan existing = new ProductionPlan();
        ReflectionTestUtils.setField(existing, "id", 11L);
        existing.setCompany(company);
        existing.setPlanNumber("PLAN-001");
        existing.setProductName("Primer White");
        existing.setQuantity(120.0);
        existing.setPlannedDate(LocalDate.of(2026, 2, 20));
        existing.setNotes("first run");

        when(planRepository.findByCompanyAndPlanNumber(company, "PLAN-001")).thenReturn(Optional.of(existing));

        ProductionPlanDto result = factoryService.createPlan(request);

        assertThat(result.id()).isEqualTo(11L);
        verify(planRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createPlan_replayCanonicalQuantityReturnsExisting() {
        ProductionPlanRequest request = new ProductionPlanRequest(
                "PLAN-CANON-1",
                "Primer White",
                10.124,
                LocalDate.of(2026, 2, 20),
                "same after rounding"
        );
        ProductionPlan existing = new ProductionPlan();
        ReflectionTestUtils.setField(existing, "id", 111L);
        existing.setCompany(company);
        existing.setPlanNumber("PLAN-CANON-1");
        existing.setProductName("Primer White");
        existing.setQuantity(10.12);
        existing.setPlannedDate(LocalDate.of(2026, 2, 20));
        existing.setNotes("same after rounding");

        when(planRepository.findByCompanyAndPlanNumber(company, "PLAN-CANON-1")).thenReturn(Optional.of(existing));

        ProductionPlanDto result = factoryService.createPlan(request);

        assertThat(result.id()).isEqualTo(111L);
        verify(planRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createPlan_replayMismatchThrowsConflict() {
        ProductionPlanRequest request = new ProductionPlanRequest(
                "PLAN-001",
                "Primer White",
                120.0,
                LocalDate.of(2026, 2, 20),
                "request-notes"
        );
        ProductionPlan existing = new ProductionPlan();
        existing.setCompany(company);
        existing.setPlanNumber("PLAN-001");
        existing.setProductName("Primer White");
        existing.setQuantity(120.0);
        existing.setPlannedDate(LocalDate.of(2026, 2, 20));
        existing.setNotes("stored-notes");

        when(planRepository.findByCompanyAndPlanNumber(company, "PLAN-001")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> factoryService.createPlan(request))
                .isInstanceOfSatisfying(ApplicationException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
    }

    @Test
    void createPlan_concurrentInsertRehydratesExisting() {
        ProductionPlanRequest request = new ProductionPlanRequest(
                "PLAN-RACE-1",
                "Primer White",
                120.0,
                LocalDate.of(2026, 2, 20),
                "race"
        );
        ProductionPlan existing = new ProductionPlan();
        ReflectionTestUtils.setField(existing, "id", 12L);
        existing.setCompany(company);
        existing.setPlanNumber("PLAN-RACE-1");
        existing.setProductName("Primer White");
        existing.setQuantity(120.0);
        existing.setPlannedDate(LocalDate.of(2026, 2, 20));
        existing.setNotes("race");

        when(planRepository.findByCompanyAndPlanNumber(company, "PLAN-RACE-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(planRepository.insertIfAbsent(
                company.getId(),
                "PLAN-RACE-1",
                "Primer White",
                120.0,
                LocalDate.of(2026, 2, 20),
                "race"
        )).thenReturn(0);

        ProductionPlanDto result = factoryService.createPlan(request);

        assertThat(result.id()).isEqualTo(12L);
        verify(planRepository, times(1)).insertIfAbsent(
                company.getId(),
                "PLAN-RACE-1",
                "Primer White",
                120.0,
                LocalDate.of(2026, 2, 20),
                "race"
        );
    }

    @Test
    void logBatch_replaySamePayloadReturnsExisting() {
        stubNonProdProfile();
        ProductionBatchRequest request = new ProductionBatchRequest(
                " BATCH-001 ",
                55.0,
                "factory-user",
                "good batch"
        );
        ProductionBatch existing = new ProductionBatch();
        ReflectionTestUtils.setField(existing, "id", 21L);
        existing.setCompany(company);
        existing.setBatchNumber("BATCH-001");
        existing.setQuantityProduced(55.0);
        existing.setLoggedBy("factory-user");
        existing.setNotes("good batch");
        existing.setProducedAt(Instant.parse("2026-02-20T00:00:00Z"));

        when(batchRepository.findByCompanyAndBatchNumber(company, "BATCH-001")).thenReturn(Optional.of(existing));

        ProductionBatchDto result = factoryService.logBatch(null, request);

        assertThat(result.id()).isEqualTo(21L);
        verify(batchRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any());
        verify(finishedGoodsService, never()).registerBatch(any(FinishedGoodBatchRequest.class));
    }

    @Test
    void logBatch_replayCanonicalQuantityReturnsExisting() {
        stubNonProdProfile();
        ProductionBatchRequest request = new ProductionBatchRequest(
                "BATCH-CANON-1",
                10.124,
                "factory-user",
                "same after rounding"
        );
        ProductionBatch existing = new ProductionBatch();
        ReflectionTestUtils.setField(existing, "id", 211L);
        existing.setCompany(company);
        existing.setBatchNumber("BATCH-CANON-1");
        existing.setQuantityProduced(10.12);
        existing.setLoggedBy("factory-user");
        existing.setNotes("same after rounding");
        existing.setProducedAt(Instant.parse("2026-02-20T00:00:00Z"));

        when(batchRepository.findByCompanyAndBatchNumber(company, "BATCH-CANON-1")).thenReturn(Optional.of(existing));

        ProductionBatchDto result = factoryService.logBatch(null, request);

        assertThat(result.id()).isEqualTo(211L);
        verify(batchRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any());
        verify(finishedGoodsService, never()).registerBatch(any(FinishedGoodBatchRequest.class));
    }

    @Test
    void logBatch_replayMismatchThrowsConflict() {
        stubNonProdProfile();
        ProductionBatchRequest request = new ProductionBatchRequest(
                "BATCH-001",
                55.0,
                "factory-user",
                "good batch"
        );
        ProductionBatch existing = new ProductionBatch();
        existing.setCompany(company);
        existing.setBatchNumber("BATCH-001");
        existing.setQuantityProduced(44.0);
        existing.setLoggedBy("factory-user");
        existing.setNotes("good batch");

        when(batchRepository.findByCompanyAndBatchNumber(company, "BATCH-001")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> factoryService.logBatch(null, request))
                .isInstanceOfSatisfying(ApplicationException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
    }

    @Test
    void logBatch_concurrentInsertRehydratesExisting() {
        stubNonProdProfile();
        ProductionBatchRequest request = new ProductionBatchRequest(
                "BATCH-RACE-1",
                10.0,
                "factory-user",
                "race"
        );
        ProductionBatch existing = new ProductionBatch();
        ReflectionTestUtils.setField(existing, "id", 22L);
        existing.setCompany(company);
        existing.setBatchNumber("BATCH-RACE-1");
        existing.setQuantityProduced(10.0);
        existing.setLoggedBy("factory-user");
        existing.setNotes("race");

        when(batchRepository.findByCompanyAndBatchNumber(company, "BATCH-RACE-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(batchRepository.insertIfAbsent(
                eq(company.getId()),
                isNull(),
                eq("BATCH-RACE-1"),
                eq(10.0),
                any(Instant.class),
                eq("factory-user"),
                eq("race")
        )).thenReturn(0);

        ProductionBatchDto result = factoryService.logBatch(null, request);

        assertThat(result.id()).isEqualTo(22L);
        verify(batchRepository, times(1)).insertIfAbsent(
                eq(company.getId()),
                isNull(),
                eq("BATCH-RACE-1"),
                eq(10.0),
                any(Instant.class),
                eq("factory-user"),
                eq("race")
        );
        verify(finishedGoodsService, never()).registerBatch(any(FinishedGoodBatchRequest.class));
    }

    @Test
    void createTask_replaySamePayloadReturnsExisting() {
        FactoryTaskRequest request = new FactoryTaskRequest(
                " Mix Primer ",
                "task description",
                "mixer-1",
                null,
                LocalDate.of(2026, 2, 24),
                91L,
                190L
        );
        FactoryTask existing = new FactoryTask();
        ReflectionTestUtils.setField(existing, "id", 31L);
        existing.setCompany(company);
        existing.setTitle("mix primer");
        existing.setDescription("task description");
        existing.setAssignee("mixer-1");
        existing.setStatus("PENDING");
        existing.setDueDate(LocalDate.of(2026, 2, 24));
        existing.setSalesOrderId(91L);
        existing.setPackagingSlipId(190L);

        when(taskRepository.findByCompanyAndSalesOrderIdAndTitleIgnoreCase(company, 91L, "Mix Primer"))
                .thenReturn(Optional.of(existing));

        assertThat(factoryService.createTask(request).id()).isEqualTo(31L);
        verify(taskRepository, never()).save(any(FactoryTask.class));
    }

    @Test
    void createTask_replayMismatchThrowsConflict() {
        FactoryTaskRequest request = new FactoryTaskRequest(
                "Mix Primer",
                "request description",
                "mixer-1",
                "PENDING",
                LocalDate.of(2026, 2, 24),
                91L,
                190L
        );
        FactoryTask existing = new FactoryTask();
        existing.setCompany(company);
        existing.setTitle("Mix Primer");
        existing.setDescription("stored description");
        existing.setAssignee("mixer-1");
        existing.setStatus("PENDING");
        existing.setDueDate(LocalDate.of(2026, 2, 24));
        existing.setSalesOrderId(91L);
        existing.setPackagingSlipId(190L);

        when(taskRepository.findByCompanyAndSalesOrderIdAndTitleIgnoreCase(company, 91L, "Mix Primer"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> factoryService.createTask(request))
                .isInstanceOfSatisfying(ApplicationException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
    }

    @Test
    void logBatch_replayDoesNotDuplicateRegisterBatchSideEffect() {
        stubNonProdProfile();
        ProductionBatchRequest request = new ProductionBatchRequest(
                "BATCH-FG-1",
                25.0,
                "factory-user",
                "first plan batch"
        );
        ProductionPlan plan = new ProductionPlan();
        ReflectionTestUtils.setField(plan, "id", 77L);
        plan.setCompany(company);
        plan.setProductName("FG-PRIMER");

        ProductionBatch saved = new ProductionBatch();
        ReflectionTestUtils.setField(saved, "id", 23L);
        saved.setCompany(company);
        saved.setPlan(plan);
        saved.setBatchNumber("BATCH-FG-1");
        saved.setQuantityProduced(25.0);
        saved.setLoggedBy("factory-user");
        saved.setNotes("first plan batch");
        saved.setProducedAt(Instant.parse("2026-02-20T10:15:30Z"));

        FinishedGood finishedGood = new FinishedGood();
        ReflectionTestUtils.setField(finishedGood, "id", 900L);
        finishedGood.setCompany(company);
        finishedGood.setProductCode("FG-PRIMER");

        when(companyEntityLookup.requireProductionPlan(company, 77L)).thenReturn(plan);
        when(batchRepository.findByCompanyAndBatchNumber(company, "BATCH-FG-1"))
                .thenReturn(Optional.empty(), Optional.of(saved));
        when(batchRepository.insertIfAbsent(
                eq(company.getId()),
                eq(77L),
                eq("BATCH-FG-1"),
                eq(25.0),
                any(Instant.class),
                eq("factory-user"),
                eq("first plan batch")
        )).thenReturn(1);
        when(finishedGoodsService.lockFinishedGoodByProductCode("FG-PRIMER")).thenReturn(finishedGood);
        when(finishedGoodsService.currentWeightedAverageCost(finishedGood)).thenReturn(BigDecimal.valueOf(42));

        factoryService.logBatch(77L, request);
        factoryService.logBatch(77L, request);

        verify(batchRepository, times(1)).insertIfAbsent(
                eq(company.getId()),
                eq(77L),
                eq("BATCH-FG-1"),
                eq(25.0),
                any(Instant.class),
                eq("factory-user"),
                eq("first plan batch")
        );
        verify(finishedGoodsService, times(1)).registerBatch(any(FinishedGoodBatchRequest.class));
    }

    private void stubNonProdProfile() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
    }
}
