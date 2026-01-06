package com.bigbrightpaints.erp.modules.hr.controller;

import com.bigbrightpaints.erp.modules.hr.dto.*;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;

import java.util.List;

@RestController
@RequestMapping("/api/v1/hr")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_ACCOUNTING')")
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
        return ResponseEntity.ok(ApiResponse.success(hrService.listAttendanceByDate(java.time.LocalDate.now())));
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
    public ResponseEntity<ApiResponse<List<PayrollRunDto>>> payrollRuns() {
        return ResponseEntity.ok(ApiResponse.success(hrService.listPayrollRuns()));
    }

    @PostMapping("/payroll-runs")
    public ResponseEntity<ApiResponse<PayrollRunDto>> createPayrollRun(@Valid @RequestBody PayrollRunRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Payroll run created", hrService.createPayrollRun(request)));
    }
}
