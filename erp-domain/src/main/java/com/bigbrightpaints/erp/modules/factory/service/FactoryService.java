package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.exception.ApplicationException;
import com.bigbrightpaints.erp.core.exception.ErrorCode;
import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.*;
import com.bigbrightpaints.erp.modules.factory.dto.*;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class FactoryService {

    private final CompanyContextService companyContextService;
    private final ProductionPlanRepository planRepository;
    private final ProductionBatchRepository batchRepository;
    private final FactoryTaskRepository taskRepository;
    private final FinishedGoodsService finishedGoodsService;
    private final CompanyEntityLookup companyEntityLookup;
    private final Environment environment;
    private final boolean legacyBatchLoggingEnabled;
    private static final int QUANTITY_SCALE = 2;

    public FactoryService(CompanyContextService companyContextService,
                          ProductionPlanRepository planRepository,
                          ProductionBatchRepository batchRepository,
                          FactoryTaskRepository taskRepository,
                          FinishedGoodsService finishedGoodsService,
                          CompanyEntityLookup companyEntityLookup,
                          Environment environment,
                          @Value("${erp.factory.legacy-batch.enabled:false}") boolean legacyBatchLoggingEnabled) {
        this.companyContextService = companyContextService;
        this.planRepository = planRepository;
        this.batchRepository = batchRepository;
        this.taskRepository = taskRepository;
        this.finishedGoodsService = finishedGoodsService;
        this.companyEntityLookup = companyEntityLookup;
        this.environment = environment;
        this.legacyBatchLoggingEnabled = legacyBatchLoggingEnabled;
    }

    public List<ProductionPlanDto> listPlans() {
        Company company = companyContextService.requireCurrentCompany();
        return planRepository.findByCompanyOrderByPlannedDateDesc(company).stream().map(this::toDto).toList();
    }

    @Transactional
    public ProductionPlanDto createPlan(ProductionPlanRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String normalizedPlanNumber = normalizeNaturalKey(request.planNumber());
        if (normalizedPlanNumber != null) {
            return planRepository.findByCompanyAndPlanNumber(company, normalizedPlanNumber)
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
        return companyEntityLookup.requireProductionPlan(company, id);
    }

    private ProductionPlanDto toDto(ProductionPlan plan) {
        return new ProductionPlanDto(plan.getId(), plan.getPublicId(), plan.getPlanNumber(), plan.getProductName(),
                plan.getQuantity(), plan.getPlannedDate(), plan.getStatus(), plan.getNotes());
    }

    /* Batches */
    public List<ProductionBatchDto> listBatches() {
        Company company = companyContextService.requireCurrentCompany();
        return batchRepository.findByCompanyOrderByProducedAtDesc(company).stream().map(this::toDto).toList();
    }

    /**
     * @deprecated Use ProductionLogService#createLog for production receipts; this path is a lightweight
     *             batch logger and should not be called alongside production logs for the same batch.
     */
    @Deprecated
    @Transactional
    public ProductionBatchDto logBatch(Long planId, ProductionBatchRequest request) {
        assertLegacyBatchLoggingAllowed();
        Company company = companyContextService.requireCurrentCompany();
        String normalizedBatchNumber = normalizeNaturalKey(request.batchNumber());
        if (normalizedBatchNumber != null) {
            return batchRepository.findByCompanyAndBatchNumber(company, normalizedBatchNumber)
                    .map(existing -> resolveExistingBatch(existing, planId, request, normalizedBatchNumber))
                    .orElseGet(() -> createBatchWithNaturalKey(company, planId, request, normalizedBatchNumber));
        }
        ProductionPlan plan = planId != null ? requirePlan(planId) : null;
        return logBatchInternal(company, plan, request, null);
    }

    private ProductionBatchDto logBatchInternal(Company company,
                                                ProductionPlan plan,
                                                ProductionBatchRequest request,
                                                String normalizedBatchNumber) {
        ProductionBatch batch = new ProductionBatch();
        batch.setCompany(company);
        if (plan != null) {
            batch.setPlan(plan);
        }
        batch.setBatchNumber(normalizedBatchNumber != null ? normalizedBatchNumber : request.batchNumber());
        batch.setQuantityProduced(request.quantityProduced());
        batch.setLoggedBy(request.loggedBy());
        batch.setNotes(request.notes());
        ProductionBatch saved = batchRepository.save(batch);

        if (plan != null) {
            registerFinishedGoodsBatch(plan, saved);
        }
        return toDto(saved);
    }

    private void registerFinishedGoodsBatch(ProductionPlan plan, ProductionBatch batch) {
        FinishedGood finishedGood = finishedGoodsService.lockFinishedGoodByProductCode(plan.getProductName());
        FinishedGoodBatchRequest batchRequest = new FinishedGoodBatchRequest(
                finishedGood.getId(),
                batch.getBatchNumber(),
                BigDecimal.valueOf(batch.getQuantityProduced()),
                finishedGoodsService.currentWeightedAverageCost(finishedGood),
                batch.getProducedAt(),
                null
        );
        finishedGoodsService.registerBatch(batchRequest);
    }

    private ProductionBatchDto toDto(ProductionBatch batch) {
        return new ProductionBatchDto(batch.getId(), batch.getPublicId(), batch.getBatchNumber(), batch.getQuantityProduced(),
                batch.getProducedAt(), batch.getLoggedBy(), batch.getNotes());
    }

    /* Tasks */
    public List<FactoryTaskDto> listTasks() {
        Company company = companyContextService.requireCurrentCompany();
        return taskRepository.findByCompanyOrderByCreatedAtDesc(company).stream().map(this::toDto).toList();
    }

    @Transactional
    public FactoryTaskDto createTask(FactoryTaskRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        if (request.salesOrderId() != null && StringUtils.hasText(request.title())) {
            String normalized = request.title().trim();
            return taskRepository.findByCompanyAndSalesOrderIdAndTitleIgnoreCase(company, request.salesOrderId(), normalized)
                    .map(existing -> resolveExistingTask(existing, request, normalized))
                    .orElseGet(() -> createTaskInternal(company, request, normalized));
        }
        return createTaskInternal(company, request, null);
    }

    private FactoryTaskDto createTaskInternal(Company company, FactoryTaskRequest request, String normalizedTitle) {
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
        return new FactoryTaskDto(task.getId(), task.getPublicId(), task.getTitle(), task.getDescription(), task.getAssignee(),
                task.getStatus(), task.getDueDate(), task.getSalesOrderId(), task.getPackagingSlipId());
    }

    private FactoryTask requireTask(Long id) {
        Company company = companyContextService.requireCurrentCompany();
        return companyEntityLookup.requireFactoryTask(company, id);
    }

    /* Dashboard */
    public FactoryDashboardDto dashboard() {
        List<ProductionPlanDto> plans = listPlans();
        List<ProductionBatchDto> batches = listBatches();
        double efficiency = plans.isEmpty() ? 0 : (double) batches.size() / plans.size();
        return new FactoryDashboardDto(efficiency, plans.stream().filter(p -> "COMPLETED".equals(p.status())).count(),
                batches.size(), List.of());
    }

    private void assertLegacyBatchLoggingAllowed() {
        if (isProdProfile() && !legacyBatchLoggingEnabled) {
            throw new ApplicationException(ErrorCode.BUSINESS_CONSTRAINT_VIOLATION,
                    "Legacy production batch logging is disabled; use production logs.")
                    .withDetail("endpoint", "/api/v1/factory/production-batches")
                    .withDetail("canonicalPath", "/api/v1/factory/production/logs")
                    .withDetail("setting", "erp.factory.legacy-batch.enabled");
        }
    }

    private boolean isProdProfile() {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
    }

    private ProductionPlanDto createPlanWithNaturalKey(Company company,
                                                       ProductionPlanRequest request,
                                                       String normalizedPlanNumber) {
        int inserted = planRepository.insertIfAbsent(
                company.getId(),
                normalizedPlanNumber,
                request.productName(),
                request.quantity(),
                request.plannedDate(),
                request.notes()
        );
        ProductionPlan resolved = planRepository.findByCompanyAndPlanNumber(company, normalizedPlanNumber)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.CONCURRENCY_CONFLICT,
                        "Production plan insert/lookup race could not be resolved")
                        .withDetail("planNumber", normalizedPlanNumber));
        if (inserted == 0) {
            return resolveExistingPlan(resolved, request, normalizedPlanNumber);
        }
        return toDto(resolved);
    }

    private ProductionPlanDto resolveExistingPlan(ProductionPlan existing,
                                                  ProductionPlanRequest request,
                                                  String normalizedPlanNumber) {
        if (!isPlanPayloadEquivalent(existing, request, normalizedPlanNumber)) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Production plan already exists with different payload")
                    .withDetail("planNumber", normalizedPlanNumber);
        }
        return toDto(existing);
    }

    private boolean isPlanPayloadEquivalent(ProductionPlan existing,
                                            ProductionPlanRequest request,
                                            String normalizedPlanNumber) {
        return Objects.equals(existing.getPlanNumber(), normalizedPlanNumber)
                && Objects.equals(existing.getProductName(), request.productName())
                && sameQuantity(existing.getQuantity(), request.quantity())
                && Objects.equals(existing.getPlannedDate(), request.plannedDate())
                && Objects.equals(existing.getNotes(), request.notes());
    }

    private ProductionBatchDto createBatchWithNaturalKey(Company company,
                                                         Long planId,
                                                         ProductionBatchRequest request,
                                                         String normalizedBatchNumber) {
        ProductionPlan plan = planId != null ? companyEntityLookup.requireProductionPlan(company, planId) : null;
        Instant producedAt = CompanyTime.now(company);
        int inserted = batchRepository.insertIfAbsent(
                company.getId(),
                plan != null ? plan.getId() : null,
                normalizedBatchNumber,
                request.quantityProduced(),
                producedAt,
                request.loggedBy(),
                request.notes()
        );
        ProductionBatch resolved = batchRepository.findByCompanyAndBatchNumber(company, normalizedBatchNumber)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCode.CONCURRENCY_CONFLICT,
                        "Production batch insert/lookup race could not be resolved")
                        .withDetail("batchNumber", normalizedBatchNumber));
        if (inserted == 0) {
            return resolveExistingBatch(resolved, planId, request, normalizedBatchNumber);
        }
        if (plan != null) {
            registerFinishedGoodsBatch(plan, resolved);
        }
        return toDto(resolved);
    }

    private ProductionBatchDto resolveExistingBatch(ProductionBatch existing,
                                                    Long planId,
                                                    ProductionBatchRequest request,
                                                    String normalizedBatchNumber) {
        if (!isBatchPayloadEquivalent(existing, planId, request, normalizedBatchNumber)) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Production batch already exists with different payload")
                    .withDetail("batchNumber", normalizedBatchNumber);
        }
        return toDto(existing);
    }

    private boolean isBatchPayloadEquivalent(ProductionBatch existing,
                                             Long planId,
                                             ProductionBatchRequest request,
                                             String normalizedBatchNumber) {
        Long existingPlanId = existing.getPlan() != null ? existing.getPlan().getId() : null;
        return Objects.equals(existing.getBatchNumber(), normalizedBatchNumber)
                && Objects.equals(existingPlanId, planId)
                && sameQuantity(existing.getQuantityProduced(), request.quantityProduced())
                && Objects.equals(existing.getLoggedBy(), request.loggedBy())
                && Objects.equals(existing.getNotes(), request.notes());
    }

    private FactoryTaskDto resolveExistingTask(FactoryTask existing,
                                               FactoryTaskRequest request,
                                               String normalizedTitle) {
        if (!isTaskPayloadEquivalent(existing, request, normalizedTitle)) {
            throw new ApplicationException(ErrorCode.CONCURRENCY_CONFLICT,
                    "Factory task already exists with different payload")
                    .withDetail("salesOrderId", request.salesOrderId())
                    .withDetail("title", normalizedTitle);
        }
        return toDto(existing);
    }

    private boolean isTaskPayloadEquivalent(FactoryTask existing,
                                            FactoryTaskRequest request,
                                            String normalizedTitle) {
        String existingTitle = existing.getTitle();
        boolean sameTitle = existingTitle != null && existingTitle.equalsIgnoreCase(normalizedTitle);
        return sameTitle
                && Objects.equals(existing.getDescription(), request.description())
                && Objects.equals(existing.getAssignee(), request.assignee())
                && Objects.equals(existing.getStatus(), request.status() != null ? request.status() : "PENDING")
                && Objects.equals(existing.getDueDate(), request.dueDate())
                && Objects.equals(existing.getSalesOrderId(), request.salesOrderId())
                && Objects.equals(existing.getPackagingSlipId(), request.packagingSlipId());
    }

    private boolean sameQuantity(double actual, Double expected) {
        return expected != null
                && canonicalizeQuantity(actual).compareTo(canonicalizeQuantity(expected.doubleValue())) == 0;
    }

    private BigDecimal canonicalizeQuantity(double value) {
        return BigDecimal.valueOf(value).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    private String normalizeNaturalKey(String naturalKey) {
        return StringUtils.hasText(naturalKey) ? naturalKey.trim() : null;
    }
}
