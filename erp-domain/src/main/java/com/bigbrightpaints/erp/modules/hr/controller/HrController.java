package com.bigbrightpaints.erp.modules.hr.controller;

import com.bigbrightpaints.erp.modules.hr.dto.*;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/hr")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
public class HrController {

    private final HrService hrService;
    private final CompanyContextService companyContextService;
    private final CompanyClock companyClock;

    public HrController(HrService hrService,
                        CompanyContextService companyContextService,
                        CompanyClock companyClock) {
        this.hrService = hrService;
        this.companyContextService = companyContextService;
        this.companyClock = companyClock;
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
    @Deprecated
    @Operation(deprecated = true)
    @GetMapping("/leave-requests")
    public ResponseEntity<ApiResponse<List<LeaveRequestDto>>> leaveRequests() {
        return ResponseEntity.ok(ApiResponse.success(hrService.listLeaveRequests()));
    }

    @Deprecated
    @Operation(deprecated = true)
    @PostMapping("/leave-requests")
    public ResponseEntity<ApiResponse<LeaveRequestDto>> createLeaveRequest(@Valid @RequestBody LeaveRequestRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Leave request created", hrService.createLeaveRequest(request)));
    }

    @Deprecated
    @Operation(deprecated = true)
    @PatchMapping("/leave-requests/{id}/status")
    public ResponseEntity<ApiResponse<LeaveRequestDto>> updateLeaveStatus(@PathVariable Long id,
                                                                           @RequestBody StatusRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Status updated", hrService.updateLeaveStatus(id, request.status())));
    }

    public record StatusRequest(String status) {}

    /* Attendance */
    @GetMapping("/attendance/date/{date}")
    public ResponseEntity<ApiResponse<List<AttendanceDto>>> attendanceByDate(
            @PathVariable @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(hrService.listAttendanceByDate(date)));
    }

    @GetMapping("/attendance/today")
    public ResponseEntity<ApiResponse<List<AttendanceDto>>> attendanceToday() {
        Company company = companyContextService.requireCurrentCompany();
        LocalDate today = companyClock.today(company);
        return ResponseEntity.ok(ApiResponse.success(hrService.listAttendanceByDate(today)));
    }

    @GetMapping("/attendance/summary")
    public ResponseEntity<ApiResponse<AttendanceSummaryDto>> attendanceSummary() {
        return ResponseEntity.ok(ApiResponse.success(hrService.getTodayAttendanceSummary()));
    }

    @GetMapping("/attendance/employee/{employeeId}")
    public ResponseEntity<ApiResponse<List<AttendanceDto>>> employeeAttendance(
            @PathVariable Long employeeId,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate) {
        return ResponseEntity.ok(ApiResponse.success(hrService.listEmployeeAttendance(employeeId, startDate, endDate)));
    }

    @PostMapping("/attendance/mark/{employeeId}")
    public ResponseEntity<ApiResponse<AttendanceDto>> markAttendance(
            @PathVariable Long employeeId,
            @Valid @RequestBody MarkAttendanceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Attendance marked", hrService.markAttendance(employeeId, request)));
    }

    @PostMapping("/attendance/bulk-mark")
    public ResponseEntity<ApiResponse<List<AttendanceDto>>> bulkMarkAttendance(
            @Valid @RequestBody BulkMarkAttendanceRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Attendance marked for " + request.employeeIds().size() + " employees",
                hrService.bulkMarkAttendance(request)));
    }

    /* Payroll */
    @GetMapping("/payroll-runs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> payrollRuns() {
        return legacyPayrollRunsGone();
    }

    @PostMapping("/payroll-runs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPayrollRun(@Valid @RequestBody PayrollRunRequest request) {
        return legacyPayrollRunsGone();
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> legacyPayrollRunsGone() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiResponse.failure(
                        "Legacy payroll runs endpoint is deprecated; use /api/v1/payroll/runs",
                        Map.of("canonicalPath", "/api/v1/payroll/runs")));
    }
}
