package com.bigbrightpaints.erp.modules.hr.controller;

import com.bigbrightpaints.erp.modules.hr.dto.*;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hr")
public class HrController {

    private final HrService hrService;

    public HrController(HrService hrService) {
        this.hrService = hrService;
    }

    /* Employees */
    @GetMapping("/employees")
    public ResponseEntity<ApiResponse<List<EmployeeDto>>> employees() {
        return ResponseEntity.ok(ApiResponse.success(hrService.listEmployees()));
    }

    @PostMapping("/employees")
    public ResponseEntity<ApiResponse<EmployeeDto>> createEmployee(@Valid @RequestBody EmployeeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Employee created", hrService.createEmployee(request)));
    }

    @PutMapping("/employees/{id}")
    public ResponseEntity<ApiResponse<EmployeeDto>> updateEmployee(@PathVariable Long id,
                                                                    @Valid @RequestBody EmployeeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Employee updated", hrService.updateEmployee(id, request)));
    }

    @DeleteMapping("/employees/{id}")
    public ResponseEntity<Void> deleteEmployee(@PathVariable Long id) {
        hrService.deleteEmployee(id);
        return ResponseEntity.noContent().build();
    }

    /* Leave Requests */
    @GetMapping("/leave-requests")
    public ResponseEntity<ApiResponse<List<LeaveRequestDto>>> leaveRequests() {
        return ResponseEntity.ok(ApiResponse.success(hrService.listLeaveRequests()));
    }

    @PostMapping("/leave-requests")
    public ResponseEntity<ApiResponse<LeaveRequestDto>> createLeaveRequest(@Valid @RequestBody LeaveRequestRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Leave request created", hrService.createLeaveRequest(request)));
    }

    @PatchMapping("/leave-requests/{id}/status")
    public ResponseEntity<ApiResponse<LeaveRequestDto>> updateLeaveStatus(@PathVariable Long id,
                                                                           @RequestBody StatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Status updated", hrService.updateLeaveStatus(id, request.status())));
    }

    public record StatusRequest(String status) {}

    /* Payroll */
    @GetMapping("/payroll-runs")
    public ResponseEntity<ApiResponse<List<PayrollRunDto>>> payrollRuns() {
        return ResponseEntity.ok(ApiResponse.success(hrService.listPayrollRuns()));
    }

    @PostMapping("/payroll-runs")
    public ResponseEntity<ApiResponse<PayrollRunDto>> createPayrollRun(@Valid @RequestBody PayrollRunRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Payroll run created", hrService.createPayrollRun(request)));
    }
}
