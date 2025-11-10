package com.bigbrightpaints.erp.modules.factory.controller;

import com.bigbrightpaints.erp.modules.factory.dto.*;
import com.bigbrightpaints.erp.modules.factory.service.FactoryService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/factory")
public class FactoryController {

    private final FactoryService factoryService;

    public FactoryController(FactoryService factoryService) {
        this.factoryService = factoryService;
    }

    /* Plans */
    @GetMapping("/production-plans")
    public ResponseEntity<ApiResponse<List<ProductionPlanDto>>> plans() {
        return ResponseEntity.ok(ApiResponse.success(factoryService.listPlans()));
    }

    @PostMapping("/production-plans")
    public ResponseEntity<ApiResponse<ProductionPlanDto>> createPlan(@Valid @RequestBody ProductionPlanRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Plan created", factoryService.createPlan(request)));
    }

    @PutMapping("/production-plans/{id}")
    public ResponseEntity<ApiResponse<ProductionPlanDto>> updatePlan(@PathVariable Long id,
                                                                      @Valid @RequestBody ProductionPlanRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Plan updated", factoryService.updatePlan(id, request)));
    }

    @PatchMapping("/production-plans/{id}/status")
    public ResponseEntity<ApiResponse<ProductionPlanDto>> updatePlanStatus(@PathVariable Long id,
                                                                            @RequestBody StatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Status updated", factoryService.updatePlanStatus(id, request.status())));
    }

    @DeleteMapping("/production-plans/{id}")
    public ResponseEntity<Void> deletePlan(@PathVariable Long id) {
        factoryService.deletePlan(id);
        return ResponseEntity.noContent().build();
    }

    public record StatusRequest(String status) {}

    /* Batches */
    @GetMapping("/production-batches")
    public ResponseEntity<ApiResponse<List<ProductionBatchDto>>> batches() {
        return ResponseEntity.ok(ApiResponse.success(factoryService.listBatches()));
    }

    @PostMapping("/production-batches")
    public ResponseEntity<ApiResponse<ProductionBatchDto>> logBatch(@RequestParam(required = false) Long planId,
                                                                    @Valid @RequestBody ProductionBatchRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Batch logged", factoryService.logBatch(planId, request)));
    }

    /* Tasks */
    @GetMapping("/tasks")
    public ResponseEntity<ApiResponse<List<FactoryTaskDto>>> tasks() {
        return ResponseEntity.ok(ApiResponse.success(factoryService.listTasks()));
    }

    @PostMapping("/tasks")
    public ResponseEntity<ApiResponse<FactoryTaskDto>> createTask(@Valid @RequestBody FactoryTaskRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Task created", factoryService.createTask(request)));
    }

    @PutMapping("/tasks/{id}")
    public ResponseEntity<ApiResponse<FactoryTaskDto>> updateTask(@PathVariable Long id,
                                                                   @Valid @RequestBody FactoryTaskRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Task updated", factoryService.updateTask(id, request)));
    }

    /* Dashboard */
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<FactoryDashboardDto>> dashboard() {
        return ResponseEntity.ok(ApiResponse.success(factoryService.dashboard()));
    }
}
