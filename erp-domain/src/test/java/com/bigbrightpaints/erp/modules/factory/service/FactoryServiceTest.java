package com.bigbrightpaints.erp.modules.factory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTask;
import com.bigbrightpaints.erp.modules.factory.domain.FactoryTaskRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionLogRepository;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlan;
import com.bigbrightpaints.erp.modules.factory.domain.ProductionPlanRepository;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryDashboardDto;
import com.bigbrightpaints.erp.modules.factory.dto.FactoryTaskRequest;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanDto;
import com.bigbrightpaints.erp.modules.factory.dto.ProductionPlanRequest;

@Tag("critical")
@ExtendWith(MockitoExtension.class)
class FactoryServiceTest {

  @Mock private CompanyContextService companyContextService;
  @Mock private ProductionPlanRepository planRepository;
  @Mock private ProductionLogRepository productionLogRepository;
  @Mock private FactoryTaskRepository taskRepository;
  @Mock private CompanyEntityLookup companyEntityLookup;

  private FactoryService factoryService;
  private Company company;

  @BeforeEach
  void setUp() {
    factoryService =
        new FactoryService(
            companyContextService,
            planRepository,
            productionLogRepository,
            taskRepository,
            companyEntityLookup);
    company = new Company();
    company.setTimezone("UTC");
    org.mockito.Mockito.lenient()
        .when(companyContextService.requireCurrentCompany())
        .thenReturn(company);
  }

  @Test
  void createPlan_replaySamePayloadReturnsExisting() {
    ProductionPlanRequest request =
        new ProductionPlanRequest(
            " PLAN-001 ", "Primer White", 120.0, LocalDate.of(2026, 2, 20), "first run");
    ProductionPlan existing = new ProductionPlan();
    ReflectionTestUtils.setField(existing, "id", 11L);
    existing.setCompany(company);
    existing.setPlanNumber("PLAN-001");
    existing.setProductName("Primer White");
    existing.setQuantity(120.0);
    existing.setPlannedDate(LocalDate.of(2026, 2, 20));
    existing.setNotes("first run");

    when(planRepository.findByCompanyAndPlanNumber(company, "PLAN-001"))
        .thenReturn(Optional.of(existing));

    ProductionPlanDto result = factoryService.createPlan(request);

    assertThat(result.id()).isEqualTo(11L);
    verify(planRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any());
  }

  @Test
  void createPlan_replayCanonicalQuantityReturnsExisting() {
    ProductionPlanRequest request =
        new ProductionPlanRequest(
            "PLAN-CANON-1",
            "Primer White",
            10.124,
            LocalDate.of(2026, 2, 20),
            "same after rounding");
    ProductionPlan existing = new ProductionPlan();
    ReflectionTestUtils.setField(existing, "id", 111L);
    existing.setCompany(company);
    existing.setPlanNumber("PLAN-CANON-1");
    existing.setProductName("Primer White");
    existing.setQuantity(10.12);
    existing.setPlannedDate(LocalDate.of(2026, 2, 20));
    existing.setNotes("same after rounding");

    when(planRepository.findByCompanyAndPlanNumber(company, "PLAN-CANON-1"))
        .thenReturn(Optional.of(existing));

    ProductionPlanDto result = factoryService.createPlan(request);

    assertThat(result.id()).isEqualTo(111L);
    verify(planRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any());
  }

  @Test
  void createPlan_replayMismatchThrowsConflict() {
    ProductionPlanRequest request =
        new ProductionPlanRequest(
            "PLAN-001", "Primer White", 120.0, LocalDate.of(2026, 2, 20), "request-notes");
    ProductionPlan existing = new ProductionPlan();
    existing.setCompany(company);
    existing.setPlanNumber("PLAN-001");
    existing.setProductName("Primer White");
    existing.setQuantity(120.0);
    existing.setPlannedDate(LocalDate.of(2026, 2, 20));
    existing.setNotes("stored-notes");

    when(planRepository.findByCompanyAndPlanNumber(company, "PLAN-001"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> factoryService.createPlan(request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
  }

  @Test
  void createPlan_concurrentInsertRehydratesExisting() {
    ProductionPlanRequest request =
        new ProductionPlanRequest(
            "PLAN-RACE-1", "Primer White", 120.0, LocalDate.of(2026, 2, 20), "race");
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
            "race"))
        .thenReturn(0);

    ProductionPlanDto result = factoryService.createPlan(request);

    assertThat(result.id()).isEqualTo(12L);
    verify(planRepository, times(1))
        .insertIfAbsent(
            company.getId(),
            "PLAN-RACE-1",
            "Primer White",
            120.0,
            LocalDate.of(2026, 2, 20),
            "race");
  }

  @Test
  void createPlan_newNaturalKeyReturnsInsertedPlan() {
    ProductionPlanRequest request =
        new ProductionPlanRequest(
            "PLAN-NEW-1", "Primer White", 120.0, LocalDate.of(2026, 2, 20), "fresh");
    ProductionPlan inserted = new ProductionPlan();
    ReflectionTestUtils.setField(inserted, "id", 14L);
    inserted.setCompany(company);
    inserted.setPlanNumber("PLAN-NEW-1");
    inserted.setProductName("Primer White");
    inserted.setQuantity(120.0);
    inserted.setPlannedDate(LocalDate.of(2026, 2, 20));
    inserted.setNotes("fresh");

    when(planRepository.findByCompanyAndPlanNumber(company, "PLAN-NEW-1"))
        .thenReturn(Optional.empty(), Optional.of(inserted));
    when(planRepository.insertIfAbsent(
            company.getId(),
            "PLAN-NEW-1",
            "Primer White",
            120.0,
            LocalDate.of(2026, 2, 20),
            "fresh"))
        .thenReturn(1);

    ProductionPlanDto result = factoryService.createPlan(request);

    assertThat(result.id()).isEqualTo(14L);
    verify(planRepository)
        .insertIfAbsent(
            company.getId(),
            "PLAN-NEW-1",
            "Primer White",
            120.0,
            LocalDate.of(2026, 2, 20),
            "fresh");
  }

  @Test
  void createPlan_withoutNaturalKeyPersistsDirectly() {
    ProductionPlanRequest request =
        new ProductionPlanRequest(
            "   ", "Primer White", 120.0, LocalDate.of(2026, 2, 20), "direct save");
    ProductionPlan saved = new ProductionPlan();
    ReflectionTestUtils.setField(saved, "id", 13L);
    saved.setCompany(company);
    saved.setPlanNumber("   ");
    saved.setProductName("Primer White");
    saved.setQuantity(120.0);
    saved.setPlannedDate(LocalDate.of(2026, 2, 20));
    saved.setNotes("direct save");

    when(planRepository.save(any(ProductionPlan.class))).thenReturn(saved);

    ProductionPlanDto result = factoryService.createPlan(request);

    assertThat(result.id()).isEqualTo(13L);
    verify(planRepository).save(any(ProductionPlan.class));
    verify(planRepository, never()).findByCompanyAndPlanNumber(any(), any());
  }

  @Test
  void factoryService_noLongerExposesLegacyBatchLoggingSurface() {
    assertThat(Arrays.stream(FactoryService.class.getDeclaredMethods()).map(Method::getName))
        .doesNotContain(
            "listBatches",
            "logBatch",
            "logBatchInternal",
            "createBatchWithNaturalKey",
            "resolveExistingBatch",
            "isBatchPayloadEquivalent",
            "assertLegacyBatchLoggingAllowed",
            "registerFinishedGoodsBatch");
    assertThat(
            Files.exists(
                Path.of(
                    "src/main/java/com/bigbrightpaints/erp/modules/factory/dto/ProductionBatchRequest.java")))
        .isFalse();
    assertThat(
            Files.exists(
                Path.of(
                    "src/main/java/com/bigbrightpaints/erp/modules/factory/dto/ProductionBatchDto.java")))
        .isFalse();
  }

  @Test
  void dashboard_countsLoggedBatchesWithoutLegacyBatchDtos() {
    ProductionPlan completedPlan = new ProductionPlan();
    completedPlan.setStatus("COMPLETED");
    ProductionPlan pendingPlan = new ProductionPlan();
    pendingPlan.setStatus("PENDING");

    when(planRepository.findByCompanyOrderByPlannedDateDesc(company))
        .thenReturn(List.of(completedPlan, pendingPlan));
    when(productionLogRepository.countByCompany(company)).thenReturn(4L);

    FactoryDashboardDto result = factoryService.dashboard();

    assertThat(result.completedPlans()).isEqualTo(1);
    assertThat(result.batchesLogged()).isEqualTo(4L);
    assertThat(result.productionEfficiency()).isEqualTo(2.0);
  }

  @Test
  void dashboard_returnsZeroEfficiencyWhenNoPlansExist() {
    when(planRepository.findByCompanyOrderByPlannedDateDesc(company)).thenReturn(List.of());
    when(productionLogRepository.countByCompany(company)).thenReturn(4L);

    FactoryDashboardDto result = factoryService.dashboard();

    assertThat(result.completedPlans()).isZero();
    assertThat(result.batchesLogged()).isEqualTo(4L);
    assertThat(result.productionEfficiency()).isZero();
  }

  @Test
  void createTask_replaySamePayloadReturnsExisting() {
    FactoryTaskRequest request =
        new FactoryTaskRequest(
            " Mix Primer ",
            "task description",
            "mixer-1",
            null,
            LocalDate.of(2026, 2, 24),
            91L,
            190L);
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
    FactoryTaskRequest request =
        new FactoryTaskRequest(
            "Mix Primer",
            "request description",
            "mixer-1",
            "PENDING",
            LocalDate.of(2026, 2, 24),
            91L,
            190L);
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
        .isInstanceOfSatisfying(
            ApplicationException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
  }
}
