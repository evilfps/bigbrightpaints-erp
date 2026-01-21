package com.bigbrightpaints.erp.modules.factory.service;

import com.bigbrightpaints.erp.core.util.CompanyEntityLookup;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.factory.domain.*;
import com.bigbrightpaints.erp.modules.factory.dto.*;
import com.bigbrightpaints.erp.modules.inventory.domain.FinishedGood;
import com.bigbrightpaints.erp.modules.inventory.dto.FinishedGoodBatchRequest;
import com.bigbrightpaints.erp.modules.inventory.service.FinishedGoodsService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

@Service
public class FactoryService {

    private final CompanyContextService companyContextService;
    private final ProductionPlanRepository planRepository;
    private final ProductionBatchRepository batchRepository;
    private final FactoryTaskRepository taskRepository;
    private final FinishedGoodsService finishedGoodsService;
    private final CompanyEntityLookup companyEntityLookup;

    public FactoryService(CompanyContextService companyContextService,
                          ProductionPlanRepository planRepository,
                          ProductionBatchRepository batchRepository,
                          FactoryTaskRepository taskRepository,
                          FinishedGoodsService finishedGoodsService,
                          CompanyEntityLookup companyEntityLookup) {
        this.companyContextService = companyContextService;
        this.planRepository = planRepository;
        this.batchRepository = batchRepository;
        this.taskRepository = taskRepository;
        this.finishedGoodsService = finishedGoodsService;
        this.companyEntityLookup = companyEntityLookup;
    }

    public List<ProductionPlanDto> listPlans() {
        Company company = companyContextService.requireCurrentCompany();
        return planRepository.findByCompanyOrderByPlannedDateDesc(company).stream().map(this::toDto).toList();
    }

    @Transactional
    public ProductionPlanDto createPlan(ProductionPlanRequest request) {
        Company company = companyContextService.requireCurrentCompany();
        String planNumber = request.planNumber();
        if (StringUtils.hasText(planNumber)) {
            String normalized = planNumber.trim();
            return planRepository.findByCompanyAndPlanNumber(company, normalized)
                    .map(this::toDto)
                    .orElseGet(() -> {
                        ProductionPlan plan = new ProductionPlan();
                        plan.setCompany(company);
                        plan.setPlanNumber(normalized);
                        plan.setProductName(request.productName());
                        plan.setQuantity(request.quantity());
                        plan.setPlannedDate(request.plannedDate());
                        plan.setNotes(request.notes());
                        return toDto(planRepository.save(plan));
                    });
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
        Company company = companyContextService.requireCurrentCompany();
        if (StringUtils.hasText(request.batchNumber())) {
            String normalized = request.batchNumber().trim();
            return batchRepository.findByCompanyAndBatchNumber(company, normalized)
                    .map(this::toDto)
                    .orElseGet(() -> logBatchInternal(company, planId, request, normalized));
        }
        return logBatchInternal(company, planId, request, null);
    }

    private ProductionBatchDto logBatchInternal(Company company, Long planId, ProductionBatchRequest request, String normalizedBatchNumber) {
        ProductionBatch batch = new ProductionBatch();
        batch.setCompany(company);
        ProductionPlan plan = planId != null ? requirePlan(planId) : null;
        if (plan != null) {
            batch.setPlan(plan);
        }
        batch.setBatchNumber(normalizedBatchNumber != null ? normalizedBatchNumber : request.batchNumber());
        batch.setQuantityProduced(request.quantityProduced());
        batch.setLoggedBy(request.loggedBy());
        batch.setNotes(request.notes());
        ProductionBatch saved = batchRepository.save(batch);

        if (plan != null) {
            FinishedGood finishedGood = finishedGoodsService.lockFinishedGoodByProductCode(plan.getProductName());
            FinishedGoodBatchRequest batchRequest = new FinishedGoodBatchRequest(
                    finishedGood.getId(),
                    saved.getBatchNumber(),
                    BigDecimal.valueOf(saved.getQuantityProduced()),
                    finishedGoodsService.currentWeightedAverageCost(finishedGood),
                    saved.getProducedAt(),
                    null
            );
            finishedGoodsService.registerBatch(batchRequest);
        }
        return toDto(saved);
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
                    .map(this::toDto)
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
}
