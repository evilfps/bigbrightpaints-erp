package com.bigbrightpaints.erp.modules.hr.controller;

import com.bigbrightpaints.erp.core.util.CompanyClock;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.service.CompanyContextService;
import com.bigbrightpaints.erp.modules.hr.dto.AttendanceBulkImportRequest;
import com.bigbrightpaints.erp.modules.hr.dto.AttendanceDto;
import com.bigbrightpaints.erp.modules.hr.dto.AttendanceSummaryDto;
import com.bigbrightpaints.erp.modules.hr.dto.BulkMarkAttendanceRequest;
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeDto;
import com.bigbrightpaints.erp.modules.hr.dto.EmployeeRequest;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveBalanceDto;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveRequestDto;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveRequestRequest;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveStatusUpdateRequest;
import com.bigbrightpaints.erp.modules.hr.dto.LeaveTypePolicyDto;
import com.bigbrightpaints.erp.modules.hr.dto.MarkAttendanceRequest;
import com.bigbrightpaints.erp.modules.hr.dto.MonthlyAttendanceSummaryDto;
import com.bigbrightpaints.erp.modules.hr.dto.SalaryStructureTemplateDto;
import com.bigbrightpaints.erp.modules.hr.dto.SalaryStructureTemplateRequest;
import com.bigbrightpaints.erp.modules.hr.service.HrService;
import com.bigbrightpaints.erp.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    /* Salary structure templates */
    @GetMapping("/salary-structures")
    public ResponseEntity<ApiResponse<List<SalaryStructureTemplateDto>>> listSalaryStructures() {
        return ResponseEntity.ok(ApiResponse.success("Salary structure templates", hrService.listSalaryStructureTemplates()));
    }

    @PostMapping("/salary-structures")
    public ResponseEntity<ApiResponse<SalaryStructureTemplateDto>> createSalaryStructure(
            @Valid @RequestBody SalaryStructureTemplateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Salary structure template created",
                hrService.createSalaryStructureTemplate(request)));
    }

    @PutMapping("/salary-structures/{id}")
    public ResponseEntity<ApiResponse<SalaryStructureTemplateDto>> updateSalaryStructure(
            @PathVariable Long id,
            @Valid @RequestBody SalaryStructureTemplateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Salary structure template updated",
                hrService.updateSalaryStructureTemplate(id, request)));
    }

    /* Leave Requests */
    @GetMapping("/leave-requests")
    public ResponseEntity<ApiResponse<List<LeaveRequestDto>>> leaveRequests() {
        return ResponseEntity.ok(ApiResponse.success(hrService.listLeaveRequests()));
    }

    @PostMapping("/leave-requests")
    public ResponseEntity<ApiResponse<LeaveRequestDto>> createLeaveRequest(
            @Valid @RequestBody LeaveRequestRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Leave request created", hrService.createLeaveRequest(request)));
    }

    @PatchMapping("/leave-requests/{id}/status")
    public ResponseEntity<ApiResponse<LeaveRequestDto>> updateLeaveStatus(
            @PathVariable Long id,
            @Valid @RequestBody LeaveStatusUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Status updated", hrService.updateLeaveStatus(id, request)));
    }

    @GetMapping("/leave-types")
    public ResponseEntity<ApiResponse<List<LeaveTypePolicyDto>>> leaveTypes() {
        return ResponseEntity.ok(ApiResponse.success("Leave types", hrService.listLeaveTypePolicies()));
    }

    @GetMapping("/employees/{employeeId}/leave-balances")
    public ResponseEntity<ApiResponse<List<LeaveBalanceDto>>> leaveBalances(
            @PathVariable Long employeeId,
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(ApiResponse.success("Leave balances", hrService.getLeaveBalances(employeeId, year)));
    }

    /* Attendance */
    @GetMapping("/attendance/date/{date}")
    public ResponseEntity<ApiResponse<List<AttendanceDto>>> attendanceByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
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

    @GetMapping("/attendance/summary/monthly")
    public ResponseEntity<ApiResponse<List<MonthlyAttendanceSummaryDto>>> monthlyAttendanceSummary(
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(ApiResponse.success("Monthly attendance summary",
                hrService.getMonthlyAttendanceSummary(year, month)));
    }

    @GetMapping("/attendance/employee/{employeeId}")
    public ResponseEntity<ApiResponse<List<AttendanceDto>>> employeeAttendance(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
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

    @PostMapping("/attendance/bulk-import")
    public ResponseEntity<ApiResponse<List<AttendanceDto>>> bulkImportAttendance(
            @Valid @RequestBody AttendanceBulkImportRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Attendance import completed",
                hrService.bulkImportAttendance(request)));
    }

    /* Payroll (legacy aliases) */
    @GetMapping("/payroll-runs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> payrollRuns() {
        return legacyPayrollRunsGone();
    }

    @PostMapping("/payroll-runs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPayrollRun() {
        return legacyPayrollRunsGone();
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> legacyPayrollRunsGone() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiResponse.failure(
                        "Legacy payroll runs endpoint is deprecated; use /api/v1/payroll/runs",
                        Map.of("canonicalPath", "/api/v1/payroll/runs")));
    }
}
