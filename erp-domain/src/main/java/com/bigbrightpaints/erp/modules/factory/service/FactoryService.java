package com.bigbrightpaints.erp.modules.factory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.*;
import com.bigbrightpaints.erp.modules.factory.dto.*;

import jakarta.transaction.Transactional;

@Service
public class FactoryService {

  private final CompanyContextService companyContextService;
  private final ProductionPlanRepository planRepository;
  private final ProductionLogRepository productionLogRepository;
  private final FactoryTaskRepository taskRepository;
  private final CompanyScopedFactoryLookupService factoryLookupService;
  private static final int QUANTITY_SCALE = 2;

  @Autowired
  public FactoryService(
      CompanyContextService companyContextService,
      ProductionPlanRepository planRepository,
      ProductionLogRepository productionLogRepository,
      FactoryTaskRepository taskRepository,
      CompanyScopedFactoryLookupService factoryLookupService) {
    this.companyContextService = companyContextService;
    this.planRepository = planRepository;
    this.productionLogRepository = productionLogRepository;
    this.taskRepository = taskRepository;
    this.factoryLookupService = factoryLookupService;
  }

  public FactoryService(
      CompanyContextService companyContextService,
      ProductionPlanRepository planRepository,
      ProductionLogRepository productionLogRepository,
      FactoryTaskRepository taskRepository,
      CompanyEntityLookup companyEntityLookup) {
    this(
        companyContextService,
        planRepository,
        productionLogRepository,
        taskRepository,
        CompanyScopedFactoryLookupService.fromLegacy(companyEntityLookup));
  }

  public List<ProductionPlanDto> listPlans() {
    Company company = companyContextService.requireCurrentCompany();
    return planRepository.findByCompanyOrderByPlannedDateDesc(company).stream()
        .map(this::toDto)
        .toList();
  }

  @Transactional
  public ProductionPlanDto createPlan(ProductionPlanRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    String normalizedPlanNumber = normalizeNaturalKey(request.planNumber());
    if (normalizedPlanNumber != null) {
      return planRepository
          .findByCompanyAndPlanNumber(company, normalizedPlanNumber)
          .map(existing -> resolveExistingPlan(existing, request, normalizedPlanNumber))
          .orElseGet(() -> createPlanWithNaturalKey(company, request, normalizedPlanNumber));
    }
    ProductionPlan plan = new ProductionPlan();
    plan.setCompany(company);
    plan.setPlanNumber(request.planNumber());
    plan.setProductName(request.productName());
    plan.setQuantity(request.quantity());
    plan.setPlannedDate(request.plannedDate());
    plan.setNotes(request.notes());
    return toDto(planRepository.save(plan));
  }

  @Transactional
  public ProductionPlanDto updatePlan(Long id, ProductionPlanRequest request) {
    ProductionPlan plan = requirePlan(id);
    plan.setProductName(request.productName());
    plan.setQuantity(request.quantity());
    plan.setPlannedDate(request.plannedDate());
    plan.setNotes(request.notes());
    return toDto(plan);
  }

  @Transactional
  public ProductionPlanDto updatePlanStatus(Long id, String status) {
    ProductionPlan plan = requirePlan(id);
    plan.setStatus(status);
    return toDto(plan);
  }

  public void deletePlan(Long id) {
    planRepository.delete(requirePlan(id));
  }

  private ProductionPlan requirePlan(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    return factoryLookupService.requireProductionPlan(company, id);
  }

  private ProductionPlanDto toDto(ProductionPlan plan) {
    return new ProductionPlanDto(
        plan.getId(),
        plan.getPublicId(),
        plan.getPlanNumber(),
        plan.getProductName(),
        plan.getQuantity(),
        plan.getPlannedDate(),
        plan.getStatus(),
        plan.getNotes());
  }

  /* Tasks */
  public List<FactoryTaskDto> listTasks() {
    Company company = companyContextService.requireCurrentCompany();
    return taskRepository.findByCompanyOrderByCreatedAtDesc(company).stream()
        .map(this::toDto)
        .toList();
  }

  @Transactional
  public FactoryTaskDto createTask(FactoryTaskRequest request) {
    Company company = companyContextService.requireCurrentCompany();
    if (request.salesOrderId() != null && StringUtils.hasText(request.title())) {
      String normalized = request.title().trim();
      return taskRepository
          .findByCompanyAndSalesOrderIdAndTitleIgnoreCase(
              company, request.salesOrderId(), normalized)
          .map(existing -> resolveExistingTask(existing, request, normalized))
          .orElseGet(() -> createTaskInternal(company, request, normalized));
    }
    return createTaskInternal(company, request, null);
  }

  private FactoryTaskDto createTaskInternal(
      Company company, FactoryTaskRequest request, String normalizedTitle) {
    FactoryTask task = new FactoryTask();
    task.setCompany(company);
    task.setTitle(normalizedTitle != null ? normalizedTitle : request.title());
    task.setDescription(request.description());
    task.setAssignee(request.assignee());
    if (request.status() != null) {
      task.setStatus(request.status());
    }
    task.setDueDate(request.dueDate());
    task.setSalesOrderId(request.salesOrderId());
    task.setPackagingSlipId(request.packagingSlipId());
    return toDto(taskRepository.save(task));
  }

  @Transactional
  public FactoryTaskDto updateTask(Long id, FactoryTaskRequest request) {
    FactoryTask task = requireTask(id);
    task.setTitle(request.title());
    task.setDescription(request.description());
    task.setAssignee(request.assignee());
    if (request.status() != null) {
      task.setStatus(request.status());
    }
    task.setDueDate(request.dueDate());
    task.setSalesOrderId(request.salesOrderId());
    task.setPackagingSlipId(request.packagingSlipId());
    return toDto(task);
  }

  private FactoryTaskDto toDto(FactoryTask task) {
    return new FactoryTaskDto(
        task.getId(),
        task.getPublicId(),
        task.getTitle(),
        task.getDescription(),
        task.getAssignee(),
        task.getStatus(),
        task.getDueDate(),
        task.getSalesOrderId(),
        task.getPackagingSlipId());
  }

  private FactoryTask requireTask(Long id) {
    Company company = companyContextService.requireCurrentCompany();
    return factoryLookupService.requireFactoryTask(company, id);
  }

  /* Dashboard */
  public FactoryDashboardDto dashboard() {
    List<ProductionPlanDto> plans = listPlans();
    Company company = companyContextService.requireCurrentCompany();
    long batchesLogged = productionLogRepository.countByCompany(company);
    double efficiency = plans.isEmpty() ? 0 : (double) batchesLogged / plans.size();
    return new FactoryDashboardDto(
        efficiency,
        plans.stream().filter(p -> "COMPLETED".equals(p.status())).count(),
        batchesLogged,
        List.of());
  }

  private ProductionPlanDto createPlanWithNaturalKey(
      Company company, ProductionPlanRequest request, String normalizedPlanNumber) {
    int inserted =
        planRepository.insertIfAbsent(
            company.getId(),
            normalizedPlanNumber,
            request.productName(),
            request.quantity(),
            request.plannedDate(),
            request.notes());
    ProductionPlan resolved =
        planRepository
            .findByCompanyAndPlanNumber(company, normalizedPlanNumber)
            .orElseThrow(
                () ->
                    new ApplicationException(
                            ErrorCode.CONCURRENCY_CONFLICT,
                            "Production plan insert/lookup race could not be resolved")
                        .withDetail("planNumber", normalizedPlanNumber));
    if (inserted == 0) {
      return resolveExistingPlan(resolved, request, normalizedPlanNumber);
    }
    return toDto(resolved);
  }

  private ProductionPlanDto resolveExistingPlan(
      ProductionPlan existing, ProductionPlanRequest request, String normalizedPlanNumber) {
    if (!isPlanPayloadEquivalent(existing, request, normalizedPlanNumber)) {
      throw new ApplicationException(
              ErrorCode.CONCURRENCY_CONFLICT,
              "Production plan already exists with different payload")
          .withDetail("planNumber", normalizedPlanNumber);
    }
    return toDto(existing);
  }

  private boolean isPlanPayloadEquivalent(
      ProductionPlan existing, ProductionPlanRequest request, String normalizedPlanNumber) {
    return Objects.equals(existing.getPlanNumber(), normalizedPlanNumber)
        && Objects.equals(existing.getProductName(), request.productName())
        && sameQuantity(existing.getQuantity(), request.quantity())
        && Objects.equals(existing.getPlannedDate(), request.plannedDate())
        && Objects.equals(existing.getNotes(), request.notes());
  }

  private FactoryTaskDto resolveExistingTask(
      FactoryTask existing, FactoryTaskRequest request, String normalizedTitle) {
    if (!isTaskPayloadEquivalent(existing, request, normalizedTitle)) {
      throw new ApplicationException(
              ErrorCode.CONCURRENCY_CONFLICT, "Factory task already exists with different payload")
          .withDetail("salesOrderId", request.salesOrderId())
          .withDetail("title", normalizedTitle);
    }
    return toDto(existing);
  }

  private boolean isTaskPayloadEquivalent(
      FactoryTask existing, FactoryTaskRequest request, String normalizedTitle) {
    String existingTitle = existing.getTitle();
    boolean sameTitle = existingTitle != null && existingTitle.equalsIgnoreCase(normalizedTitle);
    return sameTitle
        && Objects.equals(existing.getDescription(), request.description())
        && Objects.equals(existing.getAssignee(), request.assignee())
        && Objects.equals(
            existing.getStatus(), request.status() != null ? request.status() : "PENDING")
        && Objects.equals(existing.getDueDate(), request.dueDate())
        && Objects.equals(existing.getSalesOrderId(), request.salesOrderId())
        && Objects.equals(existing.getPackagingSlipId(), request.packagingSlipId());
  }

  private boolean sameQuantity(double actual, Double expected) {
    return expected != null
        && canonicalizeQuantity(actual).compareTo(canonicalizeQuantity(expected.doubleValue()))
            == 0;
  }

  private BigDecimal canonicalizeQuantity(double value) {
    return BigDecimal.valueOf(value).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  private String normalizeNaturalKey(String naturalKey) {
    return StringUtils.hasText(naturalKey) ? naturalKey.trim() : null;
  }
}
